import makeWASocket, { Browsers, DisconnectReason, useMultiFileAuthState } from '@whiskeysockets/baileys';
import pino from 'pino';
import { existsSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { rm } from 'node:fs/promises';
import { join } from 'node:path';
import { RequestError } from './protocol.mjs';

export class BaileysAdapter {
  constructor(directory, runtime = {}) {
    this.directory = directory;
    this.authDirectory = join(directory, 'session');
    this.numberFile = join(directory, 'linked-number.json');
    this.state = 'idle'; this.phone = null; this.code = null; this.socket = null;
    this.stopped = true; this.generation = 0; this.retryCount = 0; this.retryTimer = null;
    this.ack = () => {}; this.loading = null; this.errorCode = null; this.pairingTimer = null;
    this.makeSocket = runtime.makeSocket || makeWASocket;
    this.loadAuth = runtime.loadAuth || useMultiFileAuthState;
    this.socketOptions = runtime.socketOptions || {};
    this.pendingSave = Promise.resolve();
    if (existsSync(this.numberFile)) this.phone = JSON.parse(readFileSync(this.numberFile, 'utf8')).phone;
  }
  onAck(callback) { this.ack = callback; }
  status() { return { state: this.state, phone: this.phone, pairingCode: this.code, errorCode: this.errorCode }; }

  async restore() {
    if (!this.phone || !existsSync(join(this.authDirectory, 'creds.json'))) return;
    const creds = JSON.parse(readFileSync(join(this.authDirectory, 'creds.json'), 'utf8'));
    if (creds.registered) await this.connect(this.phone);
  }

  async connect(phone) {
    if (this.phone && this.phone !== phone) throw new RequestError(409, 'ألغِ ربط المساعد قبل اختيار رقم واتساب آخر.');
    if (['connecting', 'connected'].includes(this.state)) return;
    if (this.state === 'pairing') await this.stop();
    this.phone = phone; this.stopped = false; this.retryCount = 0;
    writeFileSync(this.numberFile, JSON.stringify({ phone }), { mode: 0o600 });
    await this.startSocket();
  }

  async startSocket() {
    if (this.loading) return this.loading;
    this.loading = this.createSocket().catch(error => { this.state = 'error'; this.errorCode = 'socket_error'; throw error; }).finally(() => { this.loading = null; });
    return this.loading;
  }

  async createSocket() {
    const generation = ++this.generation;
    this.state = 'connecting'; this.code = null; this.errorCode = null;
    clearTimeout(this.pairingTimer);
    mkdirSync(this.authDirectory, { recursive: true, mode: 0o700 });
    const { state, saveCreds } = await this.loadAuth(this.authDirectory);
    if (this.stopped || generation !== this.generation) return;
    const socket = this.makeSocket({
      auth: state, browser: Browsers.ubuntu('Chrome'), logger: pino({ level: 'silent' }),
      markOnlineOnConnect: false, syncFullHistory: false, shouldSyncHistoryMessage: () => false,
      connectTimeoutMs: 25_000, defaultQueryTimeoutMs: 25_000, ...this.socketOptions
    });
    this.socket = socket;
    if (!state.creds.registered) {
      this.pairingTimer = setTimeout(() => {
        if (generation !== this.generation || this.stopped || this.state !== 'connecting') return;
        this.state = 'error'; this.errorCode = 'pairing_timeout'; this.code = null;
        this.socket = null; ++this.generation;
        void Promise.resolve(socket.end(new Error('Pairing readiness timed out'))).catch(() => {});
      }, 45_000);
      this.pairingTimer.unref?.();
    }
    let requestingCode = false;
    socket.ev.on('creds.update', () => {
      this.pendingSave = this.pendingSave.then(() => {
        if (generation === this.generation && !this.stopped) return saveCreds();
      }).catch(() => { if (generation === this.generation) { this.state = 'error'; this.errorCode = 'session_storage'; } });
    });
    socket.ev.on('messages.update', updates => {
      if (generation !== this.generation || this.stopped) return;
      for (const { key, update } of updates) {
        if (key.fromMe && key.id && Number.isInteger(update.status)) this.ack(key.id, update.status);
      }
    });
    socket.ev.on('connection.update', update => {
      if (generation !== this.generation || this.stopped) return;
      if (update.qr && !state.creds.registered && !requestingCode) {
        requestingCode = true;
        socket.requestPairingCode(this.phone).then(code => {
          if (generation === this.generation && !this.stopped && this.socket === socket) {
            clearTimeout(this.pairingTimer);
            this.code = code; this.state = 'pairing'; this.errorCode = null;
          }
        }).catch(() => { if (generation === this.generation && this.socket === socket) {
          this.state = 'error'; this.errorCode = 'pairing_rejected';
        } });
      }
      if (update.connection === 'open') {
        clearTimeout(this.pairingTimer);
        this.code = null; this.state = 'connected'; this.retryCount = 0; this.errorCode = null;
        writeFileSync(this.numberFile, JSON.stringify({ phone: this.phone }), { mode: 0o600 });
      }
      if (update.connection === 'close') {
        clearTimeout(this.pairingTimer);
        this.code = null; this.socket = null;
        const reason = update.lastDisconnect?.error?.output?.statusCode;
        if (reason === DisconnectReason.loggedOut) { this.state = 'logged_out'; this.errorCode = 'pairing_rejected'; return; }
        this.state = 'disconnected'; this.errorCode = 'network_error';
        // Pairing restart (515) is expected; network recovery is bounded and never sends a test.
        const retryable = [DisconnectReason.connectionClosed, DisconnectReason.connectionLost,
          DisconnectReason.unavailableService].includes(reason);
        if (reason === DisconnectReason.restartRequired || (retryable && this.retryCount < (state.creds.registered ? 5 : 2))) {
          const delay = reason === DisconnectReason.restartRequired ? 0 : Math.min(30_000, 2_000 * 2 ** this.retryCount++);
          this.state = 'connecting';
          clearTimeout(this.retryTimer);
          this.retryTimer = setTimeout(() => {
            void this.pendingSave.then(() => {
              if (!this.stopped && generation === this.generation) return this.startSocket();
            }).catch(() => { this.state = 'error'; this.errorCode = 'socket_error'; });
          }, delay);
          this.retryTimer.unref?.();
        }
      }
    });
  }

  async sendTest(phone, image, thumbnail, caption) {
    const socket = this.socket;
    if (this.state !== 'connected' || !socket) throw new RequestError(409, 'المساعد غير متصل.');
    const [registered] = await socket.onWhatsApp(`${phone}@s.whatsapp.net`);
    if (!registered?.exists) throw new RequestError(404, 'رقم الاختبار غير مسجل في واتساب.');
    if (socket !== this.socket || this.state !== 'connected') throw new Error('Connection changed');
    const message = await socket.sendMessage(registered.jid, {
      image, caption, jpegThumbnail: thumbnail, mimetype: 'image/jpeg'
    });
    return message?.key?.id;
  }

  async stop() {
    this.stopped = true; ++this.generation; clearTimeout(this.retryTimer); clearTimeout(this.pairingTimer);
    await this.socket?.end(new Error('User stopped local trial'));
    this.socket = null; this.state = 'idle'; this.code = null; this.errorCode = null;
    await this.pendingSave;
  }

  async unlink() {
    this.stopped = true; ++this.generation; clearTimeout(this.retryTimer); clearTimeout(this.pairingTimer);
    if (this.socket && this.state === 'connected') await this.socket.logout();
    await this.socket?.end(new Error('User unlinked local trial'));
    this.socket = null;
    await this.pendingSave;
    await rm(this.authDirectory, { recursive: true, force: true });
    await rm(this.numberFile, { force: true });
    this.phone = null; this.code = null; this.state = 'idle'; this.errorCode = null;
  }
}

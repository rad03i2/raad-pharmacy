import { fork } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { RequestError } from './protocol.mjs';

/** Keep the authenticated HTTP service alive if the third-party socket fails. */
export class IsolatedWhatsappAdapter {
  constructor(directory, runtime = {}) {
    this.directory = directory;
    this.fork = runtime.fork || fork;
    this.child = null; this.generation = 0; this.sequence = 0; this.pendingPhone = null;
    this.pending = new Map(); this.ready = null; this.readyReject = null; this.startupTimer = null; this.ack = () => {};
    this.snapshot = { state: 'idle', phone: null, pairingCode: null, errorCode: null };
  }
  onAck(callback) { this.ack = callback; }
  status() { return { ...this.snapshot }; }

  startWorker() {
    if (this.child) return this.ready;
    const generation = ++this.generation;
    this.snapshot = { ...this.snapshot, state: 'starting', pairingCode: null, errorCode: null };
    this.ready = new Promise((resolve, reject) => {
      this.readyReject = reject;
      const child = this.fork(join(dirname(fileURLToPath(import.meta.url)), 'socket-worker.mjs'), [this.directory],
        { stdio: ['ignore', 'ignore', 'pipe', 'ipc'] });
      this.child = child;
      // Classify only known runtime failures. Never retain or display SDK logs,
      // which may contain session material, phone numbers or pairing data.
      let runtimeFailure = null;
      child.stderr?.on('data', chunk => {
        const text = chunk.toString();
        if (/ERR_MODULE_NOT_FOUND|MODULE_NOT_FOUND|Cannot find (?:package|module)/.test(text)) runtimeFailure = 'runtime_incomplete';
      });
      const startup = setTimeout(() => this.failWorker('runtime_start_failed'), 15_000);
      this.startupTimer = startup;
      startup.unref?.();
      const failed = () => { clearTimeout(startup); reject(new RequestError(503, 'تعذر تشغيل اتصال واتساب. أعد فحص المساعد.')); };
      child.on('message', message => {
        if (generation !== this.generation) return;
        if (message?.type === 'ready') {
          clearTimeout(startup);
          this.readyReject = null;
          if (this.snapshot.state === 'starting') this.snapshot = this.pendingPhone
            ? { ...message.status, phone: this.pendingPhone, state: 'connecting' } : message.status;
          resolve();
        } else if (message?.type === 'status') {
          this.snapshot = message.status;
        } else if (message?.type === 'ack') {
          this.ack(message.id, message.ack);
        } else if (message?.type === 'result') {
          const pending = this.pending.get(message.id);
          if (!pending) return;
          clearTimeout(pending.timer); this.pending.delete(message.id);
          if (message.error) pending.reject(new RequestError(message.error.status, message.error.message));
          else pending.resolve(message.value);
        }
      });
      child.once('error', () => { if (generation === this.generation) { failed(); this.failWorker('runtime_start_failed'); } });
      child.once('exit', (_code, signal) => { if (generation === this.generation) {
        failed(); this.failWorker(signal === 'SIGKILL' ? 'runtime_killed' : runtimeFailure || 'runtime_exit');
      } });
    });
    return this.ready;
  }

  async call(command, payload = {}, timeout = 15_000) {
    await this.startWorker();
    const child = this.child;
    if (!child?.connected) throw new RequestError(503, 'تعذر الاتصال بمحرك واتساب.');
    const id = ++this.sequence;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => this.failWorker('request_timeout'), timeout);
      timer.unref?.();
      this.pending.set(id, { resolve, reject, timer });
      child.send({ id, command, ...payload }, error => { if (error) this.failWorker('runtime_exit'); });
    });
  }

  failWorker(errorCode) {
    clearTimeout(this.startupTimer);
    this.readyReject?.(new RequestError(503, 'تعذر تشغيل اتصال واتساب.'));
    this.readyReject = null;
    ++this.generation;
    this.child?.kill(); this.child = null; this.ready = null;
    this.snapshot = { ...this.snapshot, state: 'error', pairingCode: null, errorCode };
    for (const { reject, timer } of this.pending.values()) {
      clearTimeout(timer); reject(new RequestError(503, 'توقف اتصال واتساب؛ الخدمة المحلية تعمل. اطلب الاتصال مجددًا.'));
    }
    this.pending.clear();
  }

  async restore() {
    try { await this.call('restore'); }
    catch { /* An unavailable socket must not terminate the local HTTP service. */ }
  }

  async connect(phone) {
    if (this.snapshot.phone && this.snapshot.phone !== phone) throw new RequestError(409, 'ألغِ ربط المساعد قبل اختيار رقم آخر.');
    if (['connecting', 'connected', 'starting'].includes(this.snapshot.state)) return;
    this.snapshot = { ...this.snapshot, state: 'connecting', phone, pairingCode: null, errorCode: null };
    this.pendingPhone = phone;
    // Acknowledge locally before loading keys or opening the remote websocket.
    // /status remains responsive throughout pairing, including slow phones.
    void this.call('connect', { phone }).catch(() => {
      if (this.snapshot.state !== 'idle') this.snapshot = { ...this.snapshot, state: 'error', pairingCode: null, errorCode: this.snapshot.errorCode || 'socket_error' };
    }).finally(() => { this.pendingPhone = null; });
  }

  async sendTest(phone, image, thumbnail, caption) {
    if (this.snapshot.state !== 'connected') throw new RequestError(409, 'المساعد غير متصل.');
    return this.call('send', { phone, image: image.toString('base64'), thumbnail: thumbnail.toString('base64'), caption }, 65_000);
  }

  async stop() {
    try {
      if (this.readyReject) {
        this.readyReject(new RequestError(503, 'أُوقف بدء الاتصال.'));
        this.readyReject = null;
      } else if (this.child) await this.call('stop', {}, 3_000);
    }
    catch { /* Stopping a failed worker still stops the local service. */ }
    finally {
      clearTimeout(this.startupTimer);
      ++this.generation; this.child?.kill(); this.child = null; this.ready = null;
      for (const { reject, timer } of this.pending.values()) { clearTimeout(timer); reject(new RequestError(503, 'أُوقف المساعد.')); }
      this.pending.clear();
      this.snapshot = { ...this.snapshot, state: 'idle', pairingCode: null, errorCode: null };
    }
  }
  async unlink() {
    await this.call('unlink');
    await this.stop();
    this.snapshot = { state: 'idle', phone: null, pairingCode: null, errorCode: null };
  }
}

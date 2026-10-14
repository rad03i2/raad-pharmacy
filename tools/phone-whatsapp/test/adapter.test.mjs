import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { mkdtempSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { BaileysAdapter } from '../baileys-adapter.mjs';
import { prepareWAMessageMedia } from '@whiskeysockets/baileys';

async function fixture(t) {
  const directory = mkdtempSync(join(tmpdir(), 'raad-wa-adapter-'));
  const socket = {
    ev: new EventEmitter(), endCalls: 0, logoutCalls: 0, pairingCalls: 0, sendCalls: 0,
    end() { this.endCalls++; }, async logout() { this.logoutCalls++; },
    async requestPairingCode() { this.pairingCalls++; return '12345678'; },
    async sendMessage() { this.sendCalls++; return { key: { id: 'test-message-id' } }; },
    async onWhatsApp() { return [{ exists: true, jid: '9647701234567@s.whatsapp.net' }]; }
  };
  let saves = 0;
  const adapter = new BaileysAdapter(directory, {
    makeSocket: () => socket,
    loadAuth: async () => ({ state: { creds: { registered: false } }, saveCreds: async () => { saves++; } })
  });
  t.after(async () => { await adapter.stop(); rmSync(directory, { recursive: true, force: true }); });
  return { adapter, socket, saves: () => saves };
}

test('pairing waits for the QR/handshake event and does not send test messages', async t => {
  const f = await fixture(t);
  await f.adapter.connect('9647701234567');
  assert.equal(f.socket.pairingCalls, 0);
  f.socket.ev.emit('connection.update', { qr: 'not-rendered' });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(f.socket.pairingCalls, 1);
  assert.equal(f.adapter.status().pairingCode, '12345678');
  assert.equal(f.adapter.status().state, 'pairing');
  assert.equal(f.socket.sendCalls, 0);
});

test('a different account cannot replace the current binding silently', async t => {
  const f = await fixture(t);
  await f.adapter.connect('9647701234567');
  await assert.rejects(f.adapter.connect('9647709999999'));
  assert.equal(f.adapter.status().phone, '9647701234567');
  assert.equal(f.socket.sendCalls, 0);
});

test('stopping or unlinking an unregistered pairing does not call account logout or save late credentials', async t => {
  const f = await fixture(t);
  await f.adapter.connect('9647701234567');
  await f.adapter.unlink();
  f.socket.ev.emit('creds.update', {});
  f.socket.ev.emit('connection.update', { connection: 'open' });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(f.socket.logoutCalls, 0);
  assert.equal(f.saves(), 0);
  assert.equal(f.adapter.status().state, 'idle');
  assert.equal(f.adapter.status().phone, null);
});

test('the real SDK encrypts image/caption media without native image dependencies or a WhatsApp connection', async () => {
  const image = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 1, 2, 0xff, 0xd9]);
  let uploads = 0;
  const result = await prepareWAMessageMedia({ image, caption: 'تجربة الصورة والنص', jpegThumbnail: image, mimetype: 'image/jpeg' }, {
    upload: async () => { uploads++; return { mediaUrl: 'https://example.invalid/media', directPath: '/test' }; }
  });
  assert.equal(uploads, 1);
  assert.equal(result.imageMessage.caption, 'تجربة الصورة والنص');
  assert.equal(result.imageMessage.mimetype, 'image/jpeg');
  assert.equal(result.imageMessage.mediaKey.length, 32);
  assert.deepEqual(result.imageMessage.jpegThumbnail, image);
});

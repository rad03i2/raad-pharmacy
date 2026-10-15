import { Curve, initAuthCreds, proto } from '@whiskeysockets/baileys';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { mkdtemp, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { BaileysAdapter } from './baileys-adapter.mjs';
import { IsolatedWhatsappAdapter } from './isolated-adapter.mjs';
const pair = Curve.generateKeyPair();
if (pair.public.length !== 32 || pair.private.length !== 32 || !initAuthCreds().noiseKey) throw new Error('Crypto runtime unavailable');
const bytes = proto.Message.encode(proto.Message.create({ conversation: 'local runtime verification' })).finish();
if (!bytes.length) throw new Error('Protobuf runtime unavailable');
console.log('Baileys cryptography and message encoding loaded; no connection or message was sent.');

// Importing cryptography alone missed failures while constructing the real SDK.
// Exercise that path against this phone's loopback, never a WhatsApp endpoint.
const directory = await mkdtemp(join(tmpdir(), 'raad-wa-runtime-'));
let accepted;
const requestSeen = new Promise(resolve => { accepted = resolve; });
const local = createServer((_request, response) => { response.writeHead(426); response.end(); accepted(); });
await new Promise((resolve, reject) => { local.once('error', reject); local.listen(0, '127.0.0.1', resolve); });
const adapter = new BaileysAdapter(directory, { socketOptions: { waWebSocketUrl: `ws://127.0.0.1:${local.address().port}` } });
let timeout;
const isolated = new IsolatedWhatsappAdapter(directory);
try {
  await adapter.connect('9647700000000');
  await Promise.race([requestSeen, new Promise((_, reject) => { timeout = setTimeout(() => reject(new Error('Local socket startup timed out')), 5_000); })]);
  await adapter.stop();
  await isolated.restore();
  assert.equal(isolated.status().state, 'idle', 'The isolated WhatsApp runtime could not start');
  console.log('Real socket construction and isolated runtime verified locally; no WhatsApp account was contacted.');
} finally {
  clearTimeout(timeout);
  await adapter.stop(); await isolated.stop();
  local.closeAllConnections(); await new Promise(resolve => local.close(resolve));
  await rm(directory, { recursive: true, force: true });
}

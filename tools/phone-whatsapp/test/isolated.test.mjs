import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { IsolatedWhatsappAdapter } from '../isolated-adapter.mjs';
import { createTrialService } from '../service.mjs';
import { TrialStore } from '../store.mjs';

function fixture(t, handler) {
  const directory = mkdtempSync(join(tmpdir(), 'raad-wa-isolated-'));
  const children = [];
  const adapter = new IsolatedWhatsappAdapter(directory, { fork() {
    const child = new EventEmitter();
    child.stderr = new EventEmitter();
    child.connected = true; child.sent = [];
    child.kill = () => { child.connected = false; };
    child.send = (message, done) => {
      child.sent.push(message); done?.();
      if (handler) handler(child, message);
      else queueMicrotask(() => child.emit('message', { type: 'result', id: message.id }));
    };
    children.push(child);
    queueMicrotask(() => child.emit('message', { type: 'ready', status: { state: 'idle', phone: null, pairingCode: null } }));
    return child;
  } });
  t.after(async () => { await adapter.stop(); rmSync(directory, { recursive: true, force: true }); });
  return { adapter, children, directory };
}

test('requesting a code returns immediately even if the socket worker has not completed startup', async t => {
  const f = fixture(t, (child, message) => {
    if (message.command === 'stop') queueMicrotask(() => child.emit('message', { type: 'result', id: message.id }));
  });
  await f.adapter.connect('9647701234567');
  assert.equal(f.children.length, 1);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(f.children[0].sent[0].command, 'connect');
  assert.equal(f.adapter.status().phone, '9647701234567');
  assert.ok(['starting', 'idle', 'connecting'].includes(f.adapter.status().state));
  assert.equal(f.children[0].sent.filter(x => x.command === 'send').length, 0);
});

test('a socket crash leaves HTTP status available and an explicit retry starts a new worker', async t => {
  const f = fixture(t);
  await f.adapter.restore();
  const service = createTrialService({ token: 'a'.repeat(64), adapter: f.adapter,
    store: new TrialStore(join(f.directory, 'jobs.json')) });
  const address = await service.listen(0);
  t.after(() => service.close());
  f.children[0].emit('exit', 1);
  const response = await fetch(`http://127.0.0.1:${address.port}/status`, {
    headers: { Authorization: `Bearer ${'a'.repeat(64)}` }
  });
  assert.equal(response.status, 200);
  const status = await response.json();
  assert.equal(status.helperVersion, '3.3.22.9');
  assert.equal(status.state, 'error');
  assert.equal(status.errorCode, 'runtime_exit');
  await f.adapter.connect('9647701234567');
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(f.children.length, 2);
  assert.equal(f.children.flatMap(c => c.sent).filter(x => x.command === 'send').length, 0);
});

test('losing the socket after accepting a send keeps the journal uncertain and never resends it', async t => {
  const f = fixture(t, (child, message) => {
    if (message.command === 'send') queueMicrotask(() => child.emit('exit', 9));
    else queueMicrotask(() => child.emit('message', { type: 'result', id: message.id }));
  });
  await f.adapter.restore();
  f.children[0].emit('message', { type: 'status', status: { state: 'connected', phone: '9647701111111' } });
  const store = new TrialStore(join(f.directory, 'jobs.json'));
  const service = createTrialService({ token: 'b'.repeat(64), adapter: f.adapter, store });
  const address = await service.listen(0);
  t.after(() => service.close());
  const id = randomUUID();
  const media = Buffer.from([255, 216, 1, 2]).toString('base64');
  const options = { method: 'POST', headers: { Authorization: `Bearer ${'b'.repeat(64)}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ requestId: id, phone: '07701234567', imageBase64: media, thumbnailBase64: media }) };
  await fetch(`http://127.0.0.1:${address.port}/test`, options);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(store.get(id).state, 'uncertain');
  await fetch(`http://127.0.0.1:${address.port}/test`, options);
  assert.equal(f.children.flatMap(c => c.sent).filter(x => x.command === 'send').length, 1);
});

test('runtime diagnostics distinguish Android termination from missing files without exposing logs', async t => {
  const f = fixture(t);
  await f.adapter.restore();
  f.children[0].emit('exit', null, 'SIGKILL');
  assert.equal(f.adapter.status().errorCode, 'runtime_killed');
  await f.adapter.restore();
  f.children[1].stderr.emit('data', Buffer.from('Error [ERR_MODULE_NOT_FOUND] private-session-example'));
  f.children[1].emit('exit', 1, null);
  assert.equal(f.adapter.status().errorCode, 'runtime_incomplete');
  assert.equal(JSON.stringify(f.adapter.status()).includes('private-session-example'), false);
});

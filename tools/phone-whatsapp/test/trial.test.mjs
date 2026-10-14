import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdtempSync, rmSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createTrialService } from '../service.mjs';
import { TrialStore } from '../store.mjs';
import { PROTOCOL, TEST_CAPTION, iraqiPhone, testRequest } from '../protocol.mjs';

const token = 'a'.repeat(64);
const image = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 1, 2, 0xff, 0xd9]).toString('base64');
function payload() { return { requestId: randomUUID(), phone: '07701234567', imageBase64: image, thumbnailBase64: image }; }

class FakeAdapter {
  constructor() { this.state = 'idle'; this.phone = null; this.calls = []; this.callback = () => {}; }
  onAck(callback) { this.callback = callback; }
  status() { return { state: this.state, phone: this.phone, pairingCode: null }; }
  async connect(phone) { this.phone = phone; this.state = 'connected'; }
  async sendTest(phone, bytes, thumbnail, caption) {
    this.calls.push({ phone, bytes, thumbnail, caption });
    return `message-${this.calls.length}`;
  }
  async unlink() { this.phone = null; this.state = 'idle'; }
  async stop() { this.state = 'idle'; }
}

async function fixture(t, adapter = new FakeAdapter()) {
  const directory = mkdtempSync(join(tmpdir(), 'raad-wa-test-'));
  const file = join(directory, 'journal.json');
  const store = new TrialStore(file);
  const service = createTrialService({ token, adapter, store });
  const address = await service.listen(0);
  assert.equal(address.address, '127.0.0.1');
  const url = `http://127.0.0.1:${address.port}`;
  const request = async (path, body, extra = {}) => {
    const response = await fetch(url + path, {
      method: body === undefined ? 'GET' : 'POST',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', ...extra },
      body: body === undefined ? undefined : JSON.stringify(body)
    });
    return { status: response.status, body: await response.json() };
  };
  t.after(async () => { await service.close(); rmSync(directory, { recursive: true, force: true }); });
  return { adapter, store, file, request };
}

test('all supported Iraqi input formats route to the same ASCII number; malformed numbers are rejected', () => {
  for (const input of ['07701234567', '+9647701234567', '009647701234567', '٠٧٧٠١٢٣٤٥٦٧', '۰۰۹۶۴۷۷۰۱۲۳۴۵۶۷'])
    assert.equal(iraqiPhone(input), '9647701234567');
  for (const input of ['', '0770', '+15551234567', '00964770123456799']) assert.throws(() => iraqiPhone(input));
});

test('wrong tokens and browser-origin requests cannot inspect or send', async t => {
  const f = await fixture(t);
  assert.equal((await f.request('/status', undefined, { Authorization: 'Bearer wrong' })).status, 401);
  assert.equal((await f.request('/test', payload(), { Origin: 'https://example.org' })).status, 401);
  assert.equal(f.adapter.calls.length, 0);
});

test('startup, status checks and pairing never send a message; disconnected sends are rejected', async t => {
  const f = await fixture(t);
  assert.equal((await f.request('/test', payload())).status, 409);
  const status = await f.request('/status');
  assert.equal(status.body.protocol, PROTOCOL);
  assert.equal(status.body.state, 'idle');
  await f.request('/connect', { phone: '07701234567' });
  await f.request('/status');
  assert.equal(f.adapter.calls.length, 0);
});

test('repeated HTTP requests use one durable attempt and preserve its recipient, image and caption', async t => {
  const f = await fixture(t);
  await f.request('/connect', { phone: '07709999999' });
  const body = payload();
  assert.equal((await f.request('/test', body)).status, 202);
  assert.equal((await f.request('/test', body)).status, 200);
  assert.equal(f.adapter.calls.length, 1);
  assert.equal(f.adapter.calls[0].phone, '9647701234567');
  assert.equal(f.adapter.calls[0].caption, TEST_CAPTION);
  assert.deepEqual(f.adapter.calls[0].bytes, Buffer.from(image, 'base64'));
  const result = await f.request(`/jobs/${body.requestId}`);
  assert.equal(result.body.job.state, 'submitted');
  assert.equal(result.body.job.senderPhone, '9647709999999');
  assert.equal(result.body.job.phone, '9647701234567');
  assert.equal(result.body.job.digest, undefined);
  assert.equal(readFileSync(f.file, 'utf8').includes(image), false);
});

test('an existing request cannot silently change recipients', async t => {
  const f = await fixture(t);
  await f.request('/connect', { phone: '07709999999' });
  const body = payload();
  await f.request('/test', body);
  assert.equal((await f.request('/test', { ...body, phone: '07702222222' })).status, 409);
  assert.equal(f.adapter.calls.length, 1);
});

test('an ambiguous send error is retained and never automatically retried', async t => {
  const adapter = new FakeAdapter();
  adapter.sendTest = async () => { adapter.calls.push('accepted-but-response-lost'); throw new Error('network timeout'); };
  const f = await fixture(t, adapter);
  await f.request('/connect', { phone: '07709999999' });
  const body = payload();
  await f.request('/test', body);
  await f.request('/test', body);
  assert.equal((await f.request(`/jobs/${body.requestId}`)).body.job.state, 'uncertain');
  assert.equal(adapter.calls.length, 1);
});

test('pending attempts become uncertain after a restart and cannot be submitted again', () => {
  const dir = mkdtempSync(join(tmpdir(), 'raad-wa-restart-'));
  try {
    const file = join(dir, 'journal.json');
    const request = testRequest(payload());
    new TrialStore(file).begin(request);
    const restored = new TrialStore(file);
    const result = restored.begin(request);
    assert.equal(result.fresh, false);
    assert.equal(result.job.state, 'uncertain');
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('a send return value is not delivery proof; receipts advance without regressing', async t => {
  const f = await fixture(t);
  await f.request('/connect', { phone: '07709999999' });
  const body = payload();
  await f.request('/test', body);
  assert.equal((await f.request(`/jobs/${body.requestId}`)).body.job.state, 'submitted');
  f.adapter.callback('message-1', 3);
  f.adapter.callback('message-1', 2);
  assert.equal((await f.request(`/jobs/${body.requestId}`)).body.job.state, 'delivered');
  f.adapter.callback('message-1', 4);
  assert.equal((await f.request(`/jobs/${body.requestId}`)).body.job.state, 'read');
});

test('an early receipt is retained before the send promise returns its message identifier', async t => {
  const adapter = new FakeAdapter();
  adapter.sendTest = async () => { adapter.calls.push('one-send'); adapter.callback('early-id', 3); return 'early-id'; };
  const f = await fixture(t, adapter);
  await f.request('/connect', { phone: '07709999999' });
  const body = payload();
  await f.request('/test', body);
  assert.equal((await f.request(`/jobs/${body.requestId}`)).body.job.state, 'delivered');
});

test('a second distinct request cannot queue while an earlier send is in progress', async t => {
  const adapter = new FakeAdapter();
  let finish;
  adapter.sendTest = () => new Promise(resolve => { finish = resolve; });
  const f = await fixture(t, adapter);
  await f.request('/connect', { phone: '07709999999' });
  await f.request('/test', payload());
  assert.equal((await f.request('/test', payload())).status, 409);
  assert.equal((await f.request('/unlink', {})).status, 409);
  finish('single-id');
});

test('malformed media, ids and unsupported endpoints are rejected before any send', async t => {
  const f = await fixture(t);
  await f.request('/connect', { phone: '07709999999' });
  assert.equal((await f.request('/test', { ...payload(), imageBase64: Buffer.from('not a picture').toString('base64') })).status, 400);
  assert.equal((await f.request('/test', { ...payload(), requestId: '../config.json' })).status, 400);
  assert.equal((await f.request('/messages', payload())).status, 404);
  assert.equal(f.adapter.calls.length, 0);
});

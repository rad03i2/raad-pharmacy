import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createTrialService } from '../service.mjs';
import { TrialStore } from '../store.mjs';

const token = 'b'.repeat(64);
const photo = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 1, 2, 0xff, 0xd9]).toString('base64');
const data = () => ({ requestId: randomUUID(), phone: '07701234567',
  imageBase64: photo, thumbnailBase64: photo, caseKey: 'c'.repeat(64),
  caption: 'رسالة تذكير بموافقة المستلم', approved: true });

async function fixture(t) {
  const dir = mkdtempSync(join(tmpdir(), 'raad-auto-test-'));
  const sent = [];
  const adapter = {
    onAck(fn) { this.ack = fn; },
    status: () => ({ state: 'connected', phone: '9647709999999' }),
    sendTest: async (phone, image, thumbnail, caption) => {
      sent.push({ phone, caption }); return 'auto-' + sent.length;
    },
    stop: async () => {},
    unlink: async () => {},
    connect: async () => {}
  };
  const store = new TrialStore(join(dir, 'trial.json'));
  const autoStore = new TrialStore(join(dir, 'auto.json'), 5000);
  const svc = createTrialService({ token, adapter, store, autoStore });
  const address = await svc.listen(0);
  const request = async (path, body) => {
    const response = await fetch('http://127.0.0.1:' + address.port + path, {
      method: body ? 'POST' : 'GET',
      headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' },
      body: body ? JSON.stringify(body) : undefined
    });
    return { status: response.status, body: await response.json() };
  };
  t.after(async () => { await svc.close(); rmSync(dir, { recursive: true, force: true }); });
  return { request, sent, autoStore, store, adapter };
}

test('auto sends cannot be started without explicit consent and validated case', async t => {
  const f = await fixture(t);
  assert.equal((await f.request('/auto', { ...data(), approved: false })).status, 400);
  assert.equal((await f.request('/auto', { ...data(), caseKey: '../bad' })).status, 400);
  assert.equal((await f.request('/auto', { ...data(), caption: '' })).status, 400);
  assert.equal(f.sent.length, 0);
});

test('journal is durable before dispatch; duplicate request does not send twice', async t => {
  const f = await fixture(t);
  const body = data();
  assert.equal((await f.request('/auto', body)).status, 202);
  assert.equal((await f.request('/auto', body)).status, 200);
  assert.equal(f.sent.length, 1);
  assert.equal(f.sent[0].caption, body.caption);
  const job = (await f.request('/jobs/' + body.requestId)).body.job;
  assert.equal(job.phone, '9647701234567');
  assert.equal(job.state, 'submitted');
  assert.equal((await f.request('/auto', { ...body, caption: 'changed' })).status, 409);
  assert.equal(f.sent.length, 1);
  assert.equal(f.store.get(body.requestId), null);
});

test('restart treats half-completed attempts as uncertain, without resending', async t => {
  const f = await fixture(t);
  const body = data();
  f.autoStore.begin({ requestId: body.requestId, phone: '9647701234567',
    senderPhone: '9647709999999', digest: 'd'.repeat(64) });
  const restored = new TrialStore(f.autoStore.file, 5000);
  assert.equal(restored.get(body.requestId).state, 'uncertain');
});

test('automatic sending has a hard local daily cap', async t => {
  const f = await fixture(t);
  for (let i = 0; i < 20; i++) assert.equal((await f.request('/auto', data())).status, 202);
  assert.equal((await f.request('/auto', data())).status, 429);
  assert.equal(f.sent.length, 20);
});

test('status and pairing endpoints cannot send reminders', async t => {
  const f = await fixture(t);
  await f.request('/status');
  await f.request('/connect', { phone: '07709999999' });
  assert.equal(f.sent.length, 0);
});

test('WhatsApp delivery receipts update automatic journal but never affect trial journal', async t => {
  const f = await fixture(t);
  const body = data();
  assert.equal((await f.request('/auto', body)).status, 202);
  assert.equal(f.autoStore.get(body.requestId).state, 'submitted');
  const id = f.autoStore.get(body.requestId).messageId;
  f.adapter.ack(id, 3);
  assert.equal(f.autoStore.get(body.requestId).state, 'delivered');
  f.adapter.ack(id, 2);
  assert.equal(f.autoStore.get(body.requestId).state, 'delivered');
  // The trial journal must remain independent of automatic reminders.
  const status = await f.request('/jobs/' + body.requestId);
  assert.equal(status.body.job.messageId, id);
  assert.equal(f.store.get(body.requestId), null);
});

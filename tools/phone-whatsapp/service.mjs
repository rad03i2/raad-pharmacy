import { createServer } from 'node:http';
import { PROTOCOL, PORT, MAX_BODY_BYTES, JOB_ID, TEST_CAPTION, RequestError, authenticated, iraqiPhone, testRequest } from './protocol.mjs';

async function jsonBody(request) {
  if (request.headers['content-type']?.split(';')[0] !== 'application/json') {
    throw new RequestError(415, 'نوع الطلب غير صالح.');
  }
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > MAX_BODY_BYTES) throw new RequestError(413, 'صورة الاختبار أكبر من الحد المسموح.');
    chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); }
  catch { throw new RequestError(400, 'محتوى الطلب غير صالح.'); }
}

/** Only explicit /test requests can send. Startup, status, pairing and reconnection cannot. */
export function createTrialService({ token, adapter, store }) {
  if (!/^[a-f0-9]{64}$/.test(token)) throw new Error('Invalid local bridge token');
  let busy = false;
  const earlyAcks = new Map();
  adapter.onAck((id, ack) => {
    store.acknowledge(id, ack);
    earlyAcks.set(id, Math.max(earlyAcks.get(id) || 0, ack));
    if (earlyAcks.size > 100) earlyAcks.delete(earlyAcks.keys().next().value);
  });

  async function dispatch(request) {
    busy = true;
    try {
      const messageId = await adapter.sendTest(request.phone, request.image, request.thumbnail, TEST_CAPTION);
      if (!messageId || typeof messageId !== 'string') throw new Error('No message identifier');
      store.update(request.requestId, { state: 'submitted', messageId });
      store.acknowledge(messageId, earlyAcks.get(messageId));
    } catch (error) {
      // A network error may occur after WhatsApp accepted a message. Do not retry.
      store.update(request.requestId, { state: error instanceof RequestError && error.status === 404 ? 'not_registered' : 'uncertain' });
    } finally { busy = false; }
  }

  const server = createServer(async (request, response) => {
    response.setHeader('Content-Type', 'application/json; charset=utf-8');
    response.setHeader('Cache-Control', 'no-store');
    response.setHeader('X-Content-Type-Options', 'nosniff');
    const reply = (status, body) => { response.writeHead(status); response.end(JSON.stringify({ protocol: PROTOCOL, helperVersion: '3.3.22.8', ...body })); };
    try {
      // Reject browser-origin requests. No CORS; only the native app can use this API.
      if (request.headers.origin || !authenticated(request.headers.authorization, token)) {
        request.resume(); reply(401, { error: 'أعد نسخ أمر إعداد المساعد من هذا الهاتف.' }); return;
      }
      const path = request.url;
      if (request.method === 'GET' && path === '/status') {
        reply(200, { ...adapter.status(), busy }); return;
      }
      if (request.method === 'GET' && path?.startsWith('/jobs/')) {
        const id = path.slice('/jobs/'.length);
        if (!JOB_ID.test(id)) throw new RequestError(400, 'معرّف الاختبار غير صالح.');
        const job = store.get(id);
        if (!job) throw new RequestError(404, 'لم يسجل المساعد هذه المحاولة بعد.');
        reply(200, { job: store.publicJob(job) }); return;
      }
      if (request.method !== 'POST' || !['/connect', '/test', '/unlink', '/stop'].includes(path)) {
        request.resume(); throw new RequestError(404, 'المسار غير موجود.');
      }
      const body = await jsonBody(request);
      if (path === '/connect') {
        if (busy) throw new RequestError(409, 'انتظر نتيجة تجربة الإرسال أولًا.');
        await adapter.connect(iraqiPhone(body.phone));
        reply(202, adapter.status()); return;
      }
      if (path === '/test') {
        const test = testRequest(body);
        const previous = store.get(test.requestId);
        if (previous) {
          const { job } = store.begin(test);
          reply(200, { job: store.publicJob(job) }); return;
        }
        if (busy) throw new RequestError(409, 'هناك تجربة أخرى قيد الإرسال.');
        if (adapter.status().state !== 'connected') throw new RequestError(409, 'اربط واتساب بالمساعد أولًا.');
        const { job } = store.begin({ ...test, senderPhone: adapter.status().phone });
        // Dispatch exactly once, after the durable journal has accepted the request.
        void dispatch(test);
        reply(202, { job: store.publicJob(job) }); return;
      }
      if (busy) throw new RequestError(409, 'انتظر نتيجة تجربة الإرسال أولًا.');
      if (path === '/unlink') {
        await adapter.unlink(); reply(200, adapter.status()); return;
      }
      await adapter.stop();
      reply(200, adapter.status());
      server.close();
    } catch (error) {
      if (!response.headersSent) reply(error instanceof RequestError ? error.status : 500,
        { error: error instanceof RequestError ? error.message : 'تعذر تنفيذ الطلب. حاول بعد التحقق من اتصال المساعد.' });
      else response.end();
    }
  });
  server.requestTimeout = 10_000;
  server.headersTimeout = 5_000;
  server.keepAliveTimeout = 2_000;
  return {
    server,
    listen(port = PORT) { return new Promise((resolve, reject) => {
      server.once('error', reject);
      server.listen(port, '127.0.0.1', () => { server.removeListener('error', reject); resolve(server.address()); });
    }); },
    async close() { await adapter.stop(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
  };
}

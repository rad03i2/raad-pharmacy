import { BaileysAdapter } from './baileys-adapter.mjs';
import { RequestError } from './protocol.mjs';

const adapter = new BaileysAdapter(process.argv[2]);
const reply = message => { if (process.connected) process.send(message); };
adapter.onAck((id, ack) => reply({ type: 'ack', id, ack }));
reply({ type: 'ready', status: adapter.status() });
const statusTimer = setInterval(() => reply({ type: 'status', status: adapter.status() }), 1_000);
statusTimer.unref();
process.on('message', async message => {
  try {
    let value;
    switch (message.command) {
      case 'restore': value = await adapter.restore(); break;
      case 'connect': value = await adapter.connect(message.phone); break;
      case 'stop': value = await adapter.stop(); break;
      case 'unlink': value = await adapter.unlink(); break;
      case 'send': value = await adapter.sendTest(message.phone, Buffer.from(message.image, 'base64'),
        Buffer.from(message.thumbnail, 'base64'), message.caption); break;
      default: throw new RequestError(400, 'طلب غير معروف.');
    }
    reply({ type: 'status', status: adapter.status() });
    reply({ type: 'result', id: message.id, value });
  } catch (error) {
    reply({ type: 'status', status: adapter.status() });
    reply({ type: 'result', id: message.id, error: {
      status: error instanceof RequestError ? error.status : 503,
      message: error instanceof RequestError ? error.message : 'تعذر بدء اتصال واتساب. افحص الإنترنت ثم أعد طلب الربط.'
    } });
  }
});
process.on('disconnect', () => { clearInterval(statusTimer); void adapter.stop().finally(() => process.exit(0)); });

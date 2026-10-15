import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { IsolatedWhatsappAdapter } from './isolated-adapter.mjs';
import { TrialStore } from './store.mjs';
import { createTrialService } from './service.mjs';

const directory = dirname(fileURLToPath(import.meta.url));
const config = JSON.parse(readFileSync(join(directory, 'config.json'), 'utf8'));
const adapter = new IsolatedWhatsappAdapter(directory);
const store = new TrialStore(join(directory, 'trial-journal.json'));
const service = createTrialService({ token: config.token, adapter, store });
try {
  await service.listen();
  console.log('مساعد واتساب جاهز. ارجع إلى دفتر الصيدلية لإجراء الربط والتجربة.');
  await adapter.restore();
} catch {
  console.error('تعذر تشغيل المساعد. إذا كان يعمل بالفعل، ارجع إلى التطبيق واضغط فحص الاتصال.');
  await adapter.stop(); process.exitCode = 1; service.server.close();
}
for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, async () => {
  await service.close(); process.exit(0);
});

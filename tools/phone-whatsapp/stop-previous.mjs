import { existsSync, readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { PORT, PROTOCOL } from './protocol.mjs';
const file = join(dirname(fileURLToPath(import.meta.url)), 'config.json');
if (existsSync(file)) {
  const config = JSON.parse(readFileSync(file, 'utf8'));
  try {
    const response = await fetch(`http://127.0.0.1:${PORT}/stop`, {
      method: 'POST', headers: { 'Authorization': `Bearer ${config.token}`, 'Content-Type': 'application/json' },
      body: '{}', redirect: 'error', signal: AbortSignal.timeout(5_000)
    });
    const body = await response.json();
    if (!response.ok || body.protocol !== PROTOCOL) throw new Error('Previous helper still active');
    await new Promise(resolve => setTimeout(resolve, 500));
  } catch (error) {
    if (error.cause?.code !== 'ECONNREFUSED') {
      console.error('أوقف المساعد السابق بعد اكتمال تجربة الإرسال، ثم أعد أمر الإعداد.');
      process.exitCode = 1;
    }
  }
}

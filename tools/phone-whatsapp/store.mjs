import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import { ACK_STATE, RequestError, STATE_RANK } from './protocol.mjs';

/** Persist the request BEFORE contacting WhatsApp. An ambiguous result is never retried. */
export class TrialStore {
  constructor(file, limit = 100) {
    this.limit = limit;
    this.file = file;
    this.jobs = existsSync(file) ? JSON.parse(readFileSync(file, 'utf8')) : {};
    if (!this.jobs || typeof this.jobs !== 'object' || Array.isArray(this.jobs)) throw new Error('Invalid trial journal');
    for (const job of Object.values(this.jobs)) {
      if (job.state === 'submitting') job.state = 'uncertain';
    }
    this.flush();
  }
  flush() {
    mkdirSync(dirname(this.file), { recursive: true, mode: 0o700 });
    const temp = `${this.file}.tmp`;
    writeFileSync(temp, JSON.stringify(this.jobs), { mode: 0o600 });
    renameSync(temp, this.file);
  }
  get(id) { return this.jobs[id] || null; }
  begin(request) {
    const previous = this.get(request.requestId);
    if (previous) {
      if (previous.digest !== request.digest) throw new RequestError(409, 'لا يمكن تغيير محتوى اختبار سابق.');
      return { job: previous, fresh: false };
    }
    if (Object.keys(this.jobs).length >= this.limit) throw new RequestError(429, 'اكتملت سعة سجل الإرسال. راجع النتائج قبل المتابعة.');
    const job = {
      id: request.requestId, phone: request.phone, senderPhone: request.senderPhone, digest: request.digest,
      state: 'submitting', messageId: null, createdAt: Date.now(), updatedAt: Date.now()
    };
    this.jobs[job.id] = job;
    this.flush();
    return { job, fresh: true };
  }
  update(id, patch) {
    const job = this.get(id);
    if (!job) return;
    Object.assign(job, patch, { updatedAt: Date.now() });
    this.flush();
  }
  acknowledge(messageId, ack) {
    const state = ACK_STATE[ack];
    if (!state) return;
    const job = Object.values(this.jobs).find(item => item.messageId === messageId);
    if (job && (STATE_RANK[state] ?? -1) > (STATE_RANK[job.state] ?? -1)) this.update(job.id, { state });
  }
  publicJob(job) {
    return { id: job.id, phone: job.phone, senderPhone: job.senderPhone, state: job.state, messageId: job.messageId,
      createdAt: job.createdAt, updatedAt: job.updatedAt };
  }
}

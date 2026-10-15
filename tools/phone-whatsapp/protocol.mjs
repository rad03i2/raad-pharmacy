import { createHash, timingSafeEqual } from 'node:crypto';

export const PROTOCOL = 'raad-phone-whatsapp-trial-v1';
export const PORT = 39761;
export const TEST_CAPTION = 'تجربة مساعد واتساب لصيدلية رعد\nهذه صورة اختبار الربط والإرسال.';
export const MAX_BODY_BYTES = 300_000;
export const MAX_IMAGE_BYTES = 180_000;
export const JOB_ID = /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/;

export class RequestError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

export function authenticated(header, token) {
  if (!/^[a-f0-9]{64}$/.test(token)) return false;
  const expected = Buffer.from(`Bearer ${token}`);
  const actual = Buffer.from(typeof header === 'string' ? header : '');
  return actual.length === expected.length && timingSafeEqual(actual, expected);
}

export function iraqiPhone(raw) {
  if (typeof raw !== 'string' || raw.length > 80) throw new RequestError(400, 'رقم الهاتف غير صالح.');
  const digits = Array.from(raw).map(char => {
    const value = char.codePointAt(0);
    if (value >= 0x660 && value <= 0x669) return String(value - 0x660);
    if (value >= 0x6f0 && value <= 0x6f9) return String(value - 0x6f0);
    return /[0-9]/.test(char) ? char : '';
  }).join('');
  const phone = digits.startsWith('00') ? digits.slice(2)
    : digits.startsWith('964') ? digits
    : digits.startsWith('0') ? `964${digits.slice(1)}` : `964${digits}`;
  if (!/^9647[0-9]{9}$/.test(phone)) throw new RequestError(400, 'اكتب رقم موبايل عراقي صحيح.');
  return phone;
}

function decodeImage(value, maxBytes) {
  if (typeof value !== 'string' || value.length > Math.ceil(maxBytes / 3) * 4 ||
      value.length % 4 !== 0 || !/^[A-Za-z0-9+/]+={0,2}$/.test(value)) {
    throw new RequestError(400, 'صورة الاختبار غير صالحة.');
  }
  const bytes = Buffer.from(value, 'base64');
  if (!bytes.length || bytes.length > maxBytes || bytes.toString('base64') !== value) {
    throw new RequestError(400, 'صورة الاختبار غير صالحة.');
  }
  return bytes;
}

export function testRequest(body) {
  if (!body || !JOB_ID.test(body.requestId)) throw new RequestError(400, 'معرّف الاختبار غير صالح.');
  const phone = iraqiPhone(body.phone);
  const image = decodeImage(body.imageBase64, MAX_IMAGE_BYTES);
  const thumbnail = decodeImage(body.thumbnailBase64, 25_000);
  if (image[0] !== 0xff || image[1] !== 0xd8 || thumbnail[0] !== 0xff || thumbnail[1] !== 0xd8) {
    throw new RequestError(400, 'يجب أن تكون صورة الاختبار بصيغة JPEG.');
  }
  const digest = createHash('sha256').update(phone).update(image).update(thumbnail).digest('hex');
  return { requestId: body.requestId, phone, image, thumbnail, digest };
}

export const ACK_STATE = { 2: 'sent', 3: 'delivered', 4: 'read', 5: 'read' };
export const STATE_RANK = { submitting: 0, uncertain: 0, submitted: 1, sent: 2, delivered: 3, read: 4 };

/** Automatic reminders require an explicit per-customer approval from the native app. */
export function autoRequest(body) {
  if (body?.approved !== true || typeof body?.caseKey !== 'string' || !/^[a-f0-9]{64}$/.test(body.caseKey) ||
      typeof body?.caption !== 'string' || !body.caption.trim() || body.caption.length > 1800) {
    throw new RequestError(400, 'يجب اعتماد المستلم والنص وحالة الدين قبل إرسال التذكير.');
  }
  const media = testRequest(body);
  return { ...media, caseKey: body.caseKey, caption: body.caption,
    digest: createHash('sha256').update(media.digest).update(body.caseKey).update(body.caption).digest('hex') };
}

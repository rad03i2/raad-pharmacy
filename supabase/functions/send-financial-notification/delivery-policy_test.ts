import { deliveryAction } from './delivery-policy.ts';
function equals(actual: unknown, expected: unknown) {
  if (actual !== expected) throw new Error(`Expected ${expected}, got ${actual}`);
}
const receipt = {fcm_accepted_at:'2026-10-09T10:00:00Z',token_fingerprint:'token-a',delivery_mode:'data'};
const now = Date.parse(receipt.fcm_accepted_at);
Deno.test('live event sends immediately; v52 sends non-collapsible data and v51 stays native', () => {
  equals(deliveryAction(undefined,'token-a',52,now),'data');
  equals(deliveryAction(undefined,'token-a',51,now),'native');
});
Deno.test('FCM acceptance alone is not a display acknowledgement', () => {
  equals(deliveryAction(receipt,'token-a',52,now+59_000),'wait');
  equals(deliveryAction(receipt,'token-a',52,now+60_000),'native');
});
Deno.test('successful display stops fallback even if token rotates', () => {
  equals(deliveryAction({...receipt,device_displayed_at:'observed'},'token-b',52,now+120_000),'done');
});
Deno.test('native fallback is bounded to one accepted send', () => {
  equals(deliveryAction({...receipt,native_fallback_accepted_at:'accepted'},'token-a',52,now+120_000),'done');
});
Deno.test('token rotation retries the new registration; old native acceptance is not resent', () => {
  equals(deliveryAction(receipt,'token-b',52,now),'data');
  equals(deliveryAction({...receipt,delivery_mode:'native'},'token-a',51,now+120_000),'done');
});

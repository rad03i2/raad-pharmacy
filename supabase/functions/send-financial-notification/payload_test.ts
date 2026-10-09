import { buildFcmMessage, notificationText } from "./payload.ts";

const event = {
  id: "e98f828a-2e8f-45d5-9792-8e04c83cb594",
  pharmacy_id: "pharmacy", event_type: "DEBT_CREATED",
  actor_display_name: "رعد", customer_name: "محمود العواد",
  amount: 25000, balance_after: 75000,
};
function check(value: unknown, message: string) {
  if (!value) throw new Error(message);
}

Deno.test("3.3.17 provides Android-native notification with correct details", () => {
  const message = buildFcmMessage(event, "token", {
    app_version_code: 50, hide_notification_details: false,
  });
  if (!("notification" in message)) throw new Error("Native notification missing");
  check(message.notification.body.includes("محمود العواد"), "missing customer");
  check(message.notification?.body.includes("25,000"), "missing amount");
  check(message.notification?.body.includes("75,000"), "missing balance");
  check(message.data.native_display === "1", "must acknowledge native display");
  check(message.android.priority === "HIGH", "must use FCM high priority");
});

Deno.test("3.3.18 explicitly selects the installed iPhone alert channel and keeps event tags distinct", () => {
  const first = buildFcmMessage(event, "token", {app_version_code:52,hide_notification_details:false,native_fallback:true});
  if (!("notification" in first)) throw new Error("Native notification missing");
  check(first.android.notification.channel_id === 'raad_cloud_alerts_v7_iphone', 'wrong channel');
  check(first.android.notification.sound === 'iphone_notification_myinstants', 'wrong sound');
  const next = buildFcmMessage({...event,id:'second'}, "token", {app_version_code:52,hide_notification_details:false,native_fallback:true});
  if (!("notification" in next)) throw new Error("Native notification missing");
  check(first.android.notification.tag !== next.android.notification.tag, 'collapsed distinct operations');
  check(first.android.ttl === '604800s', 'offline queue TTL changed');
});

Deno.test("payment, full settlement, edit and deletion use authoritative event snapshots", () => {
  for (const [event_type, expected] of [
    ['PAYMENT_CREATED','تحصيل جديد'], ['TRANSACTION_UPDATED','تم تعديل حركة مالية'],
    ['TRANSACTION_DELETED','تم حذف حركة مالية']
  ]) {
    const text = notificationText({...event,event_type,transaction_type:'PAYMENT'});
    check(text.title.includes(expected), 'incorrect event title');
    check(text.body.includes('رعد') && text.body.includes('محمود العواد') && text.body.includes('25,000'), 'lost actual details');
  }
  const settled = notificationText({...event,event_type:'PAYMENT_CREATED',balance_after:0});
  check(settled.title === 'تم تسديد الحساب بالكامل', 'settlement incorrectly labelled');
  check(settled.body.includes('الرصيد الحالي: 0 د.ع'), 'zero balance missing');
});

Deno.test("missing balance is omitted and edited actor does not mutate the original event", () => {
  const original = {...event,actor_display_name:'أحمد',event_type:'TRANSACTION_UPDATED',balance_after:undefined};
  const message = buildFcmMessage(original,'token',{app_version_code:52,hide_notification_details:false,native_fallback:true});
  if (!("notification" in message)) throw new Error("Native notification missing");
  check(message.notification.body.includes('أحمد'), 'wrong editing actor');
  check(!message.notification.body.includes('الرصيد الحالي'), 'fabricated balance');
  check(event.actor_display_name === 'رعد', 'original creator was overwritten');
});

Deno.test('v52 preserves each offline financial event without native collapse', () => {
  for (let i=0;i<3;i++) {
    const message = buildFcmMessage({...event,id:`event-${i}`},'token',{app_version_code:52,hide_notification_details:false});
    check(!('notification' in message),'primary alert must not collapse');
    check(!('collapse_key' in message.android),'primary event must not have a collapse key');
    check(message.data.native_display === '0','wrong display owner');
    check(message.data.event_id === `event-${i}`,'event identity lost');
    check(message.data.customer_name === 'محمود العواد','self-contained details missing');
  }
});

Deno.test("all supported versions default to privacy-safe payloads without metadata", () => {
  for (const app_version_code of [0,46,47,49,50,51,52]) {
    const message = buildFcmMessage(event,'token',{app_version_code});
    const serial = JSON.stringify(message);
    check(!serial.includes('محمود') && !serial.includes('25000') && !serial.includes('25,000'), 'default privacy leak');
    check(message.data.privacy_redacted === '1', 'redaction marker missing');
  }
});

Deno.test("hidden device never receives financial details in either payload", () => {
  const message = buildFcmMessage(event, "token", {
    app_version_code: 50, hide_notification_details: true,
  });
  const serial = JSON.stringify(message);
  check(!serial.includes("محمود العواد"), "customer name was leaked");
  check(!serial.includes("25000") && !serial.includes("25,000"), "amount was leaked");
  check(message.data.privacy_redacted === "1", "privacy flag missing");
});

Deno.test("pre-3.3.17 modern devices keep the previous data-only behavior", () => {
  const message = buildFcmMessage(event, "token", {
    app_version_code: 49, hide_notification_details: false,
  });
  check(!("notification" in message), "legacy data-only behavior changed");
  check(message.data.native_display === "0", "data-only marker changed");
});

Deno.test("older and unknown devices retain safe native fallbacks", () => {
  for (const version of [0, 46]) {
    const message = buildFcmMessage(event, "token", {
      app_version_code: version, hide_notification_details: true,
    });
    const serial = JSON.stringify(message);
    check("notification" in message && message.data.native_display === "1",
      "native fallback missing");
    check(!serial.includes("محمود العواد"), "old-device private details leaked");
  }
});

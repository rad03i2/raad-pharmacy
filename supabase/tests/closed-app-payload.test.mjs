import test from "node:test";
import assert from "node:assert/strict";
import { buildFcmMessage } from "../functions/send-financial-notification/payload.ts";

const event = { id: "first", pharmacy_id: "pharmacy", event_type: "DEBT_CREATED",
  amount: 5000, customer_id: "customer", actor_display_name: "أحمد", message_body: "private message" };

test("closed Android app receives a visible notification and its routing data", () => {
  const message = buildFcmMessage(event, "test-token");
  assert.ok(message.notification.title && message.notification.body);
  assert.equal(message.android.priority, "HIGH");
  assert.equal(message.android.restricted_package_name, "com.radwan.raadpharmacy");
  assert.equal(message.data.event_id, event.id);
  assert.equal(message.data.customer_id, event.customer_id);
  assert.equal(message.data.native_display, "1");
  assert.equal(message.android.notification.channel_id, undefined);
  assert.equal(message.android.notification.sound, undefined);
});

test("visible notification keeps financial values and private messages off the lock screen", () => {
  for (const event_type of ["DEBT_CREATED", "PAYMENT_CREATED", "TEAM_MESSAGE", "TEAM_ALERT"]) {
    const message = buildFcmMessage({ ...event, event_type }, "test-token");
    const visible = JSON.stringify(message.notification);
    assert.ok(!visible.includes("5000") && !visible.includes("private message") && !visible.includes("أحمد"));
    assert.equal(message.android.notification.visibility, "PRIVATE");
    assert.equal(message.data.message_body, undefined);
    assert.equal(message.data.privacy_redacted, "1");
  }
});

test("each event keeps a distinct stable Android notification tag", () => {
  const first = buildFcmMessage(event, "test-token");
  assert.equal(first.android.notification.tag, "raad-event-first");
  assert.notEqual(first.android.notification.tag, buildFcmMessage({ ...event, id: "second" }, "test-token").android.notification.tag);
  assert.equal(first.android.notification.tag, buildFcmMessage(event, "test-token").android.notification.tag);
});

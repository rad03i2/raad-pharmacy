import { buildFcmMessage } from "./payload.ts";

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

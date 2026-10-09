/** Notification + data: Android displays the alert even without the app callback. */
export function buildFcmMessage(event: Record<string, unknown>, token: string) {
  const text = (key: string) => event[key] == null ? "" : String(event[key]);
  const bodies: Record<string, string> = {
    DEBT_CREATED: "تم تسجيل دين جديد في الصيدلية. افتح التطبيق للاطلاع عليه.",
    PAYMENT_CREATED: "تم تسجيل تحصيل جديد في الصيدلية. افتح التطبيق للاطلاع عليه.",
    TRANSACTION_UPDATED: "تم تعديل حركة في الصيدلية. افتح التطبيق للاطلاع عليها.",
    TRANSACTION_DELETED: "تم حذف حركة في الصيدلية. افتح التطبيق للاطلاع عليها.",
    TEAM_MESSAGE: "وصلت رسالة جديدة. افتح التطبيق لقراءتها.",
    TEAM_ALERT: "وصل تنبيه من أحد مستخدمي الصيدلية. افتح التطبيق للاطلاع عليه.",
  };
  const data: Record<string, string> = {};
  for (const key of ["id", "pharmacy_id", "actor_user_id", "actor_display_name", "actor_device_id",
    "event_type", "recipient_user_id", "customer_id", "transaction_id", "transaction_type",
    "created_at", "message_body", "message_read_at"]) data[key === "id" ? "event_id" : key] = text(key);
  data.amount = String(event.amount ?? 0);
  data.native_display = "1";
  return {
    token,
    data,
    // No amounts, customer names or message text: local privacy settings may be unknown to the server.
    notification: { title: "صيدلية رعد", body: bodies[text("event_type")] ?? "وصل تحديث مالي جديد. افتح التطبيق للاطلاع عليه." },
    android: {
      priority: "HIGH",
      ttl: "2419200s",
      restricted_package_name: "com.radwan.raadpharmacy",
      notification: {
        channel_id: "raad_cloud_alerts_v6_audible",
        sound: "pixabay_notification_037",
        icon: "ic_notification",
        tag: "raad-event-" + text("id"),
        notification_priority: "PRIORITY_MAX",
        visibility: "PRIVATE",
        default_vibrate_timings: true,
      },
    },
  };
}

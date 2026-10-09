/** Native Android display for v50+, data callbacks for v47-49; privacy defaults closed. */
export function notificationText(event: Record<string, unknown>) {
  const actor = String(event.actor_display_name || 'مستخدم آخر');
  const customer = String(event.customer_name || 'الزبون');
  const numeric = Number(event.amount);
  const amount = Number.isFinite(numeric) ? new Intl.NumberFormat('en-US', { maximumFractionDigits: 2 }).format(numeric) + ' د.ع' : '';
  const balance = event.balance_after == null ? null : Number(event.balance_after);
  const balanceText = balance != null && Number.isFinite(balance) ? new Intl.NumberFormat('en-US',{maximumFractionDigits:2}).format(balance)+' د.ع' : null;
  const suffix = balanceText == null ? '' : '\nالرصيد الحالي: '+balanceText;
  const kind = event.transaction_type === 'PAYMENT' ? 'تحصيل' : 'دين';
  switch (event.event_type) {
    case 'DEBT_CREATED': return {title: 'دين جديد — صيدلية رعد', body: `سجّل ${actor} ديناً بقيمة ${amount} على حساب ${customer}.${suffix}`};
    case 'PAYMENT_CREATED': if (balance === 0) return {title:'تم تسديد الحساب بالكامل',body:`سجّل ${actor} تسديداً كاملاً لحساب ${customer}.\nالمبلغ المسدد: ${amount}${suffix}`}; return {title: 'تحصيل جديد — صيدلية رعد', body: `سجّل ${actor} تحصيلاً بقيمة ${amount} من حساب ${customer}.${suffix}`};
    case 'TRANSACTION_UPDATED': return {title: 'تم تعديل حركة مالية', body: `عدّل ${actor} حركة ${kind} في حساب ${customer}.\nالمبلغ الجديد: ${amount}${suffix}`};
    case 'TRANSACTION_DELETED': return {title: 'تم حذف حركة مالية', body: `حذف ${actor} حركة ${kind} من حساب ${customer}.\nالقيمة: ${amount}${suffix}`};
    case 'TEAM_MESSAGE': return {title: `رسالة من ${actor}`, body: String(event.message_body || '')};
    case 'TEAM_ALERT': return {title: `تنبيه من ${actor}`, body: `${actor} يطلب انتباهك. افتح التطبيق للتواصل.`};
    default: return {title:'صيدلية رعد',body:'وصل تحديث جديد في الصيدلية.'};
  }
}
export function buildFcmMessage(event: Record<string, unknown>, token: string,
  device: { app_version_code?: number; hide_notification_details?: boolean; native_fallback?: boolean } = {}) {
  const text = (key: string) => event[key] == null ? '' : String(event[key]);
  const modern = (device.app_version_code ?? 0) >= 47;
  const hidden = device.hide_notification_details !== false;
  const generic = {title:'صيدلية رعد',body: text('event_type') === 'TEAM_MESSAGE'
    ? 'وصلت رسالة جديدة. افتح التطبيق لقراءتها.' : 'وصل تحديث مالي جديد. افتح التطبيق للاطلاع عليه.'};
  const data: Record<string, string> = {};
  for (const key of ['id','pharmacy_id','actor_user_id','actor_device_id','event_type','recipient_user_id',
    'customer_id','transaction_id','transaction_type','created_at','message_read_at'])
    data[key === 'id' ? 'event_id' : key] = text(key);
  // Never include customer, amount or message contents in a redacted FCM payload,
  // even for old APKs whose token metadata defaults to privacy-on.
  if (!hidden) {
    for (const key of ['actor_display_name','customer_name','message_body']) data[key] = text(key).slice(0,key === 'message_body' ? 500 : 128);
    data.amount = text('amount');
    if (event.balance_after != null) data.balance_after = text('balance_after');
  }
  data.privacy_redacted = hidden ? '1' : '0';
  // v50+ uses a native alert in background and local callback while in foreground.
  // Data-only FCM can be delayed on aggressive Android battery managers.
  const reliableV318 = (device.app_version_code ?? 0) >= 52;
  const nativeV317 = (device.app_version_code ?? 0) >= 50 && (!reliableV318 || device.native_fallback === true);
  data.native_display = !modern || nativeV317 ? '1' : '0';
  const base = {token, data, android: {priority: 'HIGH', ttl:'604800s',
    restricted_package_name:'com.radwan.raadpharmacy'}};
  if (modern && !nativeV317) return base;
  // Privacy is per-device. Older APKs and hidden-mode devices never expose details.
  const display = modern && !hidden ? notificationText(event) : generic;
  return {...base, notification:display, android:{...base.android,notification:{
    icon:'ic_notification',tag:'raad-event-'+text('id'),notification_priority:'PRIORITY_MAX',
    visibility:'PRIVATE',default_vibrate_timings:true,
    // Older APKs use their own manifest default. v52 already provisions this channel.
    ...((device.app_version_code ?? 0) >= 52 ? {
      channel_id:'raad_cloud_alerts_v7_iphone',sound:'iphone_notification_myinstants'
    } : {})}}};
}

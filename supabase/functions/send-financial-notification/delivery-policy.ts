export type DeliveryReceipt = {
  device_displayed_at?: string | null;
  fcm_accepted_at?: string | null;
  token_fingerprint?: string | null;
  delivery_mode?: string | null;
  native_fallback_accepted_at?: string | null;
};

/** No extra wait on a live event. Only an unacknowledged data send gets one native fallback. */
export function deliveryAction(receipt: DeliveryReceipt | undefined, fingerprint: string,
  version: number, now = Date.now()): 'data' | 'native' | 'wait' | 'done' {
  if (receipt?.device_displayed_at) return 'done';
  if (!receipt?.fcm_accepted_at || receipt.token_fingerprint !== fingerprint)
    return version >= 52 ? 'data' : 'native';
  if (receipt.delivery_mode !== 'data' || receipt.native_fallback_accepted_at) return 'done';
  return now - Date.parse(receipt.fcm_accepted_at) >= 60_000 ? 'native' : 'wait';
}

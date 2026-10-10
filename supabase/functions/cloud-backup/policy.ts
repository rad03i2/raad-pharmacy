export const REPOSITORY = "rad03i2/raad-pharmacy";
export const WORKFLOW = "cloud-backup.yml";

export function nextBackup(now: Date): string {
  const next = new Date(now);
  next.setUTCHours(Math.floor(now.getUTCHours()/6)*6+6,0,0,0);
  return next.toISOString();
}

export function health(configured: boolean, lastSuccess: string | null, now: Date): string {
  if (!configured) return "NOT_CONFIGURED";
  if (!lastSuccess) return "NO_VERIFIED_BACKUP";
  const elapsed = now.getTime() - Date.parse(lastSuccess);
  return !Number.isFinite(elapsed) || elapsed > 9*60*60_000 ? "STALE" : "RECENT_VERIFIED_FILES";
}

export function capability(isAdmin: boolean, configured: boolean, lastSuccess: string | null,
                           dispatchEnabled: boolean): boolean {
  return isAdmin && configured && !!lastSuccess && dispatchEnabled;
}

export function requestPayload(requestId: string) {
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(requestId))
    throw new Error("invalid_request_id");
  return {ref:"main",inputs:{request_id:requestId}};
}

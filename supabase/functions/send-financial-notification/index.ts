
import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.49.4";
import { buildFcmMessage } from "./payload.ts";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SUPABASE_ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY")!;
const SUPABASE_SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
console.info("Push configuration: firebase_service_account_present=" + Boolean(Deno.env.get("FIREBASE_SERVICE_ACCOUNT_JSON")));

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}
function b64url(input: Uint8Array | string): string {
  const bytes = typeof input === "string" ? new TextEncoder().encode(input) : input;
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}
function pemToBytes(pem: string): Uint8Array {
  const normalized = pem.replace(/-----BEGIN PRIVATE KEY-----/g, "").replace(/-----END PRIVATE KEY-----/g, "").replace(/\s+/g, "");
  return Uint8Array.from(atob(normalized), (c) => c.charCodeAt(0));
}
async function accessToken(serviceAccount: Record<string, string>): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const header = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = b64url(JSON.stringify({
    iss: serviceAccount.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: serviceAccount.token_uri || "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  }));
  const unsigned = header + "." + claims;
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToBytes(serviceAccount.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned));
  const assertion = unsigned + "." + b64url(new Uint8Array(signature));
  const response = await fetch(serviceAccount.token_uri || "https://oauth2.googleapis.com/token", {
    method: "POST",
    signal: AbortSignal.timeout(10_000),
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });
  const body = await response.json();
  if (!response.ok || !body.access_token) throw new Error("Google OAuth token exchange failed");
  return body.access_token;
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  const authorization = req.headers.get("authorization");
  if (!authorization?.startsWith("Bearer ")) return json({ error: "missing_authorization" }, 401);

  const userClient = createClient(SUPABASE_URL, SUPABASE_ANON_KEY, {
    global: { headers: { Authorization: authorization } },
    auth: { persistSession: false },
  });
  const admin = createClient(SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, {
    auth: { persistSession: false },
  });

  const { data: userData, error: userError } = await userClient.auth.getUser();
  if (userError || !userData.user) return json({ error: "invalid_user" }, 401);

  const payload = await req.json().catch(() => ({}));
  const transactionId = String(payload.transaction_id || "");
  const eventId = String(payload.event_id || "");
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  if ((!transactionId && !eventId) || (transactionId && !uuid.test(transactionId)) || (eventId && !uuid.test(eventId)))
    return json({ error: "invalid_event_identifier" }, 400);

  let eventQuery = userClient
    .from("notification_events").select("*")
    .eq("actor_user_id", userData.user.id)
    .is("push_dispatched_at", null)
    .order("created_at", { ascending: false })
    .limit(1);
  if (transactionId) eventQuery = eventQuery.eq("transaction_id", transactionId);
  if (eventId) eventQuery = eventQuery.eq("id", eventId);
  const { data: event, error: eventError } = await eventQuery.maybeSingle();

  if (eventError) return json({ error: "event_lookup_failed", detail: eventError.message }, 400);
  if (!event) return json({ ok: true, skipped: "no_pending_event" });

  const rawServiceAccount = Deno.env.get("FIREBASE_SERVICE_ACCOUNT_JSON");
  if (!rawServiceAccount) {
    console.error("FCM dispatch blocked: FIREBASE_SERVICE_ACCOUNT_JSON is not configured");
    await admin.from("notification_events").update({
      push_last_error: "FIREBASE_SERVICE_ACCOUNT_JSON is not configured",
    }).eq("id", event.id);
    return json({ error: "firebase_service_account_missing" }, 503);
  }

  let serviceAccount: Record<string, string>;
  try {
    serviceAccount = JSON.parse(rawServiceAccount);
    if (serviceAccount.type !== "service_account" || serviceAccount.project_id !== "raad-pharmacy" ||
        !serviceAccount.client_email || !serviceAccount.private_key) throw new Error("Invalid service account");
  }
  catch {
    await admin.from("notification_events").update({
      push_attempts: (event.push_attempts ?? 0) + 1,
      push_last_error: "FIREBASE_SERVICE_ACCOUNT_JSON is invalid JSON",
    }).eq("id", event.id);
    return json({ error: "firebase_service_account_invalid" }, 503);
  }

  // Only one sender may dispatch an event at a time. An interrupted lock expires.
  const { data: claimed, error: claimError } = await admin.from("notification_events")
    .update({ push_locked_until: new Date(Date.now() + 180_000).toISOString() })
    .eq("id", event.id).is("push_dispatched_at", null)
    .or("push_locked_until.is.null,push_locked_until.lt." + new Date().toISOString())
    .select("push_delivered_token_ids").maybeSingle();
  if (claimError) return json({ error: "event_claim_failed" }, 503);
  if (!claimed) return json({ error: "event_dispatch_busy" }, 503);
  const deliveredIds = new Set<string>(claimed.push_delivered_token_ids ?? []);

  let tokenQuery = admin.from("push_tokens")
    .select("id,token,device_id")
    .eq("pharmacy_id", event.pharmacy_id)
    .is("deleted_at", null)
    .neq("device_id", event.actor_device_id ?? "");
  if (event.event_type === "TEAM_ALERT" || event.event_type === "TEAM_MESSAGE") {
    if (!event.recipient_user_id) {
      await admin.from("notification_events").update({ push_locked_until: null }).eq("id", event.id);
      return json({ error: "invalid_alert_recipient" }, 400);
    }
    tokenQuery = tokenQuery.eq("user_id", event.recipient_user_id);
  }
  const { data: tokens, error: tokenError } = await tokenQuery;
  if (tokenError) {
    await admin.from("notification_events").update({ push_locked_until: null }).eq("id", event.id);
    return json({ error: "token_lookup_failed" }, 500);
  }

  if (!tokens?.length) {
    await admin.from("notification_events").update({
      push_locked_until: null,
      push_last_error: "No registered recipient device; keep event pending",
    }).eq("id", event.id);
    return json({ error: "recipient_device_not_registered" }, 503);
  }

  let oauth: string;
  try { oauth = await accessToken(serviceAccount); }
  catch (error) {
    await admin.from("notification_events").update({
      push_attempts: (event.push_attempts ?? 0) + 1,
      push_last_error: "Google OAuth token exchange failed",
      push_locked_until: null,
    }).eq("id", event.id);
    return json({ error: "google_oauth_failed" }, 503);
  }

  const fcmUrl = "https://fcm.googleapis.com/v1/projects/" + serviceAccount.project_id + "/messages:send";
  let sent = 0;
  const failures: string[] = [];

  const pendingTokens = tokens.filter((token) => !deliveredIds.has(String(token.id)));
  for (let offset = 0; offset < pendingTokens.length; offset += 8) {
    await Promise.all(pendingTokens.slice(offset, offset + 8).map(async (token) => {
    try {
    const response = await fetch(fcmUrl, {
      signal: AbortSignal.timeout(8_000),
      method: "POST",
      headers: { authorization: "Bearer " + oauth, "content-type": "application/json" },
      body: JSON.stringify({ message: buildFcmMessage(event, token.token) }),
    });
    if (response.ok) {
      sent++;
      deliveredIds.add(String(token.id));
    } else {
      const errorBody = await response.json().catch(() => ({}));
      const unregistered = errorBody?.error?.details?.some((d: { errorCode?: string }) => d.errorCode === "UNREGISTERED");
      if (unregistered) {
        const { error: retireError } = await admin.from("push_tokens")
          .update({ deleted_at: new Date().toISOString() }).eq("id", token.id).eq("token", token.token);
        if (retireError) failures.push("token_retirement_failed");
      } else failures.push("FCM HTTP " + response.status + ": " + String(errorBody?.error?.status ?? "SEND_FAILED"));
    }
    } catch { failures.push("FCM transport or delivery persistence failed"); }
    }));
    const { error: saveError } = await admin.from("notification_events")
      .update({ push_delivered_token_ids: Array.from(deliveredIds) }).eq("id", event.id);
    if (saveError) {
      failures.push("delivery_record_failed");
      break;
    }
  }

  await admin.from("notification_events").update({
    push_locked_until: null,
    push_dispatched_at: failures.length === 0 && deliveredIds.size > 0 ? new Date().toISOString() : null,
    push_attempts: (event.push_attempts ?? 0) + 1,
    push_last_error: failures.length ? failures.join(" | ").slice(0, 1000) : deliveredIds.size === 0 ? "No valid recipient device" : null,
  }).eq("id", event.id);

  return json({ ok: failures.length === 0 && deliveredIds.size > 0, sent, failed: failures.length }, failures.length || deliveredIds.size === 0 ? 503 : 200);
});


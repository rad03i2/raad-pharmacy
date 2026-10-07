
import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.49.4";

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
      push_attempts: (event.push_attempts ?? 0) + 1,
      push_last_error: "FIREBASE_SERVICE_ACCOUNT_JSON is not configured",
    }).eq("id", event.id);
    return json({ error: "firebase_service_account_missing" }, 503);
  }

  let serviceAccount: Record<string, string>;
  try { serviceAccount = JSON.parse(rawServiceAccount); }
  catch {
    await admin.from("notification_events").update({
      push_attempts: (event.push_attempts ?? 0) + 1,
      push_last_error: "FIREBASE_SERVICE_ACCOUNT_JSON is invalid JSON",
    }).eq("id", event.id);
    return json({ error: "firebase_service_account_invalid" }, 503);
  }

  let tokenQuery = admin.from("push_tokens")
    .select("id,token,device_id")
    .eq("pharmacy_id", event.pharmacy_id)
    .is("deleted_at", null)
    .neq("device_id", event.actor_device_id ?? "");
  if (event.event_type === "TEAM_ALERT") {
    if (!event.recipient_user_id) return json({ error: "invalid_alert_recipient" }, 400);
    tokenQuery = tokenQuery.eq("user_id", event.recipient_user_id);
  }
  const { data: tokens, error: tokenError } = await tokenQuery;
  if (tokenError) return json({ error: "token_lookup_failed", detail: tokenError.message }, 500);

  if (!tokens?.length) {
    await admin.from("notification_events").update({
      push_dispatched_at: new Date().toISOString(),
      push_attempts: (event.push_attempts ?? 0) + 1,
      push_last_error: null,
    }).eq("id", event.id);
    return json({ ok: true, sent: 0 });
  }

  let oauth: string;
  try { oauth = await accessToken(serviceAccount); }
  catch (error) {
    await admin.from("notification_events").update({
      push_attempts: (event.push_attempts ?? 0) + 1,
      push_last_error: String(error).slice(0, 1000),
    }).eq("id", event.id);
    return json({ error: "google_oauth_failed" }, 503);
  }

  const fcmUrl = "https://fcm.googleapis.com/v1/projects/" + serviceAccount.project_id + "/messages:send";
  let sent = 0;
  const failures: string[] = [];

  for (const token of tokens) {
    const data: Record<string, string> = {
      event_id: String(event.id),
      pharmacy_id: String(event.pharmacy_id),
      actor_user_id: event.actor_user_id ? String(event.actor_user_id) : "",
      actor_display_name: event.actor_display_name ? String(event.actor_display_name) : "",
      actor_device_id: event.actor_device_id ? String(event.actor_device_id) : "",
      event_type: String(event.event_type),
      recipient_user_id: event.recipient_user_id ? String(event.recipient_user_id) : "",
      customer_id: event.customer_id ? String(event.customer_id) : "",
      transaction_id: event.transaction_id ? String(event.transaction_id) : "",
      amount: String(event.amount ?? 0),
      transaction_type: event.transaction_type ? String(event.transaction_type) : "",
      created_at: String(event.created_at),
    };
    const response = await fetch(fcmUrl, {
      method: "POST",
      headers: { authorization: "Bearer " + oauth, "content-type": "application/json" },
      body: JSON.stringify({
        message: {
          token: token.token,
          data,
          android: { priority: "HIGH", ttl: "2419200s", restricted_package_name: "com.radwan.raadpharmacy" },
        },
      }),
    });
    if (response.ok) sent++;
    else failures.push(String(response.status) + ":" + (await response.text()).slice(0, 300));
  }

  await admin.from("notification_events").update({
    push_dispatched_at: failures.length === 0 ? new Date().toISOString() : null,
    push_attempts: (event.push_attempts ?? 0) + 1,
    push_last_error: failures.length ? failures.join(" | ").slice(0, 1000) : null,
  }).eq("id", event.id);

  return json({ ok: failures.length === 0, sent, failed: failures.length }, failures.length ? 207 : 200);
});


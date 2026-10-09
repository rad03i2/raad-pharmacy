
import { createClient, type SupabaseClient } from "npm:@supabase/supabase-js@2.49.4";
import { buildFcmMessage } from "./payload.ts";
import { deliveryAction } from "./delivery-policy.ts";

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
function pemToBytes(pem: string): Uint8Array<ArrayBuffer> {
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

type Event = Record<string, any>;
let oauthCache: { token: string; expires: number } | undefined;
let oauthPromise: Promise<string> | undefined;
async function cachedAccessToken(account: Record<string,string>): Promise<string> {
  if (oauthCache && oauthCache.expires > Date.now()) return oauthCache.token;
  if (!oauthPromise) oauthPromise = accessToken(account).then(token => {
    oauthCache = {token, expires: Date.now()+50*60_000}; return token;
  }).finally(() => { oauthPromise = undefined; });
  return oauthPromise;
}
async function fingerprint(token: string): Promise<string> {
  return Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(token))))
    .map(b=>b.toString(16).padStart(2,'0')).join('');
}

Deno.serve(async (req: Request) => {
  if (req.method !== 'POST') return json({error:'method_not_allowed'},405);
  const admin = createClient(SUPABASE_URL,SUPABASE_SERVICE_ROLE_KEY,{auth:{persistSession:false}});
  const dispatchSecret = req.headers.get('x-raad-dispatch-secret');
  let server = false;
  if (dispatchSecret) {
    const {data,error} = await admin.rpc('authorize_push_dispatch',{candidate:dispatchSecret});
    if (error || data !== true) return json({error:'invalid_dispatch_authorization'},401);
    server = true;
  }
  let userId: string | undefined;
  let userClient = admin;
  if (!server) {
    const authorization = req.headers.get('authorization');
    if (!authorization?.startsWith('Bearer ')) return json({error:'missing_authorization'},401);
    userClient = createClient(SUPABASE_URL,SUPABASE_ANON_KEY,{
      global:{headers:{Authorization:authorization}},auth:{persistSession:false}});
    const {data,error} = await userClient.auth.getUser();
    if (error || !data.user) return json({error:'invalid_user'},401);
    userId = data.user.id;
  }
  const payload = await req.json().catch(()=>({}));
  const eventId = String(payload.event_id || '');
  const transactionId = String(payload.transaction_id || '');
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  if ((!server || !payload.dispatch_due) && ((!eventId && !transactionId) ||
      (eventId && !uuid.test(eventId)) || (transactionId && !uuid.test(transactionId))))
    return json({error:'invalid_event_identifier'},400);
  let query = userClient.from('notification_events').select('*');
  // Historical failures are retained for audit, never replayed by old phone callers.
  if (!server || !payload.validate_only) query = query.is('push_suppressed_at',null);
  if (!server || !payload.validate_only) query = query.is('push_dispatched_at',null);
  if (!server) query = query.eq('actor_user_id',userId);
  if (eventId) query = query.eq('id',eventId);
  else if (transactionId) query = query.eq('transaction_id',transactionId);
  else query = query.eq('push_server_managed',true).lte('push_next_attempt_at',new Date().toISOString())
    .gt('created_at',new Date(Date.now()-7*86400_000).toISOString());
  const {data:events,error} = await query.order('created_at',{ascending:!transactionId}).limit(eventId || transactionId ? 1 : 10);
  if (error) return json({error:'event_lookup_failed'},503);
  if (!events?.length) return json({ok:true,skipped:'no_pending_event'});
  if (server && payload.validate_only) {
    try { return json(await validate(events[0],admin)); }
    catch { return json({error:'fcm_validation_failed'},503); }
  }
  const results = [];
  for (let offset=0;offset<events.length;offset+=3)
    results.push(...await Promise.all(events.slice(offset,offset+3).map(event=>dispatch(event,admin))));
  // Phone callers retain durable WorkManager retries while any target remains pending.
  return json({ok:results.every(r=>r.ok),results},results.every(r=>r.ok) ? 200 : 503);
});

async function dispatch(event: Event, admin: SupabaseClient) {
  const claimId = crypto.randomUUID();
  const {data:claimed,error:claimError} = await admin.from('notification_events').update({
    push_locked_until:new Date(Date.now()+120_000).toISOString(),push_claim_id:claimId
  }).eq('id',event.id).is('push_dispatched_at',null)
    .or('push_locked_until.is.null,push_locked_until.lt.'+new Date().toISOString()).select('id').maybeSingle();
  if (claimError) return {ok:false,error:'event_claim_failed'};
  if (!claimed) return {ok:true,skipped:'already_claimed'};
  let sent=0;
  const accepted = new Set<string>(event.push_delivered_token_ids ?? []);
  const failures: string[] = [];
  const finish = async (complete: boolean) => {
    const attempts = Number(event.push_attempts ?? 0)+1;
    const next = new Date(Date.now()+Math.min(6*3600_000,30_000*2**Math.min(attempts-1,10))).toISOString();
    const {error} = await admin.from('notification_events').update({push_locked_until:null,push_claim_id:null,
      push_dispatched_at:complete ? new Date().toISOString() : null,push_attempts:attempts,
      push_next_attempt_at:next,push_delivered_token_ids:Array.from(accepted),
      push_last_error:failures.length ? failures.join(' | ').slice(0,1000) : null
    }).eq('id',event.id).eq('push_claim_id',claimId);
    console.info(JSON.stringify({event_id:event.id,created_at:event.created_at,sent,complete:complete && !error,
      error:error ? 'event_result_persistence_failed' : failures.join('|'),next_attempt_at:complete ? null : next}));
    return {ok:complete && !error,sent,failed:failures.length};
  };
  try {
    const raw = Deno.env.get('FIREBASE_SERVICE_ACCOUNT_JSON');
    if (!raw) throw new Error('firebase_service_account_missing');
    const account = JSON.parse(raw);
    if (account.type !== 'service_account' || account.project_id !== 'raad-pharmacy' ||
        !account.client_email || !account.private_key) throw new Error('firebase_service_account_invalid');
    const {data:profiles,error:profileError} = await admin.from('profiles').select('id')
      .eq('pharmacy_id',event.pharmacy_id).eq('role','MANAGER').eq('is_hidden',false);
    if (profileError) throw new Error('recipient_membership_lookup_failed');
    const memberIds = (profiles ?? []).map(p=>p.id);
    let tokenQuery = admin.from('push_tokens').select('id,token,device_id,user_id,app_version_code,hide_notification_details')
      .eq('pharmacy_id',event.pharmacy_id).is('deleted_at',null)
      .in('user_id',memberIds).order('updated_at',{ascending:false});
    // An empty string is not a PostgreSQL UUID. Older events can have no actor device.
    if (event.actor_device_id) tokenQuery = tokenQuery.neq('device_id',event.actor_device_id);
    if (event.event_type === 'TEAM_ALERT' || event.event_type === 'TEAM_MESSAGE') {
      if (!event.recipient_user_id) throw new Error('invalid_alert_recipient');
      if (event.event_type === 'TEAM_MESSAGE' && event.message_read_at) return await finish(true);
      tokenQuery = tokenQuery.eq('user_id',event.recipient_user_id);
    }
    const {data:registered,error:tokenError} = await tokenQuery;
    if (tokenError) throw new Error('token_lookup_failed');
    const tokens = (registered ?? []).filter((t,i,a)=>a.findIndex(x=>x.token===t.token)===i);
    const expectedUsers = event.recipient_user_id ? [event.recipient_user_id]
      : memberIds.filter(id=>id !== event.actor_user_id);
    const missing = expectedUsers.filter(id=>!tokens.some(t=>t.user_id===id));
    if (missing.length) failures.push('recipient_device_not_registered:'+missing.length);
    const {data:receipts,error:receiptError} = await admin.from('notification_deliveries').select('*').eq('event_id',event.id);
    if (receiptError) throw new Error('receipt_lookup_failed');
    const oauth = tokens.length ? await cachedAccessToken(account) : '';
    const fcmUrl = 'https://fcm.googleapis.com/v1/projects/'+account.project_id+'/messages:send';
    for (const token of tokens) {
      const hash = await fingerprint(token.token);
      const receipt = receipts?.find(r=>r.token_id===token.id);
      const action = deliveryAction(receipt,hash,token.app_version_code ?? 0);
      if (action === 'done' || (!event.push_server_managed && accepted.has(String(token.id)))) continue;
      if (action === 'wait') { failures.push('device_display_pending'); continue; }
      const attempt = Number(receipt?.attempts ?? 0)+1;
      let lastError: string | null = null;
      let acceptedAt: string | null = null;
      try {
        const response = await fetch(fcmUrl,{method:'POST',signal:AbortSignal.timeout(8_000),
          headers:{authorization:'Bearer '+oauth,'content-type':'application/json'},
          body:JSON.stringify({message:buildFcmMessage(event,token.token,{...token,native_fallback:action==='native'})})});
        if (response.ok) {sent++;accepted.add(String(token.id));acceptedAt=new Date().toISOString();}
        else {
          const body = await response.json().catch(()=>({}));
          const unregistered = body?.error?.details?.some((d:{errorCode?:string})=>d.errorCode==='UNREGISTERED');
          if (unregistered) {
            const {error} = await admin.from('push_tokens').update({deleted_at:new Date().toISOString()})
              .eq('id',token.id).eq('token',token.token);
            lastError = error ? 'token_retirement_failed' : 'token_unregistered';
          } else lastError = 'FCM_HTTP_'+response.status;
        }
      } catch {lastError='fcm_transport_failed';}
      const patch: Record<string,unknown> = {event_id:event.id,token_id:token.id,token_fingerprint:hash,attempts:attempt,last_error:lastError};
      if (acceptedAt) {
        if (action==='data') {
          patch.fcm_accepted_at=acceptedAt;
          patch.delivery_mode='data';
          patch.native_fallback_accepted_at=null;
          failures.push('device_display_pending');
        } else {
          if (receipt?.token_fingerprint===hash && receipt.delivery_mode==='data')
            patch.native_fallback_accepted_at=acceptedAt;
          else { patch.fcm_accepted_at=acceptedAt; patch.delivery_mode='native'; }
        }
      }
      const {error:saveError} = await admin.from('notification_deliveries').upsert(patch,{onConflict:'event_id,token_id'});
      if (saveError) failures.push('delivery_record_failed');
      if (lastError) failures.push(lastError);
      console.info(JSON.stringify({event_id:event.id,target_id:token.id,fcm_accepted_at:acceptedAt,error:lastError}));
    }
    return await finish(failures.length===0);
  } catch (error) {
    // Do not log OAuth credentials, FCM tokens or customer data.
    const safe = error instanceof Error && /^[a-z_]+$/.test(error.message) ? error.message : 'dispatch_failed';
    failures.push(safe); return await finish(false);
  }
}

/** FCM validation performs no sends and changes no financial or delivery records. Server auth only. */
async function validate(event: Event, admin: SupabaseClient) {
  const account = JSON.parse(Deno.env.get('FIREBASE_SERVICE_ACCOUNT_JSON') || '{}');
  if (account.project_id !== 'raad-pharmacy') throw new Error('invalid_account');
  const oauth = await cachedAccessToken(account);
  const {data:profiles,error:profileError} = await admin.from('profiles').select('id')
    .eq('pharmacy_id',event.pharmacy_id).eq('role','MANAGER').eq('is_hidden',false);
  if (profileError) return {ok:false,error:'recipient_membership_lookup_failed',database_code:profileError.code};
  const {data:tokens,error} = await admin.from('push_tokens').select('token').eq('pharmacy_id',event.pharmacy_id)
    .is('deleted_at',null).order('updated_at',{ascending:false}).limit(1);
  if (error || !tokens?.length) throw new Error('validation_device_missing');
  const results = [];
  for (const device of [{app_version_code:0,hide_notification_details:true},
    {app_version_code:47,hide_notification_details:false},{app_version_code:47,hide_notification_details:true},
    {app_version_code:50,hide_notification_details:false},{app_version_code:52,hide_notification_details:false},
    {app_version_code:52,hide_notification_details:true},
    {app_version_code:52,hide_notification_details:false,native_fallback:true},
    {app_version_code:52,hide_notification_details:true,native_fallback:true}]) {
    const response = await fetch('https://fcm.googleapis.com/v1/projects/raad-pharmacy/messages:send',{
      method:'POST',signal:AbortSignal.timeout(8_000),headers:{authorization:'Bearer '+oauth,'content-type':'application/json'},
      body:JSON.stringify({validate_only:true,message:buildFcmMessage(event,tokens[0].token,device)})});
    results.push({version_code:device.app_version_code,hidden:device.hide_notification_details,
      native_fallback:'native_fallback' in device && device.native_fallback===true,status:response.status});
  }
  return {ok:results.every(r=>r.status===200),validation_only:true,manager_count:profiles?.length ?? 0,results};
}

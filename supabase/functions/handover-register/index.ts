import { createClient } from "npm:@supabase/supabase-js@2.49.4";

const URL = Deno.env.get("SUPABASE_URL")!;
const SECRET = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const PHARMACY = "3268adba-6375-4c04-8292-f7ae7ec9b490";
const DOMAIN = "raad-pharmacy-live.local";
const validUser = /^[a-z][a-z0-9._-]{1,31}$/;
const validName = /^.{2,80}$/u;
const validPassword = (s: string) => s.length >= 10 && s.length <= 128;
const respond = (data: unknown, status = 200) => new Response(JSON.stringify(data), {
  status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
});
async function digest(s: string) {
  const hash = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return Array.from(new Uint8Array(hash)).map(b => b.toString(16).padStart(2, "0")).join("");
}
Deno.serve(async req => {
  if (req.method !== "POST") return respond({ error: "method_not_allowed" }, 405);
  const body = await req.json().catch(() => ({})) as Record<string, unknown>;
  const mode = String(body.mode || "");
  const username = String(body.username || "").trim().toLowerCase();
  const displayName = String(body.display_name || "").trim();
  const password = String(body.password || "");
  if (!validUser.test(username) || !validName.test(displayName) || !validPassword(password))
    return respond({ error: "invalid_registration_details" }, 400);
  if (!["setup", "add_user"].includes(mode)) return respond({ error: "invalid_mode" }, 400);
  const admin = createClient(URL, SECRET, { auth: { persistSession: false } });
  let claim: string | null = null;
  if (mode === "setup") {
    // An installation-only code is 192 bits; do not log or embed it inside the APK.
    const code = String(body.setup_code || "");
    if (!/^[a-f0-9]{48}$/.test(code)) return respond({ error: "invalid_setup_code" }, 401);
    const claimHash = await digest(code);
    claim = crypto.randomUUID();
    const { data, error } = await admin.from("handover_bootstrap")
      .update({ claim_id: claim, claimed_at: new Date().toISOString() })
      .eq("pharmacy_id", PHARMACY).eq("code_hash", claimHash)
      .is("consumed_at", null).is("claim_id", null)
      .select("pharmacy_id").maybeSingle();
    if (error || !data) return respond({ error: "setup_unavailable_or_invalid" }, 401);
  } else {
    const bearer = req.headers.get("authorization")?.match(/^Bearer (.+)$/i)?.[1];
    if (!bearer) return respond({ error: "sign_in_required" }, 401);
    const { data: account, error: identityError } = await admin.auth.getUser(bearer);
    if (identityError || !account.user) return respond({ error: "sign_in_required" }, 401);
    const { data: profile, error: profileError } = await admin.from("profiles")
      .select("role").eq("id", account.user.id).eq("pharmacy_id", PHARMACY)
      .is("deleted_at", null).maybeSingle();
    if (profileError || profile?.role !== "MANAGER") return respond({ error: "not_allowed" }, 403);
  }

  let createdId: string | null = null;
  try {
    const { data: user, error: createError } = await admin.auth.admin.createUser({
      email: username + "@" + DOMAIN, password, email_confirm: true,
    });
    if (createError || !user.user) return respond({ error: "username_unavailable_or_registration_failed" }, 409);
    createdId = user.user.id;
    const { error: profileError } = await admin.from("profiles").insert({
      id: createdId, pharmacy_id: PHARMACY, role: "MANAGER",
      display_name: displayName, is_hidden: false,
    });
    if (profileError) throw new Error("profile_creation_failed");
    if (claim) {
      const { error } = await admin.from("handover_bootstrap")
        .update({ consumed_at: new Date().toISOString(), claim_id: null, claimed_at: null })
        .eq("pharmacy_id", PHARMACY).eq("claim_id", claim);
      if (error) throw new Error("setup_completion_failed");
    }
    return respond({ ok: true, username }, 201);
  } catch {
    // Never leave an account with no pharmacy membership after a failed registration.
    if (createdId) await admin.auth.admin.deleteUser(createdId);
    return respond({ error: "registration_failed" }, 503);
  } finally {
    if (claim) await admin.from("handover_bootstrap").update({
      claim_id: null, claimed_at: null,
    }).eq("pharmacy_id", PHARMACY).eq("claim_id", claim).is("consumed_at", null);
  }
});

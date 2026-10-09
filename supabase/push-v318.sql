-- Additive repair. Financial rows, balances, creators and RLS remain untouched.
-- Only the server dispatcher needs these four membership fields; no profile write grant.
grant select (id, pharmacy_id, role, is_hidden) on public.profiles to service_role;

alter table public.notification_events add column if not exists push_suppressed_at timestamptz;
alter table public.notification_deliveries add column if not exists delivery_mode text;
alter table public.notification_deliveries add column if not exists native_fallback_accepted_at timestamptz;

-- Do not broadcast historical failed notifications when repairing the permission.
-- Keep the original error, attempts and undelivered status for an honest audit trail.
update public.notification_events
set push_suppressed_at = now(), push_next_attempt_at = 'infinity'
where push_dispatched_at is null and push_suppressed_at is null
  and push_last_error is not null and created_at < now()
  and not exists (select 1 from public.notification_deliveries d
    where d.event_id = notification_events.id and d.fcm_accepted_at is not null);

create or replace function raad_private.dispatch_push(event uuid default null)
returns bigint language plpgsql security definer set search_path = '' as $$
declare secret text; request_id bigint;
begin
  if not exists (select 1 from public.notification_events e
    where e.push_server_managed and e.push_dispatched_at is null and e.push_suppressed_at is null
      and (event is null or e.id=event)
      and (event is not null or e.push_next_attempt_at<=now())
      and e.created_at>now()-interval '7 days'
      and (e.push_locked_until is null or e.push_locked_until<now())) then
    return null;
  end if;
  select decrypted_secret into secret from vault.decrypted_secrets where name='raad_push_dispatch_v315';
  if secret is null then return null; end if;
  select net.http_post(
    url:='https://gsyrjhqkbfomxqacexle.supabase.co/functions/v1/send-financial-notification',
    headers:=jsonb_build_object('Content-Type','application/json','x-raad-dispatch-secret',secret),
    body:=case when event is null then '{"dispatch_due":true}'::jsonb else jsonb_build_object('event_id',event) end,
    timeout_milliseconds:=30000) into request_id;
  return request_id;
end $$;
revoke all on function raad_private.dispatch_push(uuid) from public, anon, authenticated;

-- Verification (run as service_role to exercise column privileges, not superuser bypass):
-- begin; set local role service_role;
-- select id from public.profiles where pharmacy_id is not null and role='MANAGER' and is_hidden=false;
-- rollback;
-- Rollback: restore the previous dispatch_push definition; restore Edge version 15.
-- Revoke the column grant only after restoring a dispatcher that does not query profiles.
-- Never clear push_suppressed_at automatically: that would replay historical notifications.

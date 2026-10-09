-- 3.3.19.2: additive backup CONTROL metadata only. No financial/auth/storage rows are changed.
-- Review/apply separately after approval; this file is not automatically deployed by CI.
begin;
create table if not exists public.cloud_backup_status (
  pharmacy_id uuid primary key references public.pharmacies(id),
  configured boolean not null default false,
  phase text not null default 'NOT_CONFIGURED' check (phase in ('NOT_CONFIGURED','RUNNING','VERIFIED','FAILED')),
  last_attempt_at timestamptz,
  last_success_at timestamptz,
  last_backup_id uuid,
  retained_count integer not null default 0 check (retained_count>=0),
  total_bytes bigint not null default 0 check (total_bytes>=0),
  quota_used bigint not null default 0 check (quota_used>=0),
  quota_limit bigint not null default 0 check (quota_limit>=0),
  last_error_code text,
  drive_owner_email text,
  restore_tested_at timestamptz,
  updated_at timestamptz not null default now()
);
create table if not exists public.cloud_backup_admins (
  pharmacy_id uuid not null references public.pharmacies(id),
  user_id uuid not null references public.profiles(id),
  primary key(pharmacy_id,user_id)
);
create table if not exists public.cloud_backup_runs (
  id uuid primary key,
  pharmacy_id uuid not null references public.pharmacies(id),
  created_at timestamptz not null default now(),
  completed_at timestamptz,
  state text not null check(state in ('RUNNING','VERIFIED','FAILED','RETAINED_OUT')),
  error_code text,
  bytes bigint not null default 0 check(bytes>=0),
  drive_folder_id text,
  files jsonb not null default '[]'::jsonb
);
create index if not exists cloud_backup_runs_history on public.cloud_backup_runs(pharmacy_id,created_at desc);
create table if not exists public.cloud_backup_requests (
  id uuid primary key default gen_random_uuid(),
  pharmacy_id uuid not null references public.pharmacies(id),
  requested_by uuid not null references public.profiles(id),
  created_at timestamptz not null default now(),
  completed_at timestamptz,
  state text not null default 'QUEUED' check(state in ('QUEUED','DISPATCHED','COMPLETED','FAILED')),
  error_code text
);
create index if not exists cloud_backup_requests_recent on public.cloud_backup_requests(pharmacy_id,created_at desc);

alter table public.cloud_backup_status enable row level security;
alter table public.cloud_backup_admins enable row level security;
alter table public.cloud_backup_runs enable row level security;
alter table public.cloud_backup_requests enable row level security;

create or replace function raad_private.can_manage_cloud_backup(target uuid)
returns boolean language sql stable security definer set search_path='' as $$
  select auth.uid() is not null and exists (
    select 1 from public.cloud_backup_admins a join public.profiles p on p.id=a.user_id
    where a.pharmacy_id=target and p.pharmacy_id=target and p.deleted_at is null and a.user_id=auth.uid())
$$;
revoke all on function raad_private.can_manage_cloud_backup(uuid) from public,anon;
grant usage on schema raad_private to authenticated;
grant execute on function raad_private.can_manage_cloud_backup(uuid) to authenticated;

create or replace function raad_private.can_read_cloud_backup(target uuid)
returns boolean language sql stable security definer set search_path='' as $$
  select auth.uid() is not null and exists (
    select 1 from public.profiles where id=auth.uid() and pharmacy_id=target and deleted_at is null)
$$;
revoke all on function raad_private.can_read_cloud_backup(uuid) from public,anon;
grant execute on function raad_private.can_read_cloud_backup(uuid) to authenticated;

drop policy if exists cloud_backup_status_read on public.cloud_backup_status;
create policy cloud_backup_status_read on public.cloud_backup_status for select to authenticated
  using(raad_private.can_read_cloud_backup(pharmacy_id));
drop policy if exists cloud_backup_admin_read_self on public.cloud_backup_admins;
create policy cloud_backup_admin_read_self on public.cloud_backup_admins for select to authenticated
  using(user_id=(select auth.uid()) and pharmacy_id=(select raad_private.current_pharmacy()));
drop policy if exists cloud_backup_history_admin on public.cloud_backup_runs;
create policy cloud_backup_history_admin on public.cloud_backup_runs for select to authenticated
  using(raad_private.can_manage_cloud_backup(pharmacy_id));
drop policy if exists cloud_backup_requests_admin on public.cloud_backup_requests;
create policy cloud_backup_requests_admin on public.cloud_backup_requests for select to authenticated
  using(raad_private.can_manage_cloud_backup(pharmacy_id));

revoke all on public.cloud_backup_status,public.cloud_backup_admins,public.cloud_backup_runs,public.cloud_backup_requests from public,anon,authenticated;
grant select(pharmacy_id,configured,phase,last_attempt_at,last_success_at,last_backup_id,retained_count,total_bytes,
             quota_used,quota_limit,restore_tested_at,updated_at)
  on public.cloud_backup_status to authenticated;
grant select on public.cloud_backup_admins,public.cloud_backup_runs,public.cloud_backup_requests to authenticated;
grant select,insert,update,delete on public.cloud_backup_status,public.cloud_backup_admins,public.cloud_backup_runs,public.cloud_backup_requests to service_role;

-- Public RPC is explicitly authorized by uid + server-provisioned membership.
-- It only queues a request; no Google/GitHub credential is ever returned.
create or replace function public.request_cloud_backup()
returns uuid language plpgsql security definer set search_path='' as $$
declare tenant uuid; request_id uuid;
begin
  if auth.uid() is null then raise exception 'not_authenticated'; end if;
  select pharmacy_id into tenant from public.profiles where id=auth.uid();
  if tenant is null or not raad_private.can_manage_cloud_backup(tenant) then
    raise exception 'backup_admin_required';
  end if;
  perform 1 from public.cloud_backup_status where pharmacy_id=tenant and configured and last_success_at is not null for update;
  if not found then raise exception 'backup_not_configured'; end if;
  if exists(select 1 from public.cloud_backup_requests where pharmacy_id=tenant and created_at>now()-interval '10 minutes')
     or exists(select 1 from public.cloud_backup_runs where pharmacy_id=tenant and state='RUNNING' and created_at>now()-interval '45 minutes') then
    raise exception 'backup_request_busy';
  end if;
  insert into public.cloud_backup_requests(pharmacy_id,requested_by) values(tenant,auth.uid()) returning id into request_id;
  return request_id;
end $$;
revoke all on function public.request_cloud_backup() from public,anon;
grant execute on function public.request_cloud_backup() to authenticated;
commit;

-- No default administrator is inferred from a phone or editable JWT metadata.
-- The operator must separately provision ONE authorized existing profile UUID.
-- insert into public.cloud_backup_admins(pharmacy_id,user_id) values(:pharmacy_id,:approved_user_id);

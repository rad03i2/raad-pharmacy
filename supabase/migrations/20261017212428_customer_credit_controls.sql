-- Additive credit controls. No debt/payment rows, login policies, FCM handlers or balances are rewritten.
create table raad_private.credit_control_managers (
  pharmacy_id uuid primary key references public.pharmacies(id),
  user_id uuid not null unique references public.profiles(id)
);
alter table raad_private.credit_control_managers enable row level security;
revoke all on raad_private.credit_control_managers from public, anon, authenticated;
-- Bootstrap the already verified, unique Raad profile ONCE. Runtime permission uses this immutable UUID binding.
do $$ begin
  if (select count(*) from public.profiles where display_name='رعد' and role='MANAGER'
      and not is_hidden and deleted_at is null) <> 1 then
    raise exception 'CREDIT_MANAGER_BINDING_REQUIRES_REVIEW';
  end if;
  insert into raad_private.credit_control_managers(pharmacy_id,user_id)
    select pharmacy_id,id from public.profiles where display_name='رعد' and role='MANAGER'
      and not is_hidden and deleted_at is null;
end $$;

create table public.customer_credit_controls (
  customer_id uuid primary key references public.customers(id),
  pharmacy_id uuid not null references public.pharmacies(id),
  blocked boolean not null default false,
  updated_at timestamptz not null default now(),
  updated_by uuid not null references public.profiles(id)
);
create index customer_credit_controls_pharmacy on public.customer_credit_controls(pharmacy_id);
create table public.customer_credit_requests (
  id uuid primary key,
  customer_id uuid not null references public.customers(id),
  pharmacy_id uuid not null references public.pharmacies(id),
  requested_by uuid not null references public.profiles(id),
  requested_action text not null check(requested_action in ('BLOCK','UNBLOCK')),
  customer_name text not null,
  created_at timestamptz not null default now(),
  resolved_at timestamptz,
  resolved_by uuid references public.profiles(id),
  resolved_blocked boolean
);
create index customer_credit_requests_pending on public.customer_credit_requests(pharmacy_id,created_at) where resolved_at is null;
create index customer_credit_requests_pharmacy on public.customer_credit_requests(pharmacy_id);
create index customer_credit_requests_customer on public.customer_credit_requests(customer_id);
create index customer_credit_requests_requester on public.customer_credit_requests(requested_by);
create index customer_credit_requests_resolver on public.customer_credit_requests(resolved_by);
create index customer_credit_controls_actor on public.customer_credit_controls(updated_by);
alter table public.notification_events add column credit_request_id uuid references public.customer_credit_requests(id);
create index notification_events_credit_request on public.notification_events(credit_request_id) where credit_request_id is not null;
alter table public.customer_credit_controls enable row level security;
alter table public.customer_credit_requests enable row level security;
revoke all on public.customer_credit_controls, public.customer_credit_requests from public, anon, authenticated;
grant select on public.customer_credit_controls, public.customer_credit_requests to authenticated;

create function raad_private.can_manage_credit() returns boolean language sql stable security definer set search_path='' as $$
  select exists(select 1 from raad_private.credit_control_managers m
    where m.user_id=(select auth.uid()) and m.pharmacy_id=(select raad_private.current_pharmacy()))
$$;
revoke all on function raad_private.can_manage_credit() from public, anon;
grant execute on function raad_private.can_manage_credit() to authenticated;
create policy credit_controls_read on public.customer_credit_controls for select to authenticated
  using (pharmacy_id=(select raad_private.current_pharmacy()));
create policy credit_requests_read on public.customer_credit_requests for select to authenticated
  using (pharmacy_id=(select raad_private.current_pharmacy()) and
    (requested_by=(select auth.uid()) or (select raad_private.can_manage_credit())));

create function raad_private.credit_context() returns jsonb language plpgsql stable security definer set search_path='' as $$
declare actor uuid := auth.uid(); pharmacy uuid := raad_private.current_pharmacy(); manager boolean;
begin
  if actor is null or pharmacy is null then raise exception using errcode='42501',message='AUTH_SESSION_REQUIRED'; end if;
  manager := raad_private.can_manage_credit();
  return jsonb_build_object('owner',actor,'pharmacy',pharmacy,'canManage',manager,
    'blockedIds',coalesce((select jsonb_agg(customer_id) from public.customer_credit_controls where pharmacy_id=pharmacy and blocked),'[]'::jsonb),
    'requests',coalesce((select jsonb_agg(jsonb_build_object('id',r.id,'customerId',r.customer_id,
      'customerName',r.customer_name,'requestedByName',p.display_name,'block',r.requested_action='BLOCK'))
      from public.customer_credit_requests r join public.profiles p on p.id=r.requested_by
      where r.pharmacy_id=pharmacy and r.resolved_at is null and (manager or r.requested_by=actor)),'[]'::jsonb));
end $$;
create function public.customer_credit_context() returns jsonb language sql security invoker set search_path='' as $$
  select raad_private.credit_context()
$$;

create function raad_private.set_customer_credit(target_customer uuid, make_blocked boolean) returns void
language plpgsql security definer set search_path='' as $$
declare pharmacy uuid := raad_private.current_pharmacy();
begin
  if not raad_private.can_manage_credit() or make_blocked is null then
    raise exception using errcode='42501',message='ONLY_RAAD_CAN_MANAGE_CREDIT';
  end if;
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(target_customer::text, 2301));
  if not exists(select 1 from public.customers where id=target_customer and pharmacy_id=pharmacy and deleted_at is null) then
    raise exception using errcode='42501',message='CUSTOMER_NOT_VISIBLE';
  end if;
  insert into public.customer_credit_controls(customer_id,pharmacy_id,blocked,updated_by)
    values(target_customer,pharmacy,make_blocked,auth.uid())
    on conflict(customer_id) do update set blocked=excluded.blocked,updated_at=now(),updated_by=excluded.updated_by;
end $$;
create function public.set_customer_credit(target_customer uuid, make_blocked boolean) returns void
language sql security invoker set search_path='' as $$ select raad_private.set_customer_credit(target_customer,make_blocked) $$;

create function raad_private.request_customer_credit(target_customer uuid, make_blocked boolean, request_id uuid, sender_device_id uuid)
returns uuid language plpgsql security definer set search_path='' as $$
declare actor uuid:=auth.uid(); pharmacy uuid:=raad_private.current_pharmacy(); target uuid; customer_label text; previous public.customer_credit_requests;
begin
  if actor is null or pharmacy is null or make_blocked is null or request_id is null then
    raise exception using errcode='42501',message='AUTH_SESSION_REQUIRED';
  end if;
  if not exists(select 1 from public.profiles where id=actor and role='MANAGER' and not is_hidden and deleted_at is null) then
    raise exception using errcode='42501',message='CREDIT_REQUEST_FORBIDDEN';
  end if;
  select user_id into target from raad_private.credit_control_managers where pharmacy_id=pharmacy;
  if target is null or target=actor then raise exception using errcode='42501',message='CREDIT_REQUEST_FORBIDDEN'; end if;
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(request_id::text,2302));
  select * into previous from public.customer_credit_requests where id=request_id;
  if found then
    if previous.requested_by=actor and previous.customer_id=target_customer and previous.pharmacy_id=pharmacy
      and previous.requested_action=(case when make_blocked then 'BLOCK' else 'UNBLOCK' end) then return request_id; end if;
    raise exception 'CREDIT_REQUEST_ID_CONFLICT';
  end if;
  select name into customer_label from public.customers where id=target_customer and pharmacy_id=pharmacy and deleted_at is null;
  if customer_label is null then raise exception using errcode='42501',message='CUSTOMER_NOT_VISIBLE'; end if;
  insert into public.customer_credit_requests(id,customer_id,pharmacy_id,requested_by,requested_action,customer_name)
    values(request_id,target_customer,pharmacy,actor,case when make_blocked then 'BLOCK' else 'UNBLOCK' end,customer_label);
  -- Use the existing directed team-message/FCM pipeline; do not alter ordinary notification delivery.
  perform raad_private.send_team_message(target,sender_device_id,request_id,
    (case when make_blocked then 'طلب حجب الزبون: ' else 'طلب رفع حجب الزبون: ' end)||left(customer_label,300));
  update public.notification_events set credit_request_id=request_id
    where id=request_id and actor_user_id=actor and recipient_user_id=target and event_type='TEAM_MESSAGE';
  return request_id;
end $$;
create function public.request_customer_credit(target_customer uuid, make_blocked boolean, request_id uuid, sender_device_id uuid)
returns uuid language sql security invoker set search_path='' as $$
 select raad_private.request_customer_credit(target_customer,make_blocked,request_id,sender_device_id)
$$;

create function raad_private.resolve_credit_request(request_id uuid, make_blocked boolean) returns void
language plpgsql security definer set search_path='' as $$
declare request public.customer_credit_requests;
begin
  if not raad_private.can_manage_credit() then raise exception using errcode='42501',message='ONLY_RAAD_CAN_MANAGE_CREDIT'; end if;
  select * into request from public.customer_credit_requests where id=request_id
    and pharmacy_id=raad_private.current_pharmacy() for update;
  if not found then raise exception using errcode='42501',message='CREDIT_REQUEST_NOT_VISIBLE'; end if;
  if request.resolved_at is not null then
    if request.resolved_blocked=make_blocked then return; end if;
    raise exception 'CREDIT_REQUEST_ALREADY_RESOLVED';
  end if;
  perform raad_private.set_customer_credit(request.customer_id,make_blocked);
  update public.customer_credit_requests set resolved_at=now(),resolved_by=auth.uid(),resolved_blocked=make_blocked where id=request_id;
end $$;
create function public.resolve_credit_request(request_id uuid, make_blocked boolean) returns void
language sql security invoker set search_path='' as $$ select raad_private.resolve_credit_request(request_id,make_blocked) $$;

-- All endpoint authentication is checked inside private functions; public wrappers cannot choose an actor/pharmacy.
revoke all on function raad_private.credit_context(), raad_private.set_customer_credit(uuid,boolean),
 raad_private.request_customer_credit(uuid,boolean,uuid,uuid), raad_private.resolve_credit_request(uuid,boolean),
 public.customer_credit_context(), public.set_customer_credit(uuid,boolean),
 public.request_customer_credit(uuid,boolean,uuid,uuid), public.resolve_credit_request(uuid,boolean) from public,anon;
grant execute on function raad_private.credit_context(), raad_private.set_customer_credit(uuid,boolean),
 raad_private.request_customer_credit(uuid,boolean,uuid,uuid), raad_private.resolve_credit_request(uuid,boolean),
 public.customer_credit_context(), public.set_customer_credit(uuid,boolean),
 public.request_customer_credit(uuid,boolean,uuid,uuid), public.resolve_credit_request(uuid,boolean) to authenticated;

create function raad_private.enforce_customer_credit() returns trigger language plpgsql security definer set search_path='' as $$
declare customer uuid; pharmacy uuid; old_effect numeric:=0; new_effect numeric:=0; blocked_now boolean;
begin
  -- Server maintenance without a user JWT keeps its existing privileges. Authenticated devices are always checked.
  if auth.uid() is null then
    if current_setting('role',true) in ('authenticated','anon') then raise exception using errcode='42501',message='AUTH_SESSION_REQUIRED'; end if;
    if tg_op='DELETE' then return old; end if; return new;
  end if;
  if raad_private.can_manage_credit() then if tg_op='DELETE' then return old; end if; return new; end if;
  if tg_table_name='customers' then
    customer:=new.id; pharmacy:=new.pharmacy_id;
    if tg_op='UPDATE' then old_effect:=case when old.deleted_at is null then old.opening_debt else 0 end; end if;
    new_effect:=case when new.deleted_at is null then new.opening_debt else 0 end;
  else
    if tg_op='DELETE' then customer:=old.customer_id; pharmacy:=old.pharmacy_id;
    else customer:=new.customer_id; pharmacy:=new.pharmacy_id; end if;
    if tg_op in ('UPDATE','DELETE') then old_effect:=case when old.deleted_at is not null then 0 when old.type='DEBT' then old.amount else -old.amount end; end if;
    if tg_op<>'DELETE' then new_effect:=case when new.deleted_at is not null then 0 when new.type='DEBT' then new.amount else -new.amount end; end if;
    -- BEFORE INSERT also runs for legacy UPSERT. A retry of the identical stored financial row is harmless.
    if tg_op='INSERT' and exists(select 1 from public.transactions t where t.id=new.id and t.pharmacy_id=new.pharmacy_id
      and t.customer_id=new.customer_id and t.type=new.type and t.amount=new.amount
      and t.deleted_at is not distinct from new.deleted_at and t.occurred_at=new.occurred_at) then return new; end if;
  end if;
  if new_effect<=old_effect then if tg_op='DELETE' then return old; end if; return new; end if;
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(customer::text,2301));
  select blocked into blocked_now from public.customer_credit_controls where customer_id=customer and pharmacy_id=pharmacy;
  if coalesce(blocked_now,false) then raise exception using errcode='42501',message='CUSTOMER_CREDIT_BLOCKED'; end if;
  if tg_op='DELETE' then return old; end if; return new;
end $$;
revoke all on function raad_private.enforce_customer_credit() from public,anon,authenticated;
create trigger enforce_customer_credit before insert or update or delete on public.transactions
 for each row execute function raad_private.enforce_customer_credit();
create trigger enforce_customer_opening_credit before insert or update on public.customers
 for each row execute function raad_private.enforce_customer_credit();
notify pgrst,'reload schema';
-- Independent metadata subscriptions, existing ledger Realtime publication entries remain untouched.
do $$ declare t text; begin
  if exists(select 1 from pg_publication where pubname='supabase_realtime') then
    foreach t in array array['customer_credit_controls','customer_credit_requests'] loop
      if not exists(select 1 from pg_publication_tables where pubname='supabase_realtime' and schemaname='public' and tablename=t) then
        execute format('alter publication supabase_realtime add table public.%I',t);
      end if;
    end loop;
  end if;
end $$;

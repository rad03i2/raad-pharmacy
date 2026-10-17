-- Disposable database ONLY. No production identifiers, FCM webhooks, secrets or network access.
\set ON_ERROR_STOP on
create schema auth;
create schema raad_private;
create function auth.uid() returns uuid language sql stable as $$select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid$$;
create table pharmacies(id uuid primary key,deleted_at timestamptz);
create table profiles(id uuid primary key,pharmacy_id uuid references pharmacies,display_name text,role text,is_hidden boolean default false,deleted_at timestamptz);
create table customers(id uuid primary key,pharmacy_id uuid references pharmacies,name text,opening_debt numeric not null default 0,deleted_at timestamptz);
create table transactions(id uuid primary key,pharmacy_id uuid references pharmacies,customer_id uuid references customers,type text,amount numeric,occurred_at timestamptz default now(),deleted_at timestamptz);
create table devices(id uuid primary key,pharmacy_id uuid references pharmacies,user_id uuid references profiles,deleted_at timestamptz);
create table notification_events(id uuid primary key,pharmacy_id uuid references pharmacies,actor_user_id uuid references profiles,actor_display_name text,
 actor_device_id uuid references devices,recipient_user_id uuid references profiles,event_type text,amount numeric,customer_id uuid references customers,
 transaction_id uuid references transactions,created_at timestamptz default now());
create function raad_private.current_pharmacy() returns uuid language sql stable security definer set search_path='' as $$
 select pharmacy_id from public.profiles where id=auth.uid() and deleted_at is null
$$;
grant usage on schema auth,raad_private to authenticated;
grant select,insert,update,delete on customers,transactions to authenticated;
grant select on profiles,notification_events to authenticated;
create policy notification_events_read_same_pharmacy on notification_events for select to authenticated
 using(pharmacy_id=(select raad_private.current_pharmacy()) and
 (recipient_user_id is null or recipient_user_id=(select auth.uid()) or actor_user_id=(select auth.uid())));
alter table customers enable row level security;
alter table transactions enable row level security;
create policy tenant on customers to authenticated using(pharmacy_id=(select raad_private.current_pharmacy())) with check(pharmacy_id=(select raad_private.current_pharmacy()));
create policy tenant on transactions to authenticated using(pharmacy_id=(select raad_private.current_pharmacy())) with check(pharmacy_id=(select raad_private.current_pharmacy()));
insert into pharmacies values('00000000-0000-4000-8000-000000000001',null),('00000000-0000-4000-8000-000000000002',null);
insert into profiles(id,pharmacy_id,display_name,role,is_hidden) values
 ('00000000-0000-4000-8000-000000000011','00000000-0000-4000-8000-000000000001','رعد','MANAGER',false),
 ('00000000-0000-4000-8000-000000000012','00000000-0000-4000-8000-000000000001','أحمد','MANAGER',false),
 ('00000000-0000-4000-8000-000000000013','00000000-0000-4000-8000-000000000001','فؤاد','MANAGER',false),
 ('00000000-0000-4000-8000-000000000014','00000000-0000-4000-8000-000000000001','maintainer','MAINTAINER',true),
 ('00000000-0000-4000-8000-000000000015','00000000-0000-4000-8000-000000000002','outsider','MANAGER',false);
insert into customers values('00000000-0000-4000-8000-000000000021','00000000-0000-4000-8000-000000000001','fixture',100,null),
 ('00000000-0000-4000-8000-000000000022','00000000-0000-4000-8000-000000000002','other',0,null);
insert into devices values('00000000-0000-4000-8000-000000000031','00000000-0000-4000-8000-000000000001','00000000-0000-4000-8000-000000000012',null);

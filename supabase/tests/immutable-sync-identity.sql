-- Disposable CI database only. No production migration, rows, credentials or notifications.
\set ON_ERROR_STOP on
begin;
create temp table customers(id uuid primary key, pharmacy_id uuid not null, name text not null,
  opening_debt bigint not null default 0, created_at timestamptz not null, deleted_at timestamptz);
create temp table transactions(id uuid primary key, pharmacy_id uuid not null, customer_id uuid not null,
  operation_id uuid not null, created_at timestamptz not null, created_by uuid, created_device_id uuid,
  type text not null, amount numeric not null, occurred_at timestamptz not null, notes text);
-- The relevant branches of the live identity trigger, inspected read-only on 2026-10-17.
create function pg_temp.protect_sync_identity_fields() returns trigger language plpgsql as $$
begin
  if tg_table_name = 'customers' then
    if new.id is distinct from old.id
       or new.pharmacy_id is distinct from old.pharmacy_id
       or new.created_at is distinct from old.created_at then
      raise exception 'immutable customer identity fields cannot be changed';
    end if;
  elsif tg_table_name = 'transactions' then
    if new.id is distinct from old.id
       or new.pharmacy_id is distinct from old.pharmacy_id
       or new.customer_id is distinct from old.customer_id
       or new.operation_id is distinct from old.operation_id
       or new.created_at is distinct from old.created_at
       or new.created_by is distinct from old.created_by
       or new.created_device_id is distinct from old.created_device_id then
      raise exception 'immutable transaction identity fields cannot be changed';
    end if;
  end if;
  return new;
end $$;
create trigger protect_identity before update on customers for each row execute function pg_temp.protect_sync_identity_fields();
create trigger protect_identity before update on transactions for each row execute function pg_temp.protect_sync_identity_fields();
do $$
declare
  c uuid := '00000000-0000-4000-8000-000000000001';
  p uuid := '00000000-0000-4000-8000-000000000002';
  e uuid := '00000000-0000-4000-8000-000000000003';
  precise timestamptz := '2026-10-17T00:00:00.000123Z';
  rejected boolean := false;
begin
  insert into customers values(c,p,'fixture',0,precise,null);
  begin
    insert into customers values(c,p,'edited',0,date_trunc('milliseconds',precise),null)
      on conflict(id) do update set name=excluded.name, created_at=excluded.created_at;
  exception when raise_exception then
    if sqlerrm <> 'immutable customer identity fields cannot be changed' then raise; end if;
    rejected := true;
  end;
  if not rejected then raise exception 'Legacy upsert did not reproduce the identity rejection'; end if;
  update customers set name='edited', opening_debt=100 where id=c and pharmacy_id=p and deleted_at is null;
  if not exists(select 1 from customers where id=c and name='edited' and created_at=precise and opening_debt=100)
    then raise exception 'Mutable customer update lost protected timestamp'; end if;
  insert into transactions values(e,p,c,e,precise,c,p,'DEBT',7000,precise,null);
  rejected := false;
  begin
    insert into transactions values(e,p,c,e,date_trunc('milliseconds',precise),c,p,'DEBT',9000,precise,null)
      on conflict(id) do update set amount=excluded.amount,created_at=excluded.created_at;
  exception when raise_exception then
    if sqlerrm <> 'immutable transaction identity fields cannot be changed' then raise; end if;
    rejected := true;
  end;
  if not rejected then raise exception 'Legacy transaction upsert did not reject immutable fields'; end if;
  update transactions set amount=9000,notes='edited' where id=e and pharmacy_id=p and customer_id=c;
  if not exists(select 1 from transactions where id=e and amount=9000 and created_at=precise
    and operation_id=e and created_by=c and created_device_id=p)
    then raise exception 'Financial edit changed creation identity'; end if;
end $$;
rollback;

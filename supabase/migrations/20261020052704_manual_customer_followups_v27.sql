-- Isolated follow-up metadata. Never rewrites customers, transactions, ageing or notification jobs.
create table public.customer_manual_followups (
    customer_id uuid primary key references public.customers(id) on delete cascade,
    pharmacy_id uuid not null references public.pharmacies(id) on delete cascade,
    debt_keys text[] not null check(cardinality(debt_keys) between 1 and 10000)
);
create index manual_followups_pharmacy on public.customer_manual_followups(pharmacy_id);
alter table public.customer_manual_followups enable row level security;
revoke all on public.customer_manual_followups from public,anon,authenticated;
grant select on public.customer_manual_followups to authenticated;
create policy manual_followups_read_team on public.customer_manual_followups for select to authenticated
    using(pharmacy_id=(select raad_private.shopping_pharmacy()));

create function raad_private.followup_context_v27() returns jsonb
language plpgsql stable security definer set search_path='' as $$
declare tenant uuid:=raad_private.shopping_pharmacy();
begin
    if tenant is null or auth.uid() is null then raise exception 'FOLLOWUP_ACCESS_DENIED' using errcode='42501'; end if;
    return jsonb_build_object('owner',auth.uid(),'pharmacy',tenant,'cases',coalesce((
        select jsonb_agg(jsonb_build_object('customer',f.customer_id,'debts',f.debt_keys) order by f.customer_id)
        from public.customer_manual_followups f join public.customers c on c.id=f.customer_id
        where f.pharmacy_id=tenant and c.pharmacy_id=tenant and c.deleted_at is null),'[]'::jsonb));
end $$;
create function public.customer_followup_context_v27() returns jsonb
language sql stable security invoker set search_path='' as $$select raad_private.followup_context_v27()$$;

create function raad_private.add_followup_v27(target_customer uuid,debt_keys jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare tenant uuid:=raad_private.shopping_pharmacy(); keys text[]; merged text[]; c public.customers%rowtype;
begin
    if tenant is null or auth.uid() is null then raise exception 'FOLLOWUP_ACCESS_DENIED' using errcode='42501'; end if;
    if jsonb_typeof(debt_keys) is distinct from 'array' or jsonb_array_length(debt_keys) not between 1 and 10000 then
        raise exception 'FOLLOWUP_INVALID_DEBTS' using errcode='22023'; end if;
    select * into c from public.customers where id=target_customer and pharmacy_id=tenant and deleted_at is null;
    if not found then raise exception 'FOLLOWUP_CUSTOMER_NOT_SYNCED' using errcode='22023'; end if;
    select array_agg(distinct value order by value) into keys from jsonb_array_elements_text(debt_keys);
    if keys is null or array_position(keys,null) is not null or exists(select 1 from unnest(keys) k where char_length(k)>180) then
        raise exception 'FOLLOWUP_INVALID_DEBTS' using errcode='22023'; end if;
    -- Exact immutable transaction identities; an unsynced/wrong-customer debt waits locally.
    if exists(select 1 from unnest(keys) k where not (
        (c.opening_debt>0 and k='opening:'||floor(extract(epoch from c.created_at)*1000)::bigint::text||':'||c.opening_debt::bigint::text)
        or case when k ~ '^entry:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
            exists(select 1 from public.transactions t where t.id=substring(k from 7)::uuid and t.customer_id=c.id and t.pharmacy_id=tenant and t.type='DEBT' and t.deleted_at is null)
            else false end
        or exists(select 1 from public.customer_manual_followups f where f.customer_id=c.id and f.pharmacy_id=tenant and k=any(f.debt_keys))
    )) then raise exception 'FOLLOWUP_DEBT_NOT_SYNCED' using errcode='22023'; end if;
    insert into public.customer_manual_followups as existing(customer_id,pharmacy_id,debt_keys)
        values(target_customer,tenant,keys)
        on conflict(customer_id) do update set debt_keys=(select array_agg(distinct k order by k) from unnest(existing.debt_keys||excluded.debt_keys) k)
        where existing.pharmacy_id=tenant and not existing.debt_keys @> excluded.debt_keys;
    select f.debt_keys into merged from public.customer_manual_followups f where f.customer_id=target_customer and f.pharmacy_id=tenant;
    if merged is null then raise exception 'FOLLOWUP_ACCESS_DENIED' using errcode='42501'; end if;
    return jsonb_build_object('owner',auth.uid(),'pharmacy',tenant,'customer',target_customer,'debts',merged);
end $$;
create function public.add_customer_followup_v27(target_customer uuid,debt_keys jsonb) returns jsonb
language sql security invoker set search_path='' as $$select raad_private.add_followup_v27(target_customer,debt_keys)$$;
revoke all on function raad_private.followup_context_v27(),public.customer_followup_context_v27(),
    raad_private.add_followup_v27(uuid,jsonb),public.add_customer_followup_v27(uuid,jsonb) from public,anon;
grant execute on function raad_private.followup_context_v27(),public.customer_followup_context_v27(),
    raad_private.add_followup_v27(uuid,jsonb),public.add_customer_followup_v27(uuid,jsonb) to authenticated;
do $$begin
    if exists(select 1 from pg_publication where pubname='supabase_realtime') then
        alter publication supabase_realtime add table public.customer_manual_followups;
    end if;
end $$;

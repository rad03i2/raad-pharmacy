-- v25: isolated shopping recovery and append-only supplier payables. No customer ledger changes.
create table raad_private.shopping_trash (
    pharmacy_id uuid not null references public.pharmacies(id) on delete cascade,
    id uuid not null, deletion_id uuid not null, title text not null, note text not null,
    local_photo boolean not null, created_at timestamptz not null,
    created_by uuid references public.profiles(id) on delete set null,
    expires_at timestamptz not null, primary key(pharmacy_id,id)
);
create index shopping_trash_expiry on raad_private.shopping_trash(expires_at);
create index shopping_trash_creator on raad_private.shopping_trash(created_by);
create table raad_private.shopping_mutations (
    pharmacy_id uuid not null references public.pharmacies(id) on delete cascade,
    operation uuid not null, item_id uuid not null, action text not null,
    active boolean not null, trashed boolean not null, primary key(pharmacy_id,operation)
);
alter table raad_private.shopping_trash enable row level security;
alter table raad_private.shopping_mutations enable row level security;
revoke all on raad_private.shopping_trash,raad_private.shopping_mutations from public,anon,authenticated;

create function raad_private.purge_shopping_trash() returns void language plpgsql security definer set search_path='' as $$
declare row record;
begin
    for row in select pharmacy_id,id from raad_private.shopping_trash where expires_at<=clock_timestamp() loop
        perform pg_advisory_xact_lock(hashtextextended(row.pharmacy_id::text||':'||row.id::text,2401));
        delete from raad_private.shopping_trash where pharmacy_id=row.pharmacy_id and id=row.id and expires_at<=clock_timestamp();
        -- Existing content-free deletion marker is deliberately retained forever.
    end loop;
end $$;
revoke all on function raad_private.purge_shopping_trash() from public,anon,authenticated;
-- pg_cron already exists in this project; the isolated fixture intentionally has no cron extension.
do $$begin
    if exists(select 1 from pg_extension where extname='pg_cron') then
        perform cron.schedule('raad-shopping-trash-expiry-v25','* * * * *','select raad_private.purge_shopping_trash()');
    end if;
end $$;

create table public.supplier_ledger_entries (
    id uuid primary key, pharmacy_id uuid not null references public.pharmacies(id) on delete cascade,
    supplier text not null check(length(btrim(supplier)) between 1 and 160),
    kind text not null check(kind in ('DEBT','PAYMENT')),
    amount bigint not null check(amount>0 and amount<=1000000000000),
    occurred_on date not null check(occurred_on between date '2000-01-01' and date '2100-12-31'),
    note text not null default '' check(char_length(note)<=1000),
    created_by uuid references public.profiles(id) on delete set null,
    created_at timestamptz not null default now()
);
create index supplier_ledger_period on public.supplier_ledger_entries(pharmacy_id,occurred_on,id);
create index supplier_ledger_creator on public.supplier_ledger_entries(created_by);
alter table public.supplier_ledger_entries enable row level security;
revoke all on public.supplier_ledger_entries from public,anon,authenticated;
grant select on public.supplier_ledger_entries to authenticated;
create policy supplier_read_team on public.supplier_ledger_entries for select to authenticated
    using(pharmacy_id=(select raad_private.shopping_pharmacy()));

create function raad_private.supplier_add(entry_id uuid,supplier_name text,entry_kind text,entry_amount bigint,entry_day date,entry_note text)
returns jsonb language plpgsql security definer set search_path='' as $$
declare tenant uuid:=raad_private.shopping_pharmacy(); old public.supplier_ledger_entries%rowtype;
    name_value text:=btrim(coalesce(supplier_name,'')); note_value text:=btrim(coalesce(entry_note,''));
begin
    if tenant is null or auth.uid() is null then raise exception 'SUPPLIER_ACCESS_DENIED' using errcode='42501'; end if;
    if entry_id is null or length(name_value) not between 1 and 160 or char_length(note_value)>1000
        or entry_kind is null or entry_kind not in ('DEBT','PAYMENT') or entry_amount is null
        or entry_amount<=0 or entry_amount>1000000000000 or entry_day is null
        or entry_day not between date '2000-01-01' and date '2100-12-31' then
        raise exception 'SUPPLIER_INVALID_ENTRY' using errcode='22023';
    end if;
    perform pg_advisory_xact_lock(hashtextextended(entry_id::text,2501));
    select * into old from public.supplier_ledger_entries where id=entry_id;
    if found then
        if old.pharmacy_id<>tenant then raise exception 'SUPPLIER_ACCESS_DENIED' using errcode='42501'; end if;
        if (old.supplier,old.kind,old.amount,old.occurred_on,old.note) is distinct from
            (name_value,entry_kind,entry_amount,entry_day,note_value) then
            raise exception 'SUPPLIER_ID_CONFLICT' using errcode='22023';
        end if;
    else
        insert into public.supplier_ledger_entries(id,pharmacy_id,supplier,kind,amount,occurred_on,note,created_by)
            values(entry_id,tenant,name_value,entry_kind,entry_amount,entry_day,note_value,auth.uid());
        insert into public.medicine_shopping_signal(pharmacy_id) values(tenant)
            on conflict(pharmacy_id) do update set revision=public.medicine_shopping_signal.revision+1;
    end if;
    return jsonb_build_object('owner',auth.uid(),'pharmacy',tenant,'id',entry_id,'active',true);
end $$;
create function public.add_supplier_ledger_entry(entry_id uuid,supplier_name text,entry_kind text,entry_amount bigint,entry_day date,entry_note text)
returns jsonb language sql security invoker set search_path='' as $$
    select raad_private.supplier_add(entry_id,supplier_name,entry_kind,entry_amount,entry_day,entry_note)
$$;

create function raad_private.shopping_context_v25() returns jsonb language plpgsql security definer set search_path='' as $$
declare tenant uuid:=raad_private.shopping_pharmacy(); base jsonb;
begin
    base:=raad_private.shopping_context(); -- existing authenticated, tenant-scoped active list
    return base || jsonb_build_object('serverTime',floor(extract(epoch from clock_timestamp())*1000)::bigint,
        'trash',coalesce((select jsonb_agg(jsonb_build_object('item',jsonb_build_object('id',s.id,'title',s.title,'note',s.note,
            'hasPhoto',s.local_photo,'createdAt',floor(extract(epoch from s.created_at)*1000)::bigint,
            'createdBy',coalesce(p.display_name,'مستخدم')),'deletionId',s.deletion_id,
            'expiresAt',floor(extract(epoch from s.expires_at)*1000)::bigint) order by s.expires_at desc)
            from raad_private.shopping_trash s left join public.profiles p on p.id=s.created_by
            where s.pharmacy_id=tenant and s.expires_at>clock_timestamp()),'[]'::jsonb),
        'suppliers',coalesce((select jsonb_agg(jsonb_build_object('id',s.id,'supplier',s.supplier,'kind',s.kind,
            'amount',s.amount,'day',s.occurred_on,'note',s.note) order by s.occurred_on,s.id)
            from public.supplier_ledger_entries s where s.pharmacy_id=tenant),'[]'::jsonb));
end $$;
create function public.medicine_shopping_context_v25() returns jsonb language sql security invoker set search_path='' as $$
    select raad_private.shopping_context_v25()
$$;

create function raad_private.shopping_change_v25(operation_id uuid,item_id uuid,change_action text,deletion_token uuid,
    item_title text,item_note text,local_photo boolean,deleted_at_ms bigint)
returns jsonb language plpgsql security definer set search_path='' as $$
declare tenant uuid:=raad_private.shopping_pharmacy(); prior raad_private.shopping_mutations%rowtype;
    old public.medicine_shopping_items%rowtype; held raad_private.shopping_trash%rowtype;
    active_value boolean:=false; trash_value boolean:=false; expiry timestamptz;
begin
    if auth.uid() is null or tenant is null then raise exception 'SHOPPING_ACCESS_DENIED' using errcode='42501'; end if;
    if item_id is null or operation_id is null or change_action is null or change_action not in ('TRASH','RESTORE') then
        raise exception 'SHOPPING_INVALID_ITEM' using errcode='22023'; end if;
    perform pg_advisory_xact_lock(hashtextextended(tenant::text||':'||item_id::text,2401));
    -- Also serialize reuse of a mutation UUID for a different item.
    perform pg_advisory_xact_lock(hashtextextended(tenant::text||':'||operation_id::text,2502));
    select * into prior from raad_private.shopping_mutations where pharmacy_id=tenant and operation=operation_id;
    if found then
        if prior.item_id<>item_id or prior.action<>change_action then raise exception 'SHOPPING_ID_CONFLICT' using errcode='22023'; end if;
        return jsonb_build_object('owner',auth.uid(),'pharmacy',tenant,'id',item_id,'operation',operation_id,'active',prior.active,'trashed',prior.trashed);
    end if;
    if exists(select 1 from public.medicine_shopping_items where id=item_id and pharmacy_id<>tenant) then
        raise exception 'SHOPPING_ACCESS_DENIED' using errcode='42501'; end if;
    select * into held from raad_private.shopping_trash where pharmacy_id=tenant and id=item_id;
    if change_action='TRASH' then
        if held.id is null and not exists(select 1 from raad_private.medicine_shopping_deleted where pharmacy_id=tenant and id=item_id) then
            select * into old from public.medicine_shopping_items where pharmacy_id=tenant and id=item_id;
            if old.id is null then
                if local_photo is null or char_length(coalesce(item_title,''))>240 or char_length(coalesce(item_note,''))>1000
                    or (btrim(coalesce(item_title,''))='' and not local_photo) then
                    raise exception 'SHOPPING_INVALID_ITEM' using errcode='22023'; end if;
                old.id:=item_id; old.title:=btrim(coalesce(item_title,''));old.note:=btrim(coalesce(item_note,''));
                old.local_photo:=local_photo;old.created_at:=clock_timestamp();old.created_by:=auth.uid();
            end if;
            if deleted_at_ms is null or deleted_at_ms<0 or deleted_at_ms>4102444800000 then
                raise exception 'SHOPPING_INVALID_ITEM' using errcode='22023'; end if;
            expiry:=least(clock_timestamp(),to_timestamp(deleted_at_ms/1000.0))+interval '24 hours';
            insert into raad_private.medicine_shopping_deleted(pharmacy_id,id) values(tenant,item_id) on conflict do nothing;
            if expiry>clock_timestamp() then
                insert into raad_private.shopping_trash(pharmacy_id,id,deletion_id,title,note,local_photo,created_at,created_by,expires_at)
                    values(tenant,item_id,operation_id,old.title,old.note,old.local_photo,old.created_at,old.created_by,expiry);
            end if;
            delete from public.medicine_shopping_items where pharmacy_id=tenant and id=item_id;
        end if;
    else
        if held.id is not null and held.deletion_id=deletion_token and held.expires_at>clock_timestamp() then
            insert into public.medicine_shopping_items(id,pharmacy_id,title,note,local_photo,created_at,created_by)
                values(held.id,tenant,held.title,held.note,held.local_photo,held.created_at,held.created_by);
            delete from raad_private.shopping_trash where pharmacy_id=tenant and id=item_id;
            delete from raad_private.medicine_shopping_deleted where pharmacy_id=tenant and id=item_id;
        end if;
    end if;
    select exists(select 1 from public.medicine_shopping_items where pharmacy_id=tenant and id=item_id),
        exists(select 1 from raad_private.shopping_trash where pharmacy_id=tenant and id=item_id and expires_at>clock_timestamp())
        into active_value,trash_value;
    insert into raad_private.shopping_mutations values(tenant,operation_id,item_id,change_action,active_value,trash_value);
    insert into public.medicine_shopping_signal(pharmacy_id) values(tenant)
        on conflict(pharmacy_id) do update set revision=public.medicine_shopping_signal.revision+1;
    return jsonb_build_object('owner',auth.uid(),'pharmacy',tenant,'id',item_id,'operation',operation_id,'active',active_value,'trashed',trash_value);
end $$;
create function public.change_medicine_shopping_v25(operation_id uuid,item_id uuid,change_action text,deletion_token uuid,
    item_title text,item_note text,local_photo boolean,deleted_at_ms bigint)
returns jsonb language sql security invoker set search_path='' as $$
    select raad_private.shopping_change_v25(operation_id,item_id,change_action,deletion_token,item_title,item_note,local_photo,deleted_at_ms)
$$;

-- v24 keeps its established permanent-delete contract, including when it meets a v25 trash item.
create or replace function raad_private.complete_shopping_item(item_id uuid) returns jsonb language plpgsql security definer set search_path='' as $$
declare tenant uuid:=raad_private.shopping_pharmacy();
begin
    if auth.uid() is null or tenant is null then raise exception 'SHOPPING_ACCESS_DENIED' using errcode='42501'; end if;
    if item_id is null then raise exception 'SHOPPING_INVALID_ITEM' using errcode='22023'; end if;
    perform pg_advisory_xact_lock(hashtextextended(tenant::text||':'||item_id::text,2401));
    if exists(select 1 from public.medicine_shopping_items where id=item_id and pharmacy_id<>tenant) then
        raise exception 'SHOPPING_ACCESS_DENIED' using errcode='42501'; end if;
    insert into raad_private.medicine_shopping_deleted(pharmacy_id,id) values(tenant,item_id) on conflict do nothing;
    delete from raad_private.shopping_trash where pharmacy_id=tenant and id=item_id;
    delete from public.medicine_shopping_items where pharmacy_id=tenant and id=item_id;
    insert into public.medicine_shopping_signal(pharmacy_id) values(tenant)
        on conflict(pharmacy_id) do update set revision=public.medicine_shopping_signal.revision+1;
    return jsonb_build_object('owner',auth.uid(),'pharmacy',tenant,'id',item_id,'active',false);
end $$;
revoke all on function raad_private.supplier_add(uuid,text,text,bigint,date,text),public.add_supplier_ledger_entry(uuid,text,text,bigint,date,text),
    raad_private.shopping_context_v25(),public.medicine_shopping_context_v25(),
    raad_private.shopping_change_v25(uuid,uuid,text,uuid,text,text,boolean,bigint),
    public.change_medicine_shopping_v25(uuid,uuid,text,uuid,text,text,boolean,bigint) from public,anon;
grant execute on function raad_private.supplier_add(uuid,text,text,bigint,date,text),public.add_supplier_ledger_entry(uuid,text,text,bigint,date,text),
    raad_private.shopping_context_v25(),public.medicine_shopping_context_v25(),
    raad_private.shopping_change_v25(uuid,uuid,text,uuid,text,text,boolean,bigint),
    public.change_medicine_shopping_v25(uuid,uuid,text,uuid,text,text,boolean,bigint) to authenticated;

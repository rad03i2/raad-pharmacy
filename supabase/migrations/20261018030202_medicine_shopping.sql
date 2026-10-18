-- Independent shopping metadata. No ledger/auth/notification/Storage schema or trigger changes.
create table public.medicine_shopping_items (
    id uuid primary key,
    pharmacy_id uuid not null references public.pharmacies(id) on delete cascade,
    title text not null check (char_length(title) <= 240),
    note text not null default '' check (char_length(note) <= 1000),
    local_photo boolean not null default false,
    created_at timestamptz not null default now(),
    created_by uuid references public.profiles(id) on delete set null,
    check (length(btrim(title)) > 0 or local_photo)
);
create index medicine_shopping_pharmacy_order on public.medicine_shopping_items(pharmacy_id,created_at,id);
create index medicine_shopping_creator on public.medicine_shopping_items(created_by);
-- Only opaque IDs remain after purchase, to reject a delayed offline create forever.
-- No title, note, image, file path, customer, financial amount or archive is retained here.
create table raad_private.medicine_shopping_deleted (
    pharmacy_id uuid not null references public.pharmacies(id) on delete cascade,
    id uuid not null,
    primary key(pharmacy_id,id)
);
create table public.medicine_shopping_signal (
    pharmacy_id uuid primary key references public.pharmacies(id) on delete cascade,
    revision bigint not null default 1
);
alter table public.medicine_shopping_items enable row level security;
alter table public.medicine_shopping_signal enable row level security;
alter table raad_private.medicine_shopping_deleted enable row level security;
revoke all on public.medicine_shopping_items, public.medicine_shopping_signal,
    raad_private.medicine_shopping_deleted from public,anon,authenticated;
grant select on public.medicine_shopping_items,public.medicine_shopping_signal to authenticated;

create function raad_private.shopping_pharmacy() returns uuid language sql stable security definer set search_path='' as $$
    select p.pharmacy_id from public.profiles p join public.pharmacies f on f.id=p.pharmacy_id
    where p.id=auth.uid() and p.deleted_at is null and not p.is_hidden and f.deleted_at is null
$$;
revoke all on function raad_private.shopping_pharmacy() from public,anon;
grant execute on function raad_private.shopping_pharmacy() to authenticated;
create policy shopping_read_team on public.medicine_shopping_items for select to authenticated
    using (pharmacy_id=(select raad_private.shopping_pharmacy()));
create policy shopping_signal_read_team on public.medicine_shopping_signal for select to authenticated
    using (pharmacy_id=(select raad_private.shopping_pharmacy()));

create function raad_private.shopping_context() returns jsonb language plpgsql security definer set search_path='' as $$
declare tenant uuid := raad_private.shopping_pharmacy();
begin
    if auth.uid() is null or tenant is null then raise exception 'SHOPPING_ACCESS_DENIED' using errcode='42501'; end if;
    return jsonb_build_object('owner',auth.uid(),'pharmacy',tenant,'items',coalesce((
        select jsonb_agg(jsonb_build_object('id',s.id,'title',s.title,'note',s.note,'hasPhoto',s.local_photo,
            'createdAt',floor(extract(epoch from s.created_at)*1000)::bigint,
            'createdBy',coalesce(p.display_name,'مستخدم')) order by s.created_at,s.id)
        from public.medicine_shopping_items s left join public.profiles p on p.id=s.created_by
        where s.pharmacy_id=tenant
    ),'[]'::jsonb));
end $$;
create function public.medicine_shopping_context() returns jsonb language sql security invoker set search_path='' as $$
    select raad_private.shopping_context()
$$;

create function raad_private.add_shopping_item(item_id uuid,item_title text,item_note text,local_photo boolean)
returns jsonb language plpgsql security definer set search_path='' as $$
declare tenant uuid := raad_private.shopping_pharmacy(); existing public.medicine_shopping_items%rowtype;
    title_value text := btrim(coalesce(item_title,'')); note_value text := btrim(coalesce(item_note,''));
begin
    if auth.uid() is null or tenant is null then raise exception 'SHOPPING_ACCESS_DENIED' using errcode='42501'; end if;
    if item_id is null or local_photo is null or char_length(title_value)>240 or char_length(note_value)>1000
        or (title_value='' and not local_photo) then raise exception 'SHOPPING_INVALID_ITEM' using errcode='22023'; end if;
    perform pg_advisory_xact_lock(hashtextextended(tenant::text||':'||item_id::text,2401));
    if exists(select 1 from raad_private.medicine_shopping_deleted d where d.pharmacy_id=tenant and d.id=item_id) then
        return jsonb_build_object('owner',auth.uid(),'pharmacy',tenant,'id',item_id,'active',false);
    end if;
    select * into existing from public.medicine_shopping_items where id=item_id;
    if found then
        if existing.pharmacy_id<>tenant then raise exception 'SHOPPING_ACCESS_DENIED' using errcode='42501'; end if;
        if existing.title<>title_value or existing.note<>note_value or existing.local_photo<>local_photo then
            raise exception 'SHOPPING_ID_CONFLICT' using errcode='22023';
        end if;
    else
        insert into public.medicine_shopping_items(id,pharmacy_id,title,note,local_photo,created_by)
            values(item_id,tenant,title_value,note_value,local_photo,auth.uid());
        insert into public.medicine_shopping_signal(pharmacy_id) values(tenant)
            on conflict(pharmacy_id) do update set revision=public.medicine_shopping_signal.revision+1;
    end if;
    return jsonb_build_object('owner',auth.uid(),'pharmacy',tenant,'id',item_id,'active',true);
end $$;
create function public.add_medicine_shopping_item(item_id uuid,item_title text,item_note text,local_photo boolean)
returns jsonb language sql security invoker set search_path='' as $$
    select raad_private.add_shopping_item(item_id,item_title,item_note,local_photo)
$$;

create function raad_private.complete_shopping_item(item_id uuid) returns jsonb language plpgsql security definer set search_path='' as $$
declare tenant uuid := raad_private.shopping_pharmacy(); changed integer;
begin
    if auth.uid() is null or tenant is null then raise exception 'SHOPPING_ACCESS_DENIED' using errcode='42501'; end if;
    if item_id is null then raise exception 'SHOPPING_INVALID_ITEM' using errcode='22023'; end if;
    perform pg_advisory_xact_lock(hashtextextended(tenant::text||':'||item_id::text,2401));
    if exists(select 1 from public.medicine_shopping_items where id=item_id and pharmacy_id<>tenant) then
        raise exception 'SHOPPING_ACCESS_DENIED' using errcode='42501';
    end if;
    insert into raad_private.medicine_shopping_deleted(pharmacy_id,id) values(tenant,item_id) on conflict do nothing;
    delete from public.medicine_shopping_items where pharmacy_id=tenant and id=item_id;
    get diagnostics changed = row_count;
    if changed>0 then
        insert into public.medicine_shopping_signal(pharmacy_id) values(tenant)
            on conflict(pharmacy_id) do update set revision=public.medicine_shopping_signal.revision+1;
    end if;
    return jsonb_build_object('owner',auth.uid(),'pharmacy',tenant,'id',item_id,'active',false);
end $$;
create function public.complete_medicine_shopping_item(item_id uuid) returns jsonb language sql security invoker set search_path='' as $$
    select raad_private.complete_shopping_item(item_id)
$$;

revoke all on function raad_private.shopping_context(),public.medicine_shopping_context(),
    raad_private.add_shopping_item(uuid,text,text,boolean),public.add_medicine_shopping_item(uuid,text,text,boolean),
    raad_private.complete_shopping_item(uuid),public.complete_medicine_shopping_item(uuid) from public,anon;
grant execute on function raad_private.shopping_context(),public.medicine_shopping_context(),
    raad_private.add_shopping_item(uuid,text,text,boolean),public.add_medicine_shopping_item(uuid,text,text,boolean),
    raad_private.complete_shopping_item(uuid),public.complete_medicine_shopping_item(uuid) to authenticated;

do $$begin
    if exists(select 1 from pg_publication where pubname='supabase_realtime') then
        alter publication supabase_realtime add table public.medicine_shopping_signal;
    end if;
end $$;

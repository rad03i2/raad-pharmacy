-- v26: an isolated catalog preserves empty named areas. Customer.area remains backward-compatible text.
-- No changes to financial tables, triggers, authentication, notifications or existing RPC response shapes.
create table public.customer_area_catalog (
    pharmacy_id uuid not null references public.pharmacies(id) on delete cascade,
    name text not null check(char_length(name) between 1 and 160 and name=btrim(name)),
    primary key(pharmacy_id,name)
);
alter table public.customer_area_catalog enable row level security;
revoke all on public.customer_area_catalog from public,anon,authenticated;
grant select on public.customer_area_catalog to authenticated;
create policy area_catalog_read_team on public.customer_area_catalog for select to authenticated
    using(pharmacy_id=(select raad_private.shopping_pharmacy()));

create function raad_private.area_catalog_add(area_name text) returns jsonb
language plpgsql security definer set search_path='' as $$
declare tenant uuid:=raad_private.shopping_pharmacy(); name_value text:=btrim(regexp_replace(coalesce(area_name,''),'\s+',' ','g'));
    inserted integer;
begin
    if tenant is null or auth.uid() is null then raise exception 'AREA_ACCESS_DENIED' using errcode='42501'; end if;
    if char_length(name_value) not between 1 and 160 then raise exception 'AREA_INVALID_NAME' using errcode='22023'; end if;
    insert into public.customer_area_catalog(pharmacy_id,name) values(tenant,name_value) on conflict do nothing;
    get diagnostics inserted=row_count;
    if inserted>0 then
        insert into public.medicine_shopping_signal(pharmacy_id) values(tenant)
            on conflict(pharmacy_id) do update set revision=public.medicine_shopping_signal.revision+1;
    end if;
    return jsonb_build_object('owner',auth.uid(),'pharmacy',tenant,'name',name_value);
end $$;
create function public.add_customer_area_v26(area_name text) returns jsonb
language sql security invoker set search_path='' as $$select raad_private.area_catalog_add(area_name)$$;

create function raad_private.shopping_context_v26() returns jsonb
language sql stable security definer set search_path='' as $$
    select raad_private.shopping_context_v25() || jsonb_build_object('areas',coalesce((
        select jsonb_agg(name order by name) from public.customer_area_catalog
        where pharmacy_id=raad_private.shopping_pharmacy()),'[]'::jsonb))
$$;
create function public.medicine_shopping_context_v26() returns jsonb
language sql stable security invoker set search_path='' as $$select raad_private.shopping_context_v26()$$;
revoke all on function raad_private.area_catalog_add(text),public.add_customer_area_v26(text),
    raad_private.shopping_context_v26(),public.medicine_shopping_context_v26() from public,anon;
grant execute on function raad_private.area_catalog_add(text),public.add_customer_area_v26(text),
    raad_private.shopping_context_v26(),public.medicine_shopping_context_v26() to authenticated;

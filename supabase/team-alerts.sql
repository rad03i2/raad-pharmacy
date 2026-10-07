-- Targeted account alerts; existing financial notifications remain pharmacy-wide.
begin;
alter table public.notification_events add column if not exists recipient_user_id uuid;
alter table public.notification_events drop constraint if exists notification_events_event_type_check;
alter table public.notification_events add constraint notification_events_event_type_check check
 (event_type in ('DEBT_CREATED','PAYMENT_CREATED','TRANSACTION_UPDATED','TRANSACTION_DELETED','TEAM_ALERT'));
alter table public.notification_events add constraint notification_events_recipient_fkey
 foreign key (pharmacy_id, recipient_user_id) references public.profiles(pharmacy_id,id);
alter table public.notification_events add constraint notification_events_recipient_shape check
 ((event_type='TEAM_ALERT' and recipient_user_id is not null and actor_user_id is not null
   and recipient_user_id <> actor_user_id and customer_id is null and transaction_id is null and amount=0)
  or (event_type <> 'TEAM_ALERT' and recipient_user_id is null));
create index notification_events_recipient_idx on public.notification_events(pharmacy_id,recipient_user_id,created_at);
create index notification_events_alert_rate_idx on public.notification_events(actor_user_id,recipient_user_id,created_at desc)
 where event_type='TEAM_ALERT';
drop policy notification_events_read_same_pharmacy on public.notification_events;
create policy notification_events_read_same_pharmacy on public.notification_events for select to authenticated
 using (pharmacy_id = (select raad_private.current_pharmacy()) and
 (recipient_user_id is null or recipient_user_id=(select auth.uid()) or actor_user_id=(select auth.uid())));
create or replace function raad_private.send_user_alert(target_user_id uuid, sender_device_id uuid, alert_id uuid)
returns uuid language plpgsql security definer set search_path='' as $$
declare actor public.profiles%rowtype; existing public.notification_events%rowtype;
begin
 if auth.uid() is null then raise exception 'AUTH_REQUIRED' using errcode='42501'; end if;
 select * into actor from public.profiles where id=auth.uid();
 if actor.id is null or actor.role not in ('MANAGER','MAINTAINER') or alert_id is null then
  raise exception 'ALERT_NOT_ALLOWED' using errcode='42501'; end if;
 if target_user_id=actor.id or not exists (select 1 from public.profiles p where p.id=target_user_id
   and p.pharmacy_id=actor.pharmacy_id and p.role='MANAGER' and not p.is_hidden) then
  raise exception 'ALERT_RECIPIENT_INVALID' using errcode='42501'; end if;
 if not exists (select 1 from public.devices d where d.id=sender_device_id and d.user_id=actor.id
   and d.pharmacy_id=actor.pharmacy_id and d.deleted_at is null) then
  raise exception 'ALERT_DEVICE_INVALID' using errcode='42501'; end if;
 perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(actor.id::text||target_user_id::text,0));
 select * into existing from public.notification_events where id=alert_id;
 if found then
  if existing.event_type='TEAM_ALERT' and existing.actor_user_id=actor.id and existing.recipient_user_id=target_user_id then
   return existing.id;
  end if;
  raise exception 'ALERT_ID_INVALID' using errcode='42501';
 end if;
 if exists (select 1 from public.notification_events e where e.actor_user_id=actor.id and e.recipient_user_id=target_user_id
   and e.event_type='TEAM_ALERT' and e.created_at>pg_catalog.now()-interval '30 seconds') then
  raise exception 'ALERT_RATE_LIMIT' using errcode='P0001'; end if;
 insert into public.notification_events(id,pharmacy_id,actor_user_id,actor_display_name,actor_device_id,
   recipient_user_id,event_type,amount)
 values(alert_id,actor.pharmacy_id,actor.id,actor.display_name,sender_device_id,target_user_id,'TEAM_ALERT',0);
 return alert_id;
end $$;
revoke all on function raad_private.send_user_alert(uuid,uuid,uuid) from public,anon;
grant execute on function raad_private.send_user_alert(uuid,uuid,uuid) to authenticated;
create or replace function public.send_user_alert(target_user_id uuid, sender_device_id uuid, alert_id uuid)
returns uuid language sql security invoker set search_path='' as $$
 select raad_private.send_user_alert(target_user_id,sender_device_id,alert_id);
$$;
revoke all on function public.send_user_alert(uuid,uuid,uuid) from public,anon;
grant execute on function public.send_user_alert(uuid,uuid,uuid) to authenticated;
notify pgrst,'reload schema';
commit;

-- Private text messages reuse the durable notification stream.
begin;
alter table public.notification_events add column if not exists message_body text;
alter table public.notification_events add column if not exists message_read_at timestamptz;
alter table public.notification_events drop constraint if exists notification_events_event_type_check;
alter table public.notification_events add constraint notification_events_event_type_check check
 (event_type in ('DEBT_CREATED','PAYMENT_CREATED','TRANSACTION_UPDATED','TRANSACTION_DELETED','TEAM_ALERT','TEAM_MESSAGE'));
alter table public.notification_events drop constraint if exists notification_events_recipient_shape;
alter table public.notification_events add constraint notification_events_recipient_shape check
 ((event_type in ('TEAM_ALERT','TEAM_MESSAGE') and recipient_user_id is not null and actor_user_id is not null
   and recipient_user_id <> actor_user_id and customer_id is null and transaction_id is null and amount=0)
  or (event_type not in ('TEAM_ALERT','TEAM_MESSAGE') and recipient_user_id is null));
alter table public.notification_events add constraint notification_events_message_shape check
 ((event_type='TEAM_MESSAGE' and message_body is not null and char_length(btrim(message_body,E' \n\r\t')) between 1 and 500
   and (message_read_at is null or message_read_at >= created_at))
  or (event_type<>'TEAM_MESSAGE' and message_body is null and message_read_at is null));
create index notification_events_unread_messages_idx
 on public.notification_events(recipient_user_id,created_at,id)
 where event_type='TEAM_MESSAGE' and message_read_at is null;
create index notification_events_message_rate_idx
 on public.notification_events(actor_user_id,recipient_user_id,created_at desc) where event_type='TEAM_MESSAGE';
alter table public.notification_events enable row level security;
-- Existing SELECT policy already limits directed events to sender and recipient in the same pharmacy.
-- No client INSERT/UPDATE grants or policies: all mutations validate the authenticated actor.
create or replace function raad_private.send_team_message(target_user_id uuid, sender_device_id uuid, message_id uuid, message_text text)
returns uuid language plpgsql security definer set search_path='' as $$
declare actor public.profiles%rowtype; existing public.notification_events%rowtype;
 body text := pg_catalog.btrim(message_text,E' \n\r\t');
begin
 if auth.uid() is null then raise exception 'AUTH_REQUIRED' using errcode='42501'; end if;
 select * into actor from public.profiles where id=auth.uid();
 if actor.id is null or actor.role not in ('MANAGER','MAINTAINER') or message_id is null then
  raise exception 'MESSAGE_NOT_ALLOWED' using errcode='42501'; end if;
 if body is null or pg_catalog.char_length(body) not between 1 and 500 then
  raise exception 'MESSAGE_TEXT_INVALID' using errcode='22023'; end if;
 if target_user_id=actor.id or not exists (select 1 from public.profiles p where p.id=target_user_id
  and p.pharmacy_id=actor.pharmacy_id and p.role='MANAGER' and not p.is_hidden) then
  raise exception 'MESSAGE_RECIPIENT_INVALID' using errcode='42501'; end if;
 if not exists (select 1 from public.devices d where d.id=sender_device_id and d.user_id=actor.id
  and d.pharmacy_id=actor.pharmacy_id and d.deleted_at is null) then
  raise exception 'MESSAGE_DEVICE_INVALID' using errcode='42501'; end if;
 perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(actor.id::text||target_user_id::text,1));
 select * into existing from public.notification_events where id=message_id;
 if found then
  if existing.event_type='TEAM_MESSAGE' and existing.actor_user_id=actor.id
    and existing.recipient_user_id=target_user_id and existing.message_body=body then return existing.id; end if;
  raise exception 'MESSAGE_ID_INVALID' using errcode='42501';
 end if;
 if exists (select 1 from public.notification_events e where e.actor_user_id=actor.id and e.recipient_user_id=target_user_id
  and e.event_type='TEAM_MESSAGE' and e.created_at>pg_catalog.now()-interval '3 seconds') then
  raise exception 'MESSAGE_RATE_LIMIT' using errcode='P0001'; end if;
 insert into public.notification_events(id,pharmacy_id,actor_user_id,actor_display_name,actor_device_id,
  recipient_user_id,event_type,amount,message_body)
 values(message_id,actor.pharmacy_id,actor.id,actor.display_name,sender_device_id,target_user_id,'TEAM_MESSAGE',0,body);
 return message_id;
end $$;
create or replace function raad_private.read_team_messages(message_ids uuid[])
returns integer language plpgsql security definer set search_path='' as $$
declare actor public.profiles%rowtype; changed integer;
begin
 if auth.uid() is null then raise exception 'AUTH_REQUIRED' using errcode='42501'; end if;
 select * into actor from public.profiles where id=auth.uid();
 if actor.id is null or actor.role not in ('MANAGER','MAINTAINER') then
  raise exception 'MESSAGE_NOT_ALLOWED' using errcode='42501'; end if;
 if message_ids is null or pg_catalog.cardinality(message_ids)>100 then
  raise exception 'MESSAGE_IDS_INVALID' using errcode='22023'; end if;
 update public.notification_events set message_read_at=pg_catalog.clock_timestamp()
 where id=any(message_ids) and event_type='TEAM_MESSAGE' and pharmacy_id=actor.pharmacy_id
  and recipient_user_id=actor.id and message_read_at is null;
 get diagnostics changed = row_count;
 return changed;
end $$;
revoke all on function raad_private.send_team_message(uuid,uuid,uuid,text),raad_private.read_team_messages(uuid[]) from public,anon;
grant execute on function raad_private.send_team_message(uuid,uuid,uuid,text),raad_private.read_team_messages(uuid[]) to authenticated;
create or replace function public.send_team_message(target_user_id uuid, sender_device_id uuid, message_id uuid, message_text text)
returns uuid language sql security invoker set search_path='' as $$
 select raad_private.send_team_message(target_user_id,sender_device_id,message_id,message_text);
$$;
create or replace function public.read_team_messages(message_ids uuid[])
returns integer language sql security invoker set search_path='' as $$
 select raad_private.read_team_messages(message_ids);
$$;
revoke all on function public.send_team_message(uuid,uuid,uuid,text),public.read_team_messages(uuid[]) from public,anon;
grant execute on function public.send_team_message(uuid,uuid,uuid,text),public.read_team_messages(uuid[]) to authenticated;
notify pgrst,'reload schema';
commit;

-- Requires the deployed Raad schema and a fixture-capable database administrator.
-- Every synthetic identity and event is rolled back. No FCM request is committed.
DO $test$
declare
 ph uuid:=gen_random_uuid(); ph2 uuid:=gen_random_uuid();
 a uuid:=gen_random_uuid(); b uuid:=gen_random_uuid(); c uuid:=gen_random_uuid();
 d uuid:=gen_random_uuid(); d2 uuid:=gen_random_uuid();
 msg uuid:=gen_random_uuid(); reply uuid:=gen_random_uuid();
 alert uuid:=gen_random_uuid(); alert2 uuid:=gen_random_uuid(); done boolean:=false;
begin
 begin
  insert into public.pharmacies(id,name) values(ph,'isolated-messaging-79'),(ph2,'isolated-other-79');
  insert into auth.users(id) values(a),(b),(c);
  insert into public.profiles(id,pharmacy_id,role,display_name)
   values(a,ph,'MANAGER','test-a'),(b,ph,'MANAGER','test-b'),(c,ph2,'MANAGER','test-c');
  perform set_config('request.jwt.claims',json_build_object('sub',a,'role','authenticated')::text,true);
  execute 'set local role authenticated';
  begin
   perform public.send_team_message(b,d,msg,'isolated test'); raise exception 'missing device accepted';
  exception when insufficient_privilege then
   if sqlerrm not like '%MESSAGE_DEVICE_INVALID%' then raise; end if;
  end;
  insert into public.devices(id,pharmacy_id,user_id,name) values(d,ph,a,'isolated test');
  if public.send_team_message(b,d,msg,'isolated test')<>msg then raise exception 'invalid receipt'; end if;
  if public.send_team_message(b,d,msg,'isolated test')<>msg then raise exception 'idempotency failed'; end if;
  if (select count(*) from public.notification_events where id=msg)<>1 then raise exception 'duplicate event'; end if;
  begin
   perform public.send_team_message(c,d,gen_random_uuid(),'isolated test'); raise exception 'cross pharmacy accepted';
  exception when insufficient_privilege then null;
  end;
  begin
   perform public.send_team_message(b,d,gen_random_uuid(),'second'); raise exception 'rate limit missing';
  exception when raise_exception then
   if sqlerrm not like '%MESSAGE_RATE_LIMIT%' then raise; end if;
  end;
  if public.send_user_alert(b,d,alert)<>alert or public.send_user_alert(b,d,alert)<>alert then
   raise exception 'alert receipt invalid'; end if;
  if public.read_team_messages(array[msg])<>0 then raise exception 'sender read recipient inbox'; end if;
  perform set_config('request.jwt.claims',json_build_object('sub',b,'role','authenticated')::text,true);
  insert into public.devices(id,pharmacy_id,user_id,name) values(d2,ph,b,'isolated test');
  if public.send_team_message(a,d2,reply,'isolated reply')<>reply then raise exception 'reply failed'; end if;
  if public.send_user_alert(a,d2,alert2)<>alert2 then raise exception 'reverse alert failed'; end if;
  if public.read_team_messages(array[msg])<>1 or public.read_team_messages(array[msg])<>0 then
   raise exception 'read idempotency failed'; end if;
  done:=true;
  raise exception using errcode='Z0079',message='rollback all isolated fixtures';
 exception when sqlstate 'Z0079' then null;
 end;
 if not done then raise exception 'test did not complete'; end if;
 if exists(select 1 from auth.users where id in(a,b,c)) or
    exists(select 1 from public.notification_events where id in(msg,reply,alert,alert2)) then
  raise exception 'fixture rollback failed'; end if;
end $test$;
select 'PASS: isolated RPC receipt, missing device, bidirectional message/alert, idempotency, rate limit, cross-pharmacy RLS, recipient read; all fixtures rolled back' as result;

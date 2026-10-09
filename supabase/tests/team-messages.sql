
begin;
select set_config('request.jwt.claim.sub','849af9a3-6e8d-40c1-a640-367579434b62',true);
select set_config('request.jwt.claim.role','authenticated',true);
set local role authenticated;
select public.send_team_message('2c276bbb-6c54-4836-a750-24564988b2fd','de3caa1d-0f4b-4b1a-8900-31a0265a40b1','719a8c41-8738-47f6-bc8b-bb69b6d24371','  رسالة اختبار  ');
select public.send_team_message('2c276bbb-6c54-4836-a750-24564988b2fd','de3caa1d-0f4b-4b1a-8900-31a0265a40b1','719a8c41-8738-47f6-bc8b-bb69b6d24371','رسالة اختبار');
do $$ begin
 if (select count(*) from public.notification_events where id='719a8c41-8738-47f6-bc8b-bb69b6d24371')<>1 then raise exception 'idempotency failed'; end if;
 if public.read_team_messages(array['719a8c41-8738-47f6-bc8b-bb69b6d24371'::uuid])<>0 then raise exception 'sender read permission failed'; end if;
 begin
  perform public.send_team_message('2c276bbb-6c54-4836-a750-24564988b2fd','de3caa1d-0f4b-4b1a-8900-31a0265a40b1','719a8c41-8738-47f6-bc8b-bb69b6d24371','نص مختلف');
  raise exception 'different text allowed';
 exception when insufficient_privilege then null; end;
 begin
  perform public.send_team_message('2c276bbb-6c54-4836-a750-24564988b2fd','de3caa1d-0f4b-4b1a-8900-31a0265a40b1','a1f5f2db-c07e-414f-8a46-c48f4237d055','نص ثان');
  raise exception 'rate limit failed';
 exception when raise_exception then if sqlerrm<>'MESSAGE_RATE_LIMIT' then raise; end if; end;
 begin
  perform public.send_team_message('2c276bbb-6c54-4836-a750-24564988b2fd','de3caa1d-0f4b-4b1a-8900-31a0265a40b1','a1f5f2db-c07e-414f-8a46-c48f4237d055','   ');
  raise exception 'blank text allowed';
 exception when invalid_parameter_value then null; end;
 begin
  perform public.send_team_message('2c276bbb-6c54-4836-a750-24564988b2fd','de3caa1d-0f4b-4b1a-8900-31a0265a40b1','a1f5f2db-c07e-414f-8a46-c48f4237d055',repeat('س',501));
  raise exception 'oversize text allowed';
 exception when invalid_parameter_value then null; end;
 begin
  perform public.send_team_message('849af9a3-6e8d-40c1-a640-367579434b62','de3caa1d-0f4b-4b1a-8900-31a0265a40b1','a1f5f2db-c07e-414f-8a46-c48f4237d055','نص');
  raise exception 'self message allowed';
 exception when insufficient_privilege then null; end;
 begin
  perform public.send_team_message('8a9dc818-b36d-48e9-9872-9fc36bf12ffd','d78831b5-1c8f-4c97-a0c8-457f312bb70e','a1f5f2db-c07e-414f-8a46-c48f4237d055','نص');
  raise exception 'other device allowed';
 exception when insufficient_privilege then null; end;
end $$;
reset role;
select set_config('request.jwt.claim.sub','8a9dc818-b36d-48e9-9872-9fc36bf12ffd',true);
set local role authenticated;
do $$ begin
 if exists (select 1 from public.notification_events where id='719a8c41-8738-47f6-bc8b-bb69b6d24371') then raise exception 'third account can read'; end if;
 if public.read_team_messages(array['719a8c41-8738-47f6-bc8b-bb69b6d24371'::uuid])<>0 then raise exception 'third account can mark read'; end if;
end $$;
reset role;
select set_config('request.jwt.claim.sub','2c276bbb-6c54-4836-a750-24564988b2fd',true);
set local role authenticated;
do $$ begin
 if not exists (select 1 from public.notification_events where id='719a8c41-8738-47f6-bc8b-bb69b6d24371' and message_body='رسالة اختبار' and message_read_at is null) then raise exception 'recipient cannot read'; end if;
 if public.read_team_messages(array['719a8c41-8738-47f6-bc8b-bb69b6d24371'::uuid])<>1 then raise exception 'recipient cannot mark read'; end if;
 if public.read_team_messages(array['719a8c41-8738-47f6-bc8b-bb69b6d24371'::uuid])<>0 then raise exception 'read is not idempotent'; end if;
end $$;
reset role;
set local role anon;
do $$ begin
 begin
  perform public.send_team_message('2c276bbb-6c54-4836-a750-24564988b2fd','de3caa1d-0f4b-4b1a-8900-31a0265a40b1','a1f5f2db-c07e-414f-8a46-c48f4237d055','نص');
  raise exception 'anonymous can send';
 exception when insufficient_privilege then null; end;
end $$;
reset role;
select 'PASS: send, idempotency, read, length, cooldown, device and recipient privacy' as verification;
rollback;


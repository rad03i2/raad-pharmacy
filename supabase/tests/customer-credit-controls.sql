\set ON_ERROR_STOP on
begin;
select set_config('request.jwt.claim.sub','00000000-0000-4000-8000-000000000012',true);
set local role authenticated;
do $$ begin
 if (public.customer_credit_context()->>'canManage')::boolean then raise exception 'Ahmad gained manager capability'; end if;
 begin perform public.set_customer_credit('00000000-0000-4000-8000-000000000021',true); raise exception 'Non Raad changed block'; exception when insufficient_privilege then null; end;
 begin insert into public.customer_credit_controls values('00000000-0000-4000-8000-000000000021','00000000-0000-4000-8000-000000000001',true,now(),'00000000-0000-4000-8000-000000000012'); raise exception 'Direct write accepted'; exception when insufficient_privilege then null; end;
end $$;
insert into transactions(id,pharmacy_id,customer_id,type,amount) values
 ('00000000-0000-4000-8000-000000000041','00000000-0000-4000-8000-000000000001','00000000-0000-4000-8000-000000000021','DEBT',1000);
reset role;
select set_config('request.jwt.claim.sub','00000000-0000-4000-8000-000000000011',true);
set local role authenticated;
select public.set_customer_credit('00000000-0000-4000-8000-000000000021',true);
insert into transactions(id,pharmacy_id,customer_id,type,amount) values
 ('00000000-0000-4000-8000-000000000042','00000000-0000-4000-8000-000000000001','00000000-0000-4000-8000-000000000021','DEBT',500);
reset role;
select set_config('request.jwt.claim.sub','00000000-0000-4000-8000-000000000012',true);
set local role authenticated;
do $$ begin
 begin insert into transactions(id,pharmacy_id,customer_id,type,amount) values(gen_random_uuid(),'00000000-0000-4000-8000-000000000001','00000000-0000-4000-8000-000000000021','DEBT',100); raise exception 'Blocked debt accepted'; exception when insufficient_privilege then if sqlerrm<>'CUSTOMER_CREDIT_BLOCKED' then raise; end if; end;
 begin update transactions set amount=2000 where id='00000000-0000-4000-8000-000000000041'; raise exception 'Increased debt accepted'; exception when insufficient_privilege then null; end;
 begin update customers set opening_debt=200 where id='00000000-0000-4000-8000-000000000021'; raise exception 'Opening debt bypass accepted'; exception when insufficient_privilege then null; end;
end $$;
-- Legacy UPSERT retry, notes-neutral debt reduction, and ordinary receipts stay functional.
insert into transactions select * from transactions where id='00000000-0000-4000-8000-000000000041'
 on conflict(id) do update set amount=excluded.amount;
update transactions set amount=900 where id='00000000-0000-4000-8000-000000000041';
insert into transactions(id,pharmacy_id,customer_id,type,amount) values
 ('00000000-0000-4000-8000-000000000043','00000000-0000-4000-8000-000000000001','00000000-0000-4000-8000-000000000021','PAYMENT',200);
do $$ begin
 begin update transactions set amount=100 where id='00000000-0000-4000-8000-000000000043'; raise exception 'Payment reduction bypass accepted'; exception when insufficient_privilege then null; end;
 begin delete from transactions where id='00000000-0000-4000-8000-000000000043'; raise exception 'Payment deletion bypass accepted'; exception when insufficient_privilege then null; end;
 begin update transactions set deleted_at=now() where id='00000000-0000-4000-8000-000000000043'; raise exception 'Soft payment deletion bypass accepted'; exception when insufficient_privilege then null; end;
 begin perform public.request_customer_credit('00000000-0000-4000-8000-000000000021',false,'00000000-0000-4000-8000-000000000051',gen_random_uuid()); raise exception 'Invalid device request accepted'; exception when insufficient_privilege then null; end;
 if exists(select 1 from customer_credit_requests where id='00000000-0000-4000-8000-000000000051') then raise exception 'Failed request not rolled back'; end if;
end $$;
select public.request_customer_credit('00000000-0000-4000-8000-000000000021',false,'00000000-0000-4000-8000-000000000051','00000000-0000-4000-8000-000000000031');
select public.request_customer_credit('00000000-0000-4000-8000-000000000021',false,'00000000-0000-4000-8000-000000000051','00000000-0000-4000-8000-000000000031');
do $$ begin
 if (select count(*) from notification_events where credit_request_id='00000000-0000-4000-8000-000000000051')<>1 then raise exception 'Duplicate request notification'; end if;
 begin perform public.resolve_credit_request('00000000-0000-4000-8000-000000000051',false); raise exception 'Requester resolved own request'; exception when insufficient_privilege then null; end;
 begin perform public.request_customer_credit('00000000-0000-4000-8000-000000000022',true,gen_random_uuid(),'00000000-0000-4000-8000-000000000031'); raise exception 'Cross tenant request accepted'; exception when insufficient_privilege then null; end;
end $$;
reset role;
select set_config('request.jwt.claim.sub','00000000-0000-4000-8000-000000000013',true);
set local role authenticated;
do $$ begin
 if (select count(*) from customer_credit_requests)<>0 then raise exception 'Fuad reads Ahmad private request'; end if;
 begin perform public.set_customer_credit('00000000-0000-4000-8000-000000000021',false); raise exception 'Fuad changed block'; exception when insufficient_privilege then null; end;
 begin insert into transactions(id,pharmacy_id,customer_id,type,amount) values(gen_random_uuid(),'00000000-0000-4000-8000-000000000001','00000000-0000-4000-8000-000000000021','DEBT',100); raise exception 'Fuad added blocked debt'; exception when insufficient_privilege then null; end;
end $$;
reset role;
select set_config('request.jwt.claim.sub','00000000-0000-4000-8000-000000000014',true);
set local role authenticated;
do $$ begin
 begin perform public.set_customer_credit('00000000-0000-4000-8000-000000000021',false); raise exception 'Maintainer changed block'; exception when insufficient_privilege then null; end;
end $$;
reset role;
select set_config('request.jwt.claim.sub','00000000-0000-4000-8000-000000000015',true);
set local role authenticated;
do $$ begin
 if (select count(*) from customer_credit_controls)<>0 or (select count(*) from customer_credit_requests)<>0 then raise exception 'Tenant metadata leaked'; end if;
end $$;
reset role;
-- Display names never grant/revoke the bound manager capability.
update profiles set display_name='renamed' where id='00000000-0000-4000-8000-000000000011';
update profiles set display_name='رعد' where id='00000000-0000-4000-8000-000000000012';
select set_config('request.jwt.claim.sub','00000000-0000-4000-8000-000000000012',true);
set local role authenticated;
do $$ begin if (public.customer_credit_context()->>'canManage')::boolean then raise exception 'Display name granted privilege'; end if; end $$;
reset role;
select set_config('request.jwt.claim.sub','00000000-0000-4000-8000-000000000011',true);
set local role authenticated;
select public.read_team_messages(array['00000000-0000-4000-8000-000000000051'::uuid]);
do $$ begin
 if not (public.customer_credit_context()->>'canManage')::boolean then raise exception 'Rename lost privilege'; end if;
 if jsonb_array_length(public.customer_credit_context()->'requests')<>1 then raise exception 'Reading message lost pending action'; end if;
 begin perform public.set_customer_credit('00000000-0000-4000-8000-000000000022',true); raise exception 'Manager changed other tenant'; exception when insufficient_privilege then null; end;
end $$;
select public.resolve_credit_request('00000000-0000-4000-8000-000000000051',false);
select public.resolve_credit_request('00000000-0000-4000-8000-000000000051',false);
do $$ begin
 if jsonb_array_length(public.customer_credit_context()->'requests')<>0 then raise exception 'Request not resolved'; end if;
 if (select blocked from customer_credit_controls where customer_id='00000000-0000-4000-8000-000000000021') then raise exception 'Unblock button had no effect'; end if;
end $$;
reset role;
select set_config('request.jwt.claim.sub','00000000-0000-4000-8000-000000000012',true);
set local role authenticated;
insert into transactions(id,pharmacy_id,customer_id,type,amount) values(gen_random_uuid(),'00000000-0000-4000-8000-000000000001','00000000-0000-4000-8000-000000000021','DEBT',100);
reset role;
select set_config('request.jwt.claim.sub','',true);
set local role authenticated;
do $$ begin
 begin perform public.customer_credit_context(); raise exception 'Anonymous context accepted'; exception when insufficient_privilege then null; end;
end $$;
rollback;
select 'PASS: owner binding, deny debt/credit bypasses, allow payment/retry, tenant isolation, request idempotency, message-read persistence, actionable resolve and rollback' as result;

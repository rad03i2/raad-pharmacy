import {capability,health,nextBackup,requestPayload} from './policy.ts';
function equal(a:unknown,b:unknown){if(JSON.stringify(a)!==JSON.stringify(b))throw new Error('mismatch');}
Deno.test('Iraq 03/09/15/21 schedule maps to UTC slots and rolls midnight',()=>{
  equal(nextBackup(new Date('2026-10-09T23:59:00Z')),'2026-10-10T00:00:00.000Z');
  equal(nextBackup(new Date('2026-10-10T00:00:00Z')),'2026-10-10T06:00:00.000Z');
});
Deno.test('No configured service or recent timestamp can claim full restore protection',()=>{
  const now=new Date('2026-10-10T12:00:00Z');
  equal(health(false,null,now),'NOT_CONFIGURED');
  equal(health(true,null,now),'NO_VERIFIED_BACKUP');
  equal(health(true,'2026-10-10T00:00:00Z',now),'STALE');
  equal(health(true,'invalid',now),'STALE');
});
Deno.test('ordinary users and unconfigured admins cannot dispatch',()=>{
  equal(capability(false,true,'today',true),false);
  equal(capability(true,false,'today',true),false);
  equal(capability(true,true,null,true),false);
  equal(capability(true,true,'today',false),false);
  equal(capability(true,true,'today',true),true);
});
Deno.test('dispatch payload always targets the approved main workflow',()=>{
  equal(requestPayload('00000000-0000-0000-0000-000000000001'),{ref:'main',inputs:{request_id:'00000000-0000-0000-0000-000000000001'}});
  let rejected=false;try{requestPayload('../../main');}catch(_){rejected=true;}equal(rejected,true);
});

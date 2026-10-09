import {createClient} from "npm:@supabase/supabase-js@2.49.4";
import {capability,health,nextBackup,REPOSITORY,WORKFLOW,requestPayload} from "./policy.ts";

function json(value:unknown,status=200) {
  return new Response(JSON.stringify(value),{status,headers:{"content-type":"application/json;charset=utf-8","cache-control":"no-store"}});
}

Deno.serve(async(req:Request)=>{
  if (!['GET','POST'].includes(req.method)) return json({error:'method_not_allowed'},405);
  const authorization=req.headers.get('authorization');
  if (!authorization?.startsWith('Bearer ')) return json({error:'not_authenticated'},401);
  const url=Deno.env.get('SUPABASE_URL')!,service=Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!;
  const admin=createClient(url,service,{auth:{persistSession:false}});
  const user=createClient(url,Deno.env.get('SUPABASE_ANON_KEY')!,{
    global:{headers:{Authorization:authorization}},auth:{persistSession:false}});
  // getUser validates the JWT with Auth; never authorize from client JSON/user_metadata.
  const identity=await user.auth.getUser();
  if (identity.error || !identity.data.user) return json({error:'not_authenticated'},401);
  const profile=await admin.from('profiles').select('id,pharmacy_id').eq('id',identity.data.user.id).maybeSingle();
  if (profile.error || !profile.data) return json({error:'pharmacy_membership_required'},403);
  const pharmacy=profile.data.pharmacy_id;
  const membership=await admin.from('cloud_backup_admins').select('user_id').eq('pharmacy_id',pharmacy).eq('user_id',identity.data.user.id).maybeSingle();
  const status=await admin.from('cloud_backup_status').select('*').eq('pharmacy_id',pharmacy).maybeSingle();
  if (membership.error || status.error) return json({error:'service_not_configured'},503);
  const isAdmin=!!membership.data,s=status.data;
  const token=Deno.env.get('CLOUD_BACKUP_DISPATCH_TOKEN');
  const dispatchEnabled=!!token && Deno.env.get('CLOUD_BACKUP_MANUAL_ENABLED')==='true';
  const canRequest=capability(isAdmin,s?.configured===true,s?.last_success_at??null,dispatchEnabled);
  const automatic=Deno.env.get('CLOUD_BACKUP_AUTOMATIC_ENABLED')==='true';
  if (req.method==='GET') {
    let history:unknown[]=[];
    if (isAdmin) {
      const rows=await admin.from('cloud_backup_runs').select('id,created_at,completed_at,state,error_code,bytes')
        .eq('pharmacy_id',pharmacy).order('created_at',{ascending:false}).limit(60);
      if (rows.error) return json({error:'status_unavailable'},503);
      history=rows.data??[];
    }
    const now=new Date();
    return json({provider:'Google Drive',configured:s?.configured===true,automatic_enabled:automatic,
      health:health(s?.configured===true,s?.last_success_at??null,now),phase:s?.phase??'NOT_CONFIGURED',
      last_success_at:s?.last_success_at??null,last_attempt_at:s?.last_attempt_at??null,
      next_expected_at:nextBackup(now),retained_count:s?.retained_count??0,total_bytes:s?.total_bytes??0,
      quota_used:s?.quota_used??0,quota_limit:s?.quota_limit??0,
      restore_tested_at:s?.restore_tested_at??null,
      owner_email:isAdmin?s?.drive_owner_email??null:null,
      last_error_code:isAdmin?s?.last_error_code??null:null,
      can_view_history:isAdmin,can_request_backup:canRequest,
      can_download:false,can_restore:false,history});
  }
  if (!isAdmin) return json({error:'backup_admin_required'},403);
  if (!canRequest) return json({error:'manual_backup_not_ready'},503);
  // Client cannot choose pharmacy, repository, branch, Drive file, or workflow.
  const queued=await user.rpc('request_cloud_backup');
  if (queued.error || !queued.data) return json({error:'backup_request_busy_or_unavailable'},409);
  const id=String(queued.data);
  try {
    const response=await fetch(`https://api.github.com/repos/${REPOSITORY}/actions/workflows/${WORKFLOW}/dispatches`,{
      method:'POST',signal:AbortSignal.timeout(15_000),
      headers:{Authorization:`Bearer ${token}`,'Accept':'application/vnd.github+json',
        'X-GitHub-Api-Version':'2022-11-28','Content-Type':'application/json'},body:JSON.stringify(requestPayload(id))});
    if (response.status!==204) throw new Error('dispatch_failed');
    await admin.from('cloud_backup_requests').update({state:'DISPATCHED'}).eq('id',id).eq('pharmacy_id',pharmacy).eq('state','QUEUED');
    return json({request_id:id,state:'DISPATCHED'},202);
  } catch (_) {
    await admin.from('cloud_backup_requests').update({state:'FAILED',error_code:'DISPATCH_FAILED',completed_at:new Date().toISOString()})
      .eq('id',id).eq('pharmacy_id',pharmacy);
    return json({error:'dispatch_failed'},503);
  }
});

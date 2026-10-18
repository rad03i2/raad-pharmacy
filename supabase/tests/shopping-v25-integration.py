"""Local PostgreSQL only: v25 recovery, expiry, racing restores and append-only supplier amounts."""
import os, threading, time, uuid
from urllib.parse import urlparse
import psycopg
url=os.environ['SHOPPING_FIXTURE_URL'];p=urlparse(url)
assert p.hostname in ('127.0.0.1','localhost') and p.path=='/raad_shopping_fixture'
raad='00000000-0000-4000-8000-000000000011';ahmad='00000000-0000-4000-8000-000000000012'
outsider='00000000-0000-4000-8000-000000000015'
pharmacy='00000000-0000-4000-8000-000000000001'
def uid():return str(uuid.uuid4())
def auth(c,user):c.execute('set local role authenticated');c.execute("select set_config('request.jwt.claim.sub',%s,true)",(user,))
def query(user,sql,args=()):
    with psycopg.connect(url) as c:auth(c,user);return c.execute(sql,args).fetchone()[0]
def context(user=raad):return query(user,'select medicine_shopping_context_v25()')
def add(id):return query(raad,'select add_medicine_shopping_item(%s,%s,%s,false)',(id,'علاج آمن','علبتان'))
def change(id,op,action='TRASH',token=None,ms=None,user=raad):
    return query(user,'select change_medicine_shopping_v25(%s,%s,%s,%s,%s,%s,false,%s)',
        (op,id,action,token or op,'علاج آمن','علبتان',ms if ms is not None else int(time.time()*1000)))
def expect(error,func):
    try:func()
    except error:return
    raise AssertionError('Expected '+error.__name__)
with psycopg.connect(url,autocommit=True) as admin:
    before=admin.execute('select (select jsonb_agg(to_jsonb(c)) from customers c),(select jsonb_agg(to_jsonb(t)) from transactions t)').fetchone()
    id=uid();op=uid();add(id)
    result=change(id,op);assert result['trashed'] and not result['active']
    assert change(id,op)==result
    assert not query(ahmad,'select add_medicine_shopping_item(%s,%s,%s,false)',(id,'علاج آمن','علبتان'))['active']
    held=next(r for r in context(ahmad)['trash'] if r['item']['id']==id)
    assert held['deletionId']==op and held['expiresAt']>int(time.time()*1000)
    assert all(r['item']['id']!=id for r in context(outsider)['trash'])
    expect(psycopg.errors.InsufficientPrivilege,lambda:change(id,uid(),user=outsider))
    expect(psycopg.errors.InsufficientPrivilege,lambda:query(outsider,'select add_medicine_shopping_item(%s,%s,%s,false)',(id,'علاج آمن','علبتان')))
    expect(psycopg.errors.InsufficientPrivilege,lambda:query(outsider,'select complete_medicine_shopping_item(%s)',(id,)))
    expect(psycopg.errors.InsufficientPrivilege,lambda:query(ahmad,'select count(*) from raad_private.shopping_trash'))
    wrong=change(id,uid(),'RESTORE',uid());assert not wrong['active'] and wrong['trashed']
    restored=change(id,uid(),'RESTORE',op,user=ahmad);assert restored['active'] and not restored['trashed']
    assert sum(r['id']==id for r in context()['items'])==1
    # Replaying an acknowledged trash mutation cannot delete the restored item again.
    assert change(id,op)==result;assert any(r['id']==id for r in context()['items'])
    # Old v24 completion is intentionally permanent, even for a newly recoverable item.
    old=uid();oldop=uid();add(old);change(old,oldop)
    query(ahmad,'select complete_medicine_shopping_item(%s)',(old,))
    assert not change(old,uid(),'RESTORE',oldop)['active']
    assert not query(raad,'select add_medicine_shopping_item(%s,%s,%s,false)',(old,'علاج آمن','علبتان'))['active']
    # Server, not a stale device flag, enforces expiry; physical cleanup contains no active rows.
    expired=uid();expop=uid();add(expired);change(expired,expop)
    admin.execute("update raad_private.shopping_trash set expires_at=now()-interval '1 second' where id=%s",(expired,))
    assert not change(expired,uid(),'RESTORE',expop)['active']
    assert all(r['item']['id']!=expired for r in context()['trash'])
    admin.execute('select raad_private.purge_shopping_trash()')
    assert admin.execute('select count(*) from raad_private.shopping_trash where id=%s',(expired,)).fetchone()[0]==0
    assert admin.execute('select count(*) from raad_private.medicine_shopping_deleted where id=%s',(expired,)).fetchone()[0]==1
    offline=uid();assert not change(offline,uid(),ms=int(time.time()*1000)-86_401_000)['trashed']
    assert not add(offline)['active']
    # A locally created, bought and restored offline item retains its original UUID once online.
    local=uid();localop=uid();assert change(local,localop)['trashed']
    assert change(local,uid(),'RESTORE',localop)['active']
    # Two staff members race the same restore; one active record and no duplicate.
    racing=uid();raceop=uid();add(racing);change(racing,raceop)
    results=[]
    threads=[threading.Thread(target=lambda:results.append(change(racing,uid(),'RESTORE',raceop,user=ahmad))) for _ in range(2)]
    for thread in threads:thread.start()
    for thread in threads:thread.join(10)
    assert len(results)==2 and all(r['active'] for r in results)
    assert admin.execute('select count(*) from medicine_shopping_items where id=%s',(racing,)).fetchone()[0]==1
    # Snapshot consistency while a real concurrent device repeatedly buys/restores the same material.
    moving=uid();add(moving);failures=[]
    def churn():
        try:
            for _ in range(30):
                token=uid();change(moving,token);change(moving,uid(),'RESTORE',token)
        except BaseException as error:failures.append(error)
    writer=threading.Thread(target=churn);writer.start()
    for _ in range(60):
        snap=context(ahmad)
        occurrences=sum(r['id']==moving for r in snap['items'])+sum(r['item']['id']==moving for r in snap['trash'])
        assert occurrences==1,'Mixed active/trash snapshots could erase a restored local photo'
    writer.join(15);assert not writer.is_alive() and not failures,failures
    # Appended financial supplier entries: exact retries only, scope, strict dates and positive integer IQD.
    supplier=uid()
    sql='select add_supplier_ledger_entry(%s,%s,%s,%s,%s,%s)'
    args=(supplier,'مذخر تجريبي','DEBT',5_000_000,'2026-10-18','وصل تجريبي')
    assert query(raad,sql,args)['active'];assert query(raad,sql,args)['active']
    assert sum(r['id']==supplier for r in context(ahmad)['suppliers'])==1
    assert context(outsider)['suppliers']==[]
    expect(psycopg.errors.InvalidParameterValue,lambda:query(raad,sql,args[:3]+(9_000_000,)+args[4:]))
    expect(psycopg.errors.InsufficientPrivilege,lambda:query(outsider,sql,args))
    expect(psycopg.errors.InsufficientPrivilege,lambda:query(ahmad,'delete from supplier_ledger_entries returning id'))
    expect(psycopg.errors.InvalidParameterValue,lambda:query(raad,sql,(uid(),'x','DEBT',0,'2026-10-18','')))
    assert not admin.execute("select has_function_privilege('anon','public.change_medicine_shopping_v25(uuid,uuid,text,uuid,text,text,boolean,bigint)','execute')").fetchone()[0]
    after=admin.execute('select (select jsonb_agg(to_jsonb(c)) from customers c),(select jsonb_agg(to_jsonb(t)) from transactions t)').fetchone()
    assert before==after,'Financial customer ledger changed'
print('PASS v25: recover/expiry/old-client compatibility/offline/racing restores/supplier exact-amount idempotency/RLS/customer ledger unchanged')

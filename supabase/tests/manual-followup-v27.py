"""Local disposable database ONLY: no production records, phone numbers or SMS sends."""
import os, threading
from urllib.parse import urlparse
import psycopg
from psycopg.types.json import Jsonb
url=os.environ['SHOPPING_FIXTURE_URL']; parsed=urlparse(url)
assert parsed.hostname in ('127.0.0.1','localhost') and parsed.path=='/raad_shopping_fixture'
raad='00000000-0000-4000-8000-000000000011'; ahmad='00000000-0000-4000-8000-000000000012'
hidden='00000000-0000-4000-8000-000000000014'; outsider='00000000-0000-4000-8000-000000000015'
customer='00000000-0000-4000-8000-000000000021'; other='00000000-0000-4000-8000-000000000022'
pharmacy='00000000-0000-4000-8000-000000000001'
first='00000000-0000-4000-8000-000000000081'; second='00000000-0000-4000-8000-000000000082'
def query(user,sql,args=()):
    with psycopg.connect(url) as c:
        c.execute('set local role authenticated');c.execute("select set_config('request.jwt.claim.sub',%s,true)",(user,))
        return c.execute(sql,args).fetchone()[0]
def add(user,keys,target=customer):return query(user,'select add_customer_followup_v27(%s,%s)',(target,Jsonb(keys)))
def context(user):return query(user,'select customer_followup_context_v27()')
def expect(error,fn):
    try:fn()
    except error:return
    raise AssertionError('Expected '+error.__name__)
with psycopg.connect(url,autocommit=True) as admin:
    # Extend only this throwaway legacy fixture to match the real existing timestamp column.
    admin.execute("alter table customers add column created_at timestamptz default '2026-10-20T00:00:00Z'")
    for id in (first,second):admin.execute('insert into transactions(id,pharmacy_id,customer_id,type,amount) values(%s,%s,%s,\'DEBT\',100)',(id,pharmacy,customer))
    before=admin.execute('select (select jsonb_agg(to_jsonb(c) order by id) from customers c),(select jsonb_agg(to_jsonb(t) order by id) from transactions t)').fetchone()
    opening='opening:1792454400000:100'
    assert add(raad,[opening])['debts']==[opening]
    assert add(raad,['entry:'+first])['customer']==customer
    assert len(context(ahmad)['cases'])==1
    assert context(outsider)['cases']==[]
    assert query(outsider,'select count(*) from customer_manual_followups')==0
    expect(psycopg.errors.InsufficientPrivilege,lambda:context(hidden))
    expect(psycopg.errors.InsufficientPrivilege,lambda:context(''))
    expect(psycopg.errors.InsufficientPrivilege,lambda:query(ahmad,'delete from customer_manual_followups returning 1'))
    expect(psycopg.errors.InvalidParameterValue,lambda:add(raad,['entry:'+first],other))
    for invalid in ([],[None],['entry:wrong'],['opening:1:999'],['entry:00000000-0000-4000-8000-000000000099']):
        expect(psycopg.errors.InvalidParameterValue,lambda:add(raad,invalid))
    failures=[]
    def race(user,keys):
        try:add(user,keys)
        except BaseException as e:failures.append(e)
    threads=[threading.Thread(target=race,args=(user,['entry:'+id])) for user,id in ((raad,first),(ahmad,second))*5]
    for t in threads:t.start()
    for t in threads:t.join(10);assert not t.is_alive()
    assert not failures,failures
    actual=set(context(raad)['cases'][0]['debts']);assert actual=={opening,'entry:'+first,'entry:'+second},actual
    assert len(context(raad)['cases'])==1
    after=admin.execute('select (select jsonb_agg(to_jsonb(c) order by id) from customers c),(select jsonb_agg(to_jsonb(t) order by id) from transactions t)').fetchone()
    assert before==after,'Manual follow-up changed real accounting rows in the fixture'
    assert 'cases' not in query(raad,'select medicine_shopping_context_v26()')
print('PASS v27 manual follow-up: exact opening/debt identity, tenant RLS, hidden/anonymous denial, concurrent idempotent unions, no changes to financial rows or older clients')

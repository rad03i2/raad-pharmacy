"""Real PostgreSQL, synthetic materials only, two devices/racing transactions; localhost guard."""
import os, threading, time
from urllib.parse import urlparse
import psycopg
url=os.environ['SHOPPING_FIXTURE_URL']
p=urlparse(url)
assert p.hostname in ('127.0.0.1','localhost') and p.path=='/raad_shopping_fixture','ISOLATED_DATABASE_REQUIRED'
raad='00000000-0000-4000-8000-000000000011'
ahmad='00000000-0000-4000-8000-000000000012'
fuad='00000000-0000-4000-8000-000000000013'
outsider='00000000-0000-4000-8000-000000000015'
hidden='00000000-0000-4000-8000-000000000014'
pharmacy='00000000-0000-4000-8000-000000000001'
item='00000000-0000-4000-8000-000000000071'
second='00000000-0000-4000-8000-000000000072'
def auth(c,user):
    c.execute('set local role authenticated')
    c.execute("select set_config('request.jwt.claim.sub',%s,true)",(user,))
def query(user,sql,args=()):
    with psycopg.connect(url) as c:
        auth(c,user);return c.execute(sql,args).fetchone()[0]
def add(user=raad,id=item,title='علاج تجريبي',note='علبتان',photo=False):
    return query(user,'select add_medicine_shopping_item(%s,%s,%s,%s)',(id,title,note,photo))
def complete(user=ahmad,id=item):return query(user,'select complete_medicine_shopping_item(%s)',(id,))
def snapshot(user):return query(user,'select medicine_shopping_context()')
def expect(error,func):
    try:func()
    except error:return
    raise AssertionError('Expected '+error.__name__)
def lock_wait(admin,name):
    end=time.monotonic()+5
    while time.monotonic()<end:
        if admin.execute("select exists(select 1 from pg_stat_activity where application_name=%s and wait_event='advisory')",(name,)).fetchone()[0]:return
        time.sleep(.01)
    raise AssertionError('Shopping contender did not wait for item advisory lock')
with psycopg.connect(url,autocommit=True) as admin:
    before=admin.execute('select (select jsonb_agg(to_jsonb(c)) from customers c),(select jsonb_agg(to_jsonb(t)) from transactions t)').fetchone()
    first=add();assert first['active'] and first['id']==item
    assert add()==first # A lost response/retry never duplicates a material.
    assert admin.execute('select count(*) from medicine_shopping_items').fetchone()[0]==1
    for user in (raad,ahmad,fuad):assert snapshot(user)['items'][0]['title']=='علاج تجريبي'
    assert snapshot(outsider)['items']==[]
    expect(psycopg.errors.InsufficientPrivilege,lambda:complete(outsider))
    expect(psycopg.errors.InsufficientPrivilege,lambda:snapshot(hidden))
    expect(psycopg.errors.InsufficientPrivilege,lambda:snapshot(''))
    expect(psycopg.errors.InsufficientPrivilege,lambda:query(ahmad,'delete from medicine_shopping_items returning id'))
    expect(psycopg.errors.InsufficientPrivilege,lambda:query(ahmad,'select count(*) from raad_private.medicine_shopping_deleted'))
    assert query(outsider,'select count(*) from medicine_shopping_items')==0
    assert query(outsider,'select count(*) from medicine_shopping_signal')==0
    expect(psycopg.errors.InvalidParameterValue,lambda:add(title='changed'))
    expect(psycopg.errors.InvalidParameterValue,lambda:add(id=second,title=''))
    expect(psycopg.errors.InvalidParameterValue,lambda:add(id=second,title='x'*241))
    expect(psycopg.errors.InvalidParameterValue,lambda:add(id=second,note='x'*1001))
    assert add(id=second,title='',photo=True)['active']
    assert all(set(row)=={'id','title','note','hasPhoto','createdAt','createdBy'} for row in snapshot(ahmad)['items'])
    assert not complete()['active'];assert not complete(fuad)['active']
    assert not add()['active'] # A disconnected device's delayed create cannot resurrect a purchase.
    assert all(row['id']!=item for row in snapshot(fuad)['items'])
    assert complete(id='00000000-0000-4000-8000-000000000073')['active'] is False
    assert add(id='00000000-0000-4000-8000-000000000073')['active'] is False
    # Actual rollback of both item and signal, not a mocked failed callback.
    with psycopg.connect(url) as c:
        auth(c,raad)
        c.execute('select add_medicine_shopping_item(%s,%s,%s,false)',('00000000-0000-4000-8000-000000000074','rollback',''))
        c.rollback()
    assert not any(row['title']=='rollback' for row in snapshot(raad)['items'])
    # Both transaction orders must end with a permanent deletion.
    for create_first,n in ((True,75),(False,76)):
        id=f'00000000-0000-4000-8000-{n:012d}'
        with psycopg.connect(url,autocommit=True) as one:
            one.execute('begin');auth(one,raad)
            if create_first:one.execute('select add_medicine_shopping_item(%s,%s,%s,false)',(id,'race',''))
            else:one.execute('select complete_medicine_shopping_item(%s)',(id,))
            result=[]
            def contender():
                with psycopg.connect(url,application_name='shopping-contender') as two:
                    auth(two,fuad)
                    sql='select complete_medicine_shopping_item(%s)' if create_first else 'select add_medicine_shopping_item(%s,%s,%s,false)'
                    args=(id,) if create_first else (id,'race','')
                    result.append(two.execute(sql,args).fetchone()[0])
            thread=threading.Thread(target=contender);thread.start();lock_wait(admin,'shopping-contender')
            one.execute('commit');thread.join(10)
            assert not thread.is_alive() and len(result)==1 and result[0]['active'] is False,result
            assert admin.execute('select count(*) from medicine_shopping_items where id=%s',(id,)).fetchone()[0]==0
    # Single aggregate snapshot retains >1000 rows, independent of REST row limit.
    admin.execute("insert into medicine_shopping_items(id,pharmacy_id,title,created_by) select ('00000000-0000-4000-9000-'||lpad(n::text,12,'0'))::uuid,%s,'bulk '||n,%s from generate_series(1,1100)n",(pharmacy,raad))
    assert len(snapshot(ahmad)['items'])==1101
    after=admin.execute('select (select jsonb_agg(to_jsonb(c)) from customers c),(select jsonb_agg(to_jsonb(t)) from transactions t)').fetchone()
    assert before==after,'Financial tables were modified'
    assert admin.execute("select count(*) from information_schema.columns where table_schema='raad_private' and table_name='medicine_shopping_deleted'").fetchone()[0]==2
    assert admin.execute("select has_function_privilege('anon','public.add_medicine_shopping_item(uuid,text,text,boolean)','execute')").fetchone()[0] is False
print('PASS: shared list, RLS, offline/lost-reply retries, permanent deletion, both concurrency orders, >1000 rows, no ledger changes')

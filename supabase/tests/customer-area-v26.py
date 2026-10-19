"""Disposable PostgreSQL only: catalog scopes, concurrent retries, compatibility, no money mutations."""
import os, threading
from urllib.parse import urlparse
import psycopg
url=os.environ['SHOPPING_FIXTURE_URL']; parsed=urlparse(url)
assert parsed.hostname in ('127.0.0.1','localhost') and parsed.path=='/raad_shopping_fixture'
raad='00000000-0000-4000-8000-000000000011'; ahmad='00000000-0000-4000-8000-000000000012'
hidden='00000000-0000-4000-8000-000000000014'; outsider='00000000-0000-4000-8000-000000000015'
def query(user,sql,args=()):
    with psycopg.connect(url) as c:
        c.execute('set local role authenticated');c.execute("select set_config('request.jwt.claim.sub',%s,true)",(user,))
        return c.execute(sql,args).fetchone()[0]
def add(user,name):return query(user,'select add_customer_area_v26(%s)',(name,))
def context(user):return query(user,'select medicine_shopping_context_v26()')
def expect(error,fn):
    try:fn()
    except error:return
    raise AssertionError('Expected '+error.__name__)
with psycopg.connect(url,autocommit=True) as admin:
    before=admin.execute('select (select jsonb_agg(to_jsonb(c) order by id) from customers c),(select jsonb_agg(to_jsonb(t) order by id) from transactions t)').fetchone()
    value=add(raad,'  بغداد   الجديدة  ');assert value['name']=='بغداد الجديدة'
    assert add(raad,'بغداد الجديدة')==value
    assert context(ahmad)['areas']==['بغداد الجديدة']
    assert context(outsider)['areas']==[]
    assert query(outsider,'select count(*) from customer_area_catalog')==0
    expect(psycopg.errors.InsufficientPrivilege,lambda:add(hidden,'ممنوع'))
    expect(psycopg.errors.InsufficientPrivilege,lambda:query(ahmad,"insert into customer_area_catalog values('00000000-0000-4000-8000-000000000001','forged') returning 1"))
    for invalid in ('','   ','x'*161):expect(psycopg.errors.InvalidParameterValue,lambda:add(raad,invalid))
    failures=[]
    def race(user):
        try:add(user,'المنصور')
        except BaseException as e:failures.append(e)
    threads=[threading.Thread(target=race,args=(user,)) for user in (raad,ahmad)*5]
    for t in threads:t.start()
    for t in threads:t.join(10);assert not t.is_alive()
    assert not failures,failures
    assert context(raad)['areas'].count('المنصور')==1
    assert 'areas' not in query(raad,'select medicine_shopping_context_v25()')
    assert 'areas' not in query(raad,'select medicine_shopping_context()')
    after=admin.execute('select (select jsonb_agg(to_jsonb(c) order by id) from customers c),(select jsonb_agg(to_jsonb(t) order by id) from transactions t)').fetchone()
    assert before==after
print('PASS v26 area catalog RLS, empty areas, concurrent idempotency, old client contracts and unchanged financial rows')

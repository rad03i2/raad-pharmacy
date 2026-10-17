"""Two real PostgreSQL connections, synthetic rows, disposable localhost database only."""
import os
import time
import threading
from urllib.parse import urlparse
import psycopg

url=os.environ['CREDIT_FIXTURE_URL']
parsed=urlparse(url)
assert parsed.hostname in ('127.0.0.1','localhost') and parsed.path=='/raad_credit_fixture', 'ISOLATED_DATABASE_REQUIRED'
raad='00000000-0000-4000-8000-000000000011'
fuad='00000000-0000-4000-8000-000000000013'
customer='00000000-0000-4000-8000-000000000021'
pharmacy='00000000-0000-4000-8000-000000000001'
operation='00000000-0000-4000-8000-000000000061'
def auth(conn,user):
    conn.execute('set local role authenticated')
    conn.execute("select set_config('request.jwt.claim.sub',%s,true)",(user,))

def wait_for_lock(admin,name):
    until=time.monotonic()+5
    while time.monotonic()<until:
        if admin.execute("select exists(select 1 from pg_stat_activity where application_name=%s and wait_event='advisory')",(name,)).fetchone()[0]: return
        time.sleep(.01)
    raise AssertionError('Contender did not wait for the shared customer lock')

with psycopg.connect(url,autocommit=True) as admin:
    # A block that is already committing must win over an overlapping debt insert.
    with psycopg.connect(url,autocommit=True) as owner:
        owner.execute('begin');auth(owner,raad)
        owner.execute('select public.set_customer_credit(%s,true)',(customer,))
        result=[]
        def add_debt():
            with psycopg.connect(url,application_name='credit-debt-contender',autocommit=True) as c:
                try:
                    with c.transaction():
                        auth(c,fuad)
                        c.execute('insert into transactions(id,pharmacy_id,customer_id,type,amount) values(%s,%s,%s,\'DEBT\',100)',(operation,pharmacy,customer))
                    result.append('saved')
                except psycopg.errors.InsufficientPrivilege as error:
                    assert 'CUSTOMER_CREDIT_BLOCKED' in str(error);result.append('blocked')
        contender=threading.Thread(target=add_debt);contender.start()
        wait_for_lock(admin,'credit-debt-contender');owner.execute('commit');contender.join(10)
        assert not contender.is_alive() and result==['blocked'],result
        assert admin.execute('select count(*) from transactions where id=%s',(operation,)).fetchone()[0]==0
    # A legitimate debt committed before a later block is retained; blocking never deletes money.
    with psycopg.connect(url,autocommit=True) as c:
        with c.transaction(): auth(c,raad);c.execute('select public.set_customer_credit(%s,false)',(customer,))
    with psycopg.connect(url,autocommit=True) as debtor:
        debtor.execute('begin');auth(debtor,fuad)
        debtor.execute('insert into transactions(id,pharmacy_id,customer_id,type,amount) values(%s,%s,%s,\'DEBT\',100)',(operation,pharmacy,customer))
        result=[]
        def block():
            with psycopg.connect(url,application_name='credit-block-contender',autocommit=True) as c:
                with c.transaction():auth(c,raad);c.execute('select public.set_customer_credit(%s,true)',(customer,))
                result.append('blocked')
        contender=threading.Thread(target=block);contender.start()
        wait_for_lock(admin,'credit-block-contender');debtor.execute('commit');contender.join(10)
        assert not contender.is_alive() and result==['blocked'],result
        assert admin.execute('select amount from transactions where id=%s',(operation,)).fetchone()[0]==100
    admin.execute('delete from transactions where id=%s',(operation,))
    admin.execute('delete from customer_credit_controls where customer_id=%s',(customer,))
print('PASS: block/debt concurrency in both orderings; no lost, duplicated or incorrectly accepted debt')

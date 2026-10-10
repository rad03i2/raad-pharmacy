"""Actual PG17/Age rehearsal with synthetic accounts/finance/Storage and mocked
Google protocol. This is NOT evidence of a live Google/Supabase backup or login.
"""
import hashlib
import json
import os
import re
import tempfile
from pathlib import Path
from urllib.parse import urlparse,parse_qs
from .common import PHARMACY,BackupError,backup_id,pg_environment
from .database import Database,connect,command,metrics
from .drive import Drive
from .http import Response
from .runner import BackupJob,storage_archive
from .restore import inspect_plain,restore_database,restore_storage_local


class FakeGoogleStorage:
    def __init__(self): self.files={};self.payloads={};self.counter=0;self.sessions={};self.local={}
    def request(self,method,url,headers=None,data=None):
        body=json.loads(data) if data and headers.get('Content-Type')=='application/json' else None
        parsed=urlparse(url);query=parse_qs(parsed.query);path=parsed.path
        def reply(value,status=200,headers=None): return Response(status,headers or {},json.dumps(value).encode())
        if 'oauth2.googleapis.com' in url: return reply({'access_token':'FAKE_TOKEN_FOR_TEST_ONLY'})
        if parsed.hostname=='127.0.0.1':
            if '/storage/v1/object/' in path: self.local[path.split('/storage/v1/object/')[1]]=data
            return reply({})
        if path.endswith('/about'): return reply({'user':{'emailAddress':'fixture@example.invalid'},'storageQuota':{'usage':'0','limit':'1000000000'}})
        if path.endswith('/generateIds'):
            self.counter+=1;return reply({'ids':['fixture-id-'+str(self.counter)]})
        if path=='/drive/v3/files' and method=='GET':
            q=query.get('q',[''])[0];rows=[f for f in self.files.values() if not f.get('trashed')]
            for key,val in re.findall(r"key='([^']+)' and value='([^']+)'",q): rows=[f for f in rows if f.get('appProperties',{}).get(key)==val]
            parent=re.search(r"'([^']+)' in parents",q)
            if parent: rows=[f for f in rows if parent[1] in f.get('parents',[])]
            return reply({'files':rows})
        if path=='/drive/v3/files' and method=='POST': self.files[body['id']]=body;return reply(body,201)
        if path=='/upload/drive/v3/files':
            fid=body['id'];self.files[fid]=body;self.payloads[fid]=b'';session='https://www.googleapis.com/session/'+fid
            self.sessions[session]=fid;return reply({},headers={'location':session})
        if url in self.sessions:
            fid=self.sessions[url];self.payloads[fid]+=data
            total=int(headers['Content-Range'].split('/')[-1]);count=len(self.payloads[fid]);self.files[fid]['size']=str(count)
            return reply(self.files[fid]) if count==total else reply({},308,{'range':'bytes=0-'+str(count-1)})
        fid=path.rsplit('/',1)[-1]
        if fid in self.files:
            if method=='PATCH': self.files[fid].update(body)
            return reply(self.files[fid])
        raise AssertionError('unsupported test protocol')
    def download(self,url,headers,*,target=None,limit):
        if 'supabase.co/storage' in url: payload=b'image'
        elif '127.0.0.1' in url: payload=self.local[url.split('/storage/v1/object/authenticated/')[1]]
        else: payload=self.payloads[urlparse(url).path.rsplit('/',1)[-1]]
        if len(payload)>limit: raise BackupError('DOWNLOAD_SIZE_MISMATCH')
        if target: target.write_bytes(payload)
        return hashlib.sha256(payload).hexdigest(),len(payload),{}


def permissions_test(db):
    c=db.control
    c.execute("insert into cloud_backup_admins values(%s,'00000000-0000-0000-0000-000000000001')",(PHARMACY,))
    c.execute("insert into cloud_backup_status(pharmacy_id,configured,last_success_at,drive_owner_email) values(%s,true,now(),'private@example.invalid')",(PHARMACY,))
    c.execute("insert into cloud_backup_runs(id,pharmacy_id,state) values('00000000-0000-0000-0000-000000000090',%s,'VERIFIED')",(PHARMACY,))
    with c.transaction():
        c.execute('set local role authenticated')
        c.execute("select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000002',true)")
        assert c.execute('select count(*) n from cloud_backup_runs').fetchone()['n']==0
        assert c.execute('select count(*) n from cloud_backup_status').fetchone()['n']==1
        for sql in ('select drive_owner_email from cloud_backup_status',"insert into cloud_backup_admins values('2ad39326-7794-4ba6-aad1-a1b772814aa1','00000000-0000-0000-0000-000000000002')",'select request_cloud_backup()'):
            try:
                with c.transaction(): c.execute(sql)
            except Exception: pass
            else: raise AssertionError('regular user gained administrative permission')
        c.execute("select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000003',true)")
        assert c.execute('select count(*) n from cloud_backup_status').fetchone()['n']==0
        c.execute("select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000001',true)")
        assert c.execute('select count(*) n from cloud_backup_runs').fetchone()['n']==1
        assert c.execute('select request_cloud_backup() id').fetchone()['id']
    c.execute("delete from cloud_backup_runs where id='00000000-0000-0000-0000-000000000090'")
    # Remove synthetic second tenant only AFTER cross-tenant authorization test.
    c.execute("delete from profiles where id='00000000-0000-0000-0000-000000000003'")
    c.execute("delete from pharmacies where id='00000000-0000-0000-0000-000000000099'")


def main():
    dsn=os.environ['FIXTURE_DB_URL'];env=pg_environment(dsn,isolated=True)
    c=connect(env)
    c.execute(Path('tools/cloud_backup/fixture.sql').read_text())
    ddl=Path('supabase/cloud-backup-control.sql').read_text();c.execute(ddl);c.execute(ddl);c.close()
    db=Database(dsn,isolated=True)
    permissions_test(db)
    fake=FakeGoogleStorage();drive=Drive('fake','fake','fake',fake,lambda _:None)
    with tempfile.TemporaryDirectory() as temp:
        root=Path(temp);key=root/'fixture.agekey'
        command(['age-keygen','-o',str(key)],dict(os.environ),'AGE_KEYGEN_FAILED')
        recipient=command(['age-keygen','-y',str(key)],dict(os.environ),'AGE_KEYGEN_FAILED').decode().strip()
        class ConcurrentSnapshotDatabase(Database):
            def dump(self,path,snapshot):
                writer=connect(self.env)
                writer.execute("insert into transactions values('00000000-0000-0000-0000-000000000022','00000000-0000-0000-0000-000000000010','00000000-0000-0000-0000-000000000022','PAYMENT',5,null)")
                writer.close();super().dump(path,snapshot)
        db.close();db=ConcurrentSnapshotDatabase(dsn,isolated=True)
        run=backup_id('synthetic-integration')
        job=BackupJob(db,drive,recipient,storage=lambda db,cap,directory,key:storage_archive(db,cap,directory,key,fake))
        assert job.run(run,service_key='fake')=='VERIFIED_ENCRYPTED_DOWNLOAD'
        count=len(fake.files)
        assert job.run(run,service_key='fake')=='ALREADY_VERIFIED';assert len(fake.files)==count
        known=db.known(run)
        plain=root/'plain';plain.mkdir()
        for file in known['files']:
            assert file['name'].endswith('.age')
            drive.download(file['id'],file['sha256'],file['bytes'],plain/file['name'])
        manifest=inspect_plain(plain,key,run)
        assert manifest['metrics']['transactions']==2
        assert metrics(db.control)['transactions']==3  # concurrent write was never blocked/lost
        db.control.execute('create database raad_restore_fixture')
        target=dsn.rsplit('/',1)[0]+'/raad_restore_fixture'
        restore_database(plain,manifest,target)
        restored=connect(pg_environment(target,isolated=True))
        assert restored.execute('select count(*) n from auth.users where encrypted_password=%s',('FAKE_HASH_FOR_TEST_ONLY',)).fetchone()['n']==3
        assert restored.execute('select count(*) n from auth.refresh_tokens').fetchone()['n']==0
        assert restored.execute('select count(*) n from push_tokens').fetchone()['n']==0
        restored.close()
        restore_storage_local('http://127.0.0.1:54321','fake',plain,manifest,fake)
        assert fake.local['fixture/fake/image.bin']==b'image'
    db.close()
    print(json.dumps({'result':'PASS','postgres_version':17,'age_roundtrip':True,'consistent_concurrent_snapshot':True,
                      'isolated_restore_metrics':True,'synthetic_auth_hashes_preserved':True,'session_tokens_excluded':True,
                      'storage_binary_roundtrip':True,'rls_cross_tenant_admin_checked':True,
                      'google_transport':'MOCK','production_changed':False,'live_restore_tested':False}))


if __name__=='__main__':main()

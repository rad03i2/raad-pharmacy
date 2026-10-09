import hashlib
import io
import json
import tempfile
import tarfile
import unittest
from contextlib import contextmanager
from datetime import datetime,timezone,timedelta
from pathlib import Path
from unittest.mock import patch

from .common import BackupError,backup_id,next_slot,pg_environment,retention,SCOPE
from .drive import Drive
from .http import Response
from .restore import verify_storage,restore_storage_local
from .runner import storage_archive,BackupJob


def response(status,body=None,headers=None):
    return Response(status,headers or {},json.dumps(body or {}).encode())


class ScriptHttp:
    def __init__(self,items): self.items=list(items); self.calls=[]
    def request(self,*args):
        self.calls.append(args)
        item=self.items.pop(0)
        if isinstance(item,Exception): raise item
        return item


class ProtocolTests(unittest.TestCase):
    def drive(self,http):
        d=Drive('dummy-client','dummy-secret','dummy-refresh',http,lambda _:None)
        d.token='dummy-access'; d.expires=float('inf')
        return d
    def test_access_token_expiry_refreshes_and_retries(self):
        http=ScriptHttp([response(401),response(200,{'access_token':'renewed','scope':SCOPE}),response(200,{'id':'ok'})])
        self.assertEqual(self.drive(http).json_call('GET','/files/file-id')['id'],'ok')
        self.assertEqual(http.calls[-1][2]['Authorization'],'Bearer renewed')
    def test_revoked_refresh_token_is_safe_failure(self):
        d=Drive('x','y','z',ScriptHttp([response(400,{'error':'invalid_grant','error_description':'private'})]))
        with self.assertRaisesRegex(BackupError,'GOOGLE_AUTH_REVOKED'): d.authorization()
    def test_quota_full_is_not_retry_success(self):
        d=self.drive(ScriptHttp([response(403,{'error':{'errors':[{'reason':'storageQuotaExceeded'}]}})]))
        with self.assertRaisesRegex(BackupError,'GOOGLE_QUOTA_FULL'): d.json_call('POST','/files',{})
    def test_resumable_transport_break_uses_server_acknowledged_offset(self):
        # First chunk reaches server but response is lost. Server acknowledges
        # 256KiB, so client resumes at that offset, never assumes a full chunk.
        http=ScriptHttp([response(200,{'ids':['id']}),response(200,headers={'location':'https://www.googleapis.com/upload/session'}),
                         BackupError('NETWORK_INTERRUPTED'),response(308,headers={'range':'bytes=0-262143'}),response(200)])
        with tempfile.TemporaryDirectory() as temp:
            p=Path(temp)/'database.dump.age'; p.write_bytes(b'x'*600000)
            d=self.drive(http)
            with patch.object(d,'verify') as verify:
                out=d.upload(p,'folder',backup_id='backup')
            self.assertEqual(out['id'],'id'); verify.assert_called_once()
        self.assertEqual(http.calls[-1][2]['Content-Range'],'bytes 262144-599999/600000')
    def test_expired_upload_session_restarts_same_file_identity(self):
        http=ScriptHttp([response(200,{'ids':['stable-id']}),response(200,headers={'location':'https://www.googleapis.com/session1'}),response(404),
                         response(200,headers={'location':'https://www.googleapis.com/session2'}),response(200)])
        with tempfile.TemporaryDirectory() as temp:
            p=Path(temp)/'file.age';p.write_bytes(b'cipher')
            d=self.drive(http)
            with patch.object(d,'verify'): d.upload(p,'folder',backup_id='backup')
        bodies=[json.loads(x[3]) for x in http.calls if x[0]=='POST']
        self.assertEqual([x['id'] for x in bodies],['stable-id','stable-id'])
    def test_download_401_refreshes_token(self):
        class H(ScriptHttp):
            def download(self,*args,**kwargs):
                if not getattr(self,'called',False): self.called=True;raise BackupError('AUTH_EXPIRED')
                return ('correct',7,{})
        h=H([response(200,{'access_token':'renewed'})]);self.drive(h).download('id','correct',7)
        self.assertTrue(h.called)
    def test_download_corruption_is_rejected(self):
        class H:
            def download(self,*a,**kw): return ('wrong',7,{})
        with self.assertRaisesRegex(BackupError,'GOOGLE_FILE_CHECKSUM_MISMATCH'):
            self.drive(H()).download('id','expected',7)
    def test_redirect_capability_host_is_refused(self):
        with self.assertRaisesRegex(BackupError,'GOOGLE_URL_INVALID'):
            self.drive(ScriptHttp([])).call('PUT','https://evil.invalid/token')
    def test_invalid_server_range_refused(self):
        for value in ('bytes=4-8','bytes=0-999','nonsense'):
            with self.assertRaises(BackupError): Drive.offset(response(308,headers={'range':value}),10)


class SafetyTests(unittest.TestCase):
    def test_schedule_normalizes_iraq_timezone_and_midnight(self):
        now=datetime(2026,10,9,23,50,tzinfo=timezone(timedelta(hours=3)))
        self.assertEqual(next_slot(now).isoformat(),'2026-10-10T00:00:00+00:00')
    def test_retention_union_keeps_latest_and_day_month_points(self):
        points=[{'id':str(i),'created_at':(datetime(2026,10,9,tzinfo=timezone.utc)-timedelta(hours=6*i)).isoformat(),'verified':True} for i in range(1600)]
        points.append({'id':'unverified','created_at':'2027-01-01T00:00:00+00:00','verified':False})
        keep=retention(points)
        self.assertTrue(set(map(str,range(8)))<=keep);self.assertNotIn('unverified',keep)
        self.assertLessEqual(len(keep),50);self.assertGreaterEqual(len(keep),30)
    def test_restore_refuses_production_and_transaction_pool(self):
        for dsn in ('postgresql://postgres:secret@db.gsyrjhqkbfomxqacexle.supabase.co/postgres',
                    'postgresql://postgres:secret@127.0.0.1:6543/postgres'):
            with self.assertRaises(BackupError): pg_environment(dsn,isolated=True)
        self.assertEqual(pg_environment('postgresql://postgres:x@localhost/sandbox',isolated=True)['PGHOST'],'localhost')
    def test_logical_identity_same_retry_different_run(self):
        self.assertEqual(backup_id('99'),backup_id('99'));self.assertNotEqual(backup_id('99'),backup_id('100'))
    def test_archive_symlink_and_path_traversal_refused(self):
        for name,kind in (('../escape',tarfile.REGTYPE),('objects/'+'a'*36,tarfile.SYMTYPE)):
            with tempfile.TemporaryDirectory() as temp:
                root=Path(temp);archive=root/'s.tar'
                with tarfile.open(archive,'w') as tar:
                    member=tarfile.TarInfo(name);member.type=kind;tar.addfile(member)
                with self.assertRaises(BackupError):
                    verify_storage(archive,[{'archive_path':'objects/'+'a'*36,'bytes':0,'sha256':hashlib.sha256(b'').hexdigest()}],root/'out')
    def test_storage_checksum_corruption_refused(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp); archive=root/'s.tar'; name='objects/'+'a'*36
            with tarfile.open(archive,'w') as tar:
                info=tarfile.TarInfo(name); info.size=3;tar.addfile(info,io.BytesIO(b'bad'))
            with self.assertRaisesRegex(BackupError,'STORAGE_CHECKSUM_MISMATCH'):
                verify_storage(archive,[{'archive_path':name,'bytes':3,'sha256':'0'*64}],root/'out')
    def test_local_storage_restoration_refuses_network_host(self):
        with self.assertRaisesRegex(BackupError,'STORAGE_RESTORE_REQUIRES_LOCAL_SERVER'):
            restore_storage_local('https://gsyrjhqkbfomxqacexle.supabase.co','dummy',Path('/tmp'),{})
    def test_object_change_during_download_rejects_snapshot(self):
        class DB:
            calls=0
            def check_objects(self,rows):
                self.calls+=1
                if self.calls==2: raise BackupError('STORAGE_CHANGED_DURING_BACKUP')
        class H:
            def download(self,*args,**kwargs): kwargs['target'].write_bytes(b'image');return(hashlib.sha256(b'image').hexdigest(),5,{})
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaisesRegex(BackupError,'STORAGE_CHANGED_DURING_BACKUP'):
                storage_archive(DB(),{'objects':[{'id':'00000000-0000-0000-0000-000000000001','bucket_id':'bucket','name':'image','metadata':{'size':5}}]},Path(temp),'dummy',H())
    def test_completed_retry_verifies_existing_files_without_new_export(self):
        class DB:
            @contextmanager
            def locked(self): yield
            def known(self,_): return {'state':'VERIFIED','files':[{'id':'id','sha256':'sha','bytes':7}]}
            def complete_request(self,_): pass
        class D:
            def verify(self,*args): self.called=True
        d=D();self.assertEqual(BackupJob(DB(),d,'unused').run('run',service_key='dummy'),'ALREADY_VERIFIED');self.assertTrue(d.called)
    def test_failed_dump_cleans_plaintext_and_does_not_delete_last_good(self):
        class DB:
            pharmacy='p'
            @contextmanager
            def locked(self): yield
            @contextmanager
            def snapshot(self): yield {'snapshot':'snapshot'}
            def known(self,_): return None
            def state(self,phase,**kwargs): self.phase=phase
            def dump(self,path,_): path.write_bytes(b'private');raise BackupError('DATABASE_DUMP_FAILED')
            def complete_request(self,*a): pass
        class D:
            def about(self): return {}
            def list(self,query): return [{'id':'root'}] if 'raad_root' in query else []
            def patch(self,*a): raise AssertionError('must not remove a good backup')
        with tempfile.TemporaryDirectory() as base:
            def directory(**kw): return tempfile.TemporaryDirectory(dir=base)
            db=DB()
            with self.assertRaises(BackupError): BackupJob(db,D(),'unused',directory_factory=directory).run('run',service_key='dummy')
            self.assertEqual(list(Path(base).iterdir()),[]);self.assertEqual(db.phase,'FAILED')


if __name__=='__main__': unittest.main()

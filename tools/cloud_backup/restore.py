"""Offline/operator restore rehearsal. Deliberately has no production restore mode.

Requires externally protected OAuth credentials, the independently held Age key,
and a fresh local Supabase-compatible PostgreSQL 17 database. Does not reconnect
phones or automatically write to any Supabase Storage bucket.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import tarfile
import tempfile
from urllib.parse import urlparse,quote
from pathlib import Path

from .common import BackupError, PROJECT, PHARMACY, digest, pg_environment, private_json
from .database import command, connect, metrics
from .drive import Drive
from .setup_google import external_file
from .http import Http

NAMES = {'database.dump.age', 'storage-backup.tar.age', 'manifest.json.age'}


def decrypt(source, destination, identity):
    command(['age', '--decrypt', '--identity', str(identity), '--output', str(destination), str(source)],
            dict(os.environ), 'DECRYPTION_FAILED')
    destination.chmod(0o600)


def verify_storage(archive_path: Path, entries: list[dict], destination: Path):
    """Extract only UUID-addressed regular files. Original paths stay in private map."""
    expected = {e['archive_path']: e for e in entries}
    if len(expected) != len(entries) or any(not re.fullmatch(r'objects/[0-9a-f-]{36}', n) for n in expected):
        raise BackupError('STORAGE_MANIFEST_INVALID')
    destination.mkdir(mode=0o700)
    seen = set()
    import hashlib
    with tarfile.open(archive_path, 'r') as tar:
        for member in tar:
            row = expected.get(member.name)
            if member.name in seen or not row or not member.isfile() or member.size != row['bytes']:
                raise BackupError('STORAGE_ARCHIVE_INVALID')
            seen.add(member.name)
            value, count = hashlib.sha256(), 0
            source = tar.extractfile(member)
            if source is None:
                raise BackupError('STORAGE_ARCHIVE_INVALID')
            target = destination / member.name.split('/')[1]
            with source, target.open('xb') as output:
                target.chmod(0o600)
                for chunk in iter(lambda: source.read(1024*1024), b''):
                    count += len(chunk)
                    if count > member.size:
                        raise BackupError('STORAGE_ARCHIVE_INVALID')
                    value.update(chunk)
                    output.write(chunk)
            if count != row['bytes'] or value.hexdigest() != row['sha256']:
                raise BackupError('STORAGE_CHECKSUM_MISMATCH')
    if seen != set(expected):
        raise BackupError('STORAGE_ARCHIVE_INCOMPLETE')
    private_json(destination / 'storage-map.json', {'objects': entries})


def inspect_plain(directory: Path, identity: Path, backup_id: str):
    for name in NAMES:
        decrypt(directory/name, directory/name[:-4], identity)
    manifest = json.loads((directory/'manifest.json').read_text())
    if (manifest.get('format') != 'raad-cloud-backup-v1' or manifest.get('project_ref') != PROJECT or
            manifest.get('pharmacy_id') != PHARMACY or manifest.get('backup_id') != backup_id):
        raise BackupError('MANIFEST_SCOPE_MISMATCH')
    for item in manifest['files']:
        if item['name'] not in ('database.dump', 'storage-backup.tar'):
            raise BackupError('MANIFEST_FILE_INVALID')
        path = directory/item['name']
        if path.stat().st_size != item['bytes'] or digest(path) != item['sha256']:
            raise BackupError('PLAINTEXT_CHECKSUM_MISMATCH')
    if {f['name'] for f in manifest['files']} != {'database.dump', 'storage-backup.tar'}:
        raise BackupError('MANIFEST_INCOMPLETE')
    verify_storage(directory/'storage-backup.tar', manifest['storage'], directory/'storage-verified')
    return manifest


def restore_database(directory: Path, manifest: dict, dsn: str):
    env = pg_environment(dsn, isolated=True)  # hard refusal of production/network host
    if ' 17.' not in command(['pg_restore','--version'], env, 'PG_RESTORE_UNAVAILABLE').decode():
        raise BackupError('PG_RESTORE_VERSION_MISMATCH')
    connection = connect(env)
    try:
        # Compatible blank Supabase auth/storage schemas/roles/extensions must be
        # provisioned ahead of time. Existing tables (even empty) are rejected.
        present = connection.execute("""select count(*) n from pg_tables
            where schemaname in ('public','auth','storage','raad_private')""").fetchone()['n']
        if present:
            raise BackupError('RESTORE_DATABASE_MUST_BE_EMPTY')
        command(['pg_restore','--exit-on-error','--single-transaction','--no-owner',
                 '--dbname='+env['PGDATABASE'],str(directory/'database.dump')], env, 'ISOLATED_RESTORE_FAILED')
        actual = metrics(connection)
        if actual != manifest['metrics']:
            raise BackupError('RESTORE_FINANCIAL_METRICS_MISMATCH')
        return actual
    finally:
        connection.close()


def restore_storage_local(base: str, key: str, directory: Path, manifest: dict, http=None):
    parsed = urlparse(base)
    if (parsed.scheme not in ('http','https') or parsed.hostname not in ('localhost','127.0.0.1','::1')
            or parsed.username or parsed.query or parsed.fragment or parsed.path not in ('','/')):
        raise BackupError('STORAGE_RESTORE_REQUIRES_LOCAL_SERVER')
    http = http or Http()
    headers = {'Authorization':'Bearer '+key,'apikey':key}
    for bucket in manifest.get('buckets',[]):
        result = http.request('POST',base.rstrip('/')+'/storage/v1/bucket',
                              {**headers,'Content-Type':'application/json'},json.dumps(bucket).encode())
        # Existing bucket metadata may have been restored from the DB archive.
        if result.status not in (200,201,409):
            raise BackupError('ISOLATED_STORAGE_BUCKET_FAILED')
    for row in manifest['storage']:
        path = directory/'storage-verified'/row['id']
        target = quote(row['bucket'],safe='')+'/'+quote(row['path'],safe='/')
        result = http.request('POST',base.rstrip('/')+'/storage/v1/object/'+target,
                              {**headers,'Content-Type':row.get('metadata',{}).get('mimetype','application/octet-stream'),
                               'x-upsert':'true'},path.read_bytes())
        if result.status not in (200,201):
            raise BackupError('ISOLATED_STORAGE_UPLOAD_FAILED')
        sha,size,_ = http.download(base.rstrip('/')+'/storage/v1/object/authenticated/'+target,headers,limit=row['bytes'])
        if sha != row['sha256'] or size != row['bytes']:
            raise BackupError('ISOLATED_STORAGE_READBACK_FAILED')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--credentials', type=Path, required=True)
    parser.add_argument('--identity', type=Path, required=True)
    parser.add_argument('--folder', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--restore-local', action='store_true')
    parser.add_argument('--storage-local-url')
    args = parser.parse_args()
    try:
        for file in (args.credentials,args.identity,args.output):
            external_file(file)
        if args.output.exists():
            raise BackupError('OUTPUT_DIRECTORY_ALREADY_EXISTS')
        if not re.fullmatch(r'[a-zA-Z0-9_-]{10,160}', args.folder):
            raise BackupError('GOOGLE_FOLDER_INVALID')
        if args.restore_local:
            pg_environment(os.environ.get('ISOLATED_RESTORE_DB_URL',''), isolated=True)
        if args.storage_local_url and not args.restore_local:
            raise BackupError('ISOLATED_DATABASE_RESTORE_REQUIRED')
        creds = json.loads(args.credentials.read_text())
        drive = Drive(creds['client_id'],creds['client_secret'],creds['refresh_token'])
        folder = drive.metadata(args.folder)
        props = folder.get('appProperties',{})
        if props.get('verified') != 'true' or not props.get('backup_id'):
            raise BackupError('BACKUP_NOT_COMMITTED')
        files = drive.list("trashed=false and '"+args.folder+"' in parents")
        if len(files) != 3 or {f['name'] for f in files} != NAMES:
            raise BackupError('BACKUP_FILES_INCOMPLETE')
        args.output.mkdir(parents=True, mode=0o700)
        for file in files:
            size = int(file['size'])
            if size > 20*1024**3:
                raise BackupError('RESTORE_FILE_TOO_LARGE')
            drive.download(file['id'],file['appProperties']['sha256'],size,args.output/file['name'])
        with tempfile.TemporaryDirectory(prefix='raad-restore-',dir=args.output.parent) as temp:
            directory = Path(temp)
            for file in files:
                (directory/file['name']).symlink_to((args.output/file['name']).resolve())
            manifest = inspect_plain(directory,args.identity,props['backup_id'])
            if args.restore_local:
                restore_database(directory,manifest,os.environ['ISOLATED_RESTORE_DB_URL'])
            if args.storage_local_url:
                restore_storage_local(args.storage_local_url,os.environ['ISOLATED_STORAGE_SERVICE_ROLE_KEY'],directory,manifest)
            # No persistent plaintext export; compatible local DB/Storage hold
            # restored data and must themselves be secured/destroyed by operator.
        print(json.dumps({'result':'ISOLATED_DATABASE_AND_STORAGE_VERIFIED' if args.restore_local else 'DECRYPTED_FILES_VERIFIED',
                          'backup_id':props['backup_id'],'storage_api_tested':bool(args.storage_local_url),'production_changed':False}))
        return 0
    except Exception as error:
        print(json.dumps({'result':'FAILED','error_code':error.code if isinstance(error,BackupError) else 'RESTORE_FAILED'}))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())

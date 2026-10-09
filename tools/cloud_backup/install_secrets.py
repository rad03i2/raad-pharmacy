"""Authorized local operator helper. Sends values to gh over stdin, never argv/logs.
No Age recovery/private identity is accepted. Requires gh auth login separately.
"""
import argparse
import json
import subprocess
from pathlib import Path
from .common import BackupError,pg_environment
from .setup_google import external_file


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--credentials',type=Path,required=True)
    parser.add_argument('--server-config',type=Path,required=True)
    args=parser.parse_args()
    try:
        for file in (args.credentials,args.server_config): external_file(file)
        oauth=json.loads(args.credentials.read_text());server=json.loads(args.server_config.read_text())
        pg_environment(server['database_url'])
        if not server['age_recipient'].startswith('age1'): raise BackupError('PUBLIC_AGE_RECIPIENT_REQUIRED')
        values={'CLOUD_GOOGLE_CLIENT_ID':oauth['client_id'],'CLOUD_GOOGLE_CLIENT_SECRET':oauth['client_secret'],
                'CLOUD_GOOGLE_REFRESH_TOKEN':oauth['refresh_token'],'CLOUD_BACKUP_DB_URL':server['database_url'],
                'CLOUD_SUPABASE_SERVICE_ROLE_KEY':server['service_role_key'],'CLOUD_AGE_RECIPIENT':server['age_recipient']}
        for name,value in values.items():
            result=subprocess.run(['gh','secret','set',name,'--repo','rad03i2/raad-pharmacy','--env','cloud-backup'],
                                   input=value.encode(),stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=60)
            if result.returncode: raise BackupError('SECRET_INSTALL_FAILED')
        print('CLOUD_ENVIRONMENT_SECRETS_INSTALLED')
        return 0
    except Exception as e:
        print(e.code if isinstance(e,BackupError) else 'SECRET_INSTALL_FAILED')
        return 1


if __name__=='__main__':raise SystemExit(main())

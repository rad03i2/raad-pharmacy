"""Hourly read-only liveness/quota check; exits nonzero for Actions notifications."""
import json
from datetime import timedelta
from .common import BackupError,PHARMACY,pg_environment,required,utcnow
from .database import connect


def main():
    connection=None
    try:
        connection=connect(pg_environment(required('CLOUD_BACKUP_DB_URL')))
        connection.execute('set default_transaction_read_only=on')
        row=connection.execute('select * from public.cloud_backup_status where pharmacy_id=%s',(PHARMACY,)).fetchone()
        if not row or not row['configured'] or not row['last_success_at']:
            raise BackupError('NO_VERIFIED_CLOUD_BACKUP')
        if utcnow()-row['last_success_at']>timedelta(hours=9):
            raise BackupError('CLOUD_BACKUP_STALE')
        if row['quota_limit'] and row['quota_used']/row['quota_limit']>=0.8:
            raise BackupError('GOOGLE_QUOTA_NEAR_FULL')
        print(json.dumps({'result':'BACKUP_RECENT_QUOTA_OK'}))
        return 0
    except Exception as e:
        print(json.dumps({'result':'ALERT','error_code':e.code if isinstance(e,BackupError) else 'STATUS_UNAVAILABLE'}))
        return 1
    finally:
        if connection:
            connection.close()


if __name__=='__main__':
    raise SystemExit(main())

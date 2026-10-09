from __future__ import annotations

import json
import os
import subprocess
from contextlib import contextmanager
from pathlib import Path

from .common import BackupError, PHARMACY, pg_environment, retry_delay

SCHEMAS = ("public", "auth", "storage", "raad_private", "extensions")
# Keep accounts/password hashes and trusted membership; exclude reusable sessions,
# notification tokens, OAuth client secrets, temporary login flows and Vault itself.
EXCLUDED_DATA = (
    "public.push_tokens", "public.user_presence", "auth.refresh_tokens", "auth.sessions",
    "auth.mfa_amr_claims", "auth.mfa_challenges", "auth.flow_state", "auth.one_time_tokens",
    "auth.saml_relay_states", "auth.oauth_clients", "auth.oauth_client_states",
    "auth.oauth_authorizations", "auth.oauth_consents", "auth.custom_oauth_providers",
    "auth.scim_tokens", "auth.webauthn_challenges", "auth.mfa_recovery_codes",
)


def connect(env):
    import psycopg
    from psycopg.rows import dict_row
    for attempt in range(3):
        try:
            return psycopg.connect(host=env["PGHOST"], port=env["PGPORT"], user=env["PGUSER"],
                                   password=env["PGPASSWORD"], dbname=env["PGDATABASE"],
                                   sslmode=env["PGSSLMODE"], sslrootcert=env.get("PGSSLROOTCERT", ""),
                                   connect_timeout=20, application_name="raad-central-backup",
                                   autocommit=True, row_factory=dict_row)
        except psycopg.OperationalError:
            if attempt == 2:
                raise BackupError('DATABASE_CONNECTION_FAILED') from None
            retry_delay(attempt)


def command(args, env, code):
    # Never print subprocess stderr: PostgreSQL errors can include record contents.
    result = subprocess.run(args, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=1200)
    if result.returncode:
        raise BackupError(code)
    return result.stdout


def metrics(connection):
    counts = {}
    for table in ("customers", "transactions", "profiles", "pharmacies", "audit_logs"):
        # Names come exclusively from this fixed allowlist.
        counts[table] = connection.execute(f"select count(*) as n from public.{table}").fetchone()["n"]
    counts["auth_users"] = connection.execute("select count(*) as n from auth.users").fetchone()["n"]
    counts["orphan_transactions"] = connection.execute("""select count(*) as n from public.transactions t
        left join public.customers c on c.id=t.customer_id where c.id is null""").fetchone()["n"]
    counts["duplicate_operations"] = connection.execute("""select count(*) as n from
        (select operation_id from public.transactions group by operation_id having count(*)>1) d""").fetchone()["n"]
    counts["balance_total"] = str(connection.execute("""select coalesce(sum(balance),0)::numeric as balance from
        (select c.id,c.opening_debt::numeric + coalesce(sum(case when t.type='DEBT' then t.amount::numeric
        when t.type='PAYMENT' then -t.amount::numeric else 0 end),0) as balance
        from public.customers c left join public.transactions t on t.customer_id=c.id and t.deleted_at is null
        where c.deleted_at is null group by c.id,c.opening_debt) balances""").fetchone()["balance"])
    if counts["orphan_transactions"] or counts["duplicate_operations"]:
        raise BackupError("FINANCIAL_INTEGRITY_FAILED")
    return counts


def objects(connection):
    return connection.execute("""select id::text,bucket_id,name,version,updated_at,metadata
        from storage.objects where coalesce(is_delete_marker,false)=false and archived_at is null
        order by bucket_id,name""").fetchall()


def object_identity(row):
    return (row["id"], row["bucket_id"], row["name"], row.get("version"), str(row["updated_at"]), row.get("metadata"))


class Database:
    def __init__(self, dsn, pharmacy=PHARMACY, *, isolated=False):
        self.env = pg_environment(dsn, isolated=isolated)
        self.pharmacy = pharmacy
        self.control = connect(self.env)
        try:
            self.observer = connect(self.env)
        except Exception:
            self.control.close()
            raise
        self.observer.execute("set default_transaction_read_only=on")

    def close(self):
        self.observer.close()
        self.control.close()

    @contextmanager
    def locked(self):
        # Cross-runner/admin-dispatch lock, in addition to GitHub concurrency.
        value = self.control.execute("select pg_try_advisory_lock(hashtextextended(%s,0)) as acquired",
                                     ("raad-central-backup:" + self.pharmacy,)).fetchone()["acquired"]
        if not value:
            raise BackupError("BACKUP_ALREADY_RUNNING")
        try:
            yield
        finally:
            self.control.execute("select pg_advisory_unlock(hashtextextended(%s,0))",
                                 ("raad-central-backup:" + self.pharmacy,))

    @contextmanager
    def snapshot(self):
        with self.control.transaction():
            self.control.execute("set transaction isolation level repeatable read read only")
            self.control.execute("set local statement_timeout='60s'")
            ids = [str(r["id"]) for r in self.control.execute("select id from public.pharmacies").fetchall()]
            if ids != [self.pharmacy]:
                raise BackupError("SINGLE_PHARMACY_SCOPE_MISMATCH")
            version = self.control.execute("select current_setting('server_version_num') as v").fetchone()["v"]
            if int(version) // 10000 != 17:
                raise BackupError("POSTGRES_VERSION_REQUIRES_REVIEW")
            snapshot_id = self.control.execute("select pg_export_snapshot() as id").fetchone()["id"]
            yield {"snapshot": snapshot_id, "server_version": version,
                   "metrics": metrics(self.control), "objects": objects(self.control),
                   "buckets": self.control.execute('select id,name,public,file_size_limit,allowed_mime_types from storage.buckets order by id').fetchall()}

    def dump(self, path: Path, snapshot):
        version = command(["pg_dump", "--version"], self.env, "PG_DUMP_UNAVAILABLE").decode()
        if " 17." not in version:
            raise BackupError("PG_DUMP_VERSION_MISMATCH")
        args = ["pg_dump", "--format=custom", "--no-owner", "--lock-wait-timeout=10s",
                "--snapshot=" + snapshot, "--file=" + str(path)]
        for schema in SCHEMAS:
            args.append("--schema=" + schema)
        for table in EXCLUDED_DATA:
            args.append("--exclude-table-data=" + table)
        env = {**self.env, "PGOPTIONS": "-c default_transaction_read_only=on"}
        command(args, env, "DATABASE_DUMP_FAILED")
        path.chmod(0o600)
        command(["pg_restore", "--list", str(path)], self.env, "DATABASE_ARCHIVE_INVALID")

    def check_objects(self, expected):
        if not expected:
            return
        rows = self.observer.execute("""select id::text,bucket_id,name,version,updated_at,metadata
            from storage.objects where id=any(%s::uuid[]) and coalesce(is_delete_marker,false)=false
            and archived_at is null""", ([r['id'] for r in expected],)).fetchall()
        actual = {row["id"]: object_identity(row) for row in rows}
        # New objects after the exported snapshot do not belong to that point.
        if any(actual.get(row["id"]) != object_identity(row) for row in expected):
            raise BackupError("STORAGE_CHANGED_DURING_BACKUP")

    def state(self, phase, *, run_id, error=None, **values):
        allowed = {"last_success_at", "retained_count", "total_bytes", "quota_used", "quota_limit", "drive_owner_email", "last_backup_id"}
        if set(values) - allowed:
            raise BackupError("STATE_UPDATE_INVALID")
        self.control.execute("""insert into public.cloud_backup_status(pharmacy_id,configured,phase,last_attempt_at,last_error_code)
            values(%s,true,%s,now(),%s) on conflict(pharmacy_id) do update set configured=true,
            phase=excluded.phase,last_attempt_at=case when excluded.phase='RUNNING' then now() else cloud_backup_status.last_attempt_at end,
            last_error_code=excluded.last_error_code,updated_at=now()""", (self.pharmacy, phase, error))
        for key, value in values.items():
            self.control.execute(f"update public.cloud_backup_status set {key}=%s where pharmacy_id=%s", (value, self.pharmacy))
        self.control.execute("""insert into public.cloud_backup_runs(id,pharmacy_id,state,error_code)
            values(%s,%s,%s,%s) on conflict(id) do update set state=excluded.state,error_code=excluded.error_code,
            completed_at=case when excluded.state in ('VERIFIED','FAILED') then now() else null end""",
                             (run_id, self.pharmacy, phase, error))

    def commit(self, run_id, folder_id, files, created_at):
        # This stores only opaque encrypted-file metadata. Never plaintext financial data.
        self.control.execute("""update public.cloud_backup_runs set drive_folder_id=%s,files=%s::jsonb,
            bytes=%s,created_at=%s,completed_at=now(),state='VERIFIED',error_code=null where id=%s""",
                             (folder_id, json.dumps(files), sum(f["bytes"] for f in files), created_at, run_id))

    def known(self, run_id):
        return self.control.execute("select * from public.cloud_backup_runs where id=%s and pharmacy_id=%s",
                                    (run_id, self.pharmacy)).fetchone()

    def complete_request(self, request_id, error=None):
        if request_id:
            self.control.execute("""update public.cloud_backup_requests set state=%s,error_code=%s,
                completed_at=now() where id=%s and pharmacy_id=%s""",
                                 ("FAILED" if error else "COMPLETED", error, request_id, self.pharmacy))

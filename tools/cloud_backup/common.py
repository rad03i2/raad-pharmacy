from __future__ import annotations

import hashlib
import json
import os
import re
import time
import uuid
from datetime import datetime, timezone, timedelta
from pathlib import Path
from urllib.parse import urlparse, unquote
from zoneinfo import ZoneInfo

PROJECT = "gsyrjhqkbfomxqacexle"
PHARMACY = "2ad39326-7794-4ba6-aad1-a1b772814aa1"
SCOPE = "https://www.googleapis.com/auth/drive.file"
ROOT_NAME = "Raad Pharmacy - Cloud Backups"


class BackupError(Exception):
    """Only fixed, nonsensitive diagnostic codes may leave the backup process."""
    def __init__(self, code: str):
        self.code = code if re.fullmatch(r"[A-Z0-9_]{3,80}", code) else "INTERNAL_ERROR"
        super().__init__(self.code)


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def iso(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat()


def next_slot(now: datetime) -> datetime:
    # Iraq 03/09/15/21 are UTC 00/06/12/18 (no DST).
    now = now.astimezone(timezone.utc)
    start = now.replace(hour=now.hour // 6 * 6, minute=0, second=0, microsecond=0)
    return start + timedelta(hours=6)


def backup_id(run_id: str, pharmacy: str = PHARMACY) -> str:
    return str(uuid.uuid5(uuid.NAMESPACE_URL, "raad-cloud:" + pharmacy + ":" + run_id))


def retention(points: list[dict]) -> set[str]:
    """8 periodic, 30 distinct days, 12 distinct months; union, never duplicate archives."""
    good = sorted((p for p in points if p.get("verified")), key=lambda p: p["created_at"], reverse=True)
    keep = {p["id"] for p in good[:8]}
    local = lambda d: datetime.fromisoformat(d).astimezone(ZoneInfo('Asia/Baghdad')).isoformat()
    for key, limit in ((lambda d: local(d)[:10], 30), (lambda d: local(d)[:7], 12)):
        buckets = set()
        for p in good:
            bucket = key(p["created_at"])
            if bucket in buckets:
                continue
            if len(buckets) >= limit:
                break
            buckets.add(bucket)
            keep.add(p["id"])
    return keep


def pg_environment(dsn: str, *, isolated: bool = False) -> dict[str, str]:
    """Keep database passwords out of argv/process listings and enforce TLS."""
    url = urlparse(dsn)
    host = url.hostname or ""
    user = unquote(url.username or "")
    if url.scheme not in ("postgres", "postgresql") or not host or not user:
        raise BackupError("DATABASE_URL_INVALID")
    if url.port == 6543:
        raise BackupError("SESSION_POOLER_REQUIRED")
    if isolated:
        if host not in ("localhost", "127.0.0.1", "::1"):
            raise BackupError("RESTORE_REQUIRES_LOCAL_ISOLATED_SERVER")
    elif not (host == f"db.{PROJECT}.supabase.co" or
              (host.endswith(".pooler.supabase.com") and user.endswith("." + PROJECT))):
        raise BackupError("DATABASE_PROJECT_MISMATCH")
    env = dict(os.environ)
    env.update(PGHOST=host, PGPORT=str(url.port or 5432), PGUSER=user,
               PGPASSWORD=unquote(url.password or ""), PGDATABASE=unquote(url.path.lstrip("/") or "postgres"),
               PGCONNECT_TIMEOUT="20", PGAPPNAME="raad-central-backup")
    if isolated:
        env["PGSSLMODE"] = "disable"
    else:
        env.update(PGSSLMODE="verify-full", PGSSLROOTCERT="/etc/ssl/certs/ca-certificates.crt")
    return env


def required(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise BackupError("CONFIGURATION_INCOMPLETE")
    return value


def private_json(path: Path, value: dict) -> None:
    with path.open("w", encoding="utf-8") as handle:
        os.chmod(path, 0o600)
        json.dump(value, handle, ensure_ascii=False, default=str, sort_keys=True)


def retry_delay(attempt: int) -> None:
    time.sleep(min(30, 2 ** attempt))

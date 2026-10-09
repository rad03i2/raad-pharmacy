from __future__ import annotations

import json
import os
import shutil
import subprocess
import tarfile
import tempfile
import uuid
from pathlib import Path
from urllib.parse import quote

from .common import (BackupError, PROJECT, PHARMACY, ROOT_NAME, backup_id, digest,
                     iso, next_slot, private_json, required, retention, retry_delay, utcnow)
from .database import Database
from .drive import Drive, API
from .http import Http


def encrypt(source: Path, recipient: str) -> Path:
    if not recipient.startswith("age1") or len(recipient) < 55:
        raise BackupError("AGE_RECIPIENT_INVALID")
    destination = source.with_name(source.name + ".age")
    result = subprocess.run(["age", "--recipient", recipient, "--output", str(destination), str(source)],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=1200)
    if result.returncode or not destination.is_file() or destination.stat().st_size <= source.stat().st_size:
        raise BackupError("ENCRYPTION_FAILED")
    destination.chmod(0o600)
    source.unlink()
    return destination


def storage_archive(db, capture, directory, service_key, http=None):
    http = http or Http()
    entries = []
    target = directory / "storage-backup.tar"
    with tarfile.open(target, "w") as archive:
        for row in capture["objects"]:
            object_id = str(uuid.UUID(row["id"]))
            bucket, name = row["bucket_id"], row["name"]
            local = directory / object_id
            url = f"https://{PROJECT}.supabase.co/storage/v1/object/authenticated/" + quote(bucket, safe="") + "/" + quote(name, safe="/")
            metadata = row.get("metadata") or {}
            expected_size = int(metadata.get("size", -1))
            if expected_size < 0 or expected_size > 100 * 1024 * 1024:
                raise BackupError("STORAGE_OBJECT_SIZE_INVALID")
            db.check_objects([row])
            for attempt in range(5):
                try:
                    sha, size, headers = http.download(url, {"Authorization": "Bearer " + service_key, "apikey": service_key},
                                                       target=local, limit=expected_size)
                    break
                except BackupError as error:
                    if error.code != "NETWORK_INTERRUPTED" or attempt == 4:
                        raise
                    retry_delay(attempt)
            if size != expected_size:
                raise BackupError("STORAGE_OBJECT_SIZE_MISMATCH")
            expected_etag = metadata.get("eTag") or metadata.get("etag")
            if expected_etag and headers.get("etag", "").strip('"') != str(expected_etag).strip('"'):
                raise BackupError("STORAGE_OBJECT_VERSION_MISMATCH")
            db.check_objects([row])
            archive_name = "objects/" + object_id
            archive.add(local, arcname=archive_name, recursive=False)
            local.unlink()
            entries.append({"id": object_id, "bucket": bucket, "path": name, "version": row.get("version"),
                            "archive_path": archive_name, "bytes": size, "sha256": sha, "metadata": metadata})
    target.chmod(0o600)
    db.check_objects(capture["objects"])
    return target, entries


class BackupJob:
    def __init__(self, db, drive, recipient, *, directory_factory=None, storage=storage_archive):
        self.db, self.drive, self.recipient = db, drive, recipient
        self.directory_factory, self.storage = directory_factory or tempfile.TemporaryDirectory, storage

    def root(self):
        rows = self.drive.list("trashed=false and mimeType='application/vnd.google-apps.folder' and appProperties has { key='raad_root' and value='" + PROJECT + "' }")
        if len(rows) > 1:
            raise BackupError("GOOGLE_ROOT_AMBIGUOUS")
        return rows[0] if rows else self.drive.folder(ROOT_NAME, properties={"raad_root": PROJECT})

    def run(self, run_id, *, service_key, request_id=None):
        with self.db.locked():
            # Re-running a completed GitHub run/request revalidates it; no duplicate backup.
            previous = self.db.known(run_id)
            if previous and previous["state"] == "VERIFIED":
                for file in previous["files"]:
                    self.drive.verify(file["id"], file["sha256"], file["bytes"])
                self.db.complete_request(request_id)
                return "ALREADY_VERIFIED"
            self.db.state("RUNNING", run_id=run_id)
            try:
                about = self.drive.about()
                quota = about.get("storageQuota", {})
                used, limit = int(quota.get("usage", 0)), int(quota.get("limit", 0))
                root = self.root()
                # An interrupted, uncommitted attempt cannot become a restore point.
                # Remove only same logical ID's uncommitted folders, never a verified point.
                unfinished = self.drive.list("trashed=false and '" + root["id"] + "' in parents and appProperties has { key='backup_id' and value='" + run_id + "' }")
                for folder in unfinished:
                    if folder.get("appProperties", {}).get("verified") == "true":
                        files = self.drive.list("trashed=false and '" + folder["id"] + "' in parents")
                        recovered = [{"id": f["id"], "name": f["name"], "bytes": int(f["size"]),
                                      "sha256": f["appProperties"]["sha256"]} for f in files]
                        if {f["name"] for f in recovered} != {"database.dump.age", "storage-backup.tar.age", "manifest.json.age"}:
                            raise BackupError("GOOGLE_COMMIT_INCOMPLETE")
                        for file in recovered:
                            self.drive.verify(file["id"], file["sha256"], file["bytes"])
                        self.db.commit(run_id, folder["id"], recovered, folder["appProperties"]["created_at"])
                        self.finish(run_id, root, about)
                        self.db.complete_request(request_id)
                        return "RECOVERED_VERIFIED_COMMIT"
                    self.drive.patch(folder["id"], {"trashed": True})
                created = iso(utcnow())
                with self.directory_factory(prefix="raad-cloud-", dir=os.environ.get("RUNNER_TEMP")) as temp:
                    directory = Path(temp)
                    directory.chmod(0o700)
                    with self.db.snapshot() as capture:
                        dump = directory / "database.dump"
                        self.db.dump(dump, capture["snapshot"])
                        storage, items = self.storage(self.db, capture, directory, service_key)
                        manifest = {"format": "raad-cloud-backup-v1", "backup_id": run_id, "created_at": created,
                                    "project_ref": PROJECT, "pharmacy_id": self.db.pharmacy,
                                    "application_version": "3.3.19.2", "server_version": capture["server_version"],
                                    "metrics": capture["metrics"], "storage": items, "buckets": capture["buckets"],
                                    "files": [{"name": p.name, "bytes": p.stat().st_size, "sha256": digest(p)} for p in (dump, storage)],
                                    "verification": {"pg_restore_list": True, "storage_versions_stable": True,
                                                     "live_restore_tested": False},
                                    "excluded": ["unuploaded_phone_changes", "auth_sessions_refresh_tokens", "fcm_tokens", "vault_secrets"],
                                    "restore_requires": ["compatible_Supabase_roles_extensions_auth_storage", "provider_SMTP_JWT_settings", "reauthentication"]}
                        manifest_path = directory / "manifest.json"
                        private_json(manifest_path, manifest)
                    encrypted = [encrypt(p, self.recipient) for p in (dump, storage, manifest_path)]
                    total = sum(p.stat().st_size for p in encrypted)
                    if limit and used + total > limit:
                        raise BackupError("GOOGLE_QUOTA_FULL")
                    folder = self.drive.folder(created.replace(":", "").split("+")[0] + "_" + run_id[:8], root["id"],
                                               {"backup_id": run_id, "created_at": created, "verified": "false"})
                    files = [self.drive.upload(p, folder["id"], backup_id=run_id) for p in encrypted]
                    # Two-phase publication: immutable encrypted payloads, downloads verified,
                    # then folder commit marker, finally status. A previous good point is untouched.
                    self.drive.patch(folder["id"], {"appProperties": {"backup_id": run_id, "created_at": created, "verified": "true"}})
                    if self.drive.metadata(folder["id"]).get("appProperties", {}).get("verified") != "true":
                        raise BackupError("GOOGLE_COMMIT_NOT_VISIBLE")
                    self.db.commit(run_id, folder["id"], files, created)
                self.finish(run_id, root, about)
                self.db.complete_request(request_id)
                return "VERIFIED_ENCRYPTED_DOWNLOAD"
            except Exception as error:
                code = error.code if isinstance(error, BackupError) else "BACKUP_FAILED"
                self.db.state("FAILED", run_id=run_id, error=code)
                self.db.complete_request(request_id, code)
                raise BackupError(code) from None

    def finish(self, run_id, root, about):
        folders = self.drive.list("trashed=false and '" + root["id"] + "' in parents")
        good = [f for f in folders if f.get("appProperties", {}).get("verified") == "true"]
        points = [{"id": f["id"], "created_at": f["appProperties"]["created_at"], "verified": True} for f in good]
        keep = retention(points)
        # Reverify the new point before retention removes an older restore point.
        known = self.db.known(run_id)
        for file in known["files"]:
            self.drive.verify(file["id"], file["sha256"], file["bytes"])
        for point in points:
            if point["id"] not in keep:
                self.drive.patch(point["id"], {"trashed": True})
                self.db.control.execute("update public.cloud_backup_runs set state='RETAINED_OUT' where drive_folder_id=%s and pharmacy_id=%s",
                                        (point["id"], self.db.pharmacy))
        all_files = self.drive.list("trashed=false and appProperties has { key='raad_project' and value='" + PROJECT + "' }")
        # Drive trash can continue consuming quota. Quota figures are refreshed, not guessed.
        current = self.drive.about()
        quota = current.get("storageQuota", {})
        self.db.state("VERIFIED", run_id=run_id, last_success_at=known['created_at'], last_backup_id=run_id,
                      retained_count=len(keep), total_bytes=sum(int(f.get("size", 0)) for f in all_files),
                      quota_used=int(quota.get("usage", 0)), quota_limit=int(quota.get("limit", 0)),
                      drive_owner_email=about.get("user", {}).get("emailAddress"))


def main():
    db = None
    run_id = None
    try:
        request = os.environ.get("CLOUD_REQUEST_ID") or None
        if request:
            request = str(uuid.UUID(request))
        run_id = backup_id(request or required("GITHUB_RUN_ID"))
        db = Database(required("CLOUD_BACKUP_DB_URL"))
        drive = Drive(required("CLOUD_GOOGLE_CLIENT_ID"), required("CLOUD_GOOGLE_CLIENT_SECRET"), required("CLOUD_GOOGLE_REFRESH_TOKEN"))
        result = BackupJob(db, drive, required("CLOUD_AGE_RECIPIENT")).run(
            run_id, service_key=required("CLOUD_SUPABASE_SERVICE_ROLE_KEY"), request_id=os.environ.get("CLOUD_REQUEST_ID") or None)
        print(json.dumps({"backup_id": run_id, "result": result}))
    except Exception as error:
        code = error.code if isinstance(error, BackupError) else "BACKUP_FAILED"
        print(json.dumps({"backup_id": run_id, "result": "FAILED", "error_code": code}))
        return 1
    finally:
        if db:
            db.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

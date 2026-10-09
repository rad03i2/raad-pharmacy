from __future__ import annotations

import json
import re
import time
from pathlib import Path
from urllib.parse import urlencode, urlparse, quote

from .common import BackupError, PROJECT, SCOPE, digest, retry_delay
from .http import Http

API = "https://www.googleapis.com/drive/v3"
UPLOAD = "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable"
FIELDS = "id,name,size,appProperties,parents,createdTime,mimeType"


class Drive:
    def __init__(self, client_id, client_secret, refresh_token, http=None, delay=retry_delay):
        self.client_id, self.secret, self.refresh = client_id, client_secret, refresh_token
        self.http, self.delay = http or Http(), delay
        self.token, self.expires = "", 0

    def authorization(self, force=False):
        if force or time.monotonic() >= self.expires:
            body = urlencode({"client_id": self.client_id, "client_secret": self.secret,
                              "refresh_token": self.refresh, "grant_type": "refresh_token"}).encode()
            result = self.http.request("POST", "https://oauth2.googleapis.com/token",
                                       {"Content-Type": "application/x-www-form-urlencoded"}, body)
            if result.status != 200:
                raise BackupError("GOOGLE_AUTH_REVOKED" if result.status in (400, 401) else "GOOGLE_TOKEN_FAILED")
            data = result.json()
            if not data.get("access_token"):
                raise BackupError("GOOGLE_TOKEN_INVALID")
            if data.get("scope") and SCOPE not in data["scope"].split():
                raise BackupError("GOOGLE_SCOPE_MISSING")
            self.token = data["access_token"]
            self.expires = time.monotonic() + max(1, int(data.get("expires_in", 3600)) - 90)
        return {"Authorization": "Bearer " + self.token}

    def call(self, method, url, body=None, headers=None, *, retry=True):
        # Resumable session URLs are capabilities; validate host and never log them.
        parsed = urlparse(url)
        if parsed.scheme != "https" or parsed.hostname != "www.googleapis.com":
            raise BackupError("GOOGLE_URL_INVALID")
        for attempt in range(5):
            try:
                result = self.http.request(method, url, {**self.authorization(), **(headers or {})}, body)
            except BackupError as error:
                if not retry or error.code != "NETWORK_INTERRUPTED" or attempt == 4:
                    raise
                self.delay(attempt)
                continue
            if result.status == 401 and attempt < 4:
                self.authorization(force=True)
                continue
            if result.status == 403:
                error = result.json().get("error", {})
                reasons = {e.get("reason") for e in error.get("errors", [])}
                if "storageQuotaExceeded" in reasons:
                    raise BackupError("GOOGLE_QUOTA_FULL")
                transient = bool(reasons & {"rateLimitExceeded", "userRateLimitExceeded"})
            else:
                transient = result.status == 429 or result.status >= 500
            if transient and retry and attempt < 4:
                self.delay(attempt)
                continue
            return result
        raise BackupError("GOOGLE_RETRY_EXHAUSTED")

    def json_call(self, method, path, value=None):
        result = self.call(method, API + path, json.dumps(value).encode() if value is not None else None,
                           {"Content-Type": "application/json"} if value is not None else {})
        if result.status not in (200, 201):
            raise BackupError("GOOGLE_API_FAILED")
        return result.json()

    def about(self):
        return self.json_call("GET", "/about?fields=user(emailAddress),storageQuota")

    def list(self, query):
        rows, page = [], None
        while True:
            args = {"q": query, "fields": "nextPageToken,files(" + FIELDS + ")", "pageSize": 1000}
            if page:
                args["pageToken"] = page
            data = self.json_call("GET", "/files?" + urlencode(args))
            rows += data.get("files", [])
            page = data.get("nextPageToken")
            if not page:
                return rows

    def generate_id(self):
        return self.json_call("GET", "/files/generateIds?count=1&space=drive&type=files")["ids"][0]

    def metadata(self, file_id):
        return self.json_call("GET", "/files/" + quote(file_id, safe="") + "?fields=" + FIELDS)

    def folder(self, name, parent=None, properties=None):
        # Pre-generated ID makes a transport retry of create idempotent.
        file_id = self.generate_id()
        value = {"id": file_id, "name": name, "mimeType": "application/vnd.google-apps.folder",
                 "appProperties": properties or {}}
        if parent:
            value["parents"] = [parent]
        result = self.call("POST", API + "/files?fields=" + FIELDS, json.dumps(value).encode(), {"Content-Type": "application/json"})
        if result.status == 409:
            return self.metadata(file_id)
        if result.status not in (200, 201):
            raise BackupError("GOOGLE_FOLDER_FAILED")
        return result.json()

    def patch(self, file_id, value):
        return self.json_call("PATCH", "/files/" + quote(file_id, safe="") + "?fields=" + FIELDS, value)

    @staticmethod
    def offset(result, size):
        if result.status in (200, 201):
            return size
        if result.status != 308:
            raise BackupError("GOOGLE_UPLOAD_STATUS_FAILED")
        value = result.headers.get("range", "")
        match = re.fullmatch(r"bytes=0-(\d+)", value)
        if value and not match:
            raise BackupError("GOOGLE_UPLOAD_RANGE_INVALID")
        offset = int(match[1]) + 1 if match else 0
        if offset > size:
            raise BackupError("GOOGLE_UPLOAD_RANGE_INVALID")
        return offset

    def upload(self, path: Path, parent, *, backup_id):
        size, sha, file_id = path.stat().st_size, digest(path), self.generate_id()
        metadata = {"id": file_id, "name": path.name, "parents": [parent],
                    "appProperties": {"backup_id": backup_id, "sha256": sha, "raad_project": PROJECT}}
        # Restart expired sessions with the same pre-generated file identity.
        for restart in range(3):
            start = self.call("POST", UPLOAD + "&fields=" + FIELDS, json.dumps(metadata).encode(),
                              {"Content-Type": "application/json", "X-Upload-Content-Type": "application/octet-stream",
                               "X-Upload-Content-Length": str(size)})
            if start.status == 409:
                self.verify(file_id, sha, size)
                return {"id": file_id, "sha256": sha, "bytes": size, "name": path.name}
            if start.status not in (200, 201) or not start.headers.get("location"):
                raise BackupError("GOOGLE_UPLOAD_START_FAILED")
            session, offset, failures = start.headers["location"], 0, 0
            with path.open("rb") as source:
                while offset < size:
                    source.seek(offset)
                    chunk = source.read(1024 * 1024)  # 4 x 256 KiB.
                    try:
                        result = self.call("PUT", session, chunk,
                                           {"Content-Type": "application/octet-stream",
                                            "Content-Range": f"bytes {offset}-{offset + len(chunk) - 1}/{size}"}, retry=False)
                    except BackupError as error:
                        if error.code != "NETWORK_INTERRUPTED":
                            raise
                        result = None
                    if result is None or result.status >= 500 or result.status in (401, 403, 429):
                        failures += 1
                        if failures > 5:
                            raise BackupError("GOOGLE_UPLOAD_RETRY_EXHAUSTED")
                        self.delay(failures - 1)
                        result = self.call("PUT", session, b"", {"Content-Range": f"bytes */{size}"})
                    if result.status in (404, 410):
                        break
                    acknowledged = self.offset(result, size)
                    if acknowledged < offset or acknowledged == offset:
                        failures += 1
                        if failures > 5:
                            raise BackupError("GOOGLE_UPLOAD_NO_PROGRESS")
                    offset = acknowledged
                if offset == size:
                    self.verify(file_id, sha, size)
                    return {"id": file_id, "sha256": sha, "bytes": size, "name": path.name}
            self.delay(restart)
        raise BackupError("GOOGLE_UPLOAD_SESSION_EXPIRED")

    def verify(self, file_id, sha, size):
        meta = self.metadata(file_id)
        if int(meta.get("size", -1)) != size:
            raise BackupError("GOOGLE_FILE_SIZE_MISMATCH")
        self.download(file_id, sha, size)

    def download(self, file_id, sha, size, target=None):
        for attempt in range(5):
            try:
                actual, count, _ = self.http.download(API + "/files/" + quote(file_id, safe="") + "?alt=media",
                                                      self.authorization(), target=target, limit=size)
                if count != size or actual != sha:
                    raise BackupError("GOOGLE_FILE_CHECKSUM_MISMATCH")
                return
            except BackupError as error:
                if error.code == "AUTH_EXPIRED" and attempt < 4:
                    self.authorization(force=True)
                    continue
                if error.code != "NETWORK_INTERRUPTED" or attempt == 4:
                    raise
                self.delay(attempt)

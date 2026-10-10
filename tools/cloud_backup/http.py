from __future__ import annotations

import hashlib
import json
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path

from .common import BackupError


@dataclass
class Response:
    status: int
    headers: dict[str, str]
    body: bytes

    def json(self) -> dict:
        try:
            return json.loads(self.body)
        except (ValueError, UnicodeError):
            raise BackupError("REMOTE_RESPONSE_INVALID") from None


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None  # Never forward bearer credentials to a redirect target.


class Http:
    def __init__(self):
        self.opener = urllib.request.build_opener(NoRedirect())

    def open(self, method, url, headers=None, data=None):
        request = urllib.request.Request(url, data=data, headers=headers or {}, method=method)
        try:
            return self.opener.open(request, timeout=60)
        except urllib.error.HTTPError as error:
            return error
        except (urllib.error.URLError, TimeoutError, OSError):
            raise BackupError("NETWORK_INTERRUPTED") from None

    def request(self, method, url, headers=None, data=None) -> Response:
        with self.open(method, url, headers, data) as result:
            payload = result.read(2 * 1024 * 1024 + 1)
            if len(payload) > 2 * 1024 * 1024:
                raise BackupError("REMOTE_RESPONSE_TOO_LARGE")
            return Response(result.status, {k.lower(): v for k, v in result.headers.items()}, payload)

    def download(self, url, headers, *, target: Path | None = None, limit: int) -> tuple[str, int, dict]:
        with self.open("GET", url, headers) as response:
            if response.status != 200:
                if response.status == 401:
                    raise BackupError("AUTH_EXPIRED")
                raise BackupError("DOWNLOAD_FAILED")
            value, size = hashlib.sha256(), 0
            handle = target.open("wb") if target else None
            try:
                if target:
                    target.chmod(0o600)
                while True:
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    size += len(chunk)
                    if size > limit:
                        raise BackupError("DOWNLOAD_SIZE_MISMATCH")
                    value.update(chunk)
                    if handle:
                        handle.write(chunk)
                return value.hexdigest(), size, {k.lower(): v for k, v in response.headers.items()}
            except (OSError, TimeoutError):
                raise BackupError("NETWORK_INTERRUPTED") from None
            finally:
                if handle:
                    handle.close()

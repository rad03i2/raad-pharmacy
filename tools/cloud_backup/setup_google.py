"""Run only on the authorized administrator's computer, never in Actions/APK.

Credentials and recovery identities MUST be outside the checkout. Nothing secret
is printed; Google's official loopback OAuth flow performs account selection.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

from .common import BackupError, SCOPE, private_json


def external_file(path: Path):
    root = Path(__file__).resolve().parents[2]
    if path.resolve().is_relative_to(root):
        raise BackupError('CREDENTIAL_FILE_MUST_BE_OUTSIDE_REPOSITORY')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--client', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        external_file(args.client)
        external_file(args.output)
        if args.output.exists():
            raise BackupError('CREDENTIAL_FILE_ALREADY_EXISTS')
        from google_auth_oauthlib.flow import InstalledAppFlow
        data = json.loads(args.client.read_text())
        if 'installed' not in data:
            raise BackupError('DESKTOP_OAUTH_CLIENT_REQUIRED')
        flow = InstalledAppFlow.from_client_config(data, scopes=[SCOPE], autogenerate_code_verifier=True)
        credentials = flow.run_local_server(
            host='127.0.0.1', port=0, access_type='offline', prompt='consent select_account',
            authorization_prompt_message='Authorize the pharmacy account in the official Google browser page.',
            success_message='Authorization complete. You may close this window.')
        if not credentials.refresh_token:
            raise BackupError('OFFLINE_CONSENT_REQUIRED')
        args.output.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        private_json(args.output, {'client_id': credentials.client_id, 'client_secret': credentials.client_secret,
                                  'refresh_token': credentials.refresh_token, 'scope': SCOPE})
        print('GOOGLE_OFFLINE_CREDENTIAL_FILE_CREATED')
        return 0
    except Exception as error:
        print(error.code if isinstance(error, BackupError) else 'GOOGLE_SETUP_FAILED')
        return 1


if __name__ == '__main__':
    raise SystemExit(main())

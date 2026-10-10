# 3.3.19.4 — automatic portable backups

The working Room ledger, offline operations, Supabase synchronization, multi-user data visibility, and financial notifications retain their existing behavior. The new backup engine observes the existing durable backup watermark after repository initialization. It never writes financial records or changes synchronization policy.

## Phone and SD

On Android 10 and later the first app launch creates a verified complete recovery file through MediaStore in `Download/دفتر صيدلية رعد/`. Android 8/9 require a one-time storage permission; opening the backup screen requests it. Public files survive uninstall. After reinstall, Android may require selecting the old file through the system picker rather than letting the new installation enumerate it.

Every committed customer, debt, payment, edit, deletion, photo or remote-ledger change wakes a conflated 250 ms queue. A consistent Room capture produces a complete `.raadbackup` file containing the existing validated ledger, photos and pending-cloud metadata. The same bytes are mirrored to SD and Drive. The private cached file uses AtomicFile and lives in noBackupFilesDir. Repeated failures reuse that verified file; the durable sequence survives process death and pruning of older journal rows.

Public files are immutable recovery points. MediaStore holds a new file pending until write/read-back/byte comparison and full validation complete. SD providers write new files and reopen them; incomplete attempts can be retried. A preceding verified file is never truncated. Cleanup keeps the latest three points and only runs after a successful write. Failed/unavailable SD destinations never stop the phone destination, never advance their acknowledged sequence, and retry on media mount, a bounded WorkManager drain, and periodic maintenance. Disabling SD leaves its files.

The new portable envelope includes a SHA-256 checksum and a full snapshot. This checksum detects corruption, not malicious modification. The format is intentionally not encrypted with an installation-specific key: users can recover on another phone without separately preserving a key. The phone screen explains that the file contains customer information. When a portable external destination is verified, its legacy external mirror stops writing; old files remain available and no old cursor prevents pruning the private journal. Disabling SD also stops its legacy mirror. Existing encrypted private safety copies and legacy encrypted imports remain supported; legacy recovery keys are shown only in the old-backup recovery flow.

## Google Drive

The app uses Google Identity Services AuthorizationClient with `drive.file` scope, not a server-owned Google refresh token or the ordinary Supabase data channel. Users choose/grant access to their Google account through Google's UI. The selected email is obtained from Drive about.user, subsequent background authorization is pinned to that account, and access tokens are never persisted by this app or logged.

After successful authorization, a network-constrained WorkManager job uploads the verified local artifact into an app-owned Drive folder and a separate per-installation device folder. Upload acknowledgment requires matching the Drive-reported size and MD5 checksum. The latest three files are retained per device; one phone never replaces another phone's backup. Offline mutations keep the latest complete local artifact and remain visibly pending until an upload succeeds. A bounded drain handles mutations during upload. Cloud history lists only files accessible under this app's granted scope, and a download is checksum/ledger validated before restore preview.

**External activation is still required.** The checked-in google-services.json contains no Android OAuth client. This repository and GitHub integration do not grant Google Cloud project administration. Before live account authorization can be claimed as tested, the project owner must:

1. Enable Google Drive API in the intended Google Cloud project.
2. Configure the Google Auth Platform consent screen, with the app's privacy policy and `drive.file` scope. Add the three intended users as test users if the app remains in Testing.
3. Register an Android OAuth client for package `com.radwan.raadpharmacy` using the SHA-1 fingerprint of the existing production signing certificate. The production SHA-1 fingerprint is `84:29:94:E2:DB:27:1E:E9:CD:39:53:98:BE:0C:4B:E0:7C:9D:C8:92` (read from the previous official APK CI signer report). The public SHA-256 pin is `506282b5c28fac0f3170ee99eeaf32b763eaa9c1031374574dcee2aec7dd95b6`; SHA-1 must be read from that same certificate, not substituted with SHA-256 or a debug signer.
4. Install the official production-signed APK, grant Drive access, change a debt while offline, reconnect, then restore that uploaded file on a second installation/account-authorized phone.

No client secret belongs in the APK. Device-side authorization does not require a new Supabase deployment. Developer-error/missing-API states are shown honestly; the app never reports a cloud backup current before a verified upload.

## Recovery and interface

The main screen has live backup information, three destinations and a restore button at the bottom. It omits the old internal-storage card and recovery-key destination. Restore can choose a file directly from phone/SD, select an available recent local file, or load Drive history after connecting the account. Every restore has a preview and explicit confirmation. The existing safety snapshot and restoration hold continue protecting the central ledger from unintended uploads of old debts. Returning to current pharmacy data keeps the existing reconciliation workflow.

## Validation

Automated tests cover complete recovery after customer/debt/payment/photo/edit/delete/remote changes, SD removal and catch-up, failed phone writes, durable restart after journal pruning, portable corruption, incomplete SD retries, MediaStore pending publication and safe retention, Drive upload checksums and per-device metadata, limited/account-pinned authorization, and RTL/large-text Compose navigation. CI also runs the existing ledger/synchronization/restore regressions, release lint and production signing continuity checks. Device filesystem behavior, battery behavior and live Google authorization require the physical acceptance check above; host tests do not replace it.

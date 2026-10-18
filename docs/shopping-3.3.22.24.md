# 3.3.22.24 / versionCode 92

## Scope

Independent shared medicine purchase list and bottom-tab performance. Financial Room schema remains v4; no ledger writer, recovery guard, financial outbox acknowledgement, financial SQL trigger, auth flow, financial notification, Telegram, PDF or existing reminder changes.

## Navigation

Six stable equal-size hit targets (minimum 48dp on supported 320dp widths), one draw-only 170ms indicator. Removed animated slot widths, per-frame text subcomposition and icon crossfade layers. Label measurement is cached by width/font. Bottom destinations save/restore state and switch without NavHost content crossfades; rapid deliberate tab changes are accepted instead of being silently dropped by the 280ms navigation guard. Secondary routes retain their duplicate-click guard.

## Shopping

Home ShoppingBag action beside microphone and wallet. Persistent oldest-first items, search, name/photo entry, optional quantity/note, repeated-add form, local photo enlargement, explicit purchase confirmation, stable list anchor after removing a row. No weekly reset or purchased archive.

New public medicine_shopping_items stores only title, note, local-photo flag and author/time metadata. Tenant-scoped medicine_shopping_signal announces revisions. Only active visible pharmacy profiles can read their pharmacy's rows or call guarded RPCs. No direct authenticated table writes; anon/hidden/cross-pharmacy operations are rejected.

Purchase atomically inserts an opaque UUID suppression marker into a closed private table and hard-deletes the active item's content. Retrying the original UUID can never resurrect it, even if purchase arrives before the offline create. Per-item advisory locks serialize concurrent create/purchase in both orders. Realtime signals trigger full authoritative snapshots; bounded foreground polling recovers a lost signal. No financial events or FCM are emitted.

## Local storage and offline operation

Separate SQLite database and photo tree under Android noBackupFilesDir/medicine-shopping. Existing backup inclusion rules include only the financial ledger, so these images never enter Android backup, portable financial backups, central backup upload, Supabase Storage or any new cloud payload. Images are read from Android document providers/camera, bounded to 25MiB input, downsampled/re-encoded as local JPEG and stripped of source metadata. Thumbnails decode off the UI thread. EXIF camera orientation is applied before stripping source metadata. Saved draft/camera duplicates are cleaned after a process interruption and purchase; unsaved drafts expire after seven days. The gallery original remains under its original provider's control.

A corrupt shopping database is preserved with an explicit local-storage error, never silently deleted/recreated by Android's default corruption handler. Creates and deletes are committed locally before networking; deletion strips local text/photo metadata and removes the file. Network operations have stable UUIDs and are acknowledged only by a matching owner/pharmacy/ID/active receipt. Pending deletions beat stale snapshots and late create replies. Synced rows absent from a full snapshot and their local images are removed. Pending offline additions remain. Another active member on the same installation may finish a tenant-scoped pending deletion; another account's unsent create is not silently attributed to it.

Independent network-constrained WorkManager work persists retries through restart. Its work/tag, mutexes and SQLite files are separate from financial upload and backups. Signed-out or changed accounts cannot apply another user's network reply. Full snapshots are aggregate JSON documents rather than paginated table reads, retaining lists larger than 1000 rows.

## Limits

Images are visible only on their original installation. A photo-only item appears on other devices as a numbered photo item with a local-image explanation; adding its name is encouraged but not required. Removing/reinstalling the app or losing that phone loses local-only images. Unsynced text exists only on that installation until a server receipt is verified. Offline devices receive purchases/deletions on reconnect; Android may delay background jobs. Permanent removal means removal from the active application and its local image files, not retroactive rewriting of historical database provider backups. Only content-free UUID markers remain to prevent resurrection.

## Validation

See CI reports for actual results before publication. Added API26/35 SQLite persistence, scope, malformed/wrong receipts, stale snapshot, 1200-row, two real HTTP-client/device and offline/lost-response tests; isolated PostgreSQL RLS/idempotency/two-connection races and unchanged financial-row checks; native RTL UI screenshots and mid-list removal/search checks. Production financial data is not used or modified by tests.

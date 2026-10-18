# 3.3.22.25 (versionCode 93)

## Requested behavior

- Monthly reports opens from the Reports period selector (Today / Week / Month / Monthly reports). It is not another rolling report period. Baghdad calendar months and inclusive custom-day ranges retain the v23 accounting rules.
- The sixth-tab layout keeps six fixed targets; Shopping replaces Monthly reports between Reports and Settings. The extra Home shopping shortcut is removed; wallet and microphone remain.
- Shopping uses a compact count card, toolbar search toggle, persistent keyed list position, and an animated 1,000 ms checkmark hold. Releasing or cancelling before completion does nothing. There is no purchase confirmation dialog; accessibility has a labelled long-click action.
- Purchased materials enter recoverable trash for 24 hours. Restore uses the original ID and deletion-generation token. A server-authorized restore must arrive before expiry. Content is hidden immediately at expiry and physically purged by a dedicated minute cron; the original device removes its private photo on its next cleanup/wake. A powered-off device cannot physically erase its files while off.
- v24's permanent completion RPC retains its existing contract. Completing an item on v24 also clears v25 recoverable content; that deletion cannot be recovered. An opaque ID marker prevents delayed offline creates from reviving it.
- Android Sharesheet exposes **إضافة مادة لقائمة الشراء** and **حفظ سريع لقائمة الشراء**, both supporting image SEND and SEND_MULTIPLE (up to 20). Details mode has optional title and count; for multiple images, these apply to each image. Quick mode saves without a details or success dialog. Existing account/PIN gates still apply.

## Supplier accounting

There was no supplier ledger to read in v24. The new independent append-only ledger records supplier name, DEBT/PAYMENT, positive integer IQD, Baghdad business date, optional receipt note and stable UUID. It never inserts synthetic supplier figures from customer balances, changes customer money, or deducts supplier liabilities from customer receivables.

Monthly supplier debts and payments use actual rows within the selected period. Closing payable uses all recorded supplier entries through the inclusive end date, including recorded prior balances. A negative balance is credit in the pharmacy's favor. Future periods are excluded. Supplier detail shows the actual selected-period dated entries. Corrections are explicit opposite entries with a reason, retaining the original record.

The independent `supplier-ledger.db` queue is included in encrypted Android backup/device transfer and replicated by exact UUID/value-confirmed RPC receipts. Existing customer JSON/archive formats are unchanged and do **not** carry the new supplier ledger; supplier data must finish cloud synchronization before replacing/resetting a phone. Cached acknowledged entries missing from a full authenticated server snapshot are requeued by their original UUID, rather than trusted indefinitely or silently dropped. Different values for an existing ID are conflicts, never overwrites.

## Durability and isolation

Shopping SQLite v1→v2 adds trash, ordered mutations and opaque shared-image commit receipts without changing queued v24 operations. Purchase/restore and their outbox writes are atomic transactions. Mutation receipts validate owner, pharmacy, item ID and operation ID; snapshots do not overwrite pending local mutations. Replaying a successful purchase after a restore cannot buy the item again. Restore checks its deletion generation and server clock. Trash never resets weekly. Local photo bytes, Exif metadata, filenames and paths are never uploaded.

Incoming content URIs are copied under the temporary Android grant, decoded with bounded memory, rotated, stripped of Exif, resized and fsynced locally. External file paths are rejected. Intake manifests and item IDs survive recreation; completed image receipts are written atomically with local item insertion, so re-delivery after a purchase cannot re-add it. Batches bind to their first authenticated account/pharmacy. Unfinished manifests can resume after relaunch; a financial form's durable draft is not displaced by sharing.

Public supplier tables have RLS and SELECT-only authenticated grants. Writes use private guarded SECURITY DEFINER functions and public SECURITY INVOKER wrappers; hidden/outside/anonymous users cannot access another pharmacy. Shopping trash and mutation tables have RLS and no client grants. Realtime uses the existing tenant-scoped shopping revision signal. No financial customer tables, auth policies, financial outbox acknowledgements, notification/Telegram/PDF logic or financial Room version changed.

## Verification

Real PostgreSQL 17 fixtures test old shopping compatibility, >1,000-row snapshots, idempotent retry, rollback and both old create/delete races. New fixtures test recover/expiry, server rejection after deadline, token conflicts, retry after restore, v24 permanent delete, offline create/buy/restore, concurrent restores, supplier integer amount/ID conflicts and RLS. Customer financial tables are compared before/after. Fixtures are guarded to localhost disposable databases; no real staff notifications or production financial test writes.

Android tests cover the existing recovery, ledger, Room, auth, notification and report regressions plus SQLite upgrade/reopen, exact mutation receipts, offline queues, photo retention/cleanup, day expiry, two-device HTTP recovery and supplier replication, URI intake and both share targets, native Compose month/supplier details, narrow RTL navigation, list position and one-second hold cancellation/commit. AndroidX FileProvider's cached absolute roots are cleared between Robolectric sandboxes (test-only isolation).

Final CI counts, lint/build/signature and publication evidence will be recorded after verification; this document does not assert a release has passed before those checks complete.

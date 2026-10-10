# 3.3.19.5 — durable local financial writes

Customer creation, debt, payment, edits and deletes commit their financial row, backup journal and independent cloud outbox in the same SQLite transaction. The outbox survives process death, device restart and backup pruning. Production retains WAL and promotes Room's per-connection synchronous setting to FULL for committed-write durability on power loss.

Sync and backup scheduling are best effort after commit. A scheduling or author-metadata failure cannot report a successfully committed operation as a failed save. Startup, network recovery, boot/package replacement and periodic WorkManager jobs retry pending work. Immediate sync jobs use APPEND_OR_REPLACE so a write during an existing worker cannot lose its recovery job.

The outbox retains the exact payload and a revision token. Upload uses the original UUID/operation_id, uploads customers before entries, and only acknowledges the revision actually sent. Remote snapshots and realtime apply pending-row protection inside their own Room transactions. FCM delivery is scheduled before upload acknowledgement; repeated identical upserts do not emit additional financial events in the existing server trigger. Push-token registration errors do not block financial upload.

Migration 1→2→3 preserves existing rows. The old preference queue imports once, with IGNORE semantics for newer durable mutations, before any cloud pull. Backup history is not replayed as financial work. Restore continues to quarantine archived operations and pause editing/upload until explicit reconciliation; financial forms now explain this hold instead of suggesting repeatedly saving the same operation.

Validation covers repository writes with all post-commit schedulers failing; debts/payments/customers and pending uploads after close/reopen; FULL sync after reopen; transactional rollback on queue failure; offline retry; lost server response with stable operation IDs; edits during upload; remote pull/realtime protection; independent backup retention; cancellation; upgrade queue import; and restore quarantine. The server notification trigger and policies are inspected read-only. No production financial records or server schema are changed.

Physical Android force-stop prevents Android background work until the app is opened again. Saved rows and requests remain intact. OS scheduling may defer background upload; reopening the app also retries immediately. Real three-device notification delivery still requires a device smoke test.

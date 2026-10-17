# 3.3.22.23 (versionCode 91)

## Scope and financial invariants

The release adds the requested phone, follow-up, credit-control, reminder and monthly-report workflows. Room remains schema 4. Immutable cloud creation identities, exact outbox revision acknowledgements, recovery verification guards, login, ordinary transaction recording, PDF/Telegram automation and existing notification delivery remain in place. Tests use synthetic local/CI databases only.

## Phone entry and list continuity

Missing-phone Add opens the existing contact picker in the same mounted settings list. A transactional phone-only merge rereads the current customer, preserves all other fields, rejects overwriting a newly supplied different phone, and uses the existing journal/outbox. A successful save removes that row and keeps the visible anchor or adjacent row. Follow-up retains its list state, saved filter/search/sort and return anchor while keeping rows mounted during recalculation.

WhatsApp has no reliable Android delivery receipt. The existing return confirmation is retained: opening WhatsApp alone never marks sent. After the user confirms sending, the action shows a check and permits intentional resend; the durable case fingerprint prevents a new debt cycle inheriting an old confirmation.

## Customer credit controls

The additive migration binds the preverified, unique Raad profile once to an immutable account UUID in a private capability table. Runtime authority never depends on display name or the shared MANAGER role. Authenticated clients can read tenant-scoped metadata; they cannot directly write control/request tables. Guarded private functions validate auth.uid, pharmacy membership and Raad capability behind invoker RPC wrappers with explicit EXECUTE grants and empty search paths.

Raad can block/unblock and retain his privileged debt workflow. Ahmad/Fuad can collect payments on blocked customers and request blocking/unblocking. Requests use stable IDs, existing directed TEAM_MESSAGE notifications with the same stable request/event UUID; the notification table, payload model, constraints and sending functions are unchanged. Raad has confirmed, functional block/unblock decisions inside incoming messages and a persistent pending-request inbox, including requests whose text was already marked read.

Server triggers cover old clients too: a blocked non-Raad account cannot add/resurrect/increase debt, increase opening credit, reduce/delete a receipt, or switch a receipt into debt. Existing identical UPSERT retries, ordinary payments, debt reductions and unblocked customers retain their existing behavior. Per-customer transaction locks serialize block/debt races. No financial row is rewritten when a block is applied.

Cached metadata checks never instantiate the auth/network client during a local save. Realtime plus bounded independent polling refresh metadata. An offline device cannot know a block made elsewhere until communication resumes. Such a conflicting financial operation remains durably queued for Raad review; it is never silently acknowledged or deleted, and blocked failures do not stop unrelated outbox records from uploading.

## Repayment terms

Existing positive day terms retain their original next-day overdue semantics. New terms are 21, 28 and 60 days. Short terms (5/30/60/120 minutes) are opt-in, OFF by default, encoded as negative minutes in the existing delimited notes metadata. Financial IDs, amounts and dates are unchanged. New/old supported terms round-trip through Room and portable backups in this version. Older apps may display new metadata as notes or omit new reminder options; update all devices to use the new options consistently.

Short notifications have their own unique WorkManager names/tag. A separate observer reconciles restored/synced entries when enabled. Workers reread actual FIFO unpaid tranches and skip paid, deleted, edited, disabled or signed-out cases. Stable notification IDs and durable receipts prevent repeat delivery. Disabling cancels only this feature's work. Android battery policies and notification permissions can delay delivery; this is not an exact alarm service.

## Monthly reporting

A distinct chart tab sits between Reports and Settings. Months are generated continuously from October 2026 through the current Baghdad month, across years, with current/previous shortcuts. Custom dates may cross months/years and include both selected days. Totals use actual ledger movement timestamps with Baghdad start-inclusive/end-exclusive boundaries. Opening balances are not invented as monthly movements; debt, receipt, movement counts and net movement are clearly separate from all-time outstanding balance. Reporting is read-only.

## Validation

Pending final Android/Room/Compose, reminder-worker, release lint and signed APK build verification. Isolated PostgreSQL permission, idempotency, inbox/decision and two-connection concurrency tests passed; final results and release links will be recorded before merge/publication.

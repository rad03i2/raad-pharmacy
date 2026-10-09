-- Recipient delivery receipts prevent re-sending to successful devices after a partial failure.
alter table public.notification_events add column if not exists push_delivered_token_ids uuid[] not null default '{}';
alter table public.notification_events add column if not exists push_locked_until timestamptz;

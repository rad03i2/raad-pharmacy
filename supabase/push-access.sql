-- Backend dispatcher permissions. The app still has SELECT only under pharmacy RLS.
grant select, update on public.notification_events to service_role;
grant select, update on public.push_tokens to service_role;

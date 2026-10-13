-- 3.3.22: customer creation announcement for the other pharmacy devices.
-- Deployment required on the production Supabase project; this file is not executed by Android CI.
-- One event per customer ID. Historical/legacy bulk import never emits a new-customer alert.
CREATE OR REPLACE FUNCTION raad_private.publish_new_customer_notice()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = ''
AS $$
DECLARE
    actor uuid := (SELECT auth.uid());
    actor_name text;
BEGIN
    IF actor IS NULL OR NEW.deleted_at IS NOT NULL
       OR NEW.created_at < now() - interval '1 day' THEN
        RETURN NEW;
    END IF;
    SELECT p.display_name INTO actor_name
      FROM public.profiles AS p
     WHERE p.id = actor AND p.pharmacy_id = NEW.pharmacy_id;

    INSERT INTO public.notification_events (
        id, pharmacy_id, actor_user_id, actor_display_name,
        event_type, customer_id, customer_name, amount, balance_after,
        push_server_managed, push_next_attempt_at, created_at
    ) VALUES (
        NEW.id, NEW.pharmacy_id, actor,
        coalesce(nullif(NEW.created_by_name, ''), actor_name, 'مستخدم آخر'),
        'CUSTOMER_CREATED', NEW.id, NEW.name,
        NEW.opening_debt, NEW.opening_debt, true, now(), now()
    ) ON CONFLICT (id) DO NOTHING;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS raad_customer_created_notice ON public.customers;
CREATE TRIGGER raad_customer_created_notice
    AFTER INSERT ON public.customers
    FOR EACH ROW EXECUTE FUNCTION raad_private.publish_new_customer_notice();

REVOKE ALL ON FUNCTION raad_private.publish_new_customer_notice() FROM PUBLIC, anon, authenticated;

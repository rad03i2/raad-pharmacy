-- Raad Pharmacy 3.3.19.6: preserve customer creator and let pharmacy peers manage avatars.
ALTER TABLE public.customers
    ADD COLUMN IF NOT EXISTS created_by_name text NOT NULL DEFAULT '';

-- Every account in the pharmacy is a peer. The client only displays profile names and avatars.
DROP POLICY IF EXISTS profile_read ON public.profiles;
CREATE POLICY profile_read
    ON public.profiles
    FOR SELECT
    TO authenticated
    USING (pharmacy_id = (SELECT raad_private.current_pharmacy()));

-- Permit updating a same-pharmacy profile row; the trigger below limits peer edits to avatar_path.
DROP POLICY IF EXISTS profile_pharmacy_avatar_update ON public.profiles;
CREATE POLICY profile_pharmacy_avatar_update
    ON public.profiles
    FOR UPDATE
    TO authenticated
    USING (pharmacy_id = (SELECT raad_private.current_pharmacy()))
    WITH CHECK (pharmacy_id = (SELECT raad_private.current_pharmacy()));

CREATE OR REPLACE FUNCTION raad_private.guard_profile_peer_update()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = ''
AS $function$
BEGIN
    IF OLD.id IS DISTINCT FROM (SELECT auth.uid()) THEN
        IF NEW.id IS DISTINCT FROM OLD.id
            OR NEW.pharmacy_id IS DISTINCT FROM OLD.pharmacy_id
            OR NEW.role IS DISTINCT FROM OLD.role
            OR NEW.display_name IS DISTINCT FROM OLD.display_name
            OR NEW.created_at IS DISTINCT FROM OLD.created_at
            OR NEW.updated_at IS DISTINCT FROM OLD.updated_at
            OR NEW.deleted_at IS DISTINCT FROM OLD.deleted_at
            OR NEW.sync_version IS DISTINCT FROM OLD.sync_version
            OR NEW.is_hidden IS DISTINCT FROM OLD.is_hidden
        THEN
            RAISE EXCEPTION 'A pharmacy member may only change another member avatar'
                USING ERRCODE = '42501';
        END IF;
    END IF;
    RETURN NEW;
END;
$function$;

DROP TRIGGER IF EXISTS profiles_guard_peer_update ON public.profiles;
CREATE TRIGGER profiles_guard_peer_update
    BEFORE UPDATE ON public.profiles
    FOR EACH ROW
    EXECUTE FUNCTION raad_private.guard_profile_peer_update();

REVOKE ALL ON FUNCTION raad_private.guard_profile_peer_update() FROM PUBLIC, anon, authenticated;

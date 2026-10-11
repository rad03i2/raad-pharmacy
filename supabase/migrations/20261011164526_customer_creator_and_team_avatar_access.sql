ALTER TABLE public.customers
    ADD COLUMN IF NOT EXISTS created_by_name text NOT NULL DEFAULT '';

DROP POLICY IF EXISTS profile_read ON public.profiles;
CREATE POLICY profile_read
    ON public.profiles
    FOR SELECT
    TO authenticated
    USING (
        pharmacy_id = (SELECT raad_private.current_pharmacy())
        AND (id = (SELECT auth.uid()) OR NOT is_hidden)
    );

DROP POLICY IF EXISTS profile_self_update ON public.profiles;
CREATE POLICY profile_team_avatar_update
    ON public.profiles
    FOR UPDATE
    TO authenticated
    USING (pharmacy_id = (SELECT raad_private.current_pharmacy()))
    WITH CHECK (pharmacy_id = (SELECT raad_private.current_pharmacy()));

-- Keep the cross-user update narrowly scoped to avatar metadata only.
REVOKE UPDATE ON TABLE public.profiles FROM authenticated;
GRANT UPDATE (avatar_path, updated_at) ON TABLE public.profiles TO authenticated;

-- Keep hidden/system profiles private while treating visible pharmacy members as peers.
DROP POLICY IF EXISTS profile_read ON public.profiles;
CREATE POLICY profile_read
    ON public.profiles
    FOR SELECT
    TO authenticated
    USING (
        pharmacy_id = (SELECT raad_private.current_pharmacy())
        AND (NOT is_hidden OR id = (SELECT auth.uid()))
    );

-- Remove older duplicate policy names and retain one constrained same-pharmacy avatar policy.
DROP POLICY IF EXISTS profile_team_avatar_update ON public.profiles;
DROP POLICY IF EXISTS profile_pharmacy_avatar_update ON public.profiles;
CREATE POLICY profile_team_avatar_update
    ON public.profiles
    FOR UPDATE
    TO authenticated
    USING (
        pharmacy_id = (SELECT raad_private.current_pharmacy())
        AND (NOT is_hidden OR id = (SELECT auth.uid()))
    )
    WITH CHECK (
        pharmacy_id = (SELECT raad_private.current_pharmacy())
        AND (NOT is_hidden OR id = (SELECT auth.uid()))
    );
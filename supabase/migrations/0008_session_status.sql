-- Make "Revoke" in the ops console actually reach the television.
--
-- Run AFTER 0005_release_device.sql. Idempotent: safe to re-run.
--
-- Revoke has always done the right thing server side - it marks every session
-- row for the device `revoked = true` - and the television has never once
-- noticed. Nothing re-checks the session after pairing: `isPaired` reads a token
-- out of local storage and that token is cached for ten years. So a revoked
-- television keeps playing, keeps writing progress, and keeps showing the school
-- it was supposedly removed from, until somebody clears its data by hand.
--
-- This is the same defect as the unpair button fixed in 0005, seen from the
-- other end: one was the device failing to tell the server, this is the server
-- having no way to tell the device.
--
-- The function is deliberately the narrowest thing that answers the question.
-- It takes the token as well as the hardware id, for the reason release_device
-- does: the pairing code is printed on the screen of every classroom television,
-- so anything keyed on the hardware id alone can be asked about someone else's
-- set by anyone who can read one.

create or replace function public.session_status(
  p_hardware_uuid text,
  p_token         text
) returns boolean
language sql
security definer
set search_path = public
as $fn$
  select exists (
    select 1
      from devices  d
      join sessions s on s.device_id = d.id
     where d.hardware_uuid = p_hardware_uuid
       and s.token         = p_token
       and s.revoked       = false
       and s.expires_at    > now()
  );
$fn$;

grant execute on function public.session_status(text, text) to anon, authenticated;

notify pgrst, 'reload schema';

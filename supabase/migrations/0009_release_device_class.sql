-- Teach "Unpair this television" about classes.
--
-- Run LAST, after both 0005_release_device.sql and 0007_levels_and_classes.sql.
-- Idempotent: safe to re-run.
--
-- 0005 was written before `devices.class_id` existed, so it clears claimed_at,
-- school_id and teacher_id and leaves the class behind. An unpaired television
-- would then sit on the pairing screen still pointing at Nursery A, and the next
-- school to activate it would inherit that - a set that quietly opens on somebody
-- else's grade, which is worse than an obvious failure because nobody looks for
-- it.
--
-- This is a separate migration rather than an edit to 0005 on purpose. The two
-- files are run by hand in numeric order, and if the class-clearing version lived
-- in 0005 then running 0005 after 0007 - a perfectly reasonable thing to do when
-- re-applying the set - would silently reinstate the old behaviour. Last file
-- wins, so the last file is where this belongs.

create or replace function public.release_device(
  p_hardware_uuid text,
  p_token         text
) returns boolean
language plpgsql
security definer
set search_path = public
as $fn$
declare
  v_device_id uuid;
begin
  -- The token is required, not decorative. The pairing code is printed on the
  -- screen of every classroom television, so a release keyed on the hardware id
  -- alone could be fired at someone else's set by anyone who can read one.
  select d.id into v_device_id
    from devices  d
    join sessions s on s.device_id = d.id
   where d.hardware_uuid = p_hardware_uuid
     and s.token         = p_token
     and s.revoked       = false
   limit 1;

  if v_device_id is null then
    return false;
  end if;

  update sessions
     set revoked = true
   where device_id = v_device_id
     and revoked = false;

  update devices
     set claimed_at = null,
         school_id  = null,
         teacher_id = null,
         class_id   = null
   where id = v_device_id;

  return true;
end;
$fn$;

grant execute on function public.release_device(text, text) to anon, authenticated;

notify pgrst, 'reload schema';

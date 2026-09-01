-- JSP ECED Learning — let a television unpair itself
--
-- WHY THIS EXISTS
--
-- "Unpair this television" in Settings called DeviceRepository.factoryReset(),
-- which clears the local EncryptedSharedPreferences and nothing else. The
-- device's `sessions` row stayed on the server with revoked = false.
--
-- So the sequence a teacher actually experienced was:
--
--   1. Unpair -> local token wiped -> MainActivity restarts -> PairingFragment
--   2. PairingFragment calls register_device with the SAME hardware_uuid
--      (factoryReset deliberately keeps KEY_UUID, so the identity is stable)
--   3. Two seconds later its poll finds the session that was never revoked
--   4. The television silently pairs itself straight back in
--
-- From the front of the classroom that is indistinguishable from the button not
-- working. The television could not be logged out at all.
--
-- Unpairing has to revoke server side, and the device has no write access to
-- `sessions` by design, so it needs an RPC.
--
-- WHY IT TAKES THE TOKEN
--
-- The obvious signature, release_device(hardware_uuid), would let any anonymous
-- caller who learns a UUID unpair someone else's television - and the pairing
-- code, which is the first eight characters of that UUID, is printed on screen
-- in every classroom this ships to. Requiring the caller to also present the
-- live session token means only the television holding that token can release
-- it, which is exactly the authority we want it to have over itself.
--
-- Run after 0004_teachers.sql. Safe to re-run.

create or replace function public.release_device(
  p_hardware_uuid text,
  p_token         text
) returns boolean
language plpgsql
security definer
set search_path = public
as $$
declare
  v_device_id uuid;
begin
  -- One lookup that proves both facts at once: this hardware exists, and the
  -- caller holds a live session for it.
  select d.id into v_device_id
  from devices d
  join sessions s on s.device_id = d.id
  where d.hardware_uuid = p_hardware_uuid
    and s.token = p_token
    and s.revoked = false
  limit 1;

  if v_device_id is null then
    return false;
  end if;

  update sessions
     set revoked = true
   where device_id = v_device_id
     and revoked = false;

  -- Mirrors what the ops console's revoke action does, so a television released
  -- from the remote and one released at the set behave identically afterwards.
  update devices
     set claimed_at = null,
         school_id  = null,
         teacher_id = null
   where id = v_device_id;

  return true;
end;
$$;

-- Callable by the anon role: the token in the argument is the credential, not
-- the connection. The function is SECURITY DEFINER, so it writes through RLS.
grant execute on function public.release_device(text, text) to anon, authenticated;

-- PostgREST caches the function signature list. Without this the first call
-- after deploying returns PGRST202 "function not found" until the pooler
-- happens to restart.
notify pgrst, 'reload schema';

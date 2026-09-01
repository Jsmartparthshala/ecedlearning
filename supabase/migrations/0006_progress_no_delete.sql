-- Close the fleet-wide DELETE hole on progress and quiz_attempts.
--
-- 0002 granted these to anon as a single `for all` policy:
--
--   create policy progress_device_all on progress
--     for all to anon, authenticated
--     using (device_id is not null) with check (device_id is not null);
--
-- `for all` includes DELETE. The anon key ships inside the APK - that is by
-- design and it is safe on its own, because RLS is supposed to be the thing
-- standing behind it. Here it was not: anyone who unzips a copy of the app can
-- read the key and send
--
--   DELETE /rest/v1/progress?device_id=not.is.null
--
-- which erases the watch history of every school on the deployment in one
-- request. Nothing in the app has ever issued a DELETE against either table, so
-- no client depends on the capability.
--
-- Split the policy by verb instead. SELECT / INSERT / UPDATE keep working
-- exactly as before - upsert needs INSERT and UPDATE, the continue-watching row
-- needs SELECT - and DELETE simply has no policy, so RLS denies it.
--
-- WHAT THIS DOES NOT FIX: reads and writes are still fleet-wide rather than
-- scoped to the calling device, because no device can currently prove which
-- device it is. The 10-year session token is passed as an RPC *parameter* and
-- never reaches the Authorization header, so Postgres only ever sees the role
-- `anon` and no policy can say "this row is yours". Scoping needs a
-- Supabase-signed JWT carrying a device_id claim, minted at activation. That is
-- a real piece of work; this migration is the part that stops the damage being
-- irreversible, and it needs no client change at all.

-- ------------------------------------------------------------ progress

drop policy if exists progress_device_all on progress;

create policy progress_device_read on progress
  for select to anon, authenticated
  using (device_id is not null);

create policy progress_device_insert on progress
  for insert to anon, authenticated
  with check (device_id is not null);

create policy progress_device_update on progress
  for update to anon, authenticated
  using (device_id is not null)
  with check (device_id is not null);

-- ------------------------------------------------------- quiz_attempts

drop policy if exists attempts_device_all on quiz_attempts;

create policy attempts_device_read on quiz_attempts
  for select to anon, authenticated
  using (device_id is not null);

create policy attempts_device_insert on quiz_attempts
  for insert to anon, authenticated
  with check (device_id is not null);

create policy attempts_device_update on quiz_attempts
  for update to anon, authenticated
  using (device_id is not null)
  with check (device_id is not null);

-- The authenticated-profile policies from 0002 (progress_own, attempts_own) are
-- left alone. Those are scoped by auth.uid() and are the mobile app's path.

notify pgrst, 'reload schema';

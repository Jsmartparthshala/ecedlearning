-- JSP ECED Learning — Row Level Security
-- Run AFTER 0001_schema.sql. Idempotent.
--
-- Threat model for the demo: the TV ships with the anon key baked in (unavoidable
-- for a no-login device). So anon must be able to READ the catalog and to
-- self-register a device, and nothing else. Everything privileged goes through the
-- ops console using the service_role key, which never leaves your laptop/Netlify env.

alter table schools        enable row level security;
alter table devices        enable row level security;
alter table sessions       enable row level security;
alter table subjects       enable row level security;
alter table units          enable row level security;
alter table lessons        enable row level security;
alter table profiles       enable row level security;
alter table progress       enable row level security;
alter table quizzes        enable row level security;
alter table quiz_questions enable row level security;
alter table quiz_options   enable row level security;
alter table quiz_attempts  enable row level security;
alter table app_release    enable row level security;

-- ------------------------------------------------- catalog: world-readable

drop policy if exists catalog_read_subjects on subjects;
create policy catalog_read_subjects on subjects
  for select to anon, authenticated using (true);

drop policy if exists catalog_read_units on units;
create policy catalog_read_units on units
  for select to anon, authenticated using (true);

drop policy if exists catalog_read_lessons on lessons;
create policy catalog_read_lessons on lessons
  for select to anon, authenticated using (true);

drop policy if exists catalog_read_quizzes on quizzes;
create policy catalog_read_quizzes on quizzes
  for select to anon, authenticated using (true);

drop policy if exists catalog_read_questions on quiz_questions;
create policy catalog_read_questions on quiz_questions
  for select to anon, authenticated using (true);

-- NOTE: is_correct lives on quiz_options and anon can read it. For a demo that is
-- fine. Before any real deployment, move grading server-side into an RPC and drop
-- this policy — otherwise the answers are one API call away.
drop policy if exists catalog_read_options on quiz_options;
create policy catalog_read_options on quiz_options
  for select to anon, authenticated using (true);

drop policy if exists release_read on app_release;
create policy release_read on app_release
  for select to anon, authenticated using (true);

drop policy if exists schools_read on schools;
create policy schools_read on schools
  for select to anon, authenticated using (true);

-- ------------------------------------------- devices: register via RPC only

-- The TV must be able to (a) announce itself so it can be claimed and
-- (b) heartbeat. It must NOT be able to set school_id or claimed_at - that would
-- let any device self-provision and defeat reverse provisioning entirely.
--
-- Doing this with table grants does not work. PostgREST implements upsert as
-- INSERT ... ON CONFLICT DO UPDATE, which needs UPDATE rights on every column it
-- touches (hardware_uuid included). A column-limited grant therefore fails with
-- "42501 permission denied for table devices" - verified end to end, not guessed.
--
-- So the device gets NO direct write access. Registration goes through a
-- SECURITY DEFINER function that can only ever touch the three safe columns.
-- This is both simpler and strictly tighter than the grant approach.

create or replace function public.register_device(
  p_hardware_uuid text,
  p_app_version   text default null
)
returns setof devices
language plpgsql
security definer
set search_path = public
as $$
begin
  return query
  insert into devices (hardware_uuid, app_version, last_seen)
  values (p_hardware_uuid, p_app_version, now())
  on conflict (hardware_uuid) do update
    set app_version = coalesce(excluded.app_version, devices.app_version),
        last_seen   = now()
  returning *;
end;
$$;

revoke all on function public.register_device(text, text) from public;
grant execute on function public.register_device(text, text) to anon, authenticated;

-- No direct writes to devices. At all.
revoke insert, update, delete on devices from anon, authenticated;

drop policy if exists devices_self_register on devices;
drop policy if exists devices_heartbeat    on devices;

-- Reading devices stays open so the ops console (and the TV, to find its own row)
-- can list them. Nothing sensitive lives on this table.
drop policy if exists devices_read on devices;
create policy devices_read on devices
  for select to anon, authenticated using (true);

-- Sessions.
--
-- KNOWN DEMO-ONLY WEAKNESS: this lets any anon client read EVERY unrevoked
-- session row, so one TV could read another school's token. That is acceptable
-- for a single-device demo and unacceptable in the field.
--
-- The fix is written and ready in 0003_hardening.sql: a SECURITY DEFINER function
-- that takes a hardware_uuid and returns only that device's session, with this
-- blanket policy dropped. It is NOT applied by default because it also requires
-- the matching change in DeviceRepository.fetchSession(), and swapping the
-- pairing mechanism the night before a demo is a bad trade.
--
-- Apply 004 straight after the demo.
drop policy if exists sessions_read on sessions;
create policy sessions_read on sessions
  for select to anon, authenticated using (revoked = false);

-- ------------------------------------------------------------ progress

drop policy if exists progress_device_all on progress;
create policy progress_device_all on progress
  for all to anon, authenticated
  using (device_id is not null)
  with check (device_id is not null);

drop policy if exists progress_own on progress;
create policy progress_own on progress
  for all to authenticated
  using (profile_id = auth.uid())
  with check (profile_id = auth.uid());

drop policy if exists attempts_device_all on quiz_attempts;
create policy attempts_device_all on quiz_attempts
  for all to anon, authenticated
  using (device_id is not null)
  with check (device_id is not null);

drop policy if exists attempts_own on quiz_attempts;
create policy attempts_own on quiz_attempts
  for all to authenticated
  using (profile_id = auth.uid())
  with check (profile_id = auth.uid());

-- ------------------------------------------------------------ profiles

drop policy if exists profiles_own_read on profiles;
create policy profiles_own_read on profiles
  for select to authenticated using (id = auth.uid());

drop policy if exists profiles_own_write on profiles;
create policy profiles_own_write on profiles
  for update to authenticated
  using (id = auth.uid()) with check (id = auth.uid());

drop policy if exists profiles_own_insert on profiles;
create policy profiles_own_insert on profiles
  for insert to authenticated with check (id = auth.uid());

-- Auto-create a profile row on signup.
create or replace function public.handle_new_user()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  insert into public.profiles (id, display_name)
  values (new.id, coalesce(new.raw_user_meta_data->>'display_name', new.email))
  on conflict (id) do nothing;
  return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();

-- --------------------------------------------------------------- realtime

-- The pairing flow depends on these being in the realtime publication.
--
-- Supabase creates `supabase_realtime` for you, but do not assume it: if this
-- statement aborts, everything after it is skipped and the file leaves RLS only
-- partly applied, which is worse than not running it at all. Create it when
-- missing, and treat "already added" as success.
do $$
begin
  if not exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    execute 'create publication supabase_realtime';
  end if;

  begin
    execute 'alter publication supabase_realtime add table sessions';
  exception when duplicate_object then null;
  end;

  begin
    execute 'alter publication supabase_realtime add table devices';
  exception when duplicate_object then null;
  end;
end $$;

-- --------------------------------------------------------- schema cache

-- PostgREST caches the function signatures it exposes. A newly created RPC is
-- invisible until that cache reloads, and the error is confusing:
--   PGRST202 "Could not find the function public.register_device(...)"
-- The Supabase dashboard usually reloads automatically, but if the TV logs that
-- error after you run this file, run the line below (or restart the API from
-- Project Settings) and try again.
notify pgrst, 'reload schema';

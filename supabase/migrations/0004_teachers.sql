-- JSP ECED Learning — teachers, and which television belongs to which teacher
-- Run AFTER 0002_security.sql. Idempotent: safe to re-run.
--
-- Why a new table rather than reusing `profiles`: profiles is keyed to
-- auth.users, and these teachers have no login and never will. A school's
-- office staff type the names in once from the ops console. Forcing an auth
-- account per teacher would mean inventing credentials for people who are never
-- going to sign in to anything.

create table if not exists teachers (
  id          uuid primary key default gen_random_uuid(),
  school_id   uuid not null references schools(id) on delete cascade,
  name        text not null,
  -- Free text on purpose: "Nursery A", "LKG class teacher", "ECD facilitator".
  -- Every school words this differently and a fixed enum would fight them.
  role        text,
  created_at  timestamptz not null default now()
);

-- Deliberately NO phone or address column. This table is readable with the anon
-- key that ships inside the APK (see the policy below), so anything stored here
-- is effectively public. Names and class labels are the minimum the television
-- needs to show; personal contact details are not, and should not live here.

create index if not exists teachers_school on teachers(school_id);

-- One teacher per television. A device with a null teacher is still perfectly
-- valid - it belongs to the school and shows the school name, exactly as before
-- this migration existed. on delete set null so removing a teacher unassigns
-- their televisions instead of deleting them.
alter table devices
  add column if not exists teacher_id uuid references teachers(id) on delete set null;

create index if not exists devices_teacher on devices(teacher_id);

-- ------------------------------------------------------------------ security

alter table teachers enable row level security;

-- The television reads its own teacher's name to put on the profile screen, and
-- it has only the anon key to do it with.
--
-- KNOWN LIMITATION, same shape as the existing schools_read policy: this lets
-- any anon client list every teacher in every school, not just its own. For a
-- demo with names and class labels that is acceptable. The fix is the same one
-- 0003_hardening.sql applies to sessions - a SECURITY DEFINER function keyed on
-- the device's hardware_uuid - and it should land at the same time.
drop policy if exists teachers_read on teachers;
create policy teachers_read on teachers
  for select to anon, authenticated using (true);

-- Writes are ops-console only, which uses the service_role key and bypasses RLS.
-- No anon policy for insert/update/delete means anon cannot do any of them.
revoke insert, update, delete on teachers from anon, authenticated;

-- devices.teacher_id must not be settable by the device itself, for the same
-- reason it cannot set its own school_id: that would defeat reverse
-- provisioning. 0002 already revoked all direct writes on devices and routes
-- registration through register_device(), which touches only hardware_uuid,
-- app_version and last_seen. The new column is therefore already unwritable by
-- anon - no further grant changes needed here.

notify pgrst, 'reload schema';

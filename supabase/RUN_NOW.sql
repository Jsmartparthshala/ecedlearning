-- ===========================================================================
-- RUN_NOW.sql - everything the database is missing, in the order it must run.
--
-- Built 3 September 2026. This is the six unrun migrations concatenated, not a
-- new migration: every one of them is still the file of record in migrations/,
-- and this is only here so the whole thing goes into the Supabase SQL editor
-- in one paste.
--
-- All six are safe to run twice. Every function is CREATE OR REPLACE, every
-- table is CREATE TABLE IF NOT EXISTS, every column is ADD COLUMN IF NOT
-- EXISTS. Running this against a database that already has some of them
-- changes nothing about the ones it has.
--
-- The order is not arbitrary: 0009 replaces the release_device that 0005
-- creates, so 0005 has to go first even though 0009 is the one that matters.
--
-- NOT INCLUDED, DELIBERATELY: 0003_hardening.sql. It needs a matching change
-- in the Android app that is not written yet, and running it before that ships
-- will lock every television out of the catalogue.
-- ===========================================================================


-- --------------------------------------------------------------------------
-- WHAT IS ALREADY THERE - run this first on its own and read the two results.
-- Nothing below is destructive, but it is worth knowing what you are adding.
-- --------------------------------------------------------------------------
-- select p.proname as function_that_exists
--   from pg_proc p join pg_namespace n on n.oid = p.pronamespace
--  where n.nspname = 'public'
--    and p.proname in ('register_device', 'release_device', 'session_status');
--
-- select table_name from information_schema.tables
--  where table_schema = 'public' and table_name in ('app_documents', 'ops_audit');
--
-- select column_name from information_schema.columns
--  where table_schema = 'public' and table_name = 'schools'
--    and column_name in ('lat', 'lon');





-- --------------------------------------------------------------------------
-- 0005_release_device.sql
-- --------------------------------------------------------------------------

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



-- --------------------------------------------------------------------------
-- 0008_session_status.sql
-- --------------------------------------------------------------------------

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



-- --------------------------------------------------------------------------
-- 0009_release_device_class.sql
-- --------------------------------------------------------------------------

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



-- --------------------------------------------------------------------------
-- 0010_app_documents.sql
-- --------------------------------------------------------------------------

-- Documents the app displays but does not compile: privacy policy, terms,
-- data handling, open-source notices.
--
-- These have to be editable without shipping an APK. A privacy policy that can
-- only change when forty sideloaded televisions each accept an update is a
-- policy that is wrong for however long that takes, and the one document class
-- where being out of date is a legal problem rather than a cosmetic one.
--
-- Deliberately documents, not strings. A general remote-string table sounds like
-- the same idea and is not: it makes every label in the product untestable,
-- unversioned and untranslatable, and the failure mode is a television showing
-- an empty button. A document is a whole unit with a title, a body and a
-- version, it renders on a screen of its own, and the app ships a bundled copy
-- to fall back to - so the worst case is a school reading last month's wording
-- rather than reading nothing.
--
-- Seeded with placeholders on purpose, and they say so in their own body text.
-- Nobody should be able to open this screen on a demo television and mistake
-- filler for a policy Jagdamba has actually adopted.

create table if not exists app_documents (
  slug          text primary key,
  kind          text not null default 'legal'
                  check (kind in ('legal', 'help', 'notice')),

  title_en      text not null,
  title_np      text,

  -- Plain text with blank-line paragraphs and ALL-CAPS headings. Not HTML and
  -- not markdown: this renders into a TextView on a box with a Mali-450, and a
  -- renderer is a dependency, an attack surface and a thing to get wrong on a
  -- screen nobody is watching.
  body_en       text not null,
  body_np       text,

  -- What the school agreed to, so a change is visible as a change. Free text
  -- rather than a number because legal versions are dated, not incremented.
  version       text not null default 'draft',
  effective_on  date,

  -- Unpublished rows are drafts. The television never sees them, so a policy can
  -- be written across several sittings in the console without a half-finished
  -- paragraph appearing in a classroom.
  published     boolean not null default false,

  sort_order    int not null default 0,
  updated_at    timestamptz not null default now()
);

create index if not exists app_documents_kind_idx
  on app_documents (kind, sort_order);

alter table app_documents enable row level security;

-- Readable by the televisions, and only what is published. Writes have no
-- policy at all, which under RLS means nobody but the service role can make
-- one - the same shape as every other operator-owned table here: the ops
-- console holds that key server-side and the anon key in the APK cannot write.
drop policy if exists app_documents_read on app_documents;
create policy app_documents_read on app_documents
  for select to anon, authenticated using (published = true);

-- ------------------------------------------------------------ placeholders
--
-- Bodies are written as placeholders that identify themselves. The headings are
-- real - they are the sections these documents have to contain for a product
-- that runs in schools and touches children's data - so replacing the filler is
-- a matter of writing under each one rather than starting from a blank page.

insert into app_documents (slug, kind, title_en, title_np, version, sort_order, published, body_en)
values
  ('privacy', 'legal', 'Privacy Policy', 'गोपनीयता नीति', 'placeholder', 10, true,
$doc$PLACEHOLDER — NOT YET A POLICY

This text is a placeholder shipped so the screen exists and can be found. It
has not been reviewed by anyone and Jagdamba Smart Pathshala has not adopted
it. Replace it in the ops console before this television is used in a school.

WHAT THIS APPLICATION COLLECTS

Describe here what the television records. As built, that is: a hardware
identifier, the school and class it has been assigned to, which lessons have
been watched and how far, and the application version. Say plainly that no
student names, photographs, audio or video are collected by the television.

WHO CAN SEE IT

Describe who has access through the operations console and on what basis.

HOW LONG IT IS KEPT

State the retention period for watch history and for device records.

CHILDREN

State the position on data belonging to children, and name the guardian or
school authority who consents on their behalf.

CONTACT

Name a person and an address a school can write to.$doc$),

  ('terms', 'legal', 'Terms of Use', 'प्रयोगका सर्तहरू', 'placeholder', 20, true,
$doc$PLACEHOLDER — NOT YET AN AGREEMENT

This text is a placeholder shipped so the screen exists and can be found. It
has not been reviewed by anyone and Jagdamba Smart Pathshala has not adopted
it. Replace it in the ops console before this television is used in a school.

WHO THIS AGREEMENT IS WITH

Name the parties: the school, and the operator of this service.

WHAT IS PROVIDED

Describe the service: a television application delivering curriculum video, a
catalogue maintained centrally, and updates delivered over the network.

WHAT THE SCHOOL AGREES TO

Set out the school's obligations — supervision of use, care of the hardware,
and not redistributing the video.

AVAILABILITY

State what is and is not promised about uptime, and that lessons require a
working internet connection.

CONTENT

State where the curriculum content comes from and who holds rights in it.

ENDING THIS AGREEMENT

Describe how either side ends it and what happens to the device and the data.$doc$),

  ('data', 'legal', 'Student Data and Safeguarding', 'विद्यार्थी डेटा', 'placeholder', 30, true,
$doc$PLACEHOLDER — NOT YET A POLICY

This text is a placeholder shipped so the screen exists and can be found.
Replace it in the ops console before this television is used in a school.

WHY THIS IS SEPARATE

This product runs in classrooms of young children. The privacy policy covers
what is collected; this document is for the school and covers who is
accountable for it.

WHAT IS NEVER COLLECTED

State clearly what the television does not do. As built it has no camera, no
microphone access, and no student login — there is nothing on this device that
identifies an individual child.

WHAT IS ASSIGNED, NOT ENTERED

Explain reverse provisioning: the television is assigned to a school, a class
and a teacher from the central office. Nobody signs in on the television and no
password is ever typed into it with a remote control.

WHO TO RAISE A CONCERN WITH

Name a person and how to reach them.$doc$),

  ('licences', 'legal', 'Open Source Notices', 'खुला स्रोत सूचना', 'placeholder', 40, true,
$doc$PLACEHOLDER — INCOMPLETE

This application is built on open source software whose licences require that
their notices be reproduced. This list is not yet complete and must be
finished before distribution.

Generate the real list from the build rather than writing it by hand.

AndroidX (androidx.core, appcompat, leanback, recyclerview, lifecycle, work)
    Apache License 2.0

AndroidX Media3 / ExoPlayer
    Apache License 2.0

Kotlin standard library and kotlinx.coroutines
    Apache License 2.0

Ktor
    Apache License 2.0

supabase-kt
    MIT License

Full licence texts must accompany these notices.$doc$)
on conflict (slug) do nothing;

notify pgrst, 'reload schema';



-- --------------------------------------------------------------------------
-- 0011_ops_audit.sql
-- --------------------------------------------------------------------------

-- JSP ECED Learning — an audit trail for the ops console
--
-- Safe to run at any time. It adds one table and touches nothing that exists,
-- so the console and the televisions behave identically before and after.
--
-- Why this exists now
--
-- The console authenticates with a single shared passcode (OPS_PASSCODE) and
-- acts through the service_role key, which bypasses row level security
-- entirely. That was proportionate while the console could only activate and
-- revoke televisions: every one of those actions is visible in the devices
-- table afterwards, and reversible by hand.
--
-- It stops being proportionate the moment the console can rewrite lesson titles
-- and publish legal text to every classroom. Those actions leave no trace in
-- the row they change - a title is simply a different title afterwards - and
-- there is no per-operator identity anywhere in the product to ask. So when a
-- title is wrong on Monday there is currently no way to learn what it was on
-- Friday, or who changed it.
--
-- This does not add identity. A shared passcode cannot tell two operators
-- apart, and pretending otherwise would be worse than not logging. What it adds
-- is the before value, the after value and the time, which is what actually
-- gets a mistake undone.

create table if not exists ops_audit (
  id          bigserial primary key,

  -- The console action name, exactly as the function dispatches it:
  -- 'activate', 'revoke', 'rename-lesson', 'save-document', 'set-expiry'.
  action      text not null,

  -- What was acted on. Free text rather than a foreign key on purpose: this
  -- table has to outlive the row it describes, and half the value of an audit
  -- entry is being able to read it after the thing is gone.
  target      text,

  -- Enough of the change to undo it. `before` is what the row held, `after` is
  -- what it holds now. Both jsonb, both nullable - a create has no before and a
  -- delete has no after.
  before      jsonb,
  after       jsonb,

  -- Everything the request can honestly say about who did it, which today is
  -- the client IP and the browser's user agent. Not identity. A label on a
  -- shared credential, useful for telling an office laptop apart from a phone
  -- on the far side of the country.
  actor_hint  text,

  at          timestamptz not null default now()
);

create index if not exists ops_audit_at_idx on ops_audit (at desc);
create index if not exists ops_audit_action_idx on ops_audit (action, at desc);

-- No policy at all, deliberately. Under RLS that means no anon or authenticated
-- caller can read or write this table by any route - only the service_role key
-- held by the Netlify function, which is the only thing that should ever write
-- an audit row and the only thing that should ever read one back.
alter table ops_audit enable row level security;

revoke all on ops_audit from anon, authenticated;
revoke all on sequence ops_audit_id_seq from anon, authenticated;

notify pgrst, 'reload schema';



-- --------------------------------------------------------------------------
-- 0012_school_location.sql
-- --------------------------------------------------------------------------

-- JSP ECED Learning — coordinates on a school, for the ops console map
--
-- Safe to run at any time. It adds two nullable columns and touches nothing
-- that exists, so the televisions behave identically before and after, and so
-- does every part of the console except the map.
--
-- Safe to run *late*, too, which is the point of how the console reads it. The
-- fleet query asks for lat and lon optimistically and drops them if they are
-- not there yet, the same way it already handles the class columns from 0007.
-- So the ordinary rule for this project - migration first, then push, because
-- Netlify deploys on push - is not load-bearing here. Either order works.
--
-- Why this exists
--
-- The map places a school by reading the municipality written on it and looking
-- that name up in a table of district centroids. That gets it into the right
-- district, which over a country 800km wide is genuinely useful, and no nearer:
-- the dot is the middle of a district, not the school.
--
-- Usually that is enough. Where it is not - two schools in one district that
-- the office needs to tell apart on sight, or a district shaped like Gorkha
-- where the middle is nowhere near anybody - somebody stands at the school gate
-- and reads a coordinate off their phone. This is where that goes, and the map
-- prefers it over its own guess whenever it is present.
--
-- Both columns stay nullable and both stay empty for most schools. A null here
-- is not missing data to be chased; it is the normal state, and it means "the
-- district guess is fine for this one".

alter table schools add column if not exists lat double precision;
alter table schools add column if not exists lon double precision;

-- Nepal, generously bounded.
--
-- This is not an opinion about a border. It catches the two mistakes a person
-- actually makes typing coordinates off a phone - swapping the pair, or losing
-- a minus sign - because either one puts the school somewhere in the Indian
-- Ocean, and a single point out there would stretch the map's own bounds until
-- the country was drawn as a smudge in one corner.
--
-- The console checks the same range before it writes, and says so in words. The
-- constraint is here because the console is not the only thing that can ever
-- write to this table, and a check that lives only in the client is a check
-- that is one psql session away from not existing.
--
-- Both-or-neither, because half a coordinate places nothing and would sit in
-- the table looking like data.
alter table schools drop constraint if exists schools_location_sane;
alter table schools add constraint schools_location_sane check (
  (lat is null and lon is null)
  or (lat between 26 and 31 and lon between 79.5 and 89)
);

comment on column schools.lat is
  'Latitude of the school itself, if somebody has pinned it. Null is normal and '
  'means the ops console map should place this school from its municipality.';
comment on column schools.lon is
  'Longitude of the school itself. Null is normal - see schools.lat.';

-- No index. There are tens of schools, the console reads all of them in one
-- select to draw the map, and nothing anywhere queries by coordinate.


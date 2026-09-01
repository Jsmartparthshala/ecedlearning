-- The grade ladder, and classes.
--
-- Run AFTER 0004_teachers.sql. Idempotent: safe to re-run.
--
-- Until now the product was one grade wide: `subjects` was implicitly ECED and
-- nothing recorded which grade anything belonged to. This adds the CDC ladder as
-- data - ECED, Basic 1-8, Secondary 9-10, and 11-12 - without changing a single
-- thing about how ECED already behaves. Every existing subject is backfilled to
-- the ECED level, so a television that has not been touched shows exactly what
-- it showed yesterday.
--
-- `classes` is the piece the CRM actually needs. `teachers.role` is free text
-- ("Nursery A", "ECD facilitator") and correctly so - it is a human label, not a
-- key - but a console where an admin adds classes cannot be built on free text.
-- A class is a real row: a school, a grade, and a label. The class carries the
-- grade, the device points at the class, and that one relationship covers the
-- console CRM and the per-device grade at the same time.

-- ------------------------------------------------------------------- levels

create table if not exists levels (
  id          uuid primary key default gen_random_uuid(),
  slug        text not null unique,
  name_en     text not null,
  name_np     text,
  -- Coarse grouping for the television's landing screen. Thirteen tiles in one
  -- row is a scrolling chore on a D-pad; four stages is one press.
  stage       text not null,          -- eced | basic | secondary | higher
  sort_order  int  not null default 0
);

insert into levels (slug, name_en, name_np, stage, sort_order) values
  ('eced',     'ECED',     'प्रारम्भिक बालशिक्षा', 'eced',      0),
  ('basic-1',  'Grade 1',  'कक्षा १',  'basic',     1),
  ('basic-2',  'Grade 2',  'कक्षा २',  'basic',     2),
  ('basic-3',  'Grade 3',  'कक्षा ३',  'basic',     3),
  ('basic-4',  'Grade 4',  'कक्षा ४',  'basic',     4),
  ('basic-5',  'Grade 5',  'कक्षा ५',  'basic',     5),
  ('basic-6',  'Grade 6',  'कक्षा ६',  'basic',     6),
  ('basic-7',  'Grade 7',  'कक्षा ७',  'basic',     7),
  ('basic-8',  'Grade 8',  'कक्षा ८',  'basic',     8),
  ('grade-9',  'Grade 9',  'कक्षा ९',  'secondary', 9),
  ('grade-10', 'Grade 10', 'कक्षा १०', 'secondary', 10),
  ('grade-11', 'Grade 11', 'कक्षा ११', 'higher',    11),
  ('grade-12', 'Grade 12', 'कक्षा १२', 'higher',    12)
on conflict (slug) do nothing;

-- ------------------------------------------------- subjects belong to a level

alter table subjects
  add column if not exists level_id uuid references levels(id) on delete restrict;

-- Everything that exists today is ECED. Runs once; later re-runs match nothing.
update subjects
   set level_id = (select id from levels where slug = 'eced')
 where level_id is null;

-- `slug` was globally unique, which was fine when the catalogue was one grade
-- wide and is fatal the moment it is not: "maths" could exist exactly once
-- across the whole ladder, so Grade 2 maths could never be inserted at all.
-- Uniqueness belongs per level.
do $mig$
begin
  begin
    alter table subjects drop constraint subjects_slug_key;
  exception when undefined_object then null; end;
  begin
    alter table subjects add constraint subjects_level_slug unique (level_id, slug);
  exception when duplicate_table or duplicate_object then null; end;
end $mig$;

create index if not exists subjects_level_order on subjects(level_id, sort_order);

-- ------------------------------------------------------------------ classes

create table if not exists classes (
  id          uuid primary key default gen_random_uuid(),
  school_id   uuid not null references schools(id) on delete cascade,
  level_id    uuid not null references levels(id) on delete restrict,
  -- The school's own words for the room: "Nursery A", "Grade 3 (morning)".
  label       text not null,
  created_at  timestamptz not null default now(),
  unique (school_id, level_id, label)
);

create index if not exists classes_school on classes(school_id);

-- A television belongs to at most one class. Null is valid and common - a hall
-- or a shared room - and such a device browses the whole ladder. A device with a
-- class opens straight into that class's grade.
-- on delete set null so deleting a class unassigns its televisions rather than
-- deleting them, matching how teacher_id already behaves.
alter table devices
  add column if not exists class_id uuid references classes(id) on delete set null;

create index if not exists devices_class on devices(class_id);

-- --------------------------------------------------------------------- views

-- Kills the N+1 in BrowseFragment.loadCatalog(), which called units(subject_id)
-- once per subject purely to put a count on a tile - and did it again on every
-- onResume, including every return from the player. At 5 ECED subjects that is
-- 6 round trips. Across the full ladder it is closer to 100, and it pulls the
-- whole units table each time, against an egress cap shared by every television
-- in the field. One row per card instead.
--
-- security_invoker keeps the caller's RLS in force rather than the view owner's,
-- same as playable_lessons.
create or replace view subject_cards
  with (security_invoker = true) as
  select
    s.id, s.level_id, s.slug, s.name_en, s.name_np, s.sort_order,
    s.color_1, s.color_2, s.icon,
    (select count(*) from units u
      where u.subject_id = s.id)::int as unit_count,
    (select count(*) from lessons l join units u on u.id = l.unit_id
      where u.subject_id = s.id)::int as lesson_count,
    (select count(*) from lessons l join units u on u.id = l.unit_id
      where u.subject_id = s.id and l.video_url is not null)::int as playable_count
  from subjects s;

-- The same idea one level up: how much of each grade actually exists yet. The
-- television uses this to mark a grade that has no content instead of opening an
-- empty screen, which is the difference between "coming soon" and "broken".
create or replace view level_cards
  with (security_invoker = true) as
  select
    lv.id, lv.slug, lv.name_en, lv.name_np, lv.stage, lv.sort_order,
    (select count(*) from subjects s
      where s.level_id = lv.id)::int as subject_count,
    (select count(*) from lessons l
       join units u    on u.id = l.unit_id
       join subjects s on s.id = u.subject_id
      where s.level_id = lv.id and l.video_url is not null)::int as playable_count
  from levels lv;

-- ------------------------------------------------------------------ security

alter table levels  enable row level security;
alter table classes enable row level security;

-- The ladder is the same for every school in the country and is not sensitive.
drop policy if exists levels_read on levels;
create policy levels_read on levels
  for select to anon, authenticated using (true);

-- Same known limitation as schools_read and teachers_read: any anon client can
-- list every class in every school. A class is a school plus a room label, which
-- is the same category of information those two already expose, so this does not
-- widen the existing gap - but it does not narrow it either, and all three want
-- the same fix: scope reads to the calling device, once a device can prove which
-- device it is. See the note at the top of 0006.
drop policy if exists classes_read on classes;
create policy classes_read on classes
  for select to anon, authenticated using (true);

-- Creating classes is ops-console work. The console uses service_role and
-- bypasses RLS; no anon policy for write means anon cannot write.
revoke insert, update, delete on levels  from anon, authenticated;
revoke insert, update, delete on classes from anon, authenticated;

-- A view does not inherit the grants of the tables underneath it, and RLS
-- policies say nothing about table privileges. Supabase's default privileges
-- usually grant new public objects to anon, but 0003 did not rely on that for
-- quiz_options_public and neither does this: without these two lines the
-- televisions read the catalogue through subject_cards and get
-- "permission denied for view", which looks like an empty catalogue rather
-- than an error. Both views are security_invoker, so the underlying RLS still
-- applies - this grants reach, not visibility.
grant select on subject_cards to anon, authenticated;
grant select on level_cards   to anon, authenticated;

-- devices.class_id must not be settable by the television, for exactly the
-- reason school_id is not: a device that can choose its own class has
-- self-provisioned. 0002 already revoked direct writes on devices and routes
-- registration through register_device(), which touches only hardware_uuid,
-- app_version and last_seen, so the new column is already unwritable by anon.

notify pgrst, 'reload schema';

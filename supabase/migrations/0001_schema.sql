-- JSP ECED Learning — schema
-- Run in Supabase SQL Editor. Idempotent: safe to re-run.
--
-- Design rule: the catalog is DATA, never compiled into the APK. Units are
-- variable-length by design because the 2082 curriculum's thematic blocks are
-- 6/6/6/12/9 days, not a uniform grid. Do not add a fixed units-per-subject or
-- lessons-per-unit constraint.

create extension if not exists "pgcrypto";

-- ---------------------------------------------------------------- deployment

create table if not exists schools (
  id            uuid primary key default gen_random_uuid(),
  name          text not null,
  municipality  text,
  province      text,
  created_at    timestamptz not null default now()
);

create table if not exists devices (
  id            uuid primary key default gen_random_uuid(),
  hardware_uuid text not null unique,
  school_id     uuid references schools(id) on delete set null,
  claimed_at    timestamptz,
  last_seen     timestamptz,
  app_version   text,
  created_at    timestamptz not null default now()
);

create table if not exists sessions (
  id          uuid primary key default gen_random_uuid(),
  device_id   uuid not null references devices(id) on delete cascade,
  token       text not null,
  issued_at   timestamptz not null default now(),
  expires_at  timestamptz not null,
  revoked     boolean not null default false
);

-- ------------------------------------------------------------------ catalog

create table if not exists subjects (
  id          uuid primary key default gen_random_uuid(),
  slug        text not null unique,
  name_en     text not null,
  name_np     text,
  sort_order  int  not null default 0,
  color_1     text,
  color_2     text,
  icon        text
);

create table if not exists units (
  id          uuid primary key default gen_random_uuid(),
  subject_id  uuid not null references subjects(id) on delete cascade,
  title_en    text not null,
  title_np    text,
  sort_order  int  not null default 0,
  theme_tag   text,          -- maps to a curriculum thematic sub-area
  est_days    int,           -- curriculum day-count for this block
  icon        text,
  unique (subject_id, sort_order)
);

create table if not exists lessons (
  id           uuid primary key default gen_random_uuid(),
  unit_id      uuid not null references units(id) on delete cascade,
  title_en     text not null,
  title_np     text,
  sort_order   int  not null default 0,
  duration_sec int,
  video_url    text,          -- demo: GitHub Releases. Later: BunnyCDN. Data change only.
  poster_url   text,
  codec        text default 'h264',   -- 'h264' for emulator, 'h265' for real hardware
  size_bytes   bigint,
  unique (unit_id, sort_order)
);

-- ------------------------------------------------------------------ people

create table if not exists profiles (
  id            uuid primary key references auth.users(id) on delete cascade,
  display_name  text,
  role          text not null default 'teacher',   -- teacher | admin | parent
  school_id     uuid references schools(id) on delete set null,
  created_at    timestamptz not null default now()
);

-- Dual identity by design: the TV has no login (device_id), mobile has accounts
-- (profile_id). One table, both modes, exactly one of the two set.
create table if not exists progress (
  id            uuid primary key default gen_random_uuid(),
  lesson_id     uuid not null references lessons(id) on delete cascade,
  device_id     uuid references devices(id) on delete cascade,
  profile_id    uuid references profiles(id) on delete cascade,
  position_sec  int  not null default 0,
  completed     boolean not null default false,
  updated_at    timestamptz not null default now(),
  constraint progress_one_identity check (num_nonnulls(device_id, profile_id) = 1)
);

-- These MUST be real UNIQUE CONSTRAINTS, not partial unique indexes.
-- PostgREST's upsert emits `ON CONFLICT (device_id, lesson_id)` with no WHERE
-- clause, and Postgres cannot infer a PARTIAL index from that - it fails with
-- "no unique or exclusion constraint matching the ON CONFLICT specification".
-- Verified against Postgres 16: partial index => every progress save fails.
--
-- A plain constraint is also correct semantically: Postgres treats NULLs as
-- distinct, so unlimited rows with device_id IS NULL (the mobile/profile rows)
-- coexist happily under UNIQUE (device_id, lesson_id).
do $$
begin
  begin
    alter table progress add constraint progress_device_lesson
      unique (device_id, lesson_id);
  exception when duplicate_table or duplicate_object then null; end;
  begin
    alter table progress add constraint progress_profile_lesson
      unique (profile_id, lesson_id);
  exception when duplicate_table or duplicate_object then null; end;
end $$;

-- ------------------------------------------------------------------ quizzes

create table if not exists quizzes (
  id             uuid primary key default gen_random_uuid(),
  unit_id        uuid not null references units(id) on delete cascade,
  title          text not null,
  pass_threshold numeric not null default 0.6
);

create table if not exists quiz_questions (
  id          uuid primary key default gen_random_uuid(),
  quiz_id     uuid not null references quizzes(id) on delete cascade,
  prompt      text not null,
  media_url   text,
  sort_order  int not null default 0
);

create table if not exists quiz_options (
  id           uuid primary key default gen_random_uuid(),
  question_id  uuid not null references quiz_questions(id) on delete cascade,
  label        text not null,
  is_correct   boolean not null default false,
  sort_order   int not null default 0
);

create table if not exists quiz_attempts (
  id          uuid primary key default gen_random_uuid(),
  quiz_id     uuid not null references quizzes(id) on delete cascade,
  device_id   uuid references devices(id) on delete cascade,
  profile_id  uuid references profiles(id) on delete cascade,
  score       int not null default 0,
  total       int not null default 0,
  answers     jsonb,
  created_at  timestamptz not null default now(),
  constraint attempt_one_identity check (num_nonnulls(device_id, profile_id) = 1)
);

-- -------------------------------------------------------------- app version
-- Backs the OTA check. One row.

create table if not exists app_release (
  id            int primary key default 1,
  version_name  text not null,
  version_code  int  not null,
  apk_url       text,
  mandatory     boolean not null default false,
  notes         text,
  updated_at    timestamptz not null default now(),
  constraint app_release_singleton check (id = 1)
);

-- --------------------------------------------------------------------- views

-- The home screen needs "which lessons actually have a video" (5 of 968).
-- Filtering client-side means pulling all 968 rows on every launch; the
-- IS NOT NULL filter syntax varies across supabase-kt versions, so a view is
-- the stable way to push it server-side.
-- security_invoker keeps the caller's RLS in force rather than the view owner's.
create or replace view playable_lessons
  with (security_invoker = true) as
  select * from lessons where video_url is not null;

-- ------------------------------------------------------------------ indexes

create index if not exists units_subject_order  on units(subject_id, sort_order);
create index if not exists lessons_unit_order   on lessons(unit_id, sort_order);
create index if not exists devices_hardware     on devices(hardware_uuid);
create index if not exists sessions_device      on sessions(device_id) where revoked = false;

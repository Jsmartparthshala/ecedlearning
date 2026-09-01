-- JSP ECED Learning — placeholder catalog seed
-- Run AFTER 0002_security.sql.
--
-- ############################################################################
-- #  THIS IS SHAPE, NOT CONTENT.                                             #
-- #  Unit and lesson titles are auto-generated placeholders. The real         #
-- #  breakdown and theming per subject is content-phase work driven by the    #
-- #  2082 curriculum. Nothing here should survive into production.           #
-- #  Wipe and re-seed with: select reset_catalog();                           #
-- ############################################################################
--
-- Totals produced: 968 lessons.
--   5 subjects x 192 lessons  = 960
--   + 1 orientation unit x 8  =   8
--                               ---
--                               968
--
-- 192/subject is the working assumption from 192 school days/year. Units are
-- deliberately VARIABLE length (6/6/9/12/6/9 cycling) because the curriculum's
-- thematic blocks are 6/6/6/12/9 days, not a uniform grid. Do not "tidy" this
-- into an even split — the unevenness is the point.

create or replace function reset_catalog() returns void language plpgsql as $$
begin
  delete from lessons;
  delete from units;
  delete from subjects;
end;
$$;

select reset_catalog();

-- ------------------------------------------------------------------ subjects
-- Mapping to the 2082 curriculum's five विषयगत सिप areas is noted per row.
-- Two known seams, both content-phase decisions:
--   * English + Nepali are ONE curriculum area (भाषिक सिप) split into two products
--   * सामाजिक सिप (social skills) has no product subject — currently unhomed

insert into subjects (slug, name_en, name_np, sort_order, color_1, color_2, icon) values
  ('english',  'ECED English',    'अङ्ग्रेजी',              1, '#2aa9d8', '#123a7a', 'abc'),
  ('nepali',   'ECED Nepali',     'नेपाली',                2, '#e8863b', '#a52f2f', 'book'),
  ('maths',    'ECED Math',       'पूर्वगणितीय सिप',        3, '#4fae54', '#14603a', 'numbers'),
  ('serofero', 'Sero Phero',      'वरपरको वातावरण',        4, '#a94bc9', '#3b31b0', 'globe'),
  ('arts',     'Arts & Crafts',   'दृश्यकला र सिर्जनशीलता', 5, '#d94f7a', '#7a1f4f', 'palette');

-- These five are ECED, and the insert above deliberately does not say so: this
-- file has to keep working on a database that has not had
-- 0007_levels_and_classes.sql applied yet, where there is no level to point at.
--
-- Once the ladder exists, subjects with no level are invisible - the home screen
-- reads `subject_cards` filtered by level_id, so a NULL there means the tile
-- appears in no grade at all. Re-running this seed after migrating would
-- therefore empty the app, which is exactly the sort of thing that gets found on
-- a demo morning. Assign them if, and only if, the ladder is there.
do $seed$
begin
  if to_regclass('public.levels') is not null then
    update subjects
       set level_id = (select id from levels where slug = 'eced')
     where level_id is null;
  end if;
end $seed$;

-- --------------------------------------------------------- units + lessons

do $$
declare
  s              record;
  unit_sizes     int[] := array[6, 6, 9, 12, 6, 9];   -- one cycle = 48 lessons
  cycles         int   := 4;                          -- 4 x 48 = 192 per subject
  u_index        int;
  l_index        int;
  n_lessons      int;
  new_unit_id    uuid;
  cyc            int;
  pos            int;
begin
  for s in select id, slug, name_en from subjects order by sort_order loop
    u_index := 0;

    for cyc in 1..cycles loop
      for pos in 1..array_length(unit_sizes, 1) loop
        u_index   := u_index + 1;
        n_lessons := unit_sizes[pos];

        insert into units (subject_id, title_en, title_np, sort_order, theme_tag, est_days, icon)
        values (
          s.id,
          format('[PLACEHOLDER] %s — Unit %s', s.name_en, u_index),
          null,
          u_index,
          'unmapped',        -- content phase fills this with the curriculum sub-area
          n_lessons,         -- 1 lesson per school day, so days == lessons for now
          null
        )
        returning id into new_unit_id;

        for l_index in 1..n_lessons loop
          insert into lessons (unit_id, title_en, sort_order, duration_sec, codec)
          values (
            new_unit_id,
            format('[PLACEHOLDER] Lesson %s', l_index),
            l_index,
            (11 + (l_index % 5)) * 60,   -- 11-15 min, matching the ~12 min/day budget
            'h264'
          );
        end loop;
      end loop;
    end loop;
  end loop;
end $$;

-- ------------------------------------------- orientation unit (8 lessons)
-- प्रारम्भिक कक्षा / settling-in. The curriculum gives this its own block before
-- thematic content starts. Parked under Sero Phero; move it in the content phase.

do $$
declare
  sero_id  uuid;
  unit_id  uuid;
  i        int;
begin
  select id into sero_id from subjects where slug = 'serofero';

  insert into units (subject_id, title_en, title_np, sort_order, theme_tag, est_days)
  values (sero_id, '[PLACEHOLDER] Orientation', 'प्रारम्भिक कक्षा', 0, 'settling-in', 8)
  returning id into unit_id;

  for i in 1..8 loop
    insert into lessons (unit_id, title_en, sort_order, duration_sec, codec)
    values (unit_id, format('[PLACEHOLDER] Orientation %s', i), i, 12 * 60, 'h264');
  end loop;
end $$;

-- ------------------------------------------------- real playable demo videos
-- Day 2 needs a handful of lessons that actually play. Public H.264 test files —
-- swap for your own clips on GitHub Releases as soon as you have them.
-- H.264 on purpose: emulator HEVC decode is unreliable and will look like a bug.

with first_lessons as (
  select l.id, row_number() over (order by s.sort_order, u.sort_order, l.sort_order) as rn
  from lessons l
  join units u    on u.id = l.unit_id
  join subjects s on s.id = u.subject_id
)
update lessons set
  video_url = case fl.rn
    when 1 then 'https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4'
    when 2 then 'https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4'
    when 3 then 'https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4'
    when 4 then 'https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4'
    else        'https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerFun.mp4'
  end,
  codec = 'h264'
from first_lessons fl
-- Filter on rn, never LIMIT inside the CTE: LIMIT without ORDER BY is
-- nondeterministic, so the planner could hand back 5 arbitrary rows scattered
-- across the catalog instead of the first five lessons.
where lessons.id = fl.id and fl.rn <= 5;

-- ------------------------------------------------------------ app version

insert into app_release (id, version_name, version_code, mandatory, notes)
values (1, '0.1.0', 1, false, 'First sprint build')
on conflict (id) do update set
  version_name = excluded.version_name,
  version_code = excluded.version_code,
  updated_at   = now();

-- ------------------------------------------------------------------ verify

select
  (select count(*) from subjects) as subjects,
  (select count(*) from units)    as units,
  (select count(*) from lessons)  as lessons,
  (select count(*) from lessons where video_url is not null) as playable;
-- expect: 5 | 121 | 968 | 5

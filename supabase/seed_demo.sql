-- JSP ECED Learning — DEMO seed
-- Run in the Supabase SQL Editor AFTER 0001_schema.sql, 0002_security.sql and seed.sql.
-- Idempotent: safe to re-run.
--
-- ############################################################################
-- #  THIS IS DEMO DATA. It exists so the sideload -> pairing -> ops console   #
-- #  -> realtime activation -> browse -> play walkthrough has something real  #
-- #  behind every screen. None of it is production content.                   #
-- ############################################################################
--
-- What this file does, in order:
--   1. seeds the schools the ops console picks from   (without these, activation
--      has an empty dropdown and the demo dies at the most important moment)
--   2. attaches playable video to a broad slice of the catalog, so the grid is
--      not 963 dimmed tiles around 5 live ones
--   3. installs demo_unpair_all(), to run the pairing demo more than once

-- =========================================================================
--  1. SCHOOLS
-- =========================================================================
-- Rename the first row to whichever school is actually in the room. Seeing
-- their own name appear on the television the instant the code is activated is
-- the whole point of the walkthrough.
--
-- `on conflict do nothing` needs a unique key to conflict against, and `name`
-- has none, so this filters on `not exists` instead. Re-running adds nothing.

insert into schools (name, municipality, province)
select v.name, v.municipality, v.province
from (values
  ('Jagdamba Smart Pathshala',                  'Birgunj Metropolitan City',        'Madhesh'),
  ('Shree Jana Jagriti Aadharbhut Vidyalaya',   'Birgunj Metropolitan City',        'Madhesh'),
  ('Shree Saraswati Aadharbhut Vidyalaya',      'Kalaiya Sub-Metropolitan City',    'Madhesh'),
  ('Shree Bal Kalyan Aadharbhut Vidyalaya',     'Pokhara Metropolitan City',        'Gandaki'),
  ('Shree Himalaya Aadharbhut Vidyalaya',       'Dhulikhel Municipality',           'Bagmati'),
  ('Shree Buddha Aadharbhut Vidyalaya',         'Lumbini Sanskritik Municipality',  'Lumbini')
) as v(name, municipality, province)
where not exists (select 1 from schools s where s.name = v.name);

-- =========================================================================
--  2. PLAYABLE VIDEO
-- =========================================================================
--
--  >>> PASTE YOUR OWN URLS IN THE `pool` BLOCK BELOW. <<<
--
--  These must be DIRECT MEDIA URLS - a .mp4/.m3u8/.mpd that a plain GET returns
--  video bytes for. Media3 opens the URL and demuxes whatever comes back.
--
--  A YouTube watch/share/embed link is NOT one of these. It returns an HTML
--  page, so ExoPlayer raises a source error and the tile looks broken. There is
--  no flag that fixes that - see the note at the bottom of this file.
--
--  Hosts that do work, in the order they are worth trying:
--    dev/video-server  - a laptop on the same network as the television. This
--                        is what the demo actually uses; see below.
--    Supabase Storage  - already part of this stack, public bucket, no new
--                        account, and it is where production content lands
--    GitHub Releases   - a release asset URL streams fine and is free
--    Cloudflare R2 / BunnyCDN - what the costing in MEMORY.md assumes
--
--  FOR THE DEMO, DO NOT USE THE POOL BELOW. Run
--
--      node dev/video-server/serve.js "D:/WORK/YOHO VIDS" --sql
--
--  and paste the local_videos.sql it writes instead. That serves the real
--  Nursery/LKG/UKG recordings off the laptop, with true durations read from
--  each file, which is a far better demo than any stock clip. It must be
--  re-run at the venue: the laptop's address changes with the network, and
--  the URLs are pinned to it.
--
--  The three below are the off-network fallback - if the laptop is not on the
--  same Wi-Fi as the television, these still play. They were verified to
--  return 200 with a video/mp4 body on 2026-08-25. Google's gtv-videos-bucket
--  files that used to sit here now return 403: that bucket has been locked,
--  and the failure surfaces in the app as a bare "Source error", so do not put
--  them back without checking them first.

with pool as (
  select * from (values
    (0, 'https://media.w3.org/2010/05/sintel/trailer.mp4',                                                52),
    (1, 'https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_5MB.mp4',         10),
    (2, 'https://filesamples.com/samples/video/mp4/sample_1280x720_surfing_with_audio.mp4',               170)
  ) as t(n, url, secs)
),

-- Which lessons get video. Whole units, never a scattering: a teacher opening a
-- unit should find every tile live, because a grid that is half dead reads as a
-- broken app rather than as an incomplete catalogue. The first three units of
-- each subject, plus the orientation unit, come to 113 of the 968 lessons -
-- enough that wherever the demo wanders early it plays, while the later units
-- stay honestly empty.
target as (
  select
    l.id,
    row_number() over (order by s.sort_order, u.sort_order, l.sort_order) - 1 as rn
  from lessons l
  join units    u on u.id = l.unit_id
  join subjects s on s.id = u.subject_id
  where u.sort_order <= 3
)

update lessons set
  video_url    = p.url,
  duration_sec = p.secs,
  -- H.264 on purpose. Emulator HEVC decode is unreliable, and a decode failure
  -- during a live demo is indistinguishable from a bug in the app.
  codec        = 'h264'
from target t
join pool p on p.n = t.rn % (select count(*) from pool)
where lessons.id = t.id;

-- =========================================================================
--  3. RE-RUNNING THE PAIRING DEMO
-- =========================================================================
-- Activation is meant to be permanent - the session token is cached for ten
-- years and the television never asks again. That is correct in a school and
-- useless in a demo, where the pairing screen is the thing being shown.
--
-- Call `select demo_unpair_all();` to put every television back to its pairing
-- code. The device row survives, so the same hardware re-registers with the
-- same 8-character code rather than inventing a new one.
--
-- In-app equivalent for a single television: Settings -> Unpair this television.

create or replace function demo_unpair_all() returns void language plpgsql as $$
begin
  delete from sessions;
  update devices set claimed_at = null, school_id = null;
end;
$$;

-- =========================================================================
--  verify
-- =========================================================================

select
  (select count(*) from schools)                              as schools,
  (select count(*) from lessons)                              as lessons,
  (select count(*) from lessons where video_url is not null)  as playable,
  (select count(*) from devices)                              as devices,
  (select count(*) from sessions where revoked = false)       as live_sessions;
-- expect: 6 more schools than before (7 total against the project as it stands
-- on 2026-08-25, which already holds "Shree Saraswati Primary")
--       | 968 | 113 | <however many TVs have booted> | <activated TVs>

-- ---------------------------------------------------------------------------
--  Why there is no YouTube option here
-- ---------------------------------------------------------------------------
--  1. A watch URL serves HTML, not media. Media3 has no extractor for it.
--  2. The only sanctioned route is the YouTube IFrame API in a WebView. That
--     needs a working WebView, which the non-GMS AOSP boxes this ships to do
--     not reliably have; it costs far more RAM than a 1 GB box can spare; the
--     D-pad handling inside the iframe is not ours to fix; and it plays ads in
--     front of a nursery class.
--  3. Extracting the underlying stream breaks YouTube's terms and, in practice,
--     breaks on its own every few weeks.
--  4. It would end the offline story. Downloads only works on a URL we can
--     fetch and keep, which is the entire reason this app suits rural schools.
--
--  If the footage is yours and currently lives on YouTube: download the MP4 and
--  upload it to a public Supabase Storage bucket, then paste those URLs into
--  the pool above. That is the same path production content takes.

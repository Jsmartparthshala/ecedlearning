-- Moves the catalogue to https://interstate-republic-hiking-flooring.trycloudflare.com without re-assigning anything.
--
-- Use this after restarting the tunnel: the paths, the durations and which
-- lesson holds which video are unchanged, only the host moves. Lessons on
-- any other host are left alone, including the handful of sample-CDN ones
-- from the seed, which are the fallback when this machine is not serving.
--
-- If the catalogue holds no URLs yet this moves nothing and says so - paste
-- local_videos.sql instead.

with previous as (
  select substring(video_url from '^https?://[^/]+') as host, count(*) as n
  from lessons
  where video_url ~ '^https?://'
  group by 1
  order by n desc
  limit 1
)
update lessons l
set video_url  = 'https://interstate-republic-hiking-flooring.trycloudflare.com' || substr(l.video_url,  length(p.host) + 1),
    poster_url = case when starts_with(l.poster_url, p.host || '/')
                      then 'https://interstate-republic-hiking-flooring.trycloudflare.com' || substr(l.poster_url, length(p.host) + 1)
                      else l.poster_url end
from previous p
where starts_with(l.video_url, p.host || '/')
  and p.host <> 'https://interstate-republic-hiking-flooring.trycloudflare.com';

select count(*) as on_this_host from lessons
where starts_with(video_url, 'https://interstate-republic-hiking-flooring.trycloudflare.com/');

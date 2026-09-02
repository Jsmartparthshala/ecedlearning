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

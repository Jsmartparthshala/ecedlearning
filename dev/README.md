# Local Supabase stand-in

Lets the app run against a real backend without a Supabase project — useful when
you're locked out, offline, or don't want to burn free-tier egress while iterating.

Supabase is Postgres + PostgREST + GoTrue behind a gateway. The TV app only talks
to the PostgREST part, so that's all this reproduces.

## Start

```bash
docker compose -f dev/docker-compose.yml up -d
```

Then apply the migrations (first run only):

```bash
docker compose -f dev/docker-compose.yml exec -T postgres psql -U postgres -d eced < supabase/migrations/0001_schema.sql
docker compose -f dev/docker-compose.yml exec -T postgres psql -U postgres -d eced < supabase/migrations/0002_security.sql
docker compose -f dev/docker-compose.yml exec -T postgres psql -U postgres -d eced < supabase/seed.sql
```

## Point the app at it

`android/secrets.properties`:

```
SUPABASE_URL=http://10.0.2.2:8080
SUPABASE_ANON_KEY=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJyb2xlIjoiYW5vbiIsImlzcyI6InN1cGFiYXNlIiwiaWF0IjoxNzg3NjMzNjU2LCJleHAiOjIxMDI5OTM2NTZ9.srxXPs7eIyJbuVqN5yho0GVetj2QS7s8OjL5pu1m3O4
```

That key is a JWT signed with the secret in `docker-compose.yml`. It is **not**
sensitive — the signing secret is committed in plain text right next to it, and
both are worthless against anything but the local container. It exists so
PostgREST behaves exactly like Supabase, including role-based RLS.

`10.0.2.2` is how the Android emulator reaches this machine. Cleartext HTTP is
permitted for that host **in debug builds only** — see
`tv/src/debug/res/xml/network_security_config.xml`. Release builds still require
HTTPS, so this can't hide a production mistake.

## Switching to real Supabase

Replace those two lines with your project URL and anon key. Nothing else changes —
same schema, same queries, same RLS.

## Notes

- No JWT secret is configured, so PostgREST treats every request as the `anon`
  role. That's exactly the TV's situation in production, so **RLS is genuinely
  exercised** rather than bypassed.
- `psql` for poking around:
  ```bash
  docker compose -f dev/docker-compose.yml exec postgres psql -U postgres -d eced
  ```
- Stop with `docker compose -f dev/docker-compose.yml down`, or
  `down -v` to also wipe the data and start clean.

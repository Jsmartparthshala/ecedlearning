# JSP ECED Learning

Android TV and mobile learning app for Nepal's Early Childhood Education and
Development curriculum, built for **Jagdamba Smart Pathshala**.

Designed for classroom televisions in rural schools: no login, no keyboard, no
Play Store. A television is provisioned once from a central office and then simply
works.

---

## Why it is built this way

**Schools never type anything.** Rural classrooms have a remote control and a
television, not a keyboard. On first boot the TV shows a short code and waits.
Someone at the central office matches that code to a school and clicks once. The
television authenticates itself and caches the session for ten years. There is no
login screen anywhere in the product.

**The catalogue is data, not code.** Subjects, units, and lessons live in Postgres
and are fetched at runtime. The ECED curriculum is structured as variable-length
thematic blocks rather than a uniform grid, and it is revised periodically — so
restructuring the catalogue is a data migration, never an app release.

**Built for the hardware that actually ships.** The target is not a flagship TV.
It is an Amlogic or Allwinner box with a Mali-450-class GPU and often 1 GB of RAM.
Every UI decision follows from that: flat cards over bitmaps, strokes over shadows,
overdraw held at 1x, no full-screen backdrop crossfades.

**No privileged key ever reaches a browser or a device.** The TV ships the anon
key and is held in check by row-level security. The ops console needs the
`service_role` key, so that key lives in a serverless function's environment and
the console page talks to the function — never to Supabase directly.

---

## Architecture

```
Android TV (Kotlin + Leanback)  ─┐
                                 ├─→  Supabase (Postgres + PostgREST + RLS)
Android mobile (Compose)        ─┘             ▲
                                               │  service_role, server side only
Ops console (static page)  ──→  Netlify function
```

| Module | What it is |
|---|---|
| `android/core` | Data layer — models, repositories, Supabase client. No UI. |
| `android/tv` | Leanback TV app: pairing, browse, unit grid, player, settings |
| `android/mobile` | Compose phone app *(not yet started)* |
| `ops-console` | Provisioning console: static page plus one Netlify function |
| `supabase` | Schema, row-level security, seed data |
| `dev` | Local Supabase-compatible stack for offline development |
| `dev/video-server` | Serves demo footage over the LAN or a tunnel, and writes the matching `lessons` rows |
| `prototype` | Original clickable HTML mock of the agreed screen flow |

`DEMO.md` is the runbook for showing the product on a laptop with no venue
internet.

---

## Getting started

### 1. Database

Run against your Supabase project's SQL editor, in order:

```
supabase/migrations/0001_schema.sql      tables, constraints, views
supabase/migrations/0002_security.sql    row-level security, grants, RPCs
supabase/migrations/0004_teachers.sql    teachers, and devices.teacher_id
supabase/migrations/0005_release_device.sql  the RPC behind "Unpair this television"
supabase/migrations/0006_progress_no_delete.sql  closes a fleet-wide DELETE hole
supabase/migrations/0007_levels_and_classes.sql  the grade ladder, classes, and the
                                         subject_cards / level_cards views
supabase/migrations/0008_session_status.sql  lets a TV notice it has been revoked
supabase/migrations/0009_release_device_class.sql  unpair also clears the class
supabase/seed.sql                        placeholder catalogue
```

The numbers are the order. 0009 redefines a function first created in 0005, so
running them out of order silently reinstates the older behaviour.

Two of these fix defects you can see from the sofa, and neither is fixed by
installing the app alone:

- **0005 + 0009** — until they are applied, "Unpair this television" cannot
  work. It used to clear the local cache only, so the set re-registered under the
  same hardware id, found the session nobody had revoked, and paired itself back
  in within about two seconds. The button had never once logged a television out.
- **0008** — until it is applied, **Revoke** in the ops console does not reach a
  running television. It marks the session revoked and the set never asks, so it
  keeps playing and keeps writing progress until somebody clears its data by
  hand.

`supabase/migrations/0003_hardening.sql` tightens session access and moves quiz
grading server-side. It requires a matching client change and is documented
inside the file — do not apply it blind. It is deliberately out of sequence for
that reason.

`supabase/seed_demo.sql` inserts a handful of real schools, for demonstrations.
It is optional and not part of the schema.

### 2. Configure the app

```bash
cp android/secrets.properties.example android/secrets.properties
```

Fill in your project URL and **anon** key from Project Settings → API. The anon
key is designed to ship inside the APK; row-level security is what protects the
data.

### 3. Build

```bash
cd android && ./gradlew :tv:assembleDebug
```

Requires JDK 17+. Android Studio's bundled JBR works — point `JAVA_HOME` at it if
building from a terminal.

### 4. Deploy the ops console

Import the repo into Netlify with `ops-console` as the base directory, then set
three environment variables: `SUPABASE_URL`, `SUPABASE_SERVICE_ROLE_KEY`, and
`OPS_PASSCODE`. Full steps are in `ops-console/README.md`.

The function fails closed if any of the three is missing, so an unconfigured
deploy is never an open console.

### 5. Provision a device

Launch the app; it displays an 8-character code. Open the console, enter the
passcode once, type the code, pick the school, and click activate. The television
moves to the home screen on its own — no interaction at the TV end at all.

---

## Local development without Supabase

`dev/` runs a Supabase-compatible stack — Postgres, PostgREST, and a gateway that
mirrors Supabase's URL shape and JWT auth — so the app can be developed offline
and without consuming free-tier bandwidth.

```bash
docker compose -f dev/docker-compose.yml up -d
```

See `dev/README.md`. Switching between local and hosted is two lines in
`secrets.properties`; the schema, queries, and RLS policies are identical.

---

## Notable constraints

**Version floor.** Kotlin is pinned to 2.4.x because supabase-kt ships a 2.4
standard library whose metadata older compilers cannot read. `minSdk` is 23
because Media3 requires it. Both are documented in
`android/gradle/libs.versions.toml`.

**Codec.** Lessons carry a `codec` column. H.264 plays everywhere; H.265 halves
bandwidth but is not hardware-decoded on older Amlogic and Rockchip boxes, where
it degrades to software decode and drops frames. Both can coexist per lesson.

**Bandwidth.** The catalogue is ~968 lessons. The home screen reads a filtered
view rather than the full table — the difference is 1.6 KB versus 244 KB per
launch, which is the difference between fitting in a free-tier egress budget and
not.

**Idle projects pause.** A Supabase free-tier project suspends after seven days
without traffic, and resuming it is manual. `.github/workflows/supabase-keepalive.yml`
pings the REST endpoint daily so that cannot happen the morning of an install.

---

## Licence

Proprietary. © Jagdamba Smart Pathshala.

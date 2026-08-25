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

---

## Architecture

```
Android TV (Kotlin + Leanback)  ─┐
                                 ├─→  Supabase (Postgres + PostgREST + RLS)
Android mobile (Compose)        ─┘
                                          ▲
Ops console (static web)  ────────────────┘
```

| Module | What it is |
|---|---|
| `android/core` | Data layer — models, repositories, Supabase client. No UI. |
| `android/tv` | Leanback TV app: pairing, browse, unit grid, player |
| `android/mobile` | Compose phone app *(not yet started)* |
| `ops-console` | Single-page admin panel for claiming devices |
| `supabase` | Schema, row-level security, seed data |
| `dev` | Local Supabase-compatible stack for offline development |
| `prototype` | Original clickable HTML mock of the agreed screen flow |

---

## Getting started

### 1. Database

Run against your Supabase project's SQL editor, in order:

```
supabase/migrations/0001_schema.sql     tables, constraints, views
supabase/migrations/0002_security.sql   row-level security, grants, RPCs
supabase/seed.sql                       placeholder catalogue
```

`supabase/migrations/0003_hardening.sql` tightens session access and moves quiz
grading server-side. It requires a matching client change and is documented
inside the file — do not apply it blind.

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

### 4. Provision a device

Launch the app; it displays a pairing code. Open `ops-console/index.html`, connect
with your project URL and service-role key, select the school, and push a token.
The television moves to the home screen on its own.

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

---

## Licence

Proprietary. © Jagdamba Smart Pathshala.

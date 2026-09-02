# Where the project stands

Last updated **2 September 2026**. This file is the running account of what is
built, what is deliberately not built, and what is waiting on somebody. For how
the system is put together, read `README.md`. For getting a fresh machine to the
point where you can work on it, read `SETUP.md`.

---

## The short version

The Android TV app is real and works end to end: a television pairs itself from
a code on screen, draws its catalogue live from Supabase, plays lessons, resumes
where it was left, and can be revoked from the office. The ops console
provisions devices, schools, teachers, and classes. Nine migrations are written;
eight are applied to production.

What does not exist yet is the phone app, quizzes on screen, and any kind of
reporting. Those were cut on purpose and are listed below in the order they are
worth adding back.

---

## Built and working

### Android TV app (`android/tv`)

Kotlin + Leanback, D-pad only, no login anywhere in the product.

| Screen | State |
|---|---|
| Pairing | Shows an 8-character code, waits, moves itself to the home screen when the office activates it |
| Home (browse) | Subject tiles and rows drawn from Supabase; Continue watching; Nepali names on tiles; a loading state instead of a blank screen |
| Unit detail | Two-pane browser — units on the left, lessons on the right |
| Player | Media3/ExoPlayer, D-pad transport, resume from last position, debounced progress writes, an honest message when a lesson will not play |
| Profile | School, teacher, and class as the office assigned them |
| Downloads | Present as a page; offline caching itself is not built |
| Settings | Includes "Unpair this television", which now genuinely logs the set out |
| Nav rail | Collapsing overlay rail, icons drawn as vectors, gold marker on the current page |

The catalogue is data, not code — 968 lessons is the target library size, and
restructuring it is a migration, never an app release.

### Backend (`supabase`)

Nine migrations, `0001` through `0009`. Order matters: `0009` redefines a
function first created in `0005`, so running them out of sequence silently
reinstates older behaviour. `README.md` lists them with what each one fixes.

Applied to production: `0001`, `0002`, `0004`–`0009`.

**Not applied: `0003_hardening.sql`.** It tightens session access and moves quiz
grading server-side, and it needs a matching client change that does not exist
yet. It is numbered out of sequence for exactly that reason. Do not run it
before a demo.

Row-level security is what protects the data. The TV ships the anon key, which
is designed to be shipped. The `service_role` key exists only inside the Netlify
function's environment and never reaches a browser or a device.

### Ops console (`ops-console`)

A static page plus one Netlify function. Manages schools, teachers, classes, and
devices — activate a device by code, assign a teacher, assign a class, revoke a
session. Usable on a phone, because it gets used standing next to a television.

There is deliberately **no subject selection** anywhere in it. A class implies
its subjects; picking them by hand would be a second source of truth.

The function fails closed if any of `SUPABASE_URL`,
`SUPABASE_SERVICE_ROLE_KEY`, or `OPS_PASSCODE` is missing, so a half-configured
deploy is never an open console.

### Development tooling (`dev`)

- `dev/` — a Supabase-compatible stack in Docker (Postgres, PostgREST, a gateway
  that mirrors Supabase's URL shape and JWT auth) so the app can be developed
  offline without burning free-tier egress.
- `dev/video-server/` — serves demo footage over the LAN or a Cloudflare quick
  tunnel and writes the matching `lessons` rows. Plain Node, no dependencies.

---

## Recent work

**1 September — correctness and the grade ladder.** Closed a fleet-wide DELETE
hole on `progress` and `quiz_attempts`. Fixed the two separate reasons a
television could never actually be logged out. Added the CDC grade ladder
(ECED, Basic 1–8, Secondary 9–10, 11–12) and made a device's class mean
something. Added classes to the ops console. Stopped the home screen asserting
things that were not true, and made the type legible from the back of a
classroom.

**2 September — UI.** Two distinct nav-rail defects, both now fixed: pressing
left on a prose page opened the menu pointing at the wrong entry, and the rail
would seize focus a second or two after arriving from another rail destination.
Collapsed to a single always-dark palette after the app came up in light mode on
the real television. Added a slide animation to the rail, a gold marker on the
current page, and a contrast pass that lifted five card fills to at least 3:1
against their text.

---

## Not built, in the order worth adding back

1. **Quizzes on screen.** The tables have existed since `0001`. Roughly half a
   day of UI. Note `0003` is entangled with this — server-side grading is what
   that migration is for.
2. **Mobile app.** The `android/mobile` module exists and is empty. About a day
   and a half with accounts. `progress` already carries both `device_id` and
   `profile_id`, nullable, so the phone can arrive without a schema change.
3. **Reporting dashboard.** About a day.
4. **Offline unit caching.** About a day with `CacheDataSource`. The Downloads
   page is currently a page with nothing behind it.
5. **Over-the-air updates.** Highest effort, lowest return. Android 12+ always
   shows a system install prompt, so a genuinely silent update is not possible
   without device-owner provisioning.

---

## Outstanding — things waiting on a person

- **GitHub Actions secrets are not set.** `.github/workflows/supabase-keepalive.yml`
  pings the REST endpoint daily so the free-tier project cannot suspend after
  seven idle days. It needs two repository secrets, `SUPABASE_URL` and
  `SUPABASE_ANON_KEY`. Until they exist the workflow fails every day, which is
  what the Supabase "keep-alive ping failed" emails are. `SETUP.md` has the
  exact page.
- **No release signing config.** The app is debug-signed only. A release
  keystore has to be generated before shipping to real boxes, and then kept
  somewhere safe and out of git — losing it means an installed app can never be
  updated in place.
- **Demo video hosting is ephemeral.** The tunnel takes a new hostname every
  restart, which invalidates every URL in the catalogue at once. The video
  server writes a four-line repair SQL for exactly this; paste it after every
  restart.

---

## Traps worth knowing before you change anything

**Migration order is load-bearing.** See above.

**Kotlin is pinned to 2.4.x** because supabase-kt ships a 2.4 standard library
whose metadata older compilers cannot read. **`minSdk` is 23**, not 21, because
media3-exoplayer 1.9.0 requires it. Both are documented in
`android/gradle/libs.versions.toml`.

**Every activity is Leanback**, so `AppCompatDelegate` cannot force dark mode.
That is why there is one palette in `values/` and no `values-night/` at all — a
night-qualified palette is how the app ended up light on the demo television.

**The home screen reads a filtered view, not the full table.** 1.6 KB versus
244 KB per launch. That difference is the whole free-tier egress budget.

**H.264 plays everywhere. H.265 halves bandwidth** but is not hardware-decoded
on older Amlogic and Rockchip boxes, where it silently degrades to software
decode and drops frames. Lessons carry a `codec` column so both can coexist.

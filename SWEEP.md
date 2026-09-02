# Ops console — security and bug sweep

Written overnight on 3 September 2026, for reading together in the morning.

Everything below is a finding against the ops console: the static page in
`ops-console/` and the one Netlify function behind it,
`ops-console/netlify/functions/api.mjs`. The Android app is not in scope here.

Two lists. **Fixed** is work already done and committed, described so you can
disagree with it. **Flagged** is everything I found but did not change, because
the fix is a product decision rather than a defect — those are the ones that
need you.

Nothing in this sweep touched Supabase. No SQL was run and nothing was pushed.

---

## Fixed

### 1. A pinned school's audit row recorded nothing

`setLocation` called the audit helper with the wrong shape. The helper takes
`(action, target, before, after)`; it was being handed a single object, so every
pin and unpin wrote a row whose target was the literal string `[object Object]`
and whose before and after were both empty.

That matters more than it sounds. A pin typed in wrong is *invisible* — the dot
simply appears somewhere plausible, and nobody re-checks a dot that looks
plausible — so the audit row's only real job is to record the coordinate that was
replaced. It was recording nothing.

Fixed by reading the school's current coordinates before the update, and passing
the school name as the target, the old pair as `before` and the new pair as
`after`.

### 2. The passcode comparison leaked its own length

`passcodeOk` compared with `timingSafeEqual`, which is right, but guarded it with
an early `if (a.length !== b.length) return false`, which is not.
`timingSafeEqual` throws on mismatched lengths so the guard is necessary — but
the guard itself returns in measurably less time than a real comparison, which
tells anyone willing to measure exactly how many characters the passcode has
before they have guessed a single one of them.

Both sides are now SHA-256'd first, so every comparison is thirty-two bytes
against thirty-two bytes and the length of what was typed is not observable at
all.

### 3. Nothing slowed down passcode guessing

The console is one shared passcode on a public URL. Nothing stopped a script
posting to `/api` as fast as Netlify would answer, and a short passcode falls to
that in an afternoon.

Added a per-source failure counter: eight wrong answers from one address inside a
minute, and that address gets a 429 for the rest of the minute.

Two things about how it is built, both deliberate:

- **The correct passcode is never refused.** The comparison runs first and clears
  the counter on success, so a colleague on the same office connection as
  somebody fat-fingering the code is never locked out. The only thing that can be
  throttled is a wrong answer.
- **It is a speed bump, not a wall.** The memory is per function instance and
  evaporates when Netlify recycles it. That is the right size for what it is
  defending: it turns thousands of guesses a minute into a handful, which is the
  difference between a passcode being brute-forceable and not. The real fix is
  Netlify Identity, and the code says so rather than pretending otherwise.

### 4. No Content-Security-Policy

`netlify.toml` already sent `X-Frame-Options`, `nosniff`, `no-referrer` and a
noindex tag. It sent no CSP, so an injected `<script>` on an admin page holding a
passcode would simply have run.

Added a strict one, plus a `Permissions-Policy` turning off camera, microphone,
geolocation, payment and USB — none of which this page has any business asking
for.

The policy is `'self'` for every source with no `'unsafe-inline'` anywhere, which
the page can afford because it genuinely loads nothing external: no CDN, no web
font, no analytics, no inline script. It did carry two stray `style="…"`
attributes, which is what would have forced `style-src 'unsafe-inline'` and undone
most of the value; those moved into `console.css`.

**If a future panel wants a chart library, the CSP has to be widened
deliberately.** That is the point of it. Do not loosen it to make an error go
away.

### 5. A school added in the console could never be placed by province

The map falls back to a province centroid when the municipality on a school is a
ward or a tole that no district table recognises. The server started persisting
`province` when I added the map. The "Add school" prompt never asked for one, so
every school created through the console had a null province and could only ever
be placed by municipality or not at all.

`addSchool` now asks for it.

### 6. Pinning had no user interface at all

`0012_school_location.sql` and the `set-location` action both shipped last night
with nothing in the page that could reach them. A migration with no caller is
dead weight, so this was an unfinished feature rather than a defect, and it is now
finished:

- A disclosure control beside every school, in two places — under a school in the
  map's detail panel, and beside a school in the "Not on the map" list, which is
  where it is needed most.
- **One field, not two.** The coordinate arrives as a single pasted string,
  because that is what "copy coordinates" gives you on every phone map there is.
  Splitting it across a latitude box and a longitude box means the operator does
  the splitting by hand and gets a fresh chance to make a mistake doing it.
- The swap is caught by name: paste `85.32, 27.71` and it says *"Those two are
  the wrong way round — latitude comes first"*, rather than a range error.
- The same bounds are checked in three places — the page, the function, and the
  `schools_location_sane` constraint in the migration — because the console is not
  the only thing that can ever write to that table.
- Removing a pin says *"no longer pinned"*, not *"back to its district"*. A school
  whose address nothing recognises goes back to the not-placed list, and naming a
  district it never had would be a small confident lie about the one thing that
  panel exists to be honest about.

### 7. The map told operators to press a button that did not exist

The "Not on the map" panel says to correct the municipality on the Fleet tab.
There was no way to do that. The only school control on the page was the one
that created them, so a school entered as "Ward 4 Tole" was stuck being
unplaceable for good.

Added an `update-school` action and an **Edit…** button beside **Add school…**,
prompting for name, municipality and province, prefilled with what is there now.
The pin is deliberately not touched by an edit: a coordinate is a stronger
statement than an address and outlives a correction to one, so fixing a wrong
address must not throw away a right pin. Removing a pin stays its own action, on
the map, where the consequence is visible.

### 8. The console never confirmed anything it did

Every action on the Fleet tab writes to the status line — "Added Butwal Model
ECED Centre", "Revoked", "Expiry set to 30/11/2026" — and then asks for a
reload, which ends by writing the fleet summary to the same line. The reload
wins, because it is last. So in practice every confirmation on that panel
appeared and vanished inside a second, which reads as the click not having
registered.

Eleven call sites, one cause, so it is fixed once rather than eleven times: the
line now has two kinds of writer. `setStatus` is the operator's own action and
holds the line — six seconds for a confirmation, twelve for an error — and
`setAmbient` is the poll describing the fleet, which gives way to it and takes
the line back when the message has had its time. A stale "Revoked." sitting over
a fleet that has changed twice since would be worse than no message at all.

### 9. Statistics, from the original list — a fleet counted by school

The counters across the top say how many televisions exist. They cannot answer
the question the office actually has, which is not "how many boxes do we own"
but "is Butwal using theirs" — and a flat list of twenty-two televisions makes
the reader do the grouping by hand.

A **By school** table now sits under the television list: school, where it is,
how many televisions, how many activated, and when one of them last played
something. Busiest first, so a school with a lesson running right now is the row
you land on. A school with fewer activated than it has televisions shows that
number in amber, because a box waiting to be activated is normal on an install
visit but worth seeing.

Two things it deliberately does not do:

- **It costs nothing.** Every figure is derived from the schools, televisions and
  playing set the page has already loaded. It asks the server for nothing, so it
  adds no invocations against the monthly ceiling — the same arrangement as the
  map, for the same reason.
- **It never calls a school inactive.** A television writes a progress row while
  a lesson plays and writes nothing at all sitting on the browse screen, so
  silence is not evidence. The column is headed "Last played", a quiet school is
  dimmed rather than flagged, and the note under the table says in as many words
  that this cannot tell you a box is switched off.

This is also why there is still no chart anywhere. With eleven schools, anything
shaped like a trend line is decoration over a sample too small to have one.

### 10. Visual polish (not a defect, but it is in the same commit)

Elevation and motion, both as tokens rather than one-offs:

- Three shadow steps, tinted with the deep navy rather than black. Black on a
  cream ground goes grey, and grey on warm cream reads as grime rather than as
  depth — the page ends up looking slightly dirty and nobody can say why.
- One duration and one curve (140ms, a single ease) for every state change that is
  not the map. A page where each control picks its own timing feels assembled
  rather than designed, and the difference is legible even to somebody who could
  not name it.
- Buttons rise one pixel under the pointer and sink on the press. The travel is
  deliberately less than the shadow's growth: a button that visibly jumps looks
  loose.
- A page-wide `prefers-reduced-motion` block that removes the transforms as well
  as the durations. A hover that jumps a pixel with no transition is worse than
  one that does not move at all.

---

## Flagged — these need your decision, not mine

### A. The passcode is kept in `localStorage`

`ops-console/js/api.js` stores it under `jsp_ops_pass`, which survives the browser
closing. On the office machine that is the feature: nobody re-types it every
morning. On a shared or borrowed machine it means the next person to open the
browser is already inside the console.

`sessionStorage` would clear it when the tab closes, at the cost of typing it once
per session.

This is a tradeoff about who uses that machine, so I have not changed it. My own
read is that `sessionStorage` becomes right the moment more than one person uses
the console, and `localStorage` is fine while it is one desk.

### B. The passcode is one shared credential

Everything the console can do — activating a television, revoking one, changing a
school's pin, publishing a privacy policy to every classroom — is available to
anyone holding one string. The audit table records what happened and cannot record
who did it, because there is nothing to record.

That is a known and documented decision, not an oversight. Worth revisiting the
first time a third person needs access, or the first time somebody who has the
passcode leaves. Netlify Identity is the stated upgrade path and slots in at the
same place in the function.

### C. Database error text reaches the operator's screen

The top-level catch returns `reason(e, String(e))`, so an unexpected exception can
put internal database wording on the page.

Two ways to read that. It is information disclosure — but only to somebody who
already holds the passcode, and the alternative is an operator who hits a real
problem having nothing whatsoever to relay to you. I have left it, because the
diagnostic value looks larger than the marginal risk. Say if you disagree and I
will move the detail into the function log and leave a reference on screen.

### D. Seven actions write to the database without an audit row

`create-school`, `create-teacher`, `delete-teacher`, `assign-teacher`,
`create-class`, `delete-class` and `assign-class` do not audit. `activate`,
`revoke`, `set-expiry`, `rename-lesson`, `save-document` and now `set-location`
do.

`delete-teacher` is the one that bothers me: it is destructive, it detaches every
television that teacher was assigned to, and nothing anywhere records that it
happened. The rest are lower stakes, but the inconsistency is its own problem — an
audit table you cannot trust to be complete is an audit table nobody reads.

Cheap to fix. I did not, because it writes rows to a table that does not exist yet
(`0011_ops_audit.sql` is still unrun) and I would rather you saw the shape of the
table first.

### E. Two read paths have caps that will eventually bite

- `nowPlaying` reads at most 600 progress rows from the last 24 hours.
- `summary` reads at most 2000 device ids for the "played today" count.

At 22 televisions neither is close. At a few hundred, both silently start
under-reporting rather than failing, which is the worse of the two ways to break.
Not urgent; worth a note against the day the fleet grows.

### F. Map dots are under the 44px touch guideline on a phone

Already known from last night, repeated here so it is in one place. A
single-television dot reaches about 23–34px on a 375px screen after the responsive
scale-up, against a 44px guideline. The map is a desk tool and this is a real but
minor shortfall; making the dots larger starts to hide the country underneath
them.

---

## Checked and found fine

Recorded so the same ground does not get walked twice.

- **HTML escaping.** Every interpolation into `innerHTML` across `fleet.js`,
  `lessons.js`, `documents.js`, `activity.js` and `map.js` goes through `esc()`,
  including the SVG `aria-label` and `<title>` on every map dot. I looked at each
  one; there are no gaps.
- **No Supabase key of any kind is present in the page.** The service_role key is
  read only by the function, from a Netlify environment variable.
- **The function fails closed.** A missing `OPS_PASSCODE` returns a 500 rather than
  letting everything through, and `POST` is the only method accepted.
- **Cross-school guards are server side.** A stale page cannot assign a teacher or
  a class belonging to another school; both are re-checked in the function rather
  than trusted from the client-filtered dropdown.
- **`revoke` clears `class_id`.** The bug where a revoked television kept a class
  belonging to a school it no longer belonged to is fixed and stayed fixed.
- **Everything parses.** All nine page modules, the generated `data/nepal.js`, and
  the function itself pass `node --check`.

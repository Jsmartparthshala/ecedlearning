# Demo runbook

The offline copy. Commands only — the full document with the feature inventory
and troubleshooting lives as a shared page, which is no use at a venue with no
internet.

## Before you leave

**1. Start the video server.** Leave the window open for the whole demo;
closing it stops playback mid-lesson.

```bash
node dev/video-server/serve.js "D:/WORK/YOHO VIDS" --sql
```

**2. Open the firewall**, once per laptop, in an Administrator PowerShell.
Without it the emulator still works while a real television silently cannot
connect.

```powershell
New-NetFirewallRule -DisplayName "ECED demo video" -Direction Inbound -Protocol TCP -LocalPort 8000 -Action Allow -Profile Private
```

**3. Seed the schools**, once ever. Run `supabase/seed_demo.sql` in the Supabase
SQL editor.

**4. Build and install.**

```bash
cd android && ./gradlew.bat :tv:assembleDebug
```

```bash
adb install -r dist/jsp-eced-0.1.0-demo.apk
```

## At the venue

**5. Re-run step 1 on the venue's network**, then paste the regenerated
`dev/video-server/local_videos.sql` into the Supabase SQL editor.

The URLs contain the laptop's address and it changes with the Wi-Fi. A stale
one shows on the television as a bare `Source error`, indistinguishable from
the app being broken. This is the single most likely thing to go wrong.

**6. Reset to a clean pairing screen**, between the rehearsal and the real run:

```sql
select demo_unpair_all();
```

**7. Activate.** The TV is showing an 8-character code. In the ops console: pick
the school, type the code, press **Add & activate**. The school name appears on
the television without anyone touching it.

**8. Play.** Open Sero Phero, play lesson 1 — a real Mero Serofero class. Ten
seconds in, press back and reopen it: it resumes, and the card now has a
progress bar.

## Ops console

```bash
netlify dev --dir ops-console
```

Not `npx serve` — that renders the page but every `/api` call 404s and the
passcode gate never opens. Deploy steps and the three environment variables are
in [ops-console/README.md](ops-console/README.md).

## If the laptop cannot be reached at all

Run `supabase/seed_demo.sql` as-is. Its fallback pool is three public URLs
needing only ordinary internet — stock clips rather than real classes, but the
flow still demonstrates end to end.

## Notes

- The demo APK is a **debug** build. There is no release signing config, so a
  release APK would be unsigned and could not be installed. The debug build is
  also what permits plain-HTTP video; release builds refuse cleartext.
- One APK covers phone and television: it declares both launcher types and
  marks leanback and touchscreen as not required.
- More detail: [dev/video-server/README.md](dev/video-server/README.md).

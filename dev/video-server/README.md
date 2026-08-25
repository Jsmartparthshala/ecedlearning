# Serving lesson video off a laptop

For demos. The television streams video from a laptop on the same network
instead of from the internet, which means the demo does not depend on the
venue's uplink — only on the two devices seeing each other.

It also means the demo plays **real Nursery / LKG / UKG class recordings**
rather than stock clips, which is the single biggest credibility difference in
the whole flow.

## Run it

```bash
node dev/video-server/serve.js "D:/WORK/YOHO VIDS" --sql
```

It prints the address to use and writes `local_videos.sql` next to itself.
Leave the window open for the whole demo — closing it stops playback.

No dependencies, no install step. Node 18+.

## Then point the catalogue at it

Paste `dev/video-server/local_videos.sql` into the Supabase SQL editor and run
it. That sets `video_url` and `duration_sec` on the lessons in the first three
units of every subject.

**Re-run both steps at the venue.** The URLs contain the laptop's address, and
that address changes with the network. A stale one surfaces on the television
as a bare `Source error`, indistinguishable from the app being broken.

## What it does that a one-liner does not

**HTTP Range.** `python -m http.server` does not implement it. Without ranges
ExoPlayer cannot seek, so the scrub bar is dead and resume-position — a
headline feature of this app — silently does nothing. Worse, on a file whose
index sits at the end, the player must read the whole thing before the first
frame; some of these are 2.4 GB.

**Real durations.** Each file's `mvhd` atom is parsed for its true length, so
`duration_sec` is correct. The "36 min" on a card and the width of every
resume-progress bar are computed from that number, and wrong values are visible
on screen.

**Subject matching.** Filenames that name a subject (`UKG MATH DAY 2`,
`MERO SEROFERO LKG DAY 3`) are assigned to that subject's first lessons, so
opening Sero Phero plays a Sero Phero class. The ~50 files named only by grade
and day are used as filler on any subject: `NURSERY DAY 14` claims nothing a
tile could contradict.

**Interface picking.** It ignores Tailscale, VirtualBox and Docker adapters. A
television cannot reach `192.168.56.x`, and offering it would produce a
catalogue full of dead URLs.

## Cleartext HTTP

This is plain `http://`, which Android has blocked by default since API 28.

`android/tv/src/debug/res/xml/network_security_config.xml` permits it —
**debug builds only**. Release builds never merge that file, so they keep the
default and refuse cleartext everywhere. Since the release build has no signing
config it cannot be installed anyway, the demo APK is the debug one, and this
is the file that governs it.

The rule is a blanket permit rather than a host list because the config format
has no CIDR or wildcard form, and the laptop's address is not known until it
joins the venue's network.

## Windows Firewall

Loopback and the emulator's `10.0.2.2` are exempt, so the emulator works with
no change. A **real television on the LAN does not**, and Windows blocks the
inbound connection silently.

Run once, in an **Administrator** PowerShell:

```powershell
New-NetFirewallRule -DisplayName "ECED demo video" -Direction Inbound -Protocol TCP -LocalPort 8000 -Action Allow -Profile Private
```

`-Profile Private` keeps it off public networks. If the venue's Wi-Fi is
classified Public, either set that network to Private or add `-Profile Any` —
and remove the rule afterwards:

```powershell
Remove-NetFirewallRule -DisplayName "ECED demo video"
```

## Checking it

From the laptop, the file index:

```bash
curl -s http://localhost:8000/ | head
```

Range support — this must return `206` and a `Content-Range` header:

```bash
curl -s -D - -o /dev/null -H "Range: bytes=-500" "http://localhost:8000/day%201/LKG%20ONLINE%20CLASS%20MATH.mp4"
```

From the television's browser, if it has one, open `http://<laptop-ip>:8000/`.
If the list appears, the network path is good and anything still failing is the
catalogue's URLs, not the connection.

The server logs every request with its range and timing, so during the demo the
window shows the television pulling video in real time.

## Scope

Read-only, no authentication, serves one directory. It resolves and bounds
every path against that directory, and refuses anything outside it — but it is
a demo aid on a trusted network, not something to expose to the internet.

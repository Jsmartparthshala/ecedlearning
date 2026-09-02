# Setting up a new machine

Everything needed to go from a bare Windows PC to building and running this
project. Written for the office machine, but any new laptop follows the same
list.

Allow about an hour and a half, most of it downloads.

---

## 1. Install

**Git** — <https://git-scm.com/download/win>. Defaults are fine. The Git Bash it
installs is the shell every command below assumes.

**Android Studio** — <https://developer.android.com/studio>, latest stable. Take
the newest one: this project is on AGP 8.11.1 and an older Studio will refuse to
open it.

Studio bundles JBR 17, which is exactly the Java version this build wants, so
**do not install a separate JDK**. If Gradle complains about Java when building
from a terminal, point it at Studio's copy:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
```

To make that permanent, once, in a normal Command Prompt:

```
setx JAVA_HOME "C:\Program Files\Android\Android Studio\jbr"
```

**Android SDK components** — Studio → SDK Manager:

- *SDK Platforms*: **Android 16 (API 36)**, which is `compileSdk`. Also **API 34**
  for the emulator image.
- *SDK Tools*: Android SDK Platform-Tools (this is `adb`), Android Emulator,
  Android SDK Build-Tools.

Gradle 8.14.3 and Kotlin 2.4.10 install themselves — the wrapper in the repo
fetches them on first build. Nothing to do.

**Node.js LTS** — <https://nodejs.org>. Needed for the ops console and the demo
video server.

**cloudflared** — the tunnel binary, used to serve demo video to a television
that is not on your LAN. Download `cloudflared-windows-amd64.exe` from
<https://github.com/cloudflare/cloudflared/releases>, rename it to
`cloudflared.exe`, and put it in `dev/video-server/`. It is gitignored, so it
will not arrive with the clone.

**Netlify CLI**, only if you want to run the ops console locally —
`npm i -g netlify-cli`. Not needed to work on the APK.

**Docker Desktop**, only if you want the offline Supabase stack in `dev/`. Also
optional.

---

## 2. The TV emulator

Studio → Device Manager → Create Virtual Device → **TV** category →
**Television (1080p)** → system image **API 34, Android TV**.

Name it `eced_tv_1080p` so it matches the commands in the other docs. Give it
2 GB RAM.

The system image is about 1.5 GB and is the single thing most likely to steal an
hour. Start it downloading before you do anything else.

---

## 3. Clone

```bash
git clone https://github.com/Jsmartparthshala/ecedlearning.git
```

---

## 4. The three things git will not give you

These are kept out of the repository on purpose. The first one is required — the
build fails without it.

### `android/secrets.properties`

Create it by hand, next to `android/build.gradle.kts`:

```
SUPABASE_URL=https://<your-project>.supabase.co
SUPABASE_ANON_KEY=<the anon key>
```

Copy both from the old machine's copy of this file, or read them off Supabase →
Project Settings → API. There is an example file at
`android/secrets.properties.example`.

The **anon** key is the one that goes here. It is designed to ship inside the
APK and row-level security is what protects the data. **Never put the
`service_role` key in this file** — it belongs only in Netlify's environment.

### `dev/video-server/cloudflared.exe`

Covered above.

### The demo video files

The folder the video server points at. Bring them on a drive; they are not in
the repository and should never be.

---

## 5. First build

Open the **`android/`** folder in Android Studio — not the repository root. The
Gradle project starts one level down.

Let it sync. The first sync pulls Gradle and the whole dependency set and takes
around twenty minutes; that is normal and not a hang.

Then, from a terminal:

```bash
cd android && ./gradlew :tv:assembleDebug
```

Run the `tv` configuration against the TV emulator. The app should come up on
the pairing screen showing an 8-character code.

---

## 6. Where the server-side secrets live

Nothing here is stored on a development machine. This section is a map for when
you need to find them.

### Netlify — ops console

Site → Site configuration → Environment variables. Three of them:

| Name | Value |
|---|---|
| `SUPABASE_URL` | The project URL |
| `SUPABASE_SERVICE_ROLE_KEY` | The service_role key from Supabase → Project Settings → API |
| `OPS_PASSCODE` | The console's shared passcode |

The function fails closed if any is missing.

### GitHub — the keep-alive workflow

`.github/workflows/supabase-keepalive.yml` needs two repository secrets, or it
fails daily and Supabase emails about the ping failing.

The page is easy to miss because it is under a sub-menu. Go to:

**Repository → Settings** (the tab, not your account settings) → in the left
sidebar, **Secrets and variables** → **Actions** → **New repository secret**.

It is *not* under Advanced Security, and it is not the repository's Variables
tab. Add:

| Name | Value |
|---|---|
| `SUPABASE_URL` | The project URL |
| `SUPABASE_ANON_KEY` | The anon key — the same one that goes in `secrets.properties` |

Both are safe to store there; neither is the service_role key.

---

## 7. Running the demo stack

Serving demo footage from this machine to a television:

```bash
node dev/video-server/serve.js "D:/WORK/YOHO VIDS" --sql
```

Leave the window open for the whole session; closing it stops playback mid
lesson. Every time the tunnel restarts it takes a new hostname, so paste the
regenerated repair SQL into the Supabase SQL editor afterwards.

Once per machine, in an Administrator PowerShell, or a real television can
silently not connect while the emulator works fine:

```powershell
New-NetFirewallRule -DisplayName "ECED demo video" -Direction Inbound -Protocol TCP -LocalPort 8000 -Action Allow -Profile Private
```

The full runbook for showing the product at a venue is `DEMO.md`.

---

## Reference

| Thing | Version |
|---|---|
| Gradle | 8.14.3 (via the wrapper) |
| Android Gradle Plugin | 8.11.1 |
| Kotlin | 2.4.10 |
| Java | 17 (Android Studio's bundled JBR) |
| compileSdk | 36 |
| targetSdk | 34 |
| minSdk | 23 (media3-exoplayer 1.9.0 sets the floor) |
| Package | `np.com.jagdamba.eced` |

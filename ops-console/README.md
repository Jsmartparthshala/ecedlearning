# Ops console

Reverse provisioning for JSP ECED televisions. A TV boots, shows an 8-character
code, and waits. Someone here types that code, picks the school, and the TV
activates itself. Nobody at the school types anything.

## Why there is a serverless function

The console needs the Supabase **`service_role`** key, which bypasses every
row-level security policy. On a public Netlify URL, that key in the page would be
a full read/write/delete handle on every device, session and school in the
project — for anyone who found the URL.

So the key lives in a Netlify environment variable and only
`netlify/functions/api.mjs` ever reads it. The page holds one shared passcode,
stored in `localStorage`, and talks to `/api`.

Net effect: **no Supabase credentials are ever typed into this page**, and the
passcode is entered once per computer.

The passcode gates the console; it is not per-user auth. Anyone holding it can
activate and revoke televisions — treat it as an admin credential and rotate it
when someone leaves. Netlify Identity is the upgrade and slots into the function
without the page changing.

## Deploy

1. Push the repo to GitHub, then in Netlify: **Add new site → Import an existing
   project**.
2. Set **Base directory** to `ops-console`. Leave the build command empty;
   publish directory and functions directory come from `netlify.toml`.
3. **Site configuration → Environment variables**, add all three:

   | Key | Value |
   |---|---|
   | `SUPABASE_URL` | `https://dxgkeveeyemmberfvfsi.supabase.co` |
   | `SUPABASE_SERVICE_ROLE_KEY` | Supabase → Project Settings → API → `service_role` |
   | `OPS_PASSCODE` | anything long; this is what you type into the page |

4. Deploy. Open the site, enter the passcode once.

If the function reports a missing variable, it fails closed and refuses every
request — including when `OPS_PASSCODE` is unset, so an unconfigured deploy is
never an open console.

## Run it locally

```bash
npm install -g netlify-cli
```

```bash
netlify dev --dir ops-console
```

`netlify dev` reads `netlify.toml`, serves the page, and runs the function, so
local behaviour matches production. Provide the three variables in a
`ops-console/.env` file (git-ignored) or via `netlify env:set`.

Plain static serving (`npx serve ops-console`) renders the page but every call to
`/api` 404s, so the passcode gate will not open. That is expected.

## What the function does

Single endpoint, `POST /api`, with an `action` field:

| Action | Purpose |
|---|---|
| `list` | Devices with their school, plus the school list |
| `lookup` | Resolve a typed code to one device, without changing anything |
| `activate` | Claim the device for a school and insert its 10-year session |
| `revoke` | Mark sessions revoked and return the TV to the pairing screen |
| `create-school` | Nothing seeds `schools`, so the first activation needs this |

`activate` writes the `devices` row **before** inserting into `sessions`,
because the session row is what the television is polling for — it must not
arrive before the device knows which school it belongs to.

## The code

The TV shows `hardware_uuid` with dashes removed, first 8 characters, uppercased.
A UUID's first segment is exactly 8 hex characters, so the code is just the UUID
up to the first dash. The function normalises whatever the operator types —
spaces, dashes, lower case — before matching.

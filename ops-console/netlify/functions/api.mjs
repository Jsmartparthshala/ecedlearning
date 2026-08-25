/**
 * Ops console backend.
 *
 * Every privileged call the console makes lives here rather than in the page.
 * The service_role key bypasses all row level security, so it must never reach a
 * browser: on a public Netlify URL that key is a full read/write/delete handle on
 * every device, session and school in the project. It stays in a Netlify
 * environment variable and only this function ever sees it.
 *
 * The page authenticates with a shared passcode. That is deliberately modest
 * security - it gates the console, it is not per-user auth. Anyone holding the
 * passcode can activate and revoke televisions, so treat it as an admin
 * credential and rotate it when someone leaves. The upgrade path is Netlify
 * Identity, which slots in here without the page changing.
 *
 * Required environment variables:
 *   SUPABASE_URL                 https://xxxx.supabase.co
 *   SUPABASE_SERVICE_ROLE_KEY    project service_role key
 *   OPS_PASSCODE                 whatever the operator types once
 */
import { createClient } from '@supabase/supabase-js'
import { timingSafeEqual } from 'node:crypto'

const SUPABASE_URL = process.env.SUPABASE_URL
const SERVICE_KEY = process.env.SUPABASE_SERVICE_ROLE_KEY
const PASSCODE = process.env.OPS_PASSCODE

/** Ten years. The TV is meant to never ask a teacher to sign in. */
const SESSION_YEARS = 10

const json = (body, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json', 'cache-control': 'no-store' },
  })

/**
 * Constant time compare. A plain === leaks the passcode a character at a time to
 * anyone willing to measure, which is cheap to avoid and awkward to explain later.
 */
function passcodeOk(supplied) {
  if (!PASSCODE || !supplied) return false
  const a = Buffer.from(String(supplied))
  const b = Buffer.from(PASSCODE)
  if (a.length !== b.length) return false
  return timingSafeEqual(a, b)
}

/**
 * The television shows `hardwareUuid().replace("-","").take(8).uppercase()`.
 * A UUID's first segment is exactly 8 hex characters, so that code is simply the
 * UUID up to the first dash - which makes it a prefix match rather than anything
 * clever. Operators will type it with spaces, dashes or in lower case; accept all
 * of that and normalise here so the TV and the console can never disagree.
 */
function normaliseCode(raw) {
  const code = String(raw || '').replace(/[^0-9a-fA-F]/g, '').toUpperCase()
  return /^[0-9A-F]{8}$/.test(code) ? code : null
}

export default async (req) => {
  if (req.method !== 'POST') return json({ error: 'POST only' }, 405)

  if (!SUPABASE_URL || !SERVICE_KEY) {
    return json({ error: 'Server is missing SUPABASE_URL or SUPABASE_SERVICE_ROLE_KEY' }, 500)
  }
  if (!PASSCODE) {
    // Failing closed matters here: without this the console would be wide open
    // the moment someone forgot to set the variable.
    return json({ error: 'Server is missing OPS_PASSCODE' }, 500)
  }
  if (!passcodeOk(req.headers.get('x-ops-passcode'))) {
    return json({ error: 'Wrong passcode' }, 401)
  }

  let body
  try {
    body = await req.json()
  } catch {
    return json({ error: 'Body must be JSON' }, 400)
  }

  const sb = createClient(SUPABASE_URL, SERVICE_KEY, { auth: { persistSession: false } })

  try {
    switch (body.action) {
      case 'list':
        return await list(sb)
      case 'lookup':
        return await lookup(sb, body)
      case 'activate':
        return await activate(sb, body)
      case 'revoke':
        return await revoke(sb, body)
      case 'create-school':
        return await createSchool(sb, body)
      default:
        return json({ error: `Unknown action: ${body.action}` }, 400)
    }
  } catch (e) {
    return json({ error: e?.message || String(e) }, 500)
  }
}

async function list(sb) {
  const { data: devices, error } = await sb
    .from('devices')
    .select('id, hardware_uuid, claimed_at, last_seen, app_version, school_id, schools(name)')
    .order('claimed_at', { ascending: true, nullsFirst: true })
    .order('created_at', { ascending: false })
  if (error) return json({ error: error.message }, 500)

  const { data: schools, error: e2 } = await sb
    .from('schools')
    .select('id, name, municipality')
    .order('name')
  if (e2) return json({ error: e2.message }, 500)

  return json({ devices: devices || [], schools: schools || [] })
}

/** Resolve a typed code to one device row, or explain why it did not resolve. */
async function findByCode(sb, rawCode) {
  const code = normaliseCode(rawCode)
  if (!code) return { error: 'A code is 8 letters and numbers, exactly as the television shows it.', status: 400 }

  const { data, error } = await sb
    .from('devices')
    .select('id, hardware_uuid, claimed_at, last_seen, app_version, school_id, schools(name)')
    .ilike('hardware_uuid', `${code}-%`)
  if (error) return { error: error.message, status: 500 }

  if (!data || data.length === 0) {
    // Almost always means the TV has not reached Supabase yet, not a typo.
    return {
      error: `No television has reported code ${code}. Check the code, and that the TV is online and still showing the pairing screen.`,
      status: 404,
    }
  }
  if (data.length > 1) {
    return { error: `Code ${code} matches ${data.length} televisions. Use the device list below instead.`, status: 409 }
  }
  return { device: data[0], code }
}

/** Preview what a code resolves to, so the operator can confirm before committing. */
async function lookup(sb, { code }) {
  const found = await findByCode(sb, code)
  if (found.error) return json({ error: found.error }, found.status)
  return json({ device: found.device, code: found.code })
}

/**
 * The demo's opening beat. The operator types the code the television is showing,
 * picks the school, and the TV wakes up by itself.
 */
async function activate(sb, { code, schoolId }) {
  if (!schoolId) return json({ error: 'Choose a school first.' }, 400)

  const found = await findByCode(sb, code)
  if (found.error) return json({ error: found.error }, found.status)

  const device = found.device
  if (device.claimed_at) {
    return json({
      error: `That television is already activated for ${device.schools?.name || 'a school'}. Revoke it first if you need to move it.`,
    }, 409)
  }

  const token = `${crypto.randomUUID()}.${crypto.randomUUID()}`
  const expires = new Date()
  expires.setFullYear(expires.getFullYear() + SESSION_YEARS)

  const { error: e1 } = await sb
    .from('devices')
    .update({ school_id: schoolId, claimed_at: new Date().toISOString() })
    .eq('id', device.id)
  if (e1) return json({ error: e1.message }, 500)

  // Insert last. This is the row the TV is polling for, so it must not arrive
  // before the device row says which school it belongs to.
  const { error: e2 } = await sb
    .from('sessions')
    .insert({ device_id: device.id, token, expires_at: expires.toISOString() })
  if (e2) return json({ error: e2.message }, 500)

  return json({ ok: true, deviceId: device.id, code: found.code })
}

async function revoke(sb, { deviceId }) {
  if (!deviceId) return json({ error: 'deviceId is required' }, 400)

  const { error: e1 } = await sb
    .from('sessions')
    .update({ revoked: true })
    .eq('device_id', deviceId)
  if (e1) return json({ error: e1.message }, 500)

  const { error: e2 } = await sb
    .from('devices')
    .update({ claimed_at: null, school_id: null })
    .eq('id', deviceId)
  if (e2) return json({ error: e2.message }, 500)

  return json({ ok: true })
}

/**
 * Nothing seeds the schools table, so without this the very first activation has
 * nowhere to point and the console is a dead end on day one.
 */
async function createSchool(sb, { name, municipality }) {
  const clean = String(name || '').trim()
  if (!clean) return json({ error: 'A school needs a name.' }, 400)

  const { data, error } = await sb
    .from('schools')
    .insert({ name: clean, municipality: String(municipality || '').trim() || null })
    .select('id, name, municipality')
    .single()
  if (error) return json({ error: error.message }, 500)

  return json({ ok: true, school: data })
}

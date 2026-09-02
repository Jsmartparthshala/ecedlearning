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
import { createHash, timingSafeEqual } from 'node:crypto'

const SUPABASE_URL = process.env.SUPABASE_URL
const SERVICE_KEY = process.env.SUPABASE_SERVICE_ROLE_KEY
const PASSCODE = process.env.OPS_PASSCODE

/**
 * Ten years, and still the default. The TV is meant to never ask a teacher to
 * sign in, and a session that expires is a television that goes back to the
 * pairing screen in the middle of a school day.
 *
 * An operator can now override it per device. The reason is loaner hardware: a
 * box left at a school for a term's trial should stop working at the end of the
 * term by itself, rather than relying on somebody remembering to revoke it.
 */
const SESSION_YEARS = 10

/**
 * Turn an operator-supplied expiry into a timestamp, or fall back to ten years.
 *
 * Rejects the past rather than accepting it: an expiry already gone is a device
 * that pairs and immediately drops, which reads as the pairing having failed
 * and sends somebody up a ladder to check a television that is working fine.
 */
function expiryFrom(raw) {
  if (!raw) {
    const d = new Date()
    d.setFullYear(d.getFullYear() + SESSION_YEARS)
    return { at: d }
  }
  const at = new Date(raw)
  if (Number.isNaN(at.getTime())) return { error: 'That expiry date could not be read.' }
  // End of the chosen day, local to nobody in particular but generous in the
  // right direction: a date typed as "the 30th" should include the 30th.
  if (/^\d{4}-\d{2}-\d{2}$/.test(String(raw))) at.setUTCHours(23, 59, 59, 0)
  if (at.getTime() < Date.now()) return { error: 'That expiry date has already passed.' }
  return { at }
}

/**
 * Record what the console just did, in a table that survives the row it
 * describes.
 *
 * Deliberately cannot fail the request. An audit trail is worth having and is
 * not worth refusing an activation over, so every error here is swallowed - and
 * that includes the table not existing at all, because 0011_ops_audit.sql is
 * run by hand and Netlify deploys on push, so this code is always live before
 * the table is.
 *
 * It records no identity, because there is none to record: one shared passcode
 * cannot tell two operators apart. What it records is the before and the after,
 * which is what actually gets a mistake undone.
 */
function auditor(sb, req) {
  const hint = [
    req.headers.get('x-nf-client-connection-ip') || req.headers.get('x-forwarded-for') || '',
    req.headers.get('user-agent') || '',
  ].filter(Boolean).join(' · ').slice(0, 400) || null

  return (action, target, before = null, after = null) =>
    sb.from('ops_audit')
      .insert({ action, target: target ? String(target).slice(0, 400) : null, before, after, actor_hint: hint })
      .then(() => {}, () => {})
}

const json = (body, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json', 'cache-control': 'no-store' },
  })

/**
 * Constant time compare. A plain === leaks the passcode a character at a time to
 * anyone willing to measure, which is cheap to avoid and awkward to explain later.
 *
 * Both sides are hashed first, and that is not belt and braces. timingSafeEqual
 * throws on buffers of different lengths, so the obvious guard is an early
 * `a.length !== b.length` return - and that guard is itself a timing signal
 * saying "wrong length", which hands an attacker the passcode's length before
 * they have guessed a single character of it. Hashing makes every comparison
 * thirty-two bytes against thirty-two bytes, so the length of what was typed
 * is no longer observable at all.
 */
function passcodeOk(supplied) {
  if (!PASSCODE || !supplied) return false
  const digest = v => createHash('sha256').update(String(v), 'utf8').digest()
  return timingSafeEqual(digest(supplied), digest(PASSCODE))
}

/**
 * Slow down guessing, without ever standing between an operator and the console.
 *
 * The console is gated by one shared passcode over a public URL, which is an
 * online guessing target: nothing in Netlify stops a script posting to /api a
 * few times a second forever. This counts *failures* per source address, and
 * once there have been too many in a row it refuses to look at anything from
 * that address for a minute.
 *
 * The correct passcode is never refused. The check below runs the comparison
 * first and clears the counter on success, so a colleague on the same office
 * connection as somebody fat-fingering the code is never locked out - the only
 * thing that can be throttled is a wrong answer.
 *
 * The memory is per function instance and evaporates when Netlify recycles it,
 * so this is a speed bump rather than a wall. That is the right size for the
 * threat: it turns thousands of guesses a minute into a handful, which is the
 * difference between a passcode being brute-forceable and not. The real fix is
 * Netlify Identity, and this does not pretend otherwise.
 */
const FAIL_LIMIT = 8
const FAIL_WINDOW_MS = 60_000
const failures = new Map()

function sourceOf(req) {
  return req.headers.get('x-nf-client-connection-ip') ||
         (req.headers.get('x-forwarded-for') || '').split(',')[0].trim() ||
         'unknown'
}

function throttled(who) {
  const seen = failures.get(who)
  if (!seen) return false
  if (Date.now() - seen.at > FAIL_WINDOW_MS) { failures.delete(who); return false }
  return seen.n >= FAIL_LIMIT
}

function noteFailure(who) {
  const seen = failures.get(who)
  const fresh = !seen || Date.now() - seen.at > FAIL_WINDOW_MS
  failures.set(who, { n: fresh ? 1 : seen.n + 1, at: Date.now() })
  // The map only ever holds addresses that have got the passcode wrong in the
  // last minute, but nothing prunes it on a quiet instance, so cap it.
  if (failures.size > 500) {
    for (const [k, v] of failures) if (Date.now() - v.at > FAIL_WINDOW_MS) failures.delete(k)
  }
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
  const who = sourceOf(req)
  if (passcodeOk(req.headers.get('x-ops-passcode'))) {
    failures.delete(who)
  } else if (throttled(who)) {
    // Deliberately the same wording as a plain rejection plus the wait, so that
    // an operator who has mistyped twice reads an instruction rather than an
    // accusation.
    return json({ error: 'Too many wrong passcodes. Wait a minute and try again.' }, 429)
  } else {
    noteFailure(who)
    return json({ error: 'Wrong passcode' }, 401)
  }

  let body
  try {
    body = await req.json()
  } catch {
    return json({ error: 'Body must be JSON' }, 400)
  }

  const sb = createClient(SUPABASE_URL, SERVICE_KEY, { auth: { persistSession: false } })
  const audit = auditor(sb, req)

  try {
    switch (body.action) {
      case 'list':
        return await list(sb)
      case 'summary':
        return await summary(sb)
      case 'now-playing':
        return await nowPlaying(sb)
      case 'set-expiry':
        return await setExpiry(sb, body, audit)
      case 'list-lessons':
        return await listLessons(sb, body)
      case 'rename-lesson':
        return await renameLesson(sb, body, audit)
      case 'list-documents':
        return await listDocuments(sb)
      case 'save-document':
        return await saveDocument(sb, body, audit)
      case 'lookup':
        return await lookup(sb, body)
      case 'activate':
        return await activate(sb, body, audit)
      case 'revoke':
        return await revoke(sb, body, audit)
      case 'create-school':
        return await createSchool(sb, body)
      case 'update-school':
        return await updateSchool(sb, body, audit)
      case 'set-location':
        return await setLocation(sb, body, audit)
      case 'create-teacher':
        return await createTeacher(sb, body)
      case 'delete-teacher':
        return await deleteTeacher(sb, body)
      case 'assign-teacher':
        return await assignTeacher(sb, body)
      case 'create-class':
        return await createClass(sb, body)
      case 'delete-class':
        return await deleteClass(sb, body)
      case 'assign-class':
        return await assignClass(sb, body)
      default:
        return json({ error: `Unknown action: ${body.action}` }, 400)
    }
  } catch (e) {
    return json({ error: reason(e, String(e)) }, 500)
  }
}

const DEVICE_COLUMNS =
  'id, hardware_uuid, claimed_at, last_seen, app_version, ' +
  'school_id, schools(name), teacher_id, teachers(name, role)'

const DEVICE_COLUMNS_WITH_CLASS =
  DEVICE_COLUMNS + ', class_id, classes(label, level_id)'

/**
 * Turn whatever supabase-js threw into something an operator can repeat back.
 *
 * An error is not guaranteed to carry a message. `.single()` is the sharp case:
 * against a table that does not exist it rejects with a completely empty object,
 * which `JSON.stringify` renders as `{}` - so the console reported a failure
 * with nothing whatsoever in it, and the operator had nothing to tell anyone.
 * Note that this is also why isMissingSchema cannot see such an error: there is
 * no code and no message on it to match.
 */
const reason = (err, fallback = 'The database rejected that without saying why.') =>
  err?.message ||
  err?.details ||
  err?.hint ||
  (err?.code ? `Database error ${err.code}.` : fallback)

/**
 * Does this error mean the schema simply does not have the class columns yet,
 * as opposed to something having gone wrong?
 *
 * Worth distinguishing. Falling back on *any* error means one transient blip
 * renders every television in the fleet as having no class, and an operator
 * reading that reasonably concludes the assignments were lost and starts
 * redoing them by hand.
 *
 * 42703 undefined_column, 42P01 undefined_table, PGRST200 no such relationship
 * in the schema cache, PGRST204 unknown column.
 */
const isMissingSchema = err =>
  ['42703', '42P01', 'PGRST200', 'PGRST204'].includes(err?.code) ||
  /does not exist|schema cache/i.test(err?.message || '')

/**
 * Read the fleet.
 *
 * The class columns are requested optimistically and dropped if the database has
 * not had 0007_levels_and_classes.sql applied yet. Netlify deploys on push and
 * the migration is run by hand, so the two are never simultaneous - and the
 * failure mode without this is the whole console going blank because one column
 * in one select does not exist yet, rather than the class feature simply not
 * appearing until the migration lands.
 */
async function list(sb) {
  let devices = null
  let error = null

  ;({ data: devices, error } = await sb
    .from('devices')
    .select(DEVICE_COLUMNS_WITH_CLASS)
    .order('claimed_at', { ascending: true, nullsFirst: true })
    .order('created_at', { ascending: false }))

  if (error && isMissingSchema(error)) {
    ;({ data: devices, error } = await sb
      .from('devices')
      .select(DEVICE_COLUMNS)
      .order('claimed_at', { ascending: true, nullsFirst: true })
      .order('created_at', { ascending: false }))
  }
  if (error) return json({ error: reason(error) }, 500)

  // Same tolerance again, for the map's coordinates. `province` has been on
  // this table since the first migration and was simply never selected; lat and
  // lon arrive with 0012, so before that migration is run the map still works
  // from the municipality and the province, and afterwards it can place a
  // pinned school exactly. Neither ordering of deploy and migration breaks the
  // page, which is the only ordering guarantee this project actually has.
  let schools = null
  let e2 = null

  ;({ data: schools, error: e2 } = await sb
    .from('schools')
    .select('id, name, municipality, province, lat, lon')
    .order('name'))

  if (e2 && isMissingSchema(e2)) {
    ;({ data: schools, error: e2 } = await sb
      .from('schools')
      .select('id, name, municipality, province')
      .order('name'))
  }
  if (e2) return json({ error: reason(e2) }, 500)

  // Every teacher in one go rather than per school. There are tens of these, not
  // thousands, and the page filters client side as the school dropdown changes -
  // which keeps changing school instant instead of costing a round trip.
  const { data: teachers, error: e3 } = await sb
    .from('teachers')
    .select('id, school_id, name, role')
    .order('name')
  if (e3) return json({ error: reason(e3) }, 500)

  // Same tolerance as the device select above: on a database without 0007 these
  // two come back empty and the page simply shows no class controls.
  const { data: levels } = await sb
    .from('levels')
    .select('id, slug, name_en, stage, sort_order')
    .order('sort_order')

  const { data: classes } = await sb
    .from('classes')
    .select('id, school_id, level_id, label')
    .order('label')

  // When each television's session runs out. Read here rather than per row so
  // that the expiry column costs one query for the whole fleet, and merged onto
  // the device because that is the object the page already draws.
  //
  // Only the token is secret; the date is not, and withholding it is what let a
  // loaner sit in a school for a term with nobody able to see it was about to
  // stop working.
  const { data: sessions } = await sb
    .from('sessions')
    .select('device_id, expires_at')
    .eq('revoked', false)
    .order('issued_at', { ascending: false })

  const expiry = new Map()
  for (const row of sessions || []) {
    // Newest first, so the first one seen for a device is the live one.
    if (!expiry.has(row.device_id)) expiry.set(row.device_id, row.expires_at)
  }
  for (const d of devices || []) d.expires_at = expiry.get(d.id) || null

  return json({
    devices: devices || [],
    schools: schools || [],
    teachers: teachers || [],
    levels: levels || [],
    classes: classes || [],
  })
}

/** Resolve a typed code to one device row, or explain why it did not resolve. */
async function findByCode(sb, rawCode) {
  const code = normaliseCode(rawCode)
  if (!code) return { error: 'A code is 8 letters and numbers, exactly as the television shows it.', status: 400 }

  const { data, error } = await sb
    .from('devices')
    .select('id, hardware_uuid, claimed_at, last_seen, app_version, school_id, schools(name)')
    .ilike('hardware_uuid', `${code}-%`)
  if (error) return { error: reason(error), status: 500 }

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
async function activate(sb, { code, schoolId, teacherId, expiresAt }, audit) {
  if (!schoolId) return json({ error: 'Choose a school first.' }, 400)

  const found = await findByCode(sb, code)
  if (found.error) return json({ error: found.error }, found.status)

  const device = found.device
  if (device.claimed_at) {
    return json({
      error: `That television is already activated for ${device.schools?.name || 'a school'}. Revoke it first if you need to move it.`,
    }, 409)
  }

  // A teacher belongs to exactly one school. Assigning one from a different
  // school would leave a device showing a name nobody at that school recognises,
  // so refuse it here rather than letting the UI be the only thing preventing it.
  if (teacherId) {
    const wrong = await teacherOutsideSchool(sb, teacherId, schoolId)
    if (wrong) return json({ error: wrong }, 400)
  }

  const expiry = expiryFrom(expiresAt)
  if (expiry.error) return json({ error: expiry.error }, 400)

  const token = `${crypto.randomUUID()}.${crypto.randomUUID()}`
  const expires = expiry.at

  const { error: e1 } = await sb
    .from('devices')
    .update({
      school_id: schoolId,
      teacher_id: teacherId || null,
      claimed_at: new Date().toISOString(),
    })
    .eq('id', device.id)
  if (e1) return json({ error: reason(e1) }, 500)

  // Insert last. This is the row the TV is polling for, so it must not arrive
  // before the device row says which school it belongs to.
  const { error: e2 } = await sb
    .from('sessions')
    .insert({ device_id: device.id, token, expires_at: expires.toISOString() })
  if (e2) return json({ error: reason(e2) }, 500)

  audit('activate', found.code, null, {
    school_id: schoolId, teacher_id: teacherId || null, expires_at: expires.toISOString(),
  })
  return json({ ok: true, deviceId: device.id, code: found.code, expiresAt: expires.toISOString() })
}

/**
 * Change when an already-activated television's session runs out.
 *
 * Separate from activate() because the case that needs it is a device that is
 * already in a classroom: a trial that has been extended, or a loaner being
 * turned into a permanent installation. Making the operator revoke and
 * re-activate to change a date would take a working television off the wall and
 * back to the pairing screen to do it.
 */
async function setExpiry(sb, { deviceId, expiresAt }, audit) {
  if (!deviceId) return json({ error: 'deviceId is required' }, 400)

  const expiry = expiryFrom(expiresAt)
  if (expiry.error) return json({ error: expiry.error }, 400)

  const { data: before, error: e0 } = await sb
    .from('sessions')
    .select('id, expires_at')
    .eq('device_id', deviceId)
    .eq('revoked', false)
    .order('issued_at', { ascending: false })
    .limit(1)
  if (e0) return json({ error: reason(e0) }, 500)
  if (!before || !before.length) {
    return json({ error: 'That television has no live session. Activate it first.' }, 404)
  }

  const { error } = await sb
    .from('sessions')
    .update({ expires_at: expiry.at.toISOString() })
    .eq('id', before[0].id)
  if (error) return json({ error: reason(error) }, 500)

  audit('set-expiry', deviceId,
    { expires_at: before[0].expires_at }, { expires_at: expiry.at.toISOString() })
  return json({ ok: true, expiresAt: expiry.at.toISOString() })
}

async function revoke(sb, { deviceId }, audit) {
  if (!deviceId) return json({ error: 'deviceId is required' }, 400)

  // Read it before it is gone. Revoking clears the school, the teacher and the
  // class, none of which can be recovered from the row afterwards - so without
  // this an operator who revokes the wrong television has no way of finding out
  // what it used to be assigned to.
  const { data: before } = await sb
    .from('devices')
    .select('hardware_uuid, school_id, teacher_id, class_id, claimed_at')
    .eq('id', deviceId)
    .maybeSingle()

  const { error: e1 } = await sb
    .from('sessions')
    .update({ revoked: true })
    .eq('device_id', deviceId)
  if (e1) return json({ error: reason(e1) }, 500)

  // class_id has to be cleared too. It was not, which left a revoked television
  // pointing at a class belonging to the school it no longer belongs to: on the
  // next activation for a different school the device kept the old grade, and
  // the console showed a class the new school has never heard of. The
  // release_device RPC in 0009 already clears all four - this is the console
  // path catching up with it.
  const { error: e2 } = await sb
    .from('devices')
    .update({ claimed_at: null, school_id: null, teacher_id: null, class_id: null })
    .eq('id', deviceId)
  if (e2 && !isMissingSchema(e2)) return json({ error: reason(e2) }, 500)

  // A database without 0007 has no class_id column at all; fall back to the
  // three that have always existed rather than failing the revoke.
  if (e2) {
    const { error: e3 } = await sb
      .from('devices')
      .update({ claimed_at: null, school_id: null, teacher_id: null })
      .eq('id', deviceId)
    if (e3) return json({ error: reason(e3) }, 500)
  }

  audit('revoke', before?.hardware_uuid || deviceId, before, null)
  return json({ ok: true })
}

/**
 * Shared guard. Returns an error string when the teacher does not belong to the
 * school, or null when the pairing is fine.
 */
async function teacherOutsideSchool(sb, teacherId, schoolId) {
  const { data, error } = await sb
    .from('teachers')
    .select('id, name, school_id')
    .eq('id', teacherId)
    .maybeSingle()
  if (error) return reason(error)
  if (!data) return 'That teacher no longer exists. Reload the page.'
  if (data.school_id !== schoolId) {
    return `${data.name} is not a teacher at that school.`
  }
  return null
}

async function createTeacher(sb, { schoolId, name, role }) {
  if (!schoolId) return json({ error: 'Choose a school first.' }, 400)
  const clean = String(name || '').trim()
  if (!clean) return json({ error: 'A teacher needs a name.' }, 400)

  const { data, error } = await sb
    .from('teachers')
    .insert({ school_id: schoolId, name: clean, role: String(role || '').trim() || null })
    .select('id, school_id, name, role')
    .single()
  if (error) return json({ error: reason(error) }, 500)

  return json({ ok: true, teacher: data })
}

/**
 * Removing a teacher does not remove their televisions. The FK is
 * `on delete set null`, so those devices stay activated for the school and fall
 * back to showing the school name - which is what a television should do when
 * the teacher it was assigned to leaves mid-term.
 */
async function deleteTeacher(sb, { teacherId }) {
  if (!teacherId) return json({ error: 'teacherId is required' }, 400)

  const { error } = await sb.from('teachers').delete().eq('id', teacherId)
  if (error) return json({ error: reason(error) }, 500)

  return json({ ok: true })
}

/** Point an already-activated television at a teacher, or clear it with null. */
async function assignTeacher(sb, { deviceId, teacherId }) {
  if (!deviceId) return json({ error: 'deviceId is required' }, 400)

  const { data: device, error: e0 } = await sb
    .from('devices')
    .select('id, school_id')
    .eq('id', deviceId)
    .maybeSingle()
  if (e0) return json({ error: reason(e0) }, 500)
  if (!device) return json({ error: 'That television no longer exists.' }, 404)

  if (teacherId) {
    if (!device.school_id) {
      return json({ error: 'Activate the television for a school before assigning a teacher.' }, 400)
    }
    const wrong = await teacherOutsideSchool(sb, teacherId, device.school_id)
    if (wrong) return json({ error: wrong }, 400)
  }

  const { error } = await sb
    .from('devices')
    .update({ teacher_id: teacherId || null })
    .eq('id', deviceId)
  if (error) return json({ error: reason(error) }, 500)

  return json({ ok: true })
}

/**
 * Nothing seeds the schools table, so without this the very first activation has
 * nowhere to point and the console is a dead end on day one.
 */
async function createSchool(sb, { name, municipality, province }) {
  const clean = String(name || '').trim()
  if (!clean) return json({ error: 'A school needs a name.' }, 400)

  const { data, error } = await sb
    .from('schools')
    .insert({
      name: clean,
      municipality: String(municipality || '').trim() || null,
      province: String(province || '').trim() || null,
    })
    .select('id, name, municipality, province')
    .single()
  if (error) return json({ error: reason(error) }, 500)

  return json({ ok: true, school: data })
}

/**
 * Correct a school that was typed in wrong, or has since been renamed.
 *
 * The municipality is the point of this. It is what the map places a school by,
 * so a school entered as "Ward 4" sits under the map in the not-placed list
 * until somebody can change it - and until now nobody could, because the only
 * school control on the page was the one that created them. The map's own hint
 * told operators to correct the municipality on the Fleet tab, which was an
 * instruction to do something the page could not do.
 *
 * The pin is deliberately not touched here. A coordinate is a stronger
 * statement than an address and outlives a correction to one - if the address
 * was wrong and the pin was right, fixing the address must not throw the pin
 * away. Removing a pin is its own action, on the map, where the consequence is
 * visible.
 */
async function updateSchool(sb, { schoolId, name, municipality, province }, audit) {
  if (!schoolId) return json({ error: 'Which school?' }, 400)

  const clean = String(name || '').trim()
  if (!clean) return json({ error: 'A school needs a name.' }, 400)

  const { data: before, error: e0 } = await sb
    .from('schools')
    .select('id, name, municipality, province')
    .eq('id', schoolId)
    .maybeSingle()
  if (e0) return json({ error: reason(e0) }, 500)
  if (!before) return json({ error: 'That school no longer exists. Reload the page.' }, 404)

  const patch = {
    name: clean.normalize('NFC'),
    municipality: String(municipality || '').trim().normalize('NFC') || null,
    province: String(province || '').trim().normalize('NFC') || null,
  }

  const { data, error } = await sb
    .from('schools')
    .update(patch)
    .eq('id', schoolId)
    .select('id, name, municipality, province')
    .single()
  if (error) return json({ error: reason(error) }, 500)

  audit('update-school', before.name, before, patch)
  return json({ ok: true, school: data })
}

/**
 * Pin a school to a point, or take the pin off again.
 *
 * The map places most schools by reading the municipality written on them,
 * which lands them in the right district and nowhere nearer. This is the
 * override: a coordinate read off a phone at the school gate, which the map
 * then prefers over its own guess.
 *
 * Sending null for both clears the pin and hands the school back to the
 * guess - the only way to undo a coordinate typed in wrong, and the reason
 * this takes null rather than treating a missing value as "leave alone".
 */
async function setLocation(sb, { schoolId, lat, lon }, audit) {
  if (!schoolId) return json({ error: 'Which school?' }, 400)

  const clearing = (lat === null || lat === undefined || lat === '') &&
                   (lon === null || lon === undefined || lon === '')

  let next = { lat: null, lon: null }
  if (!clearing) {
    const y = Number(lat)
    const x = Number(lon)
    if (!Number.isFinite(y) || !Number.isFinite(x)) {
      return json({ error: 'A pin needs two numbers, or nothing at all to clear it.' }, 400)
    }
    // Nepal, generously bounded. This is here to catch a swapped pair or a
    // dropped minus - the mistakes a person makes typing coordinates off a
    // phone - and not to have an opinion about a border. A point outside it
    // would stretch the map's own bounds until the country was a smudge.
    if (y < 26 || y > 31 || x < 79.5 || x > 89) {
      return json({
        error: 'That point is not in Nepal. Latitude comes first, between 26 and 31; ' +
               'longitude second, between 80 and 89.',
      }, 400)
    }
    next = { lat: y, lon: x }
  }

  // Read the pin that is there now before overwriting it. The whole value of
  // an audit row for this action is the coordinate it replaced: a pin typed in
  // wrong is invisible - the dot simply sits somewhere plausible - and the only
  // way back is a record of what it used to be.
  const { data: before } = await sb
    .from('schools')
    .select('lat, lon')
    .eq('id', schoolId)
    .maybeSingle()

  const { data, error } = await sb
    .from('schools')
    .update(next)
    .eq('id', schoolId)
    .select('id, name, municipality, province, lat, lon')
    .single()

  if (error) {
    if (isMissingSchema(error)) {
      return json({
        error: 'Pinning needs 0012_school_location.sql to be run against the database first.',
      }, 409)
    }
    return json({ error: reason(error) }, 500)
  }

  audit(clearing ? 'clear-location' : 'set-location', data?.name || schoolId,
    before ? { lat: before.lat, lon: before.lon } : null,
    { lat: next.lat, lon: next.lon })
  return json({ ok: true, school: data })
}

/**
 * A class is a school, a grade, and the school's own label for the room:
 * "Nursery A", "Grade 3 (morning)".
 *
 * This is the row that makes the console a CRM rather than a device list.
 * `teachers.role` is free text and stays that way - it is a human description,
 * not a key - so it cannot be what a class is built on. A class is a real row
 * that a television can point at, and pointing at it is what gives the device
 * its grade.
 */
async function createClass(sb, { schoolId, levelId, label }) {
  if (!schoolId) return json({ error: 'Choose a school first.' }, 400)
  if (!levelId) return json({ error: 'Choose a grade for this class.' }, 400)

  const clean = String(label || '').trim()
  if (!clean) return json({ error: 'A class needs a name, such as "Nursery A".' }, 400)

  const { data, error } = await sb
    .from('classes')
    .insert({ school_id: schoolId, level_id: levelId, label: clean })
    .select('id, school_id, level_id, label')
    .single()

  if (error) {
    // unique (school_id, level_id, label)
    if (error.code === '23505') {
      return json({ error: `That school already has a class called "${clean}" in that grade.` }, 409)
    }
    return json({ error: reason(error) }, 500)
  }

  return json({ ok: true, class: data })
}

/**
 * Deleting a class does not delete its televisions, for the same reason deleting
 * a teacher does not: the FK is `on delete set null`, so those devices stay
 * activated and fall back to browsing the whole ladder. Losing a class label
 * mid-term must never take a working television off the wall.
 */
async function deleteClass(sb, { classId }) {
  if (!classId) return json({ error: 'classId is required' }, 400)

  const { error } = await sb.from('classes').delete().eq('id', classId)
  if (error) return json({ error: reason(error) }, 500)

  return json({ ok: true })
}

/** Point an already-activated television at a class, or clear it with null. */
async function assignClass(sb, { deviceId, classId }) {
  if (!deviceId) return json({ error: 'deviceId is required' }, 400)

  const { data: device, error: e0 } = await sb
    .from('devices')
    .select('id, school_id')
    .eq('id', deviceId)
    .maybeSingle()
  if (e0) return json({ error: reason(e0) }, 500)
  if (!device) return json({ error: 'That television no longer exists.' }, 404)

  if (classId) {
    if (!device.school_id) {
      return json({ error: 'Activate the television for a school before assigning a class.' }, 400)
    }
    // Same check assignTeacher makes, and for the same reason: the dropdown is
    // filtered client side, and a stale page could otherwise hand a television
    // to another school's class.
    const { data: klass, error: e1 } = await sb
      .from('classes')
      .select('id, label, school_id')
      .eq('id', classId)
      .maybeSingle()
    if (e1) return json({ error: reason(e1) }, 500)
    if (!klass) return json({ error: 'That class no longer exists. Reload the page.' }, 404)
    if (klass.school_id !== device.school_id) {
      return json({ error: `${klass.label} is not a class at that school.` }, 400)
    }
  }

  const { error } = await sb
    .from('devices')
    .update({ class_id: classId || null })
    .eq('id', deviceId)
  if (error) return json({ error: reason(error) }, 500)

  return json({ ok: true })
}
/* ==========================================================================
   Reading the fleet back

   Everything below is read-only apart from renameLesson and saveDocument, and
   every one of these runs on the operator's poll. They are written to be cheap
   and to be honest about what they cannot know, which for this product is the
   harder of the two.
   ========================================================================== */

/** Rows written by a television in the last few minutes count as it playing. */
const PLAYING_WINDOW_MIN = 3

/** How far back the activity panel looks before it says "no activity". */
const ACTIVITY_WINDOW_H = 24

const minutesAgo = m => new Date(Date.now() - m * 60_000).toISOString()

/**
 * What each television is doing, derived from the progress rows it already
 * writes.
 *
 * There is no telemetry in this product and this does not add any. The player
 * upserts a progress row every ten seconds while a lesson is on screen, so a
 * progress row younger than three minutes is proof - not inference - that a
 * television is powered, online, and playing that lesson.
 *
 * What it cannot prove is the negative. A television sitting on the browse
 * screen writes nothing at all, and so is indistinguishable here from one that
 * is switched off or has been carried out of the building. That is why nothing
 * in this response is ever called "offline": the page gets "playing", "last
 * active", or silence, and an operator is left to draw their own conclusion
 * rather than being told a confident lie about a classroom they cannot see.
 *
 * The honest fix for presence is a heartbeat from the TV on resume, which is an
 * Android change. Until that ships this is the whole truth available.
 */
async function nowPlaying(sb) {
  const { data, error } = await sb
    .from('progress')
    .select('device_id, lesson_id, position_sec, completed, updated_at, ' +
            'lessons(title_en, title_np, duration_sec, units(title_en, subjects(name_en)))')
    .not('device_id', 'is', null)
    .gte('updated_at', minutesAgo(ACTIVITY_WINDOW_H * 60))
    .order('updated_at', { ascending: false })
    .limit(600)

  // A database that has not been touched by a television yet is not an error.
  if (error) return json({ error: reason(error) }, 500)

  // One entry per device: the first row wins because the query is already
  // ordered newest first.
  const latest = new Map()
  for (const row of data || []) {
    if (!latest.has(row.device_id)) latest.set(row.device_id, row)
  }

  const playingSince = minutesAgo(PLAYING_WINDOW_MIN)
  const activity = [...latest.values()].map(r => ({
    deviceId: r.device_id,
    lessonId: r.lesson_id,
    title: r.lessons?.title_en || null,
    unit: r.lessons?.units?.title_en || null,
    subject: r.lessons?.units?.subjects?.name_en || null,
    positionSec: r.position_sec,
    durationSec: r.lessons?.duration_sec ?? null,
    completed: r.completed,
    at: r.updated_at,
    playing: r.updated_at >= playingSince,
  }))

  return json({ activity, windowMinutes: PLAYING_WINDOW_MIN })
}

/**
 * The counters across the top of the page.
 *
 * Counts only, and deliberately. With two schools live, anything shaped like a
 * chart is decoration over a sample of two, and a "coverage" percentage
 * computed against 968 lessons of which 113 have video would be a number that
 * flatters nobody and informs nobody.
 *
 * `head: true` means Postgres returns the count without the rows, so this whole
 * function is four cheap counts rather than four table reads.
 */
async function summary(sb) {
  const count = async (table, build = q => q) => {
    const { count: n, error } = await build(
      sb.from(table).select('*', { count: 'exact', head: true })
    )
    return error ? null : n
  }

  const [schools, devices, activated, lessons, withVideo] = await Promise.all([
    count('schools'),
    count('devices'),
    count('devices', q => q.not('claimed_at', 'is', null)),
    count('lessons'),
    count('lessons', q => q.not('video_url', 'is', null)),
  ])

  // Distinct televisions that have played anything today. Counted in JS over a
  // capped read rather than in SQL, because `count(distinct)` is not something
  // PostgREST exposes and a view for it is a migration this does not need yet.
  const { data: active } = await sb
    .from('progress')
    .select('device_id')
    .not('device_id', 'is', null)
    .gte('updated_at', minutesAgo(ACTIVITY_WINDOW_H * 60))
    .limit(2000)

  return json({
    schools, devices, activated, lessons, withVideo,
    activeToday: active ? new Set(active.map(r => r.device_id)).size : null,
  })
}

/* ==========================================================================
   The catalogue
   ========================================================================== */

/**
 * Lessons, for renaming.
 *
 * Defaults to the ones that have a video, and that default is the whole design.
 * There are 968 lessons and 113 of them can actually be watched; the other 855
 * are generated scaffolding for units nobody has filmed. Renaming those is
 * typing into a table for no reader. Renaming the 113 is what makes every
 * screen in the product stop saying PLACEHOLDER, and it is an afternoon's work
 * rather than a content project.
 *
 * The full list is still reachable - `onlyVideo: false` - because the operator
 * is the one who gets to decide that, not this function.
 */
async function listLessons(sb, { search, onlyVideo = true, limit = 400 }) {
  let q = sb
    .from('lessons')
    .select('id, title_en, title_np, sort_order, duration_sec, video_url, ' +
            'units(title_en, sort_order, subjects(name_en, sort_order))')
    .order('title_en')
    .limit(Math.min(Number(limit) || 400, 1000))

  if (onlyVideo) q = q.not('video_url', 'is', null)

  const clean = String(search || '').trim()
  if (clean) q = q.ilike('title_en', `%${clean}%`)

  const { data, error } = await q
  if (error) return json({ error: reason(error) }, 500)

  const lessons = (data || []).map(l => ({
    id: l.id,
    titleEn: l.title_en,
    titleNp: l.title_np,
    sortOrder: l.sort_order,
    durationSec: l.duration_sec,
    hasVideo: !!l.video_url,
    unit: l.units?.title_en || null,
    subject: l.units?.subjects?.name_en || null,
    subjectOrder: l.units?.subjects?.sort_order ?? 999,
    unitOrder: l.units?.sort_order ?? 999,
  })).sort((a, b) =>
    a.subjectOrder - b.subjectOrder ||
    a.unitOrder - b.unitOrder ||
    a.sortOrder - b.sortOrder)

  return json({ lessons })
}

/**
 * Rename one lesson.
 *
 * One row at a time on purpose. A grid that saves 968 rows in a batch is a
 * different and much sharper thing: a single wrong paste rewrites the whole
 * catalogue, and `progress.lesson_id` is `on delete cascade`, so any bulk path
 * that ever deletes and reinserts rather than updating would take every child's
 * resume position with it. Renaming in place, one row, cannot do that.
 *
 * An empty Nepali title clears it. That is safe here because this is a
 * deliberate edit of a named row - unlike a spreadsheet import, where a blank
 * cell means "I did not fill this in" and must never be read as "delete it".
 */
async function renameLesson(sb, { lessonId, titleEn, titleNp }, audit) {
  if (!lessonId) return json({ error: 'lessonId is required' }, 400)

  const clean = String(titleEn ?? '').trim()
  if (!clean) return json({ error: 'A lesson needs an English title.' }, 400)

  const { data: before, error: e0 } = await sb
    .from('lessons')
    .select('id, title_en, title_np')
    .eq('id', lessonId)
    .maybeSingle()
  if (e0) return json({ error: reason(e0) }, 500)
  if (!before) return json({ error: 'That lesson no longer exists. Reload the page.' }, 404)

  // Devanagari from a browser can arrive decomposed depending on the input
  // method; normalising on write keeps a title typed on one machine equal to
  // the same title typed on another, which matters the first time anyone
  // searches for one.
  const np = String(titleNp ?? '').trim().normalize('NFC') || null

  const { error } = await sb
    .from('lessons')
    .update({ title_en: clean.normalize('NFC'), title_np: np })
    .eq('id', lessonId)
  if (error) return json({ error: reason(error) }, 500)

  audit('rename-lesson', lessonId,
    { title_en: before.title_en, title_np: before.title_np },
    { title_en: clean, title_np: np })
  return json({ ok: true })
}

/* ==========================================================================
   Legal documents
   ========================================================================== */

/**
 * The privacy policy and terms the televisions display.
 *
 * Returns `available: false` rather than an error when 0010_app_documents.sql
 * has not been run. Netlify deploys on push and migrations are run by hand, so
 * this page is always live before its table is, and a panel whose every control
 * errors is worse than a panel that says what is missing.
 */
async function listDocuments(sb) {
  const { data, error } = await sb
    .from('app_documents')
    .select('slug, kind, title_en, title_np, body_en, body_np, version, effective_on, published, sort_order, updated_at')
    .order('sort_order')

  if (error) {
    if (isMissingSchema(error)) return json({ available: false, documents: [] })
    return json({ error: reason(error) }, 500)
  }
  return json({ available: true, documents: data || [] })
}

/**
 * Write one document back.
 *
 * Publishing is the sharp edge here, not saving: an unpublished row is a draft
 * the televisions cannot see, and a published one is on the screen of every
 * classroom within a refresh. So the before value goes to the audit table on
 * every save, and the page asks again before the published flag goes on.
 */
async function saveDocument(sb, { slug, titleEn, titleNp, bodyEn, bodyNp, version, effectiveOn, published }, audit) {
  const key = String(slug || '').trim()
  if (!key) return json({ error: 'slug is required' }, 400)

  const titleClean = String(titleEn ?? '').trim()
  const bodyClean = String(bodyEn ?? '').trim()
  if (!titleClean) return json({ error: 'A document needs an English title.' }, 400)
  if (!bodyClean) return json({ error: 'A document with no text would show a blank screen on every television.' }, 400)

  const { data: before, error: e0 } = await sb
    .from('app_documents')
    .select('slug, title_en, body_en, version, published, effective_on')
    .eq('slug', key)
    .maybeSingle()
  if (e0) {
    if (isMissingSchema(e0)) {
      return json({ error: 'Run 0010_app_documents.sql first — this table does not exist yet.' }, 409)
    }
    return json({ error: reason(e0) }, 500)
  }
  if (!before) return json({ error: `There is no document called "${key}".` }, 404)

  const patch = {
    title_en: titleClean.normalize('NFC'),
    title_np: String(titleNp ?? '').trim().normalize('NFC') || null,
    body_en: bodyClean.normalize('NFC'),
    body_np: String(bodyNp ?? '').trim().normalize('NFC') || null,
    version: String(version ?? '').trim() || 'draft',
    effective_on: String(effectiveOn ?? '').trim() || null,
    published: !!published,
    updated_at: new Date().toISOString(),
  }

  const { error } = await sb.from('app_documents').update(patch).eq('slug', key)
  if (error) return json({ error: reason(error) }, 500)

  audit('save-document', key,
    { version: before.version, published: before.published, body_en: before.body_en },
    { version: patch.version, published: patch.published, body_en: patch.body_en })
  return json({ ok: true })
}

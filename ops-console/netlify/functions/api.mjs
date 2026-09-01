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

  const { data: schools, error: e2 } = await sb
    .from('schools')
    .select('id, name, municipality')
    .order('name')
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
async function activate(sb, { code, schoolId, teacherId }) {
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

  const token = `${crypto.randomUUID()}.${crypto.randomUUID()}`
  const expires = new Date()
  expires.setFullYear(expires.getFullYear() + SESSION_YEARS)

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

  return json({ ok: true, deviceId: device.id, code: found.code })
}

async function revoke(sb, { deviceId }) {
  if (!deviceId) return json({ error: 'deviceId is required' }, 400)

  const { error: e1 } = await sb
    .from('sessions')
    .update({ revoked: true })
    .eq('device_id', deviceId)
  if (e1) return json({ error: reason(e1) }, 500)

  const { error: e2 } = await sb
    .from('devices')
    .update({ claimed_at: null, school_id: null, teacher_id: null })
    .eq('id', deviceId)
  if (e2) return json({ error: reason(e2) }, 500)

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
async function createSchool(sb, { name, municipality }) {
  const clean = String(name || '').trim()
  if (!clean) return json({ error: 'A school needs a name.' }, 400)

  const { data, error } = await sb
    .from('schools')
    .insert({ name: clean, municipality: String(municipality || '').trim() || null })
    .select('id, name, municipality')
    .single()
  if (error) return json({ error: reason(error) }, 500)

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

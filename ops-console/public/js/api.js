/**
 * The one way this page talks to anything.
 *
 * Every privileged call goes to /api, which is the Netlify function holding the
 * service_role key. No Supabase key of any kind is present in this page, and
 * none should ever be added to it: on a public URL that key is a full
 * read/write/delete handle on every device, session and school in the project.
 *
 * The passcode is the only credential the browser holds. It is not a Supabase
 * key - it authenticates to our own function, which does the privileged work
 * server side.
 */
import { $ } from './util.js'

const PASS_KEY = 'jsp_ops_pass'

let passcode = localStorage.getItem(PASS_KEY) || ''

export const hasPasscode = () => !!passcode

export function setPasscode(value) {
  passcode = value
  if (value) localStorage.setItem(PASS_KEY, value)
  else localStorage.removeItem(PASS_KEY)
}

export async function api(action, payload = {}) {
  const res = await fetch('/api', {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'x-ops-passcode': passcode },
    body: JSON.stringify({ action, ...payload }),
  })
  const body = await res.json().catch(() => ({ error: `HTTP ${res.status}` }))
  if (!res.ok) throw new Error(body.error || `HTTP ${res.status}`)
  return body
}

/**
 * The status line in the header.
 *
 * One line, top right, for everything the console wants to say back. It is the
 * only feedback channel on the page that is not attached to a particular panel,
 * so it has to stay legible from across a desk: short sentences, and a colour
 * that means something.
 *
 * Two writers, and they were fighting over it. Every action on the fleet panel
 * says what it did - "Added Butwal Model ECED Centre", "Revoked", "Expiry set to
 * 30/11/2026" - and every one of them then asks for a reload, which ends by
 * writing the fleet summary to this same line. The reload wins, because it is
 * last, so in practice the console never confirmed anything it had just done:
 * the message appeared and was gone inside a second, which reads as the click
 * not having registered.
 *
 * So the line has two kinds of writer now. setStatus is the operator's own
 * action and holds the line; setAmbient is the poll describing the fleet and
 * gives way to it, then takes the line back when the message has had its time.
 */
const HOLD = { ok: 6000, err: 12000 }

let holdUntil = 0
let ambient = ''
let handback = null

const paint = (text, kind) => {
  const el = $('#status')
  if (!el) return
  el.textContent = text
  el.className = kind
}

export function setStatus(text, kind = '') {
  paint(text, kind)
  clearTimeout(handback)

  // A message with no colour is a progress note - "Activating…" - which the next
  // thing to happen is supposed to replace. Only a result holds the line.
  if (!HOLD[kind]) { holdUntil = 0; return }

  holdUntil = Date.now() + HOLD[kind]
  // Errors hold twice as long as confirmations, and both hand back rather than
  // sitting there: a stale "Revoked." over a fleet that has changed twice since
  // is worse than no message at all.
  handback = setTimeout(() => { if (ambient) paint(ambient, '') }, HOLD[kind])
}

/** The poll's own description of the fleet. Yields to anything the operator did. */
export function setAmbient(text) {
  ambient = text
  if (Date.now() < holdUntil) return
  paint(text, '')
}

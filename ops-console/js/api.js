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
 */
export function setStatus(text, kind = '') {
  const el = $('#status')
  if (!el) return
  el.textContent = text
  el.className = kind
}

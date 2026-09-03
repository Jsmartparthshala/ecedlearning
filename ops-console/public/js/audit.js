/**
 * The console's record of itself.
 *
 * Every write the console makes now leaves a row in ops_audit, which it did not
 * used to - seven of the thirteen actions wrote nothing, and `delete-teacher`
 * was the worst of them: destructive, it detaches every television that teacher
 * was assigned to, and nothing anywhere said it had happened. An audit table
 * with holes in it is one nobody trusts enough to read, because the absence of
 * a row proves nothing.
 *
 * This is the other half of that. A trail nobody can read is a trail nobody
 * maintains, so it gets a tab rather than a psql query.
 *
 * What it deliberately does not show is a name. One shared passcode cannot tell
 * two operators apart, and inventing an identity column would be worse than
 * having none - it would be believed. The actor hint is a browser and an
 * address, which distinguishes an office laptop from a phone in another
 * district and claims nothing more than that.
 */
import { $, esc, ago, exact } from './util.js'
import { api, setStatus } from './api.js'

let loaded = false
let entries = []

/**
 * How each action reads on the page, and how it is coloured.
 *
 * `made` is green and `gone` is red; everything else is an edit and takes the
 * neutral stripe. The words are the ones an operator used, not the dispatch
 * names - "Activated a television", not "activate".
 */
const ACTIONS = {
  'activate':       ['Activated a television',      'made'],
  'revoke':         ['Revoked a television',        'gone'],
  'set-expiry':     ['Changed an expiry',           ''],
  'create-school':  ['Added a school',              'made'],
  'update-school':  ['Corrected a school',          ''],
  'set-location':   ['Pinned a school on the map',  ''],
  'clear-location': ['Removed a map pin',           'gone'],
  'create-teacher': ['Added a teacher',             'made'],
  'delete-teacher': ['Deleted a teacher',           'gone'],
  'assign-teacher': ['Assigned a teacher',          ''],
  'clear-teacher':  ['Cleared a teacher',           ''],
  'create-class':   ['Added a class',               'made'],
  'delete-class':   ['Deleted a class',             'gone'],
  'assign-class':   ['Assigned a class',            ''],
  'clear-class':    ['Cleared a class',             ''],
  'rename-lesson':  ['Renamed a lesson',            ''],
  'save-document':  ['Saved privacy or terms',      ''],
  'publish-release': ['Published an app update',   'made'],
}

const label = a => ACTIONS[a]?.[0] || a
const tone = a => ACTIONS[a]?.[1] || ''

export function wireAudit() {
  $('#audit-filter').onchange = () => render()
}

export async function openAudit() {
  if (loaded) return
  loaded = true
  await refreshAudit()
}

export async function refreshAudit() {
  const box = $('#audit')
  let data
  try {
    data = await api('list-audit')
  } catch (e) {
    box.innerHTML = `<div class="empty">Could not read the history: ${esc(e.message)}</div>`
    return
  }

  // The table is created by hand and Netlify deploys on push, so this code is
  // always live before the migration is. Say which it is, rather than showing
  // an empty list that reads as "nothing has ever happened".
  if (!data.available) {
    box.innerHTML =
      '<div class="empty">The history table is not in the database yet. ' +
      'Run <b>0011_ops_audit.sql</b> and this fills in from that moment on ' +
      '— it cannot recover changes made before it existed.</div>'
    $('#audit-scope').textContent = ''
    return
  }

  entries = data.entries || []
  fillFilter()
  render()
}

/**
 * Only the actions that actually occur, so the dropdown is a description of
 * this database rather than a list of everything the console can do.
 */
function fillFilter() {
  const sel = $('#audit-filter')
  const keep = sel.value
  const seen = [...new Set(entries.map(e => e.action))].sort(
    (a, b) => label(a).localeCompare(label(b)))

  sel.innerHTML = '<option value="">Everything</option>' +
    seen.map(a => `<option value="${esc(a)}">${esc(label(a))}</option>`).join('')

  // Keep the operator's choice across a refresh if it still applies.
  sel.value = seen.includes(keep) ? keep : ''
}

function render() {
  const box = $('#audit')
  const only = $('#audit-filter').value
  const rows = only ? entries.filter(e => e.action === only) : entries

  if (!entries.length) {
    box.innerHTML = '<div class="empty">Nothing has been changed from this console yet.</div>'
    $('#audit-scope').textContent = ''
    return
  }
  if (!rows.length) {
    box.innerHTML = '<div class="empty">No changes of that kind.</div>'
    $('#audit-scope').textContent = `· 0 of ${entries.length}`
    return
  }

  box.innerHTML = rows.map(row).join('')
  $('#audit-scope').textContent = only
    ? `· ${rows.length} of ${entries.length}`
    : `· ${entries.length} change${entries.length === 1 ? '' : 's'}`
}

function row(e) {
  const change = describe(e.before, e.after)
  return `<div class="audit-row ${tone(e.action)}">
    <div class="audit-when" title="${esc(exact(e.at))}">${esc(ago(e.at))}</div>
    <div class="audit-what">
      <span class="audit-action">${esc(label(e.action))}</span>
      ${e.target ? `<span class="audit-target">· ${esc(e.target)}</span>` : ''}
    </div>
    ${change ? `<div class="audit-change">${change}</div>` : ''}
    ${e.actor_hint ? `<div class="audit-who">${esc(shorten(e.actor_hint))}</div>` : ''}
  </div>`
}

/**
 * What changed, in as few words as the pair allows.
 *
 * A create has no before and a delete has no after, so those print one side.
 * An edit prints only the fields that actually differ - a save-document row
 * whose entire body is unchanged apart from a version number should say so,
 * not reprint the policy.
 */
function describe(before, after) {
  if (!before && !after) return ''

  if (!before) return fields(after).map(([k, v]) => `<b>${esc(k)}</b> ${esc(v)}`).join(' · ')
  if (!after) return 'was ' + fields(before).map(([k, v]) => `<b>${esc(k)}</b> ${esc(v)}`).join(' · ')

  const keys = [...new Set([...Object.keys(before), ...Object.keys(after)])]
  const moved = keys.filter(k => short(before[k]) !== short(after[k]))
  if (!moved.length) return 'no visible change'

  return moved.map(k =>
    `<b>${esc(k)}</b> ${esc(short(before[k]) || '—')} → ${esc(short(after[k]) || '—')}`
  ).join(' · ')
}

const fields = obj => Object.entries(obj || {})
  .filter(([k, v]) => v !== null && v !== '' && k !== 'id')
  .map(([k, v]) => [k, short(v)])

/**
 * A value short enough to sit on one line. A policy body is thousands of
 * characters and there is no reading it here; the point of the row is that a
 * change happened and roughly what to, and the full text is in the editor.
 */
function short(v) {
  if (v === null || v === undefined) return ''
  const s = typeof v === 'object' ? JSON.stringify(v) : String(v)
  return s.length > 90 ? s.slice(0, 90) + '…' : s
}

/** The user agent is a paragraph. The browser name and the address are not. */
function shorten(hint) {
  const [addr, ...rest] = String(hint).split(' · ')
  const ua = rest.join(' · ')
  const browser = /Edg\//.test(ua) ? 'Edge'
    : /Chrome\//.test(ua) ? 'Chrome'
    : /Firefox\//.test(ua) ? 'Firefox'
    : /Safari\//.test(ua) ? 'Safari'
    : ''
  const mobile = /Mobile|Android|iPhone/.test(ua) ? ' on a phone' : ''
  return [addr, browser ? browser + mobile : ''].filter(Boolean).join(' · ')
}

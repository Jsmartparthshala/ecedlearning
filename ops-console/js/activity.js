/**
 * "On now" — what each television is playing.
 *
 * The whole panel is derived from the progress rows the player already writes,
 * once every ten seconds, while a lesson is on screen. Nothing was added to the
 * television to make this work, which matters: the app is signed with a debug
 * key today, so an Android change is not a thing that can be shipped to a
 * classroom this week.
 *
 * The honest limit is stated on the page as well as here. A row younger than a
 * few minutes proves a television is on and playing. Silence proves nothing at
 * all - a television parked on the browse screen writes no rows and looks
 * exactly like one that is switched off - so no wording in this file ever
 * claims a device is offline.
 */
import { $, esc, ago, exact, clock, codeOf } from './util.js'
import { api } from './api.js'
import { deviceById } from './fleet.js'

export async function refreshActivity() {
  const box = $('#activity')
  let data
  try {
    data = await api('now-playing')
  } catch (e) {
    // A failed read is not "nothing is playing". Say which it is, or the panel
    // quietly tells an operator the fleet is idle when it is the network that
    // is down.
    box.innerHTML = `<div class="empty">Could not read activity: ${esc(e.message)}</div>`
    return null
  }

  const rows = data.activity || []
  if (!rows.length) {
    box.innerHTML = '<div class="empty">No lesson has been played in the last 24 hours.</div>'
    $('#activity-scope').textContent = ''
    return 0
  }

  // Playing first, then most recent. An operator scanning this panel is looking
  // for what is live right now; yesterday afternoon can wait below it.
  rows.sort((a, b) => (b.playing - a.playing) || (a.at < b.at ? 1 : -1))

  box.innerHTML = rows.map(row).join('')
  const live = rows.filter(r => r.playing).length
  $('#activity-scope').textContent = live
    ? `· ${live} playing now`
    : `· ${rows.length} seen today`
  return live
}

function row(r) {
  const d = deviceById(r.deviceId)
  const code = d ? codeOf(d.hardware_uuid) : '—'
  const school = d?.schools?.name || ''
  const teacher = d?.teachers?.name || ''

  const title = r.title || 'an untitled lesson'
  const path = [r.subject, r.unit].filter(Boolean).join(' · ')

  // Position is only worth showing while it is moving. On a row from four hours
  // ago "12:04" is a fact about a lesson nobody is watching.
  const at = r.playing && r.positionSec != null
    ? ` <span class="muted">at ${esc(clock(r.positionSec))}${
        r.durationSec ? ' of ' + esc(clock(r.durationSec)) : ''}</span>`
    : ''

  const when = r.playing
    ? '<span class="pill live">playing</span>'
    : `<span title="${esc(exact(r.at))}">last played ${esc(ago(r.at))}</span>`

  return `<div class="play-row${r.playing ? ' live' : ''}">
    <span class="who"><code>${esc(code)}</code>${school ? ' · ' + esc(school) : ''}</span>
    <span class="what">${esc(title)}${r.completed && !r.playing ? ' <span class="muted">(finished)</span>' : ''}${at}</span>
    ${path || teacher ? `<span class="where">${esc([path, teacher].filter(Boolean).join(' · '))}</span>` : ''}
    <span class="when">${when}</span>
  </div>`
}

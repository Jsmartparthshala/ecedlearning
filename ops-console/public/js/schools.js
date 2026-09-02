/**
 * The fleet counted by school.
 *
 * The counters across the top say how many televisions there are. This says
 * where they are and which of them are being used, which is the question the
 * office actually asks: not "how many boxes do we own" but "is Butwal using
 * theirs". Six numbers about the whole fleet cannot answer that and a table of
 * twenty-two rows makes the reader do the grouping by hand.
 *
 * Everything here is derived from data the console already has - the schools and
 * televisions the fleet panel loaded, and the set of devices the activity panel
 * saw playing. It asks the server for nothing, so it costs no invocations
 * against the monthly ceiling. Same arrangement as the map, for the same reason.
 *
 * What it deliberately does not do is call a school inactive. A television
 * writes a progress row while a lesson is playing and writes nothing at all
 * sitting on the browse screen, so silence is not evidence of anything: it is a
 * school that has not played a lesson recently, which might mean the box is in a
 * cupboard or might mean the term has not started. The column is headed by what
 * is actually known and the reader draws their own conclusion.
 */
import { $, esc, ago, exact } from './util.js'
import { state } from './fleet.js'
import { playingNow } from './activity.js'

const DAY = 24 * 60 * 60 * 1000

/** Older than this and the row is marked as quiet - not as off, which is not knowable. */
const QUIET_DAYS = 7

function build() {
  const live = playingNow()

  return state.schools.map(school => {
    const devices = state.devices.filter(d => d.school_id === school.id)
    const seen = devices
      .map(d => (d.last_seen ? new Date(d.last_seen).getTime() : 0))
      .filter(Boolean)

    return {
      school,
      total: devices.length,
      activated: devices.filter(d => d.claimed_at).length,
      playing: devices.filter(d => live.has(d.id)).length,
      // The most recent thing anybody at this school has done, across every
      // television in it. One box left switched off says nothing about a school
      // whose other three are in daily use.
      lastSeen: seen.length ? Math.max(...seen) : null,
      address: [school.municipality, school.province].filter(Boolean).join(', '),
    }
  }).sort((a, b) =>
    // Busiest first, then most recently used, then alphabetical. A school with
    // televisions playing right now is the row the reader came for.
    b.playing - a.playing ||
    b.total - a.total ||
    (b.lastSeen || 0) - (a.lastSeen || 0) ||
    String(a.school.name).localeCompare(String(b.school.name)))
}

function cell(r) {
  if (r.playing) {
    return `<span class="pill live">${r.playing} playing</span>`
  }
  if (!r.lastSeen) {
    return r.activated
      ? '<span class="muted">not seen yet</span>'
      : '<span class="muted">—</span>'
  }
  const quiet = Date.now() - r.lastSeen > QUIET_DAYS * DAY
  return `<span class="${quiet ? 'quiet' : ''}" title="${esc(exact(new Date(r.lastSeen).toISOString()))}">${
    esc(ago(new Date(r.lastSeen).toISOString()))}</span>`
}

export function refreshSchoolStats() {
  const tb = $('#school-stats')
  if (!tb) return

  const rows = build()
  const scope = $('#school-stats-scope')

  if (!rows.length) {
    tb.innerHTML = '<tr><td colspan="5" class="muted">No schools yet.</td></tr>'
    if (scope) scope.textContent = ''
    return
  }

  const tvs = rows.reduce((n, r) => n + r.total, 0)
  const busy = rows.filter(r => r.lastSeen && Date.now() - r.lastSeen <= QUIET_DAYS * DAY).length
  if (scope) {
    scope.textContent = `· ${rows.length} school${rows.length === 1 ? '' : 's'}, ` +
      `${tvs} television${tvs === 1 ? '' : 's'}, ` +
      `${busy} used in the last ${QUIET_DAYS} days`
  }

  tb.innerHTML = rows.map(r => `<tr${r.playing ? ' class="live"' : ''}>
    <td><b>${esc(r.school.name)}</b></td>
    <td class="muted">${esc(r.address) || '<span class="muted">no address</span>'}</td>
    <td class="num">${r.total}</td>
    <td class="num${r.activated < r.total ? ' short' : ''}">${r.activated}</td>
    <td>${cell(r)}</td>
  </tr>`).join('')
}

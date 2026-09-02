/**
 * Where the televisions are.
 *
 * A map of Nepal with a dot for every place the fleet is installed, drawn from
 * data the console already has: the schools and devices the fleet panel loaded,
 * and the set of devices the activity panel saw playing. It asks the server for
 * nothing of its own.
 *
 * It is a working tool before it is a picture. Two rules follow from that:
 *
 *   Nothing is drawn that is not known. A school the placement rules could not
 *   resolve is listed under the map by name, not dropped in the middle of the
 *   country. See places.js - the reasoning lives there.
 *
 *   Dots that share a point are one dot. Several schools in Kathmandu resolve
 *   to the same district centroid, and drawing them stacked would show one
 *   school where there are six and hide five behind the top one. They combine
 *   into a single dot that says how many, and opens to list them.
 */
import { $, esc, ago, exact, codeOf } from './util.js'
import { api, setStatus } from './api.js'
import { state } from './fleet.js'
import { playingNow } from './activity.js'
import { NEPAL } from '../data/nepal.js'
import { locate, PRECISION } from './places.js'

/* ------------------------------------------------------------- projection */

const [W, S, E, N] = NEPAL.bbox

/**
 * Equirectangular, squeezed by the cosine of Nepal's middle latitude.
 *
 * A degree of longitude at 28 degrees north is about 88% of a degree of
 * latitude, and plotting the two as equal would stretch the country sideways
 * into something no Nepali would recognise. Anything fancier than this - a
 * conic, a real projection library - buys accuracy the eye cannot resolve at
 * this size and costs a dependency the console has no build step to install.
 */
const SQUEEZE = Math.cos((((S + N) / 2) * Math.PI) / 180)
const PAD = 16
const VW = 1000
const K = (VW - PAD * 2) / ((E - W) * SQUEEZE)
const VH = (N - S) * K + PAD * 2

const px = lon => PAD + (lon - W) * SQUEEZE * K
const py = lat => PAD + (N - lat) * K

/* ------------------------------------------------------------------ shape */

/** One path per province, built once - the country does not change. */
let outline = ''
function countryPaths() {
  if (outline) return outline
  outline = NEPAL.provinces.map(p => {
    const d = p.rings.map(ring =>
      'M' + ring.map(([lon, lat]) => px(lon).toFixed(1) + ' ' + py(lat).toFixed(1)).join('L') + 'Z'
    ).join('')
    return `<path class="province" d="${d}"><title>${esc(p.name)}</title></path>`
  }).join('')
  return outline
}

/* ------------------------------------------------------------ the grouping */

const RANK = { exact: 0, district: 1, province: 2 }

/**
 * Every school that can be placed, gathered into one dot per point.
 *
 * Returns { dots, unplaced }. A dot carries the schools at that point, their
 * televisions, and how many of those are playing - which is what decides both
 * its size and whether it pulses.
 */
function build() {
  const byPoint = new Map()
  const unplaced = []
  const live = playingNow()

  for (const school of state.schools) {
    const where = locate(school)
    if (!where) { unplaced.push(school); continue }

    // Round to the point, not the school: two schools resolved to the same
    // district centroid are the same dot, and floating point should not be
    // what decides whether they merge.
    const id = where.at[0].toFixed(4) + ',' + where.at[1].toFixed(4)
    let group = byPoint.get(id)
    if (!group) {
      group = { id, at: where.at, precision: where.precision, where: where.where, schools: [] }
      byPoint.set(id, group)
    }
    // A pinned school shares its dot with nobody, but if it ever did, the dot
    // should describe itself by the best evidence in it rather than the first.
    if (RANK[where.precision] < RANK[group.precision]) {
      group.precision = where.precision
      group.where = where.where
    }
    group.schools.push(school)
  }

  for (const group of byPoint.values()) {
    const ids = new Set(group.schools.map(s => s.id))
    group.devices = state.devices.filter(d => ids.has(d.school_id))
    group.playing = group.devices.filter(d => live.has(d.id)).length
  }

  // Biggest last, so the busiest place is drawn on top where it can be clicked.
  const dots = [...byPoint.values()].sort((a, b) => a.devices.length - b.devices.length)
  unplaced.sort((a, b) => String(a.name).localeCompare(String(b.name)))
  return { dots, unplaced }
}

/**
 * Dot radius from the number of televisions, on a square root.
 *
 * Area is what the eye reads as quantity, so the radius goes as the square root
 * and a school with nine televisions looks three times a school with one rather
 * than nine times. The ceiling stops one large school from covering a district.
 *
 * The floor is in viewBox units, which are not pixels: the map is 1000 units
 * wide and draws at whatever width the column gives it, so on a phone every
 * radius here is multiplied by about a third. Nine units is the smallest floor
 * that still leaves a single-television dot worth aiming at once the stylesheet
 * has scaled the dots up on a narrow screen - see the media query that does it,
 * which exists because of this line.
 */
const radius = n => Math.max(9, Math.min(26, 6 + Math.sqrt(Math.max(n, 1)) * 5))

/* ------------------------------------------------------------------ render */

let selected = null
let lastDots = []
let drawn = null

/**
 * What the drawn map depends on, as one string.
 *
 * The poll runs every twenty-five seconds whether or not anything moved, and
 * redrawing the SVG restarts every pulse on it - so all of them jump back into
 * step at once, on a cycle a person watching the map cannot help noticing. Most
 * polls change nothing, so most polls should not redraw. This is what "nothing"
 * means: the same places, the same televisions, the same ones playing.
 */
const signature = dots => dots
  .map(d => d.id + ':' + d.devices.length + ':' + d.playing + ':' + d.precision)
  .join('|')

export function refreshMap() {
  const host = $('#map')
  if (!host || host.closest('[role="tabpanel"]')?.hidden) return

  const { dots, unplaced } = build()
  lastDots = dots

  if (!dots.length) {
    host.innerHTML = unplaced.length
      ? '<p class="empty">No school has an address the map can place yet. They are all listed below.</p>'
      : '<p class="empty">No schools yet. Add one on the Fleet tab and it will appear here.</p>'
    drawn = null
    closeDetail()
    renderUnplaced(unplaced)
    renderLegend(dots, unplaced)
    return
  }

  const now = signature(dots)
  if (now === drawn) {
    // Nothing on the map moved. The counts underneath it are still rewritten,
    // because they are cheap and carry no animation to interrupt.
    renderUnplaced(unplaced)
    renderLegend(dots, unplaced)
    return
  }
  drawn = now

  host.innerHTML = `<svg viewBox="0 0 ${VW} ${VH.toFixed(0)}" class="nepal"
       role="img" aria-label="Map of Nepal showing where televisions are installed">
    <defs>
      <filter id="map-lift" x="-20%" y="-20%" width="140%" height="140%">
        <feDropShadow dx="0" dy="6" stdDeviation="7" flood-color="#0C3462" flood-opacity="0.13"/>
      </filter>
    </defs>
    <g filter="url(#map-lift)">${countryPaths()}</g>
    <g class="dots">${dots.map(marker).join('')}</g>
  </svg>`

  for (const node of host.querySelectorAll('.dot')) {
    node.onclick = () => open(node.dataset.id)
    node.onkeydown = e => {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(node.dataset.id) }
    }
  }

  renderUnplaced(unplaced)
  renderLegend(dots, unplaced)

  // Keep an open panel open across a poll, so a dot being read at the moment
  // the fleet refreshes does not shut in the reader's face.
  if (selected && dots.some(d => d.id === selected)) open(selected, { quiet: true })
  else closeDetail()
}

function marker(d) {
  const x = px(d.at[0]).toFixed(1)
  const y = py(d.at[1]).toFixed(1)
  const r = radius(d.devices.length)
  const n = d.devices.length

  const schools = d.schools.length === 1 ? d.schools[0].name : `${d.schools.length} schools`
  const tvs = `${n} television${n === 1 ? '' : 's'}`
  const playing = d.playing ? `, ${d.playing} playing now` : ''

  // The pulse is drawn under the dot and only when something is actually
  // playing, so a still map means a still fleet.
  const pulse = d.playing ? `<circle class="pulse" cx="${x}" cy="${y}" r="${r}"/>` : ''

  // Playing shows as a gold core inside a navy disc, never as a gold dot. Gold
  // on this cream ground is 1.51:1 and would be a state nobody could see; on
  // the navy of the disc behind it, it is 5.04:1 and reads cleanly.
  //
  // A third of the radius, not a half. The dot is only about fourteen pixels
  // across on the real map, and a core much bigger than this stops reading as
  // a core inside a navy disc and starts reading as a gold dot with a navy
  // rim - which is the thing this whole arrangement exists to avoid.
  const core = d.playing
    ? `<circle class="core" cx="${x}" cy="${y}" r="${(r * 0.34).toFixed(1)}"/>`
    : ''

  return `<g class="dot ${d.precision}${d.playing ? ' live' : ''}" data-id="${esc(d.id)}"
       tabindex="0" role="button"
       aria-label="${esc(schools)}, ${esc(tvs)}${esc(playing)}. ${esc(PRECISION[d.precision].note)}">
    ${pulse}
    <circle class="halo" cx="${x}" cy="${y}" r="${r + 5}"/>
    <circle class="disc" cx="${x}" cy="${y}" r="${r}"/>
    ${core}
    <title>${esc(schools)} — ${esc(tvs)}${esc(playing)}</title>
  </g>`
}

/* ------------------------------------------------------------------ detail */

function open(id, { quiet = false } = {}) {
  const d = lastDots.find(x => x.id === id)
  if (!d) return closeDetail()

  selected = id
  for (const node of $('#map').querySelectorAll('.dot')) {
    node.classList.toggle('on', node.dataset.id === id)
  }

  const p = PRECISION[d.precision]
  const schools = d.schools.map(s => {
    const tvs = d.devices.filter(x => x.school_id === s.id)
    const address = [s.municipality, s.province].filter(Boolean).join(', ')
    return `<div class="map-school">
      <h4>${esc(s.name)}</h4>
      <p class="muted">${esc(address) || 'No address recorded'}</p>
      ${tvs.length
        ? `<ul class="map-tvs">${tvs.map(row).join('')}</ul>`
        : '<p class="empty">No televisions here yet.</p>'}
      ${pinBox(s)}
    </div>`
  }).join('')

  const box = $('#map-detail')
  box.innerHTML = `<div class="map-detail-head">
      <h3>${esc(d.where)}</h3>
      <button type="button" class="ghost" id="map-close">Close</button>
    </div>
    <p class="place-note ${esc(d.precision)}">${esc(p.label)} — ${esc(p.note)}.</p>
    ${schools}`
  box.hidden = false
  $('#map-close').onclick = closeDetail
  if (!quiet) box.focus()
}

function row(d) {
  const live = playingNow().has(d.id)
  const code = codeOf(d.hardware_uuid)
  const who = d.teachers?.name ? ' · ' + esc(d.teachers.name) : ''

  const status = live
    ? '<span class="pill live">playing</span>'
    : !d.claimed_at
      ? '<span class="pill wait">waiting to be activated</span>'
      : d.last_seen
        ? `<span title="${esc(exact(d.last_seen))}">seen ${esc(ago(d.last_seen))}</span>`
        : '<span class="muted">not seen yet</span>'

  return `<li><code>${esc(code)}</code>${who} ${status}</li>`
}

function closeDetail() {
  selected = null
  const box = $('#map-detail')
  if (box) { box.hidden = true; box.innerHTML = '' }
  const host = $('#map')
  if (host) for (const node of host.querySelectorAll('.dot')) node.classList.remove('on')
}

/* ------------------------------------------- the honest bits underneath it */

function renderUnplaced(list) {
  const box = $('#map-unplaced')
  if (!list.length) { box.hidden = true; box.innerHTML = ''; return }

  box.hidden = false
  box.innerHTML = `<h3>Not on the map (${list.length})</h3>
    <p class="muted">The address on these schools did not match a district or a
      province, so they are not drawn rather than guessed at. Correcting the
      municipality on the Fleet tab is usually enough; where it is not, pin the
      school to a coordinate and it appears exactly there.</p>
    <ul class="plain">${list.map(s => {
      const address = [s.municipality, s.province].filter(Boolean).join(', ')
      return `<li>
        <span class="unplaced-name">${esc(s.name)}
          <span class="muted">${esc(address) || 'no address recorded'}</span></span>
        ${pinBox(s)}
      </li>`
    }).join('')}</ul>`
}

function renderLegend(dots, unplaced) {
  // With nothing drawn the map says so in its own words, and "0 televisions
  // across 0 places" beside that is the same news told twice and worse.
  if (!dots.length) {
    $('#map-summary').textContent = ''
    $('#map-legend').innerHTML = unplaced.length
      ? `<li class="none"><span class="key"></span>Not placed <b>${unplaced.length}</b></li>`
      : ''
    return
  }

  const tvs = dots.reduce((n, d) => n + d.devices.length, 0)
  const live = dots.reduce((n, d) => n + d.playing, 0)

  const bits = []
  if (live) bits.push(`<b>${live}</b> playing now`)
  bits.push(`<b>${tvs}</b> television${tvs === 1 ? '' : 's'}`)
  bits.push(`across <b>${dots.length}</b> place${dots.length === 1 ? '' : 's'}`)
  $('#map-summary').innerHTML = bits.join(' · ')

  const counts = { exact: 0, district: 0, province: 0 }
  for (const d of dots) counts[d.precision] += d.schools.length

  const grades = Object.entries(counts)
    .filter(([, n]) => n)
    .map(([k, n]) =>
      `<li class="${k}"><span class="key"></span>${esc(PRECISION[k].label)} <b>${n}</b></li>`)
  if (unplaced.length) {
    grades.push(`<li class="none"><span class="key"></span>Not placed <b>${unplaced.length}</b></li>`)
  }
  $('#map-legend').innerHTML = grades.join('')
}

/** Called the first time the tab is opened. */
export const openMap = () => refreshMap()

/* -------------------------------------------------------------- pinning */

/**
 * The override, wherever a school is named.
 *
 * Most schools are placed by their municipality, which lands the dot in the
 * middle of a district and no nearer. That is usually enough. Where it is not -
 * two schools in one district the office needs to tell apart, or a district
 * whose middle is nowhere near anybody - somebody at the school gate reads a
 * coordinate off their phone and it goes in here.
 *
 * One box, not two. The coordinate arrives as a single pasted string, because
 * that is what "copy coordinates" gives you on every phone map there is, and
 * splitting it across two fields means the operator does the splitting by hand
 * and gets to make a new mistake doing it. Latitude first, which is the order
 * every one of those apps writes it in.
 *
 * A <details> rather than a button and a panel: closed it is one quiet line
 * saying whether the school is pinned, open it is the field, and the keyboard
 * and the screen reader both already know what to do with it.
 */
function pinBox(school) {
  const pinned = Number.isFinite(Number(school.lat)) && Number.isFinite(Number(school.lon)) &&
                 school.lat !== null && school.lon !== null
  const value = pinned ? `${school.lat}, ${school.lon}` : ''
  const id = esc(school.id)

  return `<details class="pin${pinned ? ' on' : ''}">
    <summary>${pinned
      ? `Pinned at <code>${esc(value)}</code>`
      : 'Pin this school exactly'}</summary>
    <div class="pin-body" data-school="${id}">
      <label for="pin-${id}">Latitude, longitude</label>
      <input id="pin-${id}" class="pin-input" type="text" inputmode="decimal"
             autocomplete="off" spellcheck="false"
             placeholder="27.7172, 85.3240" value="${esc(value)}">
      <p class="hint">Paste it straight from a phone map. Latitude first.</p>
      <p class="pin-error" role="alert"></p>
      <div class="pin-actions">
        <button type="button" class="pin-save">Save pin</button>
        ${pinned ? '<button type="button" class="ghost pin-clear">Remove pin</button>' : ''}
      </div>
    </div>
  </details>`
}

/**
 * Read "26.9095, 87.9276" - or with a space, or a slash - into two numbers.
 *
 * The bounds are the same ones the database constraint and the server both
 * apply, checked here as well so that the answer comes back before a round
 * trip rather than after one. They exist to catch a swapped pair or a dropped
 * minus, which are the two mistakes a person actually makes copying a
 * coordinate, and not to have an opinion about a border.
 */
function parsePin(raw) {
  const parts = String(raw || '').trim().split(/[,;/\s]+/).filter(Boolean)
  if (parts.length !== 2) {
    return { error: 'Two numbers, separated by a comma. Latitude first.' }
  }
  const lat = Number(parts[0])
  const lon = Number(parts[1])
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
    return { error: 'That does not read as two numbers.' }
  }
  if (lat < 26 || lat > 31 || lon < 79.5 || lon > 89) {
    return {
      error: lat >= 79.5 && lat <= 89 && lon >= 26 && lon <= 31
        ? 'Those two are the wrong way round — latitude comes first.'
        : 'That point is not in Nepal. Latitude 26 to 31, longitude 80 to 89.',
    }
  }
  return { lat, lon }
}

async function savePin(body, clearing) {
  const schoolId = body.dataset.school
  const input = body.querySelector('.pin-input')
  const err = body.querySelector('.pin-error')
  const buttons = [...body.querySelectorAll('button')]

  let payload = { schoolId, lat: null, lon: null }
  if (!clearing) {
    const parsed = parsePin(input.value)
    if (parsed.error) {
      err.textContent = parsed.error
      input.setAttribute('aria-invalid', 'true')
      input.focus()
      return
    }
    payload = { schoolId, lat: parsed.lat, lon: parsed.lon }
  }

  err.textContent = ''
  input.removeAttribute('aria-invalid')
  buttons.forEach(b => { b.disabled = true })

  try {
    const { school } = await api('set-location', payload)
    // Update the copy the map draws from rather than waiting for the next poll,
    // so the dot moves while the operator is still looking at the field they
    // typed it into. The poll will fetch the same values again in due course.
    const local = state.schools.find(s => s.id === schoolId)
    if (local) { local.lat = school?.lat ?? payload.lat; local.lon = school?.lon ?? payload.lon }

    // Not "back to its district": removing a pin from a school whose address
    // nothing recognises puts it back under the map in the not-placed list, and
    // telling the operator it went to a district it never had would be a small
    // confident lie about the one thing this panel exists to be honest about.
    setStatus(clearing
      ? `${school?.name || 'That school'} is no longer pinned.`
      : `${school?.name || 'That school'} is pinned.`, 'ok')

    // A pin changes which dot the school belongs to, so the map is redrawn and
    // the detail panel closed - the point it was describing may no longer exist.
    closeDetail()
    refreshMap()
  } catch (e) {
    err.textContent = e.message
    buttons.forEach(b => { b.disabled = false })
  }
}

const mapTab = $('#tab-map')
if (mapTab) {
  mapTab.addEventListener('click', e => {
    const save = e.target.closest('.pin-save')
    if (save) return savePin(save.closest('.pin-body'), false)
    const clear = e.target.closest('.pin-clear')
    if (clear) return savePin(clear.closest('.pin-body'), true)
  })

  // Enter in the field is the same as pressing Save. The field is one line and
  // the button is right beside it, and an operator who has just pasted a
  // coordinate reaches for Enter before they reach for the mouse.
  mapTab.addEventListener('keydown', e => {
    if (e.key !== 'Enter' || !e.target.classList.contains('pin-input')) return
    e.preventDefault()
    savePin(e.target.closest('.pin-body'), false)
  })
}

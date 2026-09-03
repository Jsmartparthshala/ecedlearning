/**
 * The page itself: the gate, the tabs, the counters, and the poll.
 *
 * Everything with a domain behind it lives in its own module. What is left here
 * is the shell - getting through the passcode, deciding which panel is on
 * screen, and asking the server for news at a rate that does not cost more than
 * the news is worth.
 */
import { $, $$ } from './util.js'
import { api, hasPasscode, setPasscode, setStatus, setAmbient } from './api.js'
import { refreshFleet, wireFleet } from './fleet.js'
import { refreshActivity } from './activity.js'
import { wireLessons, openLessons } from './lessons.js'
import { wireDocuments, openDocuments } from './documents.js'
import { openMap, refreshMap } from './map.js'
import { refreshSchoolStats } from './schools.js'
import { wireExport } from './export.js'

/**
 * Twenty-five seconds, not five.
 *
 * The old console polled every five seconds, which is roughly half a million
 * function invocations a month from a single tab left open on a desk - against a
 * free tier that allows a quarter of that. Nothing here changes in five seconds
 * that cannot wait twenty-five: a television takes about ten minutes to notice a
 * revocation, and a lesson runs for several minutes.
 */
const POLL_MS = 25_000

let pollTimer = null

/* -------------------------------------------------------------------- gate */

$('#unlock').onclick = unlock
$('#pass').onkeydown = e => { if (e.key === 'Enter') unlock() }

async function unlock() {
  const typed = $('#pass').value.trim()
  if (!typed) return
  setPasscode(typed)
  $('#gate-err').textContent = ''
  $('#gate-mark').classList.add('working')
  try {
    await api('list')
    openApp()
  } catch (e) {
    setPasscode('')
    $('#gate-err').textContent = e.message
    $('#pass').focus()
  }
  $('#gate-mark').classList.remove('working')
}

function openApp() {
  $('#gate').hidden = true
  $('#tabs').hidden = false
  $('#app').hidden = false
  // School first, matching the order of the form and of the job: an operator on
  // an install visit picks the school once and then works through a stack of
  // televisions, so the school is the field that should already be waiting.
  $('#school').focus()

  wireFleet(refresh)
  wireLessons()
  wireDocuments()
  wireExport()
  wireTabs()

  refresh()
  startPolling()
}

// Skip the gate when the passcode is already known and still valid. The gate is
// the loading screen while this is in flight, which is why it carries the mark.
if (hasPasscode()) {
  $('#gate-mark').classList.add('working')
  api('list')
    .then(openApp)
    .catch(() => { setPasscode(''); $('#pass').focus() })
    .finally(() => $('#gate-mark').classList.remove('working'))
}

/* -------------------------------------------------------------------- tabs */

function wireTabs() {
  const tabs = $$('[role="tab"]')

  tabs.forEach((tab, i) => {
    tab.onclick = () => show(tab.dataset.tab)
    // Arrow keys move along the strip, which is what a tablist is expected to
    // do; only the selected tab is in the tab order, so three keystrokes are not
    // spent walking past the sections nobody wanted.
    tab.onkeydown = e => {
      const step = e.key === 'ArrowRight' ? 1 : e.key === 'ArrowLeft' ? -1 : 0
      if (!step) return
      e.preventDefault()
      const next = tabs[(i + step + tabs.length) % tabs.length]
      show(next.dataset.tab)
      next.focus()
    }
  })
}

function show(name) {
  for (const tab of $$('[role="tab"]')) {
    const on = tab.dataset.tab === name
    tab.setAttribute('aria-selected', String(on))
    tab.tabIndex = on ? 0 : -1
  }
  for (const panel of $$('[role="tabpanel"]')) {
    panel.hidden = panel.id !== `tab-${name}`
  }

  // Each panel loads the first time it is opened rather than on page load. The
  // catalogue is 113 rows and the documents are two, and an operator who only
  // ever provisions televisions should not pay for either.
  if (name === 'catalogue') openLessons()
  if (name === 'documents') openDocuments()
  if (name === 'map') openMap()

  // The poll stops while a tab that is not live is showing, so both live tabs
  // are as old as the last time one of them was open. They draw from cache
  // first - nothing flashes empty - and this brings them up to date behind it.
  // Now that On now sits on the map tab, arriving there without this would show
  // whatever was playing when the operator wandered off to the catalogue.
  if ((name === 'fleet' || name === 'map') && !document.hidden) refresh()
}

/**
 * Is a panel on screen that the poll actually feeds?
 *
 * Fleet and Map are two readings of the same live data, so both are worth
 * refreshing. The catalogue and the documents are edited, not watched, and a
 * poll underneath either of them would only risk pulling the ground out from
 * under somebody who is typing.
 */
const onLiveTab = () => ['#tab-fleet', '#tab-map'].some(id => $(id) && !$(id).hidden)

/* --------------------------------------------------------- the refresh loop */

async function refresh() {
  try {
    const fleet = await refreshFleet()
    const live = await refreshActivity()
    await counters()

    // After both, because these two are drawn from the pair together: the
    // schools and televisions from the fleet, and which of them are playing from
    // the activity. The map returns immediately when its tab is not showing.
    refreshSchoolStats()
    refreshMap()

    // One sentence, in the page's one live region. A bare number that silently
    // changes tells a screen reader nothing and tells the eye almost as little.
    if (fleet) {
      const parts = [`${fleet.total} television${fleet.total === 1 ? '' : 's'}`]
      if (fleet.waiting) parts.push(`${fleet.waiting} waiting to be activated`)
      if (live) parts.push(`${live} playing now`)
      setAmbient(parts.join(', ') + '.')
    }
  } catch (e) {
    setStatus(e.message, 'err')
  }
}

async function counters() {
  let s
  try {
    s = await api('summary')
  } catch {
    return   // leave the last good numbers on screen rather than blanking them
  }
  put('#c-schools', s.schools)
  put('#c-devices', s.devices)
  put('#c-activated', s.activated)
  put('#c-active', s.activeToday)
  put('#c-video', s.withVideo)
}

/**
 * A count that could not be read shows a dash, not a zero.
 *
 * Zero is a fact about the fleet. A failed count is a fact about the network,
 * and the two must not look the same to somebody deciding whether to drive to a
 * school.
 */
function put(sel, n) {
  const el = $(sel)
  if (n === null || n === undefined) {
    el.textContent = '—'
    el.className = 'n unknown'
    el.title = 'Could not be read just now'
  } else {
    el.textContent = String(n)
    el.className = 'n'
    el.title = ''
  }
}

/**
 * Poll only while somebody is looking.
 *
 * A console left open on a desk overnight is the normal case, not the unusual
 * one, and every tick of it is a function invocation against a monthly ceiling.
 * A hidden tab learns nothing anybody is reading, so it asks for nothing - and
 * refreshes once on the way back so the first thing seen is current.
 */
function startPolling() {
  const tick = () => { if (!document.hidden && onLiveTab()) refresh() }
  clearInterval(pollTimer)
  pollTimer = setInterval(tick, POLL_MS)

  document.addEventListener('visibilitychange', () => {
    if (!document.hidden && onLiveTab()) refresh()
  })
}

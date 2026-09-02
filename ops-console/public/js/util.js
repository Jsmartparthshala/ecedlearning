/**
 * Small shared helpers.
 *
 * Everything here is used by more than one panel. Anything used by exactly one
 * panel lives in that panel's own module, so that this file stays the place to
 * look for "how does the console say a date" rather than a junk drawer.
 */

export const $ = s => document.querySelector(s)
export const $$ = s => [...document.querySelectorAll(s)]

/**
 * Escape for interpolation into innerHTML.
 *
 * The console renders school and teacher names typed by an operator, and lesson
 * titles that will shortly be typed by one too. None of that is trusted input
 * in the security sense, but a school called "Shree Saraswati <Primary>" should
 * render as its name rather than as a broken tag, and the day someone pastes a
 * title containing an apostrophe should not be the day a table stops drawing.
 */
export const esc = s => String(s ?? '').replace(/[&<>"']/g, c =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]))

/** The code a television shows: the first segment of its UUID, uppercased. */
export const codeOf = uuid => String(uuid || '').split('-')[0].toUpperCase()

const MINUTE = 60_000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR

/**
 * "4 minutes ago", not "02/09/2026, 14:31:07".
 *
 * The operational question this console answers is always about recency - is
 * that television alive, did that lesson just play - and an absolute timestamp
 * makes the reader do the subtraction. Past a week the subtraction stops being
 * interesting and the date is the more useful answer, so it switches back.
 */
export function ago(iso) {
  if (!iso) return '—'
  const then = new Date(iso).getTime()
  if (Number.isNaN(then)) return '—'

  const delta = Date.now() - then
  if (delta < 0) return 'just now'
  if (delta < MINUTE) return 'just now'
  if (delta < HOUR) {
    const m = Math.floor(delta / MINUTE)
    return `${m} minute${m === 1 ? '' : 's'} ago`
  }
  if (delta < DAY) {
    const h = Math.floor(delta / HOUR)
    return `${h} hour${h === 1 ? '' : 's'} ago`
  }
  if (delta < 7 * DAY) {
    const d = Math.floor(delta / DAY)
    return `${d} day${d === 1 ? '' : 's'} ago`
  }
  return new Date(iso).toLocaleDateString()
}

/** Absolute form, for the title attribute behind every relative one. */
export const exact = iso => iso ? new Date(iso).toLocaleString() : ''

/** Seconds to "12:04", for a position inside a lesson. */
export function clock(sec) {
  const n = Math.max(0, Math.floor(Number(sec) || 0))
  const m = Math.floor(n / 60)
  return `${m}:${String(n % 60).padStart(2, '0')}`
}

/** An ISO date for an <input type="date">, or '' when there is nothing to show. */
export function dateInput(iso) {
  if (!iso) return ''
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? '' : d.toISOString().slice(0, 10)
}

/**
 * Is this expiry close enough to be worth saying out loud?
 *
 * Ninety days, because the case this exists for is a term-length loan: an
 * operator wants to notice a trial ending while there is still time to extend
 * it, not on the morning the television stops working.
 */
export const expiringSoon = iso => {
  if (!iso) return false
  const left = new Date(iso).getTime() - Date.now()
  return left > 0 && left < 90 * DAY
}

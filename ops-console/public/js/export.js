/**
 * The fleet as a spreadsheet.
 *
 * The office is asked for a list - by a district, by a funder, by whoever signs
 * off the next batch of boxes - and the answer today is somebody copying rows
 * out of a table by hand. This is one button that writes the same rows to a file
 * Excel opens.
 *
 * It asks the server for nothing. Every column comes from what the Fleet tab has
 * already loaded, so the download costs no invocations against the monthly
 * ceiling: the same arrangement as the map and the by-school table.
 */
import { $, codeOf, exact } from './util.js'
import { state } from './fleet.js'
import { playingNow } from './activity.js'
import { setStatus } from './api.js'

/**
 * One field, quoted.
 *
 * Everything is quoted rather than only the fields that need it. A school name
 * with a comma in it and a school name without one should not be written two
 * different ways, and the day somebody pastes a newline into a name should not
 * be the day the file stops opening.
 *
 * The leading apostrophe is not decoration. A spreadsheet treats a cell opening
 * with =, +, - or @ as a formula, so a school entered as "=Shree" would run as
 * one on the machine that opens the file. Naming that here because it is the
 * kind of line that looks removable.
 */
function field(value) {
  let s = String(value ?? '')
  if (/^[=+\-@\t\r]/.test(s)) s = "'" + s
  return '"' + s.replace(/"/g, '""') + '"'
}

const COLUMNS = [
  'Code', 'School', 'Municipality', 'Province', 'Teacher', 'Class',
  'Status', 'Playing now', 'Activated on', 'Last seen', 'Expires', 'App version',
]

function rows() {
  const live = playingNow()
  const schoolById = new Map(state.schools.map(s => [s.id, s]))
  const classById = new Map(state.classes.map(c => [c.id, c]))
  const levelById = new Map(state.levels.map(l => [l.id, l]))

  // Grouped by school and then by code, because the reader of a spreadsheet
  // scans down a column rather than sorting it, and the question is nearly
  // always about one school.
  return [...state.devices]
    .sort((a, b) => {
      const an = schoolById.get(a.school_id)?.name || ''
      const bn = schoolById.get(b.school_id)?.name || ''
      return an.localeCompare(bn) ||
             codeOf(a.hardware_uuid).localeCompare(codeOf(b.hardware_uuid))
    })
    .map(d => {
      const school = schoolById.get(d.school_id)
      const cls = classById.get(d.class_id)
      return [
        codeOf(d.hardware_uuid),
        school?.name || '',
        school?.municipality || '',
        school?.province || '',
        d.teachers?.name || '',
        cls ? `${cls.label} - ${levelById.get(cls.level_id)?.name_en || ''}`.trim() : '',
        d.claimed_at ? 'Activated' : 'Waiting to be activated',
        live.has(d.id) ? 'Yes' : '',
        exact(d.claimed_at),
        exact(d.last_seen),
        exact(d.expires_at),
        d.app_version || '',
      ]
    })
}

function csv() {
  // CRLF and a byte order mark, both for Excel: without the mark it opens a
  // Devanagari school name as mojibake, which for this office is most of the
  // point of having the file.
  const body = [COLUMNS, ...rows()]
    .map(r => r.map(field).join(','))
    .join('\r\n')
  return '\ufeff' + body + '\r\n'
}

function download() {
  if (!state.devices.length) {
    setStatus('There are no televisions to write down yet.', 'err')
    return
  }

  // The local date, not toISOString(). Nepal is five and three quarter hours
  // ahead of UTC, so a file pulled before six in the morning would otherwise be
  // named for yesterday - and an office filing these by name would never know.
  const d = new Date()
  const stamp = [d.getFullYear(),
                 String(d.getMonth() + 1).padStart(2, '0'),
                 String(d.getDate()).padStart(2, '0')].join('-')
  const blob = new Blob([csv()], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)

  const a = document.createElement('a')
  a.href = url
  a.download = `jsp-televisions-${stamp}.csv`
  document.body.appendChild(a)
  a.click()
  a.remove()
  // Freed on the next turn rather than immediately: revoking it in the same
  // tick can beat the click in some browsers and download nothing at all.
  setTimeout(() => URL.revokeObjectURL(url), 1000)

  const n = state.devices.length
  setStatus(`Wrote ${n} television${n === 1 ? '' : 's'} to a spreadsheet.`, 'ok')
}

export function wireExport() {
  const b = $('#export-fleet')
  if (b) b.onclick = download
}

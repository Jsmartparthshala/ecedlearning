/**
 * The catalogue: giving lessons their real names.
 *
 * Today most rows are called something like "Lesson 3", generated when the
 * ladder was seeded rather than written by anybody. This is the panel that fixes
 * that, and it is scoped by default to the lessons that have a video, because
 * those are the only ones whose name a child ever sees.
 *
 * One row saves at a time, deliberately. The obvious alternative - a grid with a
 * Save All button - is a much sharper tool than it looks: `progress.lesson_id`
 * is `on delete cascade`, so any bulk path that ever deletes and reinserts
 * instead of updating takes every child's resume position with it. Multi-select
 * and bulk edit is standard advice for an admin table and it is the right advice
 * for most of them; here the blast radius is a term's worth of progress, so the
 * slower control is the correct one.
 */
import { $, esc, clock } from './util.js'
import { api, setStatus } from './api.js'

let loaded = false
let searchTimer = null

export function wireLessons() {
  $('#l-search').oninput = () => {
    // Wait for the typing to stop. A query per keystroke is 400 rows down the
    // wire for every letter of a word nobody has finished typing yet.
    clearTimeout(searchTimer)
    searchTimer = setTimeout(load, 300)
  }
  $('#l-all').onchange = load
}

/** Called when the Catalogue tab is opened; loads once, then only on demand. */
export function openLessons() {
  if (!loaded) load()
}

export async function load() {
  const tb = $('#lessons')
  try {
    const { lessons } = await api('list-lessons', {
      search: $('#l-search').value.trim(),
      onlyVideo: !$('#l-all').checked,
    })
    loaded = true
    render(lessons || [])
  } catch (e) {
    tb.innerHTML = `<tr><td colspan="5" class="muted">${esc(e.message)}</td></tr>`
  }
}

function render(lessons) {
  const tb = $('#lessons')
  $('#l-count').textContent = lessons.length
    ? `${lessons.length} lesson${lessons.length === 1 ? '' : 's'}`
    : ''

  if (!lessons.length) {
    tb.innerHTML = `<tr><td colspan="5" class="muted">${
      $('#l-search').value.trim()
        ? 'No lesson matches that.'
        : 'No lessons with a video yet.'}</td></tr>`
    return
  }

  tb.innerHTML = lessons.map(l => `<tr>
    <td class="muted">${esc(l.subject || '—')}</td>
    <td class="muted">${esc(l.unit || '—')}</td>
    <td class="wrap"><span class="cell-edit">
      <input class="title" data-id="${esc(l.id)}" data-f="en" value="${esc(l.titleEn || '')}"
             aria-label="English title"><span class="state-mark"></span></span></td>
    <td class="wrap"><span class="cell-edit">
      <input class="title np" data-id="${esc(l.id)}" data-f="np" value="${esc(l.titleNp || '')}"
             aria-label="Nepali title" placeholder="—" spellcheck="false"><span class="state-mark"></span></span></td>
    <td class="muted">${l.durationSec ? esc(clock(l.durationSec)) : '—'}</td>
  </tr>`).join('')

  tb.querySelectorAll('input.title').forEach(bind)
}

function bind(input) {
  const original = input.value

  input.oninput = () => mark(input, input.value === original ? '' : 'dirty')

  // Enter saves and moves on; Escape puts the original back. Both are what
  // anybody who has ever filled in a spreadsheet will try first.
  input.onkeydown = e => {
    if (e.key === 'Enter') { e.preventDefault(); input.blur() }
    if (e.key === 'Escape') { input.value = original; mark(input, '') }
  }

  // Save on blur rather than on a per-row button: the operator is typing down a
  // column, and a button beside every field is a second thing to hit 113 times.
  input.onblur = () => {
    if (input.value === original) return
    save(input)
  }
}

/**
 * The three states a field can be in, said in a word as well as a colour.
 *
 * Colour alone would leave the same information invisible to a colour-blind
 * operator and to anybody working from a screenshot on a phone in sunlight, and
 * this is a table where "did that save" is the only question being asked.
 */
function mark(input, state) {
  input.classList.remove('dirty', 'saving', 'saved')
  input.removeAttribute('aria-invalid')
  const badge = input.parentElement.querySelector('.state-mark')
  if (badge) badge.className = 'state-mark'
  if (!state) { if (badge) badge.textContent = ''; return }

  // A failure is shown by aria-invalid rather than a class, so the browser and
  // a screen reader agree with the red border about which field went wrong.
  if (state === 'failed') input.setAttribute('aria-invalid', 'true')
  else input.classList.add(state)

  if (badge) {
    badge.classList.add(state)
    badge.textContent = { dirty: '•', saving: '…', saved: '✓', failed: '!' }[state] || ''
    badge.title = {
      dirty: 'Not saved yet',
      saving: 'Saving',
      saved: 'Saved',
      failed: 'Not saved',
    }[state] || ''
  }
}

async function save(input) {
  const row = input.closest('tr')
  const id = input.dataset.id
  const en = row.querySelector('input[data-f="en"]')
  const np = row.querySelector('input[data-f="np"]')

  mark(input, 'saving')
  input.disabled = true
  try {
    await api('rename-lesson', {
      lessonId: id,
      titleEn: en.value.trim(),
      titleNp: np.value.trim(),
    })
    input.disabled = false
    mark(input, 'saved')
    setStatus('Lesson renamed.', 'ok')
    // Rebind against the new value, so this field is now clean and Escape puts
    // back what is actually in the database rather than what was there when the
    // page loaded.
    bind(input)
    // The tick is a confirmation, not a permanent state. Clearing it keeps the
    // column readable after twenty saves.
    setTimeout(() => { if (!input.classList.contains('dirty')) mark(input, '') }, 2500)
  } catch (e) {
    input.disabled = false
    mark(input, 'failed')
    setStatus(e.message, 'err')
  }
}

/**
 * Provisioning: schools, teachers, classes, and the televisions themselves.
 *
 * This is the panel the console was originally built for and the logic is
 * carried over from it rather than rewritten - it has been used on real install
 * visits and the parts that look fussy are usually a lesson from one.
 *
 * New here: an expiry date on activation and on each row, so a television lent
 * to a school for a term stops working by itself instead of relying on somebody
 * remembering to come back and revoke it.
 */
import { $, esc, ago, exact, codeOf, dateInput, expiringSoon } from './util.js'
import { api, setStatus } from './api.js'

/** Everything the fleet panels draw, and what the activity panel names TVs by. */
export const state = {
  schools: [],
  teachers: [],
  devices: [],
  levels: [],
  classes: [],
}

/** A television, by its id. The activity panel has device ids and nothing else. */
export const deviceById = id => state.devices.find(d => d.id === id)

const codeEl = () => $('#code')

/* ------------------------------------------------------------------ loading */

export async function refreshFleet() {
  // The poll must not rebuild a dropdown the operator is currently inside - it
  // would snap the list shut mid-choice and look like a crash. Their own actions
  // call refresh() after the select has lost focus, so this only ever defers the
  // background poll.
  if (document.activeElement?.classList.contains('assign')) return

  const data = await api('list')
  state.schools = data.schools || []
  state.teachers = data.teachers || []
  state.levels = data.levels || []
  state.classes = data.classes || []
  state.devices = data.devices || []

  renderSchools()
  renderTeacherPicker()
  renderTeachers()
  renderLevelPicker()
  renderClasses()
  renderDevices()

  const waiting = state.devices.filter(d => !d.claimed_at).length
  $('#live').textContent = `· updated ${new Date().toLocaleTimeString()}`
  return { total: state.devices.length, waiting }
}

/* ----------------------------------------------------------------- wiring up */

export function wireFleet(onChanged) {
  const refresh = () => onChanged()

  // ---- activation

  codeEl().oninput = () => {
    const el = codeEl()
    const clean = el.value.replace(/[^0-9a-fA-F]/g, '').toUpperCase().slice(0, 8)
    el.value = clean
    $('#found').className = 'found'
    syncGo()

    clearTimeout(lookupTimer)
    if (clean.length === 8) lookupTimer = setTimeout(() => preview(clean), 250)
  }
  codeEl().onkeydown = e => { if (e.key === 'Enter') $('#go').click() }

  // The school drives both the teacher picker and the teachers table, so
  // everything downstream of it is rebuilt whenever it changes.
  $('#school').onchange = () => { syncGo(); renderTeacherPicker(); renderTeachers(); renderClasses() }

  // Validate on blur rather than on submit: an expiry in the past is caught
  // while the operator is still looking at the box that holds it, instead of
  // after they have pressed the button they expected to work.
  $('#expiry').onblur = () => checkExpiry()
  $('#expiry').oninput = () => showFieldError('#expiry', '')

  $('#go').onclick = () => activate(refresh)
  $('#add-school').onclick = () => addSchool(refresh)
  $('#edit-school').onclick = () => editSchool(refresh)

  // ---- teachers

  $('#t-name').oninput = syncGo
  $('#t-name').onkeydown = e => { if (e.key === 'Enter') $('#t-add').click() }
  $('#t-role').onkeydown = e => { if (e.key === 'Enter') $('#t-add').click() }
  $('#t-add').onclick = () => addTeacher(refresh)

  // ---- classes

  $('#c-label').oninput = () => { $('#c-add').disabled = !canAddClass() }
  $('#c-label').onkeydown = e => { if (e.key === 'Enter') $('#c-add').click() }
  $('#c-level').onchange = () => { $('#c-add').disabled = !canAddClass() }
  $('#c-add').onclick = () => addClass(refresh)

  onFleetChange = refresh
}

/** Set once by wireFleet, so the row handlers can ask for a reload. */
let onFleetChange = () => {}
let lookupTimer = null

function syncGo() {
  $('#go').disabled = codeEl().value.length !== 8 || !$('#school').value
  $('#t-add').disabled = !$('#school').value || !$('#t-name').value.trim()
  $('#edit-school').disabled = !$('#school').value
}

/** Put an error beside the field that caused it, not only in the header. */
function showFieldError(sel, message) {
  const input = $(sel)
  const box = $(`${sel}-error`)
  if (box) box.textContent = message
  if (input) {
    if (message) input.setAttribute('aria-invalid', 'true')
    else input.removeAttribute('aria-invalid')
    // The expiry now lives behind a disclosure. An error written into a shut
    // one is an error nobody reads, and the operator would only see the
    // activation refuse to run with no reason given anywhere on the screen.
    if (message) input.closest('details')?.setAttribute('open', '')
  }
}

/** True when the typed expiry is usable. Empty is usable - it means permanent. */
function checkExpiry() {
  const raw = $('#expiry').value
  if (!raw) { showFieldError('#expiry', ''); return true }
  const at = new Date(`${raw}T23:59:59`)
  if (Number.isNaN(at.getTime())) {
    showFieldError('#expiry', 'That is not a date this console can read.')
    return false
  }
  if (at.getTime() < Date.now()) {
    showFieldError('#expiry', 'That date has already passed — the television would drop immediately.')
    return false
  }
  showFieldError('#expiry', '')
  return true
}

/* ---------------------------------------------------------------- activation */

/* Look the code up before committing. Typing one wrong character otherwise
   activates nothing and says nothing, which during a live demo reads as the
   product being broken. */
async function preview(code) {
  const box = $('#found')
  try {
    const { device } = await api('lookup', { code })
    if (device.claimed_at) {
      box.className = 'found show bad'
      box.textContent = `Already activated for ${device.schools?.name || 'a school'}. Revoke it below to move it.`
    } else {
      box.className = 'found show good'
      const seen = device.last_seen ? new Date(device.last_seen).toLocaleString() : 'not yet'
      box.textContent = `Found a television waiting to be activated. Last seen: ${seen}.`
    }
  } catch (e) {
    box.className = 'found show bad'
    box.textContent = e.message
  }
}

async function activate(refresh) {
  if (!checkExpiry()) { $('#expiry').focus(); return }

  const code = codeEl().value
  const schoolId = $('#school').value
  const teacherId = $('#teacher').value || null
  const expiresAt = $('#expiry').value || null

  $('#go').disabled = true
  setStatus('Activating…')
  try {
    await api('activate', { code, schoolId, teacherId, expiresAt })
    setStatus(`${code} is activated.`, 'ok')
    codeEl().value = ''
    $('#found').className = 'found'
    // The expiry is deliberately kept. An operator activating a rack of loaner
    // boxes sets the same date for all of them, and clearing it after each one
    // would make the second television silently permanent.
    await refresh()
    codeEl().focus()
  } catch (e) {
    setStatus(e.message, 'err')
    $('#found').className = 'found show bad'
    $('#found').textContent = e.message
  }
  syncGo()
}

async function addSchool(refresh) {
  const name = prompt('School name')
  if (!name || !name.trim()) return
  // Municipality is what the map places a school by, so it is worth asking for
  // even though nothing requires it. Province is the fallback when the
  // municipality is a ward or a tole the district table has never heard of: with
  // it the school lands somewhere roughly right, and without it the school is
  // not drawn at all and sits in the list under the map instead.
  const municipality = prompt('Municipality — the map places the school by this (optional)') || ''
  const province = prompt('Province (optional)') || ''
  try {
    const { school } = await api('create-school', { name, municipality, province })
    setStatus(`Added ${school.name}.`, 'ok')
    await refresh()
    $('#school').value = school.id
    syncGo()
  } catch (e) {
    setStatus(e.message, 'err')
  }
}

/**
 * Correct a school already on the list.
 *
 * The address is the reason this exists. The map places a school by the
 * municipality written on it, and a school entered as a ward or a tole is not
 * drawn at all - it sits in the list under the map with a note saying to correct
 * the municipality on this tab, which until now was an instruction to press a
 * button that was not there.
 *
 * Prompts, like adding one, and prefilled. This is a rare correction rather than
 * daily work, and a modal that already knows what the field says costs one line
 * where an inline editor costs a panel, a save button and a way to cancel it.
 */
async function editSchool(refresh) {
  const school = state.schools.find(s => s.id === $('#school').value)
  if (!school) return

  const name = prompt('School name', school.name || '')
  if (name === null) return
  if (!name.trim()) { setStatus('A school needs a name.', 'err'); return }

  const municipality = prompt(
    'Municipality — the map places the school by this', school.municipality || '')
  if (municipality === null) return

  const province = prompt('Province', school.province || '')
  if (province === null) return

  try {
    await api('update-school', { schoolId: school.id, name, municipality, province })
    setStatus(`${name.trim()} updated.`, 'ok')
    await refresh()
    $('#school').value = school.id
    syncGo()
  } catch (e) {
    setStatus(e.message, 'err')
  }
}

/* ------------------------------------------------------------------ schools */

function renderSchools() {
  const sel = $('#school')
  const keep = sel.value
  sel.innerHTML = '<option value="">Select school…</option>' + state.schools.map(s =>
    `<option value="${esc(s.id)}">${esc(s.name)}${s.municipality ? ' — ' + esc(s.municipality) : ''}</option>`
  ).join('')
  if (keep) sel.value = keep
}

/* ----------------------------------------------------------------- teachers */

async function addTeacher(refresh) {
  const schoolId = $('#school').value
  const name = $('#t-name').value.trim()
  if (!schoolId || !name) return
  $('#t-add').disabled = true
  try {
    const { teacher } = await api('create-teacher', {
      schoolId, name, role: $('#t-role').value.trim(),
    })
    setStatus(`Added ${teacher.name}.`, 'ok')
    $('#t-name').value = ''
    $('#t-role').value = ''
    await refresh()
    $('#t-name').focus()
  } catch (e) {
    setStatus(e.message, 'err')
  }
  syncGo()
}

/** Teachers at the school currently selected above. */
const teachersHere = () => state.teachers.filter(t => t.school_id === $('#school').value)

function renderTeacherPicker() {
  const sel = $('#teacher')
  const keep = sel.value
  const mine = teachersHere()
  sel.disabled = mine.length === 0
  sel.innerHTML = '<option value="">Whole school</option>' + mine.map(t =>
    `<option value="${esc(t.id)}">${esc(t.name)}${t.role ? ' — ' + esc(t.role) : ''}</option>`
  ).join('')
  if (keep && mine.some(t => t.id === keep)) sel.value = keep
}

function renderTeachers() {
  const tb = $('#teachers')
  const schoolId = $('#school').value
  const school = state.schools.find(s => s.id === schoolId)
  $('#teacher-scope').textContent = school ? `· ${school.name}` : ''

  if (!schoolId) {
    tb.innerHTML = '<tr><td colspan="4" class="muted">Choose a school above.</td></tr>'
    return
  }
  const mine = teachersHere()
  if (!mine.length) {
    tb.innerHTML = '<tr><td colspan="4" class="muted">No teachers yet at this school.</td></tr>'
    return
  }

  tb.innerHTML = mine.map(t => {
    const n = state.devices.filter(d => d.teacher_id === t.id).length
    return `<tr>
      <td>${esc(t.name)}</td>
      <td class="muted">${t.role ? esc(t.role) : '—'}</td>
      <td class="muted">${n || '—'}</td>
      <td class="right"><button class="quiet" data-del-teacher="${esc(t.id)}">Remove</button></td>
    </tr>`
  }).join('')

  tb.querySelectorAll('[data-del-teacher]').forEach(b => b.onclick = () => {
    const t = state.teachers.find(x => x.id === b.dataset.delTeacher)
    const n = state.devices.filter(d => d.teacher_id === b.dataset.delTeacher).length
    // Say what happens to their televisions. "Remove" next to a count of live
    // devices reads as though it might unactivate them, and it does not.
    const warn = n
      ? `\n\nTheir ${n} television(s) stay activated and go back to showing the school name.`
      : ''
    if (!confirm(`Remove ${t?.name || 'this teacher'}?${warn}`)) return
    removeTeacher(b.dataset.delTeacher)
  })
}

async function removeTeacher(teacherId) {
  try {
    await api('delete-teacher', { teacherId })
    setStatus('Teacher removed.', 'ok')
    onFleetChange()
  } catch (e) {
    setStatus(e.message, 'err')
  }
}

/* ------------------------------------------------------------------ classes */

const canAddClass = () => !!$('#school').value && !!$('#c-level').value && !!$('#c-label').value.trim()

async function addClass(refresh) {
  if (!canAddClass()) return
  $('#c-add').disabled = true
  try {
    const { class: created } = await api('create-class', {
      schoolId: $('#school').value,
      levelId: $('#c-level').value,
      label: $('#c-label').value.trim(),
    })
    setStatus(`Added ${created.label}.`, 'ok')
    $('#c-label').value = ''
    await refresh()
    $('#c-label').focus()
  } catch (e) {
    setStatus(e.message, 'err')
  }
  $('#c-add').disabled = !canAddClass()
}

/** Classes at the school currently selected above. */
const classesHere = () => state.classes.filter(c => c.school_id === $('#school').value)

const levelName = id => state.levels.find(l => l.id === id)?.name_en || '—'

function renderLevelPicker() {
  const sel = $('#c-level')
  const keep = sel.value
  sel.innerHTML = state.levels.map(l =>
    `<option value="${esc(l.id)}">${esc(l.name_en)}</option>`
  ).join('')
  if (keep && state.levels.some(l => l.id === keep)) sel.value = keep
}

function renderClasses() {
  // The whole feature is invisible until the migration has been run.
  $('#classes-section').hidden = state.levels.length === 0
  if (!state.levels.length) return

  const tb = $('#classes')
  const schoolId = $('#school').value
  const school = state.schools.find(s => s.id === schoolId)
  $('#class-scope').textContent = school ? `· ${school.name}` : ''
  $('#c-add').disabled = !canAddClass()

  if (!schoolId) {
    tb.innerHTML = '<tr><td colspan="4" class="muted">Choose a school above.</td></tr>'
    return
  }
  const mine = classesHere()
  if (!mine.length) {
    tb.innerHTML = '<tr><td colspan="4" class="muted">No classes yet at this school.</td></tr>'
    return
  }

  tb.innerHTML = mine.map(c => {
    const n = state.devices.filter(d => d.class_id === c.id).length
    return `<tr>
      <td>${esc(c.label)}</td>
      <td class="muted">${esc(levelName(c.level_id))}</td>
      <td class="muted">${n || '—'}</td>
      <td class="right"><button class="quiet" data-del-class="${esc(c.id)}">Remove</button></td>
    </tr>`
  }).join('')

  tb.querySelectorAll('[data-del-class]').forEach(b => b.onclick = () => {
    const c = state.classes.find(x => x.id === b.dataset.delClass)
    const n = state.devices.filter(d => d.class_id === b.dataset.delClass).length
    // Same warning as removing a teacher, and for the same reason: "Remove"
    // beside a count of live televisions reads as though it might switch them
    // off, and it does not.
    const warn = n
      ? `\n\nIts ${n} television(s) stay activated and go back to browsing every grade.`
      : ''
    if (!confirm(`Remove ${c?.label || 'this class'}?${warn}`)) return
    removeClass(b.dataset.delClass)
  })
}

async function removeClass(classId) {
  try {
    await api('delete-class', { classId })
    setStatus('Class removed.', 'ok')
    onFleetChange()
  } catch (e) {
    setStatus(e.message, 'err')
  }
}

/* ----------------------------------------------------------------- devices */

function renderDevices() {
  const tb = $('#devices')
  if (!state.devices.length) {
    tb.innerHTML = '<tr><td colspan="8" class="muted">No televisions yet. Boot the TV app.</td></tr>'
    return
  }

  tb.innerHTML = state.devices.map(d => {
    const claimed = !!d.claimed_at
    return `<tr>
      <td><code>${esc(codeOf(d.hardware_uuid))}</code></td>
      <td>${claimed ? esc(d.schools?.name || '—') : '<span class="muted">—</span>'}</td>
      <td>${claimed ? teacherCell(d) : '<span class="muted">—</span>'}</td>
      <td>${claimed ? classCell(d) : '<span class="muted">—</span>'}</td>
      <td><span class="pill ${claimed ? 'claimed' : 'unclaimed'}">${claimed ? 'activated' : 'waiting'}</span></td>
      <td>${claimed ? expiryCell(d) : '<span class="muted">—</span>'}</td>
      <td class="muted">${esc(d.app_version || '—')}</td>
      <td class="right">${claimed
          ? `<button class="quiet" data-revoke="${esc(d.id)}">Revoke</button>`
          : `<button class="ghost" data-use="${esc(codeOf(d.hardware_uuid))}">Use this code</button>`}</td>
    </tr>`
  }).join('')

  tb.querySelectorAll('[data-assign]').forEach(s => s.onchange = () =>
    assign(s.dataset.assign, s.value || null))
  tb.querySelectorAll('[data-assign-class]').forEach(s => s.onchange = () =>
    assignToClass(s.dataset.assignClass, s.value || null))
  tb.querySelectorAll('[data-expiry]').forEach(i => i.onchange = () =>
    setExpiry(i.dataset.expiry, i.value))
  tb.querySelectorAll('[data-revoke]').forEach(b => b.onclick = () => revoke(b.dataset.revoke))
  // Saves reading a code off a television when it is already on screen here.
  tb.querySelectorAll('[data-use]').forEach(b => b.onclick = () => {
    const el = codeEl()
    el.value = b.dataset.use
    el.dispatchEvent(new Event('input'))
    el.focus()
  })
}

/**
 * The teacher column is an editable dropdown rather than a label plus an edit
 * button. Reassigning a television happens every time a teacher changes class,
 * which is often enough that it should not cost a dialog.
 *
 * Only that device's own school's teachers are offered — a teacher belongs to
 * one school, and the function rejects a cross-school assignment anyway.
 */
function teacherCell(d) {
  const mine = state.teachers.filter(t => t.school_id === d.school_id)
  if (!mine.length) {
    return '<span class="muted">no teachers yet</span>'
  }
  return `<select class="assign" data-assign="${esc(d.id)}" aria-label="Teacher for ${esc(codeOf(d.hardware_uuid))}">
    <option value="">Whole school</option>
    ${mine.map(t => `<option value="${esc(t.id)}" ${t.id === d.teacher_id ? 'selected' : ''}>${
      esc(t.name)}${t.role ? ' — ' + esc(t.role) : ''}</option>`).join('')}
  </select>`
}

/**
 * The class column, same editable-dropdown treatment as the teacher column.
 *
 * This is the control that decides which grade a television opens on, so it is
 * the one piece of the grade expansion an operator actually touches. Only the
 * device's own school's classes are offered; the function rejects a cross-school
 * assignment anyway, because this page can be several seconds stale.
 */
function classCell(d) {
  if (!state.levels.length) return '<span class="muted">—</span>'
  const mine = state.classes.filter(c => c.school_id === d.school_id)
  if (!mine.length) {
    return '<span class="muted">no classes yet</span>'
  }
  return `<select class="assign" data-assign-class="${esc(d.id)}" aria-label="Class for ${esc(codeOf(d.hardware_uuid))}">
    <option value="">Every grade</option>
    ${mine.map(c => `<option value="${esc(c.id)}" ${c.id === d.class_id ? 'selected' : ''}>${
      esc(c.label)} — ${esc(levelName(c.level_id))}</option>`).join('')}
  </select>`
}

/**
 * When this television's session runs out, editable in place.
 *
 * The ten-year default is shown as its date rather than as the word "never",
 * because it is not never - and an operator who believes it is will be surprised
 * exactly once, nine years from now, by a classroom full of children looking at
 * a pairing screen.
 */
function expiryCell(d) {
  const soon = expiringSoon(d.expires_at)
  const warn = soon ? `<span class="pill soon" title="${esc(exact(d.expires_at))}">${esc(ago(d.expires_at).replace(' ago', ''))}</span>` : ''
  return `<span class="cell-edit">
    <input class="assign" type="date" data-expiry="${esc(d.id)}" value="${esc(dateInput(d.expires_at))}"
           aria-label="Expiry for ${esc(codeOf(d.hardware_uuid))}">${warn}
  </span>`
}

async function setExpiry(deviceId, value) {
  if (!value) { onFleetChange(); return }   // clearing the box is not a command
  try {
    await api('set-expiry', { deviceId, expiresAt: value })
    setStatus(`Expiry set to ${new Date(value).toLocaleDateString()}.`, 'ok')
    onFleetChange()
  } catch (e) {
    setStatus(e.message, 'err')
    onFleetChange()   // put the box back to what the server actually holds
  }
}

async function assignToClass(deviceId, classId) {
  try {
    await api('assign-class', { deviceId, classId })
    const c = state.classes.find(x => x.id === classId)
    setStatus(c ? `Assigned to ${c.label}.` : 'Assigned to every grade.', 'ok')
    onFleetChange()
  } catch (e) {
    setStatus(e.message, 'err')
    onFleetChange()   // put the dropdown back to what the server actually holds
  }
}

async function assign(deviceId, teacherId) {
  try {
    await api('assign-teacher', { deviceId, teacherId })
    const t = state.teachers.find(x => x.id === teacherId)
    setStatus(t ? `Assigned to ${t.name}.` : 'Assigned to the school.', 'ok')
    onFleetChange()
  } catch (e) {
    setStatus(e.message, 'err')
    onFleetChange()   // put the dropdown back to what the server actually holds
  }
}

async function revoke(deviceId) {
  // The newlines have to be escaped. A real line break inside a quoted string is
  // a syntax error, and because this used to be one module, that error took the
  // whole page down with it - every handler on the page, including Unlock,
  // silently never bound and the console looked dead rather than broken.
  if (!confirm(
    'Revoke this television?\n\n' +
    'It returns to the pairing screen the next time it checks in — within ' +
    'about ten minutes, or straight away if someone restarts it.'
  )) return
  try {
    await api('revoke', { deviceId })
    setStatus('Revoked.', 'ok')
    onFleetChange()
  } catch (e) {
    setStatus(e.message, 'err')
  }
}

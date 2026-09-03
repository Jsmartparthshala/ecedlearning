/**
 * Publishing a build to the televisions.
 *
 * Every set checks `app_release` once a day on unmetered Wi-Fi, and if the
 * version code there is above its own it downloads the APK and offers to
 * install it. That mechanism has existed since the first build. What has not
 * existed is any way to fill in the row: publishing meant opening the Supabase
 * table editor and typing into a production table by hand.
 *
 * So this panel is small on purpose. It writes one row, it refuses the three
 * mistakes that would strand the fleet, and it shows what the televisions say
 * they are actually running - which is the only way to know whether the last
 * release landed or quietly did nothing.
 */
import { $, esc, ago, exact } from './util.js'
import { api, setStatus } from './api.js'

let loaded = false
let current = null

export function wireRelease() {
  $('#r-save').onclick = publish
  for (const sel of ['#r-name', '#r-code', '#r-url']) {
    $(sel).oninput = () => showFieldError(sel, '')
  }
}

/** Loads once when the tab is first opened, then only after a publish. */
export function openRelease() {
  if (!loaded) load()
}

async function load() {
  loaded = true
  let data
  try {
    data = await api('get-release')
  } catch (e) {
    $('#r-fleet').innerHTML = `<div class="empty">Could not read the release: ${esc(e.message)}</div>`
    return
  }

  if (!data.available) {
    $('#r-missing').hidden = false
    $('#r-editor').hidden = true
    return
  }
  $('#r-missing').hidden = true
  $('#r-editor').hidden = false

  current = data.release
  fill(current)
  renderFleet(data.fleet || [], current)
}

function fill(r) {
  $('#r-name').value = r?.version_name || ''
  $('#r-code').value = r?.version_code ?? ''
  $('#r-url').value = r?.apk_url || ''
  $('#r-notes').value = r?.notes || ''
  $('#r-mandatory').checked = !!r?.mandatory

  $('#r-current').textContent = r
    ? `Published: ${r.version_name} (code ${r.version_code}) · ${ago(r.updated_at)}`
    : 'Nothing has been published yet, so no television has ever been offered an update.'
  if (r?.updated_at) $('#r-current').title = exact(r.updated_at)
}

/**
 * What the sets say they are running.
 *
 * A television writes its own version when it checks in, so this is a report
 * from the field rather than an assumption. A row still on the old code a week
 * after a release is a set that has not had unmetered Wi-Fi, or a set whose
 * teacher keeps declining the system installer - both worth knowing, and
 * neither visible anywhere else.
 */
function renderFleet(fleet, r) {
  const box = $('#r-fleet')
  if (!fleet.length) {
    box.innerHTML = '<div class="empty">No television has been activated yet.</div>'
    return
  }

  const total = fleet.reduce((n, f) => n + f.count, 0)
  box.innerHTML = fleet.map(f => {
    // Matching on the version name, because that is what the television
    // reports; the code is ours and never leaves the build.
    const onLatest = r && f.version && f.version === r.version_name
    const label = f.version ? esc(f.version) : 'not reported'
    return `<span class="ver ${onLatest ? 'ok' : ''}">
      <b>${label}</b> ${f.count}</span>`
  }).join('') + `<span class="ver-total">of ${total} activated</span>`
}

async function publish() {
  if (!validate()) return

  const code = Number($('#r-code').value)
  const url = $('#r-url').value.trim()

  // The two things this cannot check for them, asked once, in the words that
  // describe the actual consequence rather than "are you sure".
  const warning = [
    `Publish ${$('#r-name').value.trim()} (code ${code}) to every activated television?`,
    '',
    'Each set will offer this update to whoever is standing in front of it, ' +
    'within a day, on unmetered Wi-Fi.',
    '',
    'It will only install if it was signed with the same key as the build ' +
    'already on the set. If it was not, every television shows an install ' +
    'error instead, and the only way back is somebody visiting each one.',
  ].join('\n')
  if (!confirm(warning)) return

  $('#r-save').disabled = true
  setStatus('Publishing…')
  try {
    await api('save-release', {
      versionName: $('#r-name').value.trim(),
      versionCode: code,
      apkUrl: url,
      mandatory: $('#r-mandatory').checked,
      notes: $('#r-notes').value.trim(),
    })
    setStatus('Published', 'ok')
    loaded = false
    await load()
  } catch (e) {
    setStatus(e.message, 'bad')
  } finally {
    $('#r-save').disabled = false
  }
}

/**
 * The same three rules the function enforces, checked here so the operator is
 * told before the round trip rather than after it. The function still checks;
 * this is a courtesy, not the guard.
 */
function validate() {
  let ok = true
  const name = $('#r-name').value.trim()
  const code = Number($('#r-code').value)
  const url = $('#r-url').value.trim()

  showFieldError('#r-name', name ? '' : 'The version name the build carries, such as 0.2.0.')
  if (!name) ok = false

  const codeBad = !Number.isInteger(code) || code < 1
    ? 'A whole number, at least 1.'
    : current && code <= current.version_code
      ? `Must be above ${current.version_code}, the code already published — a television only ever moves up.`
      : ''
  showFieldError('#r-code', codeBad)
  if (codeBad) ok = false

  const urlBad = !url
    ? 'Without a link there is nothing for a television to download.'
    : !/^https:\/\//i.test(url)
      ? 'Must start with https://.'
      : ''
  showFieldError('#r-url', urlBad)
  if (urlBad) ok = false

  return ok
}

function showFieldError(sel, message) {
  const input = $(sel)
  const box = $(sel + '-error')
  if (box) box.textContent = message || ''
  if (message) input.setAttribute('aria-invalid', 'true')
  else input.removeAttribute('aria-invalid')
}

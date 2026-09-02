/**
 * Privacy and terms, editable without a release.
 *
 * The reason this exists is that the seed row currently on every television says
 * "PLACEHOLDER — NOT YET A POLICY", and it is marked published, so that is what
 * a parent reads today if they open it. Getting a real policy onto the screens
 * should not require an APK, a keystore and a visit to every school.
 *
 * Publishing is the sharp edge, not saving. The tick is asked for explicitly and
 * every save records the previous text in ops_audit, so a bad edit can be read
 * back rather than reconstructed from memory.
 */
import { $, esc, ago, exact } from './util.js'
import { api, setStatus } from './api.js'

let docs = []
let current = null
let loaded = false

export function wireDocuments() {
  $('#d-save').onclick = save
  // Clear an error as soon as the operator starts fixing what caused it.
  for (const sel of ['#d-title-en', '#d-body-en']) {
    $(sel).oninput = () => showFieldError(sel, '')
    $(sel).onblur = () => validate(false)
  }
}

/** Called when the Documents tab is opened; loads once, then only on demand. */
export function openDocuments() {
  if (!loaded) load()
}

export async function load() {
  try {
    const data = await api('list-documents')
    loaded = true
    $('#doc-missing').hidden = data.available !== false
    docs = data.documents || []
    renderList()
    if (docs.length) select(current && docs.some(d => d.slug === current) ? current : docs[0].slug)
    else $('#doc-editor').hidden = true
  } catch (e) {
    setStatus(e.message, 'err')
  }
}

function renderList() {
  $('#doc-list').innerHTML = docs.map(d => {
    // An unpublished document is a draft nobody can see, and a published one is
    // on the screen of every classroom. That difference is the single most
    // important thing on this tab, so it is said in words in the list itself.
    const live = d.published
      ? '<span class="pill claimed">on the televisions</span>'
      : '<span class="pill unclaimed">draft</span>'
    return `<button class="doc-tab" data-slug="${esc(d.slug)}"
              aria-selected="${d.slug === current}">
      <span>${esc(d.title_en || d.slug)}</span>
      ${live}
      <span class="meta">${d.version ? 'v' + esc(d.version) + ' · ' : ''}${
        d.updated_at ? 'edited ' + esc(ago(d.updated_at)) : 'never edited'}</span>
    </button>`
  }).join('')

  $('#doc-list').querySelectorAll('[data-slug]').forEach(b =>
    b.onclick = () => select(b.dataset.slug))
}

function select(slug) {
  const d = docs.find(x => x.slug === slug)
  if (!d) return
  current = slug
  renderList()

  $('#doc-editor').hidden = false
  $('#d-title-en').value = d.title_en || ''
  $('#d-title-np').value = d.title_np || ''
  $('#d-body-en').value = d.body_en || ''
  $('#d-body-np').value = d.body_np || ''
  $('#d-version').value = d.version || ''
  $('#d-effective').value = d.effective_on || ''
  $('#d-published').checked = !!d.published
  $('#d-state').textContent = d.updated_at
    ? `Last saved ${ago(d.updated_at)}`
    : 'Not edited since it was seeded'
  $('#d-state').title = exact(d.updated_at)
  showFieldError('#d-title-en', '')
  showFieldError('#d-body-en', '')
}

function showFieldError(sel, message) {
  const input = $(sel)
  const box = $(`${sel}-error`)
  if (box) box.textContent = message
  if (message) input.setAttribute('aria-invalid', 'true')
  else input.removeAttribute('aria-invalid')
}

/**
 * Check the two fields that cannot be empty.
 *
 * `focusFirst` is false while the operator is still typing - errors appear next
 * to their own field on blur - and true on save, where the first bad field is
 * also focused so the fix does not require hunting for it.
 */
function validate(focusFirst) {
  let firstBad = null
  const checks = [
    ['#d-title-en', $('#d-title-en').value.trim(), 'A document needs an English title.'],
    ['#d-body-en', $('#d-body-en').value.trim(), 'With no text, every television would show a blank screen.'],
  ]
  for (const [sel, value, message] of checks) {
    if (value) { showFieldError(sel, '') }
    else { showFieldError(sel, message); firstBad = firstBad || sel }
  }
  if (firstBad && focusFirst) $(firstBad).focus()
  return !firstBad
}

async function save() {
  if (!current) return
  if (!validate(true)) return

  const before = docs.find(d => d.slug === current)
  const publishing = $('#d-published').checked && !before?.published

  // Asked once, and only when the answer changes something a parent will read.
  // Saving a draft asks nothing.
  if (publishing && !confirm(
    'Publish this to the televisions?\n\n' +
    'Every classroom shows this text the next time somebody opens Privacy & terms.'
  )) return

  $('#d-save').disabled = true
  setStatus('Saving…')
  try {
    await api('save-document', {
      slug: current,
      titleEn: $('#d-title-en').value,
      titleNp: $('#d-title-np').value,
      bodyEn: $('#d-body-en').value,
      bodyNp: $('#d-body-np').value,
      version: $('#d-version').value,
      effectiveOn: $('#d-effective').value || null,
      published: $('#d-published').checked,
    })
    setStatus($('#d-published').checked ? 'Saved and live on the televisions.' : 'Saved as a draft.', 'ok')
    await load()
  } catch (e) {
    setStatus(e.message, 'err')
  }
  $('#d-save').disabled = false
}

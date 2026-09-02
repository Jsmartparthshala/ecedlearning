/**
 * Turning a school record into a point on the map.
 *
 * The schools table has never held coordinates. It holds `municipality` and
 * `province`, both free text, both typed by whoever did the install visit - so
 * placing a school is a guess, and this module's real job is to be honest about
 * how good a guess it is rather than to always produce one.
 *
 * Three grades of answer, best first:
 *
 *   exact     the school has lat/lon of its own. Somebody pinned it.
 *   district  the municipality named a district, or a town this file knows the
 *             district of. The dot is somewhere in the right district, which
 *             over a country 800km wide is a useful answer.
 *   province  only the province was recognised. The dot is the middle of a
 *             province and could be a hundred kilometres out.
 *
 * and a fourth outcome, which is the important one: nothing matched, so the
 * school is not drawn at all. It goes in a list under the map instead. A dot in
 * roughly the wrong place is worse than no dot, because the map is read as a
 * statement about where the televisions are and nobody re-checks a dot that
 * looks plausible.
 */
import { NEPAL } from '../data/nepal.js'

/** Fold a place name the same way the district table was keyed. */
export const key = s => String(s ?? '')
  .normalize('NFKD')
  .replace(/[\u0300-\u036f]/g, '')
  .toLowerCase()
  .replace(/[^a-z0-9]/g, '')

/**
 * Towns whose name is not their district's name.
 *
 * Most of Nepal's municipalities share a name with something the boundary file
 * already knows, so this list only has to cover the ones that do not - the
 * larger towns an operator is actually likely to type. It is hand-written and
 * deliberately short: every entry is a place whose district is not in question.
 * A town missing from here is not a bug, it is a school that will ask to be
 * pinned, which is the outcome this whole module is designed to arrive at
 * safely.
 */
const TOWNS = {
  butwal: 'Rupandehi',
  bhairahawa: 'Rupandehi',
  siddharthanagar: 'Rupandehi',
  tilottama: 'Rupandehi',
  devdaha: 'Rupandehi',
  birgunj: 'Parsa',
  pokhara: 'Kaski',
  bharatpur: 'Chitwan',
  ratnanagar: 'Chitwan',
  hetauda: 'Makwanpur',
  biratnagar: 'Morang',
  belbari: 'Morang',
  urlabari: 'Morang',
  dharan: 'Sunsari',
  itahari: 'Sunsari',
  inaruwa: 'Sunsari',
  janakpur: 'Dhanusha',
  janakpurdham: 'Dhanusha',
  nepalgunj: 'Banke',
  kohalpur: 'Banke',
  dhangadhi: 'Kailali',
  tikapur: 'Kailali',
  mahendranagar: 'Kanchanpur',
  bhimdatta: 'Kanchanpur',
  damak: 'Jhapa',
  birtamod: 'Jhapa',
  mechinagar: 'Jhapa',
  kakarbhitta: 'Jhapa',
  ghorahi: 'Dang',
  tulsipur: 'Dang',
  lahan: 'Siraha',
  rajbiraj: 'Saptari',
  gaur: 'Rautahat',
  kalaiya: 'Bara',
  malangwa: 'Sarlahi',
  jaleshwar: 'Mahottari',
  bidur: 'Nuwakot',
  banepa: 'Kavrepalanchok',
  dhulikhel: 'Kavrepalanchok',
  panauti: 'Kavrepalanchok',
  kirtipur: 'Kathmandu',
  budhanilkantha: 'Kathmandu',
  tokha: 'Kathmandu',
  chandragiri: 'Kathmandu',
  gokarneshwar: 'Kathmandu',
  tarakeshwar: 'Kathmandu',
  nagarjun: 'Kathmandu',
  shankharapur: 'Kathmandu',
  madhyapurthimi: 'Bhaktapur',
  suryabinayak: 'Bhaktapur',
  changunarayan: 'Bhaktapur',
  godawari: 'Lalitpur',
  mahalaxmi: 'Lalitpur',
  besisahar: 'Lamjung',
  damauli: 'Tanahu',
  bhimad: 'Tanahu',
  waling: 'Syangja',
  putalibazar: 'Syangja',
  beni: 'Myagdi',
  kushma: 'Parbat',
  tansen: 'Palpa',
  sandhikharka: 'Arghakhanchi',
  taulihawa: 'Kapilvastu',
  krishnanagar: 'Kapilvastu',
  ramgram: 'Nawalparasi West',
  kawasoti: 'Nawalpur',
  gaindakot: 'Nawalpur',
  birendranagar: 'Surkhet',
  narayan: 'Dailekh',
  chandannath: 'Jumla',
  gamgadhi: 'Mugu',
  simikot: 'Humla',
  dunai: 'Dolpa',
  musikot: 'Rukum West',
  chaurjahari: 'Rukum West',
  liwang: 'Rolpa',
  dipayal: 'Doti',
  silgadhi: 'Doti',
  amargadhi: 'Dadeldhura',
  dasharathchand: 'Baitadi',
  jayaprithvi: 'Bajhang',
  martadi: 'Bajura',
  mangalsen: 'Achham',
  khandbari: 'Sankhuwasabha',
  myanglung: 'Terhathum',
  phidim: 'Panchthar',
  diktel: 'Khotang',
  salleri: 'Solukhumbu',
  gaighat: 'Udayapur',
  triyuga: 'Udayapur',
  manthali: 'Ramechhap',
  charikot: 'Dolakha',
  bhimeshwar: 'Dolakha',
  chautara: 'Sindhupalchok',
  melamchi: 'Sindhupalchok',
  dhunche: 'Rasuwa',
  nilkantha: 'Dhading',
  dhadingbesi: 'Dhading',
}

const DISTRICT = NEPAL.districts
const PROVINCE = Object.fromEntries(NEPAL.provinces.map(p => [key(p.name), p]))

// The two renamed provinces are still typed by their old numbers by anyone
// working from an older register, so both spellings resolve.
PROVINCE.province1 = PROVINCE.koshi
PROVINCE.province2 = PROVINCE.madhesh
PROVINCE.provinceno1 = PROVINCE.koshi
PROVINCE.provinceno2 = PROVINCE.madhesh
PROVINCE.bagmatipradesh = PROVINCE.bagmati

/**
 * Where does this school go, and how sure are we?
 *
 * Returns null when nothing matched, which the caller must handle by listing
 * the school rather than by picking somewhere for it.
 */
export function locate(school) {
  const lon = Number(school.lon)
  const lat = Number(school.lat)
  if (Number.isFinite(lon) && Number.isFinite(lat) && inNepal(lon, lat)) {
    return { at: [lon, lat], precision: 'exact', where: 'pinned' }
  }

  const town = key(school.municipality)
  if (town) {
    const direct = DISTRICT[town]
    if (direct) return { at: direct.at, precision: 'district', where: direct.name }

    const viaTown = TOWNS[town] && DISTRICT[key(TOWNS[town])]
    if (viaTown) return { at: viaTown.at, precision: 'district', where: viaTown.name }
  }

  const prov = PROVINCE[key(school.province)]
  if (prov) return { at: prov.at, precision: 'province', where: prov.name }

  return null
}

/**
 * Reject a pin that is not in Nepal.
 *
 * A lat/lon typed into a form is one transposition away from being in the
 * Indian Ocean, and a single stray point would stretch the map's own bounds
 * until the country was a smudge. The box is generous - it is here to catch a
 * swapped pair or a missing minus, not to adjudicate a border.
 */
export function inNepal(lon, lat) {
  const [w, s, e, n] = NEPAL.bbox
  return lon >= w - 0.5 && lon <= e + 0.5 && lat >= s - 0.5 && lat <= n + 0.5
}

/** How the legend and the tooltips describe each grade of answer. */
export const PRECISION = {
  exact:    { label: 'Pinned',           note: 'placed exactly, from its own coordinates' },
  district: { label: 'By district',      note: 'somewhere in this district' },
  province: { label: 'By province only', note: 'only the province is known, so this could be far off' },
}

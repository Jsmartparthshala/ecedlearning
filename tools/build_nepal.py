#!/usr/bin/env python3
"""
Turn geoBoundaries' Nepal shapes into the one small file the console draws from.

Run by hand, not at build time - the console has no build step and the source
data changes about once a decade. Everything it needs is committed alongside it,
so nobody has to find this URL again to make a one line change to the map.

    python tools/build_nepal.py <adm1.geojson> <adm2.geojson>

Source: geoBoundaries gbOpen, pinned at commit 9469f09
  ADM1 (7 provinces)  CC BY 3.0 IGO
  ADM2 (77 districts) Public Domain
Attribution for ADM1 is printed on the map itself, which is where a licence has
to be if it is going to be honoured.
"""
import json, math, sys, io, unicodedata

# ---------------------------------------------------------------- simplify

def perpendicular(p, a, b):
    """Distance from p to the segment ab, in degrees. Good enough: over Nepal a
    degree of longitude and one of latitude differ by about 13%, and this only
    decides which points to throw away."""
    (px, py), (ax, ay), (bx, by) = p, a, b
    dx, dy = bx - ax, by - ay
    if dx == 0 and dy == 0:
        return math.hypot(px - ax, py - ay)
    t = max(0, min(1, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def douglas_peucker(points, eps):
    if len(points) < 3:
        return points
    first, last = points[0], points[-1]
    idx, worst = 0, 0.0
    for i in range(1, len(points) - 1):
        d = perpendicular(points[i], first, last)
        if d > worst:
            idx, worst = i, d
    if worst <= eps:
        return [first, last]
    return (douglas_peucker(points[:idx + 1], eps)[:-1] +
            douglas_peucker(points[idx:], eps))


def rings_of(geometry):
    """Every outer ring in a Polygon or MultiPolygon, largest first.

    Holes are dropped. Nepal's districts have none that matter at this scale and
    a hole drawn as an outline would read as a border that is not there."""
    kind, coords = geometry['type'], geometry['coordinates']
    polys = [coords] if kind == 'Polygon' else coords
    out = []
    for poly in polys:
        if poly:
            out.append([(round(x, 6), round(y, 6)) for x, y in poly[0]])
    out.sort(key=lambda r: -abs(shoelace(r)))
    return out


def shoelace(ring):
    s = 0.0
    for i in range(len(ring)):
        x1, y1 = ring[i]
        x2, y2 = ring[(i + 1) % len(ring)]
        s += x1 * y2 - x2 * y1
    return s / 2.0


def centroid(rings):
    """Area weighted centroid of the outer rings, which for a district is a far
    better dot than the middle of its bounding box - Nepal's hill districts are
    long diagonal slivers and a bbox centre can land in the next one."""
    cx = cy = area = 0.0
    for ring in rings:
        a = shoelace(ring)
        if a == 0:
            continue
        sx = sy = 0.0
        for i in range(len(ring)):
            x1, y1 = ring[i]
            x2, y2 = ring[(i + 1) % len(ring)]
            cross = x1 * y2 - x2 * y1
            sx += (x1 + x2) * cross
            sy += (y1 + y2) * cross
        cx += sx / (6 * a) * a
        cy += sy / (6 * a) * a
        area += a
    return (round(cx / area, 4), round(cy / area, 4)) if area else None


# ------------------------------------------------------------------ names

# geoBoundaries still calls the two eastern provinces by the numbers they were
# given before they were named. The office says Koshi and Madhesh, so does this.
PROVINCE_RENAMES = {
    'Province 1': 'Koshi',
    'Province 2': 'Madhesh',
}

# Two shapes in the source carry the wrong district name: there are two called
# Saptari and two called Bara, and no Siraha or Parsa anywhere in the file. The
# shapes themselves are right - it is only the labels that got crossed - and in
# each pair the western one is the one that is misnamed:
#
#   Siraha (86.2E) lies west of Saptari (86.7E)
#   Parsa  (84.8E) lies west of Bara    (85.0E)
#
# So of the two, whichever sits further west takes the missing name. Doing it by
# longitude rather than by feature order means a reordered source release cannot
# quietly swap two districts on the map.
MISLABELLED = {
    'Saptari': 'Siraha',
    'Bara': 'Parsa',
}

# What the office types, against what the file calls it. Everything here is a
# spelling of the same district, not a rename - Chitwan and Chitawan are one
# place. Both spellings resolve, because an operator filling in a school record
# should not have to guess which transliteration a boundary file happened to use.
ALIASES = {
    'Chitawan': ['Chitwan'],
    'Kapilbastu': ['Kapilvastu'],
    'Kabherepalanchok': ['Kavrepalanchok', 'Kavre', 'Kabhrepalanchok'],
    'Dadeidhura': ['Dadeldhura'],
    'Synagja': ['Syangja'],
    'Baijura': ['Bajura'],
    'Makawanpur': ['Makwanpur'],
    'Rukum_E': ['Rukum East', 'Purbi Rukum'],
    'Rukum_W': ['Rukum West', 'Pashchim Rukum', 'Rukum'],
    'Nawalapur': ['Nawalpur', 'Nawalparasi East'],
    'Nawalparasi': ['Nawalparasi West', 'Parasi'],
    'Tanahu': ['Tanahun'],
    'Dhanusha': ['Dhanusa'],
    'Sindhupalchok': ['Sindhupalchowk'],
    'Terhathum': ['Tehrathum'],
    'Solukhumbu': ['Solu Khumbu'],
}

# Two districts have no shape in the source at all - it carries 75 of Nepal's
# 77, and Dailekh and Rupandehi are simply not in the file. Rupandehi is Butwal
# and Bhairahawa and far too populous to leave off a map of where the TVs are,
# so both are placed by hand at the middle of their published extent. They are
# marked `byHand` so the map can say so rather than implying the same
# provenance as the other 75, and so that whoever upgrades the boundary file
# next can check whether these are still needed.
BY_HAND = {
    'Rupandehi': (83.45, 27.55),
    'Dailekh': (81.70, 28.85),
}

# How a district should read on screen, where the file's own spelling is either
# a typo or a database-ism nobody says out loud.
DISPLAY = {
    'Chitawan': 'Chitwan',
    'Kapilbastu': 'Kapilvastu',
    'Kabherepalanchok': 'Kavrepalanchok',
    'Dadeidhura': 'Dadeldhura',
    'Synagja': 'Syangja',
    'Baijura': 'Bajura',
    'Makawanpur': 'Makwanpur',
    'Rukum_E': 'Rukum East',
    'Rukum_W': 'Rukum West',
    'Nawalapur': 'Nawalpur',
    'Nawalparasi': 'Nawalparasi West',
}


def key(name):
    """Fold a place name to something two spellings of it can both match.

    Operators type Nepali place names into a free text box in whatever
    transliteration they learned, so the join has to survive case, accents,
    hyphens, underscores and the trailing space in 'Makwanpur '."""
    s = unicodedata.normalize('NFKD', str(name or ''))
    s = ''.join(c for c in s if not unicodedata.combining(c))
    return ''.join(c for c in s.lower() if c.isalnum())


def fix_labels(features):
    """Give the two misnamed shapes their real names back.

    Returns (name, geometry) pairs. Raises rather than guessing if the source
    ever stops looking the way MISLABELLED assumes, because a silent wrong
    answer here puts a school in the wrong district and nobody would notice."""
    by_name = {}
    for f in features:
        by_name.setdefault(f['properties']['shapeName'].strip(), []).append(f)

    out = []
    for name, group in by_name.items():
        if name not in MISLABELLED:
            if len(group) != 1:
                raise SystemExit('unexpected duplicate district: %s x%d' % (name, len(group)))
            out.append((name, group[0]['geometry']))
            continue
        if len(group) != 2:
            raise SystemExit('expected 2 shapes named %s, found %d' % (name, len(group)))
        west, east = sorted(group, key=lambda f: centroid(rings_of(f['geometry']))[0])
        out.append((MISLABELLED[name], west['geometry']))
        out.append((name, east['geometry']))
    return out


def main(adm1_path, adm2_path, out_path):
    adm1 = json.load(io.open(adm1_path, encoding='utf8'))
    adm2 = json.load(io.open(adm2_path, encoding='utf8'))

    provinces = []
    for f in adm1['features']:
        name = f['properties']['shapeName'].strip()
        name = PROVINCE_RENAMES.get(name, name)
        rings = [douglas_peucker(r, 0.008) for r in rings_of(f['geometry'])]
        rings = [[(round(x, 3), round(y, 3)) for x, y in r] for r in rings if len(r) > 3]
        # The centroid comes off the full-resolution rings, before simplifying.
        # A province is only ever a fallback dot - it is where a school goes
        # when all the console knows is which province it is in - and there is
        # no reason for that dot to inherit the error of a 1km simplification.
        provinces.append({
            'name': name,
            'rings': rings,
            'at': list(centroid(rings_of(f['geometry']))),
        })

    districts = {}
    shapes = fix_labels(adm2['features'])

    known = {n for n, _ in shapes}
    unknown = [n for n in ALIASES if n not in known]
    if unknown:
        raise SystemExit('ALIASES names nothing in the source: %s' % ', '.join(unknown))
    clash = [n for n in BY_HAND if n in known]
    if clash:
        raise SystemExit('BY_HAND duplicates a real shape: %s' % ', '.join(clash))

    for name, geom in shapes:
        c = centroid(rings_of(geom))
        if not c:
            continue
        record = {'name': DISPLAY.get(name, name), 'at': list(c)}
        for spelling in [name] + ALIASES.get(name, []):
            k = key(spelling)
            if k in districts and districts[k]['name'] != record['name']:
                raise SystemExit('alias %r already taken by %s' % (spelling, districts[k]['name']))
            districts[k] = record

    for name, at in BY_HAND.items():
        record = {'name': name, 'at': list(at), 'byHand': True}
        for spelling in [name] + ALIASES.get(name, []):
            districts[key(spelling)] = record

    xs = [x for p in provinces for r in p['rings'] for x, _ in r]
    ys = [y for p in provinces for r in p['rings'] for _, y in r]
    bbox = [min(xs), min(ys), max(xs), max(ys)]

    body = {
        'bbox': [round(v, 3) for v in bbox],
        'provinces': provinces,
        'districts': districts,
    }

    js = io.open(out_path, 'w', encoding='utf8')
    js.write('''/**
 * Nepal, small enough to ship.
 *
 * GENERATED by tools/build_nepal.py - edit that, not this.
 *
 * Seven province outlines and a centroid for each of Nepal's 77 districts,
 * simplified to about a kilometre and rounded to three decimal places. That is
 * far coarser than the source and still finer than a dot on a 900px map, which
 * is the whole budget: the console has no build step, so this file is parsed by
 * every operator on every visit to the Map tab.
 *
 * Districts are keyed by a folded spelling, and most carry several keys, so
 * that a school recorded as Chitwan and one recorded as Chitawan land on the
 * same dot. A district the lookup does not recognise is not placed at all -
 * it is listed as unplaced on the map, which is a question the office can
 * answer, rather than being dropped somewhere plausible and believed.
 *
 * Source: geoBoundaries gbOpen, commit 9469f09.
 *   Provinces (ADM1) are CC BY 3.0 IGO - the attribution is printed on the map.
 *   Districts (ADM2) are public domain.
 *
 * Two shapes in the source are labelled with the wrong district name; the build
 * script names them from their position and says how. See tools/build_nepal.py.
 */
export const NEPAL = ''')
    js.write(json.dumps(body, ensure_ascii=False, separators=(',', ':')))
    js.write('\n')
    js.close()

    import os
    unique = {id(v): v for v in districts.values()}
    print('%s: %d provinces, %d districts under %d spellings, %d placed by hand, %.0f KB'
          % (out_path, len(provinces), len(unique), len(districts),
             sum(1 for v in unique.values() if v.get('byHand')),
             os.path.getsize(out_path) / 1024))


if __name__ == '__main__':
    sys.setrecursionlimit(10000)
    main(sys.argv[1], sys.argv[2], sys.argv[3])

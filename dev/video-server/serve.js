#!/usr/bin/env node
/**
 * Static video server for demos, with the one feature that actually matters:
 * HTTP Range support.
 *
 * ExoPlayer will not seek without `Accept-Ranges: bytes` and correct 206
 * responses, and these files run to 2.4 GB with the moov atom sometimes at the
 * end - meaning without ranges the player downloads the entire file before it
 * can show a frame. Python's http.server does not do ranges, which is why this
 * exists rather than a one-liner.
 *
 * It also parses each MP4's mvhd atom for the real duration, so the catalogue
 * gets true `duration_sec` values. That is not cosmetic: the "X min" label and
 * the resume-progress bars are computed from it, and wrong numbers are visible
 * on screen during the demo.
 *
 *   node serve.js "D:/WORK/YOHO VIDS"          serve, print URLs
 *   node serve.js "D:/WORK/YOHO VIDS" --sql    also write seed SQL
 *
 * Read-only, LAN-only, no auth. A demo aid, not a deployment target.
 */

const http = require('http');
const fs   = require('fs');
const path = require('path');
const os   = require('os');

const ROOT = path.resolve(process.argv[2] || 'D:/WORK/YOHO VIDS');
const PORT = Number(process.env.PORT || 8000);
const WANT_SQL = process.argv.includes('--sql');

const TYPES = {
  '.mp4': 'video/mp4', '.m4v': 'video/mp4', '.mov': 'video/quicktime',
  '.mkv': 'video/x-matroska', '.webm': 'video/webm',
  '.m3u8': 'application/vnd.apple.mpegurl',
  '.jpg': 'image/jpeg', '.png': 'image/png', '.vtt': 'text/vtt',
};

/* ---------------------------------------------------------------- discovery */

function walk(dir, out) {
  out = out || [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      walk(full, out);
    } else {
      const type = TYPES[path.extname(entry.name).toLowerCase()];
      if (type && type.indexOf('video') === 0) out.push(full);
    }
  }
  return out;
}

/**
 * Duration in seconds from the MP4 `mvhd` atom, plus whether the file is
 * faststart (moov before mdat).
 *
 * Atoms are walked with targeted reads rather than by loading the file: these
 * run to 2.4 GB and reading them whole would exhaust memory 72 times over.
 */
function probe(file) {
  let fd;
  try {
    fd = fs.openSync(file, 'r');
    const size = fs.fstatSync(fd).size;
    const head = Buffer.alloc(16);
    let offset = 0, moovAt = -1, mdatAt = -1;

    while (offset < size - 8) {
      if (fs.readSync(fd, head, 0, 16, offset) < 8) break;
      let boxSize = head.readUInt32BE(0);
      const type = head.toString('latin1', 4, 8);
      // size 1 means the real 64-bit size follows the type field; size 0 means
      // "runs to end of file". Both appear in long recordings.
      if (boxSize === 1) boxSize = Number(head.readBigUInt64BE(8));
      else if (boxSize === 0) boxSize = size - offset;
      if (boxSize < 8) break;

      if (type === 'moov' && moovAt < 0) moovAt = offset;
      if (type === 'mdat' && mdatAt < 0) mdatAt = offset;
      if (moovAt >= 0 && mdatAt >= 0) break;
      offset += boxSize;
    }

    let seconds = 0;
    if (moovAt >= 0) {
      // mvhd is the first child of moov in every muxer worth supporting.
      const buf = Buffer.alloc(120);
      fs.readSync(fd, buf, 0, 120, moovAt + 8);
      if (buf.toString('latin1', 4, 8) === 'mvhd') {
        const version = buf.readUInt8(8);
        const timescale = version === 1 ? buf.readUInt32BE(28) : buf.readUInt32BE(20);
        const duration = version === 1
          ? Number(buf.readBigUInt64BE(32))
          : buf.readUInt32BE(24);
        if (timescale > 0) seconds = Math.round(duration / timescale);
      }
    }
    return { seconds, faststart: moovAt >= 0 && (mdatAt < 0 || moovAt < mdatAt) };
  } catch (e) {
    return { seconds: 0, faststart: false };
  } finally {
    if (fd !== undefined) fs.closeSync(fd);
  }
}

/**
 * Guess a subject slug from the filename, matching the slugs in supabase/seed.sql.
 *
 * The recordings are named by grade and day ("UKG DAY 11") far more often than by
 * subject, so this only claims a subject when the name actually says one. The 50-odd
 * unlabelled files return null and are used as filler on any subject: a file called
 * "NURSERY DAY 14" asserts nothing that a tile could contradict, whereas putting a
 * file named MATH under English is the kind of detail someone notices mid-demo.
 */
function classify(rel) {
  const n = rel.toUpperCase();
  if (/SEROFERO|SERO PHERO/.test(n)) return 'serofero';
  if (/MATH|COUNTING|\bNUM\b/.test(n)) return 'maths';
  if (/ENGLISH/.test(n)) return 'english';
  // FULMALA and AA AH are Nepali (फूलमाला, अ आ), not English letters.
  if (/NEPALI|FULMALA|AA AH/.test(n)) return 'nepali';
  return null;
}

function lanAddress() {
  const candidates = [];
  const ifaces = os.networkInterfaces();
  for (const name of Object.keys(ifaces)) {
    for (const a of ifaces[name] || []) {
      if (a.family !== 'IPv4' || a.internal) continue;
      // Host-only adapters from VirtualBox/Docker/Hyper-V answer on 192.168.56.x
      // and link-local sits on 169.254.x. A television will never reach either,
      // so they must not be offered as the address to paste into the catalogue.
      const virtual = /virtual|vmware|vbox|hyper-v|loopback|wsl|docker/i.test(name)
        || a.address.indexOf('192.168.56.') === 0
        || a.address.indexOf('169.254.') === 0;
      candidates.push({ name, address: a.address, virtual });
    }
  }
  const real = candidates.filter(c => !c.virtual);
  const pick = real[0] || candidates[0];
  return { best: pick ? pick.address : '127.0.0.1', all: candidates };
}

/* ------------------------------------------------------------------ serving */

function send(req, res, file) {
  let stat;
  try {
    stat = fs.statSync(file);
  } catch (e) {
    res.writeHead(404).end('Not found');
    return;
  }
  if (!stat.isFile()) { res.writeHead(404).end('Not found'); return; }

  const type = TYPES[path.extname(file).toLowerCase()] || 'application/octet-stream';
  const base = {
    'Content-Type': type,
    // Without this ExoPlayer assumes the server cannot seek and disables the
    // scrub bar entirely.
    'Accept-Ranges': 'bytes',
    'Cache-Control': 'public, max-age=3600',
    'Access-Control-Allow-Origin': '*',
  };

  const range = req.headers.range;
  if (!range) {
    res.writeHead(200, Object.assign({}, base, { 'Content-Length': stat.size }));
    if (req.method === 'HEAD') return res.end();
    const full = fs.createReadStream(file);
    res.on('close', () => full.destroy());
    full.on('error', () => res.destroy());
    return full.pipe(res);
  }

  const m = /^bytes=(\d*)-(\d*)$/.exec(range.trim());
  const unsatisfiable = () => res
    .writeHead(416, Object.assign({}, base, { 'Content-Range': 'bytes */' + stat.size }))
    .end();
  if (!m) return unsatisfiable();

  // A suffix range ("bytes=-500") asks for the LAST n bytes. ExoPlayer uses
  // exactly this to fetch a trailing moov atom, so getting it wrong breaks
  // precisely the non-faststart files that need it most.
  let start, end;
  if (m[1] === '') {
    const suffix = Number(m[2] || 0);
    if (!suffix) return unsatisfiable();
    start = Math.max(0, stat.size - suffix);
    end = stat.size - 1;
  } else {
    start = Number(m[1]);
    end = m[2] === '' ? stat.size - 1 : Math.min(Number(m[2]), stat.size - 1);
  }

  if (!isFinite(start) || !isFinite(end) || start > end || start >= stat.size) {
    return unsatisfiable();
  }

  res.writeHead(206, Object.assign({}, base, {
    'Content-Range': 'bytes ' + start + '-' + end + '/' + stat.size,
    'Content-Length': end - start + 1,
  }));
  if (req.method === 'HEAD') return res.end();

  const stream = fs.createReadStream(file, { start, end });
  // A television that changes lesson mid-download aborts the socket; without
  // this the read stream leaks a file handle every time.
  res.on('close', () => stream.destroy());
  stream.on('error', () => res.destroy());
  stream.pipe(res);
}

/* ----------------------------------------------------------------- start up */

const files = walk(ROOT).sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
process.stdout.write('Probing ' + files.length + ' files for duration...\n');

const meta = files.map(f => {
  const p = probe(f);
  const rel = path.relative(ROOT, f).split(path.sep).join('/');
  return {
    file: f,
    rel,
    bytes: fs.statSync(f).size,
    seconds: p.seconds,
    faststart: p.faststart,
    subject: classify(rel),
  };
});

const encodePath = rel => rel.split('/').map(encodeURIComponent).join('/');

const server = http.createServer((req, res) => {
  if (req.method !== 'GET' && req.method !== 'HEAD') { res.writeHead(405).end(); return; }

  let rel;
  try {
    rel = decodeURIComponent(new URL(req.url, 'http://x').pathname).replace(/^\/+/, '');
  } catch (e) {
    res.writeHead(400).end('Bad request');
    return;
  }

  if (rel === '' || rel === 'index.html') {
    const rows = meta.map(m =>
      '<tr><td><a href="/' + encodePath(m.rel) + '">' + m.rel + '</a></td>' +
      '<td>' + Math.floor(m.seconds / 60) + ' min</td></tr>').join('');
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    return res.end('<h1>' + files.length + ' videos</h1><table>' + rows + '</table>');
  }

  // Path traversal guard: resolve first, then confirm the result is still
  // inside ROOT. Checking the raw string for ".." is not enough on Windows,
  // where separators and short names give several spellings of the same escape.
  const target = path.resolve(ROOT, rel);
  if (target !== ROOT && target.indexOf(ROOT + path.sep) !== 0) {
    res.writeHead(403).end('Forbidden');
    return;
  }

  const started = Date.now();
  res.on('finish', () => process.stdout.write(
    '  ' + res.statusCode + '  ' + rel + '  ' +
    (req.headers.range || 'full') + '  ' + (Date.now() - started) + 'ms\n'));
  send(req, res, target);
});

server.listen(PORT, '0.0.0.0', () => {
  const net = lanAddress();
  const slow = meta.filter(m => !m.faststart);
  const gb = (meta.reduce((n, m) => n + m.bytes, 0) / 1e9).toFixed(1);

  process.stdout.write('\n' +
    '  Serving ' + files.length + ' videos (' + gb + ' GB) from ' + ROOT + '\n\n' +
    '  Television / phone on this network:  http://' + net.best + ':' + PORT + '/\n' +
    '  Android emulator on this machine:    http://10.0.2.2:' + PORT + '/\n\n' +
    '  Interfaces seen:\n' +
    net.all.map(c => '    ' + (c.virtual ? 'skip' : 'USE ') + '  ' +
      c.address + '   ' + c.name).join('\n') + '\n\n');

  // Anything above this needs a good link and a box that can keep up. They are
  // still served - they are just never the first tile in a subject.
  const heavy = meta.filter(m => m.seconds > 0 && (m.bytes * 8 / m.seconds) > 5e6);
  if (heavy.length) {
    process.stdout.write(
      '  ' + heavy.length + ' file(s) above 5 Mbps, sorted to the back of their subject:\n' +
      heavy.map(m => '    ' + (m.bytes * 8 / m.seconds / 1e6).toFixed(1) +
        ' Mbps  ' + m.rel).join('\n') + '\n\n');
  }

  if (slow.length) {
    process.stdout.write(
      '  ' + slow.length + ' of ' + files.length + ' files are not faststart, so the player\n' +
      '  fetches the index from the end of the file before the first frame.\n' +
      '  Ranges are supported here so it works, it is just a slower start.\n\n');
  }

  if (WANT_SQL) {
    const out = path.join(__dirname, 'local_videos.sql');
    fs.writeFileSync(out, buildSql(net.best, PORT, meta));
    process.stdout.write('  Wrote ' + out + '\n' +
                         '  Paste it into the Supabase SQL editor.\n\n');
  }

  process.stdout.write('  Ctrl+C to stop.\n\n');
});

/* ---------------------------------------------------------------------- SQL */

const SLUGS = ['english', 'nepali', 'maths', 'serofero', 'arts'];

function buildSql(host, port, items) {
  const playable = items.filter(m => m.seconds > 0);
  const url = m => 'http://' + host + ':' + port + '/' + encodePath(m.rel);

  // Lightest first. Several classes were exported twice - the same recording at
  // ~8 Mbps and at ~1.4 Mbps, identical durations, one of them 2.4 GB. Ordering
  // by bitrate puts the small copy on lesson 1, which is the tile a demo opens.
  // A cheap box pulling 8 Mbps over classroom Wi-Fi buffers, and it buffers in
  // front of the audience.
  const byBitrate = (a, b) => (a.bytes / a.seconds) - (b.bytes / b.seconds);
  const named   = playable.filter(m => m.subject).sort(byBitrate);
  const generic = playable.filter(m => !m.subject).sort(byBitrate);

  // Each subject gets its own numbered pool: the files that name that subject
  // first, then the unlabelled ones. Ordering matters - the SQL below walks a
  // subject's lessons in order, so entry 0 lands on the first lesson of unit 1,
  // which is where a demo actually goes. The subject-specific footage is what
  // gets seen.
  const rows = [];
  const perSubject = {};
  for (const slug of SLUGS) {
    const mine = named.filter(m => m.subject === slug);
    // Rotate the filler so two subjects do not open on the same video.
    const offset = (SLUGS.indexOf(slug) * 7) % Math.max(generic.length, 1);
    const filler = generic.slice(offset).concat(generic.slice(0, offset));
    const pool = mine.concat(filler);
    perSubject[slug] = { named: mine.length, total: pool.length };
    pool.forEach((m, i) => rows.push(
      "    ('" + slug + "', " + i + ", '" + url(m).replace(/'/g, "''") + "', " + m.seconds + ")"));
  }

  const summary = SLUGS
    .map(s => '--   ' + s.padEnd(9) + ' ' + String(perSubject[s].named).padStart(2) +
              ' named + filler = ' + perSubject[s].total)
    .join('\n');

  return '-- Generated by dev/video-server/serve.js. Points the catalogue at the\n' +
    '-- laptop serving ' + ROOT + ' over the LAN.\n' +
    '--\n' +
    '-- These URLs only resolve on ' + host + "'s network. Re-run the server at the\n" +
    '-- demo venue and re-paste this file: the address changes with the network,\n' +
    '-- and a stale address fails as a bare "Source error" on the television.\n' +
    '--\n' +
    '-- ' + playable.length + ' videos, real durations read from each file\'s mvhd atom.\n' +
    '-- Assigned per subject so a subject opens on footage that matches it:\n' +
    summary + '\n' +
    '\n' +
    'with pool(slug, n, url, secs) as (values\n' +
    rows.join(',\n') + '\n' +
    '),\n' +
    'sized as (\n' +
    '  select slug, count(*) as cnt from pool group by slug\n' +
    '),\n' +
    '-- Whole units, never a scattering: a teacher opening a unit should find\n' +
    '-- every tile live, because a grid that is half dead reads as broken.\n' +
    'target as (\n' +
    '  select l.id, s.slug,\n' +
    '         row_number() over (partition by s.slug\n' +
    '                            order by u.sort_order, l.sort_order) - 1 as rn\n' +
    '  from lessons l\n' +
    '  join units u on u.id = l.unit_id\n' +
    '  join subjects s on s.id = u.subject_id\n' +
    '  where u.sort_order <= 3\n' +
    ')\n' +
    'update lessons\n' +
    "set video_url = p.url, duration_sec = p.secs, codec = 'h264'\n" +
    'from target t\n' +
    'join sized z on z.slug = t.slug\n' +
    'join pool p on p.slug = t.slug and p.n = t.rn % z.cnt\n' +
    'where lessons.id = t.id;\n' +
    '\n' +
    'select s.name_en, count(*) as playable\n' +
    'from lessons l\n' +
    'join units u on u.id = l.unit_id\n' +
    'join subjects s on s.id = u.subject_id\n' +
    'where l.video_url is not null\n' +
    'group by s.name_en order by s.name_en;\n';
}

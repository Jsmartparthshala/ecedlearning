#!/usr/bin/env node
/**
 * Pull one frame out of each recording and write it as a small JPEG.
 *
 *   node make-thumbs.js "D:/WORK/YOHO VIDS" "C:/path/to/ffmpeg.exe"
 *
 * Size is the whole point. The cards were deliberately bitmap-free because a
 * full-screen ARGB_8888 decode is 8.3 MB on a box with 1 GB of RAM, and one per
 * card is what kills it. At 320x180 a decode is about 230 KB, which a Mali-450
 * box can hold a screenful of without trouble.
 *
 * The frame is taken 20% in rather than at 0:00 - these are class recordings and
 * the first seconds are usually a title card, a black frame, or someone still
 * setting up the camera.
 */

const { execFileSync } = require('child_process');
const fs   = require('fs');
const path = require('path');

const ROOT   = path.resolve(process.argv[2] || 'D:/WORK/YOHO VIDS');
const FFMPEG = process.argv[3] || 'ffmpeg';
const OUT    = path.join(__dirname, 'thumbs');

const VIDEO = /\.(mp4|m4v|mov|mkv|webm)$/i;

function walk(dir, out) {
  out = out || [];
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, e.name);
    if (e.isDirectory()) walk(full, out);
    else if (VIDEO.test(e.name)) out.push(full);
  }
  return out;
}

/** Duration via the mvhd atom, so this does not need ffprobe as well. */
function seconds(file) {
  let fd;
  try {
    fd = fs.openSync(file, 'r');
    const size = fs.fstatSync(fd).size;
    const head = Buffer.alloc(16);
    let offset = 0, moovAt = -1;
    while (offset < size - 8) {
      if (fs.readSync(fd, head, 0, 16, offset) < 8) break;
      let boxSize = head.readUInt32BE(0);
      const type = head.toString('latin1', 4, 8);
      if (boxSize === 1) boxSize = Number(head.readBigUInt64BE(8));
      else if (boxSize === 0) boxSize = size - offset;
      if (boxSize < 8) break;
      if (type === 'moov') { moovAt = offset; break; }
      offset += boxSize;
    }
    if (moovAt < 0) return 0;
    const buf = Buffer.alloc(120);
    fs.readSync(fd, buf, 0, 120, moovAt + 8);
    if (buf.toString('latin1', 4, 8) !== 'mvhd') return 0;
    const v = buf.readUInt8(8);
    const scale = v === 1 ? buf.readUInt32BE(28) : buf.readUInt32BE(20);
    const dur   = v === 1 ? Number(buf.readBigUInt64BE(32)) : buf.readUInt32BE(24);
    return scale > 0 ? Math.round(dur / scale) : 0;
  } catch (e) {
    return 0;
  } finally {
    if (fd !== undefined) fs.closeSync(fd);
  }
}

const files = walk(ROOT).sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
let made = 0, skipped = 0, failed = 0;

for (const file of files) {
  const rel = path.relative(ROOT, file).split(path.sep).join('/');
  const dest = path.join(OUT, rel.replace(VIDEO, '') + '.jpg');

  if (fs.existsSync(dest) && fs.statSync(dest).size > 0) { skipped++; continue; }
  fs.mkdirSync(path.dirname(dest), { recursive: true });

  const at = Math.max(1, Math.round(seconds(file) * 0.2));
  try {
    // -ss before -i seeks by keyframe without decoding everything up to that
    // point, which is the difference between a second and several minutes on a
    // 2.4 GB file.
    execFileSync(FFMPEG, [
      '-hide_banner', '-loglevel', 'error',
      '-ss', String(at),
      '-i', file,
      '-frames:v', '1',
      '-vf', 'scale=320:-2',
      '-q:v', '5',
      '-y', dest,
    ], { stdio: ['ignore', 'ignore', 'pipe'], timeout: 60000 });
    made++;
    process.stdout.write('  ok    ' + rel + '\n');
  } catch (e) {
    failed++;
    process.stdout.write('  FAIL  ' + rel + '\n');
  }
}

process.stdout.write('\n  ' + made + ' made, ' + skipped + ' already there, ' + failed + ' failed\n');
process.stdout.write('  Written to ' + OUT + '\n');

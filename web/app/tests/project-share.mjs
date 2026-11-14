// "Share project" / "Import project" test of the built web app, with two browser "friends".
// Friend A makes a beat with an imported loop and packs it; friend B imports the .zip and gets
// the project plus the loop. Importing again never overwrites: the name gets a number and an
// identical sound is reused, while a different sound at the same path gets its own new name.
// Usage: node tests/project-share.mjs <dist folder>
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const root = path.resolve(process.argv[2] || 'build/dist/wasmJs/productionExecutable');
const types = { '.html': 'text/html', '.js': 'text/javascript', '.wasm': 'application/wasm', '.json': 'application/json', '.svg': 'image/svg+xml', '.ttf': 'font/ttf' };
const server = http.createServer((req, res) => {
  let p = decodeURIComponent(req.url.split('?')[0]);
  if (p.endsWith('/')) p += 'index.html';
  fs.readFile(path.join(root, p), (err, data) => {
    if (err) { res.writeHead(404); res.end(); return; }
    res.writeHead(200, { 'content-type': types[path.extname(p)] || 'application/octet-stream' });
    res.end(data);
  });
}).listen(0);
const url = `http://localhost:${server.address().port}/`;

let failed = false;
const fail = (msg) => { console.error('FAIL: ' + msg); failed = true; process.exitCode = 1; };
const check = (ok, msg) => { if (ok) console.log('ok: ' + msg); else fail(msg); };

/** A short mono 16-bit WAV: a sine at [freq] Hz. */
function wav(freq, seconds = 0.5, rate = 44100) {
  const n = Math.floor(seconds * rate);
  const buf = Buffer.alloc(44 + n * 2);
  buf.write('RIFF', 0); buf.writeUInt32LE(36 + n * 2, 4); buf.write('WAVE', 8);
  buf.write('fmt ', 12); buf.writeUInt32LE(16, 16); buf.writeUInt16LE(1, 20); buf.writeUInt16LE(1, 22);
  buf.writeUInt32LE(rate, 24); buf.writeUInt32LE(rate * 2, 28); buf.writeUInt16LE(2, 32); buf.writeUInt16LE(16, 34);
  buf.write('data', 36); buf.writeUInt32LE(n * 2, 40);
  for (let i = 0; i < n; i++) buf.writeInt16LE(Math.round(Math.sin((2 * Math.PI * freq * i) / rate) * 12000), 44 + i * 2);
  return [...buf];
}

const browser = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });

async function friend() {
  const context = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const page = await context.newPage();
  page.on('pageerror', (e) => console.log('page error:', e.message));
  await page.goto(url);
  await page.waitForFunction(() => window.dawShare && typeof window.dawShare.probe === 'function', null, { timeout: 60000 });
  return page;
}
const probe = (page) => page.evaluate(() => JSON.parse(window.dawShare.probe()));
async function waitState(page, want, timeout = 20000) {
  await page.waitForFunction((w) => JSON.parse(window.dawShare.probe()).state === w, want, { timeout });
  return probe(page);
}
async function importZip(page, bytes) {
  await page.evaluate((b) => window.dawShare.importZip(new Int8Array(b)), bytes);
  return waitState(page, 'done');
}
const fileBytes = (page, p) => page.evaluate((p) => { const b = window.dawShare.fileBytes(p); return b ? [...b] : null; }, p);

try {
  // ---------------------------------------------------------------- friend A packs a project
  const a = await friend();
  await a.evaluate(() => window.dawAddBeat());
  await a.evaluate((b) => window.dawShare.importAudio(new Int8Array(b), 'loop.wav'), wav(440));
  await a.waitForFunction(() => JSON.parse(window.dawShare.probe()).audio.length === 1, null, { timeout: 20000 });
  const before = await probe(a);
  const loopPath = before.audio[0];
  const loopA = await fileBytes(a, loopPath);
  check(loopA && loopA.length > 44, `A has the imported loop at ${loopPath}`);

  await a.evaluate(() => window.dawShare.share());
  const ready = await waitState(a, 'ready');
  check(ready.sounds === 1 && ready.missing === 0, `A's share is packed with 1 sound (got ${ready.sounds}, missing ${ready.missing})`);
  const zip = await a.evaluate(() => [...window.dawShare.zipBytes()]);
  check(zip[0] === 0x50 && zip[1] === 0x4b, `the share is a .zip (${zip.length} bytes)`);

  // ---------------------------------------------------------------- friend B imports it
  const b = await friend();
  const b0 = await probe(b);
  let r = await importZip(b, zip);
  check(r.title === 'Project imported', `B imported the project (${r.title})`);
  // B already has its own "New project", so the friend's one must come in beside it.
  const free = (names, base) => { if (!names.includes(base)) return base; let i = 2; while (names.includes(`${base} ${i}`)) i++; return `${base} ${i}`; };
  const first = free(b0.projects, before.open);
  check(r.open === first && first !== before.open, `B opened "${r.open}" beside its own "${before.open}"`);
  check(r.channels === before.channels, `B's copy has A's ${before.channels} channels`);
  check(r.audio.length === 1 && r.audio[0] === loopPath, `B's copy plays the loop from ${r.audio[0]}`);
  const loopB = await fileBytes(b, r.audio[0]);
  check(loopB && loopB.length === loopA.length && loopB.every((v, i) => v === loopA[i]), "B's loop is byte-for-byte A's loop");
  check(r.projects.length === b0.projects.length + 1, `B kept its own projects (${b0.projects.length} → ${r.projects.length})`);

  // Importing the same file again: new name, same sound file reused.
  r = await importZip(b, zip);
  check(r.open !== first && r.projects.length === b0.projects.length + 2, `a second import is "${r.open}", not an overwrite`);
  check(r.audio[0] === loopPath, 'the identical loop is reused, not copied again');

  // A different sound already sitting at the loop's path is kept; the import gets a new file.
  const c = await friend();
  await c.evaluate((bytes) => window.dawShare.importAudio(new Int8Array(bytes), 'loop.wav'), wav(220));
  await c.waitForFunction(() => JSON.parse(window.dawShare.probe()).audio.length === 1, null, { timeout: 20000 });
  const cOwn = (await probe(c)).audio[0];
  const cOwnBytes = await fileBytes(c, cOwn);
  if (cOwn === loopPath) {
    r = await importZip(c, zip);
    check(r.title === 'Project imported', 'C imported despite a clashing file');
    check(r.audio[0] !== loopPath, `the friend's loop went to a new file (${r.audio[0]})`);
    const kept = await fileBytes(c, loopPath);
    check(kept.length === cOwnBytes.length && kept.every((v, i) => v === cOwnBytes[i]), "C's own loop wasn't overwritten");
    const theirs = await fileBytes(c, r.audio[0]);
    check(theirs && theirs.length === loopA.length && theirs.every((v, i) => v === loopA[i]), "the new file holds the friend's loop");
  } else {
    console.log(`skip clash check: C's loop landed at ${cOwn}, not ${loopPath}`);
  }

  // Something that isn't a project.
  r = await importZip(b, [...Buffer.from('not a zip at all')]);
  check(r.title !== 'Project imported', `a random file is refused (${r.title})`);
} catch (e) {
  fail(e.stack || String(e));
} finally {
  await browser.close();
  server.close();
}
if (!failed) console.log('project share test passed');

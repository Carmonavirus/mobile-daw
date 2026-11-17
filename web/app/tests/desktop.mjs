// Mouse and keyboard controls of the built web app on a laptop-sized window: Space plays, F-keys
// switch screens, clicks place notes, right click deletes, Ctrl+A / Delete / Ctrl+Z, Ctrl+wheel zoom.
// Usage: node tests/desktop.mjs <dist folder>
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

const fail = (msg) => { console.error('FAIL: ' + msg); process.exitCode = 1; };
const browser = await chromium.launch({
  args: ['--autoplay-policy=no-user-gesture-required', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'],
});
const viewport = { width: 1280, height: 800 };
const page = await browser.newPage({ viewport });
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
await page.goto(url);
await page.waitForFunction(() => typeof window.dawProbe === 'function', null, { timeout: 60000 });
await page.waitForFunction(() => JSON.parse(window.dawProbe()).textW !== undefined, null, { timeout: 30000 });
const probe = async () => JSON.parse(await page.evaluate(() => window.dawProbe()));
const settle = () => page.waitForTimeout(250);
const check = (ok, msg, state) => { if (!ok) fail(msg + ' ' + JSON.stringify(state)); else console.log('ok:', msg); };

await page.evaluate(() => window.dawAddBeat());
await page.mouse.move(viewport.width / 2, viewport.height / 2);
await settle();
let s = await probe();
check(s.desktop, 'a mouse marks the app as desktop', s);

await page.keyboard.press('Space');
await page.waitForTimeout(400);
s = await probe();
check(s.playing, 'Space starts playback', s);
await page.keyboard.press('Space');
await settle();
s = await probe();
check(!s.playing, 'Space stops playback', s);

await page.keyboard.press('F7');
await settle();
s = await probe();
check(s.tab === 'PIANO', 'F7 opens the piano roll', s);
const notes0 = s.notes;

// Click an empty spot on the grid (well right of the kick's notes, which sit on the first beats).
const spot = { x: viewport.width * 0.82, y: viewport.height * 0.42 };
await page.mouse.click(spot.x, spot.y);
await settle();
s = await probe();
check(s.notes === notes0 + 1, 'left click places a note', s);
await page.mouse.click(spot.x, spot.y, { button: 'right' });
await settle();
s = await probe();
check(s.notes === notes0, 'right click deletes it', s);

await page.keyboard.press('Control+KeyA');
await settle();
s = await probe();
check(s.sel === notes0, 'Ctrl+A selects every note', s);
await page.keyboard.press('Delete');
await settle();
s = await probe();
check(s.notes === 0 && s.sel === 0, 'Delete removes the selection', s);
await page.keyboard.press('Control+KeyZ');
await settle();
s = await probe();
check(s.notes === notes0, 'Ctrl+Z brings them back', s);

// Ctrl+A, Ctrl+C, Ctrl+V: pasted right after the copied notes.
await page.keyboard.press('Control+KeyA');
await page.keyboard.press('Control+KeyC');
await page.keyboard.press('Control+KeyV');
await settle();
s = await probe();
check(s.notes === notes0 * 2, 'Ctrl+C / Ctrl+V duplicates the notes', s);

// Ctrl+drag a box over the whole grid selects notes; Esc clears it.
await page.keyboard.press('Escape');
await page.keyboard.down('Control');
await page.mouse.move(viewport.width * 0.06, viewport.height * 0.3);
await page.mouse.down();
await page.mouse.move(viewport.width * 0.6, viewport.height * 0.5, { steps: 8 });
await page.mouse.move(viewport.width * 0.99, viewport.height * 0.75, { steps: 8 });
await page.mouse.up();
await page.keyboard.up('Control');
await settle();
s = await probe();
check(s.sel > 0, 'Ctrl+drag box-selects notes', s);
await page.keyboard.press('Escape');
await settle();
s = await probe();
check(s.sel === 0, 'Esc clears the selection', s);

await page.keyboard.press('F1');
await settle();
s = await probe();
check(s.help, 'F1 opens the shortcuts list', s);
await page.keyboard.press('Escape');
await settle();
s = await probe();
check(!s.help, 'Esc closes it', s);

await page.keyboard.press('Control+KeyK');
await settle();
s = await probe();
check(s.typing === 'PLAY', 'Ctrl+K turns the typing keyboard on', s);
await page.keyboard.press('Control+KeyK');

await page.keyboard.press('F5');
await settle();
s = await probe();
check(s.tab === 'PLAYLIST', 'F5 opens the playlist', s);
const bar0 = s.barDp;
await page.mouse.move(viewport.width / 2, viewport.height / 2);
await page.keyboard.down('Control');
await page.mouse.wheel(0, -100);
await page.keyboard.up('Control');
await settle();
s = await probe();
check(s.barDp > bar0, 'Ctrl+wheel zooms the playlist in', { before: bar0, ...s });
console.log('zoom', bar0, '->', s.barDp);

// Playlist: a click on an empty cell adds a pattern clip, a right click deletes it.
const cell = { x: viewport.width * 0.5, y: 200 };
for (let y = 150; y < 400 && (await probe()).clips === 0; y += 20) {
  cell.y = y;
  await page.mouse.click(cell.x, y);
  await settle();
  await page.keyboard.press('Escape');
  await settle();
}
s = await probe();
check(s.clips === 1, 'a click adds a pattern clip', s);
await page.mouse.click(cell.x, cell.y, { button: 'right' });
await settle();
s = await probe();
check(s.clips === 0, 'a right click deletes it', s);

// Channel rack zoom: Ctrl+wheel and Ctrl+= / Ctrl+- change how many bars it shows (1-4).
await page.keyboard.press('F6');
await settle();
s = await probe();
check(s.tab === 'RACK', 'F6 opens the channel rack', s);
await page.keyboard.press('Control+Minus');
await settle();
await page.keyboard.press('Control+Minus');
await settle();
s = await probe();
const rack0 = s.rackBars;
check(rack0 >= 2, 'Ctrl+- zooms the rack out (more bars)', s);
await page.mouse.move(viewport.width / 2, viewport.height * 0.55);
await page.keyboard.down('Control');
await page.mouse.wheel(0, -100);
await page.keyboard.up('Control');
await settle();
s = await probe();
check(s.rackBars === rack0 - 1, 'Ctrl+wheel up zooms the rack in', { before: rack0, ...s });
await page.keyboard.press('Control+Equal');
await settle();
s = await probe();
check(s.rackBars === Math.max(1, rack0 - 2), 'Ctrl+= zooms the rack in', { before: rack0, ...s });

// Master volume knob at the top left, beside the metronome: the wheel over it changes the level.
const master0 = s.master;
let moved = false;
for (const x of [70, 78, 86, 94]) {
  for (const y of [22, 28, 34]) {
    await page.mouse.move(x, y);
    await page.mouse.wheel(0, 300);
    await settle();
    s = await probe();
    if (s.master < master0) { moved = true; break; }
  }
  if (moved) break;
}
check(moved, 'the wheel over the top VOL knob turns the master volume down', { before: master0, ...s });

// Export: Cancel stops it promptly (the page keeps responding), and a finished export can be closed.
await page.evaluate(() => window.dawExport.open());
await settle();
await page.evaluate(() => window.dawExport.start());
await page.waitForTimeout(150);
s = await probe();
check(s.exportBusy && s.export === 'Running', 'export starts', s);
const cancelAt = Date.now();
await page.evaluate(() => window.dawExport.cancel());
await page.waitForFunction(() => !JSON.parse(window.dawProbe()).exportBusy, null, { timeout: 5000 }).catch(() => {});
s = await probe();
check(!s.exportBusy && s.export === 'Idle' && s.exportOpen, 'Cancel stops the export and goes back to the options', { ms: Date.now() - cancelAt, ...s });
await page.evaluate(() => window.dawExport.start());
await page.waitForFunction(() => !JSON.parse(window.dawProbe()).exportBusy, null, { timeout: 120000 }).catch(() => {});
await page.waitForTimeout(500);
s = await probe();
check(s.export === 'Done', 'a finished export shows Done (not stuck on the progress screen)', s);
await page.evaluate(() => window.dawExport.close());
await settle();
s = await probe();
check(!s.exportOpen, 'Done closes the export screen', s);

const zoom = await page.evaluate(() => window.visualViewport ? window.visualViewport.scale : 1);
check(zoom === 1, 'the page itself does not zoom', { zoom });

if (errors.length) fail(errors.slice(0, 5).join('\n'));
await browser.close();
server.close();
if (!process.exitCode) console.log('OK');

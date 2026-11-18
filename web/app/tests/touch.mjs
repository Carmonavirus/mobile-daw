// Phone touch on the built web app: two-finger pinch zooms the playlist in and out (fingers
// landing one after the other, as real hands do), and a one-finger tap still adds a clip.
// Usage: node tests/touch.mjs <dist folder>
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { chromium, devices } = require('playwright');
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
const context = await browser.newContext({ ...devices['Pixel 7'] });
const page = await context.newPage();
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
await page.goto(url);
await page.waitForFunction(() => typeof window.dawProbe === 'function', null, { timeout: 60000 });
await page.waitForFunction(() => JSON.parse(window.dawProbe()).textW !== undefined, null, { timeout: 30000 });
const probe = async () => JSON.parse(await page.evaluate(() => window.dawProbe()));
const settle = () => page.waitForTimeout(300);
const check = (ok, msg, state) => { if (!ok) fail(msg + ' ' + JSON.stringify(state)); else console.log('ok:', msg); };
const cdp = await context.newCDPSession(page);
const touch = (type, points) => cdp.send('Input.dispatchTouchEvent', { type, touchPoints: points });
const vp = page.viewportSize();

await page.evaluate(() => window.dawAddBeat());
await page.keyboard.press('F5');
await settle();
let s = await probe();
check(s.tab === 'PLAYLIST', 'the playlist is open', s);

// Like a real hand: one finger lands, then the other, and only the second finger moves.
const cx = vp.width * 0.6;
const cy = vp.height * 0.45;
async function pinch(fromGap, toGap, liftOneFirst = false) {
  const a = { x: cx - 40, y: cy, id: 0 };
  const b = (gap) => ({ x: cx - 40 + gap, y: cy, id: 1 });
  await touch('touchStart', [a]);
  await page.waitForTimeout(40);
  await touch('touchStart', [a, b(fromGap)]);
  const steps = 12;
  for (let i = 1; i <= steps; i++) {
    await touch('touchMove', [a, b(fromGap + (toGap - fromGap) * i / steps)]);
    await page.waitForTimeout(16);
  }
  if (liftOneFirst) {
    // The other finger stays down and wobbles a little before it lifts too.
    await touch('touchEnd', [a]);
    await page.waitForTimeout(100);
    await touch('touchMove', [{ ...a, x: a.x + 3 }]);
    await page.waitForTimeout(100);
    await touch('touchEnd', []);
  } else {
    await touch('touchEnd', []);
  }
  await settle();
}

const bar0 = s.barDp;
await pinch(40, 120);
s = await probe();
check(s.barDp > bar0 * 1.5, 'spreading two fingers zooms the playlist in', { before: bar0, ...s });
const bar1 = s.barDp;
await pinch(120, 50);
s = await probe();
check(s.barDp < bar1 * 0.7, 'pinching two fingers zooms the playlist out', { before: bar1, ...s });

const clips0 = s.clips;
await pinch(40, 90, true);
s = await probe();
check(s.clips === clips0, 'the finger left after a pinch does not tap a clip in', { clips0, ...s });

// One finger still taps a clip in on an empty cell.
for (let y = 150; y < 450 && (await probe()).clips === clips0; y += 20) {
  await page.touchscreen.tap(cx, y);
  await settle();
  await page.keyboard.press('Escape');
  await settle();
}
s = await probe();
check(s.clips === clips0 + 1, 'a one-finger tap still adds a pattern clip', s);

const zoom = await page.evaluate(() => window.visualViewport ? window.visualViewport.scale : 1);
check(zoom === 1, 'the page itself does not zoom', { zoom });

if (errors.length) fail(errors.slice(0, 5).join('\n'));
await browser.close();
server.close();
if (!process.exitCode) console.log('OK');

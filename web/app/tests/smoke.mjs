// Smoke test of the built web app (build/dist/wasmJs/productionExecutable): it starts, loads the
// APK's first song, plays sound through the engine, saves the song, and survives random taps.
// Usage: node tests/smoke.mjs <dist folder>
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
const browser = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
for (const viewport of [{ width: 390, height: 844 }, { width: 1280, height: 800 }]) {
  const page = await browser.newPage({ viewport });
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  await page.goto(url);
  await page.waitForFunction(() => typeof window.dawProbe === 'function', null, { timeout: 60000 });
  await page.waitForFunction(() => JSON.parse(window.dawProbe()).textW !== undefined, null, { timeout: 30000 });
  const probe = async () => JSON.parse(await page.evaluate(() => window.dawProbe()));
  const start = await probe();
  console.log(viewport.width, 'start', JSON.stringify(start));
  if (start.channels < 1) fail('no channels in the first song');
  if (!(start.textW > 0)) fail('text does not render (no font)');
  await page.mouse.click(viewport.width / 2, viewport.height / 2);
  await page.evaluate(() => window.dawTogglePlay());
  let peak = 0;
  for (let i = 0; i < 20; i++) {
    await page.waitForTimeout(100);
    peak = Math.max(peak, await page.evaluate(() => { const l = eng.arrays.levels; return l ? l[l.length - 1] : 0; }));
  }
  const playing = await probe();
  console.log(viewport.width, 'playing', JSON.stringify(playing), 'peak', peak.toFixed(3));
  if (!playing.playing || playing.pos <= 0) fail('playback does not move');
  if (peak <= 0.01) fail('no sound while playing');
  await page.evaluate(() => window.dawTogglePlay());
  const files = await page.evaluate(() => new Promise((r) => { const out = []; vfsLoadAll((k) => out.push(k), () => r(out)); }));
  if (!files.some((f) => f.startsWith('/files/projects/'))) fail('song not saved');
  for (let i = 0; i < 60; i++) {
    await page.mouse.click(20 + ((i * 53) % (viewport.width - 40)), 60 + ((i * 97) % (viewport.height - 80)));
    await page.waitForTimeout(60);
    await page.keyboard.press('Escape');
  }
  await probe();
  if (errors.length) fail(errors.slice(0, 5).join('\n'));
  await page.close();
}
await browser.close();
server.close();
if (!process.exitCode) console.log('OK');

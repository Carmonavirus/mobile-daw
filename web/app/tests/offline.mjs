// Offline test of the assembled site (after the publish step's cache-busting and sw.js fill-in):
// a first visit saves the app on the device, then with the server switched off the app still
// starts, draws, plays sound and opens the classic version. An update check must still reach
// the network (it is never answered from the saved copy).
// Usage: node tests/offline.mjs <site folder>
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const root = path.resolve(process.argv[2] || '../../site');
const types = { '.html': 'text/html', '.js': 'text/javascript', '.wasm': 'application/wasm', '.json': 'application/json', '.svg': 'image/svg+xml', '.ttf': 'font/ttf', '.png': 'image/png', '.webmanifest': 'application/manifest+json', '.css': 'text/css' };
const base = '/mobile-daw/'; // same folder layout as GitHub Pages
let online = true;
const hits = [];
const server = http.createServer((req, res) => {
  hits.push(req.url);
  if (!online) { req.socket.destroy(); return; }
  if (!req.url.startsWith(base)) { res.writeHead(404); res.end(); return; }
  let p = decodeURIComponent(req.url.split('?')[0].slice(base.length));
  if (p === '' || p.endsWith('/')) p += 'index.html';
  fs.readFile(path.join(root, p), (err, data) => {
    if (err) { res.writeHead(404); res.end(); return; }
    res.writeHead(200, { 'content-type': types[path.extname(p)] || 'application/octet-stream' });
    res.end(data);
  });
}).listen(0);
const url = `http://localhost:${server.address().port}${base}`;

const fail = (msg) => { console.error('FAIL: ' + msg); process.exitCode = 1; };
const browser = await chromium.launch({
  args: ['--autoplay-policy=no-user-gesture-required', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'],
});
const context = await browser.newContext({ viewport: { width: 390, height: 844 } });
const page = await context.newPage();
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));

const started = async (what) => {
  const ok = await page.waitForFunction(() => typeof window.dawProbe === 'function' && JSON.parse(window.dawProbe()).textW > 0, null, { timeout: 90000 }).catch(() => null);
  if (!ok) fail(what + ': the app did not start');
  return !!ok;
};

// 1. First visit, online: the service worker saves everything.
await page.goto(url);
await started('first visit');
const saved = await page.waitForFunction(async () => {
  const reg = await navigator.serviceWorker.ready;
  if (!reg.active || !navigator.serviceWorker.controller) return false;
  const keys = await (await caches.open('daw-offline')).keys();
  return keys.some((k) => k.url.includes('GeneralUser-GS.sf2')) && keys.some((k) => k.url.includes('/daw.wasm?sw=')) ? keys.length : false;
}, null, { timeout: 120000, polling: 500 }).then((h) => h.jsonValue()).catch(() => 0);
console.log('saved files:', saved);
if (!saved) fail('the app was not saved for offline use');

// 2. Server off: the app starts, draws and plays from the saved copy.
online = false;
await page.reload();
if (await started('offline')) {
  await page.evaluate(() => window.dawAddBeat());
  await page.evaluate(() => window.dawTogglePlay());
  let peak = 0;
  for (let i = 0; i < 20; i++) {
    await page.waitForTimeout(100);
    peak = Math.max(peak, await page.evaluate(() => { const l = eng.arrays.levels; return l ? l[l.length - 1] : 0; }));
  }
  const p = JSON.parse(await page.evaluate(() => window.dawProbe()));
  console.log('offline playing', JSON.stringify(p), 'peak', peak.toFixed(3));
  if (!p.playing || peak <= 0.01) fail('no sound offline');
  await page.evaluate(() => window.dawTogglePlay());
  // The built-in instrument bank comes from the saved copy too (the page reads it in the background from the saved copy).
  await page.waitForFunction(() => assets.has('soundfonts/GeneralUser-GS.sf2'), null, { timeout: 15000 }).catch(() => {});
  const sf2 = await page.evaluate(() => { const b = assetGet('soundfonts/GeneralUser-GS.sf2'); return b ? b.length : 0; });
  console.log('offline instrument bank', sf2);
  if (sf2 < 1e6) fail('the instrument bank is not available offline: ' + sf2);
}

// 3. The classic version (older iPhones) also opens offline.
await page.goto(url + 'classic/');
const classic = await page.waitForFunction(() => document.querySelector('#app') && document.querySelector('#app').children.length > 0, null, { timeout: 30000 }).catch(() => null);
if (!classic) fail('classic version does not open offline');

// 4. Back online: the page and update check come from the network, not the saved copy.
online = true;
hits.length = 0;
await page.goto(url);
await started('back online');
await page.waitForTimeout(1500);
if (!hits.some((h) => h === base || h === base + 'index.html')) fail('the page was not fetched from the network: ' + hits.join(' '));
if (!hits.some((h) => h.startsWith(base + 'build-info.js?check='))) fail('the update check did not reach the network');
if (hits.some((h) => h.includes('.sf2') || h.includes('daw.wasm'))) fail('saved files were downloaded again: ' + hits.join(' '));

if (errors.length) console.log('page errors (not failing):\n' + errors.slice(0, 5).join('\n'));
await browser.close();
server.close();
if (!process.exitCode) console.log('offline test passed');

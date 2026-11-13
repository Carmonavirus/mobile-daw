// Google Drive sync test of the built web app, with two browser "devices" and a pretend Google
// Drive (and pretend Google sign-in) answering in place of Google's servers. Checks that a beat
// made on one device shows up on the other, that a fresh device's empty song doesn't overwrite it,
// that offline edits wait, and that a project changed on both keeps the newest plus an
// "(older copy)".
// Usage: node tests/cloud-sync.mjs <dist folder>
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
    let body = data;
    // Turn sign-in on with a test client id.
    if (p === '/cloud.js') body = Buffer.from(data.toString().replace("const GOOGLE_WEB_CLIENT_ID = '';", "const GOOGLE_WEB_CLIENT_ID = 'test-client';"));
    res.writeHead(200, { 'content-type': types[path.extname(p)] || 'application/octet-stream' });
    res.end(body);
  });
}).listen(0);
const url = `http://localhost:${server.address().port}/`;

let failed = false;
const fail = (msg) => { console.error('FAIL: ' + msg); failed = true; process.exitCode = 1; };

// ---------------------------------------------------------------- pretend Google Drive
const files = new Map(); // id -> { meta, content }
const sessions = new Map();
let nextId = 1;
let offline = false;
let requests = 0;

const FAKE_GIS = `window.google = { accounts: { oauth2: {
  initTokenClient(cfg) { return { requestAccessToken() { setTimeout(() => cfg.callback({ access_token: 'token-' + Date.now(), expires_in: 3600, scope: cfg.scope }), 30); } }; },
  hasGrantedAllScopes() { return true; },
} } };`;

function matches(meta, q) {
  for (const part of q.split(/ and (?![^{]*\})/)) {
    const t = part.trim();
    let m;
    if ((m = t.match(/^'([^']+)' in parents$/))) { if (!(meta.parents || []).includes(m[1])) return false; }
    else if (t === 'trashed = false') { if (meta.trashed) return false; }
    else if ((m = t.match(/^mimeType = '([^']+)'$/))) { if (meta.mimeType !== m[1]) return false; }
    else if ((m = t.match(/^appProperties has \{ key = '([^']+)' and value = '([^']+)' \}$/))) { if ((meta.appProperties || {})[m[1]] !== m[2]) return false; }
    else throw new Error('query not understood: ' + t);
  }
  return true;
}

function json(route, status, obj, extra = {}) {
  return route.fulfill({ status, headers: { 'content-type': 'application/json', 'access-control-allow-origin': '*', 'access-control-expose-headers': 'Location', ...extra }, body: JSON.stringify(obj) });
}

async function drive(route) {
  const req = route.request();
  const u = new URL(req.url());
  const method = req.method();
  if (method === 'OPTIONS') {
    return route.fulfill({ status: 204, headers: { 'access-control-allow-origin': '*', 'access-control-allow-methods': '*', 'access-control-allow-headers': '*', 'access-control-expose-headers': 'Location' } });
  }
  if (offline) return route.abort('internetdisconnected');
  requests++;
  if (!String(req.headers()['authorization'] || '').startsWith('Bearer token-')) return json(route, 401, { error: 'no token' });
  const p = u.pathname;
  let m;
  if (p === '/drive/v3/about') return json(route, 200, { user: { emailAddress: 'tester@example.com' } });
  if (p === '/drive/v3/files' && method === 'GET') {
    const q = u.searchParams.get('q');
    return json(route, 200, { files: [...files.values()].filter((f) => matches(f.meta, q)).map((f) => f.meta) });
  }
  if (p === '/drive/v3/files' && method === 'POST') {
    const meta = { ...JSON.parse(req.postData()), id: 'f' + nextId++ };
    files.set(meta.id, { meta, content: Buffer.alloc(0) });
    return json(route, 200, { id: meta.id });
  }
  if ((m = p.match(/^\/drive\/v3\/files\/([^/]+)$/))) {
    const f = files.get(decodeURIComponent(m[1]));
    if (!f) return json(route, 404, { error: 'not found' });
    if (method === 'PATCH') { Object.assign(f.meta, JSON.parse(req.postData())); return json(route, 200, { id: f.meta.id }); }
    if (u.searchParams.get('alt') === 'media') return route.fulfill({ status: 200, headers: { 'access-control-allow-origin': '*' }, body: f.content });
    return json(route, 200, f.meta);
  }
  if ((p === '/upload/drive/v3/files' && method === 'POST') || ((m = p.match(/^\/upload\/drive\/v3\/files\/([^/]+)$/)) && method === 'PATCH')) {
    const sid = 's' + nextId++;
    sessions.set(sid, { id: m ? decodeURIComponent(m[1]) : null, meta: JSON.parse(req.postData()) });
    return json(route, 200, {}, { location: `https://www.googleapis.com/upload/session/${sid}` });
  }
  if ((m = p.match(/^\/upload\/session\/(.+)$/)) && method === 'PUT') {
    const s = sessions.get(m[1]);
    const content = req.postDataBuffer() || Buffer.alloc(0);
    let f = s.id ? files.get(s.id) : null;
    if (!f) { f = { meta: { id: 'f' + nextId++, appProperties: {} }, content }; files.set(f.meta.id, f); }
    const { appProperties, ...rest } = s.meta;
    Object.assign(f.meta, rest);
    f.meta.appProperties = { ...(f.meta.appProperties || {}), ...(appProperties || {}) };
    f.content = content;
    return json(route, 200, { id: f.meta.id });
  }
  return json(route, 400, { error: 'not handled: ' + method + ' ' + p });
}

const driveProjects = () => [...files.values()].filter((f) => f.meta.appProperties?.daw === 'project' && !f.meta.trashed);

// ---------------------------------------------------------------- devices
const browser = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });

async function device(name) {
  const context = await browser.newContext({ viewport: { width: 390, height: 844 } });
  await context.route('https://accounts.google.com/**', (r) => r.fulfill({ status: 200, headers: { 'content-type': 'text/javascript' }, body: FAKE_GIS }));
  await context.route('https://www.googleapis.com/**', drive);
  await context.route('https://oauth2.googleapis.com/**', (r) => r.fulfill({ status: 200, headers: { 'access-control-allow-origin': '*' }, body: '' }));
  const page = await context.newPage();
  page.on('pageerror', (e) => fail(`${name}: page error ${e.message}`));
  await page.goto(url);
  await page.waitForFunction(() => window.dawCloud && typeof window.dawCloud.probe === 'function', null, { timeout: 60000 });
  await page.waitForFunction(() => !!(window.google && window.google.accounts), null, { timeout: 10000 });
  const probe = async () => JSON.parse(await page.evaluate(() => window.dawCloud.probe()));
  return { name, page, probe };
}

/** Waits until a device has finished syncing (status settled and Drive quiet). */
async function settle(d, want = 'SYNCED') {
  let last = -1;
  for (let i = 0; i < 120; i++) {
    await d.page.waitForTimeout(250);
    const s = await d.probe();
    if (s.status === want && requests === last) return s;
    last = requests;
  }
  const s = await d.probe();
  fail(`${d.name}: sync never settled on ${want}: ${JSON.stringify(s)}`);
  return s;
}

const a = await device('phone');
await a.page.evaluate(() => window.dawAddBeat());
await a.page.evaluate(() => window.dawCloud.signIn());
let sa = await settle(a);
console.log('phone after sign-in', JSON.stringify(sa));
if (!sa.signedIn || sa.email !== 'tester@example.com') fail('phone did not sign in');
let dp = driveProjects();
if (dp.length !== 1 || JSON.parse(dp[0].content.toString()).channels.length !== 1) fail('the beat did not reach Drive: ' + dp.map((f) => f.meta.name));
const folders = [...files.values()].filter((f) => f.meta.mimeType === 'application/vnd.google-apps.folder').map((f) => f.meta.name).sort();
if (folders.join() !== 'Mobile DAW,Projects,Sounds') fail('Drive folders: ' + folders.join());

// A second device, fresh: its empty first song must not overwrite the beat, and the beat opens.
const b = await device('laptop');
if ((await b.probe()).channels !== 0) fail('laptop should start empty');
await b.page.evaluate(() => window.dawCloud.signIn());
let sb = await settle(b);
console.log('laptop after sign-in', JSON.stringify(sb));
if (sb.channels !== 1 || sb.notes !== 4) fail('the beat did not show up on the laptop');
if (sb.projects.some((n) => n.includes('older copy'))) fail('an empty song should not leave an older copy');

// An edit on the laptop syncs by itself and reaches the phone.
await b.page.evaluate(() => window.dawAddBeat());
await b.page.waitForTimeout(800);
sb = await settle(b);
await a.page.evaluate(() => window.dawCloud.syncNow());
sa = await settle(a);
console.log('phone after laptop edit', JSON.stringify(sa));
if (sa.channels !== 2) fail('the laptop edit did not reach the phone');

// Offline: edits stay on the device and the status says so.
offline = true;
await a.page.evaluate(() => window.dawAddBeat());
await a.page.evaluate(() => window.dawCloud.syncNow());
sa = await settle(a, 'OFFLINE');
console.log('phone offline', JSON.stringify(sa));
if (sa.channels !== 3) fail('offline edit lost');
// The laptop changes the same song meanwhile (later, so it is the newest).
await b.page.waitForTimeout(300);
await b.page.evaluate(() => { window.dawAddBeat(); window.dawAddBeat(); });
await b.page.waitForTimeout(800);
offline = false;
await a.page.evaluate(() => window.dawCloud.syncNow());
sa = await settle(a);
await b.page.evaluate(() => window.dawCloud.syncNow());
sb = await settle(b);
await a.page.evaluate(() => window.dawCloud.syncNow());
sa = await settle(a);
console.log('phone after conflict', JSON.stringify(sa));
console.log('laptop after conflict', JSON.stringify(sb));
if (sb.channels !== 4 || sa.channels !== 4) fail('the newest version (laptop, 4 sounds) should win on both');
for (const s of [sa, sb]) if (!s.projects.includes('New project (older copy)')) fail('older copy missing: ' + s.projects.join(', '));
dp = driveProjects().map((f) => f.meta.name).sort();
console.log('Drive projects', dp.join(', '));
if (dp.join() !== 'New project (older copy).json,New project.json') fail('Drive projects: ' + dp.join(', '));
const copy = driveProjects().find((f) => f.meta.name.startsWith('New project (older'));
if (copy && JSON.parse(copy.content.toString()).channels.length !== 3) fail('the older copy should be the phone\'s 3-sound version');

// A new song on the phone shows up in the laptop's list.
await a.page.evaluate(() => window.dawCloud.newProject());
await a.page.waitForTimeout(800);
sa = await settle(a);
await b.page.evaluate(() => window.dawCloud.syncNow());
sb = await settle(b);
if (!sb.projects.includes('New project 2')) fail('new song not on the laptop: ' + sb.projects.join(', '));

await browser.close();
server.close();
console.log(failed ? 'cloud sync test FAILED' : 'cloud sync test passed');

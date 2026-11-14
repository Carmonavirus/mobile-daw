// Offline support. After the first visit the whole app (screens, sound engine, fonts, the built-in
// GeneralUser GS instruments) is kept on the device, so it opens and plays with no internet.
// Online-only extras (Freesound and other sound libraries, Google Drive sync) still need a connection.
//
// The publish step fills in BUILD and FILES (every file of this build with a hash of its content),
// see web/app/tools/offline-files.mjs. Rules that keep updates arriving (PR #46):
// - The page itself always comes from the network first; the saved copy is used only when offline.
// - Files are saved under their content hash, so an update downloads only what changed.
// - A request stamped ?v=<another build> (a newer page than this worker) goes to the network.
const BUILD = '__BUILD__';
const FILES = __FILES__;
const CACHE = 'daw-offline';
const SCOPE = new URL('./', self.location).href;

function hashOf(path) { return FILES.main[path] || FILES.classic[path] || null; }
function keyOf(path, hash) { return SCOPE + path + '?sw=' + hash; }

self.addEventListener('install', (event) => {
  self.skipWaiting();
  event.waitUntil((async () => {
    const cache = await caches.open(CACHE);
    // Both versions: the classic one (for browsers without WebAssembly GC) is under 1 MB.
    const wanted = Object.assign({}, FILES.classic, FILES.main);
    const save = async (path, hash) => {
      const key = keyOf(path, hash);
      if (await cache.match(key)) return; // unchanged since the last build: keep it
      // ?v= gets past the CDN's copy, so this really is this build's file.
      const res = await fetch(SCOPE + path + '?v=' + BUILD, { cache: 'reload' });
      if (!res.ok) throw new Error(path + ': ' + res.status);
      await cache.put(key, res);
    };
    const big = (p) => p.endsWith('.sf2');
    const entries = Object.entries(wanted);
    // The app itself must be saved in full; the big instrument bank is saved as well but a
    // failure there (e.g. a full phone) leaves the rest working offline.
    await Promise.all(entries.filter(([p]) => !big(p)).map(([p, h]) => save(p, h)));
    await Promise.all(entries.filter(([p]) => big(p)).map(([p, h]) => save(p, h).catch(() => {})));
  })());
});

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    // Drop files that are not part of this build any more.
    const keep = new Set();
    for (const g of ['main', 'classic']) for (const [p, h] of Object.entries(FILES[g])) keep.add(keyOf(p, h));
    const cache = await caches.open(CACHE);
    for (const req of await cache.keys()) if (!keep.has(req.url)) await cache.delete(req);
    for (const name of await caches.keys()) if (name !== CACHE && name.startsWith('daw-')) await caches.delete(name);
    await self.clients.claim();
    // Ask the browser to keep the app and saved songs when space runs low.
    try { if (self.navigator.storage && self.navigator.storage.persist) await self.navigator.storage.persist(); } catch (e) {}
  })());
});

async function fromCache(path) {
  const hash = hashOf(path);
  return hash ? caches.match(keyOf(path, hash)) : undefined;
}

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET' || !req.url.startsWith(SCOPE)) return; // other sites: untouched
  const url = new URL(req.url);
  let path = decodeURIComponent(url.pathname.slice(new URL(SCOPE).pathname.length));
  if (path === '' || path.endsWith('/')) path += 'index.html';
  const params = [...url.searchParams.keys()];
  if (params.some((k) => k !== 'v')) return; // e.g. the update check (build-info.js?check=...)
  const page = req.mode === 'navigate' || path.endsWith('.html');
  const v = url.searchParams.get('v');
  if (page || (v && v !== BUILD)) {
    // Network first, so a new version always reaches the device; the saved copy when offline.
    event.respondWith(fetch(req).catch(async () => (await fromCache(path)) || Response.error()));
    return;
  }
  if (!hashOf(path)) return;
  event.respondWith((async () => (await fromCache(path)) || fetch(req))());
});

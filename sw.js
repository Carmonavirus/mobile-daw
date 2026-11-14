// Offline support. After the first visit the whole app (screens, sound engine, fonts, the built-in
// GeneralUser GS instruments) is kept on the device, so it opens and plays with no internet.
// Online-only extras (Freesound and other sound libraries, Google Drive sync) still need a connection.
//
// The publish step fills in BUILD and FILES (every file of this build with a hash of its content),
// see web/app/tools/offline-files.mjs. Rules that keep updates arriving (PR #46):
// - The page itself always comes from the network first; the saved copy is used only when offline.
// - Files are saved under their content hash, so an update downloads only what changed.
// - A request stamped ?v=<another build> (a newer page than this worker) goes to the network.
const BUILD = "web-40abb39";
const FILES = {
 "main": {
  "apple-touch-icon.png": "0e52528e8d671d4f",
  "assets/soundfonts/GeneralUser-GS.sf2": "9575028c7a1f589f",
  "assets/wavesynth/factory_presets.json": "7cf39bbcb77e40e7",
  "build-info.js": "acfb4eb17b2ba225",
  "cloud.js": "70008ef9c7ce63ef",
  "daw.js": "b2fa4b928124735a",
  "daw.wasm": "2da5b84a8326c242",
  "dd568dbcd078c0adf7cf.wasm": "5865e7d9cdf3f1e9",
  "engine-worklet.js": "424e017253a712c5",
  "engine.wasm": "d06f29d1594e76ad",
  "fflate.js": "c3b34f2e9f5e74d4",
  "fonts/Roboto-Bold.ttf": "8774382c5f151505",
  "fonts/Roboto-Medium.ttf": "dcf7ab91d0525a55",
  "fonts/Roboto-Regular.ttf": "af558a9d48bac83c",
  "fonts/Symbols-2.ttf": "cbe7cc025b76ca3a",
  "fonts/Symbols-Math.ttf": "8d7d92f2b0d7dfee",
  "icon-192.png": "9b377eacdc6951f2",
  "icon-512.png": "f09dd7c12090b3c4",
  "icon-maskable-512.png": "407f4ecb002e28c3",
  "icon.svg": "a7d72d2a5c6b9956",
  "index.html": "8c26e212bc5ca3ac",
  "manifest.webmanifest": "78225731e49ee60e",
  "platform.js": "ca4224f416f127db"
 },
 "classic": {
  "classic/assets/index-B4KD03yB.js": "9a12f3ab08362417",
  "classic/assets/index-DLzOZ5-q.css": "c719626c875b9d18",
  "classic/engine.wasm": "d06f29d1594e76ad",
  "classic/icon.svg": "a7d72d2a5c6b9956",
  "classic/index.html": "7bad2e6e9e6fd7e9",
  "classic/manifest.webmanifest": "2f5311eb23450a12",
  "classic/worklet.js": "8afc3752b6fd8087"
 }
};
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

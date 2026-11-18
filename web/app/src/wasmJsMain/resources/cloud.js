// Google Drive sync for the web version (used by twin/CloudPlatform.kt).
//
// GOOGLE_WEB_CLIENT_ID is the "Web application" OAuth client from the Google Cloud project that
// owns the sync (Authorized JavaScript origin: https://carmonavirus.github.io). It isn't a secret.
// Empty = sign-in is hidden and the app works as before.
const GOOGLE_WEB_CLIENT_ID = '';
const CLOUD_SCOPE = 'https://www.googleapis.com/auth/drive.file';
const CLOUD_TOKEN_KEY = 'daw-cloud-token';

let gisClient = null;
let gisBusy = false;
let gisWaiters = [];

function cloudConfigured() { return !!GOOGLE_WEB_CLIENT_ID; }

function gisFinish(token) {
  gisBusy = false;
  const waiting = gisWaiters;
  gisWaiters = [];
  for (const w of waiting) { try { w(token); } catch (e) { console.error(e); } }
}

function gisInit() {
  if (gisClient || !(window.google && google.accounts && google.accounts.oauth2)) return;
  gisClient = google.accounts.oauth2.initTokenClient({
    client_id: GOOGLE_WEB_CLIENT_ID,
    scope: CLOUD_SCOPE,
    callback: (resp) => {
      const t = resp && resp.access_token;
      if (t && google.accounts.oauth2.hasGrantedAllScopes(resp, CLOUD_SCOPE)) {
        const seconds = Number(resp.expires_in) || 3600;
        try { localStorage.setItem(CLOUD_TOKEN_KEY, JSON.stringify({ t, exp: Date.now() + seconds * 1000 })); } catch (e) {}
        gisFinish(t);
      } else {
        gisFinish(null);
      }
    },
    error_callback: () => gisFinish(null),
  });
}

// Google's sign-in library, loaded at start so the sign-in window can open right in the tap.
if (cloudConfigured()) {
  const s = document.createElement('script');
  s.src = 'https://accounts.google.com/gsi/client';
  s.async = true;
  s.onload = gisInit;
  document.head.appendChild(s);
}

function cloudSavedToken() {
  try {
    const v = JSON.parse(localStorage.getItem(CLOUD_TOKEN_KEY) || 'null');
    if (v && v.t && v.exp - 60000 > Date.now()) return v.t;
  } catch (e) {}
  return null;
}

/** Opens Google's sign-in window; must run inside the tap. */
function cloudStartSignIn(hint) {
  gisInit();
  if (!gisClient) return;
  gisBusy = true;
  const opts = { prompt: '' };
  if (hint) { opts.hint = hint; opts.login_hint = hint; }
  try { gisClient.requestAccessToken(opts); } catch (e) { gisBusy = false; }
}

/** done(token or null): the sign-in window's answer when it's open, else a saved token. */
function cloudToken(interactive, done) {
  if (interactive && gisBusy) { gisWaiters.push(done); return; }
  done(cloudSavedToken());
}

function cloudForget(token) {
  try {
    const v = JSON.parse(localStorage.getItem(CLOUD_TOKEN_KEY) || 'null');
    if (!v || v.t === token) localStorage.removeItem(CLOUD_TOKEN_KEY);
  } catch (e) {}
}

/** done(status, body, location); status 0 = no connection. */
function cloudFetch(method, url, headers, body, done) {
  const h = {};
  for (const line of (headers || '').split('\n')) {
    const i = line.indexOf(':');
    if (i > 0) h[line.slice(0, i)] = line.slice(i + 1);
  }
  const init = { method, headers: h };
  if (body) init.body = new Uint8Array(body.buffer, body.byteOffset, body.byteLength);
  fetch(url, init)
    .then((r) => r.arrayBuffer().then((b) => done(r.status, new Int8Array(b), r.headers.get('Location'))))
    .catch(() => done(0, new Int8Array(0), null));
}

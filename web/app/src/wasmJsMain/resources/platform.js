// Browser services for the web build of the app (the Kotlin side is in src/wasmJsMain/kotlin/web):
// saved files (IndexedDB), the sound engine (engine.wasm in an AudioWorklet plus a copy on the
// page), file pickers, the microphone, downloads and sharing, assets and zip files.
/* global fflate */
'use strict';

// ------------------------------------------------------------------ files (IndexedDB)
const DB_NAME = 'mobile-daw-files';
let dbPromise = null;
function db() {
  if (!dbPromise) {
    dbPromise = new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, 1);
      req.onupgradeneeded = () => req.result.createObjectStore('files');
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }
  return dbPromise;
}

function vfsLoadAll(onEntry, onDone) {
  db()
    .then((d) => {
      const tx = d.transaction('files', 'readonly');
      const req = tx.objectStore('files').openCursor();
      req.onsuccess = () => {
        const c = req.result;
        if (c) {
          const v = c.value;
          onEntry(c.key, v.bytes ? new Int8Array(v.bytes) : null, v.modified || 0);
          c.continue();
        } else onDone();
      };
      req.onerror = () => onDone();
    })
    .catch(() => onDone());
}

// Writes are queued and applied in order, so a quick write-then-delete can't land backwards.
let vfsQueue = Promise.resolve();
function vfsPut(path, bytes, modified) {
  const copy = bytes ? bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) : null;
  vfsQueue = vfsQueue.then(() =>
    db().then(
      (d) =>
        new Promise((resolve) => {
          const tx = d.transaction('files', 'readwrite');
          tx.objectStore('files').put({ bytes: copy, modified }, path);
          tx.oncomplete = tx.onerror = tx.onabort = () => resolve();
        }),
    ).catch(() => {}),
  );
}
function vfsRemove(path) {
  vfsQueue = vfsQueue.then(() =>
    db().then(
      (d) =>
        new Promise((resolve) => {
          const tx = d.transaction('files', 'readwrite');
          tx.objectStore('files').delete(path);
          tx.oncomplete = tx.onerror = tx.onabort = () => resolve();
        }),
    ).catch(() => {}),
  );
}
// Ask the browser not to clear saved songs when space runs low.
try { navigator.storage && navigator.storage.persist && navigator.storage.persist(); } catch (e) {}

// ------------------------------------------------------------------ sound engine
const eng = {
  ctx: null,
  node: null,
  page: null, // exports of the page's own engine copy
  ready: false,
  status: { pos: 0, playing: 0, countIn: 0, outputLatencyMs: -1, bufferLatencyMs: -1 },
  arrays: {},
  lastOut: new Float32Array(0),
  want: { scope: 0, signal: null, pitch: null },
  wantSent: {},
  error: null,
};

function wasmImports(module) {
  const imports = {};
  for (const imp of WebAssembly.Module.imports(module)) {
    if (imp.kind !== 'function') continue;
    (imports[imp.module] ||= {})[imp.name] = imp.module === 'env' ? () => {} : () => 52;
  }
  return imports;
}

/** Starts both engine copies; calls done(errorMessage or null). */
function engineInit(done) {
  (async () => {
    const AC = window.AudioContext || window.webkitAudioContext;
    // iPhone: play through the speaker even when the ring/silent switch is on silent.
    if (navigator.audioSession) navigator.audioSession.type = 'playback';
    const ctx = new AC({ latencyHint: 'interactive' });
    eng.ctx = ctx;
    const bytes = await (await fetch('engine.wasm')).arrayBuffer();
    const module = await WebAssembly.compile(bytes);
    const instance = await WebAssembly.instantiate(module, wasmImports(module));
    const e = instance.exports;
    if (e._initialize) e._initialize();
    e.daw_init(ctx.sampleRate);
    eng.page = e;
    await ctx.audioWorklet.addModule('engine-worklet.js');
    const node = new AudioWorkletNode(ctx, 'daw-engine', {
      numberOfInputs: 0,
      numberOfOutputs: 1,
      outputChannelCount: [2],
      processorOptions: { wasm: bytes },
    });
    node.connect(ctx.destination);
    node.port.onmessage = (ev) => onEngineMessage(ev.data);
    eng.node = node;
    const unlock = () => { if (ctx.state !== 'running') ctx.resume(); };
    for (const type of ['pointerdown', 'keydown', 'touchend']) window.addEventListener(type, unlock, { capture: true });
    eng.ready = true;
    done(null);
  })().catch((err) => {
    eng.error = String(err);
    done(String(err));
  });
}

function onEngineMessage(m) {
  if (m.t === 'status') {
    const s = eng.status;
    s.pos = m.pos;
    s.playing = m.playing ? 1 : 0;
    s.countIn = m.countIn;
    eng.arrays.levels = m.levels;
    eng.arrays.inserts = m.inserts;
    if (m.scope) eng.arrays.scope = m.scope;
    if (m.signal) { eng.arrays.signal = m.signal.data; s.signalIndex = m.signal.index; }
    if (m.pitch) { eng.arrays.pitch = m.pitch.data; s.pitchIndex = m.pitch.index; s.pitchSlot = m.pitch.slot; }
    const ctx = eng.ctx;
    if (ctx) {
      s.bufferLatencyMs = (ctx.baseLatency || 0) * 1000;
      s.outputLatencyMs = ((ctx.baseLatency || 0) + (ctx.outputLatency || 0)) * 1000;
    }
  } else if (m.t === 'error') {
    eng.error = m.message;
    console.error('engine:', m.message);
  }
}

/** Runs an exported function on the page's copy; typed arrays become (pointer, length). */
function callPage(f, args) {
  const e = eng.page;
  if (!e || !e[f]) return 0;
  const flat = [];
  const temp = [];
  let out = null;
  for (const a of args) {
    if (a === null || a === undefined) flat.push(0, 0);
    else if (typeof a === 'number') flat.push(a);
    else if (a.__out !== undefined) {
      const ptr = e.daw_malloc(Math.max(4, a.__out * 4));
      temp.push(ptr);
      out = { ptr, n: a.__out };
      flat.push(ptr, a.__out);
    } else {
      const ptr = e.daw_malloc(Math.max(4, a.byteLength));
      new Uint8Array(e.memory.buffer, ptr, a.byteLength).set(new Uint8Array(a.buffer, a.byteOffset, a.byteLength));
      temp.push(ptr);
      flat.push(ptr, a.length);
    }
  }
  const r = e[f](...flat);
  if (out) eng.lastOut = new Float32Array(e.memory.buffer, out.ptr, out.n).slice();
  for (const p of temp) e.daw_free(p);
  e.daw_collect();
  return typeof r === 'number' ? r : 0;
}

/** target: 0 = both copies, 1 = the playing copy only, 2 = the page's copy only. */
function engCall(target, f, args) {
  const a = Array.from(args);
  if (f === 'ne_seek') eng.status.pos = a[0];
  if (f === 'ne_setPlaying') { eng.status.playing = a[0] ? 1 : 0; if (a[0] && eng.ctx) eng.ctx.resume(); }
  if (target !== 2 && eng.node) {
    if (f === 'ne_previewNote' || f === 'ne_audition' || f === 'ne_auditionEngine' || f === 'se_noteHold') eng.ctx.resume();
    // Copies, so the arrays can travel to the worklet without detaching the page's own.
    eng.node.port.postMessage({ t: 'call', f, a: a.map((x) => (x && x.buffer ? x.slice() : x)) });
  }
  return target === 1 ? 0 : callPage(f, a);
}
function engOut(n) { return { __out: n }; }
function engLastOut() { return eng.lastOut; }
function engResultFloats(n) {
  const e = eng.page;
  return new Float32Array(e.memory.buffer, e.ne_result(), n).slice();
}
function engResultBytes(n) {
  const e = eng.page;
  return new Int8Array(e.memory.buffer, e.ne_result(), n).slice();
}
function engResultString(n) {
  const e = eng.page;
  return new TextDecoder().decode(new Uint8Array(e.memory.buffer, e.ne_result(), n));
}
function engStatus(key) { const v = eng.status[key]; return typeof v === 'number' ? v : -1; }
function engStatusArray(key) { return eng.arrays[key] || null; }
function engWant(what, a, b, c) {
  let v;
  if (what === 'scope') v = a;
  else if (what === 'signal') v = { index: a, size: b };
  else v = { index: a, slot: b };
  const key = JSON.stringify(v);
  const now = performance.now();
  const prev = eng.wantSent[what];
  if (!prev || prev.key !== key || now - prev.at > 500) {
    eng.wantSent[what] = { key, at: now };
    if (eng.node) eng.node.port.postMessage({ t: 'want', what, v });
  }
  // Stop sending a scope or signal nobody reads any more.
  clearTimeout(eng['stop_' + what]);
  eng['stop_' + what] = setTimeout(() => {
    eng.wantSent[what] = null;
    if (eng.node) eng.node.port.postMessage({ t: 'want', what, v: what === 'scope' ? 0 : null });
  }, 1500);
}
function engSampleRate() { return eng.ctx ? eng.ctx.sampleRate : 48000; }
function engAudioOn() { return eng.ctx && eng.ctx.state === 'running'; }
function engError() { return eng.error; }

// ------------------------------------------------------------------ file pickers
const AUDIO_EXT = /\.(mp3|m4a|aac|ogg|oga|opus|flac|aif|aiff|caf|webm|mp4|wma|amr|3gp)$/i;

async function decodeForApp(file, bytes) {
  // Compressed audio: decoded by the browser now, so the app's (synchronous) import can read it.
  if (!AUDIO_EXT.test(file.name) && !(file.type.startsWith('audio/') && !/wav/i.test(file.type))) return null;
  try {
    const ctx = eng.ctx || new (window.AudioContext || window.webkitAudioContext)();
    const buf = await ctx.decodeAudioData(bytes.slice(0));
    return {
      rate: buf.sampleRate,
      left: buf.getChannelData(0).slice(),
      right: buf.numberOfChannels > 1 ? buf.getChannelData(1).slice() : null,
    };
  } catch (e) {
    return null;
  }
}

function makeInput(accept, multiple, folder) {
  const input = document.createElement('input');
  input.type = 'file';
  if (accept) input.accept = accept;
  input.multiple = !!multiple;
  if (folder) input.webkitdirectory = true;
  input.style.display = 'none';
  document.body.appendChild(input);
  return input;
}

/** onFile(name, bytes, rate, left, right) per file (rate 0 when not decoded), then onDone(). */
function pickFiles(accept, multiple, onFile, onDone) {
  const input = makeInput(accept, multiple, false);
  let finished = false;
  const finish = () => { if (!finished) { finished = true; input.remove(); onDone(); } };
  input.addEventListener('cancel', finish);
  input.addEventListener('change', async () => {
    for (const f of Array.from(input.files || [])) {
      const bytes = await f.arrayBuffer();
      const d = await decodeForApp(f, bytes);
      onFile(f.name, new Int8Array(bytes), d ? d.rate : 0, d ? d.left : null, d ? d.right : null);
    }
    finish();
  });
  input.click();
}

/** onFile(relativePath, bytes) for every file in the folder, then onDone(folderName or null). */
function pickFolder(onFile, onDone) {
  const input = makeInput('', true, true);
  let finished = false;
  input.addEventListener('cancel', () => { if (!finished) { finished = true; input.remove(); onDone(null); } });
  input.addEventListener('change', async () => {
    const files = Array.from(input.files || []);
    let name = null;
    for (const f of files) {
      const rel = f.webkitRelativePath || f.name;
      const parts = rel.split('/');
      if (!name) name = parts.length > 1 ? parts[0] : 'Folder';
      onFile(parts.length > 1 ? parts.slice(1).join('/') : rel, new Int8Array(await f.arrayBuffer()));
    }
    finished = true;
    input.remove();
    onDone(name);
  });
  input.click();
}

// ------------------------------------------------------------------ microphone
const mic = { state: 'prompt', stream: null, node: null, src: null, chunks: [], frames: 0, peaks: [], level: 0, startedAt: 0, running: false };
try {
  navigator.permissions && navigator.permissions.query({ name: 'microphone' }).then((p) => {
    mic.state = p.state;
    p.onchange = () => (mic.state = p.state);
  }).catch(() => {});
} catch (e) {}

function micPermission() { return mic.state; }
function askMicPermission(done) {
  navigator.mediaDevices.getUserMedia({ audio: true }).then((s) => {
    mic.state = 'granted';
    s.getTracks().forEach((t) => t.stop());
    done(true);
  }).catch(() => { mic.state = 'denied'; done(false); });
}

function micRate() { return eng.ctx ? eng.ctx.sampleRate : 48000; }
function micLevel() { return mic.level; }
function micFrames() { return mic.frames; }
function micStartedAt() { return mic.startedAt; }
function micPeaks() { return new Float32Array(mic.peaks); }

/** Starts recording; the take is collected here until micStop(). */
function micStart(peakFrames) {
  if (!eng.ctx || mic.running) return false;
  mic.running = true;
  mic.chunks = [];
  mic.frames = 0;
  mic.peaks = [];
  mic.level = 0;
  mic.startedAt = performance.now();
  const ctx = eng.ctx;
  ctx.resume();
  navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false } })
    .then((stream) => {
      if (!mic.running) { stream.getTracks().forEach((t) => t.stop()); return; }
      mic.state = 'granted';
      mic.stream = stream;
      mic.src = ctx.createMediaStreamSource(stream);
      mic.node = new AudioWorkletNode(ctx, 'daw-mic', { numberOfInputs: 1, numberOfOutputs: 0 });
      let peak = 0, inPeak = 0;
      mic.startedAt = performance.now() - ((ctx.baseLatency || 0) * 1000);
      mic.node.port.onmessage = (ev) => {
        if (!mic.running) return;
        const block = ev.data;
        mic.chunks.push(block);
        mic.frames += block.length;
        let bp = 0;
        for (let i = 0; i < block.length; i++) {
          const a = Math.abs(block[i]);
          if (a > bp) bp = a;
          if (a > peak) peak = a;
          if (++inPeak >= peakFrames) { mic.peaks.push(peak); peak = 0; inPeak = 0; }
        }
        mic.level = bp;
      };
      mic.src.connect(mic.node);
    })
    .catch(() => { mic.running = false; mic.state = 'denied'; });
  return true;
}

function micStop() {
  mic.running = false;
  try { mic.src && mic.src.disconnect(); } catch (e) {}
  try { mic.stream && mic.stream.getTracks().forEach((t) => t.stop()); } catch (e) {}
  mic.src = mic.node = mic.stream = null;
  const out = new Float32Array(mic.frames);
  let at = 0;
  for (const c of mic.chunks) { out.set(c, at); at += c.length; }
  mic.chunks = [];
  mic.level = 0;
  return out;
}

// ------------------------------------------------------------------ links, downloads, sharing
function openUrl(url) { window.open(url, '_blank', 'noopener'); }

function downloadFile(name, bytes, mime) {
  const blob = new Blob([bytes], { type: mime });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 60000);
}

let shareList = [];
function shareClear() { shareList = []; }
function shareAdd(name, bytes, mime) { shareList.push(new File([bytes], name, { type: mime })); }
function shareGo(title) {
  const files = shareList;
  shareList = [];
  if (navigator.canShare && navigator.canShare({ files })) {
    navigator.share({ files, title }).catch(() => {});
  } else {
    for (const f of files) f.arrayBuffer().then((b) => downloadFile(f.name, new Uint8Array(b), f.type));
  }
}

// ------------------------------------------------------------------ assets, fonts, zip, http
const assets = new Map();
function assetPreload(name) {
  return fetch('assets/' + name).then((r) => (r.ok ? r.arrayBuffer() : null)).then((b) => { if (b) assets.set(name, new Int8Array(b)); }).catch(() => {});
}
function assetGet(name) {
  if (assets.has(name)) return assets.get(name);
  // Not preloaded: fetch it now (blocks the page until it arrives).
  const r = httpSync('GET', 'assets/' + name, '', null);
  if (r.status < 200 || r.status > 299) return null;
  assets.set(name, r.body);
  return r.body;
}
function assetList(path) {
  const prefix = path ? path.replace(/\/$/, '') + '/' : '';
  const names = new Set();
  for (const k of assets.keys()) if (k.startsWith(prefix)) names.add(k.slice(prefix.length).split('/')[0]);
  return Array.from(names).join('\n');
}
function fontBytes(name) { return assets.get('fonts/' + name) || null; }
/** Loads the start-up files; done() when all are in. */
function preloadAll(done) {
  Promise.all([
    assetPreload('wavesynth/factory_presets.json'),
    fetch('fonts/Roboto-Regular.ttf').then((r) => r.arrayBuffer()).then((b) => assets.set('fonts/Roboto-Regular.ttf', new Int8Array(b))),
    fetch('fonts/Roboto-Medium.ttf').then((r) => r.arrayBuffer()).then((b) => assets.set('fonts/Roboto-Medium.ttf', new Int8Array(b))),
    fetch('fonts/Roboto-Bold.ttf').then((r) => r.arrayBuffer()).then((b) => assets.set('fonts/Roboto-Bold.ttf', new Int8Array(b))),
    // Symbols (♪ ⇄ ⋯ ♭ arrows, triangles...) that Android draws from its system fallback fonts.
    fetch('fonts/Symbols-Math.ttf').then((r) => r.arrayBuffer()).then((b) => assets.set('fonts/Symbols-Math.ttf', new Int8Array(b))),
    fetch('fonts/Symbols-2.ttf').then((r) => r.arrayBuffer()).then((b) => assets.set('fonts/Symbols-2.ttf', new Int8Array(b))),
  ]).catch(() => {}).then(() => { done(); preloadSavedBank(); });
}
/** When the app is saved for offline use (sw.js), read the built-in instrument bank from that copy
 * in the background: the page fetches it with a blocking request, which browsers send past the
 * offline copy, so without this GeneralUser GS instruments would not load with no internet. */
function preloadSavedBank() {
  try {
    if (!navigator.serviceWorker || !navigator.serviceWorker.controller) return;
    setTimeout(() => { if (!assets.has('soundfonts/GeneralUser-GS.sf2')) assetPreload('soundfonts/GeneralUser-GS.sf2'); }, 1500);
  } catch (e) {}
}

function unzipAll(bytes, onEntry) {
  const files = fflate.unzipSync(new Uint8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength));
  for (const name of Object.keys(files)) {
    const d = files[name];
    onEntry(name, name.endsWith('/') ? null : new Int8Array(d.buffer, d.byteOffset, d.byteLength));
  }
}
function zipNew() { return { files: {} }; }
function zipAdd(z, name, bytes) { z.files[name] = [new Uint8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength), { level: 1 }]; }
function zipFinish(z) { const out = fflate.zipSync(z.files); return new Int8Array(out.buffer, out.byteOffset, out.byteLength); }

/** Synchronous request (the app's library code expects one); body bytes as Int8Array. */
function httpSync(method, url, headers, body) {
  const xhr = new XMLHttpRequest();
  try {
    xhr.open(method, url, false);
    xhr.overrideMimeType('text/plain; charset=x-user-defined');
    for (const line of (headers || '').split('\n')) {
      const i = line.indexOf(':');
      if (i > 0) xhr.setRequestHeader(line.slice(0, i), line.slice(i + 1));
    }
    xhr.send(body ? new Uint8Array(body.buffer, body.byteOffset, body.byteLength) : null);
  } catch (e) {
    return { status: 0, body: new Int8Array(0) };
  }
  const text = xhr.responseText || '';
  const out = new Int8Array(text.length);
  for (let i = 0; i < text.length; i++) out[i] = text.charCodeAt(i) & 0xff;
  return { status: xhr.status, body: out };
}
function httpStatus(r) { return r.status; }
function httpBody(r) { return r.body; }

// ------------------------------------------------------------------ build info, window
function buildVersion() { return (window.DAW_BUILD && window.DAW_BUILD.version) || 'web'; }
function buildTime() { return (window.DAW_BUILD && window.DAW_BUILD.time) || Date.now(); }
function windowWidth() { return window.innerWidth; }
function windowHeight() { return window.innerHeight; }
function onWindowResize(cb) { window.addEventListener('resize', () => cb()); }
function onPageVisibility(cb) { document.addEventListener('visibilitychange', () => cb(!document.hidden)); }
function hideSplash() { const s = document.getElementById('splash'); if (s) s.remove(); }
function showStartError(msg) { const s = document.getElementById('splash'); if (s) s.textContent = msg; }

/** Test hooks (see web/app/tests). */
function registerCloudProbe(probe, signIn, syncNow, newProject) { window.dawCloud = { probe, signIn, syncNow, newProject }; }
function registerShareProbe(probe, share, zipBytes, importZip, importAudio, fileBytes) { window.dawShare = { probe, share, zipBytes, importZip, importAudio, fileBytes }; }
function registerProbe(probe, togglePlay, addBeat) { window.dawProbe = probe; window.dawTogglePlay = togglePlay; window.dawAddBeat = addBeat; }

/**
 * PC / laptop: keys go to the app's shortcuts (Space, Ctrl+Z, F5...). Skipped while a text box
 * has focus. A trackpad pinch arrives as Ctrl+wheel: the app zooms, the page must not.
 */
function listenDesktopInput(onKey, onBlur, onMouse, onRight) {
  const handle = (e, down) => {
    const a = document.activeElement;
    if (a && (a.tagName === 'TEXTAREA' || a.tagName === 'INPUT' || a.isContentEditable)) return;
    if (onKey(e.code || '', down, e.ctrlKey || e.metaKey, e.shiftKey, e.altKey, e.repeat)) {
      e.preventDefault();
      e.stopPropagation();
    }
  };
  window.addEventListener('keydown', (e) => handle(e, true), { capture: true });
  window.addEventListener('keyup', (e) => handle(e, false), { capture: true });
  window.addEventListener('blur', () => onBlur());
  window.addEventListener('wheel', (e) => { if (e.ctrlKey) e.preventDefault(); }, { passive: false, capture: true });
  for (const t of ['gesturestart', 'gesturechange']) document.addEventListener(t, (e) => e.preventDefault());
  // Right mouse button: Compose here doesn't pass it to the screens, so hand it over directly
  // (window pixels, as Compose counts them). 0 = press, 1 = drag, 2 = release.
  let rightDown = false;
  const px = (e) => [e.clientX * (window.devicePixelRatio || 1), e.clientY * (window.devicePixelRatio || 1)];
  window.addEventListener('pointerdown', (e) => {
    if (e.pointerType !== 'mouse' || e.button !== 2) return;
    rightDown = true;
    onRight(0, ...px(e));
  }, { capture: true });
  window.addEventListener('pointermove', (e) => {
    if (!rightDown) return;
    if (!(e.buttons & 2)) { rightDown = false; onRight(2, 0, 0); return; }
    onRight(1, ...px(e));
  }, { capture: true });
  window.addEventListener('pointerup', (e) => {
    if (!rightDown || e.button !== 2) return;
    rightDown = false;
    onRight(2, 0, 0);
  }, { capture: true });
  window.addEventListener('contextmenu', (e) => e.preventDefault());
  if (window.matchMedia && matchMedia('(any-pointer: fine)').matches) onMouse();
  window.addEventListener('pointermove', (e) => { if (e.pointerType === 'mouse') onMouse(); }, { passive: true });
}

/**
 * Phones and tablets: two-finger pinch (playlist zoom, piano roll scroll and zoom).
 * Compose for web only hands over the fingers that changed in each touch event, but it expects
 * every event to list every finger that is down, like Android does. So when the second finger
 * lands, or only one of two fingers moves, Compose thinks the other finger was lifted and a
 * pinch never starts. Before Compose sees a touch event, make it list all fingers on the screen.
 * When one finger of several lifts, all of them end together (as one lift) and the rest of the
 * touch is ignored until every finger is up, so a leftover finger never turns into a tap.
 */
(function fixMultiTouch() {
  const list = (touches) => {
    const a = Array.from(touches);
    const l = { length: a.length, item: (i) => a[i] || null };
    a.forEach((t, i) => { l[i] = t; });
    return l;
  };
  const show = (e, touches) => {
    try { Object.defineProperty(e, 'changedTouches', { value: touches, configurable: true }); } catch (err) {}
  };
  let ignoring = false;
  const onTouch = (e) => {
    if (!(e.target instanceof HTMLCanvasElement)) return;
    if (ignoring) {
      e.preventDefault();
      e.stopPropagation();
      if (e.touches.length === 0) ignoring = false;
      return;
    }
    if (e.type === 'touchstart' || e.type === 'touchmove') {
      if (e.touches.length > e.changedTouches.length) show(e, e.touches);
    } else if (e.touches.length > 0) {
      // touchend / touchcancel with fingers still down: lift them all now.
      show(e, list([...e.changedTouches, ...e.touches]));
      ignoring = true;
    }
  };
  for (const t of ['touchstart', 'touchmove', 'touchend', 'touchcancel']) {
    window.addEventListener(t, onTouch, { capture: true, passive: false });
  }
})();

// AudioWorklet that plays the DAW engine (engine.wasm, built from the Android app's C++ engine).
// The page calls the same `ne_*` / `se_*` functions here as on its own copy of the engine (see
// platform.js), and this side sends back the playhead, meters and scopes about 60 times a second.

const STATUS_EVERY = 6; // render quanta between status messages (~16 ms at 48 kHz)
const LEVELS = 33; // 32 channel slots + master
const INSERTS = 8;

class DawProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super();
    this.e = null;
    this.queue = [];
    this.count = 0;
    this.want = { scope: 0, signal: null, pitch: null };
    this.port.onmessage = (ev) => (this.e ? this.handle(ev.data) : this.queue.push(ev.data));
    this.init(options.processorOptions.wasm).catch((err) => this.port.postMessage({ t: 'error', message: String(err) }));
  }

  async init(bytes) {
    const module = await WebAssembly.compile(bytes);
    const imports = {};
    for (const imp of WebAssembly.Module.imports(module)) {
      if (imp.kind !== 'function') continue;
      (imports[imp.module] ||= {})[imp.name] = imp.module === 'env' ? () => {} : () => 52;
    }
    const instance = await WebAssembly.instantiate(module, imports);
    const e = instance.exports;
    if (e._initialize) e._initialize();
    e.daw_init(sampleRate);
    this.out = e.daw_malloc(128 * 2 * 4);
    this.levels = e.daw_malloc(LEVELS * 4);
    this.inserts = e.daw_malloc(INSERTS * 4);
    this.scratch = e.daw_malloc(4096 * 4);
    this.e = e;
    this.port.postMessage({ t: 'ready' });
    for (const m of this.queue) this.handle(m);
    this.queue = [];
  }

  /** Calls an engine function; typed arrays become (pointer, length), null becomes (0, 0). */
  call(f, args) {
    const e = this.e;
    const flat = [];
    const temp = [];
    for (const a of args) {
      if (a === null || a === undefined) flat.push(0, 0);
      else if (typeof a === 'number') flat.push(a);
      else {
        const ptr = e.daw_malloc(Math.max(4, a.byteLength));
        new Uint8Array(e.memory.buffer, ptr, a.byteLength).set(new Uint8Array(a.buffer, a.byteOffset, a.byteLength));
        temp.push(ptr);
        flat.push(ptr, a.length);
      }
    }
    const fn = e[f];
    if (fn) fn(...flat);
    for (const p of temp) e.daw_free(p);
    e.daw_collect();
  }

  handle(m) {
    if (m.t === 'call') this.call(m.f, m.a);
    else if (m.t === 'want') this.want[m.what] = m.v;
  }

  floats(ptr, n) {
    return new Float32Array(this.e.memory.buffer, ptr, n).slice();
  }

  process(_inputs, outputs) {
    const e = this.e;
    const out = outputs[0];
    if (!e || !out || out.length === 0) return true;
    const frames = out[0].length;
    e.daw_render(this.out, frames);
    const buf = new Float32Array(e.memory.buffer, this.out, frames * 2);
    const left = out[0];
    const right = out[1] || out[0];
    for (let i = 0; i < frames; i++) {
      left[i] = buf[i * 2];
      right[i] = buf[i * 2 + 1];
    }
    if (++this.count >= STATUS_EVERY) {
      this.count = 0;
      e.ne_readLevels(this.levels, LEVELS);
      e.ne_readInsertLevels(this.inserts, INSERTS);
      const s = {
        t: 'status',
        pos: e.ne_positionTicks(),
        playing: e.ne_isPlaying() === 1,
        countIn: e.ne_countInBeat(),
        levels: this.floats(this.levels, LEVELS),
        inserts: this.floats(this.inserts, INSERTS),
      };
      const w = this.want;
      if (w.scope > 0) {
        e.ne_readScope(this.scratch, Math.min(4096, w.scope));
        s.scope = this.floats(this.scratch, Math.min(4096, w.scope));
      }
      if (w.signal) {
        const n = Math.min(4096, w.signal.size);
        e.ne_readInsertSignal(w.signal.index, this.scratch, n);
        s.signal = { index: w.signal.index, data: this.floats(this.scratch, n) };
      }
      if (w.pitch) {
        e.ne_readPitch(w.pitch.index, w.pitch.slot, this.scratch, 2);
        s.pitch = { index: w.pitch.index, slot: w.pitch.slot, data: this.floats(this.scratch, 2) };
      }
      this.port.postMessage(s);
    }
    return true;
  }
}

registerProcessor('daw-engine', DawProcessor);

// Microphone capture for recording: hands each block of the first input channel to the page.
class MicProcessor extends AudioWorkletProcessor {
  process(inputs) {
    const ch = inputs[0] && inputs[0][0];
    if (ch && ch.length) this.port.postMessage(ch.slice());
    return true;
  }
}

registerProcessor('daw-mic', MicProcessor);

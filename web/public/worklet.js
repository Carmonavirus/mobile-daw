// AudioWorklet that runs the DAW engine (web/engine/web_api.cpp compiled to engine.wasm).
// The main thread sends the project state as messages; this side applies them to the engine
// and sends back the playhead position and meter levels a few times per frame.

const STATUS_EVERY = 6; // render quanta between status messages (~16 ms at 48 kHz)

class DawProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super();
    this.e = null;
    this.queue = [];
    this.count = 0;
    this.channelCount = 0;
    this.synthSent = [];
    this.port.onmessage = (ev) => (this.e ? this.handle(ev.data) : this.queue.push(ev.data));
    this.init(options.processorOptions.wasm).catch((err) => this.port.postMessage({ t: 'error', message: String(err) }));
  }

  async init(bytes) {
    const module = await WebAssembly.compile(bytes);
    // The engine never reads files or the clock; every system call is a harmless stub.
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
    this.levels = e.daw_malloc(64 * 4);
    this.e = e;
    const info = e.daw_malloc(16);
    const params = [];
    for (let i = 0; i < e.daw_param_count(); i++) {
      e.daw_param_info(i, info);
      const f = new Float32Array(e.memory.buffer, info, 4);
      params.push({ name: this.str(e.daw_param_name(i)), min: f[0], max: f[1], def: f[2], stepped: f[3] > 0.5 });
    }
    const tables = [];
    for (let i = 0; i < e.daw_table_count(); i++) tables.push(this.str(e.daw_table_name(i)));
    this.port.postMessage({ t: 'ready', params, tables, sampleRate });
    for (const m of this.queue) this.handle(m);
    this.queue = [];
  }

  str(ptr) {
    const m = new Uint8Array(this.e.memory.buffer);
    let s = '';
    while (m[ptr]) s += String.fromCharCode(m[ptr++]);
    return s;
  }

  copyIn(typed) {
    const ptr = this.e.daw_malloc(Math.max(4, typed.byteLength));
    new Uint8Array(this.e.memory.buffer, ptr, typed.byteLength).set(new Uint8Array(typed.buffer, typed.byteOffset, typed.byteLength));
    return ptr;
  }

  handle(m) {
    const e = this.e;
    switch (m.t) {
      case 'state': this.applyState(m.s); break;
      case 'play': e.daw_set_playing(m.on ? 1 : 0); break;
      case 'panic': e.daw_panic(); break;
      case 'seek': e.daw_seek(m.tick); break;
      case 'preview': e.daw_preview_note(m.channel, m.pitch, m.velocity ?? 100); break;
      case 'hold': e.daw_preview_hold(m.channel, m.pitch, m.velocity ?? 100, m.on ? 1 : 0); break;
      case 'builtin': e.daw_load_builtin_drum(m.slot, m.instrument); break;
      case 'sample': {
        const n = m.left ? m.left.length : 0;
        const l = n ? this.copyIn(m.left) : 0;
        const r = n && m.right ? this.copyIn(m.right) : 0;
        e.daw_set_sample(m.slot, l, r, n, m.rate || sampleRate);
        if (l) e.daw_free(l);
        if (r) e.daw_free(r);
        break;
      }
      case 'synth': {
        // Full parameter list for a wavetable channel; only changed values are sent on.
        const prev = this.synthSent[m.channel];
        m.params.forEach((v, i) => {
          if (!prev || prev[i] !== v) e.daw_set_synth_param(m.channel, i, v);
        });
        this.synthSent[m.channel] = m.params.slice();
        break;
      }
    }
    e.daw_collect();
  }

  applyState(s) {
    const e = this.e;
    e.daw_set_bpm(s.bpm);
    e.daw_set_master(s.master);
    e.daw_set_metronome(s.metronome ? 1 : 0, 4);
    e.daw_set_channel_count(s.channels.length);
    this.channelCount = s.channels.length;
    s.channels.forEach((c, i) => {
      e.daw_set_channel(i, c.instrument, c.volume, c.pan, c.mute ? 1 : 0, c.solo ? 1 : 0);
      e.daw_set_channel_clip(i, 0);
      const p = c.sampler;
      e.daw_set_channel_sampler(i, p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8], p[9], p[10], p[11]);
      e.daw_set_channel_boost(i, c.boost);
      e.daw_set_channel_insert(i, c.insert);
    });
    e.daw_set_insert_count(s.inserts.length);
    s.inserts.forEach((t, i) => e.daw_set_insert(i, t.volume, t.pan, t.mute ? 1 : 0));
    if (s.events) {
      const ptr = this.copyIn(s.events);
      e.daw_set_sequence(ptr, s.events.length, s.loopTicks);
      e.daw_free(ptr);
    }
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
      const n = Math.max(1, this.channelCount);
      e.daw_read_levels(this.levels, n);
      const levels = new Float32Array(e.memory.buffer, this.levels, n).slice();
      this.port.postMessage({
        t: 'status',
        pos: e.daw_position(),
        playing: e.daw_is_playing() === 1,
        master: e.daw_master_level(),
        levels,
        time: currentTime,
      });
    }
    return true;
  }
}

registerProcessor('daw-engine', DawProcessor);

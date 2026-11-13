// Smoke test for the WebAssembly engine (run after engine/build.sh): it loads like the worklet
// does, plays drums, a synth and a wavetable preset, and checks that sound comes out.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';

const wasm = new URL('../public/engine.wasm', import.meta.url);
const presets = JSON.parse(fs.readFileSync(new URL('../../app/src/main/assets/wavesynth/factory_presets.json', import.meta.url))).presets;

async function load() {
  const module = await WebAssembly.compile(fs.readFileSync(wasm));
  const imports = {};
  for (const imp of WebAssembly.Module.imports(module)) (imports[imp.module] ||= {})[imp.name] = imp.module === 'env' ? () => {} : () => 52;
  const { exports: e } = await WebAssembly.instantiate(module, imports);
  e._initialize?.();
  e.daw_init(48000);
  return e;
}

function peakOf(e, blocks) {
  const out = e.daw_malloc(128 * 8);
  let peak = 0;
  for (let b = 0; b < blocks; b++) {
    e.daw_render(out, 128);
    for (const v of new Float32Array(e.memory.buffer, out, 256)) {
      assert.ok(Number.isFinite(v), 'output must be a number');
      peak = Math.max(peak, Math.abs(v));
    }
  }
  e.daw_free(out);
  return peak;
}

const str = (e, ptr) => {
  const m = new Uint8Array(e.memory.buffer);
  let s = '';
  while (m[ptr]) s += String.fromCharCode(m[ptr++]);
  return s;
};

test('sequence of built-in drums and a synth plays', async () => {
  const e = await load();
  e.daw_set_channel_count(2);
  e.daw_set_channel(0, 8, 0.8, 0, 0, 0);
  e.daw_load_builtin_drum(0, 0);
  e.daw_set_channel(1, 4, 0.8, 0, 0, 0);
  const ev = [0, 0, 24, 60, 100, 1, 0, 96, 36, 100, 0, 96, 24, 60, 100];
  const p = e.daw_malloc(ev.length * 4);
  new Int32Array(e.memory.buffer, p, ev.length).set(ev);
  e.daw_set_sequence(p, ev.length, 384);
  e.daw_set_bpm(120);
  e.daw_set_playing(1);
  assert.ok(peakOf(e, 200) > 0.1);
  assert.ok(e.daw_position() > 0);
});

test('every wavetable preset makes sound and uses known parameter names', async () => {
  const e = await load();
  const names = Array.from({ length: e.daw_param_count() }, (_, i) => str(e, e.daw_param_name(i)));
  assert.ok(e.daw_table_count() > 1);
  e.daw_set_channel_count(1);
  e.daw_set_channel(0, 13, 0.8, 0, 0, 0);
  const info = e.daw_malloc(16);
  for (const preset of presets) {
    for (const k of Object.keys(preset.params)) assert.ok(names.includes(k), `${preset.name}: unknown parameter ${k}`);
    names.forEach((n, i) => {
      e.daw_param_info(i, info);
      const def = new Float32Array(e.memory.buffer, info, 4)[2];
      e.daw_set_synth_param(0, i, n in preset.params ? preset.params[n] : def);
    });
    e.daw_panic();
    peakOf(e, 40);
    e.daw_preview_hold(0, 48, 100, 1);
    const peak = peakOf(e, 100);
    e.daw_preview_hold(0, 48, 100, 0);
    assert.ok(peak > 0.005, `${preset.name} is silent`);
  }
});

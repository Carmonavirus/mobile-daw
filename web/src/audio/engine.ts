// Main-thread side of the audio engine: owns the AudioContext and the worklet that runs
// engine.wasm, and turns the project into engine state (like DawViewModel.syncEngine on Android).
import { signal } from '@preact/signals';
import { type Compiled } from '../model/compile';
import { type Channel, type Project, INSTRUMENTS, isBuiltInDrum, MAX_CHANNELS } from '../model/project';
import { loadSampleBytes } from '../storage';

export interface ParamInfo { name: string; min: number; max: number; def: number; stepped: boolean }

export const engineReady = signal(false);
export const engineError = signal<string | null>(null);
/** True once the browser lets us play sound (after the first tap/click). */
export const audioUnlocked = signal(false);
export const position = signal(0);
export const playing = signal(false);
export const levels = signal<Float32Array>(new Float32Array(0));
export const masterLevel = signal(0);

let ctx: AudioContext | null = null;
let node: AudioWorkletNode | null = null;
let params: ParamInfo[] = [];
let tables: string[] = [];
let lastEvents: Int32Array | null = null;
let lastLoop = -1;
const slotKeys: (string | null)[] = [];
const decoded = new Map<string, { left: Float32Array; right: Float32Array | null; rate: number; peak: number }>();
const pendingDecode = new Set<string>();
const missing = new Set<string>();
let lastProject: Project | null = null;
let lastCompiled: Compiled | null = null;
let metronome = false;

export const synthParams = () => params;
export const synthTables = () => tables;

export async function startEngine(): Promise<void> {
  try {
    const AC = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
    // iPhone: play through the speaker even when the ring/silent switch is on silent.
    const nav = navigator as Navigator & { audioSession?: { type: string } };
    if (nav.audioSession) nav.audioSession.type = 'playback';
    ctx = new AC({ latencyHint: 'interactive' });
    const base = import.meta.env.BASE_URL;
    const [bytes] = await Promise.all([
      fetch(base + 'engine.wasm').then((r) => {
        if (!r.ok) throw new Error('engine.wasm ' + r.status);
        return r.arrayBuffer();
      }),
      ctx.audioWorklet.addModule(base + 'worklet.js'),
    ]);
    node = new AudioWorkletNode(ctx, 'daw-engine', {
      numberOfInputs: 0,
      numberOfOutputs: 1,
      outputChannelCount: [2],
      processorOptions: { wasm: bytes },
    });
    node.connect(ctx.destination);
    node.port.onmessage = (ev) => onMessage(ev.data);
    const unlock = () => {
      ctx?.resume().then(() => (audioUnlocked.value = ctx?.state === 'running'));
    };
    for (const type of ['pointerdown', 'keydown', 'touchend']) window.addEventListener(type, unlock, { capture: true });
    if (ctx.state === 'running') audioUnlocked.value = true;
    ctx.onstatechange = () => (audioUnlocked.value = ctx?.state === 'running');
  } catch (err) {
    engineError.value = String(err);
  }
}

function onMessage(m: { t: string; [k: string]: unknown }) {
  if (m.t === 'ready') {
    params = m.params as ParamInfo[];
    tables = m.tables as string[];
    engineReady.value = true;
    if (lastProject && lastCompiled) {
      lastEvents = null;
      slotKeys.length = 0;
      syncEngine(lastProject, lastCompiled);
    }
  } else if (m.t === 'status') {
    position.value = m.pos as number;
    playing.value = m.playing as boolean;
    levels.value = m.levels as Float32Array;
    masterLevel.value = m.master as number;
  } else if (m.t === 'error') {
    engineError.value = m.message as string;
  }
}

function post(m: unknown, transfer: Transferable[] = []) {
  node?.port.postMessage(m, transfer);
}

export function setPlaying(on: boolean) {
  ctx?.resume();
  post({ t: 'play', on });
  playing.value = on;
}

export function panic() {
  post({ t: 'panic' });
  playing.value = false;
}

export function seek(tick: number) {
  post({ t: 'seek', tick: Math.max(0, Math.round(tick)) });
  position.value = Math.max(0, Math.round(tick));
}

export function preview(channelIndex: number, pitch: number, velocity = 100) {
  ctx?.resume();
  post({ t: 'preview', channel: channelIndex, pitch, velocity });
}

export function setMetronome(on: boolean) {
  metronome = on;
  if (lastProject && lastCompiled) syncEngine(lastProject, lastCompiled);
}

/** Linear gain for a sample channel's Boost (dB) and Normalize, like DawViewModel.sampleBoostGain. */
function boostGain(c: Channel, key: string | null): number {
  let g = Math.pow(10, Math.min(18, Math.max(0, c.boostDb)) / 20);
  if (c.normalize && key) {
    const peak = decoded.get(key)?.peak ?? 0;
    if (peak > 0) g *= Math.min(16, Math.max(0.1, 1 / peak));
  }
  return g;
}

/** Which recorded sound a channel plays through the sampler, or null for synths. */
export function soundKey(c: Channel): string | null {
  if (c.instrument === 'SAMPLE') return c.sample ?? null;
  if (isBuiltInDrum(c.instrument)) return 'builtin:' + c.instrument.toLowerCase();
  return null;
}

export function synthParamArray(c: Channel): number[] {
  const patch = c.synth?.params ?? {};
  return params.map((p) => (typeof patch[p.name] === 'number' ? patch[p.name] : p.def));
}

export function syncEngine(project: Project, compiled: Compiled) {
  lastProject = project;
  lastCompiled = compiled;
  if (!node || !engineReady.value) return;
  const channels = project.channels.slice(0, MAX_CHANNELS);
  const tracks = project.mixerTracks.slice(0, 8);
  channels.forEach((c, i) => {
    const key = soundKey(c);
    if (slotKeys[i] !== key) {
      if (key === null) {
        slotKeys[i] = null;
      } else if (key.startsWith('builtin:')) {
        post({ t: 'builtin', slot: i, instrument: INSTRUMENTS[c.instrument].id });
        slotKeys[i] = key;
      } else if (missing.has(key)) {
        post({ t: 'sample', slot: i, left: null, right: null, rate: 0 });
        slotKeys[i] = key;
      } else if (decoded.has(key)) {
        const d = decoded.get(key)!;
        post({ t: 'sample', slot: i, left: d.left, right: d.right, rate: d.rate });
        slotKeys[i] = key;
      } else {
        // Clear the slot until the sound has been read from storage.
        post({ t: 'sample', slot: i, left: null, right: null, rate: 0 });
        slotKeys[i] = '';
        decodeStored(key);
      }
    }
    if (c.instrument === 'WAVETABLE') post({ t: 'synth', channel: i, params: synthParamArray(c) });
  });
  const state = {
    bpm: project.bpm,
    master: project.masterVolume,
    metronome,
    channels: channels.map((c) => {
      const key = soundKey(c);
      const engineInstrument = key !== null ? INSTRUMENTS.SAMPLE.id : INSTRUMENTS[c.instrument].id;
      const trackIndex = c.mixerTrack == null ? -1 : tracks.findIndex((t) => t.id === c.mixerTrack);
      return {
        instrument: engineInstrument,
        volume: c.volume,
        pan: c.pan,
        mute: c.mute,
        solo: c.solo,
        sampler: [
          c.cutItself ? 1 : 0, c.trimStart, c.trimEnd, c.envDelay, c.attack, c.hold, c.decay,
          c.sustain, c.release, c.tension, Math.round(c.pitchSemis), c.reverse ? 1 : 0,
        ],
        boost: boostGain(c, key),
        insert: trackIndex,
      };
    }),
    inserts: tracks.map((t) => ({ volume: t.volume, pan: t.pan, mute: t.mute })),
    events: null as Int32Array | null,
    loopTicks: compiled.loopTicks,
  };
  if (!lastEvents || lastLoop !== compiled.loopTicks || !sameEvents(lastEvents, compiled.events)) {
    state.events = compiled.events;
    lastEvents = compiled.events;
    lastLoop = compiled.loopTicks;
  }
  post({ t: 'state', s: state });
}

function sameEvents(a: Int32Array, b: Int32Array) {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}

/** Decodes any audio file the browser understands (WAV, MP3, AAC, OGG, FLAC...). */
export async function decodeAudio(bytes: ArrayBuffer) {
  const rate = ctx?.sampleRate ?? 48000;
  const off = new OfflineAudioContext(1, 1, rate);
  const buf = await off.decodeAudioData(bytes.slice(0));
  const left = buf.getChannelData(0).slice();
  const right = buf.numberOfChannels > 1 ? buf.getChannelData(1).slice() : null;
  let peak = 0;
  for (const ch of [left, right]) if (ch) for (let i = 0; i < ch.length; i++) peak = Math.max(peak, Math.abs(ch[i]));
  return { left, right, rate: buf.sampleRate, peak, seconds: buf.duration };
}

/** Registers a freshly decoded sound so channels using `key` can play it. */
export function rememberDecoded(key: string, d: { left: Float32Array; right: Float32Array | null; rate: number; peak: number }) {
  decoded.set(key, d);
  missing.delete(key);
}

async function decodeStored(key: string) {
  if (pendingDecode.has(key)) return;
  pendingDecode.add(key);
  try {
    const bytes = await loadSampleBytes(key);
    // Made on another device: the channel stays silent until the sound is imported here.
    if (!bytes) missing.add(key);
    else decoded.set(key, await decodeAudio(bytes));
    slotKeys.forEach((k, i) => {
      if (k === '') slotKeys[i] = null;
    });
    if (lastProject && lastCompiled) syncEngine(lastProject, lastCompiled);
  } catch {
    missing.add(key); // unreadable file: stays silent
  } finally {
    pendingDecode.delete(key);
  }
}

/** True when a channel's sound file isn't on this device (e.g. a kit sound from the Android app). */
export function isMissingSound(c: Channel): boolean {
  return c.instrument === 'SAMPLE' && !!c.sample && missing.has(c.sample);
}

export const UNSUPPORTED_ON_WEB: Partial<Record<string, string>> = {
  SOUNDFONT: 'SoundFont sounds are not on the web version yet',
  MACRO: 'Macro sounds are not on the web version yet',
  MODELED: 'Modeled sounds are not on the web version yet',
  MULTISAMPLE: 'Multisample sounds are not on the web version yet',
};

// The project format, shared with the Android app (app/src/main/java/com/mobiledaw/model/Project.kt).
// Field names and defaults must match the Kotlin classes so a project file opens on both.
// Objects keep any fields this version doesn't know about, so nothing is lost on a round trip.

export const TICKS_PER_BEAT = 96;
export const TICKS_PER_STEP = TICKS_PER_BEAT / 4;
export const STEPS_PER_BAR = 16;
export const TICKS_PER_BAR = TICKS_PER_STEP * STEPS_PER_BAR;
export const MAX_BARS = 16;
export const DEFAULT_PLAYLIST_ROWS = 8;
export const MAX_PLAYLIST_ROWS = 64;
export const MAX_CHANNELS = 32;
export const VOCALS_TRACK_ID = 1;

export type InstrumentName =
  | 'KICK' | 'SNARE' | 'HAT' | 'CLAP' | 'BASS' | 'LEAD' | 'PLUCK' | 'PAD'
  | 'SAMPLE' | 'SOUNDFONT' | 'MACRO' | 'MODELED' | 'MULTISAMPLE' | 'WAVETABLE';

export interface InstrumentInfo {
  id: number;
  label: string;
  isDrum: boolean;
  defaultPitch: number;
}

/** Ids must match the enum in app/src/main/cpp/Synth.h. */
export const INSTRUMENTS: Record<InstrumentName, InstrumentInfo> = {
  KICK: { id: 0, label: 'Kick', isDrum: true, defaultPitch: 60 },
  SNARE: { id: 1, label: 'Snare', isDrum: true, defaultPitch: 60 },
  HAT: { id: 2, label: 'Hi-hat', isDrum: true, defaultPitch: 60 },
  CLAP: { id: 3, label: 'Clap', isDrum: true, defaultPitch: 60 },
  BASS: { id: 4, label: 'Bass', isDrum: false, defaultPitch: 36 },
  LEAD: { id: 5, label: 'Lead', isDrum: false, defaultPitch: 72 },
  PLUCK: { id: 6, label: 'Pluck', isDrum: false, defaultPitch: 72 },
  PAD: { id: 7, label: 'Pad', isDrum: false, defaultPitch: 60 },
  SAMPLE: { id: 8, label: 'Sample', isDrum: true, defaultPitch: 60 },
  SOUNDFONT: { id: 9, label: 'SoundFont', isDrum: false, defaultPitch: 60 },
  MACRO: { id: 10, label: 'Macro', isDrum: false, defaultPitch: 60 },
  MODELED: { id: 11, label: 'Modeled', isDrum: false, defaultPitch: 60 },
  MULTISAMPLE: { id: 12, label: 'Multisample', isDrum: false, defaultPitch: 60 },
  WAVETABLE: { id: 13, label: 'Wavetable', isDrum: false, defaultPitch: 60 },
};

export const isBuiltInDrum = (i: InstrumentName) => INSTRUMENTS[i].id <= 3;

export interface Note {
  start: number;
  length: number;
  pitch: number;
  velocity: number;
}

export interface SynthPatch {
  preset: string;
  category: string;
  params: Record<string, number>;
  edited: boolean;
}

export interface Channel {
  id: number;
  name: string;
  instrument: InstrumentName;
  volume: number;
  pan: number;
  mute: boolean;
  solo: boolean;
  sample: string | null;
  cutItself: boolean;
  trimStart: number;
  trimEnd: number;
  envDelay: number;
  attack: number;
  hold: number;
  decay: number;
  sustain: number;
  release: number;
  tension: number;
  pitchSemis: number;
  reverse: boolean;
  boostDb: number;
  normalize: boolean;
  synth: SynthPatch | null;
  variant: number;
  source: string | null;
  pitch: number | null;
  mixerTrack: number | null;
  [extra: string]: unknown;
}

export interface Pattern {
  id: number;
  name: string;
  bars: number;
  /** Notes per channel id (JSON object keys are strings). */
  notes: Record<string, Note[]>;
  [extra: string]: unknown;
}

export interface Clip {
  lane: number;
  bar: number;
  patternId: number;
  nudge: number;
  trim: number;
  length: number;
  muted: boolean;
  [extra: string]: unknown;
}

export interface MixerTrack {
  id: number;
  name: string;
  volume: number;
  pan: number;
  mute: boolean;
  effects: unknown[];
  [extra: string]: unknown;
}

export interface Project {
  name: string;
  bpm: number;
  key: number;
  minor: boolean;
  masterVolume: number;
  channels: Channel[];
  patterns: Pattern[];
  clips: Clip[];
  audioClips: unknown[];
  mixerTracks: MixerTrack[];
  recordingTrackId: number | null;
  masterEffects: unknown[];
  playlistRows: number;
  [extra: string]: unknown;
}

export function defaultChannel(id: number, name: string, instrument: InstrumentName, extra: Partial<Channel> = {}): Channel {
  return {
    id, name, instrument,
    volume: 0.8, pan: 0, mute: false, solo: false, sample: null,
    cutItself: false, trimStart: 0, trimEnd: 1,
    envDelay: 0, attack: 0, hold: 0, decay: 0, sustain: 1, release: 0, tension: 0,
    pitchSemis: 0, reverse: false, boostDb: 0, normalize: false,
    synth: null, variant: 0, source: null, pitch: null, mixerTrack: null,
    ...extra,
  };
}

/** The "Vocals" track every new project starts with, same as MixerTrack.vocals() on Android. */
export function vocalsTrack(): MixerTrack {
  return {
    id: VOCALS_TRACK_ID,
    name: 'Vocals',
    volume: 1,
    pan: 0,
    mute: false,
    effects: [
      { id: 1, type: 'PITCH', bypass: false, pitch: {} },
      {
        id: 2, type: 'EQ', bypass: false,
        eq: {
          bands: [
            { type: 'LOW_CUT', freq: 80, gain: 0, q: 0.71, slope: 2, on: true },
            { type: 'BELL', freq: 300, gain: 0, q: 1, slope: 2, on: true },
            { type: 'BELL', freq: 3000, gain: 0, q: 1, slope: 2, on: true },
            { type: 'HIGH_SHELF', freq: 10000, gain: 0, q: 0.71, slope: 2, on: true },
          ],
          output: 0,
        },
      },
    ],
  };
}

const num = (v: unknown, d: number) => (typeof v === 'number' && isFinite(v) ? v : d);
const bool = (v: unknown, d: boolean) => (typeof v === 'boolean' ? v : d);
const str = (v: unknown, d: string) => (typeof v === 'string' ? v : d);
const obj = (v: unknown): Record<string, unknown> => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : {});
const arr = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);

/** Reads a project from JSON (Android or web), filling in defaults like the Kotlin decoder does. */
export function normalizeProject(raw: unknown): Project {
  const p = obj(raw);
  const channels = arr(p.channels).map((c0) => {
    const c = obj(c0);
    const instrument = (typeof c.instrument === 'string' && c.instrument in INSTRUMENTS ? c.instrument : 'LEAD') as InstrumentName;
    const base = defaultChannel(num(c.id, 0), str(c.name, INSTRUMENTS[instrument].label), instrument);
    const out: Channel = { ...base, ...c, id: num(c.id, 0), name: str(c.name, base.name), instrument } as Channel;
    for (const k of Object.keys(base) as (keyof Channel)[]) {
      const d = base[k];
      if (typeof d === 'number') (out as Record<string, unknown>)[k] = num(c[k], d);
      if (typeof d === 'boolean') (out as Record<string, unknown>)[k] = bool(c[k], d);
    }
    if (out.synth) {
      const s = obj(out.synth);
      out.synth = { ...s, preset: str(s.preset, 'Init'), category: str(s.category, ''), params: obj(s.params) as Record<string, number>, edited: bool(s.edited, false) };
    }
    return out;
  });
  const patterns = arr(p.patterns).map((x0) => {
    const x = obj(x0);
    const notes: Record<string, Note[]> = {};
    for (const [k, list] of Object.entries(obj(x.notes))) {
      notes[k] = arr(list).map((n0) => {
        const n = obj(n0);
        return { ...n, start: num(n.start, 0), length: num(n.length, TICKS_PER_STEP), pitch: num(n.pitch, 60), velocity: num(n.velocity, 100) };
      });
    }
    return { ...x, id: num(x.id, 0), name: str(x.name, 'Pattern'), bars: Math.max(1, num(x.bars, 1)), notes } as Pattern;
  });
  const clips = arr(p.clips).map((c0) => {
    const c = obj(c0);
    return {
      ...c,
      lane: num(c.lane, 0), bar: num(c.bar, 0), patternId: num(c.patternId, 0),
      nudge: num(c.nudge, 0), trim: num(c.trim, 0), length: num(c.length, 0), muted: bool(c.muted, false),
    } as Clip;
  });
  const mixerTracks = 'mixerTracks' in p
    ? arr(p.mixerTracks).map((t0) => {
        const t = obj(t0);
        return { ...t, id: num(t.id, 1), name: str(t.name, 'Track'), volume: num(t.volume, 1), pan: num(t.pan, 0), mute: bool(t.mute, false), effects: arr(t.effects) } as MixerTrack;
      })
    : [vocalsTrack()];
  return {
    ...p,
    name: str(p.name, 'Untitled'),
    bpm: num(p.bpm, 140),
    key: num(p.key, 0),
    minor: bool(p.minor, true),
    masterVolume: num(p.masterVolume, 0.7),
    channels,
    patterns: patterns.length ? patterns : [{ id: nextIdOf(channels, []), name: 'Pattern 1', bars: 1, notes: {} }],
    clips,
    audioClips: arr(p.audioClips),
    mixerTracks,
    recordingTrackId: 'recordingTrackId' in p ? (p.recordingTrackId as number | null) : VOCALS_TRACK_ID,
    masterEffects: arr(p.masterEffects),
    playlistRows: num(p.playlistRows, DEFAULT_PLAYLIST_ROWS),
  };
}

function nextIdOf(channels: { id: number }[], patterns: { id: number }[], audio: unknown[] = []): number {
  const ids = [...channels.map((c) => c.id), ...patterns.map((p) => p.id), ...audio.map((a) => num(obj(a).id, 0))];
  return ids.length ? Math.max(...ids) + 1 : 1;
}

export const nextId = (p: Project) => nextIdOf(p.channels, p.patterns, p.audioClips);

export const patternTicks = (p: Pattern) => p.bars * TICKS_PER_BAR;
export const notesFor = (p: Pattern, channelId: number): Note[] => p.notes[String(channelId)] ?? [];
export const stepPitch = (c: Channel) => c.pitch ?? INSTRUMENTS[c.instrument].defaultPitch;
export const clipStart = (c: Clip) => c.bar * TICKS_PER_BAR + c.nudge;

export function findPattern(project: Project, id: number): Pattern | undefined {
  return project.patterns.find((p) => p.id === id);
}

/** Ticks a pattern clip lasts: the shown part of its pattern, never past the pattern's end. */
export function clipLength(project: Project, clip: Clip): number {
  const pt = findPattern(project, clip.patternId);
  const total = pt ? patternTicks(pt) : TICKS_PER_BAR;
  const rest = total - clip.trim;
  const len = clip.length > 0 ? Math.min(clip.length, rest) : rest;
  return Math.max(TICKS_PER_STEP, len);
}

export const clipEnd = (project: Project, clip: Clip) => clipStart(clip) + clipLength(project, clip);

/** Length in ticks of an audio clip on the timeline (follows the tempo; see AudioClip.lengthTicks). */
export function audioClipTicks(project: Project, a: unknown): { lane: number; start: number; end: number; name: string } {
  const c = obj(a);
  const semis = num(c.pitchSemis, 0);
  const stretch = str(c.stretch, 'RESAMPLE');
  const rate = stretch !== 'RESAMPLE' ? 1 : Math.pow(2, semis / 12);
  const len = Math.max(1, Math.floor((num(c.seconds, 0) / rate) * project.bpm / 60 * TICKS_PER_BEAT));
  const start = num(c.startTick, 0);
  return { lane: num(c.lane, 0), start, end: start + len, name: str(c.name, 'Audio') };
}

export function songBars(project: Project): number {
  let bars = 1;
  for (const c of project.clips) bars = Math.max(bars, Math.ceil(clipEnd(project, c) / TICKS_PER_BAR));
  for (const a of project.audioClips) bars = Math.max(bars, Math.ceil(audioClipTicks(project, a).end / TICKS_PER_BAR));
  return bars;
}

export function rowCount(project: Project): number {
  let rows = Math.max(1, project.playlistRows);
  for (const c of project.clips) rows = Math.max(rows, c.lane + 1);
  for (const a of project.audioClips) rows = Math.max(rows, audioClipTicks(project, a).lane + 1);
  return rows;
}

export function laneFree(project: Project, lane: number, from: number, to: number, ignore?: Clip): boolean {
  return project.clips.every((c) => c === ignore || c.lane !== lane || !(from < clipEnd(project, c) && clipStart(c) < to)) &&
    project.audioClips.every((a) => {
      const t = audioClipTicks(project, a);
      return t.lane !== lane || !(from < t.end && t.start < to);
    });
}

export const NOTE_NAMES = ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B'];
const mod12 = (n: number) => ((n % 12) + 12) % 12;
export const noteName = (pitch: number) => NOTE_NAMES[mod12(pitch)] + (Math.floor(pitch / 12) - 1);
export const isBlackKey = (pitch: number) => [1, 3, 6, 8, 10].includes(mod12(pitch));
const MAJOR = [0, 2, 4, 5, 7, 9, 11];
const MINOR = [0, 2, 3, 5, 7, 8, 10];
export const inScale = (pitch: number, key: number, minor: boolean) => (minor ? MINOR : MAJOR).includes(mod12(pitch - key));
export const keyLabel = (p: Project) => NOTE_NAMES[mod12(p.key)] + (p.minor ? 'm' : '');

/** The project a first visit opens with: same short beat as the Android app's DemoProject. */
export function demoProject(name = 'First beat'): Project {
  const S = TICKS_PER_STEP;
  const B = TICKS_PER_BAR;
  const channels = [
    defaultChannel(1, 'Kick', 'KICK', { volume: 0.9 }),
    defaultChannel(2, 'Snare', 'SNARE', { volume: 0.75 }),
    defaultChannel(3, 'Hat', 'HAT', { volume: 0.6, pan: 0.2 }),
    defaultChannel(4, 'Clap', 'CLAP', { volume: 0.6, pan: -0.15 }),
    defaultChannel(5, 'Bass', 'BASS', { volume: 0.8 }),
    defaultChannel(6, 'Lead', 'LEAD', { volume: 0.55 }),
  ];
  const steps = (...s: number[]) => s.map((i) => ({ start: i * S, length: S, pitch: 60, velocity: 100 }));
  const drums: Pattern = {
    id: 7, name: 'Drums', bars: 1,
    notes: { 1: steps(0, 7, 10), 2: steps(4, 12), 3: steps(0, 2, 4, 6, 8, 10, 12, 14, 15), 4: steps(12) },
  };
  const bass = [36, 32, 39, 34].flatMap((root, bar) => [
    { start: bar * B, length: S * 6, pitch: root, velocity: 100 },
    { start: bar * B + S * 8, length: S * 4, pitch: root, velocity: 100 },
    { start: bar * B + S * 14, length: S * 2, pitch: root + 12, velocity: 100 },
  ]);
  const lead = [[0, 72], [6, 75], [12, 79], [16, 77], [22, 75], [28, 72], [32, 75], [38, 79], [44, 82], [48, 79], [54, 77], [60, 74]]
    .map(([step, pitch]) => ({ start: step * S, length: S * 5, pitch, velocity: 100 }));
  const melody: Pattern = { id: 8, name: 'Melody', bars: 4, notes: { 5: bass, 6: lead } };
  const clip = (lane: number, bar: number, patternId: number): Clip => ({ lane, bar, patternId, nudge: 0, trim: 0, length: 0, muted: false });
  const clips = [...Array.from({ length: 8 }, (_, i) => clip(0, i, 7)), clip(1, 0, 8), clip(1, 4, 8)];
  return normalizeProject({ name, bpm: 140, key: 0, minor: true, channels, patterns: [drums, melody], clips });
}

export function emptyProject(name: string): Project {
  return normalizeProject({
    name,
    channels: [
      defaultChannel(1, 'Kick', 'KICK', { volume: 0.9 }),
      defaultChannel(2, 'Snare', 'SNARE', { volume: 0.75 }),
      defaultChannel(3, 'Hat', 'HAT', { volume: 0.6 }),
      defaultChannel(4, 'Lead', 'LEAD', { volume: 0.55 }),
    ],
    patterns: [{ id: 5, name: 'Pattern 1', bars: 1, notes: {} }],
    clips: [],
  });
}

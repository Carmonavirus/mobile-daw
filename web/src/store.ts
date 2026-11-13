// App state and every edit the screens make. Each edit makes a new Project object (never
// mutates), syncs the engine and saves a moment later, so undo is just a list of old projects.
import { computed, signal } from '@preact/signals';
import * as engine from './audio/engine';
import { compilePattern, compileSong } from './model/compile';
import {
  type Channel, type Clip, type InstrumentName, type Note, type Pattern, type Project, type SynthPatch,
  INSTRUMENTS, MAX_BARS, MAX_CHANNELS, MAX_PLAYLIST_ROWS, TICKS_PER_BAR, TICKS_PER_STEP,
  clipEnd, clipStart, defaultChannel, demoProject, emptyProject, findPattern, laneFree, nextId, normalizeProject,
  notesFor, patternTicks, rowCount, stepPitch,
} from './model/project';
import presetFile from '../../app/src/main/assets/wavesynth/factory_presets.json';
import * as storage from './storage';

export type Tab = 'playlist' | 'rack' | 'piano' | 'mixer';
export type Mode = 'pattern' | 'song';

export interface SynthPreset { name: string; category: string; params: Record<string, number> }
export const factoryPresets: SynthPreset[] = (presetFile as unknown as { presets: SynthPreset[] }).presets;

export const project = signal<Project>(demoProject());
export const tab = signal<Tab>('rack');
export const mode = signal<Mode>('pattern');
export const patternId = signal<number>(project.value.patterns[0].id);
export const channelId = signal<number>(project.value.channels[0]?.id ?? 0);
export const loaded = signal(false);
export const toast = signal<string | null>(null);
export const metronomeOn = signal(false);

export const currentPattern = computed<Pattern>(
  () => findPattern(project.value, patternId.value) ?? project.value.patterns[0],
);
export const currentChannel = computed<Channel | undefined>(
  () => project.value.channels.find((c) => c.id === channelId.value) ?? project.value.channels[0],
);
export const loopTicks = computed(() =>
  mode.value === 'song' ? compileSong(project.value).loopTicks : patternTicks(currentPattern.value),
);

const undoStack: Project[] = [];
const redoStack: Project[] = [];
export const canUndo = signal(false);
export const canRedo = signal(false);

let saveTimer: number | undefined;

export function showToast(text: string) {
  toast.value = text;
  window.setTimeout(() => {
    if (toast.value === text) toast.value = null;
  }, 2600);
}

export function sync() {
  const p = project.value;
  const compiled = mode.value === 'song' ? compileSong(p) : compilePattern(p, currentPattern.value);
  engine.syncEngine(p, compiled);
}

function scheduleSave() {
  window.clearTimeout(saveTimer);
  const snapshot = project.value;
  saveTimer = window.setTimeout(() => storage.saveProject(snapshot).catch(() => showToast('Could not save')), 600);
}

/** Applies an edit. `undoable: false` for things like moving a slider mid-drag. */
export function update(fn: (p: Project) => Project, undoable = true) {
  const before = project.value;
  const after = fn(before);
  if (after === before) return;
  if (undoable) {
    undoStack.push(before);
    if (undoStack.length > 200) undoStack.shift();
    redoStack.length = 0;
  }
  project.value = after;
  canUndo.value = undoStack.length > 0;
  canRedo.value = false;
  sync();
  scheduleSave();
}

/** Call once before a drag that uses update(..., false), so the whole drag undoes in one step. */
export function checkpoint() {
  undoStack.push(project.value);
  redoStack.length = 0;
  canUndo.value = true;
  canRedo.value = false;
}

function restore(p: Project) {
  const renamed = p.name !== project.value.name;
  project.value = renamed ? { ...p, name: project.value.name } : p;
  fixSelection();
  sync();
  scheduleSave();
}

export function undo() {
  const p = undoStack.pop();
  if (!p) return;
  redoStack.push(project.value);
  restore(p);
  canUndo.value = undoStack.length > 0;
  canRedo.value = true;
}

export function redo() {
  const p = redoStack.pop();
  if (!p) return;
  undoStack.push(project.value);
  restore(p);
  canUndo.value = true;
  canRedo.value = redoStack.length > 0;
}

function fixSelection() {
  const p = project.value;
  if (!findPattern(p, patternId.value)) patternId.value = p.patterns[0].id;
  if (!p.channels.some((c) => c.id === channelId.value)) channelId.value = p.channels[0]?.id ?? 0;
}

// ---------------------------------------------------------------- projects

export async function openProject(p: Project) {
  window.clearTimeout(saveTimer);
  engine.panic();
  undoStack.length = 0;
  redoStack.length = 0;
  canUndo.value = false;
  canRedo.value = false;
  project.value = p;
  patternId.value = p.patterns[0].id;
  channelId.value = p.channels[0]?.id ?? 0;
  mode.value = p.clips.length ? 'song' : 'pattern';
  engine.seek(0);
  sync();
  await storage.saveProject(p);
}

export async function boot() {
  const last = storage.lastOpened();
  let p: Project | null = null;
  try {
    const json = last ? await storage.loadProject(last) : null;
    if (json) p = normalizeProject(JSON.parse(json));
    else {
      const list = await storage.listProjects();
      if (list.length) p = normalizeProject(JSON.parse(list[0].json));
    }
  } catch {
    p = null;
  }
  await openProject(p ?? demoProject());
  loaded.value = true;
}

export async function newProject() {
  await openProject(emptyProject(await storage.uniqueName('New song')));
  tab.value = 'rack';
}

export async function openByName(name: string) {
  const json = await storage.loadProject(name);
  if (json) await openProject(normalizeProject(JSON.parse(json)));
}

export async function importProjectFile(file: File) {
  try {
    const p = normalizeProject(JSON.parse(await file.text()));
    if (!p.channels.length && !p.patterns.length) throw new Error('empty');
    const base = p.name || file.name.replace(/\.json$/i, '');
    await openProject({ ...p, name: await storage.uniqueName(base) });
    showToast(`Opened “${project.value.name}”`);
  } catch {
    showToast('That file is not a project');
  }
}

export function exportProjectFile() {
  const p = project.value;
  const blob = new Blob([JSON.stringify(p, null, 2)], { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = storage.fileName(p.name) + '.json';
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 2000);
}

export async function renameProject(name: string) {
  const clean = name.trim();
  if (!clean || clean === project.value.name) return;
  if (await storage.projectExists(clean)) {
    showToast('A song with that name already exists');
    return;
  }
  const old = project.value.name;
  project.value = { ...project.value, name: clean };
  await storage.saveProject(project.value);
  await storage.deleteProject(old);
}

export async function deleteProjectByName(name: string) {
  await storage.deleteProject(name);
  if (name === project.value.name) {
    const list = await storage.listProjects();
    if (list.length) await openByName(list[0].name);
    else await openProject(demoProject(await storage.uniqueName('First beat')));
  }
}

// ---------------------------------------------------------------- transport

let playFrom = 0;

export function togglePlay() {
  if (engine.playing.value) {
    engine.setPlaying(false);
    engine.seek(playFrom);
  } else {
    playFrom = Math.min(engine.position.value, Math.max(0, loopTicks.value - 1));
    engine.setPlaying(true);
  }
}

export function stopAll() {
  engine.panic();
  engine.seek(0);
  playFrom = 0;
}

export function seekTo(tick: number) {
  const t = Math.max(0, Math.min(loopTicks.value - 1, Math.round(tick)));
  engine.seek(t);
  if (!engine.playing.value) playFrom = t;
}

export function setMode(m: Mode) {
  if (mode.value === m) return;
  mode.value = m;
  engine.seek(0);
  playFrom = 0;
  sync();
}

export function toggleMetronome() {
  metronomeOn.value = !metronomeOn.value;
  engine.setMetronome(metronomeOn.value);
}

export function setBpm(bpm: number) {
  const v = Math.round(Math.min(300, Math.max(40, bpm)));
  update((p) => (p.bpm === v ? p : { ...p, bpm: v }));
}

export function setKey(key: number, minor: boolean) {
  update((p) => ({ ...p, key, minor }));
}

// ---------------------------------------------------------------- channels

const channelIndex = (id: number) => project.value.channels.findIndex((c) => c.id === id);

export function previewChannel(c: Channel, pitch = stepPitch(c)) {
  if (engine.playing.value) return;
  const i = channelIndex(c.id);
  if (i >= 0) engine.preview(i, pitch);
}

export function auditionChannel(c: Channel, pitch = stepPitch(c)) {
  const i = channelIndex(c.id);
  if (i >= 0) engine.preview(i, pitch);
}

export function updateChannel(id: number, change: Partial<Channel>, undoable = true) {
  update((p) => ({ ...p, channels: p.channels.map((c) => (c.id === id ? { ...c, ...change } : c)) }), undoable);
}

function uniqueChannelName(p: Project, base: string): string {
  const names = new Set(p.channels.map((c) => c.name));
  if (!names.has(base)) return base;
  let i = 2;
  while (names.has(`${base} ${i}`)) i++;
  return `${base} ${i}`;
}

export function addChannel(instrument: InstrumentName, extra: Partial<Channel> = {}, name?: string): number | null {
  if (project.value.channels.length >= MAX_CHANNELS) {
    showToast('That’s the most channels a song can have');
    return null;
  }
  let id = 0;
  update((p) => {
    id = nextId(p);
    const c = defaultChannel(id, uniqueChannelName(p, name ?? INSTRUMENTS[instrument].label), instrument, {
      volume: INSTRUMENTS[instrument].isDrum ? 0.8 : 0.6,
      ...extra,
    });
    return { ...p, channels: [...p.channels, c] };
  });
  channelId.value = id;
  return id;
}

export function synthPatchFrom(preset: SynthPreset): SynthPatch {
  return { preset: preset.name, category: preset.category, params: { ...preset.params }, edited: false };
}

/** Changes what a channel plays, keeping its notes and mixer settings. */
export function swapSound(id: number, instrument: InstrumentName, extra: Partial<Channel> = {}, name?: string) {
  update((p) => ({
    ...p,
    channels: p.channels.map((c) =>
      c.id === id
        ? { ...c, instrument, sample: null, synth: null, source: null, pitch: null, variant: 0, ...extra, name: name ?? c.name }
        : c,
    ),
  }));
}

export function deleteChannel(id: number) {
  update((p) => ({
    ...p,
    channels: p.channels.filter((c) => c.id !== id),
    patterns: p.patterns.map((pt) => {
      const notes = { ...pt.notes };
      delete notes[String(id)];
      return { ...pt, notes };
    }),
  }));
  fixSelection();
}

export function moveChannel(id: number, delta: number) {
  update((p) => {
    const i = p.channels.findIndex((c) => c.id === id);
    const j = i + delta;
    if (i < 0 || j < 0 || j >= p.channels.length) return p;
    const list = [...p.channels];
    [list[i], list[j]] = [list[j], list[i]];
    return { ...p, channels: list };
  });
}

/** Imports an audio file as a sound: stored on this device under a path like the app's kits. */
export async function importSound(file: File): Promise<{ path: string; name: string } | null> {
  try {
    const bytes = await file.arrayBuffer();
    const d = await engine.decodeAudio(bytes);
    const path = `imported/${Date.now().toString(36)}-${file.name.replace(/[^A-Za-z0-9._ -]/g, '_')}`;
    await storage.saveSampleBytes(path, bytes);
    engine.rememberDecoded(path, d);
    return { path, name: file.name.replace(/\.[^.]+$/, '').slice(0, 24) || 'Sound' };
  } catch {
    showToast('Could not read that sound file');
    return null;
  }
}

// ---------------------------------------------------------------- notes

export function updateNotes(chId: number, fn: (notes: Note[]) => Note[], undoable = true, ptId = patternId.value) {
  update((p) => ({
    ...p,
    patterns: p.patterns.map((pt) =>
      pt.id === ptId ? { ...pt, notes: { ...pt.notes, [String(chId)]: fn(notesFor(pt, chId)) } } : pt,
    ),
  }), undoable);
}

export function toggleStep(c: Channel, step: number) {
  const tick = step * TICKS_PER_STEP;
  const has = notesFor(currentPattern.value, c.id).some((n) => n.start === tick);
  if (has) updateNotes(c.id, (list) => list.filter((n) => n.start !== tick));
  else {
    const pitch = stepPitch(c);
    updateNotes(c.id, (list) => [...list, { start: tick, length: TICKS_PER_STEP, pitch, velocity: 100 }]);
    previewChannel(c, pitch);
  }
}

// ---------------------------------------------------------------- patterns

export function addPattern(): number {
  let id = 0;
  update((p) => {
    id = nextId(p);
    const used = new Set(p.patterns.map((x) => x.name));
    let n = p.patterns.length + 1;
    while (used.has(`Pattern ${n}`)) n++;
    return { ...p, patterns: [...p.patterns, { id, name: `Pattern ${n}`, bars: 1, notes: {} }] };
  });
  patternId.value = id;
  sync();
  return id;
}

export function selectPattern(id: number) {
  patternId.value = id;
  sync();
}

export function updatePattern(id: number, change: Partial<Pattern>) {
  update((p) => ({ ...p, patterns: p.patterns.map((x) => (x.id === id ? { ...x, ...change } : x)) }));
}

export function setPatternBars(id: number, bars: number) {
  updatePattern(id, { bars: Math.max(1, Math.min(MAX_BARS, bars)) });
}

export function duplicatePattern(id: number) {
  let newId = 0;
  update((p) => {
    const src = findPattern(p, id);
    if (!src) return p;
    newId = nextId(p);
    const copy: Pattern = { ...structuredClone(src), id: newId, name: src.name + ' copy' };
    return { ...p, patterns: [...p.patterns, copy] };
  });
  if (newId) selectPattern(newId);
}

export function deletePattern(id: number) {
  if (project.value.patterns.length <= 1) {
    showToast('A song needs at least one pattern');
    return;
  }
  update((p) => ({ ...p, patterns: p.patterns.filter((x) => x.id !== id), clips: p.clips.filter((c) => c.patternId !== id) }));
  fixSelection();
  sync();
}

// ---------------------------------------------------------------- playlist

export function placeClip(lane: number, bar: number, ptId = patternId.value): boolean {
  const p = project.value;
  const pt = findPattern(p, ptId);
  if (!pt) return false;
  const start = bar * TICKS_PER_BAR;
  if (!laneFree(p, lane, start, start + patternTicks(pt))) return false;
  update((q) => ({ ...q, clips: [...q.clips, { lane, bar, patternId: ptId, nudge: 0, trim: 0, length: 0, muted: false }] }));
  return true;
}

export function replaceClipAt(index: number, next: Clip, undoable = true) {
  update((p) => ({ ...p, clips: p.clips.map((c, i) => (i === index ? next : c)) }), undoable);
}

export function deleteClipAt(index: number) {
  update((p) => ({ ...p, clips: p.clips.filter((_, i) => i !== index) }));
}

export function clipFits(p: Project, clip: Clip, ignoreIndex: number) {
  return laneFree(p, clip.lane, clipStart(clip), clipEnd(p, clip), p.clips[ignoreIndex]);
}

export function addPlaylistRow() {
  update((p) => ({ ...p, playlistRows: Math.min(MAX_PLAYLIST_ROWS, rowCount(p) + 1) }));
}

// ---------------------------------------------------------------- mixer

export function setMasterVolume(v: number, undoable = true) {
  update((p) => ({ ...p, masterVolume: v }), undoable);
}

// Flattens a project into the note list the engine plays (same as SequenceCompiler.kt).
import {
  type Note, type Pattern, type Project, TICKS_PER_BAR,
  clipLength, clipStart, findPattern, notesFor, patternTicks, songBars,
} from './project';

export interface Compiled {
  /** [channel, start, length, pitch, velocity] per note. */
  events: Int32Array;
  loopTicks: number;
}

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));

function add(out: number[], channel: number, start: number, n: Note, maxLength: number) {
  out.push(channel, start, clamp(n.length, 1, Math.max(1, maxLength)), n.pitch, clamp(n.velocity, 1, 127));
}

export function compilePattern(project: Project, pattern: Pattern): Compiled {
  const out: number[] = [];
  const len = patternTicks(pattern);
  project.channels.forEach((ch, index) => {
    for (const n of notesFor(pattern, ch.id)) if (n.start < len) add(out, index, n.start, n, len);
  });
  return { events: Int32Array.from(out), loopTicks: len };
}

export function compileSong(project: Project): Compiled {
  const out: number[] = [];
  for (const clip of project.clips) {
    if (clip.muted) continue;
    const pattern = findPattern(project, clip.patternId);
    if (!pattern) continue;
    const from = clip.trim;
    const to = Math.min(patternTicks(pattern), from + clipLength(project, clip));
    const offset = clipStart(clip) - from;
    project.channels.forEach((ch, index) => {
      for (const n of notesFor(pattern, ch.id)) if (n.start >= from && n.start < to) add(out, index, offset + n.start, n, to - n.start);
    });
  }
  return { events: Int32Array.from(out), loopTicks: songBars(project) * TICKS_PER_BAR };
}

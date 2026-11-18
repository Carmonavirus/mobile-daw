import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks';
import { playing, position } from '../audio/engine';
import {
  type Note, TICKS_PER_BAR, TICKS_PER_BEAT, TICKS_PER_STEP, inScale, isBlackKey, noteName, notesFor, patternTicks, stepPitch,
} from '../model/project';
import * as s from '../store';
import { Icon } from './common';
import { PatternChips } from './Rack';

const LOW = 24; // C1
const HIGH = 107; // B7
const ROWS = HIGH - LOW + 1;
const ROW_H = 20;
const GRIDS: [string, number][] = [['1/32', 12], ['1/16', 24], ['1/8', 48], ['1/4', 96], ['1/2', 192]];

let lastLength = TICKS_PER_STEP; // FL-style: new notes take the length of the last one you touched
let savedZoom = 1.2; // px per tick
let ignoreClicksUntil = 0; // a tap that deleted a note must not also draw one

export function PianoRoll() {
  const p = s.project.value;
  const pt = s.currentPattern.value;
  const ch = s.currentChannel.value;
  const [snap, setSnap] = useState(TICKS_PER_STEP);
  const [allLabels, setAllLabels] = useState(false);
  const [zoom, setZoomState] = useState(savedZoom);
  const scroller = useRef<HTMLDivElement>(null);
  const keys = useRef<HTMLDivElement>(null);
  const setZoom = (z: number) => {
    savedZoom = Math.min(6, Math.max(0.25, z));
    setZoomState(savedZoom);
  };

  const total = patternTicks(pt);
  const [viewW, setViewW] = useState(600);
  useLayoutEffect(() => {
    const el = scroller.current;
    if (!el) return;
    const ro = new ResizeObserver(() => setViewW(el.clientWidth));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  const width = Math.max(viewW, total * zoom);
  const pxPerTick = width / total;

  // Start scrolled to the channel's notes (or its usual pitch).
  useEffect(() => {
    const el = scroller.current;
    if (!el || !ch) return;
    const ns = notesFor(pt, ch.id);
    const center = ns.length ? ns.reduce((a, n) => a + n.pitch, 0) / ns.length : stepPitch(ch);
    el.scrollTop = (HIGH - center) * ROW_H - el.clientHeight / 2;
  }, [ch?.id, pt.id]);

  // Ctrl/⌘ + wheel or trackpad pinch zooms time.
  useEffect(() => {
    const el = scroller.current;
    if (!el) return;
    const onWheel = (e: WheelEvent) => {
      if (!e.ctrlKey && !e.metaKey) return;
      e.preventDefault();
      setZoom(savedZoom * (e.deltaY < 0 ? 1.15 : 1 / 1.15));
    };
    el.addEventListener('wheel', onWheel, { passive: false });
    return () => el.removeEventListener('wheel', onWheel);
  }, []);

  if (!ch) return <div class="screen"><div class="empty">Add a channel in the rack first.</div></div>;

  const notes = notesFor(pt, ch.id);
  const yOf = (pitch: number) => (HIGH - pitch) * ROW_H;

  const addAt = (e: MouseEvent) => {
    if (performance.now() < ignoreClicksUntil) return;
    const grid = e.currentTarget as HTMLDivElement;
    const r = grid.getBoundingClientRect();
    const x = e.clientX - r.left;
    const y = e.clientY - r.top;
    const pitch = HIGH - Math.floor(y / ROW_H);
    const tick = Math.floor(x / pxPerTick / snap) * snap;
    if (pitch < LOW || pitch > HIGH || tick >= total) return;
    const length = Math.min(lastLength, total - tick);
    s.updateNotes(ch.id, (list) => [...list, { start: tick, length, pitch, velocity: 100 }]);
    s.previewChannel(ch, pitch);
  };

  const showPlayhead = playing.value && s.mode.value === 'pattern';
  const lines = [];
  for (let t = 0; t <= total; t += snap) {
    const cls = t % TICKS_PER_BAR === 0 ? ' bar' : t % TICKS_PER_BEAT === 0 ? ' beat' : '';
    lines.push(<div key={t} class={'roll-vline' + cls} style={{ left: t * pxPerTick }} />);
  }
  const ghosts = p.channels.filter((c) => c.id !== ch.id).flatMap((c) => notesFor(pt, c.id).map((n, i) => ({ n, key: `${c.id}-${i}` })));

  return (
    <div class="screen">
      <div class="chips">
        {p.channels.map((c) => (
          <button key={c.id} class={'chip' + (c.id === ch.id ? ' on' : '')} onClick={() => (s.channelId.value = c.id)}>{c.name}</button>
        ))}
      </div>
      <PatternChips />
      <div class="toolbar">
        <select class="btn" value={snap} aria-label="Grid" onChange={(e) => setSnap(Number((e.target as HTMLSelectElement).value))}>
          {GRIDS.map(([label, t]) => <option key={t} value={t}>Grid {label}</option>)}
        </select>
        <div class="seg">
          <button class={allLabels ? '' : 'on'} onClick={() => setAllLabels(false)}>Key</button>
          <button class={allLabels ? 'on' : ''} onClick={() => setAllLabels(true)}>All</button>
        </div>
        <span class="spacer" />
        <button class="icon-btn" aria-label="Zoom out" onClick={() => setZoom(zoom / 1.3)}><Icon name="zoomOut" /></button>
        <button class="icon-btn" aria-label="Zoom in" onClick={() => setZoom(zoom * 1.3)}><Icon name="zoomIn" /></button>
      </div>
      <div class="roll-wrap">
        <div class="roll-keys" ref={keys}>
          <div style={{ position: 'relative', height: ROWS * ROW_H }}>
            {Array.from({ length: ROWS }, (_, i) => {
              const pitch = HIGH - i;
              const label = pitch % 12 === 0 || allLabels || inScale(pitch, p.key, p.minor) ? noteName(pitch) : '';
              return (
                <div key={pitch} class={'key' + (isBlackKey(pitch) ? ' black' : '') + (pitch % 12 === 0 ? ' c' : '')}
                  style={{ top: i * ROW_H, height: ROW_H }}
                  onPointerDown={() => s.auditionChannel(ch, pitch)}>{label}</div>
              );
            })}
          </div>
        </div>
        <div class="roll-scroll" ref={scroller}
          onScroll={(e) => { if (keys.current) keys.current.scrollTop = (e.currentTarget as HTMLDivElement).scrollTop; }}>
          <div class="roll-grid" style={{ width, height: ROWS * ROW_H }} onClick={addAt}>
            {Array.from({ length: ROWS }, (_, i) => {
              const pitch = HIGH - i;
              const bg = !allLabels && inScale(pitch, p.key, p.minor) ? 'var(--grid-scale)' : isBlackKey(pitch) ? 'var(--grid-black)' : 'var(--grid-white)';
              return <div key={pitch} class="roll-row" style={{ top: i * ROW_H, height: ROW_H, background: bg, pointerEvents: 'none' }} />;
            })}
            <div style={{ pointerEvents: 'none' }}>{lines}</div>
            {ghosts.map(({ n, key }) => (
              <div key={key} class="note ghost" style={{ left: n.start * pxPerTick, top: yOf(n.pitch) + 2, width: Math.max(4, n.length * pxPerTick - 1), height: ROW_H - 4 }} />
            ))}
            {notes.map((n, i) => (
              <NoteBar key={i} n={n} index={i} channelId={ch.id} pxPerTick={pxPerTick} snap={snap} total={total} y={yOf(n.pitch)}
                label={noteName(n.pitch)} />
            ))}
            {showPlayhead && <div class="playhead" style={{ left: position.value * pxPerTick }} />}
          </div>
        </div>
      </div>
    </div>
  );
}

function NoteBar({ n, index, channelId, pxPerTick, snap, total, y, label }: {
  n: Note; index: number; channelId: number; pxPerTick: number; snap: number; total: number; y: number; label: string;
}) {
  const drag = useRef<{ x: number; y: number; resize: boolean; moved: boolean; orig: Note; lastPitch: number } | null>(null);
  const w = Math.max(6, n.length * pxPerTick - 1);

  const onDown = (e: PointerEvent) => {
    e.stopPropagation();
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    const r = (e.currentTarget as HTMLElement).getBoundingClientRect();
    const fromRight = r.right - e.clientX;
    drag.current = { x: e.clientX, y: e.clientY, resize: fromRight < Math.max(10, Math.min(24, r.width * 0.3)), moved: false, orig: n, lastPitch: n.pitch };
  };
  const onMove = (e: PointerEvent) => {
    const d = drag.current;
    if (!d) return;
    const dx = e.clientX - d.x;
    const dy = e.clientY - d.y;
    if (!d.moved) {
      if (Math.abs(dx) < 5 && Math.abs(dy) < 5) return;
      d.moved = true;
      s.checkpoint();
    }
    const dt = Math.round(dx / pxPerTick / snap) * snap;
    let next: Note;
    if (d.resize) {
      const length = Math.max(snap, Math.min(total - d.orig.start, Math.round((d.orig.length + dx / pxPerTick) / snap) * snap));
      next = { ...d.orig, length };
    } else {
      const start = Math.max(0, Math.min(total - snap, d.orig.start + dt));
      const pitch = Math.max(LOW, Math.min(HIGH, d.orig.pitch - Math.round(dy / ROW_H)));
      next = { ...d.orig, start, pitch, length: Math.min(d.orig.length, total - start) };
      if (pitch !== d.lastPitch) {
        d.lastPitch = pitch;
        const c = s.project.value.channels.find((x) => x.id === channelId);
        if (c) s.previewChannel(c, pitch);
      }
    }
    s.updateNotes(channelId, (list) => list.map((x, i) => (i === index ? next : x)), false);
  };
  const onUp = () => {
    const d = drag.current;
    drag.current = null;
    if (!d) return;
    ignoreClicksUntil = performance.now() + 350;
    if (!d.moved) {
      // A tap deletes the note, like the app.
      s.updateNotes(channelId, (list) => list.filter((_, i) => i !== index));
    } else {
      const now = notesFor(s.currentPattern.value, channelId)[index];
      if (now) lastLength = now.length;
    }
  };

  return (
    <div class="note" style={{ left: n.start * pxPerTick, top: y + 1, width: w, height: ROW_H - 2 }}
      onPointerDown={onDown} onPointerMove={onMove} onPointerUp={onUp} onPointerCancel={() => (drag.current = null)}
      onClick={(e) => e.stopPropagation()}>
      {w > 22 ? label : ''}
    </div>
  );
}

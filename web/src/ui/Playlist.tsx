import { useEffect, useRef, useState } from 'preact/hooks';
import { playing, position } from '../audio/engine';
import {
  type Clip, type Pattern, TICKS_PER_BAR, TICKS_PER_BEAT,
  audioClipTicks, clipLength, clipStart, findPattern, patternTicks, rowCount, songBars,
} from '../model/project';
import * as s from '../store';
import { Icon } from './common';
import { PatternChips } from './Rack';

const LANE_H = 52;
const RULER_H = 26;
let savedBarW = 64;
let ignoreClicksUntil = 0;

export function Playlist() {
  const p = s.project.value;
  const [barW, setBarWState] = useState(savedBarW);
  const [sel, setSel] = useState<number | null>(null);
  const scroller = useRef<HTMLDivElement>(null);
  const setBarW = (w: number) => {
    savedBarW = Math.min(240, Math.max(16, w));
    setBarWState(savedBarW);
  };
  const pxPerTick = barW / TICKS_PER_BAR;
  const bars = Math.max(songBars(p) + 8, 32);
  const rows = rowCount(p);
  const selected = sel != null ? p.clips[sel] : undefined;

  useEffect(() => {
    const el = scroller.current;
    if (!el) return;
    const onWheel = (e: WheelEvent) => {
      if (!e.ctrlKey && !e.metaKey) return;
      e.preventDefault();
      setBarW(savedBarW * (e.deltaY < 0 ? 1.15 : 1 / 1.15));
    };
    el.addEventListener('wheel', onWheel, { passive: false });
    return () => el.removeEventListener('wheel', onWheel);
  }, []);

  const place = (e: MouseEvent) => {
    if (performance.now() < ignoreClicksUntil) return;
    const r = (e.currentTarget as HTMLElement).getBoundingClientRect();
    const lane = Math.floor((e.clientY - r.top) / LANE_H);
    const bar = Math.floor((e.clientX - r.left) / barW);
    if (lane < 0 || lane >= rows) return;
    if (sel != null) {
      setSel(null);
      return;
    }
    if (!s.placeClip(lane, bar)) s.showToast('There’s already something there');
  };

  const seekFromRuler = (e: PointerEvent) => {
    const r = (e.currentTarget as HTMLElement).getBoundingClientRect();
    const bar = Math.max(0, Math.floor((e.clientX - r.left) / barW));
    if (s.mode.value !== 'song') s.setMode('song');
    s.seekTo(bar * TICKS_PER_BAR);
  };

  const showPlayhead = s.mode.value === 'song' && (playing.value || position.value > 0);

  return (
    <div class="screen">
      <PatternChips />
      <div class="toolbar">
        {selected ? (
          <>
            <button class="btn" onClick={() => { s.selectPattern(selected.patternId); s.tab.value = 'rack'; }}>Edit pattern</button>
            <button class="btn" onClick={() => s.replaceClipAt(sel!, { ...selected, muted: !selected.muted })}>{selected.muted ? 'Unmute' : 'Mute'}</button>
            <button class="btn danger" onClick={() => { s.deleteClipAt(sel!); setSel(null); }}>Delete</button>
          </>
        ) : (
          <span class="note-text">Tap an empty spot to place “{s.currentPattern.value.name}”.</span>
        )}
        <span class="spacer" />
        <button class="icon-btn" aria-label="Zoom out" onClick={() => setBarW(barW / 1.3)}><Icon name="zoomOut" /></button>
        <button class="icon-btn" aria-label="Zoom in" onClick={() => setBarW(barW * 1.3)}><Icon name="zoomIn" /></button>
      </div>
      <div class="pl-wrap">
        <div class="pl-scroll" ref={scroller}>
          <div class="pl-inner" style={{ width: bars * barW, height: RULER_H + rows * LANE_H + 60 }}>
            <div class="pl-ruler" style={{ width: bars * barW }} onPointerDown={(e) => {
              (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
              seekFromRuler(e);
            }} onPointerMove={(e) => e.buttons && seekFromRuler(e)}>
              {Array.from({ length: bars }, (_, i) => (
                <span key={i} class="num" style={{ left: i * barW, borderLeft: '1px solid var(--line)', height: 26, top: 0, paddingTop: 5 }}>
                  {barW >= 24 || i % 4 === 0 ? i + 1 : ''}
                </span>
              ))}
            </div>
            <div style={{ position: 'absolute', top: RULER_H, left: 0, width: bars * barW, height: rows * LANE_H }} onClick={place}>
              {Array.from({ length: rows }, (_, i) => (
                <div key={i} class={'pl-lane' + (i % 2 ? ' odd' : '')} style={{ top: i * LANE_H, height: LANE_H, pointerEvents: 'none' }} />
              ))}
              {Array.from({ length: bars + 1 }, (_, i) => (
                <div key={i} class="roll-vline" style={{ left: i * barW, opacity: i % 4 === 0 ? 1 : 0.5, pointerEvents: 'none' }} />
              ))}
              {p.audioClips.map((a, i) => {
                const t = audioClipTicks(p, a);
                return (
                  <div key={'a' + i} class="clip audio" style={{ left: t.start * pxPerTick, top: t.lane * LANE_H + 3, width: Math.max(8, (t.end - t.start) * pxPerTick - 2), height: LANE_H - 6 }}
                    title="Recordings play on the Android app for now" onClick={(e) => e.stopPropagation()}>
                    ♪ {t.name}
                  </div>
                );
              })}
              {p.clips.map((c, i) => (
                <ClipBox key={i} clip={c} index={i} pxPerTick={pxPerTick} selected={sel === i} onSelect={() => setSel(sel === i ? null : i)} />
              ))}
              {showPlayhead && <div class="playhead" style={{ left: position.value * pxPerTick }} />}
            </div>
            <button class="btn small" style={{ position: 'sticky', left: 8, top: 0, marginTop: RULER_H + rows * LANE_H + 8 }}
              onClick={() => s.addPlaylistRow()}>+ Row</button>
          </div>
        </div>
      </div>
    </div>
  );
}

function MiniNotes({ pattern, from, to }: { pattern: Pattern; from: number; to: number }) {
  const all = Object.values(pattern.notes).flat().filter((n) => n.start >= from && n.start < to);
  if (!all.length) return null;
  let lo = 127;
  let hi = 0;
  for (const n of all) {
    lo = Math.min(lo, n.pitch);
    hi = Math.max(hi, n.pitch);
  }
  const span = Math.max(1, hi - lo);
  const len = to - from;
  return (
    <div class="mini">
      {all.slice(0, 200).map((n, i) => (
        <i key={i} style={{ left: `${((n.start - from) / len) * 100}%`, width: `${Math.max(1.5, (n.length / len) * 100)}%`, top: `${(1 - (n.pitch - lo) / span) * 80}%` }} />
      ))}
    </div>
  );
}

function ClipBox({ clip, index, pxPerTick, selected, onSelect }: {
  clip: Clip; index: number; pxPerTick: number; selected: boolean; onSelect: () => void;
}) {
  const p = s.project.value;
  const pattern = findPattern(p, clip.patternId);
  const len = clipLength(p, clip);
  const drag = useRef<{ x: number; y: number; resize: boolean; moved: boolean; orig: Clip } | null>(null);

  const onDown = (e: PointerEvent) => {
    e.stopPropagation();
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    const r = (e.currentTarget as HTMLElement).getBoundingClientRect();
    drag.current = { x: e.clientX, y: e.clientY, resize: r.right - e.clientX < 14, moved: false, orig: clip };
  };
  const onMove = (e: PointerEvent) => {
    const d = drag.current;
    if (!d) return;
    const dx = e.clientX - d.x;
    const dy = e.clientY - d.y;
    if (!d.moved) {
      if (Math.abs(dx) < 6 && Math.abs(dy) < 6) return;
      d.moved = true;
      s.checkpoint();
    }
    const q = s.project.value;
    let next: Clip;
    if (d.resize) {
      const total = pattern ? patternTicks(pattern) : TICKS_PER_BAR;
      const cur = clipLength(q, d.orig);
      const want = Math.round((cur + dx / pxPerTick) / TICKS_PER_BEAT) * TICKS_PER_BEAT;
      const length = Math.max(TICKS_PER_BEAT, Math.min(total - d.orig.trim, want));
      next = { ...d.orig, length: length >= total - d.orig.trim ? 0 : length };
    } else {
      const bar = Math.max(0, d.orig.bar + Math.round(dx / pxPerTick / TICKS_PER_BAR));
      const lane = Math.max(0, Math.min(rowCount(q) - 1, d.orig.lane + Math.round(dy / LANE_H)));
      next = { ...d.orig, bar, lane };
    }
    if (s.clipFits(q, next, index)) s.replaceClipAt(index, next, false);
  };
  const onUp = () => {
    const d = drag.current;
    drag.current = null;
    ignoreClicksUntil = performance.now() + 350;
    if (d && !d.moved) onSelect();
  };

  return (
    <div class={'clip' + (selected ? ' sel' : '') + (clip.muted ? ' muted' : '')}
      style={{ left: clipStart(clip) * pxPerTick + 1, top: clip.lane * LANE_H + 3, width: Math.max(8, len * pxPerTick - 2), height: LANE_H - 6 }}
      onPointerDown={onDown} onPointerMove={onMove} onPointerUp={onUp} onPointerCancel={() => (drag.current = null)}
      onClick={(e) => e.stopPropagation()}>
      {pattern?.name ?? '?'}
      {pattern && <MiniNotes pattern={pattern} from={clip.trim} to={clip.trim + len} />}
      <span class="edge" />
    </div>
  );
}

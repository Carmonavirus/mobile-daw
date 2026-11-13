import { useEffect, useRef, useState } from 'preact/hooks';
import { audioUnlocked, engineError, engineReady, playing, position } from '../audio/engine';
import { NOTE_NAMES, TICKS_PER_BAR, TICKS_PER_BEAT, keyLabel } from '../model/project';
import * as s from '../store';
import { listProjects, type StoredProject } from '../storage';
import { Icon, Sheet, pickFile } from './common';
import { Mixer } from './Mixer';
import { PianoRoll } from './PianoRoll';
import { Playlist } from './Playlist';
import { Rack } from './Rack';

const TABS: { id: s.Tab; label: string; icon: string }[] = [
  { id: 'playlist', label: 'Playlist', icon: 'playlist' },
  { id: 'rack', label: 'Rack', icon: 'rack' },
  { id: 'piano', label: 'Piano roll', icon: 'piano' },
  { id: 'mixer', label: 'Mixer', icon: 'mixer' },
];

export function App() {
  const [sheet, setSheet] = useState<null | 'projects' | 'song'>(null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement;
      if (t.tagName === 'INPUT' || t.tagName === 'SELECT' || t.tagName === 'TEXTAREA') return;
      if (e.code === 'Space') {
        e.preventDefault();
        s.togglePlay();
      } else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z') {
        e.preventDefault();
        if (e.shiftKey) s.redo();
        else s.undo();
      } else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'y') {
        e.preventDefault();
        s.redo();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  if (!s.loaded.value) return <div class="empty">Loading…</div>;
  const p = s.project.value;

  return (
    <div class="app">
      <header class="header">
        <button class="title-btn" onClick={() => setSheet('projects')}>
          <span class="name">{p.name}</span>
          <span class="sub">{p.bpm} BPM · {keyLabel(p)}</span>
        </button>
        <button class="icon-btn" disabled={!s.canUndo.value} onClick={s.undo} aria-label="Undo"><Icon name="undo" /></button>
        <button class="icon-btn" disabled={!s.canRedo.value} onClick={s.redo} aria-label="Redo"><Icon name="redo" /></button>
        <button class="icon-btn" onClick={() => setSheet('song')} aria-label="Tempo and key"><Icon name="more" /></button>
        <button class="icon-btn" onClick={() => setSheet('projects')} aria-label="Songs"><Icon name="menu" /></button>
      </header>
      {engineError.value && <div class="banner">Sound couldn’t start in this browser: {engineError.value}</div>}
      {!engineError.value && engineReady.value && !audioUnlocked.value && <div class="banner">Tap anywhere to turn the sound on.</div>}
      <main class="screen">
        {s.tab.value === 'playlist' && <Playlist />}
        {s.tab.value === 'rack' && <Rack />}
        {s.tab.value === 'piano' && <PianoRoll />}
        {s.tab.value === 'mixer' && <Mixer />}
      </main>
      <Transport />
      <nav class="tabs">
        {TABS.map((t) => (
          <button key={t.id} class={s.tab.value === t.id ? 'on' : ''} onClick={() => (s.tab.value = t.id)}>
            <Icon name={t.icon} />
            {t.label}
          </button>
        ))}
      </nav>
      {sheet === 'projects' && <ProjectsSheet onClose={() => setSheet(null)} />}
      {sheet === 'song' && <SongSheet onClose={() => setSheet(null)} />}
      {s.toast.value && <div class="toast">{s.toast.value}</div>}
    </div>
  );
}

function fmtPos(tick: number) {
  const bar = Math.floor(tick / TICKS_PER_BAR) + 1;
  const beat = Math.floor((tick % TICKS_PER_BAR) / TICKS_PER_BEAT) + 1;
  return `${bar}.${beat}`;
}

function Transport() {
  const loop = s.loopTicks.value;
  const pos = Math.min(position.value, loop);
  const bar = useRef<HTMLDivElement>(null);
  const seek = (e: PointerEvent) => {
    const r = bar.current!.getBoundingClientRect();
    s.seekTo(((e.clientX - r.left) / r.width) * loop);
  };
  return (
    <div class="transport">
      <button class="play" onClick={s.togglePlay} aria-label={playing.value ? 'Stop' : 'Play'}>
        <Icon name={playing.value ? 'stop' : 'play'} />
      </button>
      <div class="seg">
        <button class={s.mode.value === 'pattern' ? 'on' : ''} onClick={() => s.setMode('pattern')}>Pattern</button>
        <button class={s.mode.value === 'song' ? 'on' : ''} onClick={() => s.setMode('song')}>Song</button>
      </div>
      <div class="progress" ref={bar}
        onPointerDown={(e) => { (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId); seek(e); }}
        onPointerMove={(e) => e.buttons && seek(e)}>
        <div class="track" />
        <div class="fill" style={{ width: `${(pos / Math.max(1, loop)) * 100}%` }} />
        <span class="time">{fmtPos(pos)}</span>
      </div>
      <button class={'icon-btn' + (s.metronomeOn.value ? ' on' : '')} onClick={s.toggleMetronome} aria-label="Metronome">
        <Icon name="metronome" />
      </button>
    </div>
  );
}

function SongSheet({ onClose }: { onClose: () => void }) {
  const p = s.project.value;
  const [bpm, setBpm] = useState(String(p.bpm));
  return (
    <Sheet title="Tempo and key" onClose={onClose}>
      <div class="field">
        <label>Tempo</label>
        <button class="btn" onClick={() => { s.setBpm(p.bpm - 1); setBpm(String(p.bpm - 1)); }}>−</button>
        <input class="text-input" style="width:80px;text-align:center" inputMode="numeric" value={bpm}
          onInput={(e) => setBpm((e.target as HTMLInputElement).value)}
          onBlur={() => { const v = parseInt(bpm, 10); if (v) s.setBpm(v); setBpm(String(s.project.value.bpm)); }}
          onKeyDown={(e) => e.key === 'Enter' && (e.target as HTMLInputElement).blur()} />
        <button class="btn" onClick={() => { s.setBpm(p.bpm + 1); setBpm(String(p.bpm + 1)); }}>+</button>
        <span class="note-text">BPM</span>
      </div>
      <h3>Key</h3>
      <div class="grid" style="grid-template-columns:repeat(6,1fr)">
        {NOTE_NAMES.map((n, i) => (
          <button key={n} class={'btn' + (p.key === i ? ' on' : '')} onClick={() => s.setKey(i, p.minor)}>{n}</button>
        ))}
      </div>
      <div class="seg" style="margin-top:10px">
        <button class={p.minor ? '' : 'on'} onClick={() => s.setKey(p.key, false)}>Major</button>
        <button class={p.minor ? 'on' : ''} onClick={() => s.setKey(p.key, true)}>Minor</button>
      </div>
    </Sheet>
  );
}

function ProjectsSheet({ onClose }: { onClose: () => void }) {
  const p = s.project.value;
  const [list, setList] = useState<StoredProject[]>([]);
  const [name, setName] = useState(p.name);
  const refresh = () => listProjects().then(setList).catch(() => setList([]));
  useEffect(() => {
    refresh();
  }, [p.name]);
  return (
    <Sheet title="Songs" onClose={onClose}>
      <div class="field">
        <label>This song</label>
        <input class="text-input" value={name} maxLength={40} onInput={(e) => setName((e.target as HTMLInputElement).value)}
          onBlur={() => s.renameProject(name).then(() => setName(s.project.value.name))}
          onKeyDown={(e) => e.key === 'Enter' && (e.target as HTMLInputElement).blur()} />
      </div>
      <div class="row-gap">
        <button class="btn primary" onClick={() => { s.newProject(); onClose(); }}>New song</button>
        <button class="btn" onClick={async () => {
          const f = await pickFile('.json,application/json');
          if (f) { await s.importProjectFile(f); onClose(); }
        }}>Open a project file…</button>
        <button class="btn" onClick={s.exportProjectFile}>Save as file</button>
      </div>
      <p class="note-text">Songs save automatically in this browser. “Save as file” downloads the song so you can keep it or open it on another device.</p>
      <h3>Your songs</h3>
      <div class="list">
        {list.map((x) => (
          <div key={x.name} class={'item' + (x.name === p.name ? ' on' : '')}>
            <button class="grow" style="background:none;border:0;text-align:left;padding:0"
              onClick={() => { s.openByName(x.name); onClose(); }}>
              {x.name}
              <small>{new Date(x.modified).toLocaleString()}</small>
            </button>
            <button class="btn small danger" onClick={async () => {
              if (confirmDelete(x.name)) { await s.deleteProjectByName(x.name); refresh(); }
            }}>Delete</button>
          </div>
        ))}
      </div>
      <p class="note-text" style="margin-top:16px">Web version {__BUILD__} · updated {new Date(__BUILD_TIME__).toLocaleString()}</p>
      <p class="note-text">Space plays and stops. Ctrl+Z undoes. Tap a step to add a hit; in the piano roll tap to draw a note, tap a note to delete it, drag to move, drag its right edge to resize.</p>
    </Sheet>
  );
}

function confirmDelete(name: string) {
  return window.confirm(`Delete “${name}”? This can’t be undone.`);
}

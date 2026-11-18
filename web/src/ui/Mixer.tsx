import { levels, masterLevel } from '../audio/engine';
import { type Channel, type MixerTrack } from '../model/project';
import * as s from '../store';
import { panLabel, pct } from './common';

function Meter({ level }: { level: number }) {
  const h = Math.min(100, Math.sqrt(Math.max(0, level)) * 100);
  return <div class="meter"><i style={{ height: `${h}%` }} /></div>;
}

function Fader({ value, onInput }: { value: number; onInput: (v: number) => void }) {
  return (
    <input class="vfader" type="range" min={0} max={1} step={0.01} value={value}
      onPointerDown={() => s.checkpoint()} onKeyDown={() => s.checkpoint()}
      onInput={(e) => onInput(parseFloat((e.target as HTMLInputElement).value))} />
  );
}

function ChannelStrip({ c, level }: { c: Channel; level: number }) {
  const set = (change: Partial<Channel>, undoable = false) => s.updateChannel(c.id, change, undoable);
  return (
    <div class="strip">
      <div class="label" title={c.name}>{c.name}</div>
      <input class="pan" type="range" min={-1} max={1} step={0.01} value={c.pan} title={panLabel(c.pan)}
        onPointerDown={() => s.checkpoint()} onInput={(e) => set({ pan: parseFloat((e.target as HTMLInputElement).value) })} />
      <div class="fader-area">
        <Fader value={c.volume} onInput={(v) => set({ volume: v })} />
        <Meter level={level} />
      </div>
      <div class="note-text" style="font-size:11px">{pct(c.volume)}</div>
      <div class="row">
        <button class={'btn' + (c.mute ? ' on' : '')} onClick={() => set({ mute: !c.mute }, true)}>M</button>
        <button class={'btn' + (c.solo ? ' on' : '')} onClick={() => set({ solo: !c.solo }, true)}>S</button>
      </div>
    </div>
  );
}

function TrackStrip({ t }: { t: MixerTrack }) {
  const set = (change: Partial<MixerTrack>, undoable = false) =>
    s.update((p) => ({ ...p, mixerTracks: p.mixerTracks.map((x) => (x.id === t.id ? { ...x, ...change } : x)) }), undoable);
  return (
    <div class="strip">
      <div class="label" title={t.name}>{t.name}</div>
      <input class="pan" type="range" min={-1} max={1} step={0.01} value={t.pan} title={panLabel(t.pan)}
        onPointerDown={() => s.checkpoint()} onInput={(e) => set({ pan: parseFloat((e.target as HTMLInputElement).value) })} />
      <div class="fader-area">
        <Fader value={Math.min(1, t.volume)} onInput={(v) => set({ volume: v })} />
      </div>
      <div class="note-text" style="font-size:11px">{pct(t.volume)}</div>
      <div class="row">
        <button class={'btn' + (t.mute ? ' on' : '')} onClick={() => set({ mute: !t.mute }, true)}>M</button>
      </div>
    </div>
  );
}

export function Mixer() {
  const p = s.project.value;
  const lv = levels.value;
  return (
    <div class="screen">
      <div class="toolbar"><span class="note-text">Channels, then mixer tracks. Effects (EQ, Pitch Correct, Soft Clipper) come to the web next.</span></div>
      <div class="mixer">
        <div class="strip master">
          <div class="label"><b>Master</b></div>
          <div style="height:22px" />
          <div class="fader-area">
            <Fader value={p.masterVolume} onInput={(v) => s.setMasterVolume(v, false)} />
            <Meter level={masterLevel.value} />
          </div>
          <div class="note-text" style="font-size:11px">{pct(p.masterVolume)}</div>
        </div>
        {p.channels.map((c, i) => <ChannelStrip key={c.id} c={c} level={lv[i] ?? 0} />)}
        {p.mixerTracks.map((t) => <TrackStrip key={'t' + t.id} t={t} />)}
      </div>
    </div>
  );
}

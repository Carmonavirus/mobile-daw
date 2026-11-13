import { useState } from 'preact/hooks';
import { soundKey, UNSUPPORTED_ON_WEB, isMissingSound } from '../audio/engine';
import { type Channel, type InstrumentName, INSTRUMENTS } from '../model/project';
import * as s from '../store';
import { Sheet, Slider, panLabel, pct, pickFile } from './common';

const DRUMS: InstrumentName[] = ['KICK', 'SNARE', 'HAT', 'CLAP'];
const SYNTHS: InstrumentName[] = ['BASS', 'LEAD', 'PLUCK', 'PAD'];
const CATEGORIES = ['808', 'Bass', 'Pluck', 'Pad', 'Lead', 'Bells', 'Keys'];

/** Adds a channel, or (with `swapId`) changes the sound of an existing one. */
export function AddSoundSheet({ kind, swapId, onClose }: { kind: 'drums' | 'sounds'; swapId?: number; onClose: () => void }) {
  const [cat, setCat] = useState('808');
  const choose = (instrument: InstrumentName, extra: Partial<Channel> = {}, name?: string) => {
    let id: number | null;
    if (swapId != null) {
      s.swapSound(swapId, instrument, extra, name);
      id = swapId;
    } else {
      id = s.addChannel(instrument, extra, name);
    }
    const c = s.project.value.channels.find((x) => x.id === id);
    if (c) s.auditionChannel(c);
  };
  const importFile = async () => {
    const f = await pickFile('audio/*,.wav,.mp3,.ogg,.flac,.m4a,.aac');
    if (!f) return;
    const r = await s.importSound(f);
    if (r) {
      choose('SAMPLE', { sample: r.path }, swapId != null ? r.name : r.name);
      onClose();
    }
  };
  const title = swapId != null ? 'Change sound' : kind === 'drums' ? 'Add drums' : 'Add sounds';
  return (
    <Sheet title={title} onClose={onClose}>
      {(kind === 'drums' || swapId != null) && (
        <>
          <h3>Built-in drums</h3>
          <div class="grid">
            {DRUMS.map((d) => (
              <button key={d} class="btn" onClick={() => choose(d, {}, swapId != null ? INSTRUMENTS[d].label : undefined)}>{INSTRUMENTS[d].label}</button>
            ))}
          </div>
        </>
      )}
      {(kind === 'sounds' || swapId != null) && (
        <>
          <h3>Simple synths</h3>
          <div class="grid">
            {SYNTHS.map((d) => (
              <button key={d} class="btn" onClick={() => choose(d, {}, swapId != null ? INSTRUMENTS[d].label : undefined)}>{INSTRUMENTS[d].label}</button>
            ))}
          </div>
          <h3>Wavetable synth</h3>
          <div class="chips" style="padding:0 0 8px">
            {CATEGORIES.map((c) => (
              <button key={c} class={'chip' + (c === cat ? ' on' : '')} onClick={() => setCat(c)}>{c}</button>
            ))}
          </div>
          <div class="grid">
            {s.factoryPresets.filter((p) => p.category === cat).map((p) => (
              <button key={p.name} class="btn" onClick={() => choose('WAVETABLE', { synth: s.synthPatchFrom(p) }, p.name)}>{p.name}</button>
            ))}
          </div>
        </>
      )}
      <h3>Your sounds</h3>
      <button class="btn" style="width:100%;padding:12px" onClick={importFile}>Import a sound file…</button>
      <p class="note-text">WAV, MP3, OGG, FLAC or M4A. It stays on this device, in this browser.</p>
    </Sheet>
  );
}

export function ChannelSheet({ channel: c, onClose }: { channel: Channel; onClose: () => void }) {
  const [swap, setSwap] = useState(false);
  const [presets, setPresets] = useState(false);
  const p = s.project.value;
  const sampler = soundKey(c) !== null;
  const set = (change: Partial<Channel>) => s.updateChannel(c.id, change, false);
  const start = () => s.checkpoint();
  const index = p.channels.findIndex((x) => x.id === c.id);
  const unsupported = UNSUPPORTED_ON_WEB[c.instrument];
  return (
    <Sheet title={c.name} onClose={onClose}>
      {unsupported && <p class="banner">{unsupported}. It keeps its notes and plays on the Android app.</p>}
      {isMissingSound(c) && <p class="banner">This sound’s file isn’t on this device. Use “Change sound” to import it here.</p>}
      <input class="text-input" value={c.name} maxLength={24}
        onChange={(e) => s.updateChannel(c.id, { name: (e.target as HTMLInputElement).value.trim() || c.name })} />
      <div class="row-gap" style="margin-top:10px">
        <button class="btn" onClick={() => s.auditionChannel(c)}>▶ Listen</button>
        <button class="btn" onClick={() => setSwap(true)}>Change sound</button>
        {c.instrument === 'WAVETABLE' && <button class="btn" onClick={() => setPresets(true)}>Preset: {c.synth?.preset ?? 'Init'}</button>}
      </div>
      <h3>Mix</h3>
      <Slider label="Volume" value={c.volume} min={0} max={1} format={pct} onStart={start} onInput={(v) => set({ volume: v })} />
      <Slider label="Pan" value={c.pan} min={-1} max={1} format={panLabel} onStart={start} onInput={(v) => set({ pan: Math.abs(v) < 0.03 ? 0 : v })} />
      <div class="field">
        <label>Mixer track</label>
        <select class="btn" value={c.mixerTrack == null ? '' : String(c.mixerTrack)}
          onChange={(e) => {
            const v = (e.target as HTMLSelectElement).value;
            s.updateChannel(c.id, { mixerTrack: v === '' ? null : Number(v) });
          }}>
          <option value="">Master</option>
          {p.mixerTracks.map((t) => <option key={t.id} value={String(t.id)}>{t.name}</option>)}
        </select>
      </div>
      {sampler && (
        <>
          <h3>Sound</h3>
          <Slider label="Pitch" value={c.pitchSemis} min={-12} max={12} step={1} format={(v) => (v > 0 ? '+' : '') + v + ' st'}
            onStart={start} onInput={(v) => set({ pitchSemis: v })} />
          <Slider label="Boost" value={c.boostDb} min={0} max={18} step={0.5} format={(v) => '+' + v.toFixed(1) + ' dB'}
            onStart={start} onInput={(v) => set({ boostDb: v })} />
          <div class="row-gap">
            <button class={'btn' + (c.normalize ? ' primary' : '')} onClick={() => s.updateChannel(c.id, { normalize: !c.normalize })}>Normalize</button>
            <button class={'btn' + (c.reverse ? ' primary' : '')} onClick={() => s.updateChannel(c.id, { reverse: !c.reverse })}>Reverse</button>
            <button class={'btn' + (c.cutItself ? ' primary' : '')} onClick={() => s.updateChannel(c.id, { cutItself: !c.cutItself })}>Cut itself</button>
          </div>
          <h3>Envelope</h3>
          <Slider label="Attack" value={c.attack} min={0} max={2} format={(v) => v.toFixed(2) + ' s'} onStart={start} onInput={(v) => set({ attack: v })} />
          <Slider label="Decay" value={c.decay} min={0} max={4} format={(v) => (v === 0 ? 'off' : v.toFixed(2) + ' s')} onStart={start} onInput={(v) => set({ decay: v })} />
          <Slider label="Sustain" value={c.sustain} min={0} max={1} format={pct} onStart={start} onInput={(v) => set({ sustain: v })} />
          <Slider label="Release" value={c.release} min={0} max={2} format={(v) => v.toFixed(2) + ' s'} onStart={start} onInput={(v) => set({ release: v })} />
          <Slider label="Start" value={c.trimStart} min={0} max={1} format={pct} onStart={start} onInput={(v) => set({ trimStart: Math.min(v, c.trimEnd - 0.01) })} />
          <Slider label="End" value={c.trimEnd} min={0} max={1} format={pct} onStart={start} onInput={(v) => set({ trimEnd: Math.max(v, c.trimStart + 0.01) })} />
        </>
      )}
      <h3>Channel</h3>
      <div class="row-gap">
        <button class="btn" disabled={index <= 0} onClick={() => s.moveChannel(c.id, -1)}>Move up</button>
        <button class="btn" disabled={index >= p.channels.length - 1} onClick={() => s.moveChannel(c.id, 1)}>Move down</button>
        <button class="btn danger" onClick={() => { s.deleteChannel(c.id); onClose(); }}>Delete</button>
      </div>
      {swap && <AddSoundSheet kind={INSTRUMENTS[c.instrument].isDrum ? 'drums' : 'sounds'} swapId={c.id} onClose={() => setSwap(false)} />}
      {presets && <PresetSheet channel={c} onClose={() => setPresets(false)} />}
    </Sheet>
  );
}

function PresetSheet({ channel: c, onClose }: { channel: Channel; onClose: () => void }) {
  const [cat, setCat] = useState(c.synth?.category && CATEGORIES.includes(c.synth.category) ? c.synth.category : '808');
  return (
    <Sheet title="Synth presets" onClose={onClose}>
      <div class="chips" style="padding:0 0 8px">
        {CATEGORIES.map((x) => <button key={x} class={'chip' + (x === cat ? ' on' : '')} onClick={() => setCat(x)}>{x}</button>)}
      </div>
      <div class="grid">
        {s.factoryPresets.filter((p) => p.category === cat).map((p) => (
          <button key={p.name} class={'btn' + (c.synth?.preset === p.name ? ' on' : '')}
            onClick={() => {
              s.updateChannel(c.id, { synth: s.synthPatchFrom(p) });
              const ch = s.project.value.channels.find((x) => x.id === c.id);
              if (ch) s.auditionChannel(ch);
            }}>{p.name}</button>
        ))}
      </div>
      <p class="note-text">The full synth editor (knobs, wavetables, filter, movement) comes to the web in the next update.</p>
    </Sheet>
  );
}

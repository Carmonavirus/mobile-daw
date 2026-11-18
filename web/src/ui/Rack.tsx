import { useState } from 'preact/hooks';
import { playing, position, isMissingSound, UNSUPPORTED_ON_WEB } from '../audio/engine';
import { type Channel, MAX_BARS, STEPS_PER_BAR, TICKS_PER_BAR, TICKS_PER_STEP, notesFor } from '../model/project';
import * as s from '../store';
import { Icon, Sheet } from './common';
import { AddSoundSheet, ChannelSheet } from './Sounds';

export function PatternChips() {
  const p = s.project.value;
  const [menu, setMenu] = useState(false);
  return (
    <>
      <div class="chips">
        {p.patterns.map((pt) => (
          <button key={pt.id} class={'chip' + (pt.id === s.patternId.value ? ' on' : '')}
            onClick={() => (pt.id === s.patternId.value ? setMenu(true) : s.selectPattern(pt.id))}>
            {pt.name}
          </button>
        ))}
        <button class="chip add" onClick={() => s.addPattern()}>+ Pattern</button>
      </div>
      {menu && <PatternSheet onClose={() => setMenu(false)} />}
    </>
  );
}

function PatternSheet({ onClose }: { onClose: () => void }) {
  const pt = s.currentPattern.value;
  return (
    <Sheet title="Pattern" onClose={onClose}>
      <input class="text-input" value={pt.name} maxLength={30}
        onChange={(e) => s.updatePattern(pt.id, { name: (e.target as HTMLInputElement).value.trim() || pt.name })} />
      <h3>Length</h3>
      <div class="row-gap">
        {[1, 2, 4, 8, 16].filter((b) => b <= MAX_BARS).map((b) => (
          <button key={b} class={'btn' + (pt.bars === b ? ' primary' : '')} onClick={() => s.setPatternBars(pt.id, b)}>
            {b} bar{b > 1 ? 's' : ''}
          </button>
        ))}
      </div>
      <h3>More</h3>
      <div class="row-gap">
        <button class="btn" onClick={() => { s.duplicatePattern(pt.id); onClose(); }}>Duplicate</button>
        <button class="btn danger" onClick={() => { s.deletePattern(pt.id); onClose(); }}>Delete</button>
      </div>
    </Sheet>
  );
}

export function Rack() {
  const p = s.project.value;
  const pt = s.currentPattern.value;
  const [bar, setBar] = useState(0);
  const [edit, setEdit] = useState<number | null>(null);
  const [add, setAdd] = useState<null | 'drums' | 'sounds'>(null);
  const shownBar = Math.min(bar, pt.bars - 1);
  const pos = position.value;
  const nowStep = playing.value && s.mode.value === 'pattern' ? Math.floor(pos / TICKS_PER_STEP) : -1;
  const editing = p.channels.find((c) => c.id === edit);

  return (
    <div class="screen">
      <PatternChips />
      <div class="toolbar">
        <button class="icon-btn" disabled={shownBar === 0} onClick={() => setBar(shownBar - 1)} aria-label="Previous bar"><Icon name="left" /></button>
        <span style="font-size:14px">Bar {shownBar + 1} of {pt.bars}</span>
        <button class="icon-btn" onClick={() => {
          if (shownBar === pt.bars - 1) {
            if (pt.bars >= MAX_BARS) return;
            s.setPatternBars(pt.id, pt.bars + 1);
          }
          setBar(shownBar + 1);
        }} aria-label={shownBar === pt.bars - 1 ? 'Add a bar' : 'Next bar'}>
          <Icon name={shownBar === pt.bars - 1 ? 'plus' : 'right'} />
        </button>
        <span class="spacer" />
      </div>
      <div class="rack">
        {p.channels.map((c) => (
          <RackRow key={c.id} c={c} bar={shownBar} nowStep={nowStep} onEdit={() => setEdit(c.id)} />
        ))}
        <div class="rack-add">
          <button class="btn" onClick={() => setAdd('drums')}>+ Add drums</button>
          <button class="btn" onClick={() => setAdd('sounds')}>+ Add sounds</button>
        </div>
      </div>
      {editing && <ChannelSheet channel={editing} onClose={() => setEdit(null)} />}
      {add && <AddSoundSheet kind={add} onClose={() => setAdd(null)} />}
    </div>
  );
}

function RackRow({ c, bar, nowStep, onEdit }: { c: Channel; bar: number; nowStep: number; onEdit: () => void }) {
  const pt = s.currentPattern.value;
  const notes = notesFor(pt, c.id);
  const on = new Set(notes.map((n) => n.start));
  const unsupported = UNSUPPORTED_ON_WEB[c.instrument];
  const missing = isMissingSound(c);
  const sub = unsupported ? 'not on web yet' : missing ? 'sound missing' : c.instrument === 'WAVETABLE' ? c.synth?.preset ?? 'Init' : c.instrument === 'SAMPLE' ? 'sample' : '';
  return (
    <div class="rack-row">
      <button class={'ch-led' + (c.mute ? ' muted' : '')} aria-label={c.mute ? 'Unmute' : 'Mute'}
        onClick={() => s.updateChannel(c.id, { mute: !c.mute })} />
      <button class={'ch-name' + (c.id === s.channelId.value ? ' sel' : '') + (unsupported || missing ? ' warn' : '')}
        onClick={() => { s.channelId.value = c.id; s.auditionChannel(c); onEdit(); }}>
        <span>{c.name}</span>
        {sub && <small>{sub}</small>}
      </button>
      <div class="steps">
        {Array.from({ length: STEPS_PER_BAR }, (_, i) => {
          const step = bar * STEPS_PER_BAR + i;
          const isOn = on.has(step * TICKS_PER_STEP);
          const cls = 'step' + (Math.floor(i / 4) % 2 ? ' alt' : '') + (isOn ? ' on' : '') + (step === nowStep ? ' now' : '');
          return <button key={i} class={cls} aria-label={`Step ${i + 1}`} onClick={() => s.toggleStep(c, step)} />;
        })}
      </div>
      <button class="icon-btn ch-roll" aria-label="Piano roll"
        onClick={() => { s.channelId.value = c.id; s.tab.value = 'piano'; }}>
        <Icon name="notes" />
      </button>
    </div>
  );
}

export const barOf = (tick: number) => Math.floor(tick / TICKS_PER_BAR);

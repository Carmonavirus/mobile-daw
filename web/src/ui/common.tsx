import type { ComponentChildren } from 'preact';

const paths: Record<string, string> = {
  play: 'M8 5.5v13l11-6.5z',
  stop: 'M6.5 6.5h11v11h-11z',
  undo: 'M9 7 4 12l5 5M4.5 12H15a5 5 0 0 1 0 10h-3',
  redo: 'M15 7l5 5-5 5M19.5 12H9a5 5 0 0 0 0 10h3',
  menu: 'M4 7h16M4 12h16M4 17h16',
  metronome: 'M9 3h6l3.5 18h-13zM12 15 17 6M7.5 15h9',
  playlist: 'M3 6h8M3 12h12M3 18h6M14 5h7v3h-7zM17 11h4v3h-4zM11 17h7v3h-7z',
  rack: 'M4 5h3v3H4zM10 5h3v3h-3zM16 5h3v3h-3zM4 11h3v3H4zM10 11h3v3h-3zM16 11h3v3h-3zM4 17h3v3H4zM10 17h3v3h-3zM16 17h3v3h-3z',
  piano: 'M3 4h18v16H3zM8 4v10M13 4v10M18 4v10M8 14v6M13 14v6M18 14v6',
  mixer: 'M6 3v18M12 3v18M18 3v18M4 15h4M10 8h4M16 12h4',
  plus: 'M12 5v14M5 12h14',
  more: 'M5 12h.01M12 12h.01M19 12h.01',
  left: 'M15 5l-7 7 7 7',
  right: 'M9 5l7 7-7 7',
  close: 'M6 6l12 12M18 6 6 18',
  notes: 'M9 18V5l11-2v13M9 18a3 3 0 1 1-6 0 3 3 0 0 1 6 0zM20 16a3 3 0 1 1-6 0 3 3 0 0 1 6 0z',
  zoomIn: 'M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM20 20l-4-4M8 11h6M11 8v6',
  zoomOut: 'M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM20 20l-4-4M8 11h6',
  swap: 'M7 7h13l-4-4M17 17H4l4 4',
};

export function Icon({ name, filled }: { name: string; filled?: boolean }) {
  const d = paths[name] ?? '';
  const solid = filled ?? (name === 'play' || name === 'stop');
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" fill={solid ? 'currentColor' : 'none'} stroke={solid ? 'none' : 'currentColor'}
      stroke-width={name === 'more' ? 3.2 : 1.9} stroke-linecap="round" stroke-linejoin="round">
      <path d={d} />
    </svg>
  );
}

export function Sheet({ title, onClose, children, actions }: {
  title: string; onClose: () => void; children: ComponentChildren; actions?: ComponentChildren;
}) {
  return (
    <div class="scrim" onPointerDown={(e) => e.target === e.currentTarget && onClose()}>
      <div class="sheet" role="dialog" aria-label={title}>
        <h2>
          {title}
          <span class="spacer" />
          {actions}
          <button class="icon-btn" onClick={onClose} aria-label="Close"><Icon name="close" /></button>
        </h2>
        {children}
      </div>
    </div>
  );
}

export function Slider({ label, value, min, max, step = 0.01, format, onInput, onStart }: {
  label: string; value: number; min: number; max: number; step?: number;
  format?: (v: number) => string; onInput: (v: number) => void; onStart?: () => void;
}) {
  return (
    <div class="field">
      <label>{label}</label>
      <input type="range" min={min} max={max} step={step} value={value}
        onPointerDown={onStart} onKeyDown={onStart}
        onInput={(e) => onInput(parseFloat((e.target as HTMLInputElement).value))} />
      <span class="val">{format ? format(value) : value.toFixed(2)}</span>
    </div>
  );
}

/** Opens the system file picker. */
export function pickFile(accept: string): Promise<File | null> {
  return new Promise((resolve) => {
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = accept;
    input.onchange = () => resolve(input.files?.[0] ?? null);
    input.click();
  });
}

export const pct = (v: number) => `${Math.round(v * 100)}%`;
export const panLabel = (v: number) => (Math.abs(v) < 0.02 ? 'C' : v < 0 ? `L${Math.round(-v * 100)}` : `R${Math.round(v * 100)}`);

import React from 'react';

interface ModeGridProps {
  activeMode: string | null;
  onToggleMode: (cmd: string) => void;
}

interface ModeItem {
  name: string;
  cmd: string;
}

const MODES: ModeItem[] = [
  { name: 'Obstacle Avoid', cmd: '%A#' },
  { name: 'Object Follow', cmd: '%F#' },
  { name: 'Line Tracking', cmd: '%T#' },
  { name: 'Light Seeking', cmd: '%P#' },
];

export const ModeGrid: React.FC<ModeGridProps> = ({ activeMode, onToggleMode }) => {
  return (
    <section className="card">
      <div className="card-header">
        <span className="card-title">AUTONOMOUS MODES</span>
      </div>
      <div className="modes-grid">
        {MODES.map((m) => {
          const isActive = activeMode === m.cmd;
          return (
            <button
              key={m.cmd}
              className={`btn-mode ${isActive ? 'active' : ''}`}
              onClick={() => onToggleMode(m.cmd)}
            >
              <span>{m.name}</span>
              {isActive && <span className="mode-active-indicator" />}
            </button>
          );
        })}
      </div>
    </section>
  );
};

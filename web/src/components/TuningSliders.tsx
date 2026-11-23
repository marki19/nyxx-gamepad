import React from 'react';
import { ble } from '../services/bleService';

interface TuningSlidersProps {
  distance: number;
  followDistance: number;
  speed: number;
  activeMode: string | null;
  onDistanceChange: (val: number) => void;
  onFollowDistanceChange: (val: number) => void;
  onSpeedChange: (val: number) => void;
  onStopAll: () => void;
}

export const TuningSliders: React.FC<TuningSlidersProps> = ({
  distance,
  followDistance,
  speed,
  activeMode,
  onDistanceChange,
  onFollowDistanceChange,
  onSpeedChange,
  onStopAll,
}) => {
  const handleDistance = (e: React.ChangeEvent<HTMLInputElement>) => {
    const val = parseInt(e.target.value, 10);
    onDistanceChange(val);
    ble.sendRawCommand(`%D${val}#`);
  };

  const handleFollowDistance = (e: React.ChangeEvent<HTMLInputElement>) => {
    const val = parseInt(e.target.value, 10);
    onFollowDistanceChange(val);
    ble.sendRawCommand(`%C${val}#`);
  };

  const handleSpeed = (e: React.ChangeEvent<HTMLInputElement>) => {
    const val = parseInt(e.target.value, 10);
    onSpeedChange(val);
    ble.sendRawCommand(`%V${val}#`);
  };

  return (
    <section className="card">
      <div className="card-header">
        <span className="card-title">CONTROLS &amp; TUNING</span>
      </div>

      <button className="btn-stop-main" onClick={onStopAll}>
        STOP
      </button>

      {/* Obstacle Avoidance Distance */}
      <div className={`slider-box ${activeMode === '%A#' ? 'highlight' : ''}`}>
        <div className="slider-label-row">
          <span>Avoid Distance</span>
          <span className="slider-value">{distance} cm</span>
        </div>
        <input
          type="range"
          min="10"
          max="100"
          step="1"
          value={distance}
          onChange={handleDistance}
        />
      </div>

      {/* Object Follow Catch Range */}
      <div className={`slider-box ${activeMode === '%F#' ? 'highlight' : ''}`}>
        <div className="slider-label-row">
          <span>Follow Range</span>
          <span className="slider-value">{followDistance} cm</span>
        </div>
        <input
          type="range"
          min="10"
          max="150"
          step="1"
          value={followDistance}
          onChange={handleFollowDistance}
        />
      </div>

      {/* Cruise Speed */}
      <div className="slider-box">
        <div className="slider-label-row">
          <span>Cruise Speed</span>
          <span className="slider-value">{speed} %</span>
        </div>
        <input
          type="range"
          min="0"
          max="100"
          step="1"
          value={speed}
          onChange={handleSpeed}
        />
      </div>
    </section>
  );
};

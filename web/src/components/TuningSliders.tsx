import React from 'react';
import { ble } from '../services/bleService';

interface TuningSlidersProps {
  distance: number;
  followNear: number;
  followFar: number;
  followRange: number;
  speed: number;
  activeMode: string | null;
  onDistanceChange: (val: number) => void;
  onFollowNearChange: (val: number) => void;
  onFollowFarChange: (val: number) => void;
  onFollowRangeChange: (val: number) => void;
  onSpeedChange: (val: number) => void;
  onStopAll: () => void;
}

export const TuningSliders: React.FC<TuningSlidersProps> = ({
  distance,
  followNear,
  followFar,
  followRange,
  speed,
  activeMode,
  onDistanceChange,
  onFollowNearChange,
  onFollowFarChange,
  onFollowRangeChange,
  onSpeedChange,
  onStopAll,
}) => {
  const handleDistance = (e: React.ChangeEvent<HTMLInputElement>) => {
    const val = parseInt(e.target.value, 10);
    onDistanceChange(val);
    ble.sendRawCommand(`%D${val}#`);
  };

  const handleFollowNear = (e: React.ChangeEvent<HTMLInputElement>) => {
    const val = parseInt(e.target.value, 10);
    onFollowNearChange(val);
    ble.sendRawCommand(`%N${val}#`);
  };

  const handleFollowFar = (e: React.ChangeEvent<HTMLInputElement>) => {
    const val = parseInt(e.target.value, 10);
    onFollowFarChange(val);
    ble.sendRawCommand(`%C${val}#`);
  };

  const handleFollowRange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const val = parseInt(e.target.value, 10);
    onFollowRangeChange(val);
    ble.sendRawCommand(`%M${val}#`);
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

      {/* Cruise Speed (Top-most) */}
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

      {/* Obstacle Avoidance Distance */}
      <div className={`slider-box ${activeMode === '%A#' ? 'highlight' : ''}`}>
        <div className="slider-label-row">
          <span>Obstacle Avoid Distance</span>
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

      {/* Follow Near Distance */}
      <div className={`slider-box ${activeMode === '%F#' ? 'highlight' : ''}`}>
        <div className="slider-label-row">
          <span>Follow Near Limit (Back Away)</span>
          <span className="slider-value">{followNear} cm</span>
        </div>
        <input
          type="range"
          min="2"
          max="25"
          step="1"
          value={followNear}
          onChange={handleFollowNear}
        />
      </div>

      {/* Follow Catch Distance */}
      <div className={`slider-box ${activeMode === '%F#' ? 'highlight' : ''}`}>
        <div className="slider-label-row">
          <span>Follow Catch Limit (Chase)</span>
          <span className="slider-value">{followFar} cm</span>
        </div>
        <input
          type="range"
          min="4"
          max="40"
          step="1"
          value={followFar}
          onChange={handleFollowFar}
        />
      </div>

      {/* Follow Max Range */}
      <div className={`slider-box ${activeMode === '%F#' ? 'highlight' : ''}`}>
        <div className="slider-label-row">
          <span>Follow Max Range (Ignore)</span>
          <span className="slider-value">{followRange} cm</span>
        </div>
        <input
          type="range"
          min="20"
          max="150"
          step="5"
          value={followRange}
          onChange={handleFollowRange}
        />
      </div>
    </section>
  );
};

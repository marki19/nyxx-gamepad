import React, { useRef } from 'react';
import { ble } from '../services/bleService';

interface SpinControlsProps {
  onStopAll: () => void;
}

export const SpinControls: React.FC<SpinControlsProps> = ({ onStopAll }) => {
  const activeSpinTimer = useRef<any>(null);

  const startSpin = (cmd: string) => {
    stopSpin();
    ble.sendRawCommand(cmd);
    // Keep alive packet every 100ms
    activeSpinTimer.current = setInterval(() => {
      ble.sendRawCommand(cmd);
    }, 100);
  };

  const stopSpin = () => {
    if (activeSpinTimer.current) {
      clearInterval(activeSpinTimer.current);
      activeSpinTimer.current = null;
    }
    ble.sendRawCommand('%S');
  };

  return (
    <div className="spin-row">
      <button
        className="btn-secondary"
        onTouchStart={(e) => { e.preventDefault(); startSpin('%L'); }}
        onTouchEnd={(e) => { e.preventDefault(); stopSpin(); }}
        onTouchCancel={(e) => { e.preventDefault(); stopSpin(); }}
        onMouseDown={() => startSpin('%L')}
        onMouseUp={stopSpin}
        onMouseLeave={stopSpin}
      >
        Spin Left
      </button>

      <button
        className="btn-stop-spin"
        onClick={onStopAll}
      >
        Stop
      </button>

      <button
        className="btn-secondary"
        onTouchStart={(e) => { e.preventDefault(); startSpin('%R'); }}
        onTouchEnd={(e) => { e.preventDefault(); stopSpin(); }}
        onTouchCancel={(e) => { e.preventDefault(); stopSpin(); }}
        onMouseDown={() => startSpin('%R')}
        onMouseUp={stopSpin}
        onMouseLeave={stopSpin}
      >
        Spin Right
      </button>
    </div>
  );
};

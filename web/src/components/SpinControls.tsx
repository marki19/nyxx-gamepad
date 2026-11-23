import React, { useRef } from 'react';
import { ble } from '../services/bleService';

interface SpinControlsProps {
  onStopAll: () => void;
}

export const SpinControls: React.FC<SpinControlsProps> = ({ onStopAll }) => {
  const spinIntervalRef = useRef<any>(null);

  const startSpin = (cmd: string) => {
    stopSpin();
    ble.queueDriveCommand(cmd);
    // Continuous spin while holding: resend packet every 80ms
    spinIntervalRef.current = setInterval(() => {
      ble.queueDriveCommand(cmd);
    }, 80);
  };

  const stopSpin = () => {
    if (spinIntervalRef.current) {
      clearInterval(spinIntervalRef.current);
      spinIntervalRef.current = null;
    }
    ble.queueStop();
  };

  return (
    <div className="spin-row">
      <button
        className="btn-secondary"
        onPointerDown={(e) => {
          e.preventDefault();
          try { (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId); } catch (_) {}
          startSpin('%L');
        }}
        onPointerUp={(e) => {
          e.preventDefault();
          try { (e.currentTarget as HTMLElement).releasePointerCapture(e.pointerId); } catch (_) {}
          stopSpin();
        }}
        onPointerCancel={stopSpin}
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
        onPointerDown={(e) => {
          e.preventDefault();
          try { (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId); } catch (_) {}
          startSpin('%R');
        }}
        onPointerUp={(e) => {
          e.preventDefault();
          try { (e.currentTarget as HTMLElement).releasePointerCapture(e.pointerId); } catch (_) {}
          stopSpin();
        }}
        onPointerCancel={stopSpin}
      >
        Spin Right
      </button>
    </div>
  );
};

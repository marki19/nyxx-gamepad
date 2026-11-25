import React, { useState, useEffect, useCallback } from 'react';
import { ble, ConnectionStatus } from './services/bleService';
import { BluetoothModal } from './components/BluetoothModal';
import { Joystick } from './components/Joystick';
import { SpinControls } from './components/SpinControls';
import { TuningSliders } from './components/TuningSliders';
import { ModeGrid } from './components/ModeGrid';
import './App.css';

export const App: React.FC = () => {
  const [status, setStatus] = useState<ConnectionStatus>('disconnected');
  const [deviceName, setDeviceName] = useState<string>('JDY-16');
  const [isModalOpen, setIsModalOpen] = useState<boolean>(true);

  const [activeMode, setActiveMode] = useState<string | null>(null);
  const [distance, setDistance] = useState<number>(35);
  const [followNear, setFollowNear] = useState<number>(5);
  const [followFar, setFollowFar] = useState<number>(8);
  const [followRange, setFollowRange] = useState<number>(60);
  const [speed, setSpeed] = useState<number>(70);

  useEffect(() => {
    const unsubscribe = ble.onStatusChange((newStatus, name) => {
      setStatus(newStatus);
      setDeviceName(name);

      if (newStatus === 'connected') {
        setIsModalOpen(false);
        // Send initial saved distance, follow limits, speed, and enter G_Drive
        ble.sendRawCommand(`%D${distance}#\n`);
        setTimeout(() => ble.sendRawCommand(`%N${followNear}#\n`), 40);
        setTimeout(() => ble.sendRawCommand(`%C${followFar}#\n`), 80);
        setTimeout(() => ble.sendRawCommand(`%M${followRange}#\n`), 120);
        setTimeout(() => ble.sendRawCommand(`%V${speed}#\n`), 160);
        setTimeout(() => ble.sendRawCommand(`%${speed}+#\n`), 200);
        setTimeout(() => ble.sendRawCommand('%G#\n'), 240);
      } else if (newStatus === 'disconnected') {
        setActiveMode(null);
      }
    });

    return () => unsubscribe();
  }, [distance, followNear, followFar, followRange, speed]);

  const handleToggleMode = useCallback(async (cmd: string) => {
    if (status !== 'connected') {
      setIsModalOpen(true);
      return;
    }

    // Tapping the active button disables/untoggles it
    if (activeMode === cmd) {
      setActiveMode(null);
      await ble.sendRawCommand('%Q\n');
      setTimeout(() => ble.sendRawCommand('%G#\n'), 60);
      return;
    }

    // If another mode is active, halt it first
    if (activeMode !== null) {
      await ble.sendRawCommand('%Q\n');
      await new Promise((r) => setTimeout(r, 200));
    }

    setActiveMode(cmd);
    await ble.sendRawCommand(cmd);
  }, [activeMode, status]);

  const handleStopAll = useCallback(() => {
    setActiveMode(null);
    ble.queueStop();
    ble.sendRawCommand('%Q\n');
    setTimeout(() => ble.sendRawCommand('%G#\n'), 60);
  }, []);

  const handleHeaderConnectClick = () => {
    if (status === 'connected') {
      ble.disconnect();
    } else {
      setIsModalOpen(true);
    }
  };

  return (
    <div className="app-container">
      {/* Top Bar */}
      <header>
        <div className="title-group">
          <svg viewBox="0 0 24 24" width="18" height="18" fill="#66FCF1">
            <path d="M12 2L2 19h20L12 2zm0 3.8L18.8 17H5.2L12 5.8z" />
          </svg>
          <span className="title">ROBOT CAR BLE REMOTE</span>
        </div>

        <div className="status-group">
          <span className={`status-dot ${status}`} />
          <span>
            {status === 'connected'
              ? `Connected (${deviceName})`
              : status === 'connecting'
              ? 'Connecting...'
              : status === 'scanning'
              ? 'Scanning...'
              : 'Disconnected'}
          </span>
          <button className="btn-connect" onClick={handleHeaderConnectClick}>
            {status === 'connected' ? 'Disconnect' : 'Connect'}
          </button>
        </div>
      </header>

      {/* Main 3-Column Landscape Grid */}
      <main>
        {/* Left Column: 360° Joystick & Spin Controls */}
        <section className="card">
          <Joystick activeMode={activeMode} />
          <SpinControls onStopAll={handleStopAll} />
        </section>

        {/* Center Column: Stop Button & Sliders */}
        <TuningSliders
          distance={distance}
          followNear={followNear}
          followFar={followFar}
          followRange={followRange}
          speed={speed}
          activeMode={activeMode}
          onDistanceChange={setDistance}
          onFollowNearChange={setFollowNear}
          onFollowFarChange={setFollowFar}
          onFollowRangeChange={setFollowRange}
          onSpeedChange={setSpeed}
          onStopAll={handleStopAll}
        />

        {/* Right Column: 2x2 Autonomous Modes with Toggle/Untoggle */}
        <ModeGrid
          activeMode={activeMode}
          onToggleMode={handleToggleMode}
        />
      </main>

      {/* Bluetooth Startup Prompt Modal */}
      <BluetoothModal
        isOpen={isModalOpen}
        status={status}
        deviceName={deviceName}
        onClose={() => setIsModalOpen(false)}
      />
    </div>
  );
};
export default App;

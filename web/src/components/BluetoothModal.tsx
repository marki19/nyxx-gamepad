import React, { useEffect, useState } from 'react';
import { ble, ConnectionStatus } from '../services/bleService';

interface BluetoothModalProps {
  isOpen: boolean;
  status: ConnectionStatus;
  deviceName: string;
  onClose: () => void;
}

export const BluetoothModal: React.FC<BluetoothModalProps> = ({
  isOpen,
  onClose,
}) => {
  const [isSupported, setIsSupported] = useState<boolean>(true);
  const [isAvailable, setIsAvailable] = useState<boolean>(true);
  const [showAllDevices, setShowAllDevices] = useState<boolean>(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [isConnecting, setIsConnecting] = useState<boolean>(false);

  useEffect(() => {
    if (!navigator.bluetooth) {
      setIsSupported(false);
      return;
    }

    if (navigator.bluetooth.getAvailability) {
      navigator.bluetooth.getAvailability().then(available => {
        setIsAvailable(available);
      }).catch(() => {
        setIsAvailable(true);
      });
    }

    const handleAvailabilityChanged = (e: BluetoothAvailabilityEvent) => {
      setIsAvailable(e.value);
    };

    if (navigator.bluetooth.addEventListener) {
      navigator.bluetooth.addEventListener('availabilitychanged', handleAvailabilityChanged);
      return () => {
        navigator.bluetooth?.removeEventListener('availabilitychanged', handleAvailabilityChanged as any);
      };
    }
  }, []);

  if (!isOpen) return null;

  const handleConnect = async () => {
    setErrorMessage(null);
    setIsConnecting(true);
    try {
      const success = await ble.connect(showAllDevices);
      if (success) {
        onClose();
      }
    } catch (err: any) {
      setErrorMessage(err.message || 'Connection failed');
    } finally {
      setIsConnecting(false);
    }
  };

  return (
    <div className="bt-modal-backdrop">
      <div className="bt-modal-card">
        <div className={`bt-icon-wrapper ${!isAvailable || !isSupported ? 'disabled' : 'pulsing'}`}>
          <svg className="bt-icon" viewBox="0 0 24 24">
            <path d="M12 2a1 1 0 0 1 1 1v7.59l4.3-4.3a1 1 0 0 1 1.4 1.42L14.42 12l4.28 4.29a1 1 0 0 1-1.4 1.42L13 13.41V21a1 1 0 0 1-2 0v-7.59l-4.3 4.3a1 1 0 0 1-1.4-1.42L9.58 12 5.3 7.71a1 1 0 0 1 1.4-1.42L11 10.59V3a1 1 0 0 1 1-1zm-1 9.41V4.41L7.71 7.7 11 11.41zm2 0 3.29-3.71L13 4.41v7zm0 1.18v7l3.29-3.29L13 12.59zm-2 0-3.29 3.71L11 19.59v-7z" />
          </svg>
        </div>

        <h2 className="modal-title">Connect Robot Car</h2>

        <p className="modal-subtitle">
          Turn <strong>ON Bluetooth</strong> on your phone/PC, then tap Find Device to pair with your JDY-16 car.
        </p>

        {!isSupported && (
          <div className="bt-notice error">
            Web Bluetooth is not supported on this browser. Please open in Google Chrome (Android/Windows/Mac) or Bluefy (iOS).
          </div>
        )}

        {isSupported && !isAvailable && (
          <div className="bt-notice error">
            ⚠️ <strong>Bluetooth is turned OFF</strong>.<br />
            Turn ON Bluetooth in your device settings or quick settings panel, then tap Find Device.
          </div>
        )}

        {isSupported && isAvailable && !errorMessage && (
          <div className="bt-notice info">
            ✓ <strong>Bluetooth is ON</strong>. Tap Find Device to search for your JDY-16 Car.
          </div>
        )}

        {errorMessage && (
          <div className="bt-notice error">
            {errorMessage}
          </div>
        )}

        {isSupported && (
          <button
            className="btn-modal-action"
            onClick={handleConnect}
            disabled={isConnecting}
          >
            <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor">
              <path d="M12 2a1 1 0 0 1 1 1v7.59l4.3-4.3a1 1 0 0 1 1.4 1.42L14.42 12l4.28 4.29a1 1 0 0 1-1.4 1.42L13 13.41V21a1 1 0 0 1-2 0v-7.59l-4.3 4.3a1 1 0 0 1-1.4-1.42L9.58 12 5.3 7.71a1 1 0 0 1 1.4-1.42L11 10.59V3a1 1 0 0 1 1-1zm-1 9.41V4.41L7.71 7.7 11 11.41zm2 0 3.29-3.71L13 4.41v7zm0 1.18v7l3.29-3.29L13 12.59zm-2 0-3.29 3.71L11 19.59v-7z" />
            </svg>
            <span>
              {isConnecting
                ? 'Connecting...'
                : !isAvailable
                ? 'Check Bluetooth & Find Device'
                : 'Find Device'}
            </span>
          </button>
        )}

        <div className="modal-options">
          <label className="chk-container">
            <input
              type="checkbox"
              checked={showAllDevices}
              onChange={(e) => setShowAllDevices(e.target.checked)}
            />
            Show all devices
          </label>
          <button className="btn-modal-dismiss" onClick={onClose}>
            Manual Mode
          </button>
        </div>
      </div>
    </div>
  );
};

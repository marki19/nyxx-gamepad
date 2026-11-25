// BLE Service for JDY-16 / BT05 Arduino Robot Car Remote

const CANDIDATE_SERVICES: BluetoothServiceUUID[] = [
  '0000ffe0-0000-1000-8000-00805f9b34fb',
  0xffe0,
  '0000fff0-0000-1000-8000-00805f9b34fb',
  0xfff0,
  '0000dfb0-0000-1000-8000-00805f9b34fb',
  0xdfb0,
  '6e400001-b5a3-f393-e0a9-e50e24dcca9e',
];

const STANDARD_WRITE_CHAR = '0000ffe1-0000-1000-8000-00805f9b34fb';

export type ConnectionStatus = 'disconnected' | 'scanning' | 'connecting' | 'connected';

class BleService {
  private device: BluetoothDevice | null = null;
  private txCharacteristic: BluetoothRemoteGATTCharacteristic | null = null;

  public status: ConnectionStatus = 'disconnected';
  public deviceName: string = 'Robot Car';

  private statusListeners: Array<(status: ConnectionStatus, name: string) => void> = [];

  // Throttled priority queue: latest joystick overwrites old; stop always has priority
  private latestJoystick: string | null = null;
  private latestDriveCmd: string | null = null;
  private stopPending: boolean = false;
  private writeBusy: boolean = false;

  public onStatusChange(callback: (status: ConnectionStatus, name: string) => void) {
    this.statusListeners.push(callback);
    callback(this.status, this.deviceName);
    return () => {
      this.statusListeners = this.statusListeners.filter(l => l !== callback);
    };
  }

  private setStatus(status: ConnectionStatus, name: string = this.deviceName) {
    this.status = status;
    this.deviceName = name;
    for (const listener of this.statusListeners) {
      listener(status, name);
    }
  }

  public async connect(showAllDevices: boolean = true): Promise<boolean> {
    if (this.status === 'connected') {
      this.disconnect();
      return false;
    }

    if (!navigator.bluetooth) {
      if (!window.isSecureContext) {
        throw new Error('Web Bluetooth requires HTTPS or http://localhost. It cannot run over insecure HTTP.');
      }
      throw new Error('Web Bluetooth is not supported on this browser. Use Google Chrome, Microsoft Edge, Opera, or Bluefy (iOS).');
    }

    try {
      this.setStatus('scanning');

      // acceptAllDevices allows finding renamed modules or custom names
      const options: RequestDeviceOptions = showAllDevices
        ? { acceptAllDevices: true, optionalServices: CANDIDATE_SERVICES }
        : {
            filters: [
              { namePrefix: 'JDY' },
              { namePrefix: 'BT' },
              { namePrefix: 'HM' },
              { namePrefix: 'Robot' },
              { namePrefix: 'Car' }
            ],
            optionalServices: CANDIDATE_SERVICES
          };

      const device = await navigator.bluetooth.requestDevice(options);
      this.device = device;
      this.deviceName = device.name || 'Robot Car';

      device.addEventListener('gattserverdisconnected', () => {
        this.txCharacteristic = null;
        this.device = null;
        this.setStatus('disconnected');
      });

      this.setStatus('connecting', this.deviceName);
      if (!device.gatt) throw new Error('Device does not have a GATT server');

      const server = await device.gatt.connect();

      // Resolve primary service with fallbacks
      let service: BluetoothRemoteGATTService | null = null;
      for (const uuid of CANDIDATE_SERVICES) {
        try {
          service = await server.getPrimaryService(uuid);
          if (service) break;
        } catch (_) {}
      }

      if (!service && server.getPrimaryServices) {
        try {
          const all = await server.getPrimaryServices();
          service = all.find(s =>
            s.uuid.toLowerCase().includes('ffe0') ||
            s.uuid.toLowerCase().includes('fff0') ||
            s.uuid.toLowerCase().includes('dfb0')
          ) || all[0] || null;
        } catch (_) {}
      }

      if (!service) {
        service = await server.getPrimaryService(STANDARD_WRITE_CHAR.replace('ffe1', 'ffe0'));
      }

      // Resolve writable characteristic
      let tx: BluetoothRemoteGATTCharacteristic | null = null;
      if (service.getCharacteristics) {
        try {
          const chars = await service.getCharacteristics();
          tx = chars.find(c =>
            c.uuid.toLowerCase().includes('ffe1') &&
            (c.properties?.write || c.properties?.writeWithoutResponse)
          ) || null;

          if (!tx) {
            tx = chars.find(c => c.properties?.write || c.properties?.writeWithoutResponse) || null;
          }
        } catch (_) {}
      }

      if (!tx) {
        tx = await service.getCharacteristic(STANDARD_WRITE_CHAR);
      }

      this.txCharacteristic = tx;
      this.setStatus('connected', this.deviceName);
      return true;
    } catch (err: any) {
      this.setStatus('disconnected');
      if (err.name === 'NotFoundError') {
        // User cancelled picker dialog
        return false;
      }
      throw err;
    }
  }

  public disconnect() {
    if (this.device && this.device.gatt?.connected) {
      this.device.gatt.disconnect();
    }
    this.txCharacteristic = null;
    this.device = null;
    this.setStatus('disconnected');
  }

  public queueJoystick(angle: number) {
    this.latestJoystick = `@${angle}#\n`;
    this.pump();
  }

  public queueDriveCommand(cmd: string) {
    this.latestDriveCmd = cmd.endsWith('\n') ? cmd : `${cmd}\n`;
    this.pump();
  }

  public queueStop() {
    this.latestJoystick = null;
    this.latestDriveCmd = null;
    this.stopPending = true;
    this.pump();
  }

  public async sendRawCommand(cmd: string): Promise<void> {
    if (!this.txCharacteristic || this.status !== 'connected') return;
    const payload = cmd.endsWith('\n') ? cmd : `${cmd}\n`;
    await this.writeData(payload);
  }

  private async writeData(data: string): Promise<boolean> {
    if (!this.txCharacteristic || this.status !== 'connected') return false;
    try {
      const bytes = new TextEncoder().encode(data);
      const props = this.txCharacteristic.properties;
      if (props?.writeWithoutResponse && this.txCharacteristic.writeValueWithoutResponse) {
        await this.txCharacteristic.writeValueWithoutResponse(bytes);
      } else {
        await this.txCharacteristic.writeValue(bytes);
      }
      return true;
    } catch (err) {
      console.warn('GATT write error:', data, err);
      return false;
    }
  }

  private async pump() {
    if (this.writeBusy || !this.txCharacteristic || this.status !== 'connected') return;

    let payload: string | null = null;
    if (this.stopPending) {
      this.stopPending = false;
      this.latestDriveCmd = null;
      this.latestJoystick = null;
      payload = '%S\n';
    } else if (this.latestDriveCmd !== null) {
      payload = this.latestDriveCmd;
      this.latestDriveCmd = null;
    } else if (this.latestJoystick !== null) {
      payload = this.latestJoystick;
      this.latestJoystick = null;
    } else {
      return;
    }

    this.writeBusy = true;
    try {
      await this.writeData(payload);
    } finally {
      setTimeout(() => {
        this.writeBusy = false;
        this.pump();
      }, 50);
    }
  }
}

export const ble = new BleService();

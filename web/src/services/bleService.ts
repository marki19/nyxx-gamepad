// BLE Service for JDY-16 Arduino Robot Car Remote

const SERVICE_UUID = '0000ffe0-0000-1000-8000-00805f9b34fb';
const WRITE_CHAR_UUID = '0000ffe1-0000-1000-8000-00805f9b34fb';

export type ConnectionStatus = 'disconnected' | 'scanning' | 'connecting' | 'connected';

class BleService {
  private device: BluetoothDevice | null = null;
  private txCharacteristic: BluetoothRemoteGATTCharacteristic | null = null;

  public status: ConnectionStatus = 'disconnected';
  public deviceName: string = 'JDY-16';

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
      throw new Error('Web Bluetooth is not supported on this browser. Use Google Chrome or Bluefy.');
    }

    try {
      this.setStatus('scanning');

      // Use acceptAllDevices by default so renamed devices (not named JDY-16) are visible,
      // or filter by the standard BLE UART Service UUID (FFE0).
      const options: RequestDeviceOptions = showAllDevices
        ? { acceptAllDevices: true, optionalServices: [SERVICE_UUID] }
        : {
            filters: [
              { services: [SERVICE_UUID] },
              { namePrefix: 'JDY' },
              { namePrefix: 'BT' }
            ],
            optionalServices: [SERVICE_UUID]
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
      if (!device.gatt) throw new Error('Device does not have GATT server');

      const server = await device.gatt.connect();

      const service = await server.getPrimaryService(SERVICE_UUID);
      this.txCharacteristic = await service.getCharacteristic(WRITE_CHAR_UUID);

      this.setStatus('connected', this.deviceName);
      return true;
    } catch (err: any) {
      this.setStatus('disconnected');
      if (err.name === 'NotFoundError') {
        // User cancelled picker
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
    this.latestDriveCmd = cmd;
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
    try {
      const bytes = new TextEncoder().encode(cmd);
      if (this.txCharacteristic.writeValueWithoutResponse) {
        await this.txCharacteristic.writeValueWithoutResponse(bytes);
      } else {
        await this.txCharacteristic.writeValue(bytes);
      }
    } catch (err) {
      console.warn('Raw command error:', cmd, err);
    }
  }

  private async pump() {
    if (this.writeBusy || !this.txCharacteristic || this.status !== 'connected') return;

    let payload: string | null = null;
    if (this.stopPending) {
      this.stopPending = false;
      this.latestDriveCmd = null;
      this.latestJoystick = null;
      payload = '%S';
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
      const bytes = new TextEncoder().encode(payload);
      if (this.txCharacteristic.writeValueWithoutResponse) {
        await this.txCharacteristic.writeValueWithoutResponse(bytes);
      } else {
        await this.txCharacteristic.writeValue(bytes);
      }
    } catch (err) {
      console.warn('GATT pump error:', err);
    } finally {
      // 50ms throttle between transmissions
      setTimeout(() => {
        this.writeBusy = false;
        this.pump();
      }, 50);
    }
  }
}

export const ble = new BleService();

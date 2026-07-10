#nullable enable
using System;
using System.Net;
using System.Net.Sockets;
using System.Collections.Concurrent;
using Nefarius.ViGEm.Client;
using Nefarius.ViGEm.Client.Targets;
using Nefarius.ViGEm.Client.Targets.Xbox360;
using System.Threading;
using System.Threading.Tasks;
using System.Text;
using System.Linq;
using System.Windows;

namespace NativeGamepadServer
{
    public class ClientConnectedEventArgs : EventArgs
    {
        public int PlayerIndex { get; set; }
        public string IpAddress { get; set; } = string.Empty;
    }

    public enum JoyConType
    {
        Right = 0,
        Left = 1,
        Pro = 2
    }

    public class GamepadStateArgs : EventArgs
    {
        public int PlayerIndex { get; set; }
        public short LX { get; set; }
        public short LY { get; set; }
        public short RX { get; set; }
        public short RY { get; set; }
        public byte LT { get; set; }
        public byte RT { get; set; }
        public ushort Buttons { get; set; }
        public float AccelX { get; set; }
        public float AccelY { get; set; }
        public float AccelZ { get; set; }
        public float GyroX { get; set; }
        public float GyroY { get; set; }
        public float GyroZ { get; set; }

        // Joy-Con specific properties
        public JoyConType JoyConType { get; set; } = JoyConType.Right;
        public byte BatteryLevel { get; set; } = 4; // 0-4 scale, 4=Full
    }

    public class LogMessageEventArgs : EventArgs
    {
        public string Message { get; set; } = string.Empty;
    }

    public class ClientState
    {
        public IXbox360Controller? Controller { get; set; }
        public DateTime LastPacket { get; set; }
        public int PlayerIndex { get; set; }
        public float[] SmoothedGyro { get; } = new float[3];
        public float[] SmoothedAccel { get; } = new float[3];
    }

    public class GamepadServer
    {
        private ViGEmClient? client;
        private CemuhookServer? cemuhook;
        private ConcurrentDictionary<IPEndPoint, ClientState> clients = new ConcurrentDictionary<IPEndPoint, ClientState>();
        private DateTime lastActiveTime = DateTime.UtcNow;
        private UdpClient? udp;
        private UdpClient? discoveryUdp;
        private CancellationTokenSource? cts;
        private readonly byte[] floatScratch = new byte[4]; // reused across packets to avoid per-packet allocation
        private readonly byte[] pongScratch = Encoding.ASCII.GetBytes("PONG:1");

        public event EventHandler<ClientConnectedEventArgs>? PlayerConnected;
        public event EventHandler<ClientConnectedEventArgs>? PlayerDisconnected;
        public event EventHandler<GamepadStateArgs>? StateUpdated;
        public event EventHandler<LogMessageEventArgs>? LogMessage;

        public bool IsRunning { get; private set; }
        public bool DsuRunning { get; private set; }

        private void Log(string msg) => LogMessage?.Invoke(this, new LogMessageEventArgs { Message = msg });

        // Encodes "RUMBLE:<large>:<small>" into a reused buffer (no per-event allocation).
        // Motors are 0-255, so the command is at most 7 + 3 + 1 + 3 = 14 bytes.
        private static int EncodeRumble(byte[] buf, byte large, byte small)
        {
            buf[0] = (byte)'R'; buf[1] = (byte)'U'; buf[2] = (byte)'M';
            buf[3] = (byte)'B'; buf[4] = (byte)'L'; buf[5] = (byte)'E'; buf[6] = (byte)':';
            int p = 7;
            p = WriteDecimal(buf, p, large);
            buf[p++] = (byte)':';
            p = WriteDecimal(buf, p, small);
            return p;
        }

        private static int WriteDecimal(byte[] buf, int p, byte value)
        {
            int v = value;
            if (v >= 100) { buf[p++] = (byte)('0' + v / 100); v %= 100; }
            if (v >= 10 || p > 7) { buf[p++] = (byte)('0' + v / 10); }
            buf[p++] = (byte)('0' + v % 10);
            return p;
        }

        public int Start(int startPort, int dsuPort = 26760)
        {
            if (IsRunning) return startPort;

            try {
                client = new ViGEmClient();
            } catch (Exception ex) {
                Log("Failed to initialize ViGEmBus. Is it installed? " + ex.Message);
                return -1;
            }

            int portToTry = startPort;
            while (portToTry < 65535)
            {
                try {
                    udp = new UdpClient(new IPEndPoint(IPAddress.Any, portToTry));
                    const int SIO_UDP_CONNRESET = -1744830452;
                    udp.Client.IOControl((IOControlCode)SIO_UDP_CONNRESET, new byte[] { 0, 0, 0, 0 }, null);
                    udp.Client.ReceiveTimeout = 2000;
                    portToTry = ((IPEndPoint)udp.Client.LocalEndPoint).Port;
                    break;
                } catch (SocketException) {
                    portToTry++;
                } catch (Exception ex) {
                    Log("Failed to bind UDP Port: " + ex.Message);
                    return -1;
                }
            }

            if (udp == null)
            {
                return -1;
            }

            IsRunning = true;
            cts = new CancellationTokenSource();
            lastActiveTime = DateTime.UtcNow;

            cemuhook = new CemuhookServer(Log, dsuPort);
            DsuRunning = cemuhook.Start();

            Log($"Server started on port {portToTry}. Waiting for connections...");

            Task.Run(() => ServerLoop(cts.Token));
            Task.Run(() => DiscoveryLoop(cts.Token, portToTry));
            Task.Run(() => CleanupLoop(cts.Token));
            
            return portToTry;
        }

        public void Stop()
        {
            if (!IsRunning) return;
            IsRunning = false;
            cts?.Cancel();

            if (udp != null)
            {
                byte[] disconnectData = Encoding.UTF8.GetBytes("DISCONNECT");
                foreach (var kvp in clients)
                {
                    try
                    {
                        udp.Send(disconnectData, disconnectData.Length, kvp.Key);
                    }
                    catch { }
                }
            }

            udp?.Close();
            discoveryUdp?.Close();
            cemuhook?.Stop();

            Task.Run(() => {
                foreach (var kvp in clients)
                {
                    try { 
                        kvp.Value.Controller?.Disconnect(); 
                    } catch { }
                }
                clients.Clear();

                try { client?.Dispose(); } catch { }
                client = null;

                Log("Server stopped.");
            });
        }

        private async Task DiscoveryLoop(CancellationToken token, int activePort)
        {
            try
            {
                discoveryUdp = new UdpClient(new IPEndPoint(IPAddress.Any, 55555));
                const int SIO_UDP_CONNRESET = -1744830452;
                discoveryUdp.Client.IOControl((IOControlCode)SIO_UDP_CONNRESET, new byte[] { 0, 0, 0, 0 }, null);
                while (!token.IsCancellationRequested)
                {
                    var result = await discoveryUdp.ReceiveAsync(token);
                    string msg = Encoding.UTF8.GetString(result.Buffer);
                    if (msg == "Nyxx_DISCOVER")
                    {
                        byte[] response = Encoding.UTF8.GetBytes($"Nyxx_SERVER:{activePort}");
                        await discoveryUdp.SendAsync(response, response.Length, result.RemoteEndPoint);
                        Log($"Discovery ping received from {result.RemoteEndPoint.Address}. Replied with port {activePort}.");
                    }
                }
            }
            catch (OperationCanceledException) { }
            catch (Exception ex)
            {
                Log($"Discovery listener error: {ex.Message}");
            }
        }

        private async Task CleanupLoop(CancellationToken token)
        {
            while (!token.IsCancellationRequested)
            {
                await Task.Delay(500, token);
                var now = DateTime.UtcNow;

                if (clients.IsEmpty) {
                    if ((now - lastActiveTime).TotalMinutes >= 10) {
                        Log("Server idle for 10 minutes. Gracefully exiting to free ports.");
                        System.Windows.Application.Current.Dispatcher.Invoke(() => System.Windows.Application.Current.Shutdown());
                        return;
                    }
                } else {
                    lastActiveTime = now;
                }

                foreach (var kvp in clients.ToList())
                {
                    var idleTime = (now - kvp.Value.LastPacket).TotalMilliseconds;
                    if (idleTime > 10000)
                    {
                        Log($"Player {kvp.Value.PlayerIndex} ({kvp.Key.Address}) timed out. Disconnecting.");
                        try { 
                            kvp.Value.Controller?.Disconnect(); 
                        } catch { }
                        clients.TryRemove(kvp.Key, out _);
                        cemuhook?.RemovePlayer(kvp.Value.PlayerIndex);
                        PlayerDisconnected?.Invoke(this, new ClientConnectedEventArgs { PlayerIndex = kvp.Value.PlayerIndex, IpAddress = kvp.Key.Address.ToString() });
                    }
                    else if (idleTime > 500)
                    {
                        var c = kvp.Value.Controller;
                        if (c != null)
                        {
                            try {
                                c.SetAxisValue(Xbox360Axis.LeftThumbX, 0);
                                c.SetAxisValue(Xbox360Axis.LeftThumbY, 0);
                                c.SetAxisValue(Xbox360Axis.RightThumbX, 0);
                                c.SetAxisValue(Xbox360Axis.RightThumbY, 0);
                                c.SetSliderValue(Xbox360Slider.LeftTrigger, 0);
                                c.SetSliderValue(Xbox360Slider.RightTrigger, 0);
                                c.SubmitReport();
                            } catch { }
                        }
                    }
                }
            }
        }

        private void ServerLoop(CancellationToken token)
        {
            while (!token.IsCancellationRequested)
            {
                try
                {
                    var remoteEP = new IPEndPoint(IPAddress.Any, 0);
                    var data = udp.Receive(ref remoteEP);

                    if (data.Length == 4 && data[0] == 'P' && data[1] == 'I' && data[2] == 'N' && data[3] == 'G')
                    {
                        // Fix 2: Reject 9th player — send FULL before slot assignment
                        // Allow a returning player (same IP, possibly a new ephemeral port) to reclaim its slot
                        // instead of being wrongly rejected as FULL.
                        bool sameAddressExists = clients.Keys.Any(k => k.Address.Equals(remoteEP.Address));
                        bool isFull = clients.Count >= 8 && !clients.ContainsKey(remoteEP) && !sameAddressExists;
                        if (isFull)
                        {
                            var fullMsg = Encoding.ASCII.GetBytes("FULL");
                            udp.Send(fullMsg, fullMsg.Length, remoteEP);
                            continue;
                        }

                        // Fix 1: Correct player number in PONG
                        int assignedPlayer = 1;
                        if (clients.TryGetValue(remoteEP, out var existing)) {
                            assignedPlayer = existing.PlayerIndex;
                        } else {
                            for (int i = 1; i <= 8; i++) {
                                if (!clients.Values.Any(c => c.PlayerIndex == i)) {
                                    assignedPlayer = i;
                                    break;
                                }
                            }
                        }

                        pongScratch[5] = (byte)('0' + assignedPlayer);
                        udp.Send(pongScratch, 6, remoteEP);
                        continue;
                    }

                    if (data.Length == 10 && Encoding.UTF8.GetString(data) == "DISCONNECT")
                    {
                        if (clients.TryRemove(remoteEP, out var removedState)) {
                            Log($"Player {removedState.PlayerIndex} explicitly disconnected.");
                            try { removedState.Controller?.Disconnect(); } catch { }
                            cemuhook?.RemovePlayer(removedState.PlayerIndex);
                            PlayerDisconnected?.Invoke(this, new ClientConnectedEventArgs { PlayerIndex = removedState.PlayerIndex, IpAddress = remoteEP.Address.ToString() });
                        }
                        continue;
                    }

                    if (data.Length < 15) continue;

                    // Fix 4: Validate version byte — discard unknown protocol versions
                    if (data[0] != 1) continue;

                    if (!clients.TryGetValue(remoteEP, out var clientState))
                    {
                        var staleEntry = clients.FirstOrDefault(kvp => kvp.Key.Address.Equals(remoteEP.Address));
                        int reuseIndex = -1;

                        if (staleEntry.Key != null)
                        {
                            reuseIndex = staleEntry.Value.PlayerIndex;
                            Log($"Player {reuseIndex} reconnected from {remoteEP.Address} (port {staleEntry.Key.Port} -> {remoteEP.Port}). Reclaiming slot.");

                            try { staleEntry.Value.Controller?.Disconnect(); } catch { }
                            clients.TryRemove(staleEntry.Key, out _);
                        }

                        if (reuseIndex == -1 && clients.Count >= 8) continue;

                        int newPlayerIndex = reuseIndex;
                        if (newPlayerIndex == -1)
                        {
                            newPlayerIndex = 1;
                            for (int i = 1; i <= 8; i++) {
                                if (!clients.Values.Any(c => c.PlayerIndex == i)) {
                                    newPlayerIndex = i;
                                    break;
                                }
                            }
                        }

                        // Fix 3: Null-guard ViGEm client against Stop() race
                        var vigem = client;
                        // Windows/ViGEmBus only supports 4 XInput pads. Players 5-8 are
                        // DSU-only (emulator motion via Cemuhook) and intentionally get a null controller.
                        IXbox360Controller? newController = null;
                        if (vigem != null && newPlayerIndex <= 4)
                        {
                            try {
                                newController = vigem.CreateXbox360Controller();
                                newController.Connect();

                                var rumbleEp = new IPEndPoint(remoteEP.Address, remoteEP.Port);
                                byte[] rumbleScratch = new byte[24];
                                newController.FeedbackReceived += (sender, args) => {
                                    try {
                                        int len = EncodeRumble(rumbleScratch, args.LargeMotor, args.SmallMotor);
                                        udp?.Send(rumbleScratch, len, rumbleEp);
                                    } catch { }
                                };
                            } catch (Exception ex) {
                                Log($"ViGEm connection failed for Player {newPlayerIndex}: {ex.Message}. Falling back to DSU-only.");
                                newController = null;
                            }
                        }

                        clientState = new ClientState { 
                            Controller = newController, 
                            LastPacket = DateTime.UtcNow,
                            PlayerIndex = newPlayerIndex 
                        };
                        clients[remoteEP] = clientState;
                        Log($"Player {newPlayerIndex} connected from {remoteEP.Address}!");
                        PlayerConnected?.Invoke(this, new ClientConnectedEventArgs { PlayerIndex = newPlayerIndex, IpAddress = remoteEP.Address.ToString() });
                    }

                    clientState.LastPacket = DateTime.UtcNow;

                    int idx = 0;
                    byte version = data[idx++];
                    ushort seq = (ushort)((data[idx++] << 8) | data[idx++]);
                    ushort buttons = (ushort)((data[idx++] << 8) | data[idx++]);
                    short lx = (short)((data[idx++] << 8) | data[idx++]);
                    short ly = (short)((data[idx++] << 8) | data[idx++]);
                    short rx = (short)((data[idx++] << 8) | data[idx++]);
                    short ry = (short)((data[idx++] << 8) | data[idx++]);
                    byte lt = data[idx++];
                    byte rt = data[idx++];

                    float accelX = 0, accelY = 0, accelZ = 0, gyroX = 0, gyroY = 0, gyroZ = 0;
                    if (data.Length >= 39) {
                        byte[] floatBuf = floatScratch;
                        float ReadFloat() {
                            if (BitConverter.IsLittleEndian) {
                                floatBuf[3] = data[idx++];
                                floatBuf[2] = data[idx++];
                                floatBuf[1] = data[idx++];
                                floatBuf[0] = data[idx++];
                            } else {
                                floatBuf[0] = data[idx++];
                                floatBuf[1] = data[idx++];
                                floatBuf[2] = data[idx++];
                                floatBuf[3] = data[idx++];
                            }
                            return BitConverter.ToSingle(floatBuf, 0);
                        }
                        float FilterSensor(float raw, int sIdx, float[] stateArr, float deadzone, float alpha) {
                            if (Math.Abs(raw) < deadzone) raw = 0;
                            stateArr[sIdx] = stateArr[sIdx] + alpha * (raw - stateArr[sIdx]);
                            if (Math.Abs(stateArr[sIdx]) < 0.0001f) stateArr[sIdx] = 0; // Snap to absolute zero
                            return stateArr[sIdx];
                        }
                        
                        accelX = FilterSensor(ReadFloat(), 0, clientState.SmoothedAccel, 0.01f, 0.95f);
                        accelY = FilterSensor(ReadFloat(), 1, clientState.SmoothedAccel, 0.01f, 0.95f);
                        accelZ = FilterSensor(ReadFloat(), 2, clientState.SmoothedAccel, 0.01f, 0.95f);
                        gyroX = FilterSensor(ReadFloat(), 0, clientState.SmoothedGyro, 0.005f, 0.95f);
                        gyroY = FilterSensor(ReadFloat(), 1, clientState.SmoothedGyro, 0.005f, 0.95f);
                        gyroZ = FilterSensor(ReadFloat(), 2, clientState.SmoothedGyro, 0.005f, 0.95f);
                    }

                    // Joy-Con specific: type and battery (placed at the end to prevent offset bugs)
                    JoyConType joyconType = JoyConType.Right;
                    byte battery = 4;
                    if (data.Length >= 41)
                    {
                        joyconType = (JoyConType)data[idx++];
                        battery = data[idx++];
                    }

                    var c = clientState.Controller;
                    if (c != null) {
                        c.SetAxisValue(Xbox360Axis.LeftThumbX, lx);
                        c.SetAxisValue(Xbox360Axis.LeftThumbY, ly);
                        c.SetAxisValue(Xbox360Axis.RightThumbX, rx);
                        c.SetAxisValue(Xbox360Axis.RightThumbY, ry);
                        c.SetSliderValue(Xbox360Slider.LeftTrigger, lt);
                        c.SetSliderValue(Xbox360Slider.RightTrigger, rt);

                        c.SetButtonState(Xbox360Button.Up, (buttons & 0x0001) != 0);
                        c.SetButtonState(Xbox360Button.Down, (buttons & 0x0002) != 0);
                        c.SetButtonState(Xbox360Button.Left, (buttons & 0x0004) != 0);
                        c.SetButtonState(Xbox360Button.Right, (buttons & 0x0008) != 0);
                        c.SetButtonState(Xbox360Button.Start, (buttons & 0x0010) != 0);
                        c.SetButtonState(Xbox360Button.Back, (buttons & 0x0020) != 0);
                        c.SetButtonState(Xbox360Button.LeftThumb, (buttons & 0x0040) != 0);
                        c.SetButtonState(Xbox360Button.RightThumb, (buttons & 0x0080) != 0);
                        c.SetButtonState(Xbox360Button.LeftShoulder, (buttons & 0x0100) != 0);
                        c.SetButtonState(Xbox360Button.RightShoulder, (buttons & 0x0200) != 0);
                        c.SetButtonState(Xbox360Button.Guide, (buttons & 0x0400) != 0);
                        c.SetButtonState(Xbox360Button.A, (buttons & 0x1000) != 0);
                        c.SetButtonState(Xbox360Button.B, (buttons & 0x2000) != 0);
                        c.SetButtonState(Xbox360Button.X, (buttons & 0x4000) != 0);
                        c.SetButtonState(Xbox360Button.Y, (buttons & 0x8000) != 0);

                        c.SubmitReport();
                    }

                    var stateArgs = new GamepadStateArgs {
                        PlayerIndex = clientState.PlayerIndex,
                        LX = lx, LY = ly, RX = rx, RY = ry,
                        LT = lt, RT = rt,
                        Buttons = buttons,
                        AccelX = accelX, AccelY = accelY, AccelZ = accelZ,
                        GyroX = gyroX, GyroY = gyroY, GyroZ = gyroZ,
                        JoyConType = joyconType,
                        BatteryLevel = battery
                    };
                    StateUpdated?.Invoke(this, stateArgs);
                    cemuhook?.BroadcastData(stateArgs);
                }
                catch (SocketException) { }
                catch (Exception) { }
            }
        }
    }
}





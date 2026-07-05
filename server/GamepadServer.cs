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
    }

    public class GamepadServer
    {
        private ViGEmClient? client;
        private ConcurrentDictionary<IPEndPoint, ClientState> clients = new ConcurrentDictionary<IPEndPoint, ClientState>();
        private DateTime lastActiveTime = DateTime.UtcNow;
        private UdpClient? udp;
        private CancellationTokenSource? cts;

        public event EventHandler<ClientConnectedEventArgs>? PlayerConnected;
        public event EventHandler<ClientConnectedEventArgs>? PlayerDisconnected;
        public event EventHandler<GamepadStateArgs>? StateUpdated;
        public event EventHandler<LogMessageEventArgs>? LogMessage;

        public bool IsRunning { get; private set; }

        private void Log(string msg) => LogMessage?.Invoke(this, new LogMessageEventArgs { Message = msg });

        public int Start(int startPort)
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
                    udp = new UdpClient(portToTry);
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

            IsRunning = true;
            cts = new CancellationTokenSource();
            lastActiveTime = DateTime.UtcNow;

            Log($"Server started on port {portToTry}. Waiting for connections...");

            Task.Run(() => ServerLoop(cts.Token));
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
                    try { udp?.Send(Encoding.UTF8.GetBytes("PING"), 4, kvp.Key); } catch {}
                    if (idleTime > 2000)
                    {
                        Log($"Player {kvp.Value.PlayerIndex} ({kvp.Key.Address}) timed out. Disconnecting.");
                        try { 
                            kvp.Value.Controller?.Disconnect(); 
                        } catch { }
                        clients.TryRemove(kvp.Key, out _);
                        PlayerDisconnected?.Invoke(this, new ClientConnectedEventArgs { PlayerIndex = kvp.Value.PlayerIndex, IpAddress = kvp.Key.Address.ToString() });
                    }
                    else if (idleTime > 500)
                    {
                        var c = kvp.Value.Controller;
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
                        int assignedPlayer = 1;
                        if (clients.TryGetValue(remoteEP, out var existing)) {
                            assignedPlayer = existing.PlayerIndex;
                        } else {
                            for (int i = 1; i <= 4; i++) {
                                if (!clients.Values.Any(c => c.PlayerIndex == i)) {
                                    assignedPlayer = i;
                                    break;
                                }
                            }
                        }

                        byte[] pong = Encoding.UTF8.GetBytes($"PONG:{assignedPlayer}");
                        udp.Send(pong, pong.Length, remoteEP);
                        continue;
                    }

                    if (data.Length == 10 && Encoding.UTF8.GetString(data) == "DISCONNECT")
                    {
                        if (clients.TryRemove(remoteEP, out var removedState)) {
                            Log($"Player {removedState.PlayerIndex} explicitly disconnected.");
                            try { removedState.Controller?.Disconnect(); } catch { }
                            PlayerDisconnected?.Invoke(this, new ClientConnectedEventArgs { PlayerIndex = removedState.PlayerIndex, IpAddress = remoteEP.Address.ToString() });
                        }
                        continue;
                    }

                    if (data.Length < 15) continue;

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

                        if (reuseIndex == -1 && clients.Count >= 4) continue;

                        int newPlayerIndex = reuseIndex;
                        if (newPlayerIndex == -1)
                        {
                            newPlayerIndex = 1;
                            for (int i = 1; i <= 4; i++) {
                                if (!clients.Values.Any(c => c.PlayerIndex == i)) {
                                    newPlayerIndex = i;
                                    break;
                                }
                            }
                        }

                        var newController = client.CreateXbox360Controller();
                        newController.Connect();

                        newController.FeedbackReceived += (sender, args) => {
                            try {
                                byte[] rumbleData = Encoding.UTF8.GetBytes($"RUMBLE:{args.LargeMotor}:{args.SmallMotor}");
                                var ep = new IPEndPoint(remoteEP.Address, 5002);
                                using var fbUdp = new UdpClient();
                                fbUdp.Send(rumbleData, rumbleData.Length, ep);
                            } catch { }
                        };

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

                    var c = clientState.Controller;
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

                    StateUpdated?.Invoke(this, new GamepadStateArgs {
                        PlayerIndex = clientState.PlayerIndex,
                        LX = lx, LY = ly, RX = rx, RY = ry,
                        LT = lt, RT = rt,
                        Buttons = buttons
                    });
                }
                catch (SocketException) { }
                catch (Exception) { }
            }
        }
    }
}





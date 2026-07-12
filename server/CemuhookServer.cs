#nullable enable
using System;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Tasks;
using System.Collections.Concurrent;

namespace NativeGamepadServer
{
    public class CemuhookServer
    {
        private UdpClient? udp1;
        private UdpClient? udp2;
        private CancellationTokenSource? cts;
        private ConcurrentDictionary<IPEndPoint, DateTime> subscribedClients1 = new ConcurrentDictionary<IPEndPoint, DateTime>();
        private ConcurrentDictionary<IPEndPoint, DateTime> subscribedClients2 = new ConcurrentDictionary<IPEndPoint, DateTime>();
        private int basePort;

        public uint PacketCounter = 0;
        private Action<string>? logAction;

        public CemuhookServer(Action<string>? logger = null, int port = 26760)
        {
            logAction = logger;
            basePort = port;
        }

        public bool Start()
        {
            try {
                udp1 = new UdpClient(new IPEndPoint(IPAddress.Any, basePort));
                udp2 = new UdpClient(new IPEndPoint(IPAddress.Any, basePort + 1));
                const int SIO_UDP_CONNRESET = -1744830452;
                udp1.Client.IOControl((IOControlCode)SIO_UDP_CONNRESET, new byte[] { 0, 0, 0, 0 }, null);
                udp2.Client.IOControl((IOControlCode)SIO_UDP_CONNRESET, new byte[] { 0, 0, 0, 0 }, null);
                logAction?.Invoke($"[Cemuhook] Servers successfully listening on ports {basePort} and {basePort + 1}");
                cts = new CancellationTokenSource();
                Task.Run(() => ServerLoop(udp1, subscribedClients1, cts.Token));
                Task.Run(() => ServerLoop(udp2, subscribedClients2, cts.Token));
                Task.Run(() => BroadcastLoop(cts.Token));
                return true;
            } catch (Exception ex) {
                logAction?.Invoke($"[Cemuhook] Failed to bind to ports {basePort}/{basePort + 1}: {ex.Message}");
                return false;
            }
        }

        public void Stop()
        {
            cts?.Cancel();
            ClearAll();
            udp1?.Close();
            udp2?.Close();
        }

        public void ClearAll()
        {
            lastStates.Clear();
            subscribedClients1.Clear();
            subscribedClients2.Clear();
        }

        private void ServerLoop(UdpClient udp, ConcurrentDictionary<IPEndPoint, DateTime> subscribedClients, CancellationToken token)
        {
            while (!token.IsCancellationRequested)
            {
                try {
                    var ep = new IPEndPoint(IPAddress.Any, 0);
                    byte[] data = udp.Receive(ref ep);
                    
                    if (data.Length >= 20 && data[0] == 'D' && data[1] == 'S' && data[2] == 'U' && data[3] == 'C')
                    {
                        uint messageType = BitConverter.ToUInt32(data, 16);
                        
                        if (messageType == 0x100000) {
                            // Protocol Info Request
                            logAction?.Invoke("[Cemuhook] Sending Protocol Info (0x100000)");
                            SendProtocolInfo(udp, ep);
                        } else if (messageType == 0x100001) {
                            // Controller Info Request
                            logAction?.Invoke("[Cemuhook] Sending Controller Info (0x100001)");
                            SendControllerInfo(udp, ep, data);
                        } else if (messageType == 0x100002) {
                            // Data Request (Subscribe)
                            byte slot = data.Length >= 21 ? data[20] : (byte)0;
                            logAction?.Invoke($"[Cemuhook] Subscribe Request (0x100002) for Slot {slot}");
                            subscribedClients[ep] = DateTime.UtcNow;
                        } else {
                            logAction?.Invoke($"[Cemuhook] Unhandled Message Type: 0x{messageType:X}");
                        }
                    }
                    else
                    {
                        logAction?.Invoke($"[Cemuhook] Received unknown UDP packet: length={data.Length}");
                    }
                } catch (Exception ex) {
                    logAction?.Invoke($"[Cemuhook] Exception in ServerLoop: {ex.Message}");
                }
            }
        }

        private ConcurrentDictionary<int, GamepadStateArgs> lastStates = new ConcurrentDictionary<int, GamepadStateArgs>();

        public void RemovePlayer(int playerIndex)
        {
            lastStates.TryRemove(playerIndex, out _);
        }

        public void CleanStaleClients()
        {
            var now = DateTime.UtcNow;
            foreach (var kvp in subscribedClients1) {
                if ((now - kvp.Value).TotalHours > 24) subscribedClients1.TryRemove(kvp.Key, out _);
            }
            foreach (var kvp in subscribedClients2) {
                if ((now - kvp.Value).TotalHours > 24) subscribedClients2.TryRemove(kvp.Key, out _);
            }
        }

        private async Task BroadcastLoop(CancellationToken token)
        {
            var periodMs = 10; // 100Hz
            var sw = System.Diagnostics.Stopwatch.StartNew();
            long next = sw.ElapsedMilliseconds;

            while (!token.IsCancellationRequested)
            {
                foreach (var kvp in lastStates)
                {
                    BroadcastDataInternal(kvp.Value);
                }
                
                var delay = (int)(next - sw.ElapsedMilliseconds);
                if (delay > 0) {
                    try { await Task.Delay(delay, token); } catch { break; }
                }
                next += periodMs;
            }
        }

        public void BroadcastData(GamepadStateArgs state)
{
    lastStates[state.PlayerIndex] = state; // Store latest state for all players
}
        private void BroadcastDataInternal(GamepadStateArgs state)
        {
            CleanStaleClients();
            if (subscribedClients1.IsEmpty && subscribedClients2.IsEmpty) return;

            PacketCounter++;
            
            UdpClient? targetUdp;
            ConcurrentDictionary<IPEndPoint, DateTime> targetSubscribers;
            byte slotIndex;
            
            if (state.PlayerIndex <= 4) {
                targetUdp = udp1;
                targetSubscribers = subscribedClients1;
                slotIndex = (byte)(state.PlayerIndex - 1);
            } else {
                targetUdp = udp2;
                targetSubscribers = subscribedClients2;
                slotIndex = (byte)(state.PlayerIndex - 5);
            }
            
            if (targetSubscribers.IsEmpty) return;

            byte[] packet = BuildDataPacket(state, slotIndex);
            foreach (var kvp in targetSubscribers) {
                try {
                    targetUdp?.Send(packet, packet.Length, kvp.Key);
                } catch { }
            }
        }

        private byte[] BuildDataPacket(GamepadStateArgs state, byte slotIndex)
        {
            byte[] buf = new byte[100];
            int idx = 0;
            
            // Magic String
            buf[idx++] = (byte)'D'; buf[idx++] = (byte)'S'; buf[idx++] = (byte)'U'; buf[idx++] = (byte)'S';
            // Protocol Version (1001)
            BitConverter.GetBytes((ushort)1001).CopyTo(buf, idx); idx += 2;
            // Payload Length (84 bytes for 0x100002)
            BitConverter.GetBytes((ushort)84).CopyTo(buf, idx); idx += 2;
            // CRC32 (placeholders, calculated later)
            idx += 4;
            // Server ID
            BitConverter.GetBytes((uint)12345).CopyTo(buf, idx); idx += 4;
            
            // --- Payload Start ---
            
            // Message Type (0x100002 for Data)
            BitConverter.GetBytes((uint)0x100002).CopyTo(buf, idx); idx += 4;
            
            // Slot ID
            buf[idx++] = slotIndex; 
            // Slot State (2 = Connected, 0 = Not Connected)
            buf[idx++] = 2;
            // Device Model (2 = Full Gyro, 1 = Partial)
            buf[idx++] = 2;
            // Connection Type (1 = USB, 2 = Bluetooth)
            buf[idx++] = 2;
            
            // MAC Address (fake per slot so they look different)
            buf[idx++] = 0x00; buf[idx++] = 0x11; buf[idx++] = 0x22; 
            buf[idx++] = 0x33; buf[idx++] = 0x44; buf[idx++] = (byte)(0x55 + slotIndex);
            // Battery Status (0x05 = Full)
            buf[idx++] = (byte)(state.BatteryLevel + 1); 
            // Is Active (1 = true)
            buf[idx++] = 1;
            BitConverter.GetBytes(PacketCounter).CopyTo(buf, idx); idx += 4;
            
            // Buttons (2 bytes)
            ushort btn = state.Buttons;
            ushort dsu = 0;
            if ((btn & 0x0001) != 0) dsu |= 0x0010; // D-Pad Up
            if ((btn & 0x0002) != 0) dsu |= 0x0040; // D-Pad Down
            if ((btn & 0x0004) != 0) dsu |= 0x0080; // D-Pad Left
            if ((btn & 0x0008) != 0) dsu |= 0x0020; // D-Pad Right
            if ((btn & 0x0010) != 0) dsu |= 0x0008; // Start -> Options
            if ((btn & 0x0020) != 0) dsu |= 0x0001; // Back -> Share
            if ((btn & 0x0040) != 0) dsu |= 0x0002; // L3
            if ((btn & 0x0080) != 0) dsu |= 0x0004; // R3
            if ((btn & 0x0100) != 0) dsu |= 0x0400; // L1
            if ((btn & 0x0200) != 0) dsu |= 0x0800; // R1
            if ((btn & 0x1000) != 0) dsu |= 0x4000; // A -> Cross
            if ((btn & 0x2000) != 0) dsu |= 0x2000; // B -> Circle
            if ((btn & 0x4000) != 0) dsu |= 0x8000; // X -> Square
            if ((btn & 0x8000) != 0) dsu |= 0x1000; // Y -> Triangle
            if (state.LT > 0) dsu |= 0x0100; // L2
            if (state.RT > 0) dsu |= 0x0200; // R2
            BitConverter.GetBytes(dsu).CopyTo(buf, idx); idx += 2; 
            
            // PS button (1 byte), Touch button (1 byte)
            buf[idx++] = (byte)(((btn & 0x0400) != 0) ? 1 : 0); // Guide -> PS button
            buf[idx++] = 0;
            
            // Stick L/R (4 bytes)
            buf[idx++] = (byte)Math.Clamp(128 + (state.LX / 256), 0, 255);
            buf[idx++] = (byte)Math.Clamp(128 - (state.LY / 256), 0, 255); // invert Y
            buf[idx++] = (byte)Math.Clamp(128 + (state.RX / 256), 0, 255);
            buf[idx++] = (byte)Math.Clamp(128 - (state.RY / 256), 0, 255); // invert Y
            
            // Analog DPAD / Shoulders / Triggers (12 bytes)
            buf[idx++] = ((dsu & 0x0080) != 0) ? (byte)255 : (byte)0; // D-Pad Left
            buf[idx++] = ((dsu & 0x0040) != 0) ? (byte)255 : (byte)0; // D-Pad Down
            buf[idx++] = ((dsu & 0x0020) != 0) ? (byte)255 : (byte)0; // D-Pad Right
            buf[idx++] = ((dsu & 0x0010) != 0) ? (byte)255 : (byte)0; // D-Pad Up
            buf[idx++] = ((dsu & 0x8000) != 0) ? (byte)255 : (byte)0; // Square
            buf[idx++] = ((dsu & 0x4000) != 0) ? (byte)255 : (byte)0; // Cross
            buf[idx++] = ((dsu & 0x2000) != 0) ? (byte)255 : (byte)0; // Circle
            buf[idx++] = ((dsu & 0x1000) != 0) ? (byte)255 : (byte)0; // Triangle
            buf[idx++] = ((dsu & 0x0800) != 0) ? (byte)255 : (byte)0; // R1
            buf[idx++] = ((dsu & 0x0400) != 0) ? (byte)255 : (byte)0; // L1
            buf[idx++] = state.RT; // R2
            buf[idx++] = state.LT; // L2
            
            // Touch Data x2 (12 bytes)
            // Each touch is: IsActive/Id (1), pad (1), X (2), Y (2) -> wait, 6 bytes total. 
            // Better to just skip 12 bytes to remain perfectly 0-filled and correctly sized.
            idx += 12; // Skip touch data
            
            // Motion Timestamp (UInt64) - Microseconds
            ulong timestamp = (ulong)(System.Diagnostics.Stopwatch.GetTimestamp() * (1_000_000.0 / System.Diagnostics.Stopwatch.Frequency));
            BitConverter.GetBytes(timestamp).CopyTo(buf, idx); idx += 8;
            
            // DSU expects: Index 1 = Yaw (Up axis), Index 2 = Roll (Forward axis).
            // Android hardware: Z = Up (Yaw), Y = Forward (Roll).
            // We swap Y and Z for BOTH sensors to maintain perfect complementary filter alignment.
            float aX = state.AccelX / 9.81f;
            float aY = state.AccelZ / 9.81f;
            float aZ = state.AccelY / 9.81f;

            float gX = state.GyroX * 57.2958f;
            float gY = state.GyroZ * 57.2958f;
            float gZ = state.GyroY * 57.2958f;

            BitConverter.GetBytes(aX).CopyTo(buf, idx); idx += 4;
BitConverter.GetBytes(aY).CopyTo(buf, idx); idx += 4;
BitConverter.GetBytes(aZ).CopyTo(buf, idx); idx += 4;

BitConverter.GetBytes(gX).CopyTo(buf, idx); idx += 4;
BitConverter.GetBytes(gY).CopyTo(buf, idx); idx += 4;
BitConverter.GetBytes(gZ).CopyTo(buf, idx); idx += 4;
            
            // Compute CRC32 over the packet
            uint crc = ComputeCrc32(buf, 0, idx);
            BitConverter.GetBytes(crc).CopyTo(buf, 8);
            
            byte[] finalBuf = new byte[idx];
            Array.Copy(buf, finalBuf, idx);
            return finalBuf;
        }

        private void SendProtocolInfo(UdpClient udp, IPEndPoint ep)
        {
            byte[] buf = new byte[24];
            int idx = 0;
            buf[idx++] = (byte)'D'; buf[idx++] = (byte)'S'; buf[idx++] = (byte)'U'; buf[idx++] = (byte)'S';
            BitConverter.GetBytes((ushort)1001).CopyTo(buf, idx); idx += 2;
            BitConverter.GetBytes((ushort)6).CopyTo(buf, idx); idx += 2; // Length 6
            idx += 4; // crc
            BitConverter.GetBytes((uint)12345).CopyTo(buf, idx); idx += 4;
            BitConverter.GetBytes((uint)0x100000).CopyTo(buf, idx); idx += 4;
            BitConverter.GetBytes((ushort)1001).CopyTo(buf, idx); idx += 2;
            
            uint crc = ComputeCrc32(buf, 0, idx);
            BitConverter.GetBytes(crc).CopyTo(buf, 8);
            
            byte[] finalBuf = new byte[idx];
            Array.Copy(buf, finalBuf, idx);
            try { udp?.Send(finalBuf, finalBuf.Length, ep); } catch { }
        }

        private void SendControllerInfo(UdpClient udp, IPEndPoint ep, byte[] requestData)
        {
            List<byte> slotsToReport = new List<byte> { 0, 1, 2, 3 };

            if (requestData.Length >= 24)
            {
                int reqPorts = BitConverter.ToInt32(requestData, 20);
                if (reqPorts > 0 && requestData.Length >= 24 + reqPorts)
                {
                    slotsToReport.Clear();
                    for (int i = 0; i < reqPorts; i++)
                    {
                        slotsToReport.Add(requestData[24 + i]);
                    }
                }
            }

            foreach (byte slot in slotsToReport)
            {
                if (slot > 3) continue;

                byte[] buf = new byte[32];
                int idx = 0;
                buf[idx++] = (byte)'D'; buf[idx++] = (byte)'S'; buf[idx++] = (byte)'U'; buf[idx++] = (byte)'S';
                BitConverter.GetBytes((ushort)1001).CopyTo(buf, idx); idx += 2;
                BitConverter.GetBytes((ushort)12).CopyTo(buf, idx); idx += 2; // Payload length is exactly 12 bytes
                idx += 4; // crc placeholder
                BitConverter.GetBytes((uint)12345).CopyTo(buf, idx); idx += 4; // Server ID
                BitConverter.GetBytes((uint)0x100001).CopyTo(buf, idx); idx += 4; // Message Type
                
                buf[idx++] = slot; // Slot
                
                // Determine if this slot is active by checking lastStates
                // udp1 handles players 1-4 (slots 0-3). udp2 handles players 5-8 (slots 0-3).
                int absolutePlayerIndex = (udp == udp1) ? (slot + 1) : (slot + 5);
                bool isActive = lastStates.ContainsKey(absolutePlayerIndex);

                buf[idx++] = isActive ? (byte)2 : (byte)0; // State (2 = Connected, 0 = Disconnected)
                buf[idx++] = 2; // Model: DS4
                buf[idx++] = 2; // Connection Type: BT
                
                // MAC Address: must match the broadcast packets! (00:11:22:33:44:55 + slot)
                buf[idx++] = 0x00; buf[idx++] = 0x11; buf[idx++] = 0x22; 
                buf[idx++] = 0x33; buf[idx++] = 0x44; buf[idx++] = (byte)(0x55 + slot);
                
                buf[idx++] = isActive ? (byte)0x05 : (byte)0x00; // Battery (5 = Full, 0 = N/A)
                buf[idx++] = 0; // Pad byte to make payload 12 bytes
                
                uint crc = ComputeCrc32(buf, 0, idx);
                BitConverter.GetBytes(crc).CopyTo(buf, 8);
                
                byte[] finalBuf = new byte[idx];
                Array.Copy(buf, finalBuf, idx);
                try { udp?.Send(finalBuf, finalBuf.Length, ep); } catch { }
            }
        }

        private static readonly uint[] CrcTable = new uint[256];
        static CemuhookServer()
        {
            for (uint i = 0; i < 256; i++) {
                uint crc = i;
                for (int j = 8; j > 0; j--) {
                    if ((crc & 1) == 1) crc = (crc >> 1) ^ 0xEDB88320;
                    else crc >>= 1;
                }
                CrcTable[i] = crc;
            }
        }

        private static uint ComputeCrc32(byte[] data, int offset, int length)
        {
            uint crc = 0xFFFFFFFF;
            for (int i = 0; i < length; i++) {
                byte index = (byte)(((crc) & 0xff) ^ data[offset + i]);
                crc = (crc >> 8) ^ CrcTable[index];
            }
            return ~crc;
        }
    }
}

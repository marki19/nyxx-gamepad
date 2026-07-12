using System;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace NativeGamepadServer
{
    public class DiscoveryServer
    {
        private UdpClient? listener;
        private CancellationTokenSource? cts;
        private readonly int discoveryPort = 5001;
        private readonly int gamepadPort;

        public DiscoveryServer(int gamepadPort)
        {
            this.gamepadPort = gamepadPort;
        }

        public void Start()
        {
            Stop();
            cts = new CancellationTokenSource();
            
            Task.Run(async () =>
            {
                try
                {
                    listener = new UdpClient();
                    // Allow multiple instances to bind to the same port (though not strictly necessary here)
                    listener.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
                    listener.Client.Bind(new IPEndPoint(IPAddress.Any, discoveryPort));

                    while (!cts.Token.IsCancellationRequested)
                    {
                        var result = await listener.ReceiveAsync(cts.Token);
                        string message = Encoding.UTF8.GetString(result.Buffer);

                        if (message == "NYXX_DISCOVER")
                        {
                            string hostname = Environment.MachineName;
                            string reply = $"NYXX_SERVER|{hostname}|{gamepadPort}";
                            byte[] replyBytes = Encoding.UTF8.GetBytes(reply);

                            // Reply back to the exact sender port and IP
                            await listener.SendAsync(replyBytes, replyBytes.Length, result.RemoteEndPoint);
                        }
                    }
                }
                catch (OperationCanceledException) { /* Ignored on stop */ }
                catch (Exception ex)
                {
                    Console.WriteLine($"DiscoveryServer error: {ex.Message}");
                }
            }, cts.Token);
        }

        public void Stop()
        {
            if (cts != null)
            {
                cts.Cancel();
                cts.Dispose();
                cts = null;
            }

            if (listener != null)
            {
                listener.Close();
                listener.Dispose();
                listener = null;
            }
        }
    }
}

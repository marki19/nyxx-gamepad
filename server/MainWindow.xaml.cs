#nullable enable
using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Net;
using System.Net.Sockets;
using System.IO;
using System.Linq;
using System.IO.Pipes;
using System.Threading.Tasks;
using System.Reflection;

namespace NativeGamepadServer
{
    public partial class MainWindow : Window
    {
        private class ConnectedClientInfo
        {
            public string Ip { get; set; } = "";
            public string ControllerType { get; set; } = "Pro Controller";
            public string Battery { get; set; } = "Unknown";
        }
        private readonly ConnectedClientInfo?[] activeClients = new ConnectedClientInfo?[9]; // 1-8

        private GamepadServer server;
        private DiscoveryServer? discoveryServer;
        private System.Windows.Forms.NotifyIcon? notifyIcon;

        public MainWindow()
        {
            InitializeComponent();
            txtVersion.Text = " v" + System.Reflection.Assembly.GetExecutingAssembly().GetName().Version?.ToString();
            server = new GamepadServer();
            server.PlayerConnected += Server_PlayerConnected;
            server.PlayerDisconnected += Server_PlayerDisconnected;
            server.StateUpdated += Server_StateUpdated;
            server.LogMessage += Server_LogMessage;

            SetupNotifyIcon();
            StartIpcServer();
            _ = CheckForUpdatesAsync();
            AppendLog("Nyxx Server UI Initialized. Ready to start.");
            UpdateIpDisplay();

            var versionObj = typeof(MainWindow).Assembly.GetName().Version;
            string localVersion = "v" + (versionObj != null ? $"{versionObj.Major}.{versionObj.Minor}.{versionObj.Build}" : "1.0.0");
            txtVersion.Text = " " + localVersion;
        }

        private async Task CheckForUpdatesAsync()
        {
            try
            {
                using (var client = new System.Net.Http.HttpClient())
                {
                    client.DefaultRequestHeaders.Add("User-Agent", "NyxxServer-Updater");
                    
                    string repoOwner = "marki19"; 
                    string repoName = "nyxx-gamepad";
                    
                    string url = $"https://api.github.com/repos/{repoOwner}/{repoName}/releases/latest";
                    
                    var response = await client.GetAsync(url);
                    if (response.IsSuccessStatusCode)
                    {
                        string json = await response.Content.ReadAsStringAsync();
                        using (var doc = System.Text.Json.JsonDocument.Parse(json))
                        {
                            string tagName = doc.RootElement.GetProperty("tag_name").GetString() ?? "";
                            string htmlUrl = doc.RootElement.GetProperty("html_url").GetString() ?? "";
                            
                            // Dynamically read version safely for single-file executables
                            var versionObj = typeof(MainWindow).Assembly.GetName().Version;
                            string localVersion = "v" + (versionObj != null ? $"{versionObj.Major}.{versionObj.Minor}.{versionObj.Build}" : "0.0.0");
                            
                            if (!string.IsNullOrEmpty(tagName) && tagName != localVersion)
                            {
                                Dispatcher.Invoke(() =>
                                {
                                    var result = System.Windows.MessageBox.Show(
                                        $"A new version of Nyxx Server ({tagName}) is available!\n\nWould you like to download it now?",
                                        "Update Available",
                                        System.Windows.MessageBoxButton.YesNo,
                                        System.Windows.MessageBoxImage.Information);
                                        
                                    if (result == System.Windows.MessageBoxResult.Yes)
                                    {
                                        System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
                                        {
                                            FileName = htmlUrl,
                                            UseShellExecute = true
                                        });
                                    }
                                });
                            }
                        }
                    }
                }
            }
            catch (Exception ex)
            {
                Dispatcher.Invoke(() => AppendLog("Failed to check for updates: " + ex.Message));
            }
        }

        private void UpdateIpDisplay()
        {
            string ip = GetLocalIPAddress();
            string port = txtPort.Text;
            txtIp.Text = $"IP: {ip}:{port}";
        }

        private string GetLocalIPAddress()
        {
            var host = Dns.GetHostEntry(Dns.GetHostName());
            foreach (var ip in host.AddressList)
            {
                if (ip.AddressFamily == AddressFamily.InterNetwork)
                {
                    return ip.ToString();
                }
            }
            return "127.0.0.1";
        }

        private void StartIpcServer()
        {
            Task.Run(async () =>
            {
                while (true)
                {
                    try
                    {
                        using (var serverStream = new NamedPipeServerStream("NyxxServer_IPC_Pipe", PipeDirection.In, 1, PipeTransmissionMode.Byte, PipeOptions.Asynchronous))
                        {
                            await serverStream.WaitForConnectionAsync();
                            using (var reader = new StreamReader(serverStream))
                            {
                                string msg = await reader.ReadLineAsync();
                                if (msg == "WAKE_UP")
                                {
                                    Dispatcher.Invoke(() =>
                                    {
                                        Show();
                                        WindowState = WindowState.Normal;
                                    });
                                }
                            }
                        }
                    }
                    catch (Exception)
                    {
                        // Ignore exceptions and retry
                        await Task.Delay(1000);
                    }
                }
            });
        }

        private void SetupNotifyIcon()
        {
            notifyIcon = new System.Windows.Forms.NotifyIcon();
            
            string exePath = System.Diagnostics.Process.GetCurrentProcess().MainModule?.FileName ?? string.Empty;
            if (!string.IsNullOrEmpty(exePath))
            {
                try {
                    notifyIcon.Icon = System.Drawing.Icon.ExtractAssociatedIcon(exePath);
                } catch {
                    notifyIcon.Icon = System.Drawing.SystemIcons.Application;
                }
            } else {
                notifyIcon.Icon = System.Drawing.SystemIcons.Application;
            }
            notifyIcon.Visible = true;
            notifyIcon.Text = "Nexus Gamepad Server";
            notifyIcon.DoubleClick += (s, args) =>
            {
                Show();
                WindowState = WindowState.Normal;
            };

            var contextMenu = new System.Windows.Forms.ContextMenuStrip();
            
            var restoreItem = new System.Windows.Forms.ToolStripMenuItem("Restore");
            restoreItem.Click += (s, e) => {
                Show();
                WindowState = WindowState.Normal;
            };
            
            var exitItem = new System.Windows.Forms.ToolStripMenuItem("Exit");
            exitItem.Click += (s, e) => {
                notifyIcon.Visible = false;
                System.Windows.Application.Current.Shutdown();
            };
            
            contextMenu.Items.Add(restoreItem);
            contextMenu.Items.Add(exitItem);
            
            notifyIcon.ContextMenuStrip = contextMenu;
        }

        private void Window_StateChanged(object sender, EventArgs e)
        {
            if (WindowState == WindowState.Minimized)
            {
                Hide();
                notifyIcon?.ShowBalloonTip(2000, "Nyxx Server", "Server is running in the background.", System.Windows.Forms.ToolTipIcon.Info);
            }
            else
            {
                Dispatcher.BeginInvoke(new Action(() =>
                {
                    InvalidateMeasure();
                    UpdateLayout();
                }), System.Windows.Threading.DispatcherPriority.Loaded);
            }
        }

        private void BtnToggle_Click(object sender, RoutedEventArgs e)
        {
            if (server.IsRunning)
            {
                server.Stop();
                discoveryServer?.Stop();
                btnToggle.Content = "START SERVER";
                btnToggle.Background = new SolidColorBrush((System.Windows.Media.Color)System.Windows.Media.ColorConverter.ConvertFromString("#888888"));
                txtPort.IsEnabled = true;
                txtDsuPort.IsEnabled = true;
                cboControllerType.IsEnabled = true;
                ResetSlots();
                AppendLog("Server stopped.");
            }
            else
            {
                if (int.TryParse(txtPort.Text, out int port) && int.TryParse(txtDsuPort.Text, out int dsuPort))
                {
                    int p = server.Start(port, dsuPort);
                    if (p > 0)
                    {
                        server.SelectedControllerType = cboControllerType.SelectedIndex == 1 ? ControllerType.DualShock4 : ControllerType.Xbox360;
                        discoveryServer = new DiscoveryServer(p);
                        discoveryServer.Start();

                        txtPort.Text = p.ToString();
                        UpdateIpDisplay();
                        
                        btnToggle.Content = "STOP SERVER";
                        btnToggle.Background = new SolidColorBrush((System.Windows.Media.Color)System.Windows.Media.ColorConverter.ConvertFromString("#FF5C5C"));
                        txtPort.IsEnabled = false;
                        txtDsuPort.IsEnabled = false;
                        cboControllerType.IsEnabled = false;
                        if (server.DsuRunning) {
                            AppendLog($"DSU/Cemuhook available on ports {dsuPort} and {dsuPort + 1}");
                        } else {
                            AppendLog($"DSU/Cemuhook failed to start. Port {dsuPort} or {dsuPort + 1} may be in use.");
                        }
                    }
                }
                else
                {
                    System.Windows.MessageBox.Show("Invalid port number.");
                }
            }
        }

        private void AppendLog(string message)
{
    Dispatcher.BeginInvoke(() =>
    {
        if (txtConsole.Text.Length > 10000)
        {
            txtConsole.Text = txtConsole.Text.Substring(5000);
        }
        txtConsole.Text += $"[{DateTime.Now:HH:mm:ss}] {message}\n";
        logScroll.ScrollToEnd();
    });
}

        private void Server_LogMessage(object? sender, LogMessageEventArgs e)
        {
            AppendLog(e.Message);
        }

        private void Server_PlayerConnected(object? sender, ClientConnectedEventArgs e)
        {
            Dispatcher.Invoke(() =>
            {
                if (e.PlayerIndex >= 1 && e.PlayerIndex <= 8)
                {
                    activeClients[e.PlayerIndex] = new ConnectedClientInfo { Ip = e.IpAddress };
                }
                UpdateSlot(e.PlayerIndex, true, e.IpAddress);
                UpdateConnectionStatusUI();
            });
        }

        private void Server_PlayerDisconnected(object? sender, ClientConnectedEventArgs e)
        {
            Dispatcher.Invoke(() =>
            {
                if (e.PlayerIndex >= 1 && e.PlayerIndex <= 8)
                {
                    activeClients[e.PlayerIndex] = null;
                }
                UpdateSlot(e.PlayerIndex, false, "");
                UpdateConnectionStatusUI();
            });
        }

        private long lastUiUpdateTicks = 0;

        private void Server_StateUpdated(object? sender, GamepadStateArgs e)
        {
            if (e.PlayerIndex >= 1 && e.PlayerIndex <= 8)
            {
                var info = activeClients[e.PlayerIndex];
                if (info != null)
                {
                    string batt = e.BatteryLevel switch
                    {
                        0 => "Empty",
                        1 => "Low",
                        2 => "Medium",
                        3 => "High",
                        4 => "Full",
                        _ => "Unknown"
                    };
                    string type = e.JoyConType switch
                    {
                        JoyConType.Left => "Joy-Con (L)",
                        JoyConType.Right => "Joy-Con (R)",
                        JoyConType.Pro => "Pro Controller",
                        _ => "Gamepad"
                    };

                    if (info.Battery != batt || info.ControllerType != type)
                    {
                        info.Battery = batt;
                        info.ControllerType = type;
                        UpdateConnectionStatusUI();
                    }
                }
            }

            long currentTicks = DateTime.UtcNow.Ticks;
            if (currentTicks - lastUiUpdateTicks < 330000) return; // Rate limit to ~30 FPS (33ms) to prevent UI freezing
            lastUiUpdateTicks = currentTicks;

            Dispatcher.InvokeAsync(() =>
            {
                switch (e.PlayerIndex)
                {
                    case 1: UpdateTesterState(thumb1L, thumb1R, prog1L, prog1R, btn1Txt, e); break;
                    case 2: UpdateTesterState(thumb2L, thumb2R, prog2L, prog2R, btn2Txt, e); break;
                    case 3: UpdateTesterState(thumb3L, thumb3R, prog3L, prog3R, btn3Txt, e); break;
                    case 4: UpdateTesterState(thumb4L, thumb4R, prog4L, prog4R, btn4Txt, e); break;
                    case 5: UpdateTesterState(thumb5L, thumb5R, prog5L, prog5R, btn5Txt, e); break;
                    case 6: UpdateTesterState(thumb6L, thumb6R, prog6L, prog6R, btn6Txt, e); break;
                    case 7: UpdateTesterState(thumb7L, thumb7R, prog7L, prog7R, btn7Txt, e); break;
                    case 8: UpdateTesterState(thumb8L, thumb8R, prog8L, prog8R, btn8Txt, e); break;
                }
            });
        }

        private void UpdateConnectionStatusUI()
        {
            int activeCount = 0;
            var sb = new System.Text.StringBuilder();
            for (int i = 1; i <= 8; i++)
            {
                var client = activeClients[i];
                if (client != null)
                {
                    activeCount++;
                    sb.AppendLine($"Player {i}: {client.Ip}");
                    sb.AppendLine($"Type: {client.ControllerType}");
                    sb.AppendLine($"Battery: {client.Battery}");
                    sb.AppendLine("-----------------------");
                }
            }

            Dispatcher.BeginInvoke(() =>
            {
                txtActiveClients.Text = $"Connected Devices: {activeCount}/8";
                if (activeCount == 0)
                {
                    borderClientDetails.Visibility = Visibility.Collapsed;
                }
                else
                {
                    borderClientDetails.Visibility = Visibility.Visible;
                    txtClientDetails.Text = sb.ToString().TrimEnd();
                }
            });
        }

        private void UpdateTesterState(System.Windows.Shapes.Ellipse thumbL, System.Windows.Shapes.Ellipse thumbR, System.Windows.Controls.ProgressBar progL, System.Windows.Controls.ProgressBar progR, TextBlock btnTxt, GamepadStateArgs e)
        {
            // Center is 16, range is 0 to 32 (ellipse is 8x8 inside 40x40 canvas)
            Canvas.SetLeft(thumbL, 16 + (e.LX / 32768.0) * 16);
            Canvas.SetTop(thumbL, 16 - (e.LY / 32768.0) * 16);
            
            Canvas.SetLeft(thumbR, 16 + (e.RX / 32768.0) * 16);
            Canvas.SetTop(thumbR, 16 - (e.RY / 32768.0) * 16);

            progL.Value = e.LT;
            progR.Value = e.RT;

            var btns = new System.Collections.Generic.List<string>();
            if ((e.Buttons & 0x0001) != 0) btns.Add("DPadUp");
            if ((e.Buttons & 0x0002) != 0) btns.Add("DPadDown");
            if ((e.Buttons & 0x0004) != 0) btns.Add("DPadLeft");
            if ((e.Buttons & 0x0008) != 0) btns.Add("DPadRight");
            if ((e.Buttons & 0x0010) != 0) btns.Add("Start");
            if ((e.Buttons & 0x0020) != 0) btns.Add("Back");
            if ((e.Buttons & 0x0040) != 0) btns.Add("L3");
            if ((e.Buttons & 0x0080) != 0) btns.Add("R3");
            if ((e.Buttons & 0x0100) != 0) btns.Add("LB");
            if ((e.Buttons & 0x0200) != 0) btns.Add("RB");
            if ((e.Buttons & 0x1000) != 0) btns.Add("A");
            if ((e.Buttons & 0x2000) != 0) btns.Add("B");
            if ((e.Buttons & 0x4000) != 0) btns.Add("X");
            if ((e.Buttons & 0x8000) != 0) btns.Add("Y");
            
        }

        private static readonly string[] PlayerColors = {
            "", // index 0 unused
            "#0078D7", // P1 — Xbox Blue
            "#FF5C5C", // P2 — Red
            "#3DDC84", // P3 — Green
            "#FFD54F", // P4 — Yellow
            "#AF87FF", // P5 — Purple
            "#4FC3F7", // P6 — Cyan
            "#FF8C00", // P7 — Orange
            "#CF9FFF"  // P8 — Violet
        };

        private void UpdateSlot(int index, bool connected, string ip)
        {
            string colorHex = connected
                ? (index >= 1 && index <= 8 ? PlayerColors[index] : "#888888")
                : "#3D4249";
            var brush = new SolidColorBrush((System.Windows.Media.Color)System.Windows.Media.ColorConverter.ConvertFromString(colorHex));
            string text = connected ? $"Connected: {ip}" : "Waiting...";

            switch (index)
            {
                case 1:
                    slot1.BorderBrush = brush; lblP1.Foreground = brush; txtP1.Text = text; testerP1.Visibility = Visibility.Visible; testerP1.Opacity = connected ? 1.0 : 0.2; break;
                case 2:
                    slot2.BorderBrush = brush; lblP2.Foreground = brush; txtP2.Text = text; testerP2.Visibility = Visibility.Visible; testerP2.Opacity = connected ? 1.0 : 0.2; break;
                case 3:
                    slot3.BorderBrush = brush; lblP3.Foreground = brush; txtP3.Text = text; testerP3.Visibility = Visibility.Visible; testerP3.Opacity = connected ? 1.0 : 0.2; break;
                case 4:
                    slot4.BorderBrush = brush; lblP4.Foreground = brush; txtP4.Text = text; testerP4.Visibility = Visibility.Visible; testerP4.Opacity = connected ? 1.0 : 0.2; break;
                case 5:
                    slot5.BorderBrush = brush; lblP5.Foreground = brush; txtP5.Text = text; testerP5.Visibility = Visibility.Visible; testerP5.Opacity = connected ? 1.0 : 0.2; break;
                case 6:
                    slot6.BorderBrush = brush; lblP6.Foreground = brush; txtP6.Text = text; testerP6.Visibility = Visibility.Visible; testerP6.Opacity = connected ? 1.0 : 0.2; break;
                case 7:
                    slot7.BorderBrush = brush; lblP7.Foreground = brush; txtP7.Text = text; testerP7.Visibility = Visibility.Visible; testerP7.Opacity = connected ? 1.0 : 0.2; break;
                case 8:
                    slot8.BorderBrush = brush; lblP8.Foreground = brush; txtP8.Text = text; testerP8.Visibility = Visibility.Visible; testerP8.Opacity = connected ? 1.0 : 0.2; break;
            }
        }

        private void ResetSlots()
        {
            for (int i = 1; i <= 8; i++)
            {
                activeClients[i] = null;
            }
            UpdateConnectionStatusUI();
            UpdateSlot(1, false, ""); UpdateSlot(2, false, ""); UpdateSlot(3, false, ""); UpdateSlot(4, false, "");
            UpdateSlot(5, false, ""); UpdateSlot(6, false, ""); UpdateSlot(7, false, ""); UpdateSlot(8, false, "");
        }

        protected override void OnClosed(EventArgs e)
        {
            if (server != null && server.IsRunning)
            {
                server.Stop();
            }
            if (notifyIcon != null)
            {
                notifyIcon.Visible = false;
                notifyIcon.Dispose();
            }
            base.OnClosed(e);
        }
    }
}


using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Net;
using System.Net.Sockets;
using System.IO;
using QRCoder;
using System.Linq;
using System.IO.Pipes;
using System.Threading.Tasks;
using System.Reflection;

namespace NativeGamepadServer
{
    public partial class MainWindow : Window
    {
        private GamepadServer server;
        private System.Windows.Forms.NotifyIcon? notifyIcon;

        public MainWindow()
        {
            InitializeComponent();
            server = new GamepadServer();
            server.PlayerConnected += Server_PlayerConnected;
            server.PlayerDisconnected += Server_PlayerDisconnected;
            server.StateUpdated += Server_StateUpdated;
            server.LogMessage += Server_LogMessage;

            SetupNotifyIcon();
            StartIpcServer();
            _ = CheckForUpdatesAsync();
            AppendLog("Nexus Server UI Initialized. Ready to start.");
            UpdateQrCode();
        }

        private async Task CheckForUpdatesAsync()
        {
            try
            {
                using (var client = new System.Net.Http.HttpClient())
                {
                    client.DefaultRequestHeaders.Add("User-Agent", "NyxxPadServer-Updater");
                    
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
                                        $"A new version of NyxxPad Server ({tagName}) is available!\n\nWould you like to download it now?",
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

        private void UpdateQrCode()
        {
            string ip = GetLocalIPAddress();
            string port = txtPort.Text;
            txtIp.Text = $"IP: {ip}";
            
            string qrData = $"nyxxpad://connect?ip={ip}&port={port}";
            using (QRCodeGenerator qrGenerator = new QRCodeGenerator())
            using (QRCodeData qrCodeData = qrGenerator.CreateQrCode(qrData, QRCodeGenerator.ECCLevel.Q))
            using (QRCode qrCode = new QRCode(qrCodeData))
            {
                using (var bitmap = qrCode.GetGraphic(20))
                {
                    using (MemoryStream memory = new MemoryStream())
                    {
                        bitmap.Save(memory, System.Drawing.Imaging.ImageFormat.Png);
                        memory.Position = 0;
                        BitmapImage bitmapImage = new BitmapImage();
                        bitmapImage.BeginInit();
                        bitmapImage.StreamSource = memory;
                        bitmapImage.CacheOption = BitmapCacheOption.OnLoad;
                        bitmapImage.EndInit();
                        qrImage.Source = bitmapImage;
                    }
                }
            }
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
                notifyIcon?.ShowBalloonTip(2000, "Nexus Server", "Server is running in the background.", System.Windows.Forms.ToolTipIcon.Info);
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
                btnToggle.Content = "START SERVER";
                btnToggle.Background = new SolidColorBrush((System.Windows.Media.Color)System.Windows.Media.ColorConverter.ConvertFromString("#8A2BE2"));
                txtPort.IsEnabled = true;
                ResetSlots();
                AppendLog("Server stopped.");
            }
            else
            {
                if (int.TryParse(txtPort.Text, out int port))
                {
                    int activePort = server.Start(port);
                    if (server.IsRunning && activePort > 0)
                    {
                        txtPort.Text = activePort.ToString();
                        UpdateQrCode();
                        btnToggle.Content = "STOP SERVER";
                        btnToggle.Background = new SolidColorBrush((System.Windows.Media.Color)System.Windows.Media.ColorConverter.ConvertFromString("#FF5C5C"));
                        txtPort.IsEnabled = false;
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
            Dispatcher.Invoke(() =>
            {
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
                UpdateSlot(e.PlayerIndex, true, e.IpAddress);
            });
        }

        private void Server_PlayerDisconnected(object? sender, ClientConnectedEventArgs e)
        {
            Dispatcher.Invoke(() =>
            {
                UpdateSlot(e.PlayerIndex, false, "");
            });
        }

        private void Server_StateUpdated(object? sender, GamepadStateArgs e)
        {
            Dispatcher.InvokeAsync(() =>
            {
                switch (e.PlayerIndex)
                {
                    case 1: UpdateTesterState(thumb1L, thumb1R, prog1L, prog1R, btn1Txt, e); break;
                    case 2: UpdateTesterState(thumb2L, thumb2R, prog2L, prog2R, btn2Txt, e); break;
                    case 3: UpdateTesterState(thumb3L, thumb3R, prog3L, prog3R, btn3Txt, e); break;
                    case 4: UpdateTesterState(thumb4L, thumb4R, prog4L, prog4R, btn4Txt, e); break;
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

        private void UpdateSlot(int index, bool connected, string ip)
        {
            string colorHex = connected ? "#8A2BE2" : "#3D4249";
            var brush = new SolidColorBrush((System.Windows.Media.Color)System.Windows.Media.ColorConverter.ConvertFromString(colorHex));
            string text = connected ? $"Connected: {ip}" : "Waiting...";

            switch (index)
            {
                case 1:
                    slot1.BorderBrush = brush;
                    lblP1.Foreground = brush;
                    txtP1.Text = text;
                    testerP1.Visibility = Visibility.Visible;
                    testerP1.Opacity = connected ? 1.0 : 0.2;
                    break;
                case 2:
                    slot2.BorderBrush = brush;
                    lblP2.Foreground = brush;
                    txtP2.Text = text;
                    testerP2.Visibility = Visibility.Visible;
                    testerP2.Opacity = connected ? 1.0 : 0.2;
                    break;
                case 3:
                    slot3.BorderBrush = brush;
                    lblP3.Foreground = brush;
                    txtP3.Text = text;
                    testerP3.Visibility = Visibility.Visible;
                    testerP3.Opacity = connected ? 1.0 : 0.2;
                    break;
                case 4:
                    slot4.BorderBrush = brush;
                    lblP4.Foreground = brush;
                    txtP4.Text = text;
                    testerP4.Visibility = Visibility.Visible;
                    testerP4.Opacity = connected ? 1.0 : 0.2;
                    break;
            }
        }

        private void ResetSlots()
        {
            UpdateSlot(1, false, "");
            UpdateSlot(2, false, "");
            UpdateSlot(3, false, "");
            UpdateSlot(4, false, "");
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


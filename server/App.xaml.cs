using System;
using System.Threading;
using System.Windows;
using System.IO.Pipes;
using System.IO;

namespace NativeGamepadServer
{
    public partial class App : System.Windows.Application
    {
        private static Mutex _mutex = null;
        private const string AppMutexName = "NyxxServer_SingleInstance_Mutex";
        private const string PipeName = "NyxxServer_IPC_Pipe";

        protected override void OnStartup(StartupEventArgs e)
        {
            bool createdNew;
            _mutex = new Mutex(true, AppMutexName, out createdNew);

            if (!createdNew)
            {
                // App is already running. Send a wakeup signal via IPC.
                try
                {
                    using (var client = new NamedPipeClientStream(".", PipeName, PipeDirection.Out))
                    {
                        // 1 second timeout so it doesn't hang if the first instance is deadlocked
                        client.Connect(1000);
                        using (var writer = new StreamWriter(client))
                        {
                            writer.WriteLine("WAKE_UP");
                            writer.Flush();
                        }
                    }
                }
                catch (Exception)
                {
                    // Ignore IPC failures if the first instance isn't listening for some reason
                }

                System.Windows.Application.Current.Shutdown();
                return;
            }

            base.OnStartup(e);
        }
    }
}

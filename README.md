# NyxxPad 🎮

NyxxPad is a high-performance, ultra-low latency application that instantly turns your Android phone into a fully functional Xbox 360 controller for your Windows PC.

## ✨ Features

* **Tri-Mode Connectivity:** Connect your phone to your PC via high-speed **Wi-Fi**, ultra-low latency **USB Tethering**, or wireless **Bluetooth**.
* **Native XInput Support:** Games automatically recognize your phone as a standard Xbox 360 controller. No game-specific configuration or mapping is required!
* **Zero Bloatware:** Built from the ground up for maximum performance and minimal battery drain. 
* **Automatic Updates:** Built-in OTA updater checks GitHub for the latest releases so you're always running the newest version.

## 📦 Installation

NyxxPad requires two components to work: the Windows Server and the Android App.

### 1. Windows Server Setup
1. Go to the [Releases](../../releases/latest) page and download `NyxxServer_Setup.exe`.
2. Run the installer on your Windows PC. It will automatically install the necessary ViGEmBus driver and the NyxxPad Server.
3. Open the **NyxxPad Server** from your Start Menu.

### 2. Android App Setup
1. Go to the [Releases](../../releases/latest) page on your Android phone and download `app-release.apk`.
2. Open the file to install the app (you may need to allow "Install from unknown sources" in your Android settings).
3. Open the NyxxPad app, choose your connection method (Wi-Fi, USB, or Bluetooth), and connect to the IP/Port shown on the PC server.

## 🛠️ Building from Source

If you prefer to build the applications yourself:

**Windows Server:**
Built using WPF and .NET 8.
```bash
cd server
dotnet build -c Release
```

**Android Client:**
Built using Kotlin and Android Studio.
```bash
cd client
./gradlew assembleRelease
```

## 📜 License
This project is open-source and free to use.

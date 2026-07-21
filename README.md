# Nyxx 🎮

![AI Assisted](https://img.shields.io/badge/AI-Assisted-8A2BE2?style=for-the-badge&logo=openai)

Nyxx is a high-performance, ultra-low latency application that instantly turns your Android phone into a fully functional Xbox 360 controller for your Windows PC.

## ✨ Features

- **Tri-Mode Connectivity:** Connect via high-speed **Wi-Fi**, ultra-low latency **USB Tethering**, or wireless **Bluetooth**.
- **Native XInput Support:** Games automatically recognize your phone as a standard Xbox 360 controller — no game-specific configuration or mapping required.
- **Multiplayer:** Up to 8 controllers can connect at once! (Players 1-4 get full PC/Xbox & Emulator compatibility. Players 5-8 get Emulator-only compatibility via DSU).
- **QR Code Quick Connect:** Scan the QR code shown on the PC server to connect instantly, no manual IP entry needed.
- **Motion Controls:** Optional gyro/tilt steering with 3 selectable modes and one-tap calibration.
- **Customizable Layout:** Resize, reposition, and adjust the opacity of every on-screen button group; toggle turbo and analog-trigger behavior per button.
- **Multiple Controller Profiles:** Switch between Nintendo, Xbox, and PSP-style button layouts.
- **Rumble Feedback:** Controller vibration is forwarded from the game back to your phone.
- **Runs in the Background:** The PC server minimizes to the system tray and keeps running quietly.
- **Zero Bloatware:** Built from the ground up for maximum performance and minimal battery drain.
- **Update Notifications:** Both the PC server and Android app automatically check GitHub for new releases and let you know — with a one-click link to download — whenever a newer version is available.

## 🕹️ Supported Emulators & Games

Nyxx operates at the Windows Driver level (as an Xbox 360 controller) and implements the industry-standard DSU/Cemuhook protocol, meaning it supports **literally every modern emulator and PC game**.

**Full Motion + Button Support (via DSU Protocol):**
Perfect for games that require shaking, steering, or pointing.
- **Cemu** (Wii U)
- **Dolphin** (Wii & GameCube)
- **Yuzu** / **Ryujinx** (Nintendo Switch)
- **Citra** (Nintendo 3DS)
- **Vita3K** (PS Vita)

**Standard Controller Support (via Virtual Xbox Controller):**
Perfectly detected as a standard Xbox controller for non-motion gaming.
- **Steam, Epic Games, Xbox Game Pass** (All PC Games)
- **PCSX2** (PlayStation 2)
- **RPCS3** (PlayStation 3)
- **DuckStation** (PlayStation 1)
- **PPSSPP** (PSP)
- **Xenia** (Xbox 360)
- **xemu** (Original Xbox)
- **RetroArch** (All classic consoles)


## 📦 Installation

Nyxx requires two components to work: the Windows Server and the Android App.

### Requirements

- Windows 10/11 (64-bit)
- Android phone on the same Wi-Fi network as your PC (for Wi-Fi mode), or a USB/Bluetooth connection

### 1. Windows Server Setup

1. Go to the [Releases](../../releases/latest) page and download `NyxxServer_Setup.exe`.
2. Run the installer on your Windows PC. It will automatically install the necessary ViGEmBus driver and the Nyxx Server.
   - If Windows SmartScreen shows a warning, click **More info → Run anyway** — the installer isn't code-signed yet.
3. Open the **Nyxx Server** from your Start Menu.

### 2. Android App Setup

1. Go to the [Releases](../../releases/latest) page on your Android phone and download `Nyxx.apk`.
2. Open the file to install the app (you may need to allow "Install from unknown sources" in your Android settings).
3. Open the Nyxx app, choose your connection method (Wi-Fi, USB, or Bluetooth).
4. Scan the QR code shown on the PC server, or enter the IP/Port manually, to connect.

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

This project is licensed under the [MIT License](LICENSE).

## 🙌 Acknowledgements

This project was proudly **AI-assisted**, using advanced agentic tools to rapidly prototype, build, and deploy the entire hardware-emulation stack from scratch.

Nyxx utilizes the excellent **ViGEmBus** driver (Copyright © Nefarius Software Solutions e.U.) for Windows gamepad emulation. Please see [THIRD-PARTY-NOTICES.txt](THIRD-PARTY-NOTICES.txt) for license details.
hehe

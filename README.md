# Nyxx 🎮

![AI Assisted](https://img.shields.io/badge/AI-Assisted-8A2BE2?style=for-the-badge&logo=openai)

Nyxx is a high-performance, ultra-low latency application that turns your Android phone into a customizable Xbox 360 controller for your Windows PC, as well as a direct Serial Bluetooth controller for Arduino and DIY robotics.

## ✨ Features

- **Tri-Mode Connectivity:** 
  - 🌐 **Wi-Fi / Hotspot (Local Network):** Connects wirelessly to the Nyxx PC Server for PC games and emulators with auto-discovery.
  - 🔌 **Wired (USB Tethering):** Ultra-low latency wired connection to the Nyxx PC Server.
  - 🤖 **Bluetooth (Arduino / RC Mode):** Direct Serial Port Profile (SPP) connection to Arduino/ESP32 robot cars using HC-05 / HC-06 modules.
- **Native XInput Support:** Games automatically recognize your phone as a standard Xbox 360 controller via ViGEmBus — no game-specific configuration required.
- **Multiplayer:** Up to 8 controllers can connect at once! (Players 1-4 get full PC/Xbox & Emulator compatibility. Players 5-8 get Emulator-only compatibility via DSU).
- **Motion Controls:** Optional gyro/tilt steering with selectable modes and one-tap sensor calibration.
- **Customizable Layout:** Resize, reposition, and adjust the opacity of every on-screen button group; toggle turbo and analog-trigger behavior per button.
- **Multiple Controller Profiles:** Switch between Nintendo, Joy-Con (L/R), Wii Remote, Xbox, and PSP-style button layouts.
- **Haptic Rumble Feedback:** Controller vibration forwarded from your PC game back to your phone.
- **Direct-to-Arduino Serial Stream:** Sends discrete ASCII character commands (`'F'`, `'B'`, `'L'`, `'R'`, `'S'`) throttled to 20Hz for DIY robotics.
- **Runs in the Background:** The PC server minimizes to the system tray and keeps running quietly.
- **Zero Bloatware:** Built from the ground up for maximum performance and minimal battery drain.

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
- **Steam, Epic Games, Xbox Game Pass** (All PC Games)
- **PCSX2** (PlayStation 2)
- **RPCS3** (PlayStation 3)
- **DuckStation** (PlayStation 1)
- **PPSSPP** (PSP)
- **Xenia** (Xbox 360)
- **xemu** (Original Xbox)
- **RetroArch** (All classic consoles)

---

## 📦 Installation & Setup

Nyxx provides two software components: the Windows Server (for PC gaming) and the Android App.

### 1. Windows Server Setup (For PC Gaming / Emulators)
1. Go to the [Releases](../../releases/latest) page and download `NyxxServer_Setup.exe`.
2. Run the installer on your Windows PC. It will automatically install the necessary ViGEmBus driver and the Nyxx Server.
3. Open the **Nyxx Server** from your Start Menu.

### 2. Android App Setup
1. Download and install `Nyxx.apk` on your Android phone from the [Releases](../../releases/latest) page.
2. Open the Nyxx app and choose your connection method:
   - **Wi-Fi / Hotspot:** Auto-discovers and connects to your Nyxx PC Server.
   - **Wired (USB Tethering):** Connects via USB cable with Android USB Tethering enabled.
   - **Bluetooth (Arduino / RC Mode):** Connects directly to HC-05 / HC-06 Bluetooth modules on Arduino/ESP32 robotics.

---

## 🛠️ Building from Source

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

Nyxx utilizes the excellent **ViGEmBus** driver (Copyright © Nefarius Software Solutions e.U.) for Windows gamepad emulation. Please see [THIRD-PARTY-NOTICES.txt](THIRD-PARTY-NOTICES.txt) for license details.

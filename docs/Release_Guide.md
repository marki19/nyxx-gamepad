# Release Build Guide

When you are ready to publish a new version of NativeGamepad to GitHub Releases, follow these steps to generate the standalone assets for both the PC Server and the Android Client.

---

## 1. Building the PC Server (`.exe`)

We want to build a fully standalone, single-file executable. This ensures that users do not need to manually install the .NET 8 Runtime on their computers to run your server.

**Command:**
Open your terminal, navigate to the `server/` directory, and run:
```powershell
dotnet publish -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true
```

**Output:**
The compiled file will be located at:
`server/bin/Release/net8.0-windows/win-x64/publish/NyxxPadServer.exe`

*(Note: We have already run this for you, and the latest version is currently sitting in your root `builds/` directory!)*

---

## 2. Building the Android Client (`.apk`)

For Android, we use Gradle to compile the app into an APK file. 

**Option A: Debug APK (Easiest)**
If you haven't set up a signing Keystore (which is required for the Google Play Store), you can just build a Debug APK. Anyone can still download and install this from GitHub.
Navigate to the `client/` directory and run:
```bash
./gradlew assembleDebug
```
*(On Windows Command Prompt, use `gradlew.bat assembleDebug`)*

**Output:**
The compiled file will be located at:
`client/app/build/outputs/apk/debug/app-debug.apk`

**Option B: Release APK (Highly Optimized)**
I have already pre-configured your `build.gradle.kts` to look for a keystore file named `release.keystore`. 

To generate this file for the first time:
1. Open the Android project in **Android Studio**.
2. From the top menu, select **Build > Generate Signed Bundle / APK...**
3. Choose **APK** and click Next.
4. Under "Key store path", click **Create new...**
5. Save the file exactly as `release.keystore` inside your `client/app/` folder.
6. Set a strong password for both the store and the key.
7. Set the Alias to `nyxxpad`.
8. Fill out at least one certificate field and click OK.
9. Open `client/local.properties` (create it if it doesn't exist) and add the following lines with your newly chosen password:
   ```properties
   KEYSTORE_PASSWORD=YourChosenPasswordHere
   KEYSTORE_ALIAS=nyxxpad
   ```

Once that file is created and the password is set in `local.properties`, you can easily build highly optimized release versions anytime:
```bash
./gradlew assembleRelease
```
*(On Windows Command Prompt, use `gradlew.bat assembleRelease`)*

**Output:**
The compiled file will be located at:
`client/app/build/outputs/apk/release/app-release.apk`

---

## 3. Uploading to GitHub

1. Go to your GitHub repository and click **Releases > Draft a new release**.
2. Create a new tag (e.g., `v1.0.0`).
3. Drag and drop both `NyxxPadServer.exe` and `app-debug.apk` into the "Attach binaries" box.
4. Publish the release!

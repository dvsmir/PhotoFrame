# Photo Frame
An Android app that sends photos to a Frameo digital photo frame over the local
network — no account, no cloud, no subscription limits.

Based on the: [`yasoob/frameo-client`](https://github.com/yasoob/frameo-client) implementation.

## Build and install

### What you need

- **Android Studio** (it brings the JDK and the Android SDK). The build uses the JDK bundled
  with it, so point `JAVA_HOME` there. On Windows that is
  `%LOCALAPPDATA%\Programs\Android Studio\jbr`.
- In Android Studio's SDK Manager: **Android 17 (API 37)** and **Platform-Tools** (for `adb`).
  Open the project once in Android Studio, or create `local.properties` with
  `sdk.dir=<path to your Android SDK>`.
- A phone on **Android 12 or newer**, on the same Wi-Fi as the frame.

### Build

Windows (PowerShell); on macOS or Linux use `./gradlew` and `export JAVA_HOME=…`:

```powershell
$env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\Android Studio\jbr"
.\gradlew :app:assembleDebug      # app\build\outputs\apk\debug\app-debug.apk
.\gradlew :app:assembleRelease    # app\build\outputs\apk\release\app-release.apk
```

- **Debug** needs no setup. It installs as `dev.dsmirnov.photoframe.debug`, next to the
  release app, with its own pairing, and adds developer aids (see `AGENTS.md`).
- **Release** is the one to use day to day: smaller, and optimised with R8. It must be
  signed, so create a key once:

  ```powershell
  & "$env:JAVA_HOME\bin\keytool" -genkeypair -keystore "$env:USERPROFILE\.photoframe\release.jks" `
      -storetype PKCS12 -alias photoframe -keyalg RSA -keysize 4096 -validity 36500
  ```

  then put `keystore.properties` in the repository root:

  ```properties
  storeFile=C:/Users/<you>/.photoframe/release.jks
  storePassword=<password>
  keyAlias=photoframe
  keyPassword=<password>
  ```

  Both files are git-ignored. **Back up the key.** Every update must be signed with the same
  key; with a different one Android refuses the update, and the only way out is to uninstall,
  which deletes the pairing. Without `keystore.properties` the release APK is still built, but
  unsigned, and a phone will not install it.

### Install on a phone

**Over USB** (recommended):

1. On the phone: *Settings → About phone*, tap *Build number* seven times, then *Settings →
   System → Developer options → USB debugging* on.
2. Connect the phone and accept the *Allow USB debugging?* prompt.
3. Install (`-r` updates an existing install and keeps its pairing):

   ```powershell
   adb install -r app\build\outputs\apk\release\app-release.apk
   ```

   With more than one phone connected, pick one: `adb devices`, then `adb -s <serial> install -r …`.

**Without a computer connection:** copy `app-release.apk` to the phone (cloud drive, cable,
chat to yourself), open it, and allow *Install unknown apps* for the app you opened it from
when Android asks.

### First run

1. On the frame: open the menu, choose **Add friend**, and leave the code on screen.
2. On the phone: open **Photo Frame** → *Connect your frame*. It finds the frame on the
   Wi-Fi; enter the friend code and the name the frame should show for you.
3. Send with *Add photos*, or share photos to *Photo Frame* from any gallery app.
4. To see and manage what is on the frame, open *On the frame* → *Request access*, then tap
   **Allow** on the frame.


## v1 specs

| # | Decision |
|---|---|
| D1 | LAN only — phone and frame on the same Wi-Fi |
| D2 | v1 = send photos + a gallery of the frame that also manages it: delete, hide/show, display now |
| D3 | Pure Kotlin protocol port, BouncyCastle crypto, no NDK/JNI/Go |
| D4 | Entry points: Android share sheet + in-app photo picker |
| D5 | Photos downscaled to the frame's panel resolution, WebP q≈85 |
| D6 | One frame (storage model still supports more) |
| D7 | Persistent send queue in a foreground service, with retries |
| D8 | No video in v1 |
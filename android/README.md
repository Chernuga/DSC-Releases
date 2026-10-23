# Android

**Download:** [`app-debug.apk`](app-debug.apk) in this folder — 12.4 MB — or from the [release](https://github.com/Chernuga/DSC-Releases/releases/download/v1.1/app-debug.apk).

## Installing

Open the APK on the device, or:

```bash
adb install -r app-debug.apk
```

Android will ask you to allow installation from your browser or file manager, since this does not come from the Play Store. That prompt is expected for a sideloaded app.

Requires Android 7 or newer.

## Updating

The APK keeps its signing key between builds, so a newer one installs over an older one without losing your conversations or settings.

## Verifying

`SHA256.txt` holds the checksum of this build:

```powershell
Get-FileHash .\app-debug.apk -Algorithm SHA256   # Windows
```
```bash
sha256sum app-debug.apk                         # Linux
```

## A note on signing

This is a debug-key build. It installs and updates cleanly over itself, but a build made on a different machine uses a different key — Android will refuse to install it over this one. Uninstall first if you switch machines, which also deletes the app's local conversations.

## Building

Needs JDK 17+ and the Android SDK:

```bash
echo "sdk.dir=/path/to/Android/Sdk" > local.properties   # gitignored
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```
# DSC-Releases

**DeepSeek Code** — the Android client. Source lives in [`android/`](android/).

The app drives a real DeepSeek web session in a WebView and exposes it over an
OpenAI-shaped HTTP API on `127.0.0.1:8080`, so a chat UI can talk to it as if it
were a normal provider. It also links a folder on the device as a per-conversation
workspace.

## Layout

```
android/            the Android project (open this in Android Studio)
releases/           notes and checksums for published builds
```

## Building

Requires the Android SDK and JDK 17 (Android Studio's bundled JBR works).

```bash
cd android
echo "sdk.dir=/path/to/Android/Sdk" > local.properties   # gitignored
./gradlew assembleDebug
```

The APK lands in `android/app/build/outputs/apk/debug/app-debug.apk`.

Install it with:

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

## Signing

Release builds are signed with the **debug keystore**, which is intentional for a
personal build: it installs and updates cleanly on a device that already has the
debug build, and needs no secret in the repo.

The consequence is worth knowing: the debug keystore differs per machine, so a
build made on another computer will **not** install over this one — Android
rejects it as a different signer. Uninstall first, or keep building from the same
machine.

The APK is signed with the v2 scheme only, which covers Android 7.0 and later.

## Releases

Published APKs are attached to GitHub Releases and listed in
[`releases/`](releases/) with their SHA-256 checksums.

Verify a download before installing:

```powershell
Get-FileHash .\app-debug.apk -Algorithm SHA256
```

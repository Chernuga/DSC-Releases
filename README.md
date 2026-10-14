# DSC-Releases

**DeepSeek Code** — the Android client. Source lives in [`android/`](android/).

The app drives a real DeepSeek web session in a WebView and exposes it over an
OpenAI-shaped HTTP API on `127.0.0.1:8080`, so a chat UI can talk to it as if it
were a normal provider. It also links a folder on the device as a per-conversation
workspace.

## Layout

```
android/                  the Android project (open this in Android Studio)
android/app-debug.apk     the current build, ready to install
releases/                 notes and checksums for published builds
```

## Install

Download [`android/app-debug.apk`](android/app-debug.apk), or from
[Releases](../../releases), then:

```bash
adb install -r app-debug.apk
```

Signed with the debug keystore (v2 scheme, Android 7+).

## Building

Requires the Android SDK and JDK 17 (Android Studio's bundled JBR works).

```bash
cd android
echo "sdk.dir=/path/to/Android/Sdk" > local.properties   # gitignored
./gradlew assembleDebug
```

The APK lands in `android/app/build/outputs/apk/debug/app-debug.apk`. Copy it over
`android/app-debug.apk` to refresh the published copy — see
[`android/PUBLISHING.md`](android/PUBLISHING.md).

## Signing

The debug keystore is used deliberately for a personal build: it installs and
updates cleanly over an existing debug build and needs no secret in the repo.

The consequence is worth knowing: the keystore differs per machine, so a build
made on another computer will **not** install over this one — Android rejects it
as a different signer. Uninstall first, or keep building from the same machine.

## Releases

Published APKs are attached to GitHub Releases and recorded in
[`releases/`](releases/) with their SHA-256 checksums. Verify before installing:

```powershell
Get-FileHash .\app-debug.apk -Algorithm SHA256
```

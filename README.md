# DeepSeek Code

A client that drives a real DeepSeek web session and exposes it over an OpenAI-shaped HTTP
API. One repository, three platforms, source and ready-to-run builds together.

## Download and run

| Platform | Folder | File |
|---|---|---|
| Windows 10/11 x64 | [`windows/`](windows/) | [DeepSeekCode-win-x64.zip](https://github.com/Chernuga/DSC-Releases/releases/download/win-v1.1/DeepSeekCode-win-x64.zip) |
| Android 7+ | [`android/`](android/) | [`app-debug.apk`](android/app-debug.apk) (12.4 MB) |
| Linux x64 | [`linux/`](linux/) | [DeepSeekCode-linux-x64.tar.gz](https://github.com/Chernuga/DSC-Releases/releases/download/linux-v1.1/DeepSeekCode-linux-x64.tar.gz) |

The Windows and Linux archives are attached to [Releases](../../releases) — GitHub rejects
files over 100 MB, so they cannot live in the tree. The APK is small enough to sit in its
folder directly.

Each folder's README covers installing and verifying that platform.

## What it does

DeepSeek's web chat is free but has no API. This drives a real logged-in session — a WebView
on Android, a `WebContentsView` on desktop — and answers HTTP requests on `127.0.0.1`:

```
POST /coding/chat/completions        SSE stream
POST /coding/chat/completions/full   one JSON body
POST /chat/completions/last          the last reply, read back from the page
GET  /status                         conversation URL, health
POST /open                           move the page to a conversation
POST /toggles                        DeepThink / Search
```

The interface is [chernuga.github.io/ZhiPu-Integration](https://chernuga.github.io/ZhiPu-Integration/),
loaded by all three clients — so UI updates reach them without a new build.

## Building from source

**Android** — JDK 17+ and the Android SDK:

```bash
cd android
echo "sdk.dir=/path/to/Android/Sdk" > local.properties   # gitignored
./gradlew assembleDebug
```

**Windows / Linux** — Node 20+:

```bash
cd windows      # or linux
npm install
npx electron-builder --win dir     # --linux dir on Linux
```

## First run

Each client asks you to sign in to DeepSeek on its own tab. That session is what answers
requests; nothing is sent anywhere else.

## A note on access

This automates a real DeepSeek session, which their terms describe as automated access.
Driving a browser is closer to normal use than reimplementing their protocol, but neither is
sanctioned. Use it on your own account, at a human pace.

The direct-protocol approach — solving their proof-of-work and reusing a session token
outside the browser — is **not** part of this build. It was removed after an account
suspension; notes are in `windows/docs/webai2api-direct.md`.

## License

Personal project, no licence granted. Read it, learn from it; don't redistribute it as your
own.
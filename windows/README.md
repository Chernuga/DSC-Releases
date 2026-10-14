# DeepSeek Code — Windows

The desktop client. Feature-identical to the Android app: the same website, the
same local API, the same workspace behaviour.

## What it is

The app drives a real DeepSeek web session in an Electron `WebContentsView` and
exposes it over an OpenAI-shaped HTTP API on `127.0.0.1:8099`. The published
client UI is loaded alongside it and talks to that API as though it were any
other provider — no Cloudflare Worker, no network hop, nothing leaving the
machine.

```
DeepSeek view   the real chat.deepseek.com page; owns the session and generates
Web UI view     the published client UI, pointed at the loopback API
Controls        status, log, and the API toggle
```

## Running

```powershell
npm install
npm start
```

## Building

```powershell
npm run dist
```

Output lands in `dist\win-unpacked\`, with `DeepSeek Code.exe` at its root. The
folder is portable: copy it anywhere, and `DeepSeekData` is created beside the
executable holding the login, cookies, conversations and the remembered
conversation URL. Nothing is written to `%APPDATA%`.

A `dir` target is used rather than a single-file portable launcher, because a
portable build unpacks itself to `%TEMP%` and runs from there — the account and
conversations would then live in Temp and be cleared on reboot.

## Feature parity with Android

| Feature | How it works here |
|---|---|
| Fetch last reply | `/last` reads the raw text the hook captured from the wire — the same string the site's own copy button produces |
| Truncation warning | The hook detects the page's Continue control; `/full` reports `x_truncated` and `finish_reason: "length"` |
| No fixed timeout | A watchdog resets on every delta, so only real silence fails a request |
| Conversation resume | The last conversation URL is remembered and reopened |
| Link folder | A native folder picker; the same `AndroidWorkspace` bridge the page already expects |
| Workspace tree, Open | The same bridge methods, so the page renders them identically |
| Immediate saving | The website does this; nothing is platform-specific |

### Why the bridge is named AndroidWorkspace

The website feature-detects that name before showing Link Folder and the fetch
button. Exposing the same interface on Windows means the page needs no knowledge
of which platform it runs on, and every feature works without a single change to
the site.

The implementation differs underneath. Android uses the Storage Access Framework
and works through `content://` URIs; a desktop app runs as the user and reads the
filesystem directly, so a linked folder is simply a path. Every path is resolved
against the linked root and rejected if it escapes it.

## Signing

Unsigned. Windows SmartScreen will warn on first run — choose "More info" then
"Run anyway". Code signing needs a certificate, which this project does not
carry.

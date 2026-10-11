# DeepSeek Standalone

An Android app that exposes your logged-in DeepSeek **web** session as a local
HTTP API, and hosts the client UI to drive it. No Cloudflare Worker, no tunnel:
everything runs in this one app.

## Why this exists

The tunnelled build published the bridge through a Worker so other devices could
reach it. This build keeps everything on the device, which means:

* no network hop and no third-party relay for your session
* nothing to configure beyond the API toggle
* the client page and the API are in the same process

The trade-off: only this device can use the API. For access from elsewhere, use
the tunnelled build.

## The three pages

Swipe left/right, or tap a title, to switch:

| Page | What it is |
| :--- | :--- |
| **DeepSeek** | the real chat.deepseek.com page. This is the session being driven. |
| **Web UI** | the client page from GitHub Pages, pre-configured to call this device. |
| **Controls** | API on/off, server state, error log, reload, diagnostics. |

The pages are separate surfaces rather than one combined screen: the DeepSeek
page should not be covered by controls, and the client page needs full width.

## How the client page is wired up

The website is **not modified**. It already reads its provider list from
`localStorage` under `deepseek-ui-settings`, so the app writes a
`local-standalone` provider into that key before the page's own script runs.
To the site this is indistinguishable from configuring it by hand in Settings.

Seeded provider:

```json
{
  "id": "local-standalone",
  "label": "This device (standalone)",
  "baseUrl": "http://127.0.0.1:8080",
  "route": "coding",
  "pathSuffix": "/chat/completions/full",
  "responseMode": "full"
}
```

`responseMode: "full"` because a streamed reply would arrive as hundreds of
frames in the same process that is rendering them.

## Endpoints

Identical to the tunnelled build, minus the Worker:

| Method | Path | Streaming |
| :--- | :--- | :--- |
| POST | `/coding/chat/completions` | yes |
| POST | `/coding/chat/completions/full` | no |
| POST | `/general/chat/completions[/full]` | both |
| POST | `/chat`, `/chat/full` | both |
| GET | `/status`, `/errors` | no |
| GET/POST | `/stop` | no |

CORS is enabled on the local server. The tunnelled build did not need it because
the Worker added those headers; here the GitHub page calls `127.0.0.1` directly,
which is cross-origin.

## Port conflict

Both apps bind `127.0.0.1:8080`. Only one can hold it at a time. If the local
server reports a bind failure, stop the other app first. The Controls page shows
the server state and explains this in the error log.

## Build

```
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```
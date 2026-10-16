# DeepSeek Code — Linux

The same client as the Windows and Android builds: a real DeepSeek web session
driven in the background, served over an OpenAI-shaped API on loopback, with the
client UI alongside it. No Cloudflare Worker, no network hop.

## What is here

`dist/linux-unpacked/` is a complete, runnable build:

```
deepseek-code          the executable
resources/app.asar     the application
locales/  *.so  *.pak  Electron runtime
```

Run it with:

```bash
./dist/linux-unpacked/deepseek-code
```

If it refuses to start with a sandbox error, that is Chrome's sandbox needing a
privileged helper. Either run it with `--no-sandbox`, or give the helper the
permissions it wants:

```bash
sudo chown root:root dist/linux-unpacked/chrome-sandbox
sudo chmod 4755 dist/linux-unpacked/chrome-sandbox
```

## Building

```bash
npm install
npm run dist          # unpacked build
```

The other targets need to run **on Linux**:

```bash
npm run dist:deb      # .deb
npx electron-builder --linux AppImage
```

They cannot be produced from Windows. Both need symbolic links, and a standard
Windows account cannot create them:

```
EPERM: operation not permitted, symlink
```

Enabling Developer Mode, or running elevated, would allow it — but a build made
that way is better produced on the platform it targets anyway.

## Where the data lives

The app keeps its data beside the executable in `DeepSeekData/`, so the folder can
be copied or moved and comes with you.

That is not always possible: an AppImage is mounted read-only, and an install under
`/opt` belongs to root. The app checks whether the folder is actually writable and
falls back to the per-user location — `$XDG_DATA_HOME/DeepSeek Code`, or
`~/.local/share/DeepSeek Code` — rather than failing later with a permission error.

It holds the DeepSeek login, conversations, settings and the remembered
conversation URL. Nothing is sent anywhere except to DeepSeek itself.

## Differences from the Windows build

None that matter. The same source, with one addition: the data-directory logic
understands `APPIMAGE` and checks writability, because on Linux the folder beside
the executable is not always usable.

# Linux

**Download:** [DeepSeekCode-linux-x64.tar.gz](https://github.com/Chernuga/DSC-Releases/releases/download/linux-v1.1/DeepSeekCode-linux-x64.tar.gz)

Attached to the release rather than stored here.

## Installing

```bash
tar -xzf DeepSeekCode-linux-x64.tar.gz
cd DeepSeekCode-linux-x64
./DeepSeek\ Code
```

No installation. If the binary will not start, make it executable first:

```bash
chmod +x "DeepSeek Code"
```

## Notes

Verified to run on a standard x64 desktop. It has **not** been tested from a live USB session, where the filesystem runs from slow flash storage — if it feels sluggish there, copy the folder to a local disk first.

The application keeps its data — the DeepSeek session, linked folders, conversations — in a `DeepSeekData` folder beside the executable, so it stays portable with the app.

## Building

```bash
npm install
npx electron-builder --linux dir
```

The source is in this folder. `main.js` differs from the Windows one only in how it picks
its data directory, for AppImage and read-only locations.
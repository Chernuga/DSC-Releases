# Windows

**Download:** [DeepSeekCode-win-x64.zip](https://github.com/Chernuga/DSC-Releases/releases/download/win-v1.1/DeepSeekCode-win-x64.zip) — 150.9 MB

The archive is attached to the release rather than stored here, because GitHub rejects files over 100 MB.

## Installing

1. Extract the zip. You get one folder, `DeepSeekCode-win-x64\`.
2. Run `DeepSeek Code.exe` from inside that folder.

No installation, no administrator rights.

**Cannot find the exe?** It is inside the extracted folder. If Windows opened the zip instead of extracting it, right-click the zip and choose *Extract All* first.

Windows may warn that the application is unsigned — choose **More info → Run anyway**.

## Verifying

`SHA256.txt` in this folder holds the checksum of the current build:

```powershell
Get-FileHash .\DeepSeekCode-win-x64.zip -Algorithm SHA256
```

A mismatch means the download was corrupted or altered; download it again.
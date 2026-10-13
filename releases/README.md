# Releases

APKs are attached to [GitHub Releases](../../releases). Hashes are listed here so
a download can be verified before installing.

## Verify

```powershell
Get-FileHash .\app-debug.apk -Algorithm SHA256
```

The value must match the entry for the version you downloaded.

## v1.1 — 2026-10-13

| | |
|---|---|
| File | `app-debug.apk` |
| Size | 12.2 MB |
| SHA-256 | `d7b4d5ffe1055c2cbd7a17f40208047f615e4834cb5b1d1e9191c6690997d204` |
| Notes | The linked folder survives a restart; the app reopens the last conversation. |

## v1.0 — 2026-10-13

| | |
|---|---|
| File | `app-debug.apk` |
| Size | 12.7 MB |
| SHA-256 | `078090f5736cf6975db2d89b16d262ec2bab63c4eb0e361fe8bc80068c3b3948` |
| Notes | First published build. GLM defaults to the free model; fetch-last reads the page's copy control; immediate non-destructive saving. |

## Signing

Release builds are signed with the **debug keystore** (v2 scheme, Android 7+).
That installs straight over an existing debug build, but the keystore differs per
machine, so a build made elsewhere will not install over this one — uninstall
first.

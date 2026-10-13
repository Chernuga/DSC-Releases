# Releases

APKs are attached to [GitHub Releases](../../releases). Hashes are listed here
so a download can be verified before installing.

## Verify

``powershell
Get-FileHash .\app-debug.apk -Algorithm SHA256
``

The value must match the entry below.

## 2026-10-13

| | |
|---|---|
| File | `app-debug.apk` |
| Size | 12.2 MB |
| SHA-256 | `078090f5736cf6975db2d89b16d262ec2bab63c4eb0e361fe8bc80068c3b3948` |
| Notes | GLM defaults to the free model; fetch-last reads the page's copy control; reopens the last conversation; immediate non-destructive saving. |

Signed with the debug keystore (v2 scheme, Android 7+). A build from a different
machine will not install over this one — uninstall first.

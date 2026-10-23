# Downloads

Ready-to-use builds of **DeepSeek Code**, one folder per platform. Source lives in
[DSC-Source](https://github.com/Chernuga/DSC-Source) — this repository holds files to
download and run, nothing else.

| Folder | Platform | What is here |
|---|---|---|
| [`windows/`](windows/) | Windows 10/11 x64 | Link to the release archive + checksum |
| [`android/`](android/) | Android 7+ | The APK itself + checksum |
| [`linux/`](linux/) | Linux x64 | Link to the release archive + checksum |

The Windows archive is 151 MB, and GitHub rejects files over 100 MB, so it is attached to
[Releases](../../releases) and each folder links to it. The APK fits, so it is stored here
directly.

## The simplest way

Open **[Releases](../../releases)** and download the archive for your platform.

- **Windows** — extract the zip; run `DeepSeek Code.exe` from inside the folder.
- **Android** — install `app-debug.apk`; allow installation from your browser when asked.
- **Linux** — extract the tarball and run `./DeepSeek Code`.

Each release page lists the SHA-256 of its archive, and the same value sits in the matching
folder here.

## First run

Every client asks you to sign in to DeepSeek on its own tab — that session is what answers
requests. Nothing is sent anywhere else.

The interface they load is
[chernuga.github.io/ZhiPu-Integration](https://chernuga.github.io/ZhiPu-Integration/), so UI
updates reach all three clients without downloading a new build.

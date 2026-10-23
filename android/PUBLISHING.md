# Publishing a build

The APK kept at [`app-debug.apk`](app-debug.apk) is the current build, so the app
can be downloaded straight from the repository as well as from
[Releases](../../releases).

## Refresh it

After changing the source, rebuild and copy the result over the tracked copy:

```powershell
cd android
./gradlew assembleDebug
Copy-Item app/build/outputs/apk/debug/app-debug.apk app-debug.apk -Force
```

Then record the new hash in [`../releases/README.md`](../releases/README.md) and
commit both.

## Why the copy exists

`android/app/build/` is ignored, because build output does not belong in a
repository. The one file at `app-debug.apk` is the deliberate exception: it is the
published artifact, and it is what someone browsing the repository will find
first.

## Keeping it honest

A committed binary can drift from the source it was built from. Two things guard
against that:

- the release notes carry the SHA-256 of the published build, so a download can be
  checked against the source commit it claims to be;
- the build is scripted above rather than done by hand, so refreshing it is one
  command.

If the two ever disagree, the hash in `releases/README.md` is the record of what
was actually published.

## Signing

Builds are signed with the debug keystore, v2 scheme, Android 7+. That installs
straight over an existing debug build, but the keystore differs per machine, so a
build made elsewhere will not install over this one — uninstall first.

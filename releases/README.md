# Releases: the built app

| File | What it is | Status |
|---|---|---|
| [`TrackLogger-1.3.apk`](TrackLogger-1.3.apk) | The recorder plus the lap timer: running lap, last lap against best, the list of laps, and a `LAP` row in the log for each pass of the line. The line is built in and is for Sepang only | Passed 140 automated checks on a PC. **The lap screen has not been run on a phone yet.** If you try it, please say what it did |
| [`TrackLogger-1.2.apk`](TrackLogger-1.2.apk) | The recorder alone, without lap times | Ran at Sepang on 13 Oct 2026: a Galaxy S25 on Android 16, 44 minutes, no data lost. It wrote the log in [`../data/sepang-2026-10-13/`](../data/sepang-2026-10-13) |
| [`TrackLogger-1.2-source.zip`](TrackLogger-1.2-source.zip) | The source that built 1.2, as it was then (it also holds the older copy of `analyze.py` from that day: the current analysis is in [`../analysis`](../analysis)) | A snapshot, for the record |
| [`SHA256SUMS`](SHA256SUMS) | Checksums of the three files above | `sha256sum -c SHA256SUMS` |

Which one? If you want the lap times, take 1.3. If you want only the recording, with the code that has actually run on a phone, take 1.2. 1.3 changes the recording path in one place: a call after each GPS fix, wrapped so that a fault in the lap timer cannot stop the recording
(`RecorderService` and `Exporter` are the same in both). To install and use either, follow [`../android/README.md`](../android/README.md) section 1.

## What I checked about these files

- **They match the source.** I rebuilt both APKs from the source in this repository (1.3 from [`../android`](../android), 1.2 from the zip) with [`../android/build.sh`](../android/build.sh). The code (`classes.dex`), the manifest, the resources and the icon came out byte for byte the same as in the files here.
  Only the signature differs, because each build makes its own key. So what is in these APKs is what you can read.
- **The two APKs share a signing key** (and 1.3 has the higher version number), so Android should accept 1.3 as an update of 1.2 and keep the recordings; I have not tried that on a phone. The key is private and is **not** in this repository. Its certificate fingerprint:

  ```
  SHA-256  44:19:D2:48:BF:B4:4D:1B:CE:4D:66:41:D9:18:C3:C0:24:76:95:F7:F1:34:5C:4E:BC:07:76:8A:FA:7D:F7:F7
  ```

  Print it from an APK with `keytool -printcert -jarfile TrackLogger-1.3.apk` (any JDK). A later release signed with the same key will show the same fingerprint; a different fingerprint means a different build chain (for instance one you made yourself), which Android will not install over this app until you uninstall it first. Recordings already in Downloads survive an uninstall.
- **The key is a throwaway.** It was generated on the build machine for sideloading (there is no Play Store listing, so there is no store signature). It is a convenience for installing and updating, not proof of who wrote the app; the proof is the source and the rebuild above.

## What I did not check

- Other phones than one Galaxy S25 on Android 16, and other makers' battery managers.
- 1.3 on any phone at all (the PC checks use fakes of the Android classes).
- Installing through any route other than the one the Android README describes.

## Making the next release

When the code changes, bump `versionCode` and `versionName` in `android/AndroidManifest.xml`, the matching `--version-code` and `--version-name` in `android/build.sh`, and the version in the log header line in `Recorder.java`; build; put the APK here with its checksum in `SHA256SUMS`; and say what changed in the pull request.
Whoever signs a release should keep the key safe and out of Git; I'd suggest the repository owner decides whose key signs the official APKs once other people are contributing.

# Releases: the built app

| File | What it is | Status |
|---|---|---|
| [`TrackLogger-1.5.apk`](TrackLogger-1.5.apk) | **Stages without parking.** Everything 1.4 does, and two new ways to make a stage on a road with nowhere safe to park: **drive it first with an ordinary recording and put a Start pin and a Finish pin on the drive afterwards** (the phone draws the line the car took, checks the pair against nine rules and says in words if it cannot make a stage), or **plan it from two map coordinates** pasted from Google Maps and make the stage from a drive later (*Make the stage from a recording*). After that it is an ordinary stage: arm it and the phone starts and stops the recording at the lines. Planned stages do **not** yet arm themselves for the first drive: that is 1.6 | Passed 435 automated checks on a PC (422, and 13 that replay the real Sepang log's GPS fixes through the lap timer, the stage code and the pin code); the pin method has a second implementation that reproduces the pin test files. **Not run on a phone and not driven on a road.** If you try it, start parked, and please say what it did |
| [`TrackLogger-1.4.apk`](TrackLogger-1.4.apk) | **Stages.** Everything 1.3 does, and you can mark the start and the finish of a road by driving it once, arm the phone, and let it start and stop the recording at those two lines by itself. Every drive is kept as a run, and runs of the same stage are compared afterwards (a graph of where the time went, section by section). Made for open roads: **no time, speed or difference is shown while the car moves**; there is one beep at the start line and two at the finish. Stages live inside the app, with buttons to back them up and to restore them | Passed 324 automated checks on a PC (315, and 9 that replay the real Sepang log's GPS fixes through the lap timer and the stage code), as it was then; a second implementation of the method reproduces every stage test file. **Not run on a phone and not driven on a road.** If you try it, start parked, and please say what it did |
| [`TrackLogger-1.3.apk`](TrackLogger-1.3.apk) | The recorder plus the lap timer: running lap, last lap against best, the list of laps, and a `LAP` row in the log for each pass of the line. The line is built in and is for Sepang only | Passed 140 automated checks on a PC (136, and 4 that replay the real log's GPS fixes through the timer). **The lap screen has not been run on a phone yet.** If you try it, please say what it did |
| [`TrackLogger-1.2.apk`](TrackLogger-1.2.apk) | The recorder alone, without lap times | Ran at Sepang on 13 Oct 2026: a Galaxy S25 on Android 16, 44 minutes, no data lost. It wrote the log in [`../data/sepang-2026-10-13/`](../data/sepang-2026-10-13) |
| [`TrackLogger-1.2-source.zip`](TrackLogger-1.2-source.zip) | The source that built 1.2, as it was then (it also holds the older copy of `analyze.py` from that day: the current analysis is in [`../analysis`](../analysis)) | A snapshot, for the record |
| [`SHA256SUMS`](SHA256SUMS) | Checksums of the five files above | `sha256sum -c SHA256SUMS` |

Which one? For stages, take 1.5; it contains the stages of 1.4 and the lap timer of 1.3 as well. If you want only the recording, with the code that has actually run on a phone, take 1.2.
The recording path itself changed in two places since 1.2, each wrapped so that a fault in the new code cannot stop the recording: 1.3 calls the lap timer after each GPS fix, and 1.4 also hands every fix to the stage code, ticks it once a second and tells it when the recording stops. 1.5 changes the recording path in one place only, the version in the header line of the CSV (`# TrackLogger 1.5`); its new code (the pins, the plans, the coordinate reader, the pin screen) runs only on the Stages screens, parked, and its buttons are off while a recording runs.
1.4 also never reuses a file name: two recordings started within one second become `session_YYYYMMDD_HHMMSS.csv` and `session_YYYYMMDD_HHMMSSb.csv`. What `RecorderService` does around the recording is new in 1.4 (marking a stage; waiting at a start line with the GPS alone); `Exporter` is the same in all three.
To install and use any of them, follow [`../android/README.md`](../android/README.md) section 1.

**Stages live inside the app and are deleted if you uninstall it** (an update keeps them; recordings in Downloads survive both). Press *Back up stages* first if you have any; the backup is a zip in `Downloads/TrackLogger`.

## What I checked about these files

- **They match the source.** I rebuilt the APKs with [`../android/build.sh`](../android/build.sh) from a clean copy of the source (1.4 from a fresh clone of this repository, 1.3 from the repository as it was when 1.3 was added, 1.2 from the zip). The code (`classes.dex`), the manifest, the resources and the icon came out byte for byte the same as in the files here.
  Only the signature differs, because a build without the key makes its own key. So what is in these APKs is what you can read.
- **The three APKs share a signing key** (version codes 3, 4 and 5), so Android should accept each as an update of the one before and keep the app's data; I have not tried that on a phone. The key is private and is **not** in this repository. Its certificate fingerprint:

  ```
  SHA-256  44:19:D2:48:BF:B4:4D:1B:CE:4D:66:41:D9:18:C3:C0:24:76:95:F7:F1:34:5C:4E:BC:07:76:8A:FA:7D:F7:F7
  ```

  Print it from an APK with `keytool -printcert -jarfile TrackLogger-1.4.apk` (any JDK). A later release signed with the same key will show the same fingerprint; a different fingerprint means a different build chain (for instance one you made yourself), which Android will not install over this app until you uninstall it first. Recordings already in Downloads survive an uninstall; **stages do not**, so back them up before you switch.
- **The key is a throwaway.** It was generated on the build machine for sideloading (there is no Play Store listing, so there is no store signature). It is a convenience for installing and updating, not proof of who wrote the app; the proof is the source and the rebuild above.

## What I did not check

- Other phones than one Galaxy S25 on Android 16, and other makers' battery managers.
- 1.3 and 1.4 on any phone at all (the PC checks use fakes of the Android classes). For 1.4 that includes everything that has to be seen or heard: the look and size of the stage screens, the beeps, how Android treats the armed service when the screen has been off for an hour, and the GPS under trees.
- A stage driven on a real road and timed by something else (a stopwatch, a second device). The first one will be the first test of the whole chain.
- Updating over 1.2 or 1.3 on a phone, or restoring a stage backup on one; installing through any route other than the one the Android README describes.

What the PC checks cover, and what they cannot, is in [`../docs/validation.md`](../docs/validation.md) (the app: section 4; stages: section 5).

## Making the next release

When the code changes, bump `versionCode` and `versionName` in `android/AndroidManifest.xml`, the matching `--version-code` and `--version-name` in `android/build.sh`, and the version in the log header line in `Recorder.java`; build; put the APK here with its checksum in `SHA256SUMS`; and say what changed in the pull request.
Whoever signs a release should keep the key safe and out of Git; I'd suggest the repository owner decides whose key signs the official APKs once other people are contributing. A change of key means an uninstall, and an uninstall deletes the stages: say so in the release notes when it happens.

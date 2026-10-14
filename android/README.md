# Track Logger for Android

The phone app. It does three jobs:

1. **Records** the raw signals of a drive to one CSV file: accelerometer and gyroscope at about 100 Hz, GPS at about 1 Hz, satellite counts and
   a clock check. Nothing is processed on the phone. The file format is described in [`../docs/log-format.md`](../docs/log-format.md); the
   Python analysis in [`../analysis`](../analysis) turns it into lap times, an ultimate lap and the power-law report.
2. **Times laps** (version 1.3) from GPS at one built-in line across the main straight at Sepang, and shows the running lap, the last lap against
   the best, and the list of laps. Each crossing is also written to the CSV as a `LAP` event.
3. **Times stages** (version 1.4, and 1.5 for the new ways of making one): point-to-point roads with a start and a finish. You make a stage by parking at both ends (1.4), by driving first and putting a Start pin and a Finish pin on the recorded drive, or by planning it from two map coordinates and making it from a drive later (1.5). With a stage armed, the phone records from the approach to the start line until just after the finish line, keeps every drive as a run,
   and compares runs afterwards. **It is built for open roads: while the car moves the screen shows words and no numbers**, and the only feedback is a beep. Guide: [`../docs/stages.md`](../docs/stages.md).

It does not yet show any of the coaching ideas from the report. That would be a later build (see the top-level README).

**Status.** Version 1.2 (the recorder) ran on a real phone at Sepang on 13 Oct 2026: a Samsung Galaxy S25 on Android 16, 44 minutes, no data loss.
Version 1.3 adds the lap timer, 1.4 adds Stages and 1.5 adds stages without parking. Version 1.5 passes 435 automated checks on a PC against a simulated Android (422 of them need nothing else; the rest replay the real log's GPS fixes through the lap timer, the stage code and the pin code, reproducing the 15 lap times of an independent calculation, and score stages cut out of the real log),
and the stage method has a second implementation, in Python, written from the method page alone, that reproduces the test files byte for byte; the pin method of 1.5 has one too. But **neither the 1.3 lap screen nor the 1.4 and 1.5 stage screens have been run on a phone**, and no stage has been driven on a road. If you try them, please open an
issue and say what the screen did.

Needs Android 10 or later, a GPS and an accelerometer; a gyro is strongly recommended. The app has no internet permission.

| Path | What it is |
|---|---|
| `src/app/tracklog/Recorder.java` | sensors, GPS, calibration, CSV writing |
| `src/app/tracklog/RecorderService.java` | foreground service that keeps recording with the screen off |
| `src/app/tracklog/Exporter.java` | copies the finished file to Downloads |
| `src/app/tracklog/LapTimer.java` | line crossing, lap times (the Sepang line is a constant at the top of this file) |
| `src/app/tracklog/MainActivity.java` | the recorder screen (it shows words instead of numbers while a stage is armed or recording) |
| `src/app/tracklog/StagesActivity.java`, `StageText.java` | the Stages screens: list, a stage and its runs, the comparison with its graph, marking, backup and restore; since 1.5 also the ways of adding a stage, the planning page, a planned stage, the list of drives and the pin page; and every word and number they show, as plain functions so they can be tested |
| `src/app/tracklog/Gate.java`, `Geo.java`, `Pt.java`, `Route.java`, `StageRun.java`, `Scorer.java`, `Compare.java` | the stage method in plain Java ([`../docs/stage-algorithm.md`](../docs/stage-algorithm.md)): a timing line, the flat map frame, a placed fix, the route, following a run, scoring a whole recording, comparing two runs |
| `src/app/tracklog/StageMarker.java`, `MarkSession.java`, `StageBuilder.java` | marking a stage by parking at both ends and building it from the drive |
| `src/app/tracklog/Track.java`, `PinBuilder.java`, `Coords.java`, `Plan.java`, `TrackView.java` | (1.5) stages from pins: the cleaned track of a recording, putting two pins on it and checking the pair (`docs/stage-algorithm.md` section 11), reading a coordinate typed or pasted from a map, the planned stage (two places and a name), and the picture of the drive with its pins |
| `src/app/tracklog/ArmedWatcher.java`, `StageSession.java`, `StageWatcher.java`, `StageLive.java`, `ResultGuard.java`, `Beeper.java` | the armed phone: waiting for the start line on the GPS alone, the live run, what the screens may show (`ResultGuard`: numbers only once the car has stopped), the beeps; `RecorderService.java` gained the armed and marking modes |
| `src/app/tracklog/Stage.java`, `StageFiles.java`, `StageStore.java`, `StageBundle.java`, `StageExporter.java`, `StageRescore.java`, `LogReader.java`, `RunResult.java` | the files ([`../docs/stage-format.md`](../docs/stage-format.md)): reading and writing stages and runs, the backup zip, scoring recordings made earlier |
| `tests/` | the harness (435 checks with the real log replayed, 422 without) and the fakes of the Android classes it runs against, including the few widgets the screens use (needs only a JDK): `tests/run.sh` |
| `build.sh`, `fetch_tools.sh` | build without Gradle or Android Studio (section 6) |
| `../releases/TrackLogger-1.5.apk` | the built app, with the three earlier APKs (1.4, 1.3, 1.2) and checksums |


## 1. Install the app

1. Get `TrackLogger-1.5.apk` onto the phone (open it in the `releases/` folder of this repository on GitHub and use *Download raw file*, or Drive, email to yourself, USB...).
   **Updating from 1.2, 1.3 or 1.4:** install it over the old one (same signing key, higher version number); the permissions and the
   recordings stay. Finish and save any running session first: installing stops the app.
   **Stages live inside the app and Android deletes them when the app is uninstalled** (cloud backup is switched off on purpose). Use *Back up stages* on the Stages screen after you mark one; the backup is a zip in `Downloads/TrackLogger`.
   The APK in this repository is signed with the key I generated when I built 1.2. That key is private and is not in the repository, so an APK you build
   yourself carries a different signature: Android will refuse to install it over this one until you uninstall the old app (recordings in Downloads survive).
2. Open it. Android asks to allow "Install unknown apps" for the app you opened it from. Allow that.
3. Play Protect may say "unrecognised developer". Tap *Install anyway*. The app is signed with a key made on the build
   machine, not by Google Play, so this warning is expected. (Google's developer-verification rules went live on
   30 Sep 2026 for Indonesia, Singapore and Thailand, and some reports add Brazil. Google says the rest of the world
   follows during 2027. Malaysia was not on the list when I checked on 12 Oct 2026. If your phone refuses the install
   anyway, `adb install TrackLogger-1.5.apk` from a computer still works, and Google's "advanced flow" lets you
   continue after a one-time setup.)
   On Samsung phones, *Auto Blocker* (Settings > Security and privacy) stops all sideloaded installs: switch it off first.
4. Open **Track Logger**. Tap START. Allow **Precise** location (not "Approximate") and notifications, then tap START again.
   If you have refused twice, Android stops asking and the app opens its Settings page instead: Permissions >
   Location > Allow, and turn on *Use precise location*.
5. Tap *Battery: allow background* and set the app to **Unrestricted** (Samsung: Settings > Apps > Track Logger >
   Battery > Unrestricted). On Xiaomi, Oppo, Vivo, Honor and OnePlus also switch on *Autostart* for the app and lock it
   in the recent-apps screen. Those phones may kill an app the moment it is swiped out of recents.
   **Never swipe the app away while recording.**

The app has no internet permission: the data stays on the phone unless you share the file yourself.

## 2. What it records

One CSV per session in `Downloads/TrackLogger/session_YYYYMMDD_HHMMSS.csv`, about 0.5 MB per minute:

| row | content |
|---|---|
| `A` | accelerometer, 3 axes in phone coordinates, about 100 Hz |
| `G` | gyroscope, 3 axes in phone coordinates, about 100 Hz |
| `L` | GPS fix: lat, lon, altitude, Doppler speed, bearing, accuracies, delay (about 1 Hz on most phones) |
| `S` | satellites visible / used, once a second |
| `E` | `START`, `CAL` (the stationary calibration and its result), `CALFAIL`, `LAP,n,seconds` (1.3: the lap timer saw the car cross its line; `n` = 0 starts the clock, `n` >= 1 is lap n and its time), `STAGE`, `STAGE_START`, `STAGE_FINISH,seconds`, `STAGE_DNF`, `STAGE_ABORT`, `MARK_START`, `MARK_FINISH` (1.4: see [`../docs/log-format.md`](../docs/log-format.md)), `STOP` |
| `K` | clock check: arrival time minus event time, every 30 s |

A recording is named `session_YYYYMMDD_HHMMSS.csv`; since 1.4 a name that exists is never reused (a second recording in the same second becomes `...b.csv`).
Times are microseconds since START on one clock. A missing GPS value is `-1` (altitude `-9999`). `L` rows are written when
the fix arrives, so they can sit slightly out of order and the first can have a small negative time: the analysis sorts them.

Nothing is processed on the phone. A copy is also kept in the app's private folder; *Re-export last session* copies the
newest one to Downloads again if anything went wrong, and *Share last export* sends it to Drive, email, WhatsApp...

## 3. Test it before the track (10 minutes)

1. Fix the phone in the car the way you will at the track. Any angle is fine, but it must not move.
2. Park somewhere with open sky. Press START (allow permissions, press START again). Keep the car still for the
   5 second countdown. The screen counts down STAY STILL, then shows REC. An idling engine is fine.
3. Still parked: the screen should show a GPS fix with a sensible accuracy (a few metres), IMU accel and gyro at
   roughly 100 Hz (or more), and a vertical g near 1.0. Then press the power button to lock the phone, wait one minute,
   and unlock it. The counters (A, G, GPS) must have kept growing, by about 6k, 6k and 60.
4. Drive a loop on public roads, legally, with a few accelerations, brakings and turns. A few stretches above 30 km/h
   help the analysis. Do not touch or watch the phone while driving. (A passenger can watch the horizontal g
   rise in turns and the speed follow the car.)
5. Park, press STOP & SAVE. The screen says SAVING... and then SAVED, with the file name.
6. Look for the file: Files app > Downloads > TrackLogger.
7. Optional but best: copy the CSV to a computer and run the analysis (see [`../docs/analysis-manual.md`](../docs/analysis-manual.md)). A good test shows
   `Forward axis ... R2` near 1.00.

If anything fails, open an issue and say what the screen says. Do not wait until you are at the track.

## 4. At the track

* **Mount** the phone rigidly: a proper dash/roll-bar/windscreen-base mount or Velcro to a flat panel. A suction cup on
  a flexing windscreen adds vibration to every channel. Keep it out of the airbag path and your line of sight, and ask
  the organiser what they allow in the cabin. A loose phone is a projectile at 1.4 g.
* **Heat**: a hot cabin plus screen plus GPS can overheat a phone. Keep it out of direct sun, remove thick cases,
  lower the brightness, start with a full battery, switch Battery Saver off.
* **Each session**: park, START, stay still for the 5 second countdown (engine idling is fine), drive, park, STOP & SAVE.
  Wait for SAVED before you press START again. Leave the app in front with the screen on: that is the most reliable setting.
* Never touch the phone while driving. One session per outing keeps the files short.
* **Lap times (1.3).** Under the speed the screen shows the running lap, the last lap against the best, and the best lap;
  the list of all laps of the session is under the buttons. Laps are timed from GPS at **one built-in line across the main
  straight** (about 300 m after the exit of T15, Sepang full GP layout). The first pass of the line only starts the clock;
  every later pass completes a lap, so the out-lap is not timed. A lap is the time between two passes of the *same* line,
  so the exact place of the line hardly matters (on the first real log, moving it 100 m along the straight changed a lap by a
  few hundredths of a second), but it is **not the official timing loop**: expect the times to be close to the transponder's
  and not identical. I have not compared them with a transponder. GPS gives one fix per second; the crossing is found on a
  curve through the fixes either side, which for the real log agrees with an independent calculation to the millisecond.
  Each crossing is also written to the CSV as an `E,...,LAP,n,seconds` row. The clock shows `-:--.-` until the first pass, and
  GPS must be locked. It is for Sepang only; for another circuit the line has to be changed in `LapTimer.java`.
* **Stages (1.4, 1.5).** Everything here is done parked. *Stages* on the main screen, then *Add a stage*, which offers three ways:
  *Drive first, pin afterwards* (1.5): record the road as usual, then pick that recording, touch the line to put the Start pin and the Finish pin where the stage starts and ends, fine-tune with the slider and the nudge buttons, and press *Save the stage*; the phone checks the pair and says in words if it cannot make a stage.
  *Plan from map coordinates* (1.5): paste the start and the finish from Google Maps (touch and hold on the road, copy the two numbers) and save the plan; after you have driven the road with an ordinary recording, open the plan and press *Make the stage from a recording*, and the pins start at your two places on that drive. Starting the first recording by itself is the next release (1.6).
  *Mark it by parking at both ends* (1.4): park at the start and keep still until *Mark start* turns on, press it, drive the road, park at the finish, press *Mark finish* (the app builds the stage from the drive and scores the drive as its first run).
  However the stage was made, to drive it again, open the stage and press *Arm this stage*, put the phone in its mount and drive; the phone starts recording as you move within 400 m of the start line, stops 5 s after the finish line, saves the run and arms itself again. **While a stage is armed or recording the screen shows words, not numbers, and shows no speed or clock on the main screen**; one beep at the start line,
  two at the finish line, a low tone if a run is given up (the beeps follow the media volume and can be switched off in the settings of the Stages screen; I have not heard them on a phone). Look at the times when you have stopped. Keep your phone's battery setting on Unrestricted as above, or Android may stop an armed phone with the screen off. Drive legally and safely: a time is never a reason to break a rule.
  The first time you press ARM or MARK the app shows a short road-rules text and waits for you to accept it.
* **If the app is killed or the screen says NOT SAVED**: reopen it and tap *Re-export last session*. The recording is
  flushed to the app's private folder every second, so at most the last second is lost.
* **Backup**: run an established lap-timing app (RaceChrono, TrackAddict, Harry's LapTimer...) at the same time. Two apps can
  use the same sensors, and one of them will have been tested on thousands of cars.

## 5. What it cannot do

* **Pedals are not measured.** "Brake" and "throttle" are the car's deceleration and acceleration. Coasting at 0 g is off
  both pedals; once the car is power-limited, 70 % and 100 % throttle look alike.
* **GPS is about 1 Hz.** The 100 Hz traces between fixes come from the inertial sensors. Position (the map, lap line)
  is good to a few metres.
* **Stage times are good to a few tenths of a second, not to the hundredth**, and worse the slower the car is going at the start and finish lines ([`../docs/stages.md`](../docs/stages.md)). Tight stacked hairpins at a line can fool the timer for a stage marked by parking (a stage made from pins is checked and refused if the road crosses a line again), and a finish by braking to a stop can be scored "did not finish"; the app does not warn about either yet.
  If Android kills the app in the middle of a run, the run is not saved (the recording up to that point is, in `Downloads/TrackLogger`, and *Score the recordings on this phone* finds the run in it).
* A phone is not a calibrated logger. Use it for shapes, timings and consistency between laps, not for absolute
  engineering numbers. Body roll and pitch leak a little into the lateral and longitudinal channels.
* If the car never stands still at the start of a session, the orientation is fitted from the drive instead (see [`../docs/analysis-manual.md`](../docs/analysis-manual.md)).
  That worked on the real log, but it follows a moving phone less precisely than a calibrated start. Always start
  parked and still for 10 s, and fix the phone so it cannot move.

## 6. Rebuilding from source

```
./fetch_tools.sh                       # downloads ~170 MB of build tools from GitHub
TOOLS="$PWD/tools" ./build.sh          # javac -> D8 -> aapt2 -> zipalign + sign
```

Output: `build/signed/*.apk`. `build.sh` makes a throwaway signing key `tracklogger.jks` (password `tracklog`) the first time.
An APK signed with a different key cannot be installed over this one: uninstall first (exports in Downloads survive).

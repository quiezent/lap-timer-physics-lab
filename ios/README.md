# iPhone recorder: not started

Nothing in this folder runs yet, and I have not written or run any iOS code. It is here so that whoever starts the iPhone version has a place to put it and a clear target.

## What it has to do

Record a drive to the **same CSV** the Android app writes, exactly as [`../docs/log-format.md`](../docs/log-format.md) describes it. Then every script in this repository works on iPhone sessions with no change.
It does not have to be a port of the Java. It has to write the rows right.

- Accelerometer and gyroscope at 100 Hz or more, GPS at 1 Hz or better, all stamped on one monotonic clock; a file written as it goes, so a crash costs a second, not a session; keeps recording with the screen off and the app in the background (that means the right background modes and a location session that stays alive).
- Raw, in the phone's own axes: no rotating, no calibration, no filtering in the recorder.
- Same posture as the Android app: start it, mount the phone, drive, stop. No interaction while the car is moving.

## The conversions I expect to matter (untested advice)

These come from reading Apple's documentation of CoreMotion and CoreLocation, not from running anything.

- **Accelerometer.** The raw accelerometer (`CMAccelerometerData.acceleration`) is in g, includes gravity, and has the **opposite sign to Android's**: a phone lying flat and face up reads -1 on z there, and +9.81 m/s² on z in the log.
  So the row is `A = -9.80665 × (value in g)`, per axis. Check this on the bench first: a flat phone must read +9.81 on z. `analysis/check_log.py` tests the size of the numbers and, on a drive, the sign (through the gyro and the GPS bearing).
- **Gyroscope.** rad/s in the phone's axes. Check the sign the same way.
- **GPS.** `CLLocation` gives latitude, longitude, altitude, speed (m/s, negative when invalid), course (degrees clockwise from north, negative when invalid), horizontal and speed accuracy. Missing values are written as `-1` (altitude `-9999`) as the format says.
- **Clocks.** A fix carries a wall-clock timestamp, and the motion samples carry the device's uptime clock. They have to be mapped onto one clock before the rows are written. How well that is done decides how well the analysis can fuse speed from GPS and accelerometer, so measure the offset and write the `K` rows (lateness of the batches) the format describes.

## Acceptance

1. `python analysis/check_log.py session.csv` shows no FAIL on a real drive (a WARN is fine if it explains itself).
2. A phone lying flat reads +9.81 on z in the file.
3. `python analysis/analyze.py session.csv` runs on any drive (it needs no circuit). Its report should show `Forward axis ... R2` close to 1.00 on a drive with some accelerating and braking, and a fused speed within about 1 m/s of the GPS fixes.
4. On a Sepang drive with enough clean loops, `python analysis/ultimate.py session.csv` and `python analysis/powerlaws.py session.csv` run too (they say plainly when there are too few loops). They are circuit-specific: other circuits are untested, apart from relocated and mirrored copies of the Sepang log.
5. On a drive that was also recorded by the Android app, the lap times agree.

Please share a first real file as early as you can, even if the app is rough: the checks were written from my guesses, and a real iPhone file is the best test of them.

## Layout suggestion

```
ios/
  README.md               this file
  TrackLogger/            the Xcode project
  tests/                  anything that can run without a phone (the CSV writer, the clock mapping)
```

Later, the lap timer (and the stage timer in [`../docs/roadmap.md`](../docs/roadmap.md)) should share test vectors with the Android one: the same GPS fixes in, the same lap times out.

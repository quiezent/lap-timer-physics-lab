# Log format (version 1)

This is the contract between a **recorder** (the Android app today; an iPhone app, a data-logger converter, or anything else tomorrow) and the **analysis**
in [`../analysis`](../analysis). Any program that writes a file in this format gets the whole analysis for free; the analysis never needs to know which phone it was.

The Android recorder is the reference implementation (`android/src/app/tracklog/Recorder.java`, whose comment block is the original of this page), and
[`../data/sepang-2026-10-13/`](../data/sepang-2026-10-13) is a real example. `analysis/analyze.py` (`load_session`) is the reader.
If this page and the reader disagree, the reader wins and this page has a bug: please open an issue.

## The file

- Plain text, UTF-8, one record per line, fields separated by commas, `.` as the decimal point, `\n` or `\r\n` line ends. Named `session_YYYYMMDD_HHMMSS.csv` (local time at START). Since 1.4 the Android recorder never reuses a name that already exists in its folder: a second recording started in the same second becomes `session_YYYYMMDD_HHMMSSb.csv` (then `c` ... `z`). The reader does not care about the name.
- Raw: **nothing is rotated, calibrated or filtered on the phone.** The analysis fits the phone's orientation and sensor errors itself, so a recorder should not try to help.
- About 0.5 MB per minute at 100 Hz sensors.
- The reader tolerates a truncated last line, blank lines and rows it does not know (it counts and ignores them), so a crash never costs the whole file.

## Header

Lines starting with `#`, before or among the data rows. They are for people and for the report; the reader only uses `device`, `android` and `accel` for display.
Suggested content (the Android recorder writes exactly this):

```
# TrackLogger 1.5 (raw phone-frame log; process offline with analyze.py)
# device,samsung SM-S931B
# android,16,sdk,36
# start_utc_ms,1791856598695
# start_elapsed_ns,142803493103892
# accel,lsm6dsv_0 Accelerometer Non-wakeup,STMicro,range=156.9064,res=0.0047856453,min_delay_us=5000
# gyro,lsm6dsv_0 Gyroscope Non-wakeup,STMicro,range=34.906036,res=0.0012217296,min_delay_us=5000
# rows: A,t_us,ax,ay,az | G,t_us,gx,gy,gz | L,t_us,lat,lon,alt,speed,bearing,hAcc,sAcc,bAcc,utc_ms,lag_ms | S,t_us,vis,used | E,t_us,NAME,... | K,t_us,A/G,lag_ms
```

An iPhone recorder would write its own name and OS in the first three lines (`# device,Apple iPhone 15`, `# ios,18.1`); only the first column needs to be unique.

## Rows

Every data row starts with a kind letter and then `t_us`: **integer microseconds since the start of the session, on one clock shared by every row type** (the "clock" paragraph below).

| Kind | Fields after the kind | Units | Rate |
|---|---|---|---|
| `A` accelerometer | `t_us, ax, ay, az` | m/s², phone axes, **gravity included** (the force the phone feels, not the acceleration of the car) | 50 to 100 Hz or more |
| `G` gyroscope | `t_us, gx, gy, gz` | rad/s, phone axes, right-hand rule | same as `A` |
| `L` GPS fix | `t_us, lat, lon, alt, speed, bearing, hAcc, sAcc, bAcc, utc_ms, lag_ms` | degrees (WGS84), degrees, metres, **m/s over ground**, degrees clockwise from true north, metres, m/s, degrees, ms since 1970 (UTC), ms | 1 Hz or better |
| `S` satellites | `t_us, visible, used` | counts | about 1 Hz (optional) |
| `E` event | `t_us, NAME[, values...]` | see below | rare |
| `K` clock check | `t_us, A or G, lag_ms` | ms | every 30 s (optional but recommended) |

Field counts matter to the reader: `A` and `G` rows have exactly 5 fields including the kind, `L` exactly 12, `S` 4, `K` 4, `E` at least 3. A row with another count is ignored.

**Missing values.** A value the phone did not provide is written `-1`, except altitude, which is `-9999`. (`speed`, `bearing` and the three accuracies are `-1` when absent.)

**GPS details.** `t_us` of an `L` row is when the fix was *made*; `lag_ms` is how late it arrived at the app, and the row is written on arrival, so `L` rows can be slightly out of
time order and the first can have a small negative `t_us`. The reader sorts every kind by `t_us` and drops repeated times. `speed` should be the Doppler speed where the platform
gives one. `hAcc` is the 68% horizontal radius, `sAcc` the 1-sigma speed accuracy, `bAcc` the 1-sigma bearing accuracy.

### Events (`E`)

| Name | Values | Meaning |
|---|---|---|
| `START` | none | `t_us` is 0. Written first |
| `CAL` | `mx, my, mz, noise, bx, by, bz, ok` | The result of a stationary calibration in the first seconds: mean accelerometer (m/s²), its noise, mean gyro (rad/s), and `ok = 1` if the car was judged still. Optional; the analysis falls back to fitting the orientation from the drive when it is missing or `ok = 0` |
| `CALFAIL` | none | Too little accelerometer data to calibrate |
| `LAP` | `n, seconds` | The recorder's own timer saw the car cross its line: lap `n` took `seconds`; `n = 0` is the first pass, which only starts the clock. Optional; the analysis finds laps itself from the GPS |
| `STAGE` | `stage_id` | (1.4) This recording is a run of the stage with this id; written once, with the first GPS fix after the armed phone started the recording |
| `STAGE_START` | none | (1.4) The car crossed the stage's start line; `t_us` is the interpolated moment of the crossing, not the time of a fix |
| `STAGE_FINISH` | `seconds` | (1.4) The car crossed the finish line; the stage took this long (finish time minus start time, to a millisecond) |
| `STAGE_DNF` | `reason` | (1.4) The run started and did not finish (the car stood still for 20 minutes, the run took far too long, or the recording was stopped before the finish line). Plain words, with commas replaced by spaces |
| `STAGE_ABORT` | `reason` | (1.4) The recording was started for a stage and the car never crossed the start line (it moved away from it, or 5 minutes went by). Plain words. The app deletes such a recording after writing this row, so a file you meet with this row has been copied out of the app some other way |
| `MARK_START` | `lat, lon, accuracy_m` | (1.4) The driver parked and marked the start of a new stage here: the average of the last (up to 5) still fixes, degrees with 7 decimals, and the mean `hAcc` of those fixes in metres |
| `MARK_FINISH` | `lat, lon, accuracy_m` | (1.4) The same for the finish. A recording with both is the *marking drive* from which the app builds a stage |
| `STOP` | none | Always the last row |

New event names are fine: the reader ignores names it does not know. None of the `STAGE*` or `MARK_*` rows is needed by the analysis: the app finds a stage's runs from the GPS fixes, and `analysis/stage_compare.py` does the same from the file ([`stage-algorithm.md`](stage-algorithm.md)); the events are the recorder's own notes of what it did, and a way to check one against the other.

## Axes and signs

The **phone frame** is the platform's sensor frame, the same on Android and iOS: **x to the right of the screen, y toward the top of the screen, z out of the screen**,
right-handed. The accelerometer reports *specific force*, which is the phone's acceleration minus gravity: a phone lying face up on a table reads **+9.81 on z**, so "up" shows as a positive reading,
and along any horizontal axis the reading is the phone's own acceleration (accelerating forward reads positive along the car's forward axis, braking reads negative). The analysis works out
which phone axis is the car's forward axis, up and left from the drive, so the phone may sit at any angle, but it must not move during the session.
`check_log.py` tests the sign from the file itself (the yaw rate about "up" must fall when the GPS bearing falls).

## One clock

All rows use **one monotonic clock** (on Android, `SystemClock.elapsedRealtimeNanos()`, which the platform uses for sensor events and GPS fixes alike). `t_us = (event time - session start) / 1000`.
The analysis needs `A`, `G` and `L` aligned to within about 0.1 s; the `K` rows let it check that, and it corrects a sensor clock that is plainly on a different base (a negative or minutes-long
lag). Wall-clock time (`utc_ms`) is only for the GPS fixes and for labelling.

## Starting a session

Ideally the car is parked and still for the first 5 seconds (the stationary calibration) with the phone fixed where it will stay. The real Sepang log has no standstill
(it starts at 172 km/h) and the analysis copes by fitting the orientation to the drive, at some cost in precision of the g numbers (see the report).

## Checking a file

```
python analysis/check_log.py path/to/session.csv
```

prints the health of a recording as PASS / WARN / FAIL lines: rows per kind and their rates and gaps, the size of the accelerometer reading (about 9.8 when the car is quiet if the
units are m/s²; about 1.0 means someone wrote g), the gyro units (rad/s, not deg/s), whether the GPS speed matches the distance between fixes (a factor of 3.6 means km/h), the sign of the
accelerometer, whether the clocks line up, and anything the analysis will have to guess. I tested it on the real Sepang file (it passes, with one warning that the car was not still at the start)
and on copies of it that I broke on purpose (g instead of m/s², flipped sign, km/h, deg/s): each one is caught. Run it on the first file a new recorder produces, before anything else.

## Notes for an iPhone recorder

I have not run any iPhone code, so treat this as a checklist, not as tested advice.

| Needed | iOS source | What to do |
|---|---|---|
| `A` | `CMMotionManager` accelerometer (`CMAccelerometerData.acceleration`), or `CMDeviceMotion.userAcceleration + gravity` | iOS reports in **g and with the opposite sign** (face up on a table, z reads -1). Write `-9.80665 * value` so that the flat phone reads +9.81 on z. Check it with a flat-on-the-table file and `check_log.py` |
| `G` | `CMGyroData.rotationRate`, or `CMDeviceMotion.rotationRate` | rad/s, right-hand rule: write as is |
| rate | `accelerometerUpdateInterval`, `gyroUpdateInterval` | ask for 0.01 s (100 Hz) |
| `L` | `CLLocationManager` / `CLLocation` | `coordinate`, `altitude`, `speed` (negative means invalid: write -1), `course` (negative: -1), `horizontalAccuracy`, `speedAccuracy`, `courseAccuracy` (negative: -1); `timestamp` goes to `utc_ms` |
| `t_us` | `CMLogItem.timestamp` is seconds since boot | use that clock for `A`/`G`. For an `L` row, convert the fix's wall-clock `timestamp` to the same clock (`uptime_now - (now - fix.timestamp)`) so all three kinds line up |
| keep running | background modes for location, `allowsBackgroundLocationUpdates`, screen kept on, a wake lock equivalent (`isIdleTimerDisabled`) | the car, the mount and heat are the same problems as on Android |

Use `desiredAccuracy = kCLLocationAccuracyBestForNavigation` and `activityType = .automotiveNavigation` for the best GPS.

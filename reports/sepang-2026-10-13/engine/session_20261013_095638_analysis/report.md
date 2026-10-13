# Track Logger analysis

File: `session_20261013_095638.csv`
Phone: samsung SM-S931B | Android 16,sdk,36

## Data quality
- Duration 43.9 min | accelerometer 118 Hz (310360 rows) | gyro 118 Hz | GPS fixes 2632 (1.00 Hz)
- GPS: median accuracy 3.8 m, speed accuracy 0.81 m/s, median delivery lag 14 ms
- No standstill in this file (the recording began on the move, or the car never stopped), so the phone's mounting angle was fitted to the drive itself: the accelerometer against the GPS speed change and the GPS course change, over a sliding window of about a minute (2626 GPS intervals, residual 2.2 m/s2, GPS time lag 0.39 s).
  - WARNING: the phone did not stay put. Its angle about the vertical drifted by 9 degrees (median) and up to 52 degrees from its average, and its tilt by 6 degrees. Braking and cornering numbers from the accelerometer are less certain than usual; GPS speeds and lap times are not affected much.
  - The accelerometer reads 3 % lower than the GPS-derived accelerations (a mount that flexes does this).
- Speed fusion: accelerometer bias -0.00 m/s2 (median), 32 GPS fixes skipped as outliers, 5 resets to GPS; fused speed agrees with the GPS fixes to 1.05 m/s rms (the accelerometer's error model was widened 7-fold: it does not follow the car as closely as a rigidly mounted, calibrated phone would)
  - WARNING: the fused speed and the GPS disagree by more than 1 m/s rms; speeds between GPS fixes are uncertain.
- Cross-check: accelerometer lateral g vs speed x gyro yaw rate: correlation 0.880, ratio 0.79 (1.00 = perfect; a mismatch of 5-10 % is roll and mount flex).

## Laps
Lap line: automatic, at the fastest point of the session (lat 2.760399, lon 101.734411). Pass `--sf lat,lon` to use the real start/finish line.

| Lap | Time | Distance m | Max km/h | Min km/h | Note |
|---|---|---|---|---|---|
| 1 | 2:42.950 | 5475 | 238 | 49 |  |
| 2 | 2:35.382 | 5521 | 238 | 46 |  |
| 3 | 2:38.522 | 5459 | 237 | 49 |  |
| 4 | 2:35.165 | 5467 | 237 | 50 |  |
| 5 | 2:36.222 | 5506 | 237 | 47 |  |
| 6 | 2:33.497 | 5511 | 239 | 52 |  |
| 7 | 2:37.804 | 5501 | 239 | 43 |  |
| 8 | 2:33.448 | 5459 | 237 | 50 |  |
| 9 | 2:33.672 | 5500 | 236 | 58 |  |
| 10 | 2:35.152 | 5475 | 236 | 47 |  |
| 11 | 2:36.798 | 5505 | 237 | 48 |  |
| 12 | 2:33.047 | 5511 | 237 | 53 | BEST |
| 13 | 2:36.368 | 5452 | 236 | 52 |  |
| 14 | 2:46.535 | 5471 | 237 | 51 | in/out/slow lap |
| 15 | 2:33.889 | 5479 | 237 | 54 |  |

## Corners (reference: best lap)

| # | dir | apex m | entry km/h | min km/h | exit km/h | brake from (m before apex) | peak brake g | apply s | release s | trail s | peak lat g | pickup m after apex | accel ramp s | turn-in dk/ds | turn-in R2 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| C1 | R | 230 | 237 | 64 | 63 | 230 | 1.47 | 0.18 | 5.78 | 1.33 | 1.06 | 40 | 7.45 | 5.6e-04 | 0.95 |
| C2 | R | 368 | - | 62 | 113 | - | - | - | - | - | 1.24 | 23 | 0.91 | - | - |
| C3 | R | 1160 | 208 | 74 | 116 | 162 | 1.31 | 0.19 | 3.87 | 1.69 | 1.15 | 8 | 1.84 | 4.4e-04 | 0.95 |
| C4 | L | 1672 | - | 119 | 127 | - | - | - | - | - | 1.19 | 25 | 4.03 | 3.6e-05 | 0.19 |
| C5 | R | 2155 | 177 | 95 | 121 | 123 | 1.02 | 0.23 | 2.47 | 1.75 | 1.15 | 44 | 1.67 | 1.8e-04 | 0.95 |
| C6 | L | 2742 | 196 | 53 | 111 | 164 | 1.11 | 0.20 | 2.33 | 1.02 | 1.07 | 7 | 1.21 | 7.9e-04 | 0.56 |
| C7 | R | 3084 | - | 87 | 122 | - | - | - | - | - | 1.21 | 8 | 2.28 | 2.7e-04 | 0.88 |
| C8 | L | 3452 | - | 129 | 136 | - | - | - | - | - | 1.36 | 1 | 12.11 | 1.2e-04 | 0.89 |
| C9 | R | 3764 | 136 | 68 | 110 | 95 | 0.84 | 0.38 | 2.04 | 2.81 | 1.14 | 6 | 2.52 | 4.3e-04 | 0.98 |
| C10 | L | 4716 | 231 | 62 | 109 | 233 | 1.25 | 0.27 | 2.82 | 1.07 | 0.94 | 3 | 3.11 | 3.2e-04 | 0.95 |

How to read it: *apply* = time from first brake pressure (-0.1 g) to 90 % of peak deceleration; *release* = time from 90 % of peak back to -0.1 g; *trail* = time braking AND cornering together; *accel ramp* = time from +0.1 g to +0.4 g (or 80 % of the peak if lower) on the way out; *turn-in dk/ds* = how fast curvature (the wheel angle) is wound on per metre, and *R2* = how straight that ramp is (1.00 = a perfect linear ramp).

## g-g envelope (speed > 8 m/s)
- Peak braking 1.32 g | peak acceleration 0.68 g | peak lateral 1.16 g left, 1.29 g right

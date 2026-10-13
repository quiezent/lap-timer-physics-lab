# Sepang track day, 13 October 2026: the raw log

`session_20261013_095638.csv` is the first real recording I received from the app, unedited. It is 28 MB.

| | |
|---|---|
| Circuit | Sepang International Circuit, full Grand Prix layout (clockwise, 15 turns, 5.54 km) |
| Car | Porsche 991.2 GT3 (the driver's own), track day, one session |
| Phone | Samsung SM-S931B (Galaxy S25), Android 16, mounted in the car; it moved in its mount during the session |
| Recorder | Track Logger 1.2 (the lap-timer screen of 1.3 did not exist yet, so the file has no `LAP` rows; the analysis finds the laps itself) |
| Start | 2026-10-13 01:56:38 UTC (09:56:38 Malaysia time, which is the number in the file name) |
| Length | 2,633 s (43.9 min) |
| Laps | 15 loops: best 2:33.07, a typical clean loop 2:34.51 |
| SHA-256 | `6e3020e4850caf494015dd2fa021a650caab675375d700528ade99408eda5d9f` |

Check it with `sha256sum session_20261013_095638.csv`. If your copy differs, the results in `reports/sepang-2026-10-13/` will not reproduce exactly.

## What is in the file

Plain text, one row per sample, comma separated. Lines starting with `#` are the header (device, sensor ranges, the start time on two clocks).
Every row has the sensor clock in microseconds as its second field.

| Row | Fields | How many here |
|---|---|---|
| `A` | `t_us, ax, ay, az` accelerometer in the phone's own frame, m/s² | 310,360 (about 118 Hz) |
| `G` | `t_us, gx, gy, gz` gyroscope in the phone's own frame, rad/s | 310,268 |
| `L` | `t_us, lat, lon, alt, speed, bearing, hAcc, sAcc, bAcc, utc_ms, lag_ms` GPS fix | 2,632 (about 1 Hz) |
| `S` | `t_us, satellites_visible, satellites_used` | 1,688 |
| `K` | `t_us, A/G, lag_ms` how late the sensor delivered a batch | 176 |
| `E` | `t_us, NAME, ...` events: `START`, `CAL` (the stationary calibration; here it says the car was not still), `STOP`, and in 1.3 `LAP,n,seconds` | 3 |

The accelerometer and gyroscope are raw and in the phone's frame: nothing has been rotated, calibrated or filtered. Turning them into
forward, sideways and braking g is the job of `analysis/analyze.py`; how it does that, and where it is uncertain for this file, is in
`reports/sepang-2026-10-13/REPORT.md` section 1.

## What it does not contain

No name, account, phone number or identifier beyond the device model. Every GPS fix is inside the circuit or its pit lane. The file starts
with the car already moving at 172 km/h, so there is no standstill: the analysis fits the phone's orientation from the drive itself.

## Using it

```
python analysis/ultimate.py data/sepang-2026-10-13/session_20261013_095638.csv --out my_report
python analysis/powerlaws.py data/sepang-2026-10-13/session_20261013_095638.csv --out my_powerlaws
```

Both are described in `docs/analysis-manual.md`. The outputs I got are committed in `reports/sepang-2026-10-13/` so you can compare.

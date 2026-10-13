# How the analysis works, in plain words

```
phone ──► session.csv ──► analyze.py ──► ultimate.py ──► powerlaws.py ──► numbers, tables, figures ──► REPORT.md
 (raw)     (docs/log-      channels:       laps, sections,   braking / cornering /
           format.md)      speed, g,       ultimate lap,     exit-speed laws, where
                           curvature       deltas, predictor the time went
```

Everything runs offline on a computer. The app only records, because a phone is a poor place to do physics while a driver is driving, and a recording can be re-analysed when the analysis improves.

## 1. Reading the phone (`analyze.py`)

The file holds raw motion sensors in the phone's own axes (about 118 Hz) and GPS (1 Hz). To use them it has to know which way the phone points and what each sensor gets wrong.

- **Orientation.** With the car parked and still, gravity gives "up" and the gyro's resting value gives its bias. The Sepang log has no standstill (it starts at 172 km/h), so the program fits the phone's orientation to the drive itself:
  it compares the accelerometer with the GPS speed change and the GPS course change over a sliding window of about a minute, which also follows a phone that moves in its mount. That gives the car's forward, left and up axes in the phone's frame, over time.
- **Speed.** GPS speed is good but 1 Hz; the accelerometer is fast but drifts. A Kalman filter and smoother combine them, with an error model that widens when the accelerometer and GPS disagree and resets to the GPS after two rejected fixes.
  The report prints how far the fused speed is from the GPS fixes (1.05 m/s rms on the real log) and warns above 1 m/s.
- **Channels.** Forward g (negative is braking), sideways g, curvature (yaw rate over speed, proportional to the steering angle), and the lap clock.

## 2. Laps and sections (`ultimate.py`)

- **The map.** The circuit is drawn from the driver's own fastest lap that has no GPS gap, and every lap is placed on it by progress along the track (positions from the GPS fixes, the integrated speed between them), on a **1 m grid**.
  The recorded "laps" are *loops*: they run from the middle of the main straight to the middle of it again, because the start/finish line's coordinates were not found in any source. They are therefore offset from the timing-line laps.
- **Sections.** The loop is cut in the middle of each long straight (80 m or more); on the Sepang session this gave five sections (T1 to T3, T4 to T6, T7 and T8, T9 to T14, T15). The cut points are where speed is high and an alignment error costs least time.
- **The ultimate lap.** A lap that leaves a corner faster gets a free head start into the next section, so each section's time is corrected for the speed carried over the cut. The correction is a *price* in seconds per m/s, taken from the car's power-limited acceleration envelope.
  The program then takes the best corrected section of each kind from all laps, shrinks lucky outliers back toward the typical value (so measurement noise is not mistaken for skill), and adds them up, with an error bar. The raw best-of-pieces number is printed too.
  On the real session: 2:31.78 ± 0.07.
- **Deltas.** Every loop is measured against the ultimate lap, by section and against distance.

## 3. Corner events (`ultimate.py`)

For each of the 15 corners (positions from open map data, moved to where the driver's own laps run slowest when the map is more than 8 m off) the program reads, lap by lap:
where the main braking zone starts (a track position in metres before the apex, where deceleration first passes 0.30 g or 35% of that zone's peak), peak braking (the strongest 0.2 s, not the best instant, because a phone in its mount rings at 2 to 10 Hz),
the slowest speed, where the throttle comes back (forward acceleration first above 0.10 g after the slowest point), speed 100 m after the apex, turn-in and peak sideways g. Values that cannot be trusted (a corner too close to the next, a pick-up that moves
by more than 25 m from lap to lap) are blanked or flagged instead of shown.

## 4. The lap predictor (`ultimate.py`)

Each clean loop is replayed the way a live display would have seen it: GPS fixes arrive late, only the part driven so far is used. Four predictions of the finishing time are compared with guessing a typical lap: the deviation so far stays,
a pace-scaled version, a fitted slope, and the average of those two. On the real session the four are within a few tenths of each other from 30% of the lap, so the simplest ("the deviation so far stays") is as good as any; it is about a second out at half distance and 0.07 s out at 95%.

## 5. The power-law layer (`powerlaws.py`)

This is the part I added for this project. It reuses the engine's aligned laps and events and asks physical questions of them:

- **Braking zones.** For each lap and heavy zone, fit a straight line to speed² against distance; the slope is the steady deceleration, R² says how straight it is; compute the apex-speed sensitivity `a/v` and, at T1, the time cost per metre of braking point.
- **Cornering.** The radius the car drove at each corner's slowest point, and a log-log fit of speed against radius over the corners that have a minimum of their own, with a bootstrap interval and a leave-one-out check; the grip used at each; a cross-check of the gyro's sideways g against the accelerometer's.
- **Exit speed.** Fit the straights' speed envelope `a(v) = pw/v − k v²`, integrate how a speed advantage decays along each following straight, and compare with the slope of straight-time against exit speed across the laps.
- **Where the time went.** For each corner a *window* runs from the middle of the gap before it to the middle of the gap after it; a lap's *excess* in a window is its time minus the median of the other laps. A window is **rated** only where the loop is a fast one and the GPS is good through all of it.
  Then: how concentrated the loss is (against a Gaussian-noise comparison), the ten largest windows, and, for the biggest, what the driver inputs looked like.
- **Figures and tables.** Seven figures and fifteen tables, plus `numbers.json` holding every figure the report quotes.

## 6. The models and the simulator (`physics/`, `sim/`)

- `physics/gt3_model.py`: a point-mass 991.2 GT3 (engine power and efficiency, drag, downforce, tyre grip, rolling resistance), with the published figures as defaults.
- `sim/simulate_sepang.py`: a quasi-steady-state lap solver (a perfect driver on an ideal line, every corner at the grip limit) and a simulator that adds planted driver errors, sensor noise, GPS delay and dropouts and writes the app's CSV with the exact truth beside it.
  It is how the engine was tested before it met a real log (`docs/validation.md`).
- `sim/calibrated_lap.py`: asks whether the model reproduces the real lap when it is given what the straights and the braking showed.
- `analysis/sepang_model.json` and `.npz`: the Sepang centreline, widths, corner positions and a physics prior (see `NOTICE.md` for the sources).

## Design choices worth knowing

- **Raw logging.** The recorder never rotates, calibrates or filters. All the judgement is in the analysis, where it can be re-run.
- **Say the uncertainty.** Every figure that matters has an interval or a note; a result that can't be trusted is blank and says why.
- **Laps against laps.** The model is used to order the corners by value, not to say what a lap "should" take.
- **A rule beats a hunch.** The "rated window" rule replaced my first, wrong ranking of the biggest losses. It is a rule in the code, not a judgement call per case.

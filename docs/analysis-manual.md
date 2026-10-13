# Analysis manual

How to run the Python analysis on a recording and how to read what comes out. The programs are all in [`../analysis`](../analysis); each reads the CSV that the app writes
(format: [`log-format.md`](log-format.md)).

| Program | What it does | Typical use |
|---|---|---|
| `analyze.py` | One file: turns the raw phone signals into speed, braking/throttle, steering, g-g traces, lap times and a per-corner table | a first look, and a health check of a new recording |
| `ultimate.py` | One or several files: builds the circuit map, stitches the **ultimate lap**, measures every loop against it, replays the **lap predictor** (it calls `analyze.py` for each file) | the day's report |
| `powerlaws.py` | One file: the **power-law report** of this project: braking in speed², corner speed against radius, what an exit speed is worth, where the time went, with figures and tables | the physics view; the report in `reports/` was made with it |
| `check_log.py` | One file: does the recording meet the [log format](log-format.md)? PASS / WARN / FAIL lines for rates, units, signs and clocks | the first thing to run on a file from a new recorder or phone |
| `stage_compare.py` | (release 1.4) **Stages** on a computer: `build` a stage from a marking recording, `score` every run of a stage in a recording, `compare` two runs (a stage file, a folder or a backup zip), `selftest`. It reads and writes the same text files as the app ([`stage-format.md`](stage-format.md)) and was written from the method page ([`stage-algorithm.md`](stage-algorithm.md)) without reading the Android code | scoring a recording you copied off the phone; checking another implementation against the fixtures |
| `test_reader.py` | Does `analyze.load_session` take every kind of row the app writes (the 1.4 stage events included)? 11 small cases, under a second | after a change to the reader |
| `watch_day.py` | A folder: re-runs `ultimate.py` whenever a new session file arrives | at the track, with a laptop |
| `regression_check.py` | Re-runs `ultimate.py` and `powerlaws.py` on the committed Sepang log and compares every output with `reports/sepang-2026-10-13/` | after any change to the analysis (about 40 s) |

```
pip install numpy scipy matplotlib
python analysis/powerlaws.py data/sepang-2026-10-13/session_20261013_095638.csv --out my_powerlaws
```

`powerlaws.py` runs the engine first (so it needs `ultimate.py` and its companions in the same folder), then writes `numbers.json` (every figure it quotes), `tables/*.csv` and
`figures/*.png` into `--out`; `--work DIR` chooses where the engine's intermediate files go and `--no-plots` skips the figures. It takes about 20 seconds. The results are deterministic
(the bootstrap uses a fixed seed). `sim/calibrated_lap.py` then uses `numbers.json` to ask whether the physics reproduces the real lap time (see the report, section 8).

## `stage_compare.py`: stages from a computer

```
python analysis/stage_compare.py selftest                                  # reproduces every expected result of analysis/stage_fixtures (about a second)
mkdir my_stage
python analysis/stage_compare.py build marking.csv --id st_mine --name "My road" --out my_stage/stage.txt   # a stage from a marking recording
python analysis/stage_compare.py score my_stage session_20261013_121500.csv --out-dir my_stage             # every run of that stage in a recording, saved as run files
python analysis/stage_compare.py compare my_stage                                                         # the newest run against the best other one (--against previous, --plot f.png)
```

Run `python analysis/stage_compare.py --help` for the options of each command (it needs only the Python standard library; `--plot` also needs matplotlib). A *marking recording* is a session file with `MARK_START` and `MARK_FINISH` events, such as the app writes when you mark a stage.
The method is [`stage-algorithm.md`](stage-algorithm.md); the plain-words guide is [`stages.md`](stages.md). Stage times are good to a few tenths of a second, not to the hundredth: read the accuracy section before quoting one.

## `analyze.py`: analyse one session

```
pip install numpy matplotlib
python analysis/analyze.py session_20261013_093012.csv
python analysis/analyze.py session_20261013_093012.csv --sf 2.7608,101.7380    # optional start/finish line
```

(`--sf` takes the latitude,longitude of the start/finish line, for example from a long press in Google Maps.
Without it laps are cut at the fastest point of the session, which is fine for comparing laps.)

It writes, next to the CSV: `report.md`, `channels.csv`, `lap_table.csv`, `corner_table.csv`, `fig_best_lap.png`,
`fig_corners.png`, `fig_laps.png`, `fig_gg.png`, `fig_map.png`.

How the channels are made:

* **up** and **gyro bias** come from the 5 seconds you sat still.
* **No standstill?** The first real log began at 172 km/h in a braking zone, so there was no stationary calibration. The
  analysis then fits the phone's orientation to the drive itself (the accelerometer against the GPS speed change and the
  GPS course change, over a sliding window of about a minute), which also follows a phone that moves in its mount. It is
  less precise than a calibrated start; the report says which mode it used and warns when the phone moved (on the real log
  by 9 degrees median and up to 52, mostly in the first ten minutes).
* **forward axis** is found by regressing the phone's horizontal acceleration against the GPS speed derivative, so the
  mount angle does not matter. The report prints R2 (should be close to 1) and the GPS time lag.
* **long. g** = acceleration along that axis: negative is braking, positive is throttle.
* **lateral g** = acceleration across it (left positive). **Curvature** = yaw rate / speed, proportional to wheel angle.
* **speed** = GPS Doppler speed combined with the integrated accelerometer (Kalman filter and smoother). When the
  accelerometer disagrees with the GPS (a flexing mount, a sensor that reads a few per cent low) the filter trusts it less,
  restarts from the GPS after two rejected fixes, and the report prints how far the fused speed is from the GPS fixes (a
  warning above 1 m/s rms).
* Report check: lateral g from the accelerometer against speed x yaw rate from the gyro. They should agree within
  5-10 % (the rest is body roll and mount flex).

Tested on simulated sessions (11 minutes, 5.3 km lap, 4 flying laps, random mount angle, noise, GPS lag, GPS dropout):
forward axis within 0.5 degrees, speed error 0.06 m/s rms, long. g error 0.02 g, curvature error 2 %, lap times
within 0.03 s. It also copes with a sensor clock that differs from the GPS clock, a GPS fix stamped before START, and a
failed calibration. These tests use my own model of the sensors, so they check the maths, not the phone.

On the first real log (118 Hz sensors, 1 Hz GPS, no standstill, a phone that shifted in its mount) the same comparisons
against the raw GPS were: lap times agree across three positions of the timing line within +-0.06 s (not compared with a
transponder); slowest speed in a corner within 1-2 km/h; total speed lost in each braking zone within about 5 %; speed
100 m after the apex read 6-7 km/h low at the slowest corners (T2, T14, T15), though lap-to-lap differences follow the GPS
(correlation 0.67-0.96); fused speed against GPS fixes 1.05 m/s rms; the accelerometer read 3 % lower than the GPS-derived
accelerations.

## `ultimate.py`: ultimate lap, deltas and lap prediction

`analysis/ultimate.py` takes one or more session files, builds the circuit map from your fastest lap that has no GPS
dropout, cuts every lap into seven sections at the middle of the straights, and then:

* **stitches the ultimate lap**: for each section it takes the best demonstrated version, after correcting for the speed
  the lap carried over the cut (a lap that leaves a corner faster starts the next section faster; the price is in seconds
  per m/s and is printed). The best of several noisy numbers is optimistic, so the figure is shrunk toward the typical
  section (empirical Bayes) and printed with an error bar. The raw best-of-pieces figure is shown too.
* **measures every loop against it**: time gap per section and against distance, and the **driver-input deltas** per
  corner: slowest speed, where the main braking zone starts (a track position, metres from the apex), peak braking,
  throttle pick-up, speed 100 m after the apex, peak cornering and turn-in. Peak braking is the strongest 0.2 s, not the
  best instant (a phone in its mount rings at 2-10 Hz, which moves a single instant by 0.1-0.2 g from lap to lap). Throttle
  pick-up is shown only if it happens before the next corner's braking zone and is marked `*` (and not compared) where it
  varies by more than 25 m between laps. Speed 100 m after the apex is left blank where the next corner, or its braking
  zone, is too close. The report's section 6 ("How far to trust this") opens with "About this recording": the notes from the per-file analysis (no
  standstill, phone moved, accelerometer scale, fused speed against GPS).
* **replays a lap-time predictor** on your clean loops the way a live display would have seen them (GPS fixes arrive late
  and only the part of the lap driven so far is used; the "typical lap" it compares with is built from your other loops,
  later ones included, which a live display would not have yet), and shows the "potential" of the lap in progress: what
  it would finish at if the rest were driven like the ultimate lap.
* numbers the 15 Sepang corners by laying the built-in model (racing line, corner positions, a physics reference for the
  991.2 GT3) over your own map. If the map does not match the full Grand Prix layout within 12 m, it says so and labels the
  corners C1, C2... from your laps instead (about 12 of the 15 corners; double-apex pairs merge). `--no-model` forces that.
  The corner positions come from open map data, not a survey, and can be tens of metres off. So for the driver-input
  numbers the program moves each corner's reference apex to where your own laps run slowest, whenever that is more than
  8 m from the map's apex, and the report says so.

```
pip install numpy matplotlib
python analysis/ultimate.py session_A.csv session_B.csv session_C.csv     # all files pooled into one analysis
python analysis/ultimate.py session_A.csv --out report_A                    # choose the output folder (default: ultimate_out)
python analysis/ultimate.py ... --no-model                                  # North/South layouts, other circuits
```

It needs `analyze.py`, `ultimate_report.py`, `sepang_model.npz` and `sepang_model.json` in the same folder, Python 3 and
numpy (run here only on Python 3.13; a static check finds nothing newer than 3.7 in the code); matplotlib is only for the
figures, and `--no-plots` skips them. It takes about
5 seconds per session. Output: **`ultimate_report.md`** (read this first), `ultimate_sections.csv`, `ultimate_corners.csv`
and five figures. [`../reports/sepang-2026-10-13/engine/`](../reports/sepang-2026-10-13/engine) holds the real report for the Sepang session, and
[`sepang-primer.md`](sepang-primer.md) is a primer on the circuit.

**Using it on the day.** The recording is saved when you press STOP, so the loop is: session, STOP & SAVE, copy the CSV to a laptop
and run the script, read the report, adjust, next session. Pool the files of the day so
the ultimate lap and the gaps update as laps come in. With a laptop at the track,
`python analysis/watch_day.py FOLDER` does the pooling for you: copy each session file into FOLDER and, once the file has
stopped growing, every session in the folder is analysed together and `FOLDER/ultimate_out/ultimate_report.md` is
rewritten (Ctrl-C to stop). When several files are pooled, one that cannot be used (empty, no GPS lock, too short) is
named in the report and left out, instead of stopping the whole analysis. Four or more clean loops are needed for the predictor, and a loop is
clean only if every section has a good GPS fix. A loop runs from the middle of the main straight to the middle of it again,
so a usable one needs both crossings at racing speed; out-laps and in-laps are skipped, and with only one usable loop the
report says there is nothing to stitch yet. Keep tyres and fuel roughly alike and **never mix wet and dry runs**.
Loops start in the middle of the main straight (the start/finish line was not found in any source), so they are offset from
the dash's timing-line laps.

**What the report flags instead of hiding.** The independent review of this code (see [`validation.md`](validation.md)) found that bad data could
produce a confident but wrong report, so the report now says so when:

* the GPS stopped for more than 5 s (a section is used only if there is a GPS fix within 3 s of both of its ends; longer
  dropouts widen the error bar, the lap line is never placed inside a gap, and the circuit map is drawn from a lap
  without a gap when there is one);
* the map drawn from your best lap is more than 2 % longer or shorter than a typical lap, which means part of it had no GPS;
* no loop is clean through every section (the ultimate lap is then stitched from partial loops and can come out slower
  than your fastest loop), or your fastest loop is the one with the dropout (its affected sections cannot be used, so the
  ultimate lap comes out slower than it, by 0.15 to 0.4 s in the tests, and the report tells you to take the fastest loop
  as your best);
* a file is the same recording as another one you gave (the second is named and left out);
* there are too few loops for a comparison (with two or three the report says what is missing instead of printing gaps).

Time spent parked with the app running (pit lane, paddock) does not count when the tool measures GPS noise.

**How well it worked on simulated sessions.** 11 simulated Sepang sessions with planted driver mistakes, 5-10 flying laps,
1 Hz GPS with 2-4 m of correlated error, delay, dropouts and outliers; the answers were known exactly:

| What | Result |
|---|---|
| Section time error | 0.03-0.06 s rms |
| Ultimate lap, raw best-of-pieces | 0.10 s too optimistic on average (too fast) |
| Ultimate lap, noise-corrected | no bias on average (+0.001 s), but individual sessions were 0.15 s too fast to 0.15 s too slow (spread 0.087 s) against the physically re-simulated lap of the chosen pieces |
| Printed error bar (0.07-0.15 s) | the actual error was larger than the bar in 3 of 11 sessions, about what a one-standard-deviation bar should give; error / bar has rms 0.69 |
| Choice of pieces | on average 0.07 s worse than the best mix a search found (worst 0.18 s) |
| Corner events (spread of the error from lap to lap, against the exact answer) | braking position 1-2 m at most corners (mean 1.9 m), slowest speed 0.27 km/h, exit speed 0.5 km/h, pick-up 0.5 m, peak brake 0.007 g (the strongest 0.2 s; the instantaneous peak used before 1.3 was 0.015 g) |
| Lap-time prediction (best model, rms over 65 simulated laps) | 1.3 s with no look at the lap; 0.9 s at 20 % of the lap, 0.5 s at 50 %, 0.26 s at 80 %, 0.06 s at 95 % |

The noisier the GPS, the wider the error bar: the tool measures the sideways scatter of your GPS fixes on the straights
(only fixes taken at racing speed count), prints it in the summary, and raises its timing noise floor to match.

Single corner readings can still be wrong. Over 11 simulated sessions about 1 braking position in 170 was 10 m or more
off (T3, T9 and T14, in 3 sessions), 1 exit speed in 380 was more than 3 km/h off, and 1 slowest speed in 40 was more than
1 km/h off, mostly at T13, which has no real minimum. Peak braking at T6 read about 0.1 g low on every lap, which cancels when
you compare laps. So the report leaves exit speed blank where the next corner is under 100 m away (T1, with T2 80 m later),
does not coach on corners without a speed minimum of their own (T10, T13), and a single large gap in one corner of one lap is
worth a second look in `ultimate_corners.csv` before you act on it.

**First real session (13 Oct 2026, Sepang).** 15 timed laps in 44 minutes: best 2:33.07 (lap 12; 2:33.05-2:33.08 depending
on where the timing line sits), typical clean lap 2:34.51, ultimate lap 2:31.78 +-0.07, which is 1.29 s under the best lap
and comes from five different laps. The average gap to the ultimate lap by section: T9-T14 0.91 s, T1-T3 0.63, T7-T8 0.56,
T4-T6 0.54, T15 0.43. The predictor, replayed on the 10 clean loops, was 1.0 s out at 50 % of the lap, 0.3-0.5 s from 70 %
and 0.07 s at 95 %, against 1.58 s for guessing a typical lap; the simulations had suggested 0.5 s at 50 %, because real
laps vary more (traffic, mistakes). The phone shifted in its mount and the file had no standstill, so braking and cornering
g from the accelerometer are less certain than the lap and section times, which come from GPS.

**Limits, stated plainly.** The simulator is my own model of Sepang and of a driver, so these numbers show that the
method works when its assumptions hold, not that it works on your phone. Real GPS can drift or jump near buildings.
Pedals are inferred from acceleration. The ultimate lap assumes the pieces can be driven together; the speed correction
covers the hand-over between corners to first order only. Differences smaller than the printed bar are noise.
The real session is the only real test so far: on it the lap and section times, the slowest speeds and the total braking
per zone held up against the raw GPS, while exit speeds, braking positions and throttle pick-up are rough (see the `analyze.py` section above).

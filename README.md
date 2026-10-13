# Lap Timer Physics Lab

**A phone lap timer, and a physics-first reading of a track day: what braking, cornering and acceleration say about where lap time is.**

I'm Claude, an AI model made by Anthropic. I wrote this project with a driver: an Android app that records a drive, times laps and (since release 1.4) times **point-to-point road stages**, and software that
reads the recording through the laws that govern a car. It covers one car (a Porsche 991.2 GT3), one circuit (Sepang) and, so far, **one real session**:
13 October 2026, 15 laps, 44 minutes, from one phone. I have never sat in the car. What I know is what a phone could sense, plus physics, and
everything I call a finding below was produced by code in this repository running on the CSV in this repository, so you can re-run it and check me.

![Where each loop gained or lost time against the median loop, by corner](reports/sepang-2026-10-13/figures/where_the_time_goes.png)

## Where it stands

| Part | State |
|---|---|
| Android recorder (`android/`, version 1.2) | Ran on a real phone at Sepang: 44 minutes, 118 Hz motion sensors, 1 Hz GPS, nothing lost |
| Lap timer in the app (version 1.3) | In 1.4 too. Passes automated checks on a PC (some replay the real log's GPS fixes and reproduce its 15 lap times); **has not been run on a phone yet** |
| **Stages in the app (version 1.4)** | Mark the start and finish of a road by driving it once; after that the phone starts and stops recording at those lines by itself, keeps each drive as a run, and compares runs afterwards. On open roads it shows **no time or difference while the car moves**. 324 automated checks pass on a PC (315 without the real log); a second implementation of the method, written from the written method alone, reproduces all 28 stage and run test files byte for byte. **Never run on a phone, never driven on a road** |
| Analysis (`analysis/`) | Runs on the real log: lap times, an "ultimate lap", deltas, a lap predictor, and the power-law report. `stage_compare.py` does stages on a computer |
| Coaching on the phone | **Not built.** The advice lives in the report. Next release: load earlier Sepang sessions as the baseline and a Coaching button for the circuit (see below) |
| iPhone app, other circuits | Not started. Ideas and a contract for contributors are below |
| Data | One session at Sepang, no real stage yet. Every result is a hypothesis to test, not a verdict |

## The idea I started from

The driver's thought was that driving fast means *turning exponential physics into linear execution*. I studied it and I'd put it slightly differently:
the physics of a car is made of **power laws** (a quantity that scales as a power of speed or radius), not exponentials, and the useful trick is that
each one becomes a straight line in the right coordinates. A straight line is something a driver can execute and a program can fit.

| Law | Why | The straight line | What 10% more speed does |
|---|---|---|---|
| **Braking** | stopping distance is v² / 2a | speed² falls linearly with distance | 21% more distance to stop |
| **Cornering** | grip gives sideways acceleration v² / r = a | speed grows as the square root of radius | needs 21% more radius (or 21% more grip) |
| **Straights** | drag force grows as v², drag power as v³; power-limited acceleration falls as 1/v | acceleration = P/(m·v) − k·v² | 21% more drag, 33% more power to hold it |
| **The lap** | time = distance / speed | | 9% less time |

Two consequences, both measured on the real laps (details in the [report](reports/sepang-2026-10-13/REPORT.md)): the **last metres of a braking
zone decide the apex speed** (one metre of braking-point error moves the apex speed by at most 0.8 to 2.6 km/h, depending on the corner, because a/v is largest where v is smallest), and a
**km/h at a corner exit is worth about 0.01 to 0.03 s** on the straight that follows, an advantage that decays with drag but never reaches zero.

![The three laws, with the Sepang laps sitting on them](reports/sepang-2026-10-13/figures/three_laws_real_points.png)

## What I set out to do, and how it went

| # | The objective I gave myself | How it went |
|---|---|---|
| 1 | Make the physics measurable: find the coordinates where each law is a straight line, and test them on real laps | **Met.** Braking is a straight line in speed² (median R² 0.973 over 98 braking zones); corner speed follows radius^0.50 (90% interval 0.48 to 0.53) over 11 corners; the straights follow the power-limited curve in shape but with about three quarters of the brochure power, which I cannot yet explain |
| 2 | Record a track day on a phone, reliably | **Met, with a caveat.** No data lost, but the phone moved in its mount (9° median, up to 52°) and the file starts at 172 km/h with no standstill to calibrate from. The first real log broke my analysis, and I changed it |
| 3 | Build an **ultimate lap**: the best demonstrated version of each section, stitched, with the speed carried across each join accounted for | **Met.** 2:31.78 ± 0.07, which is 1.29 s under the best loop (2:33.07) and 2.73 s under a typical clean loop (2:34.51); it comes from five different loops |
| 4 | Predict the lap while it is being driven | **Partly.** About a second out at half distance, 0.07 s at 95%. My simulations promised 0.5 s at half distance; real laps vary more |
| 5 | Say where the time is, in terms a driver can act on | **Met as hypotheses.** Ranked, sized, with the law behind each (table below). None has been tried on track |
| 6 | Validate before trusting, and say what I don't know | **Mostly.** Simulated sessions with planted mistakes, an independent code review (10 findings, all addressed), and cross-checks on the real log. **Missing: ground truth** (a transponder or the car's own data) |
| 7 | Show it on the phone | **Not done.** The app times laps; the coaching is in the report |

## What I found at Sepang

The best loop was 2:33.07 and a typical clean loop 2:34.51. Where I think the time is, ranked by what I would try first (the items overlap, so don't add them up):

| # | Where | What the log shows | Worth per lap | How sure |
|---|---|---|---|---|
| 1 | Incidents anywhere | 10 of 180 rated corner-laps lost 0.5 s or more and hold **41%** of everything lost; two I could read in detail look like arriving too fast or lifting, not pace | about 0.6 s | the loss is certain, the cause is not |
| 2 | T12: commit or lift | five loops carried 129 to 136 km/h at the slowest point, five dipped to 119 to 125 km/h; the first group was 0.3 to 0.4 s quicker | 0.3 to 0.4 s | high on size; whether to go flat is the driver's call |
| 3 | T1 braking point | the four fastest approaches braked 216 to 227 m out, the slowest three 244 to 247 m; each metre earlier costs 0.027 s | about 0.2 s | moderate |
| 4 | T9 grip | slowest point at 0.86 g where T1, T4 and T11 reach 1.03 to 1.05 g | 0.1 to 0.2 s | low to moderate |
| 5 | T5 and T7 mid-corner speed | laps with a faster slowest point were quicker (r = -0.88 and -0.82); 4% more speed needs 8% more radius | 0.15 to 0.3 s | moderate |
| 6 | T15 exit onto the main straight | speed 100 m after the apex ranged from 91 to 120 km/h; later braking went with a better exit | 0.06 to 0.1 s typical, more if the best exit repeated | low to moderate |

These are for the driver to test, a few metres or a few km/h at a time, only while the car stays settled and within the track's rules. A phone can't see the
pedals, the steering, the traffic or the flags, and the driver knows things it does not. The full evidence, the figures and the case studies of two incidents are in
**[reports/sepang-2026-10-13/REPORT.md](reports/sepang-2026-10-13/REPORT.md)**.

## What I learned

- **The three laws are real on a real car**, not just textbook: straight lines in speed² for 99% of braking zones (R² above 0.9), the same grip (0.86 to 1.05 g) at the slowest point of every corner, and a straights curve that matches the 1/v shape.
- **Loss is lumpy.** Five per cent of the corner-laps hold 38% of the time lost; pure noise of the same size would put 26% there. The largest single lever is not technique but the one-off moments (arriving 9.5 km/h too fast at T4, a dip to 81 km/h at T12) that cost 1 to 2 seconds.
- **A phone is good for shapes and timings, not for absolute numbers.** Lap and section times are good to a few hundredths; braking and cornering g are less certain because the accelerometer reads about 3% low and the mount moves.
- **A physics model needs the grip.** With the engine, drag and braking the phone measured, a perfect drive of my point-mass model takes anywhere from 2:29.7 to 2:37.3 depending on a cornering grip I can only bound, because 0.1 of grip is 3 to 4 s. The real laps sit inside that range; the model can't say how far inside, so I compare laps with laps and use the model only to order corners.
- **Real data humbles the code.** The first real log broke the analysis (no standstill, a moving phone) and my first "biggest losses" table blamed GPS gaps for 2 to 3 seconds that were not driving. Both are fixed, and the second is now a rule: a corner window counts only where the GPS is good through all of it.

**What I got wrong or could not show:** firmer braking at T1 did *not* make T1 to T3 faster; I can't attribute the rise of peak g with speed to aero; T12 and T13 radii are noise (flat-out kinks); the brochure physics was 8 s too fast; the lap predictor is weaker on real laps than on my simulations. The report lists each with the evidence.

## What I'd improve with more data

1. **More sessions, same driver.** Ten to fourteen laps per corner leave the intervals wide; four or five sessions would turn most "moderate" items into a yes or a no.
2. **Other drivers and cars**, to see whether the exponents (0.50 for cornering, the near-constant 0.9 to 1.0 g braking) hold when the grip and the line change.
3. **A rigid mount and a standing start**, so the accelerometer needs less guessing.
4. **Ground truth for the braking points** (a transponder, a second logger, or the car's CAN speed and brake pressure). Today they are checked for repeatability, not against truth.
5. **A full-throttle run and the car's mass**, to settle why the straights show about three quarters of the brochure's push.
6. **Tyre, fuel and weather drift**, so a session's trend can be modelled instead of treating loops as interchangeable.
7. **Enough laps to say whether the loss has a power-law tail**, and with what exponent. With 180 corner-laps I can only say it is lumpy.

## The app, today and next

**Today (1.4):** records the raw signals to a CSV; shows the running lap, the last lap against the best, and the list of laps, timed from GPS at a built-in line on the Sepang main straight; and has a **Stages** screen for roads. It shows nothing from the report.

**What the driver asked for next, and I will build in this order:** (1) *Sepang from earlier sessions*: load a previous Sepang session (or several) on the phone as the baseline, so that predictions and comparisons work from the first lap and not after a few laps of collecting data; (2) a **Coaching button** for the circuit: predicted lap time and a live delta against your own best-of-sessions lap from lap 1, with a debrief after the session
(circuit only; stages on open roads stay as they are). Before or alongside: the two stage limits below.

**The debrief idea, in the order I first wrote it:**

1. **A pit-lane debrief screen.** After a session, on the phone, the report's essentials in a few cards: the ultimate lap and its gap, the three biggest gaps by corner, the braking-point spread at each heavy zone with the v² line, and the one question the data raises ("what was different in loops 7, 9, 10, 12 and 15 at T12?"). A driver's attention belongs on the road; coaching you read at a standstill is safe, and a live display is not something to add lightly.
2. **A glanceable live delta** to the ultimate lap, as one number, only after the debrief is working.

A concept of the debrief, using the real Sepang numbers (**not built**, a sketch of the idea):

```
  PIT DEBRIEF (concept)           session 13 Oct, 15 loops
  best 2:33.07   ultimate 2:31.78 ± 0.07   gap 1.29 s

  biggest gaps to your own best sections
   T9–T14  0.91 s   T12: 5 loops at 129–136 km/h, 5 at 119–125  (+0.3–0.4 s)
   T1–T3   0.63 s   T1 braking point: 232 m median, 216 m fastest (0.027 s per metre)
   T7–T8   0.56 s   T7 slowest point: best three 4.4 km/h above typical (0.034 s per km/h)

  one-off losses ≥ 0.5 s: 10 of 180 corner-laps = 41% of time lost
```

## Release 1.4: stages for the roads you drive

The driver also drives roads that are used as rally stages (the Dash highways, KKB to Fraser, Fraser to Bentong) and asked for the app to mark a start and a finish once, then start and stop the logging on those lines on every later drive, save them as stages, and compare re-runs.
I asked where they drive them. The answer was **open roads, normal traffic**, and that decided a lot: the app shows no time and no comparison while the car moves, and that is a rule the tests enforce, not a promise ([`docs/stages.md`](docs/stages.md)).

| # | The objective I gave myself | How it went |
|---|---|---|
| 1 | Mark a start and a finish by driving the road once, without typing coordinates | **Built, never tried on a phone.** Park, press *Mark start*, drive, park, press *Mark finish*; the stage is built from the drive and the drive itself is scored as the first run |
| 2 | On later drives, start and stop the logging on those lines by itself | **Built, never tried on a phone.** Arm a stage; the phone waits on the GPS alone and records from the approach to 5 s past the finish, then arms itself again. Whether Android lets it keep doing that with the screen off for an hour on a given phone is the main thing I could not test |
| 3 | Keep the road safe: nothing to read or touch while moving | **Met, as far as a PC can show.** Words only while the car is not known to be stopped (a random walk of 4000 steps through the screens found no moving screen with a time on it); one beep at the start line, two at the finish, a low tone if a run is given up. I have not heard the beeps on a phone |
| 4 | Save every drive as a run and compare re-runs: where was one ahead of the other | **Built.** Best, previous or first as the baseline; a graph of the difference along the road; sections; an "about the same" band that widens when the car was slow at a line |
| 5 | Make the method checkable by someone who does not trust my code, and re-implementable (an iPhone version) | **Met, with a finding.** [`docs/stage-algorithm.md`](docs/stage-algorithm.md) gives every step and constant; a second pass of the same model, given only that page, wrote [`analysis/stage_compare.py`](analysis/stage_compare.py) and reproduced all 28 test files byte for byte. It also had to guess in five places (now written down) and found two limits (below) |
| 6 | Say how accurate a stage time is | **Partly.** From simulations: about `3 m x sqrt(1/v_start² + 1/v_finish²)` seconds, so 0.1 s for fast rolling lines and 0.3 to 0.4 s on a slow mountain road. **No stopwatch or second device has checked it on a road** |

**What I learned.** A stage time is only as good as the speed at its two lines: one metre of GPS error is `1/speed` seconds, so a standing start is three times worse than a flying one, and a twisty road is worse than a highway. The lines are 20 m inside the marks for that reason, and the independent simulation showed they come out 15 to 18 m of road from them, not 20 (the recorded route zigzags with the noise).
The same independent pass found two limits that my own tests had not looked for: **a finish by braking to a stop can be scored "did not finish"** when the line ends up a few metres before the stopping place (28 of about 1500 simulated runs, 25 of them on one stage), and **tight stacked hairpins at a start or finish line** (legs closer than about 16 m) can make a run cross the line on the wrong leg and be wrong by tens of seconds. Both are documented in [`docs/stage-algorithm.md`](docs/stage-algorithm.md) section 9 and on the roadmap; the app does not yet warn about either.
And a clean first pass of a second implementation proves less than it seems: the Python author broke their own code in 55 one-line ways, and the shared test files noticed 25; the other 30 are rules of the method that no test file holds anyone to yet (listed on the roadmap).

**What I'd improve with more data:** drive a stage twice on a real road with a stopwatch or a second device at the lines, and a phone in the mount, and see whether the simulated accuracy holds; try it under tree cover and in a cutting; find out what a battery manager does to an armed phone with the screen off; let the user's real roads (not my synthetic ones) test the hairpin limit; and write the 30 missing test files.

## Try it

**The app.** [`releases/`](releases) holds the APKs: `TrackLogger-1.2.apk` is the recorder that ran at Sepang, `TrackLogger-1.3.apk` adds the lap timer, and `TrackLogger-1.4.apk` adds Stages (neither has run on a phone yet). Install steps, mounting and a ten-minute test before the track are in
[`android/README.md`](android/README.md); how to use Stages, and the rule for open roads, are in [`docs/stages.md`](docs/stages.md). Never touch the phone while driving. Try a new release parked, and on a quiet road, before you rely on it.

**The analysis** (Python 3; `pip install -r requirements.txt` installs numpy, scipy and matplotlib):

```
python analysis/ultimate.py  data/sepang-2026-10-13/session_20261013_095638.csv --out my_report
python analysis/powerlaws.py data/sepang-2026-10-13/session_20261013_095638.csv --out my_powerlaws
```

The first makes the ultimate lap, deltas and predictor; the second makes the power-law report and the figures. Both are deterministic. Using your own file is the same
command; the manual is [`docs/analysis-manual.md`](docs/analysis-manual.md).

**The stages**, on a computer: `python analysis/stage_compare.py selftest` re-computes every expected result of the stage method (in about a second, from the standard library alone), and `build`, `score` and `compare` do what the app does with a recording you copied off the phone.

## Map of the repository

| Path | What is in it |
|---|---|
| [`reports/sepang-2026-10-13/REPORT.md`](reports/sepang-2026-10-13/REPORT.md) | **The report**: findings, case studies, the places to improve, what I got wrong |
| [`data/sepang-2026-10-13/`](data/sepang-2026-10-13) | The real recording (28 MB CSV, unedited) and what is in it |
| [`docs/physics-and-power-laws.md`](docs/physics-and-power-laws.md) | The three laws, the exponents, and how I tested them |
| [`docs/how-it-works.md`](docs/how-it-works.md) | The analysis engine in plain words |
| [`docs/validation.md`](docs/validation.md) | What was tested, how, with what result, and what was not |
| [`docs/analysis-manual.md`](docs/analysis-manual.md) | Using the analysis scripts |
| [`docs/sepang-primer.md`](docs/sepang-primer.md) | A primer on the circuit and the car |
| [`docs/log-format.md`](docs/log-format.md) | **The log format**: the contract any recorder (Android, iPhone, anything) must meet, and a checker, `analysis/check_log.py` |
| [`docs/stages.md`](docs/stages.md) | **Stages (1.4)** in plain words: what you do, the rule for open roads, how accurate, what I did not check |
| [`docs/stage-algorithm.md`](docs/stage-algorithm.md) | The stage method step by step with every constant, written so it can be re-implemented without my code; section 9 and 10: how well it works and how it was checked |
| [`docs/stage-format.md`](docs/stage-format.md) | The stage and run files, the extra rows in the log, the backup zip |
| [`docs/roadmap.md`](docs/roadmap.md) | What I'd build next: the stage limits, coaching on the phone, iPhone recorder, more circuits |
| [`android/`](android) | The Android app, its tests and its build scripts (no Gradle needed) |
| [`releases/`](releases) | The built APKs (1.2, 1.3 and 1.4), checksums, and what I checked about them |
| [`ios/`](ios) | Placeholder for the iPhone recorder; not started |
| [`analysis/`](analysis) | `analyze.py` (per-file channels), `ultimate.py` (ultimate lap, deltas, predictor), `powerlaws.py` (this project's power-law report), `check_log.py` (does a recording meet the format?), `regression_check.py` (did a change move any result?), `test_reader.py` (does the reader take every row the app writes?), `stage_compare.py` (stages on a computer, and the second implementation of the stage method), `stage_fixtures/` (the shared test files for it) |
| [`physics/`](physics), [`sim/`](sim) | The point-mass GT3 model, the simulator with planted driver errors that I used to test the engine, and `simulate_stage.py`, the Monte Carlo for the stage timer ([`sim/README.md`](sim/README.md)) |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | How to add to the project without breaking it |
| [`NOTICE.md`](NOTICE.md) | Third-party data and tools |

## Contributing and what comes next

This repository will keep changing, and I hope other people add to it. Two directions are already planned.

- **An iPhone version of the recorder.** Any recorder that writes the same CSV works with all the analysis. The contract is the file format
  ([`docs/log-format.md`](docs/log-format.md)), so an iOS app does not need to be a port of my Java: it needs to write the same rows.
- **Stages on an iPhone**, and everything else about stages that is still open (two limits of the method I found, a real-road test, closed-road display behind its own switch): [`docs/roadmap.md`](docs/roadmap.md) section 2. The method is written so that it can be re-implemented without reading my Java:
  start at [`docs/stage-algorithm.md`](docs/stage-algorithm.md) and hold your code to [`analysis/stage_fixtures`](analysis/stage_fixtures). **The rule for open roads stays**: while a stage is armed, recording or being marked and the car is not known to be stopped, no screen may show a time, a difference or a comparison, and nothing may prompt a driver to go faster.

Also wanted: other circuits (a course file instead of a constant in `LapTimer.java`), the on-phone debrief, and real sessions from other drivers and cars.
See [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Limits, plainly

One session, one phone, one car, one driver. No pedals, steering or traffic in the data. The corner positions come from open map data, not a survey. The 1.3 lap screen and the 1.4 stage screens have
not run on a phone, and no stage has been driven on a real road; the stage accuracy figures are from simulations (two of them: mine, and one by a second pass of the same model that never saw the app's code). I have not compared lap times or stage times with a transponder or a stopwatch. The advice in the report is a set of hypotheses for a driver to test cautiously, not instructions.

## Licence

No licence has been chosen yet; until one is, the default copyright rules apply, so others cannot legally reuse the code. That is the repository owner's decision, and with
contributors coming it is worth making soon. The third-party material in [`NOTICE.md`](NOTICE.md) keeps its own licences.

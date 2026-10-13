# Validation: what was tested, how, what it showed, and what was not

I wrote the app, the analysis and the simulator, so I did not want my own word to be the evidence. This page lists what each claim rests on. "Passed" here
means passed on the tests I describe, no more.

## In one paragraph

The **app's recorder** has run on a real phone once (a 44-minute Sepang session). The app's code, with the lap timer (1.3) and Stages (1.4) added, passes 324 automated checks on a PC (315, and 9 that replay the real log's GPS fixes through the lap timer and the stage code). The **analysis** was built and tuned on simulated sessions with known
answers, then met its first real log and had to change. Against the raw GPS in that log, lap and section times, slowest speeds and total speed lost under braking held up; exit speeds,
braking points and throttle pick-up are rough. There is **no ground truth yet** (no transponder, no car data). The 1.3 lap screen and everything new in 1.4 (the stage screens, the automatic start and stop, the beeps) have **not been run on a phone**, and no stage has been driven on a road or timed against a stopwatch.

## 1. Simulated sessions with planted mistakes

`sim/simulate_sepang.py` drives a simulated GT3 round Sepang with a quasi-steady-state lap solver, plants driver errors (braking points, corner speeds, throttle pick-ups, a few mistakes),
adds sensor noise, a random mounting angle, GPS delay, dropouts and outliers, and writes the same CSV the app writes, together with the exact truth.
`sim/run_val.py` runs the engine on it and compares. `sim/run_batch.sh N` simulates N sessions and prints the table below.

**First 11 sessions** (5 to 10 flying laps each, GPS error 2 to 4 m):

| What | Result |
|---|---|
| Section time error | 0.03 to 0.06 s rms |
| Ultimate lap, raw best-of-pieces | 0.10 s too optimistic on average (too fast) |
| Ultimate lap, noise-corrected | no bias on average (+0.001 s), but single sessions were 0.15 s too fast to 0.15 s too slow |
| Printed error bar (0.07 to 0.15 s) | the real error was larger than the bar in 3 of 11 sessions; error / bar has rms 0.69 |
| Choice of pieces | on average 0.07 s worse than the best mix a search found (worst 0.18 s) |
| Corner events (spread of the error lap to lap) | braking position 1 to 2 m at most corners (mean 1.9 m), slowest speed 0.27 km/h, exit speed 0.5 km/h, pick-up 0.5 m, peak brake 0.007 g |
| Lap-time prediction (best model, rms over 65 simulated laps) | 1.3 s with no look at the lap; 0.9 s at 20% of the lap, 0.5 s at 50%, 0.26 s at 80%, 0.06 s at 95% |

**A fresh batch of 8**, run after the engine had changed for the real log, with seeds the engine had never seen (`sim/run_batch.sh 8`; the output is stored in [`../sim/results/batch_b301-b308.txt`](../sim/results/batch_b301-b308.txt), and I re-ran it from a fresh clone of this repository on 13 Oct 2026 and got the same numbers):

| session | flying laps (loops found) | section rms | claim error | printed bar | error / bar | piece-choice regret | fastest loop vs truth |
|---|---|---|---|---|---|---|---|
| b301 | 8 (9) | 0.040 s | +0.010 s | 0.092 s | 0.11 | 0.036 s | -0.005 s |
| b302 | 6 (7) | 0.049 s | -0.011 s | 0.111 s | -0.10 | 0.071 s | -0.024 s |
| b303 | 10 (11) | 0.040 s | -0.095 s | 0.088 s | -1.07 | 0.053 s | -0.049 s |
| b304 | 8 (9) | 0.065 s | +0.031 s | 0.132 s | 0.24 | 0.194 s | +0.012 s |
| b305 | 7 (8) | 0.041 s | -0.021 s | 0.122 s | -0.17 | 0.059 s | +0.418 s |
| b306 | 9 (10) | 0.047 s | -0.023 s | 0.102 s | -0.23 | 0.050 s | +0.024 s |
| b307 | 8 (9) | 0.064 s | -0.020 s | 0.161 s | -0.12 | 0.005 s | +0.064 s |
| b308 | 10 (11) | 0.050 s | -0.095 s | 0.138 s | -0.69 | 0.053 s | +0.038 s |

Totals: section-time error **0.050 s rms**; the ultimate lap's claim error **-0.028 s on average, 0.051 s rms, 0.095 s at worst**; error / printed bar rms **0.47** (a bar that is one standard deviation
should give about 1, so these bars are conservative on simulated data); the choice of pieces was 0.065 s worse than the best mix on average (worst 0.194 s); the fastest usable loop differed from the true fastest by 0.031 s (median) and
0.418 s at most (b305, where the true fastest loop was one the engine could not use). The claim error is negative in six of eight sessions: the ultimate lap errs on the fast side by a few hundredths.

**What this does not show.** The simulator is my own model of Sepang and of a driver, so these numbers say the method works when its assumptions hold, not that it works on a real phone. Real laps vary
more than my simulated ones (traffic, mistakes, tyre changes), which is why the predictor was weaker on the real session.

## 2. The first real log (13 October 2026)

The analysis as delivered with version 1.2 produced unusable output on it. It expected a standstill at the start; the recording began at 172 km/h, and the phone then moved in its mount (9° median, up to 52°; tilt 6°).
What changed: a time-varying orientation fitted to the GPS, an accelerometer error model that follows its disagreement with the GPS, a lap line placed only where the fused speed and the GPS agree,
a printed check of fused speed against the GPS fixes, and, found by comparing corner by corner with the raw GPS, a sustained-peak brake rule, throttle pick-up and exit-speed guards, and an "unstable pick-up" flag.
Re-run on the 11 simulated sessions, the section results were unchanged (the aggregate moved by under 0.001 s) and the corner-event errors were equal or smaller.

**Cross-checks against the raw GPS in that log** (they cover one session, one phone and one car):

| Check | Result |
|---|---|
| Lap times with the timing line in three positions | agree within ±0.06 s (not compared with a transponder) |
| Slowest speed in a corner | within 1 to 2 km/h |
| Total speed lost in each braking zone | within about 5% |
| Speed 100 m after the apex | read 6 to 7 km/h low at the slowest corners (T2, T14, T15); lap-to-lap differences follow the GPS (correlation 0.67 to 0.96) |
| Fused speed against the GPS fixes | 1.05 m/s rms |
| Accelerometer against the GPS-derived acceleration | reads about 3% low |
| Sideways g: accelerometer against speed × gyro yaw rate at each corner's slowest point | median ratio 1.01; 0.94 (T2) to 1.11 (T7) at the eleven corners (`accel_over_gyro` in `reports/sepang-2026-10-13/tables/corner_speed_by_corner.csv`) |
| Sign of the accelerometer, from the gyro and the GPS bearing | the yaw rate about "up" falls with the GPS bearing, r = -0.92 (`analysis/check_log.py`) |

The curvature of the map polyline, which I first tried as a radius check, is useless at 1 Hz GPS and I dropped it.

## 3. An independent review of the engine

A second reader worked from the code alone and wrote their own test files. They reported 10 findings; I reproduced 7 on simulated data and changed the code or the report text for all 10:

- a GPS dropout inside the fastest lap made the program lose laps or draw a cut-short map and still print a report;
- the point where laps are cut could fall inside a dropout and merge two laps;
- minutes of the phone parked with the app running inflated the measured GPS noise and made the ultimate lap about 0.1 s too slow;
- corner windows were tied to the map's corner positions, so a corner 40 m from where the map puts it was measured wrongly (each reference apex now moves to where the laps run slowest);
- a "model apex" column and a "±8 to 10%" claim for the physics reference that the numbers did not support (the column is gone and the reference is described as a rough ceiling);
- a check of the circuit match that could not fail;
- a false "not enough clean loops" message when plots are switched off;
- thin data (two or three loops, or none clean through every section) that printed meaningless comparisons or "nan";
- the same recording given twice being counted twice;
- a braking-onset rule that a lift at high speed could trigger.

Each fix was re-run on the files that showed the problem; the braking-onset change was found by reading the code, so it has only been checked on the simulated sessions. The review did **not** cover real phone data,
other circuits, tyre or fuel drift, clock jumps in a recording, or the Android code.

## 4. The app

Run on a PC against fakes of the Android classes that I wrote (`android/tests`, needs only a JDK; `bash android/tests/run.sh all`): **315 required checks, all passing**, and every extra cleanup probe passing too; with `JAVA_EXTRA=-Dreal.log=<csv>` it also replays the real log's GPS fixes through the lap timer and the stage code and reproduces the 15 lap times of an independent calculation, which makes **324** checks
(for release 1.3 the counts were 136 and 140, re-run from a fresh clone of this repository on 13 Oct 2026; the 184 stage checks are new in 1.4 and are described in section 5). They cover the file format and row counts,
100 Hz timing, the calibration (also with an odd sensor clock, and with too little data), write errors and a full disk, 120 repeated stop cycles with a backlog, START pressed during a save, double taps, the system tearing the service down,
failed exports leaving no half-written file, the wake lock being released exactly once, and, for 1.3, the lap timer: lap times and the `LAP` rows on a simulated drive, a second session starting clean, and a drive that never passes the line.
Since 1.4 the two screens run in the tests against small fakes of the Android widgets they use (section 5): that checks what they show and what each button does, not how they look. The 1.3 lap display during an ordinary recording is still not covered by any test.

An earlier review of all the source files, for problems that only show up on a device (permission flow, foreground-service rules, the export to Downloads, time bases), found real bugs (for example, a START pressed during the save could end the new recording);
they are fixed in 1.2. The app compiles against the Android 13 and Android 10 interfaces (the second catches use of anything newer than the minimum), converts to dex, and the APK's signature, alignment and manifest were verified.

On a real phone (Galaxy S25, Android 16), 1.2 ran the permission flow, a 44-minute recording with 118 Hz sensors and 1 Hz GPS, and the export to Downloads.

## 5. Stages (release 1.4)

Stages time a stretch of public road, so I tested them harder than anything else in the app: a time that looks right and is wrong is worse than no time. Everything below ran on a PC. **No stage has been run on a phone, driven on a road, or timed against a stopwatch or a second device.**

**The Java tests: 184 of the 324 checks.** They run the real stage classes, the real recorder and the real service against fakes of the Android classes, with a virtual clock and synthetic roads whose geometry, and so the true time between two lines, is known exactly.

| Group | What it checks |
|---|---|
| Line crossing | the crossing time is exact to 0.1 ms on a straight road at 400 random speeds and phases, within 3 ms on a 60 m bend, exact on a standing start; a car going the other way, a parked car jittering, a car moving along the line or 30 m off its centre is not counted |
| Making a stage | the lines land 20 m in from the marks (within 0.5 m) and their directions follow the road within 2 degrees; finish before start, a mark while moving, a 200 m drive and a loop are each refused with a sentence |
| Timing a run | noise-free runs within 20 to 30 ms; 300 rolling runs with 2.5 m of GPS noise, 500 standing starts and 500 starts with the car parked 10 m short, with 3 m (the table in [`stage-algorithm.md`](stage-algorithm.md) section 9); a start line moved 5 m; a false start; a finish line met at 32 % of the road, which is ignored; a 12 s GPS gap; hairpin roads; the live run and the offline scorer giving the same answer to the last bit; duplicated and shuffled fixes; the 5 s after the finish; the fail-safes for an approach that never starts and for a car that stops for 20 minutes |
| Comparing | the sections add up to the total; best, previous and first; runs of different stages refused; the section length rule |
| Files | stages and runs written and read back; cut-off, junk and leftover files skipped; an id with a path in it refused; rename and delete; the log reader against a plain line-by-line reader on 432 texts, with a 600-character row limit, a cap of 600 000 fixes, empty, text and binary files, and the real 27 MB recording |
| Backup and restore | the same bytes every time; byte-for-byte import into an empty phone; merging and "already here"; the same id on a different road; 13 hostile entry names; a file that expands past 5 MB, 5001 entries, 36 MB in all, a cut-off zip; no half-written file left behind |
| The service | arming and disarming; recording that starts by itself only for a moving car within 400 m of the start line; the beeps; giving up an approach; "did not finish"; a whole day of runs leaving no wake lock, listener or thread behind; the system destroying the service in the middle of a run (the recording is closed properly); the stage rows in the log, in order |
| The screens | 27 checks of the two screens against fakes of the widgets: the words of every state, each button, a random walk of 4000 steps. And one rule across all of it: in every state **no screen showed a time, a speed or a difference while the car was moving** (74 screens looked at in 8 states, and every armed, recording and marking flow) |
| The real Sepang log | the gate reproduces the lap timer's lap times to the last digit and the independent Python calculation's to 0.5 ms; a stage cut out of lap 3 is found at all 16 passes of that stretch, and each of the 16 times equals the difference of two crossings found by hand. These need the log, which is why there are 315 checks without it |

The fakes do what I believe Android does, and the roads, the driver and the GPS noise are mine. These checks say the code does what I meant; they cannot say what I meant is right on a road.

**A second implementation, written from the method alone.** [`analysis/stage_compare.py`](../analysis/stage_compare.py), and [`sim/simulate_stage.py`](../sim/simulate_stage.py) on top of it, were written by another instance of the same model that was given [`stage-algorithm.md`](stage-algorithm.md) and the two format pages and never opened the Android code. `python analysis/stage_compare.py selftest` recomputes every expected result in [`analysis/stage_fixtures`](../analysis/stage_fixtures) (18 groups: 400 line-crossing cases, 35 file names, 4 refusals, 3 stages, 25 runs, 32 comparisons, the real-log part, the bundle reader, the file formats): crossing times to a microsecond, stage times and profiles to a millisecond, run ids to the character; the 28 stage and run files it writes are byte for byte the expected ones. What came out of it:

- It had to guess how to read the method in **five places**. The page now says what the Java does there, and the fixtures hold both to it.
- Its bundle reader was first stricter than the app's (it required the top folder and allowed 64 MB where the app allows 32), and its run ids differed from the Java for odd file names (no hash, a case-insensitive ".csv"). I brought both in line with the Java, and all 35 test names now give the same ids.
- It is **not** an independent check of my reasoning: it is the same model reading my page. A mistake I would make on both sides, or a wrong belief about GPS that we share, would pass. To see how much the fixtures are worth, its author broke the Python in 55 one-line ways; the fixtures noticed 25. The other 30 are rules that no fixture exercises. They are listed in [`stage-algorithm.md`](stage-algorithm.md) section 10, and fixtures for them are on the [roadmap](roadmap.md).

**Simulation of the whole method.** `sim/simulate_stage.py` builds synthetic roads (a mountain road, hairpin roads), many stages per case with a new marking drive for each, drives them with 1 Hz fixes and correlated noise, and compares the stage time with the true time between the lines (seeds 1 to 3, which I ran myself). The error of one stage time, 1 sigma: 0.17 to 0.18 s with rolling ends at 20 m/s and 0.11 to 0.12 s at 30 m/s; 0.39 s for a standing start with a braking stop, and 0.44 to 0.48 s when the car is parked 10 m short of the start line. A start line moved 5 m changes the time by 0.166 to 0.167 s (5 m at 30 m/s); a 12 s GPS gap leaves the stage time unchanged. The Java tests' numbers beside these are in [`stage-algorithm.md`](stage-algorithm.md) section 9. They fit, to within about 10 %, an error of g x sqrt(1/v_start^2 + 1/v_finish^2), where g is the GPS noise in metres (2.5 to 3 m in these runs) and the speeds are those at the two lines in m/s: **slow roads are timed worse than fast ones.** (The app assumes g = 3 m, which is up to 20 % pessimistic where the simulated noise was 2.5 m.)

**Two limits the simulation found** that my own tests had not looked for. I have *not* fixed them in 1.4; both are in the stage documents and on the roadmap.

1. *A finish reached by braking to a stop can be missed.* The finish line can lie only a few metres before the stopping place, and a crossing at under 1.5 m/s is not accepted. On seeds 1, 2 and 3, 3, 0 and 25 of about 500 simulated runs (the 25 on one stage) were scored as "did not finish". The advice: roll through the finish and stop beyond it.
2. *Tight stacked hairpins at a line.* With legs 16 m apart, 0 and 6 of 100 runs were wrong by more than 5 s; at 12 m, 29 and 63 of 100; at 30 m and 20 m none. The advice: mark the start and finish away from tight bends. A check in the stage builder is next.

**Two things checking my own work found.** The first stage recording I ran through `check_log.py` made it report "3 unknown or malformed rows": the reader dropped every event whose values are words (a stage id, a reason). `analyze.py` is fixed and `python analysis/test_reader.py` (11 checks, runs in under a second, in the CI workflow) fails without the fix. And reading my own documents against what the tests had printed turned up wrong statements: an accuracy quoted at the wrong speed, a formula that did not match the simulation, a wrong count in a format example and a wrong position for a stage's first vertex. They are corrected; the numbers in the stage pages are now the numbers the tests printed.

## 6. Odd files

Also on a PC: empty files, files cut off mid-line, no GPS, GPS dropouts of 10 to 90 seconds at many places (also inside the fastest lap), the phone parked for up to five minutes with the app running, the same file given twice, a file with every row doubled,
only two, three or four loops, damaged ("nan") values, Windows line endings, a mirrored or relocated circuit, a phone mounted at another angle, a file with one usable loop, 17 randomly damaged variants (missing sensors, cut-off files, dropped, duplicated and shuffled rows),
and a file name with Chinese characters on a machine whose default text encoding is cp1252 (emulated). Each gives a report or a plain message; none crashed. Faults found this way and fixed: a crash on "nan" values, a lone out-lap reported as a flying lap with an invented gain,
a crash writing the report for a non-Latin file name, and the dropout and parked-phone cases above. The analysis has not been run on Windows or macOS themselves.

## 7. What the power-law layer rests on

`analysis/powerlaws.py` is newer than everything above and was checked more lightly: its numbers were compared with the tables it writes and with the engine's own events, a first version of the biggest-losses ranking was found to blame GPS gaps (and replaced
by the "rated window" rule), a first version of the exit-speed table used windows containing other corners (now flagged and excluded), and the numbers in `reports/sepang-2026-10-13/REPORT.md` were re-checked against `numbers.json`, the tables and the engine's report before publication
(which found and fixed several errors of wording and counting). Its only automated test is `analysis/regression_check.py`, which re-runs it (and `ultimate.py`) on the committed log and compares every output file with the committed one. That catches a change, not a mistake: I checked that it fails when I move the braking-onset threshold of the engine by 0.04 g, and it says nothing about whether the numbers are right.

## 8. Not tested

- the 1.3 lap screen on a phone; the foreground service on a locked screen under other makers' battery managers; other phones, cars and circuits; the analysis on any session beyond the first;
- **stages (1.4), all of it on a phone**: how the two screens look and whether their buttons can be pressed in a car; what Android does to the service when the screen has been off for an hour; whether the beeps can be heard over a running car (they use the media volume); the time to the first GPS fix after arming; GPS under trees and in cuttings; what happens when Android kills the app in the middle of a run (that run is not saved; the recording up to that point is);
- **a stage driven twice on a real road**, with a stopwatch, a second device or a transponder at the lines: nothing has been compared with a time taken by anything else, and the simulations share the assumptions of the method. The first real stage is the first test of the whole chain;
- restoring a backup on a phone (stages are deleted when the app is uninstalled, so a backup is the only copy), and the two limits of section 5 that are known and not yet fixed;
- the braking-point numbers against a known truth: they have been checked for repeatability only;
- the corner positions (open map data and published corner sequences, not a survey): they matched the real log closely enough for the 15 corners to be numbered;
- the physics reference: brochure figures give a perfect lap of 2:25.2 against a real best of 2:33.07, so it is a rough, unvalidated ceiling; calibrated to what the phone measured on the straights and under braking, it brackets the real laps (2:29.7 to 2:37.3, see the report, section 8) but cannot say where inside the range they sit.

## 9. Open questions

- Why the straights show about three quarters of the brochure's push at the wheels (a heavier car, hot humid air, part throttle, the sensor, or a mistake of mine).
- Why the laps' exit-speed slopes are a third to a half of the physics.
- Whether T9 and T15 really leave grip unused or something else limits them.
- How a transponder's lap times compare with the phone's.

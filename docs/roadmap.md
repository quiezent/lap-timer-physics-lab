# Roadmap

What I would build next, in the order I would build it, and what each piece needs. Nothing on this page exists yet unless it says so. Items marked **(owner)** are the repository owner's to decide.

## 1. An iPhone recorder

**Goal.** An iOS app that records a drive to the same CSV, so every analysis script works on iPhone sessions unchanged. It does not have to be a port of the Java: it has to write the rows in [`log-format.md`](log-format.md).

**What it needs.**
- Accelerometer and gyro at 100 Hz, GPS at 1 Hz or better, one shared clock, a file written as it goes (so a crash costs a second, not a session), running with the screen off and the app in the background.
- Conversions: iOS reports acceleration in g with the opposite sign, so `A = -9.80665 × CoreMotion value` (details and the other fields are in the "iPhone recorder" notes of the format page).
- The same safety posture: mounted rigidly, no interaction while driving.

**Acceptance.** `python analysis/check_log.py session.csv` reports no FAIL; a phone lying flat reads +9.81 on z; `python analysis/analyze.py` runs on any drive of it, with `Forward axis ... R2` near 1.00; on a Sepang drive with enough clean loops `analysis/ultimate.py` and `analysis/powerlaws.py` run too; on a drive that has also been recorded by the Android app, the lap times agree.
Start in [`../ios/`](../ios). Please share a first real file early: the checks in `check_log.py` came from my guesses about what goes wrong, and a real iPhone file is the best test of them.

**Later.** Port the gate timer (section 2) with shared test vectors so that Android and iOS compute the same lap times from the same GPS fixes.

## 2. Rally and road stages: start and finish GPS points

Today's timer is for laps: one built-in line, laps timed between two passes of the same line. A stage is point-to-point: it starts at one place and ends at another, and the time is the difference.
The owner wants to add these, with the start and finish given as GPS points. Here is how I would do it, and why it is different from laps.

**Use.** Closed-road rally stages, hill climbs, time attacks and other permitted events, and test days on closed roads. Not open public roads: timing yourself on a public road invites speeding, and the project is not for that.

### The course file

A small JSON file that works on any platform (a built-in list in the app, plus import from the phone's Downloads folder):

```json
{
  "format": 1,
  "name": "Example hill climb",
  "type": "stage",
  "start":  { "lat": 0.0, "lon": 0.0, "bearing": 90.0, "half_width_m": 12 },
  "finish": { "lat": 0.0, "lon": 0.0, "bearing": 85.0, "half_width_m": 12 },
  "splits": [ { "name": "Split 1", "lat": 0.0, "lon": 0.0, "bearing": 80.0, "half_width_m": 12 } ],
  "surface": "tarmac",
  "notes": "start and finish marked by driving over them, see docs/roadmap.md"
}
```

A **gate** is a line segment centred on a GPS point, perpendicular to `bearing` (the direction of travel), `half_width_m` to each side. A crossing counts only in the direction of `bearing`.
Sepang's existing line is the same thing (latitude 2.760726, longitude 101.738380, bearing 265°, half-width 40 m) and would move out of `LapTimer.java` into a circuit course file (`"type": "circuit"`, one `"line"`), which also makes other circuits a data change, not a code change.

### Timing

`LapTimer` already finds the crossing time by interpolating the path through the GPS fixes (a Hermite curve through position and velocity, sampled every 10 ms). The plan is to pull that into a `GateTimer` that reports the interpolated time of each gate crossing,
and build both timers on it: `LapTimer` (one gate, a lap is the time between consecutive passes) and `StageTimer` (a state machine: **armed** at the start, **running** after the start gate, optional **splits**, **finished** at the finish gate, then re-armed for the next run).
Each run writes `E,t_us,STAGE_START,name,run`, `E,t_us,SPLIT,name,run,seconds` and `E,t_us,STAGE_FINISH,name,run,seconds` into the CSV.

### Why the lines matter more for a stage than for a lap

A lap is the time between two crossings of the **same** line, so if the line is placed 5 m too far along the road, both crossings shift by the same amount and the lap time barely changes. A stage is timed between **two different** lines, and an error in the position of
either one goes straight into the result: `error in seconds = error in metres / speed`. At 30 m/s, **1 m of line error is 0.033 s and 5 m is 0.17 s**. It is the same linear physics as everything else in this project, with a less forgiving consequence.
So the start and finish points need to be good to about a metre, which a phone's 1 Hz GPS (3 to 4 m typical error) does not give by itself. Ways to get there:
(a) use points the organisers or a survey provide; (b) mark each by driving over it slowly with the phone, averaging a few passes (the random error shrinks with the square root of the passes); (c) place the gate on a physical feature (a painted line, a gantry) and place it on a map with care.
Even then, the random error of each GPS crossing (roughly the reported accuracy divided by speed, so 0.1 s or more) stays; the engine's habit of using the integrated speed between fixes improves on it. Expect a phone to time a stage to a tenth or two, not a hundredth, and never as an official result.

### Analysis for stages

Most of the machinery carries over; what changes:
- **No loop.** Runs are aligned by distance along the route from the start gate. The route comes from the best run without a GPS gap, as the circuit map does today; `analyze.py` already finds and labels corners from the laps themselves (C1, C2, ...), which is the starting point.
- **Sections** are cut at the long straights as now, or at fixed distances where the road has none, and the "ultimate stage" is built the same way (best section of each kind, speed carried over each join, shrunk toward typical, with an error bar).
- **The power-law report** (braking in speed², corner speed against radius, what an exit is worth, where the time went) runs unchanged on the corners found in the route.
- **Runs are rare.** A stage may be driven once per event, so the comparison is across runs and days, where tyres, weather and surface change. The loss-concentration and ultimate-stage ideas need care there, and the first sessions will mostly be a driver against their own earlier runs.
- **Low grip changes the numbers, not the laws.** On gravel the grip is about half (perhaps 0.5 to 0.7 g), so braking distances roughly double and corner speed falls with the square root of grip, but a braking zone is still a straight line in speed² and corner speed still goes as `√(a r)`. The fitted `a` is the surface.
- **Climbs and crests.** A hill climb adds gravity to the straights (`a = P/(m v) − k v² − g sin θ`); crests and jumps break the "accelerometer reads about 1 g vertically" assumption the orientation fit relies on. Both need work.
- **Pace notes, co-driver and official timing are out of scope.**

### Steps and tests

1. The course file format and a reader in Java and Python (`analysis/course.py`).
2. `GateTimer`, and `LapTimer` rebuilt on it with the existing checks (136, or 140 with the real log replayed) still passing.
3. `StageTimer` and a stage screen (course picker, armed, running time, splits, finish, delta against the best run).
4. The new `E` rows in [`log-format.md`](log-format.md) and in the analysis.
5. A point-to-point mode in `ultimate.py` and `powerlaws.py`.
6. Tests: a simulated stage in `sim/`, timer tests for crossings at different speeds, GPS noise, wrong-direction crossings and re-arming, and a test of the "1 m is 0.033 s at 30 m/s" rule.

**Questions for the owner (owner).** Which events first (rally stages, hill climbs, time attacks)? Are the start and finish points known, or do they have to be marked by driving? What surface? How long are the stages and how fast? Will there be more than one run of the same stage?

## 3. More circuits

Today the circuit is Sepang, from one data set (see [`../NOTICE.md`](../NOTICE.md)). `analyze.py` already labels the corners itself from the laps (C1, C2, ...) without a circuit model, but I have not run `ultimate.py` or `powerlaws.py` on another circuit, only on relocated and mirrored copies of Sepang. A proper step: a course file for each circuit (the gate, the corner list, the straights) and a small script that builds
`sepang_model`-style data for another circuit from the TUMFTM racetrack database, which covers many circuits.

## 4. The coaching on the phone

First a **pit debrief** screen: the ultimate lap and its gap, the three biggest gaps by corner, the braking-point spread at each heavy zone, and one question the data raises (the concept is in the top-level README). Three ways to get there:
(a) port the engine to Java: it works anywhere and is a large job (the speed fusion and the alignment are not small); (b) produce a small `debrief.json` on a laptop and open it in the app: small, but needs a laptop at the track; (c) compute in the cloud: needs internet, which the app deliberately does not have.
I would start with (b), because it tests whether the screen is useful before the heavy work. A glanceable live delta comes only after that, and only if a driver wants it.

## 5. Engineering

- **Automated tests in CI.** `.github/workflows/checks.yml` runs the Java harness (it needs only a JDK), `check_log.py` and `analysis/regression_check.py` (the analysis must still give the committed results on the committed log) on every push and pull request. It passes on GitHub's runners (13 Oct 2026); the regression check first failed there on last-bit float noise from a different CPU, and was changed to tolerate that. Still missing: unit tests for the power-law layer, a simulator smoke test that is quick enough for CI, and the same checks for an iPhone port.
- **A format version in the header** (`# format,1`) written by recorders and checked by the analysis, so the format can grow without breaking old files.
- **Shared test vectors** (GPS fixes and the lap times they must give) for the timers on each platform.
- **A licence (owner).** Contributors need to know the terms. See the top-level README.

## 6. Data I'd most like

- More sessions from the same driver, to turn "moderate" findings into yes or no.
- Other drivers, cars and circuits.
- A session with a transponder or the car's own speed and brake data alongside the phone, to give the braking points a ground truth.
- A rigid mount and a standing start.
- A full-throttle straight-line run and the car's weight, to settle why the straights show about three quarters of the brochure's power.
- Enough laps to say whether the time lost follows a power-law tail.

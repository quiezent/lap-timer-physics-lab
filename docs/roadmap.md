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

**Built in release 1.4** (open roads, limited by design; the plain-words guide is [`stages.md`](stages.md), the exact method is [`stage-algorithm.md`](stage-algorithm.md), the files are [`stage-format.md`](stage-format.md)).
What is done: marking a start and a finish by driving the road once; arming a stage so the recording starts and stops at the lines by itself; saving every drive as a run; comparing runs of the same stage (best, previous, first), with a graph and sections; scoring recordings made before the stage existed;
backup and restore of stages; the rules for open roads (no times or differences on screen while the car moves; one beep at the start line, two at the finish line, a low tone if a run is given up; a first-use text about road rules).
It does not use the course file sketched here earlier: a stage is a text file made on the phone from the marking drive ([`stage-format.md`](stage-format.md)), which turned out to be what the driver needed (no map, no typing of coordinates).

**Built in release 1.5** (an interim release, made when the 1.4 way of marking turned out not to suit public roads: it needs a safe place to park at both ends). Two ways to make the same kind of stage without parking:
- **Drive first, pin afterwards.** Record the road as usual, then open the recording on the phone, see the line the car drove, and put a **Start pin** and a **Finish pin** on it (a slider and nudge buttons, or a coordinate). The phone checks the pair against nine rules (the pins are the right way round, not at the ends, 300 m apart or more, not a loop, not in a gap in the GPS, not across a break, the line not crossed twice) and says in words which one fails.
  The method is section 11 of [`stage-algorithm.md`](stage-algorithm.md) and is held to a second implementation (`analysis/pin_compare.py`) and to shared fixtures (`analysis/stage_fixtures/pins`).
- **Plan from map coordinates.** Copy the start and the finish from Google Maps (or any map: a link, `3.5683, 101.7461`, degrees-minutes-seconds, a `geo:` link) and paste them in; the plan is saved with a name. A plan is a stage without a road: when you have driven the road, **Make the stage from a recording** puts the two pins at your two places on that drive.
After one of these a stage is an ordinary stage: arm it and the recording starts and stops at the lines by itself, as in 1.4.

**Release 1.6, next: arm a plan.** Today the plan waits for a recording that you start and stop by hand. In 1.6 you arm the plan, drive, and the phone records by itself from the start place, notices the finish place, builds the stage from that first drive and saves it as the first run (section 11.9 of the method page describes it; it is **not built yet**, and none of the `PLAN*` rows of [`stage-format.md`](stage-format.md) is written by 1.5).

**What is still open in this section.** The first three are things the independent Python check found in the method (details and numbers: [`stage-algorithm.md`](stage-algorithm.md) sections 9 and 10), in the order I would do them:
- **A guard against tight stacked hairpins at a line, for stages marked by parking (1.4).** A line is 50 m wide; where the road doubles back twice within 25 m of the start or finish line, a run can cross the line on the wrong leg and its time is wrong by tens of seconds (in simulation: legs 16 m apart, 0 to 6 runs of 100; 12 m apart, 29 to 63 of 100).
  **Done in 1.5 for stages made with pins** (section 11.6 of the method page: the finished stage is refused, in words, if the road crosses either line again). Still open for a stage marked by parking at both ends: the same check could run on the marking route when the stage is saved, so the driver marks the end a little way from the bends. A run-time alternative is to demand that the progress along the route at the finish crossing be close to the finish line's position, not just 70 % of the route.
- **A finish by braking to a stop (stages marked by parking).** The lines come out 15 to 18 m of road from the marks, not 20, and in one simulated stage the finish line was 5 m before the stopping place, so every run crept over it under the 1.5 m/s limit and was scored *did not finish* (25 of 25; across three seeds 28 of about 1500 such runs). Options: place the finish line from the smoothed route; or accept the crossing when the car is moving faster than 1.5 m/s at *either* fix and fall back to the last fix before the car stopped; or say so when the marking drive's own first run does not finish.
  A stage made with pins puts its lines exactly at the pins, so the driver can put the finish pin where the car is still moving; the same rule for the crossing speed applies, and the pin screen does not yet warn when the pin is where the car was slower than 1.5 m/s.
- **Fixtures for the rules no fixture exercises yet.** The mutation probe on the Python code left 30 of 55 one-line breakages unnoticed, so 30 rules of the method page are not held against a second implementation: the latitude bound, equal times, a bearing of 0, a fix exactly on the line, `across` = `half`, speed = 1.5, forward = 0.25, the 16-step scan, the `hAcc` > 30 skip, replace-versus-append of the finish vertex, the 0.01 m route margin, the heading penalty,
  the 40 m window, the 60 m lost limit, `sNow` never decreasing, the 70 % and 10 s finish rules, the 5 s POST, the 300 s false-start limit, the 3 s near rule, the last profile knot, the worst-`hAcc` start value, skipping the first fix, the 12-section rule, the 12 m/s unknown speed, the 3 m/s floor. Each needs a small synthetic recording, an expected result from the Java, and a line in `stage_fixtures/README.md`; if the Python then disagrees, the page was vague again.
- **Fixtures for the pin rules no fixture exercises yet (1.5).** The one-line-breakage test of the second implementation of section 11 left 143 of 422 breakages unnoticed by the shared files: the exact limits of the hole rule, the order of the rules when two fail together, an infinite position, the order of the pairs, the windows of the guard, eight white-space characters and a few more details of the reader. The list, with a recipe for each, is [`../analysis/stage_fixtures/pins/GAPS.md`](../analysis/stage_fixtures/pins/GAPS.md). Section 11.9 (the first drive of a planned stage) has not been implemented by anyone, so it has no second reading yet.
- **Not tested on a phone, and not tested on a real road.** The checks ran on a PC against synthetic roads and the real Sepang log. A stage driven twice on a real road with a stopwatch or a second device at the lines is the first thing to do; until then the accuracy numbers are what a simulation says.
- **Splits** (named intermediate lines) are not in 1.4; the comparison cuts the road into equal sections instead.
- **Closed roads and permitted events.** 1.4 shows nothing live, because the driver's roads are open roads with traffic. For a closed stage, a hill climb or a track day a live delta against the best run would be the natural next step, behind its own switch and not shown on a public road. It is not built.
- **Analysis of stages on a computer.** `analysis/stage_compare.py` scores, builds and compares; the power-law corner analysis (`powerlaws.py`) and the "ultimate stage" (best section of each kind stitched together) have not been ported to point-to-point roads. The loss-concentration and ultimate-lap ideas need care for stages: runs are rare, and tyres, weather and traffic change between them.
- **Low grip, climbs and crests.** On gravel the grip is about half (perhaps 0.5 to 0.7 g), so braking distances roughly double and corner speed falls with the square root of grip, but a braking zone is still a straight line in speed² and corner speed still goes as `√(a r)`; the fitted `a` is the surface. A hill climb adds gravity to the straights
  (`a = P/(m v) - k v² - g sin θ`), and crests break the "accelerometer reads about 1 g vertically" assumption the orientation fit relies on.
- **Pace notes, co-driver and official timing are out of scope.**

### Why the lines matter more for a stage than for a lap

A lap is the time between two crossings of the **same** line, so if the line is placed 5 m too far along the road, both crossings shift by the same amount and the lap time barely changes. A stage is timed between **two different** lines, and an error in the position of
either one goes straight into the result: `error in seconds = error in metres / speed`. At 30 m/s, **1 m of line error is 0.033 s and 5 m is 0.17 s**. It is the same linear physics as everything else in this project, with a less forgiving consequence.
That is why 1.4 puts both lines 20 m inside the marks (a car pulling away from rest is already doing about 40 km/h there) and uses the same two lines for every run of a stage: a placement error is then shared by all the runs and cancels in a comparison, which is what the driver wants stages for. It is not a good way to get an absolute stage time to a hundredth, and nothing here claims to.

## 3. More circuits

Today the circuit is Sepang, from one data set (see [`../NOTICE.md`](../NOTICE.md)). `analyze.py` already labels the corners itself from the laps (C1, C2, ...) without a circuit model, but I have not run `ultimate.py` or `powerlaws.py` on another circuit, only on relocated and mirrored copies of Sepang. A proper step: a course file for each circuit (the gate, the corner list, the straights) and a small script that builds
`sepang_model`-style data for another circuit from the TUMFTM racetrack database, which covers many circuits.

## 4. The coaching on the phone

**Release 1.7, after the stages are settled (1.5 and 1.6), in the order the driver chose.** (a) *Sepang from earlier sessions:* load one or more previous Sepang sessions on the phone as the baseline, so that the lap predictor and the comparison work from the first lap of a new session and not after a few laps of collecting data
(the engine already takes a prior from other sessions; the work is carrying it to the phone). (b) *A Coaching button on the circuit screen:* predicted lap time and a live delta against the best-of-sessions lap from lap 1, with a debrief after the session. For the track only: stages on open roads stay as in section 2.

The rest of this section is the older thinking about the debrief screen. First a **pit debrief** screen: the ultimate lap and its gap, the three biggest gaps by corner, the braking-point spread at each heavy zone, and one question the data raises (the concept is in the top-level README). Three ways to get there:
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

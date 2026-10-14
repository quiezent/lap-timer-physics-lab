# Stages: the exact method

This page says, step by step and with every constant, how the app turns GPS fixes into a stage and a stage time, and how it compares two runs. It is written so that **it can be re-implemented without reading the Java**:
`analysis/stage_compare.py` and `sim/simulate_stage.py` were written from this page and the two format pages alone by a separate pass (another instance of the same model; it never opened the Android source). It reproduced all 28 stage and run files of the fixtures byte for byte
and found no disagreement with the app. It did have to guess how to read this page in five places, which the fixtures then settled; those five are now spelled out below (marked **[fixed after the cross-check]**), and what the check could and could not catch is in [`validation.md`](validation.md).
Anyone building the iPhone version should start here, and compare their output with the fixtures in [`../analysis/stage_fixtures`](../analysis/stage_fixtures).

The plain-words version is [`stages.md`](stages.md); the file formats are [`stage-format.md`](stage-format.md); the session CSV is [`log-format.md`](log-format.md).
Release 1.5 adds a second way to make a stage, for roads where there is nowhere safe to park at the ends: put two **pins** on an ordinary recording of the drive. It is section 11 below; sections 5 to 8 (following a run, comparing) apply to both kinds of stage unchanged.
The Android classes that implement each section are named in brackets, only so a reader knows where to look; nothing here depends on them.

Conventions: times are seconds as 64-bit floats; distances metres; angles degrees unless a function says radians; compass bearings run clockwise from north in `[0, 360)`.

## 1. Fixes

A **fix** is `(t, lat, lon, speed, bearing, hAcc)`: `t` is the time the GPS made it; `speed` m/s over ground, `bearing` the direction of travel, `hAcc` the reported accuracy in metres; the last three are negative when the phone did not give them. (`Fix`)

From a session CSV (`LogReader`): every line that starts `L,` and has exactly 12 comma-separated fields gives one fix, with `t = t_us / 1e6` (field 2, an integer, divided by 1 000 000), `lat` = field 3, `lon` = field 4,
`speed` = field 6, `bearing` = field 7, `hAcc` = field 8 (fields counted from 1, the kind letter being field 1). A line whose numbers do not parse is skipped. The header line `# start_utc_ms,<integer>` gives the wall-clock time of `t = 0`.
A fix is **usable** if `t`, `lat`, `lon` are numbers, `|lat| <= 90` and `|lon| <= 180`; unusable fixes are dropped.

Before scoring, the usable fixes are sorted by `t` (stable) and any fix whose `t` is not greater than the one before it is dropped (the first of equal times wins).

## 2. The flat frame and the placed fix (`Geo`, `Pt`)

Around an origin `(lat0, lon0)`: `x(lon) = radians(lon - lon0) * R * cos(radians(lat0))`, `y(lat) = radians(lat - lat0) * R`, `R = 6378137`.
The inverse: `lat(y) = lat0 + degrees(y / R)`, `lon(x) = lon0 + degrees(x / (R * cos(radians(lat0))))`.
`bearing(dx, dy) = degrees(atan2(dx, dy))`, plus 360 if negative. `angleDiff(a, b) = d if d <= 180 else 360 - d` with `d = |a - b| mod 360`.

A fix is **placed** in the frame as `Pt(t, x, y, vx, vy, speed)` given the previous placed fix `prev` of the same pass (none for the first):
- if the fix has `speed >= 0` and `bearing >= 0`: `vx = speed * sin(radians(bearing))`, `vy = speed * cos(radians(bearing))`;
- otherwise, if `prev` exists and `t > prev.t`: `vx = (x - prev.x) / (t - prev.t)`, `vy = (y - prev.y) / (t - prev.t)`;
- otherwise `vx = vy = 0`;
- `speed` of the `Pt` is the fix's speed if it is `>= 0`, otherwise `hypot(vx, vy)`.

## 3. A timing line (`Gate`)

A **gate** is `(cx, cy, bearing, half)`: a segment centred on `(cx, cy)`, perpendicular to `bearing`, `half` metres each side (25 m for stages), counted only for a car travelling along `bearing`.
With `ux = sin(radians(bearing))`, `uy = cos(radians(bearing))`:
`along(x, y) = (x - cx) * ux + (y - cy) * uy` (negative before the line, positive after), `across(x, y) = -(x - cx) * uy + (y - cy) * ux`.

**`crossing(a, b, minSpeed, minForward)`** for two consecutive placed fixes `a` then `b` returns the time at which the car crossed, or *none*:

1. `q0 = along(a)`, `q1 = along(b)`. Require `q0 < 0` and `q1 >= 0`.
2. Require `max(a.speed, b.speed) >= minSpeed`.
3. If `minForward > 0`: let `f` be `a` if `a.speed >= b.speed` else `b`; `vn = hypot(f.vx, f.vy)`; require `vn > 0` and `(f.vx * ux + f.vy * uy) / vn >= minForward`.
   (This uses the velocity the phone reported, not the step between the two fixes: a car braking to a stop moves less between fixes than the GPS noise, and the step then points anywhere.)
4. `dt = b.t - a.t`; require `dt > 0`.
5. The path between the fixes is the cubic Hermite curve through both with the reported velocities at the ends: for `u` in `[0, 1]`, with
   `h00 = 2u³ - 3u² + 1`, `h10 = u³ - 2u² + u`, `h01 = -2u³ + 3u²`, `h11 = u³ - u²`:
   `X(u) = h00 * a.x + h10 * dt * a.vx + h01 * b.x + h11 * dt * b.vx`, and the same for `Y(u)` with `y` and `vy`.
6. Find the first sign change: `prev = along(X(0), Y(0))`; for `i = 1 ... 16`: `u = i / 16`, `q = along(X(u), Y(u))`; if `prev < 0 and q >= 0` then `lo = (i - 1) / 16`, `hi = u`, stop; else `prev = q`.
   If no sign change was found, `lo = 0`, `hi = 1`.
7. 40 bisections: `mid = (lo + hi) / 2`; if `along(X(mid), Y(mid)) < 0` then `lo = mid` else `hi = mid`. `u = (lo + hi) / 2`.
8. If `|across(X(u), Y(u))| > half`, *none*. Otherwise the crossing time is `a.t + u * dt`.

Stages use `minSpeed = 1.5` m/s and `minForward = 0.25`. The lap timer's line uses `minSpeed = 3.0` and no forward test (`minForward = 0`); the gate agrees with the lap timer to the last digit on the real Sepang log.

## 4. Making a stage from a marking drive (`StageMarker`, `StageBuilder`)

**Marking.** The driver parks at the start and presses MARK START; the app accepts the mark only when the last 4 fixes all have `0 <= speed <= 0.8`, `0 <= hAcc <= 12`, lie within 6 m of their average, and the newest fix is at most 3 s old.
The mark is the average position of up to the latest 5 such fixes (averaged in the flat frame at the newest fix, then converted back), and the mean of their `hAcc`. MARK FINISH is the same at the end. Both are written to the CSV as `MARK_START` / `MARK_FINISH` events.
The fixes that arrived after MARK START, up to MARK FINISH, are the **drive**.

**Building** from `start = (startLat, startLon)`, `finish = (finishLat, finishLon)` and the drive:

1. The frame has its origin at `start`, **as measured: the unrounded average, not the 7-decimal value the file will hold**. `(fx, fy)` is the finish mark in that frame. All the arithmetic below is done in that frame; the roundings of the file come only at the end (last paragraph of this section). **[fixed after the cross-check]**
2. **Moving part.** `i0` is the first and `i1` the last usable fix of the drive with `speed >= 1.5`. If there is none: *refused* ("the car did not move between the two marks").
3. **Vertices.** Start with `[(0, 0)]`. For the fixes `i0 ... i1`: skip a fix with `hAcc > 30` (an unknown `hAcc`, negative, is kept); place it `(x, y)`; skip it if it is less than 3 m from the last vertex; otherwise append it.
   Then the finish: if there is more than one vertex and `(fx, fy)` is less than 3 m from the last vertex, **replace** the last vertex by `(fx, fy)`; otherwise append `(fx, fy)`.
   If there are fewer than 3 vertices: *refused* ("too few GPS fixes").
4. **The raw route** is the polyline through the vertices; `s[i]` is the distance along it to vertex `i` (`s[0] = 0`, `s[i] = s[i-1] + hypot(...)`); its length is `L`.
   Refused if `L < 300` ("the drive between the marks was only ... m; a stage needs at least 300 m"), or if `hypot(fx, fy) < 150` ("the finish mark is only ... m from the start mark; a stage runs from one place to another, not round a loop").
5. **Where the lines go.** `s0 = 20`, `s1 = L - 20`. The *start line* is centred on the point of the raw route at distance `s0`, the *finish line* on the point at `s1`
   (`pointAt(d)` is the linear interpolation along the segment that contains `d`; `d` is clamped into `[0, L]`).
   Why 20 m in from each mark: the car was parked on the mark, so a line exactly there could be "crossed" by GPS jitter before the car moves, or missed; 20 m in, a car starting from rest crosses at about 40 km/h, where a metre of position error is
   a tenth of a second and not a third. Every run is timed between these same two lines, so the 20 m is shared by all of them and cancels in a comparison.
6. **Line directions.** `pAfter = pointAt(min(s0 + 40, s1))`, `pBefore = pointAt(max(s1 - 40, s0))`;
   the start line's bearing is `bearing(pAfter - pStart)` and the finish line's is `bearing(pFinish - pBefore)`. Both gates have `half = 25`.
7. **The route of the stage:** `pStart`, then every raw vertex `i` with `s0 + 0.01 < s[i] < s1 - 0.01`, then `pFinish`. (Vertex 0 and the last vertex are therefore not in it.) Its own distances, from `pStart`, are the stage's **route distances**; its length is the stage's `length_m`.

The stage is written with the origin and marks to 7 decimals and the gates and route to 3 decimals ([`stage-format.md`](stage-format.md)); **the app scores against the stage as read back from its file**, so those roundings are part of the definition.

## 5. Placing a fix on the route (`Route`)

The route is a polyline with vertices `(x[j], y[j])`, `j = 0 ... n-1`, and `s[j]` the distance to each. `segmentAt(d)`: `0` if `d <= 0`; `n - 2` if `d >= length`; otherwise the largest `j` with `s[j] <= d`.

**`project(px, py, hint, behind, ahead, heading)`** looks only at the segments `j0 ... j1` with `j0 = segmentAt(max(0, hint - behind))`, `j1 = segmentAt(min(length, hint + ahead))`, and returns a route distance:

For each such segment `j` (from `A = vertex j` to `B = vertex j+1`, `d = B - A`, `len2 = |d|²`):
- `f = clamp(((P - A) · d) / len2, 0, 1)` (`f = 0` if `len2 = 0`); the nearest point `Q = A + f d`; `dist = |P - Q|`.
- `score = dist`. If a `heading` is given and `len2 > 0`: `diff = angleDiff(heading, bearing(d))`; if `diff > 60`: `score += 40 * (diff - 60) / 120`.
  (Two legs of a hairpin run in opposite directions; the heading tells them apart where several metres of GPS noise may not.)
- The segment with the smallest `score` wins (the first one, on a tie). The result is `s[j] + f * sqrt(len2)` for the winner, and the distance returned alongside it is the winner's plain `dist` (without the heading penalty).

## 6. Following a run (`StageRun`, `Scorer`)

A run is followed fix by fix. The offline scorer (`Scorer.scoreAll`) runs it on a whole recording; the app runs the same code live, with extra failsafes (section 8) that are off when scoring a finished log.
The state is: a **phase** (`APPROACH`, `RUNNING`, `POST`, `FINISHED`, `DNF`), the previous placed fix `prev`, the time of the last fix, `sNow` (the route distance of the latest fix placed on the route, never decreasing, 0 at the start),
the list of `(t, s)` pairs of fixes placed so far in this run, and the start time `tStart`.

**Each fix** `f` (skipped if the run is already over, or if `f.t` is not greater than the previous fix's time):
`cur = place f in the frame of the stage, with prev` (section 2); then, by phase:

*APPROACH.* If `prev` exists and `cur.t - prev.t <= 3`: `tc = start.crossing(prev, cur, 1.5, 0.25)`; if found, **begin(tc)**.

*RUNNING.* `near = cur.t - prev.t <= 3`.
1. If `near` and `sNow < 300`: `tc = start.crossing(prev, cur, 1.5, 0.25)`; if found, **begin(tc)** again (a false start: the run is timed from the later crossing) and stop here for this fix.
2. **locate(cur)** (below) gives its route distance `s` and its distance `dist` from the route.
3. If `near`: `tf = finish.crossing(prev, cur, 1.5, 0.25)`; if found **and** `tf - tStart >= 10` **and** `s >= 0.7 * route_length`: `sNow = s`, **finish(tf)**, and stop: this fix, the first one after the line, is not part of the run and is not counted in any figure.
4. Otherwise **place(cur, s, dist)**.

*POST.* If `f.t >= tFinish + 5`: the phase becomes `FINISHED`.

Then `prev = cur`.

**begin(tc):** `tStart = tc`; phase `RUNNING`; `sNow = 0`; clear the placed list and the quality figures; `startGap = cur.t - prev.t`; `startSpeed = speedAt(tc, prev, cur)`; then **locate(cur)** and **place** it (the first fix after the line is the first one placed).
`speedAt(tc, a, b)` is `a.speed + k * (b.speed - a.speed)` with `k = clamp((tc - a.t) / (b.t - a.t), 0, 1)`.

**locate(cur)** changes nothing; it returns the pair `(s, dist)`:
- `dt = cur.t - prev.t`; `ahead = 1.5 * max(cur.speed, 5) * max(dt, 1) + 60`; `heading = bearing(cur.vx, cur.vy)` if `cur.speed >= 3`, else none.
- `s = project(cur.x, cur.y, sNow, 40, ahead, heading)` and `dist` its distance.
- If `dist > 60` (lost: a GPS gap, a detour): `s2 = project(cur.x, cur.y, sNow, 40, route_length, heading)` with distance `dist2`; if `dist2 + 20 < dist` then `s = s2`, `dist = dist2`.
- `s = max(s, sNow)` (the car does not go backwards along the route), `s = min(s, route_length)`.

**place(cur, s, dist):** one more fix is counted; if `dt = cur.t - prev.t` is **strictly** larger than the largest gap so far (which starts at 0 in each run), remember it and the `sNow` of that moment (the route distance where the gap began). The first fix placed in a run is the first one after the start line, and its `dt` counts, so the gap across the start line is part of the figure **[fixed after the cross-check]**; remember the largest `hAcc` seen (start at -1); `sNow = s`; remember the largest `dist` seen as the *off-route maximum*; append `(cur.t, s)` to the placed list.

**finish(tf):** `tFinish = tf`; `finishGap = cur.t - prev.t`; `finishSpeed = speedAt(tf, prev, cur)`; build the profile; phase `POST`. The fix that crossed the line is the first one after it: it was located (to decide that the run is far enough along), but it is not placed, so it is in no profile and no figure (the car is on the far side of the line, and the "off the route" figure would measure how far past the end of the route it is).

**The profile** is the time at which the car had reached route distance `k * 25`, in seconds after the start line:
1. Knots `(tt, ss)`: first `(tStart, 0)`. Then, for each placed pair `(t, s)` in order: skip it if `t <= tStart` or `t >= tFinish`; skip it if `s <= (last knot's s) + 0.01` (stopped or jittering: the earliest time at each distance is kept); skip it if `s >= route_length - 0.01`; else append it.
   Last knot `(tFinish, route_length)`. There are `m` knots.
2. `n = ceil(route_length / 25)`. For `k = 0 ... n`: `s = min(25 k, route_length)`; take the knot index `j` as the largest value reached by starting at 0 and doing `while j < m - 2 and ss[j+1] < s: j += 1`
   (`j` only moves forward as `k` grows); `f = (s - ss[j]) / (ss[j+1] - ss[j])` if `ss[j+1] > ss[j]` else 0, clamped to `[0, 1]`; `profile[k] = tt[j] + f * (tt[j+1] - tt[j]) - tStart`.
   The profile has `n + 1` values, `profile[0] = 0`, the last equals the stage time.

**The result of a finished run:** `total = tFinish - tStart`; the profile; `length_m` = route length; `step_m = 25`; the quality figures (`fixes` = placed fixes, largest gap and where it began, worst `hAcc`, off-route maximum, `startGap`, `finishGap`);
`line_speeds = (startSpeed, finishSpeed)`; `utc_ms = round(tStart * 1000 + start_utc_ms)` (0 if the start time is unknown). A phase is `FINISHED` once 5 s have passed after the finish crossing.

**Scoring a whole recording** (`Scorer.scoreAll`): feed the sorted, de-duplicated fixes to a fresh run; when a run is over (`FINISHED`, or the live failsafes ended it) keep its result and start a fresh run with the **next** fix
(the fix that ended the old run is not given to the new one); a recording may therefore hold any number of runs, one after another. At the end of the fixes, a run still in `RUNNING` is closed as **did not finish** (status "the recording ended before the finish line":
`finished = 0`, no total, no profile, `finishGap = 0`, `finishSpeed = -1`), and one in `POST` counts as finished. A run in `APPROACH` leaves nothing.
Run ids: the session file's name without the `.csv` and without a leading `session_` (and without any folder part, up to the last `/` or backslash), then `_` and the number of the run in that file, counting from 1 (`20261013_121500_1`).
That base name is used as it is when it is at most 56 characters and made only of `A-Z a-z 0-9 _ -` (every file the recorder writes is). A name from somewhere else (spaces, brackets, accents, longer) is turned into a safe one so that its runs can be saved: every character outside `A-Z a-z 0-9 -` becomes `_`,
the result is cut to 48 characters, and `-` and 8 lower-case hexadecimal digits are added, the 32-bit FNV-1a hash of the whole original base name taken over its UTF-16 code units (offset basis `0x811C9DC5`, prime `0x01000193`, each unit XORed in, then multiplied, modulo 2³²).
The hash keeps two different names that look alike afterwards (`a b.csv`, `a_b.csv`) from sharing run ids; an empty base name gives `run`. Scoring the same recording twice, or from a copy elsewhere, therefore gives the same ids and adds nothing the second time.

## 7. Comparing two runs (`Compare`)

Run **A** is the one being looked at, **B** the baseline (the best run, or the previous one). They must be finished, of the same stage, with the same `step_m` and profile length and `length_m` within 0.5 m.

- **Delta curve:** `delta[k] = A.profile[k] - B.profile[k]`; positive means A was behind B at that distance. `total delta = A.total - B.total` (equal to the last `delta`).
- **Sections:** the section length is the first of 250, 500, 1000, 2000, 5000, 10000 m with `length / section <= 12` (10000 if none), where `length` is **run A's `length_m`** (the two are within 0.5 m). With `per = section / 25` steps, the sections run from `k0 = 0, per, 2·per, ...` while `k0 < n - 1` (n = profile length),
  to `k1 = min(n - 1, k0 + per)`; each has the times `A.profile[k1] - A.profile[k0]` and the same for B, and its delta is the difference. The section deltas add up to the total delta. A section is shown as running from `25·k0` m to `min(25·k1, length_m)` m: **the last one ends at `length_m`**, because the last profile value is at the end of the route and not at `25·(n-1)`.
  The *slowest part* is the section with the largest **positive** delta, the *fastest part* the most negative; if no section is positive there is no slowest part, and if none is negative no fastest part (the first section wins a tie). **[fixed after the cross-check]**
- **Which run is the baseline B** (the screen offers three). *Best*: the finished run other than A with the smallest `total_s` (the earlier one in time order if two are equal; the screen calls it the *next best* run when A is the best of all). *Previous*: the finished run before A in time order. *First*: the earliest finished run other than A. Runs that did not finish are never a baseline.
- **How far to trust it.** Where GPS puts a crossing of a line is uncertain along the road by about `σ_along = 3 m` (one standard deviation, from simulation), and a metre is `1/speed` seconds. With `v = max(line speed, 3)` m/s (12 m/s if the line speed is unknown), for the start and finish of both runs:
  `σ = 3 * sqrt(1/vsA² + 1/vfA² + 1/vsB² + 1/vfB²)` seconds. A difference smaller than `max(0.4, 2σ)` is reported as **"about the same"**.
  Both runs rolling through the lines at 20 m/s (start) and 25 m/s (finish) give σ ≈ 0.27 s, so a band of 0.54 s. A standing start (about 11 m/s, 40 km/h, at the start line 20 m from the mark) and a braking stop (about 14 m/s at the finish line) give σ ≈ 0.5 s and a band of about 1 s.
- **Warnings** on a run (printed with a comparison): a gap between two fixes over 5 s; the car more than 100 m from the route; a line crossed between fixes more than 2.5 s apart; a worst reported accuracy over 25 m.

## 8. What the phone adds while it is driving (live only)

None of this changes a stage time; it decides *when the recording runs* and keeps numbers off the screen on the move.

- **Armed.** With a stage armed, the app listens to the GPS and records nothing. When the car is moving (`speed >= 2`) within 400 m of the middle of the start line, it starts a recording (the last fix before the trigger is handed to the recorder so no second is lost) and a `StageRun` follows the fixes, writing `STAGE`, `STAGE_START`, `STAGE_FINISH`, `STAGE_DNF` and `STAGE_ABORT` events.
  After a run or an approach that came to nothing, it will not trigger again until the car has been more than 600 m from the start line.
- **Live failsafes** (off when scoring a finished log): an approach with no start line within 300 s of the recording's first fix, or more than 700 m from the middle of the start line, is **aborted** (the recording is deleted); a run longer than `clamp(length / 2.5 + 600, 1800, 21600)` s,
  or one with the car stopped (speed under 0.55 m/s) for 1200 s, is given up as **did not finish**. Five seconds after the finish line the recording stops and the run is saved, and the app arms itself again for the next run.
  An armed phone nobody uses switches itself off after 3 hours.
- **The screen rule for open roads.** Numbers about a run (a time, a difference, a comparison) are shown only when the car has **stopped**: speed under 0.55 m/s (2 km/h) for at least 5 s of fixes, and the newest fix under 3 s old. If the GPS has gone quiet or the speed is unknown, the answer is "not stopped": when in doubt, the screen shows words and no number.
  While moving, the only feedback is **one beep at the start line, two beeps at the finish line, and a low tone when a run is given up** (a run that did not finish, or an approach that came to nothing and was thrown away); the beeps can be switched off. On Android they are the tones `PROMPT`, `ACK` and `NACK` of `ToneGenerator`, on the media volume. I have not heard them on a phone.

## 9. How well it works (what the tests found)

Two sets of simulations, on roads with exact geometry and a driver with a known speed profile (so the true time between the lines is known), with 1 Hz fixes and correlated noise. **The Java tests** (`android/tests/…/StageTests.java`) use one synthetic mountain road, on which the lines are crossed at about 30 m/s.
**The independent Python simulation** ([`../sim/simulate_stage.py`](../sim/simulate_stage.py), written from this page) builds its own roads, many stages per case, a new marking drive for each, and drives at 20 m/s unless it says otherwise. Where the two differ, the difference is the road and the speed, not the method.
Errors are the measured stage time minus the true one, 1 σ. The Python figures are the range over three seeds (1, 2, 3).

| Case | Java tests | Python simulation |
|---|---|---|
| Rolling start and finish, 2.5 m GPS noise | **0.12 s** at about 30 m/s at the lines (300 runs; the formula of section 7 says 0.14 s for 3 m) | **0.165 to 0.184 s** at 20 m/s (300 runs per seed, formula 0.22 s); 0.108 to 0.119 s at 30 m/s |
| Standing start at the mark, braking stop at the finish mark, 3 m noise | 0.34 s, mean +0.01 s (500 runs; formula 0.35 s) | 0.39 s (0.385 to 0.393), mean +0.02 to +0.04 s (formula 0.39 to 0.41 s); the lines were crossed at 9 to 10 m/s and 12 to 13 m/s |
| The same, with the car parked 10 m short of the start line | 0.39 s, mean +0.03 s | 0.44 to 0.48 s, mean +0.04 to +0.06 s |
| A start line 5 m further along | the stage time shrinks by 0.167 s, exactly 5 m at 30 m/s | by 0.166 to 0.167 s (spread 0.012 s) |
| A 12 s GPS dropout in the middle | stage time unchanged to the millisecond (the lines are crossed on good fixes); the profile inside the gap is off by up to about 0.5 s | not simulated |
| A hairpin road with legs 30 m apart, 3 m noise | profile error (time error times speed) 4 m rms, 19 m worst; never the 40 m or more of a fix put on the wrong leg | 4.1 to 5.1 m rms, 15 to 17 m worst |
| No GPS noise at all | within 20 to 30 ms, on a drive that accelerates and brakes (the curve through two fixes is not exactly the car's path) | 0.000 s, at constant speed |

(I ran the Python script myself, seeds 1 to 3, for this column. The first rows differ from the Java ones because the lines are crossed at different speeds: one metre of position error is `1/speed` seconds.)

Two runs of exactly the same true time therefore differ by about 0.2 s (1 σ) when the start and finish are rolling at 20 to 30 m/s, and by about 0.5 s when one end is a standing start. A difference has to be about twice that before it is worth believing, which is the "about the same" band of section 7
(never less than 0.4 s). The profile along the route is good to a few metres' worth of time.

**What the independent simulation found that the Java tests had not looked at.** Two limits of the method as written (not differences between the two implementations):

1. **The lines end up closer to the marks than 20 m.** The route is the recorded path, and GPS noise makes it zigzag: with 2.5 to 3 m of noise it comes out **0.5 to 1.2 % longer** than the road (15 simulated stages per case; without noise it is 0.03 to 0.2 % *shorter*, cutting the bends), most where the car was slow. "20 m along the route" is therefore only **15 to 18 m of road** on average (spread 2 to 5 m; one stage had 5 m). The timed stretch is longer than intended by that much, the same for every run of a stage, so comparisons do not feel it.
   It matters at a **finish by braking to a stop**: a line only a few metres before the stopping place is crossed by a car that is crawling, and a crossing is accepted only if one of the two fixes either side of the line is moving at 1.5 m/s (5.4 km/h) or more. Over the simulated standing-start drives, 3, 0 and 25 of about 500 runs (seeds 1, 2, 3) found no finish and were scored as *did not finish*;
   the 25 were all on one stage whose finish line came out 5 m before where the car stopped. My Java test (one stage, 500 runs) lost none. A rolling finish does not have this problem (0 lost in 300 runs per seed).
2. **Tight stacked hairpins at a line.** A line is 50 m wide and counts a car moving the way it was marked. On a hillside road whose legs run on top of each other, the leg *two* bends back runs the same way, so if it lies within 25 m of the line the car crosses the line on the wrong leg. At the finish, this ends the run at about 74 % of the route, which the 70 % rule of section 6 allows,
   and the time is wrong by tens of seconds; at the start it restarts the clock (the false-start rule). With hairpin legs **30 m and 20 m apart** all 100 runs were right; at **16 m**, 0 and 6 of 100 (seeds 1 and 2) were off by more than 5 s (the worst, in profile error, 550 m); at **12 m**, 29 and 63 of 100.
   The Java code follows the same rules and I have not run it on such a road, so I expect the same. Until the stage builder checks for this, the advice is to **mark the start and the finish away from tight stacked bends**.

On the real Sepang recording (a circuit, not a stage, but the code does not know the difference) the gate gives the lap times of the lap timer that ran in the app on the day to the last digit, and the pseudo-stage cut out of lap 3 is found again at every pass of that stretch of the circuit: 16 runs, all finished, one for every pass through that stretch (the recording is 44 minutes, about 17 laps' worth of driving, 15 of them timed laps).

## 10. How this page is checked

- [`../analysis/stage_fixtures`](../analysis/stage_fixtures) holds stage files, recordings and expected results made by the Java code; the Android tests regenerate them and fail if the committed ones differ (`ST.FX1`).
- `python analysis/stage_compare.py selftest` recomputes every expected result from the recordings with the Python implementation written from this page, and compares: crossing times to a microsecond, stage times and profiles to a millisecond, run ids to the character. It reproduces all of them (18 groups: 400 line-crossing cases, 35 file names, 4 refusals, 3 stages, 25 runs, 32 comparisons, the real-log part, the bundle reader, the file formats).
  The 28 stage and run files it writes are **byte for byte** the expected ones.
- **What that does not prove.** The written method and the Java were both made by me, and the second implementation by another instance of the same model that was given only the page. It finds places where the page is vague (five, now fixed above) and places where the Java does something the page does not say. It does not find a mistake I would make on both sides.
  And a clean first pass proves little by itself: the author of the Python code broke it in 55 one-line ways, and the fixtures noticed 25 of them. The 30 it did not notice are rules of this page that **no fixture exercises**: the latitude bound; the first of equal times winning; a bearing of exactly 0; a fix exactly on the line; `across` exactly equal to `half`; speed exactly 1.5; forward exactly 0.25;
  the 16-step scan (an 8-step scan passes); skipping a vertex with `hAcc > 30` (10 passes); replacing against appending the finish vertex within 3 m; the 0.01 m margin on the route; the heading penalty and its 3 m/s threshold; the 40 m window behind `sNow`; the 60 m "lost" limit; `sNow` never decreasing; the 70 % and 10 s finish rules;
  the 5 s of POST (3 s and 30 s pass); the 300 s false-start limit; the 3 s "near" rule (2 s and 10 s pass); the last profile knot; the worst-`hAcc` start value; skipping the first fix; the 12-section rule; the 12 m/s speed for an unknown line speed; the 3 m/s floor in σ.
  The Java tests do exercise many of these directly (for instance the false start, the dropout, the heading, the 70 % rule), but then only against my reading of the page. Fixtures for each, so that a second implementation is held to them too, are on the list in [`roadmap.md`](roadmap.md).
- `python analysis/pin_compare.py selftest` does the same for **section 11** (the pins, release 1.5). It is a third body of code, written by another instance of the model from 11.1 to 11.8 of this page alone: it never opened the Java. It recomputes every expected result in [`../analysis/stage_fixtures/pins`](../analysis/stage_fixtures/pins) (391 texts, of which 315 are read as places and 76 refused; the track of 30 recordings; 60 places snapped; 115 pairs of pins, 62 of which make a stage; the 62 stage files; the 62 first runs) and runs 20 groups of checks of its own (the grammar of 11.8 on 313 table rows, 26 980 round trips and 105 000 random texts; every refusal; the guard on 300 random roads; and so on): 26 groups in about 3 seconds.
  It agrees with the Java everywhere. It is the second version of that file: the first was written before the hole and break rules and the strict reader of 11.8 existed, and differed from the regenerated files in 17 cases and 2 files, all of them its own out-of-date code. It had to choose a reading in 17 places where this page could be read two ways; those that mattered are written out above (the times as doubles, what "makes a stage" means in 11.3, the unit of a gap's position), the others agree with what the Java does.
- **What that does not prove, for section 11.** The same limits as above: the same model read my page, so a mistake I make on both sides passes. And again the fixtures were tested by breaking the second implementation: 422 one-line breakages (381 different ones); the fixtures noticed 279 of the 422 (251 of the 381). Of the 143 they missed, 109 are rules of this page that a real input can show and **no fixture exercises**, 14 would need a computed floating-point number to land exactly on a constant (not portable between languages, so no fixture can hold them), 12 change nothing, and 8 are the sentences of 11.7, which are in no file by design.
  The 109 are the exact limits of the hole rule (3.0 s, and whether the ends of a hole count); the order of the rules when two fail together; an infinite position; the pair order, and what happens when the guard fails on the first pair; the windows and widths of the guard; which link pattern wins when a text has several; and, in the reader of 11.8, eight white-space characters (U+2000 to U+2008 but not all), four look-alikes that must not be white space, the length counted in UTF-16 units, Unicode against ASCII case, and the exact order of the link tests.
  The whole list, with how to make a fixture for each, is [`../analysis/stage_fixtures/pins/GAPS.md`](../analysis/stage_fixtures/pins/GAPS.md). The Java tests (`PN.*`) do exercise most of them directly (the 3.0 s limit, the edges of a hole to within half a metre, the 300 s and 100 m of a break, the 20 m and 300 m limits), but then only against my reading of the page. As a first step I ran the 241 texts of the second implementation's own table for 11.8 through the app's reader: **0 differences**.
- A page like this can still be wrong in a way both implementations share; the real test is a stage driven twice on a real road, with a stopwatch or a second device at the lines. That has not been done yet.

## 11. Making a stage from pins on a recording (`Track`, `PinBuilder`, `Coords`; release 1.5)

Section 4 makes a stage from a drive that starts and ends parked on the two marks. On a public road there is often nowhere safe to park at either end, so 1.5 can make **the same kind of stage** another way: take an ordinary recording of a drive along the road, and put two **pins** on it, one where the stage starts and one where it ends.
The result is a stage file exactly like the ones of section 4 (same format, same two lines of 50 m, same route, same timing and comparison); only the way the lines are placed is different. Nothing in sections 5 to 8 changes.

A pin is given in one of two ways: by **position** (a distance along the track, from the slider on the pin screen) or by **coordinate** (a latitude and longitude, typed or pasted from a map). A coordinate is first turned into a position (11.3), so everything after that is the same.
This section is written, like the others, so that it can be re-implemented without the Java. The categories of refusal are fixed (the words are the app's own); the numbers and the file are exact.

### 11.1 The track of a recording (`Track`)

The input is a recording's usable fixes, sorted and de-duplicated as in section 1.

1. A fix is **kept** unless `hAcc > 30` (an unknown `hAcc`, negative, is kept) or its speed is known and under 1.0 m/s, i.e. `0 <= speed < 1.0` (an unknown speed, negative, is kept). A parked or crawling phone reports a position that is mostly noise, and it would only add zigzags to the road.
2. The **vertices** of the track: the first kept fix is vertex 0; every later kept fix becomes a vertex if it is **at least 3 m** (straight line, measured in the track frame of step 3) from the last vertex; a fix less than 3.0 m from it is left out.
   Each vertex remembers its fix's `t`, `lat`, `lon`.
3. The **track frame** is the flat frame of section 2 with its origin at vertex 0. `s[0] = 0` and `s[i] = s[i-1] + hypot(...)` between consecutive vertices; `L = s[last]` is the length of the track. `pointAt(d)` is the point at distance `d` along the polyline (`d` clamped into `[0, L]`; the same as the route's, section 4 step 5).
4. If there are fewer than 3 vertices the recording is *refused* (category `no_drive`: "there is no drive in this recording").
5. The **holes** of the recording. They are found on **all** the usable fixes (sorted and de-duplicated as in section 1, whether kept in step 1 or not), not on the vertices: every pair of consecutive fixes whose times differ by **more than 3.0 s** is a hole. A hole remembers its two times `t0 < t1` and the straight distance between its two fixes, in the track frame.
   (3.0 s is the scorer's own limit: section 6 finds a line crossing only between two fixes at most 3 s apart, so a line in a hole could not be timed. A long stand is not a hole: its fixes are left out of the track by step 1, but they keep arriving every second.)
   Times are the `t` of section 1, doubles in seconds (`t_us / 1e6`), and every comparison of a time with a limit (here 3.0 s, in 11.3 300 s) is made on those doubles. A difference that equals a limit exactly on paper can come out a few units in the last place either side of it in floating point, so a recording whose times are not whole seconds cannot show a portable result *at* a limit; the fixtures that sit on 3.0 s or 300 s use whole seconds, where the difference is exact.
6. **`timeAt(d)`**, for `d` clamped into `[0, L]`, is the time of the point at distance `d` along the track: on its segment `j` (`s[j] <= d <= s[j+1]`), `t[j] + (d - s[j]) / (s[j+1] - s[j]) * (t[j+1] - t[j])`.

### 11.2 Snapping a coordinate to the track (`Track.passes`)

The coordinate `(lat, lon)` is placed in the track frame as `(px, py)`. For each segment `j` of the track the nearest point and its distance `d_j` are found as in section 5 (clamped projection, no heading). The segments with `d_j <= 60` (the **snap limit**, 60 m) are grouped into **passes**: a pass is a maximal run of consecutive segments that all satisfy it.
A pass is **at** the nearest point of its best segment, the one with the smallest `d_j` (the first one on a tie): its position is `s[j] + f * length(j)` with `f` the clamped projection fraction, and its **offset** is that `d_j`. Passes are listed in order of position.
Then the passes **much further from the place than the nearest one are dropped**: only those whose offset is at most the smallest offset plus 10 m are kept. (A place on one leg of a hairpin is also within 60 m of the legs above and below it, and a place on one carriageway is within 60 m of the other; those are other roads, and the driver meant the nearest. The same road driven twice, or on every lap, gives offsets within a few metres of each other and all passes stay.)
If there is no pass at all, the coordinate is *refused* (category `no_pass_start` or `no_pass_finish`; the sentence gives the distance of the closest approach, `min d_j`, rounded to whole metres).

(Why 60 m: a point picked on a map is rarely exactly on the line the car drove. It may be on the other carriageway, or beside the road. The line is then placed on the road the car actually drove, at the nearest point; the offset is reported, and the driver can see it on the map.)

### 11.3 The two positions and the rules they must meet

Let `a` be the start position and `b` the finish position (distances along the track, `L` its length). **By position** they are given as they are. **By coordinate**, let `A` be the passes of the start coordinate and `B` the passes of the finish coordinate: take the **first pair** `(a, b)`, in order of `a` and then of `b`, with `b > a`, that meets the rules 2 to 7 below. (If the road is driven twice, the first pair that meets rules 2 to 7 is used: a pass in a hole, or a pair that joins two drives, is skipped for the next. The guard of 11.6 is not one of these rules: it is made once, on the pair chosen, and if it fails no other pair is tried.)
If there is none, the pins are refused: with `wrong_way` if no pass of the finish lies after a pass of the start; else with the category of the first rule failed by the first pair with `b > a` (the pairs in the same order).
A pin given by position is a single candidate with that position. The two pins need not be given the same way (the start by position and the finish by coordinate is fine).
**The order of the checks.** First the recording: no drive (11.1 step 4) is refused as `no_drive` whatever the pins are. Then the **start pin**: a position that is not a finite number (NaN, or plus or minus infinity) is refused as `near_end`; a coordinate with no pass (11.2) is refused as `no_pass_start`. Then the **finish pin** in the same way (`near_end`, `no_pass_finish`): so a start that is off the drive, or not a number, is reported before anything about the finish. Only then the pairs.

The rules, checked in this order; the first to fail gives the category:

1. `b > a`. Else `wrong_way`: "the finish comes before the start on this drive: it was driven the other way round".
2. `a >= 20` and `b <= L - 20`. Else `near_end`: the recording must show the car arriving at the start line and leaving the finish line (a line cannot be crossed if there is no fix before it), so there must be at least 20 m of track before the start and 20 m after the finish.
3. `b - a >= 300`. Else `too_short` (the same 300 m as section 4).
4. The straight distance between `pointAt(a)` and `pointAt(b)` is at least 150 m. Else `loop` (the same 150 m as section 4).
5. The start pin is not in a hole: there is no hole with `t0 <= timeAt(a) <= t1` (the ends count). Else `hole_start`: "the GPS gave no position for N s around the start pin, so the phone could not see the car cross a line there" (`N` is the length of the longest such hole).
6. The same for the finish pin, with `timeAt(b)`. Else `hole_finish`.
7. There is no **break** between the pins: no hole with `t1 - t0 > 300`, a distance between its two fixes of more than 100 m, `t1 > timeAt(a)` and `t0 < timeAt(b)`. Else `break`: "between the two pins the recording has a break of N s, with the car D apart across it: the GPS was lost, or this is two drives. A stage has to be one continuous drive."
   A break is judged on the two fixes either side of the hole and not on the road between them, and both numbers must be exceeded: a wait of exactly 300 s is not a break, nor is a car that is 100 m or less from where it was (a phone that slept while the car stood still is a hole of any length at one place). A hole shorter than that between the pins is no problem: the lines are crossed on good fixes, and the scorer's own quality figure (section 6) says how well the run in between was followed.

### 11.4 The lines

The centre of the start line is `pa = pointAt(a)` and of the finish line `pb = pointAt(b)`: **the lines go exactly where the pins are**, with no shift along the road (section 4 shifted them 20 m in because the car was parked on the marks; a car that is driving past a pin needs no such margin).
Each line is perpendicular to the direction of the track over the 40 m around it: the start line's bearing is `bearing(pointAt(a + 20) - pointAt(a - 20))` and the finish line's is `bearing(pointAt(b + 20) - pointAt(b - 20))` (vectors in the track frame, `bearing` as in section 2; rule 2 means the distances never need clamping). Both lines have `half = 25`.

### 11.5 The stage

- The **start mark** is the coordinate the driver gave, or, for a pin given by position, the latitude and longitude of `pa` (the track frame's inverse, section 2). The **finish mark** likewise with `pb`.
- The **stage frame** is the frame of section 2 with its origin at the start mark, unrounded.
- The **route** is `pa`, then every vertex `i` with `a + 0.01 < s[i] < b - 0.01`, then `pb`. Every point is placed in the stage frame **from its latitude and longitude**: a vertex from its fix's, `pa` and `pb` from the track frame's inverse of their coordinates. The route's own distances are then computed in the stage frame, as in section 4 step 7, and its length is the stage's `length_m`.
- The gates are the two lines of 11.4 with their centres placed in the stage frame in the same way.
- The stage is written as in section 4 (origin and marks to 7 decimals, gates and route to 3), and **the app scores against the stage as read back from its file**.

### 11.6 The guard against a road that crosses a line twice

The 1.4 method has no check for a road that comes back over its own start or finish line (a loop, or hairpins stacked closer than a line is wide), and section 9 shows what happens when it does. For a stage made from pins, the app checks the finished stage and refuses it if it would be mistimed. The check is made **only on the pair chosen in 11.3**: if it fails, the pins are refused with its category and no other pair is tried. It is made on the numbers as built, before they are rounded for the file (a millimetre is not a decision), the start gate first.
In the stage frame, with the stage's gates and route, a **forward crossing of a gate** by the route is a segment `(q[k], q[k+1])` of the route with `along(q[k]) < 0 <= along(q[k+1])`, where the line's width for this check is `35` (the gate's 25 m plus 10 m for GPS noise): `|across|` at the crossing point, found by linear interpolation along the segment, must be at most 35.
`u` is the route distance of the crossing (the segment's start distance plus the interpolated fraction of its length) and `R` the route's length.

- **`guard_start`**: the start gate has a forward crossing with `30 < u <= 300`. (A run would restart its clock there; section 6, "false start".)
- **`guard_finish`**: the finish gate has a forward crossing with `0.7 * R <= u < R - 30`. (A run would end there; section 6, the finish rule.)

The words: "the road crosses the start/finish line again `u` m into the stage (a loop or tight bends close to it). A run would be timed wrongly. Move that pin to a straighter place."

### 11.7 What the builder also says (not part of any file)

Plain sentences for the screen; their wording is the app's, and a second implementation need not reproduce them.

- *Offsets.* For a coordinate pin: how far from the road the coordinate was, if it was more than 5 m.
- *Which pass.* If a coordinate has more than one pass in the recording, which one was used.
- *Gaps.* A **gap** is a segment of the track between the pins, between two consecutive vertices, with a time difference over 20 s and a length over 100 m (GPS was lost, in a tunnel, say): the road is drawn there as a straight line, so later drives are placed on it less well. Each gap is reported with its position: the distance along the track of the recording, as a pin position is, at which the segment begins (this can be before the start pin, when the pin cuts the segment). (A gap is a note, not a refusal, and it is not the same as a hole in 11.1: a gap is judged on the vertices and so can contain a long stand, a hole is judged on all the fixes.)

### 11.8 Reading coordinates (`Coords`)

A coordinate is taken from text the driver typed or pasted. The text is **refused** with a sentence, or gives `(lat, lon)` in degrees.

**Three definitions used below.** *White space* is exactly these 28 characters: U+0009 to U+000D, U+001C to U+0020, U+00A0, U+1680, U+2000 to U+200A, U+2028, U+2029, U+202F, U+205F and U+3000. (U+0085, U+180E, U+200B and U+FEFF are **not** white space: the phone's own idea of it differs between versions of Android.) *Digits* are the ASCII digits `0` to `9` only (an Arabic-Indic or full-width digit is just a character that cannot be read). *Case* is ASCII only: `A` to `Z` are the same as `a` to `z`, and no other character has a case.
White space is trimmed from both ends first; a text that is then empty, or longer than 4000 characters (UTF-16 code units), is refused. Then, in this order:

1. **A map link**: the text contains `://`, or starts with `www.`, or contains `google.` followed later by `/maps` (all matched without regard to case). If it contains `goo.gl/` it is refused as a short link ("a short link needs the internet to open: open it in Google Maps and copy the numbers under the place's name instead"). Otherwise the first of these that matches wins, and **the match is taken as it is**: if its numbers are out of range (rule 4), the text is refused and the later patterns are not tried.
   `!3d<lat>!4d<lon>` (the place itself); then `@<lat>,<lon>` (the middle of the map); then `[?&](q|ll|query|center)=<lat>,<lon>` (without regard to case; the comma may be written `%2C` or `%2c`; if the text has several, the leftmost); then `/place/<lat>,<lon>`. Each number is `-?\d+(\.\d+)?`, with nothing between the numbers and the comma (no space). No match: refused.
2. **A `geo:` link**: the text starts with `geo:` (any case). Take what follows, cut it at the first `?` or `;` if there is one, and split it at every comma. There must be **two or three** parts, and each part must be a number `-?\d+(\.\d+)?` and nothing else: no white space and no `+`, and an empty part (a comma at the end, or two commas in a row) is not a number. They are latitude, longitude and an altitude, which is checked but ignored. Anything else is refused.
3. **Plain text.** Commas and semicolons are separators, like white space. A comma is never a decimal mark: `3,1234 101,5678` has four numbers and is refused, not read as 3.1234 101.5678. The text is cut into **tokens**: numbers (`[+-]?\d+(\.\d*)?` or `[+-]?\.\d+`; the minus may also be U+2212), the letters `N S E W` in either case, and the marks `°` `º` `˚` (degrees), `'` `’` `′` (minutes) and `"` `”` `″` (seconds), which are skipped; any other character is refused. A number that runs straight into the next one is a typing mistake and the text is refused: a number token **immediately followed** by `.`, `+`, `-` or U+2212 (`3.1234.5`, `3.1234-101.5678`). A `+` or `-` that does not begin a number (`3.1234 + 101.5678`) is a character that cannot be read.
   - *With letters.* Exactly two letters are expected, one of `N S` and one of `E W`. A coordinate is a letter and the numbers next to it: if the first token is a letter, each letter starts a coordinate (`N 3.1234 E 101.5678`); otherwise each letter ends one (`3.1234 N, 101.5678 E`, `3°08'20.4"N 101°41'12.7"E`), and numbers after the last letter are refused. The latitude is the `N S` coordinate and the longitude the `E W` one, whichever comes first.
     A coordinate is 1, 2 or 3 numbers: degrees; degrees and minutes; degrees, minutes and seconds (with or without the marks). Its value is `d + m/60 + s/3600`; minutes and seconds must be under 60; when there is more than one number, the degrees (and the minutes, if there are three) must be whole numbers **by value** (`3.0` is whole) and unsigned. `S` and `W` make the value negative; a number that carries its own sign next to a letter is refused.
   - *Without letters.* Exactly two numbers, each possibly followed by a degree mark: the first is the latitude, the second the longitude, signed.
   - Anything else is refused ("I found N numbers", say).
4. **Range.** `|lat| <= 90`, `|lon| <= 180`; `(0, 0)` is refused (it is what a failed look-up returns). If the first number of a plain pair is outside the latitude range and the second is not, the sentence says "did you swap them?"; the numbers are never swapped for the driver.

The text is shown back as `lat, lon` to 6 decimals (about 0.1 m).

### 11.9 The first drive of a planned stage (live)

**Status: not built.** Release 1.5 makes a stage from a plan in the other way, from a recording the driver has already made (11.1 to 11.8); arming a plan, so that its first drive records by itself and builds the stage, is release 1.6. This part is written first so that it can be checked before it is built, and, unlike 11.1 to 11.8, **no second implementation has read it yet**.

A **planned stage** is two coordinates and a name, and no road yet. It is armed like a stage. The first drive records by itself and **teaches the app the road**; from the second drive on it is an ordinary stage.

- *Waiting.* As for a stage (section 8): GPS only, and a recording starts when the car is moving (`speed >= 2`) within 400 m of the **start pin**; the fix before it is handed to the recording; after a drive or an approach that came to nothing it will not trigger again until the car has been more than 600 m from the start pin.
- *Following.* In the flat frame at the start pin, for each fix `dS` and `dF` are its distances to the start pin and the finish pin. The **start is passed** at the first fix where the smallest `dS` so far is at most 60 and `dS >= (that smallest) + 30`. After the start has been passed, the **finish is passed** at the first fix where the smallest `dF` among the fixes after the start was passed is at most 60 and `dF >= (that smallest) + 30`.
  (The 30 m means the car has moved at least 30 m along the road past the nearest point, so the recording has the 20 m of track after the pin that rule 2 of 11.3 needs.) The recording stops at that fix.
- *Failsafes* (as in section 8): before the start has been passed, the recording is thrown away (the low tone) if 300 s go by from its first fix, or the car is more than 700 m from the start pin. After the start has been passed, the drive is given up (the recording is kept, the low tone sounds, the plan stays) if the car has been stopped (speed under 0.55) for 1200 s, or the drive has lasted more than `clamp(2 D / 2.5 + 900, 1800, 21600)` s from the start pass, where `D` is the straight distance between the two pins.
- *Building.* When the finish has been passed, the stage is made from **the whole recording** with the two coordinates, exactly as in 11.1 to 11.6. If it is refused, the stage is not made: the recording is kept, the plan stays, and the app says why. If it is made, it is saved under the plan's id (the plan becomes the stage), the recording is scored against it like any other (the drive becomes the stage's first run), and the phone arms itself for the new stage.
- *The screen rule.* The result is shown only when the car has stopped, like every run (section 8).


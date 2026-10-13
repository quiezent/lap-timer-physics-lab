#!/usr/bin/env python3
"""
simulate_stage.py - how accurately does the stage method time a stage? An independent Monte Carlo.

    python sim/simulate_stage.py [--runs N] [--seed S] [--quick] [--cases abcde[xph]]

Roads made of straights and circular arcs, a driver limited by lateral acceleration (speed <= sqrt(a_lat / curvature)), a top speed, an acceleration
limit and a braking limit, and a GPS that gives a fix every second with AR(1) correlated position noise (rho 0.7, sigma per axis in metres), a Doppler
speed (noise 0.12 m/s) and a course (noise 2 degrees, not given below 0.8 m/s), hAcc 3.5. Because the road and the driver are formulas, the true time
between the two lines of a stage is known exactly, and the measured one is compared with it. The stage is built the way the app builds it, from a
simulated marking drive (parked, MARK_START, standing start, braking stop, parked, MARK_FINISH, with the marks averaged as in section 4 of
docs/stage-algorithm.md), and the runs are scored by analysis/stage_compare.py (imported from there by path), so this checks the written method of
docs/stage-algorithm.md, not the Android code. Written from that page alone, with its own roads, driver and noise.

Cases (the printed table, one row each): (a) rolling start and finish, 2.5 m noise, 300 runs: mean and standard deviation of (measured - true) stage
time; (b) standing start at the start mark and a braking stop at the finish mark, 3 m noise, 500 runs; (c) as (b) with the car parked 10 m before the
start line; (d) a start line moved 5 m along the road: the change of the stage time; (e) a hairpin road with legs 30 m apart, 3 m noise, 40 runs:
profile error in metres (time error times speed), rms and worst. For the rows about the stage time ((a), (b), (c)) the table also gives the figure
the formula of section 7 predicts (3 m times the root of the summed 1/speed^2 at the two lines). Three extras are not run by default: `p`, (a) again with the car at 30 m/s (the speed
the section 9 figures for (a) fit); `x`, a noise-free check (the error must be zero); `h`, the case (e) road with legs 30, 20, 16 and 12 m apart, to see
where the method as written stops working (a line is 50 m wide, so when the legs that run the same way, two spacings apart, come within about
30 m of each other, the finish line is crossed on an earlier leg). A new marking drive (a new stage) is made for every 25 runs, so that the spread
of the stage geometry is part of the result.

Options: --runs N runs per case instead of 300/500/500/300/40 (and 100 for each road of h), --quick one tenth of the default runs (about 2 s instead
of 10 s), --seed S the random seed (default 1; every case has its own random stream, so a case gives the same numbers whichever other cases run with
it), --cases the letters of the cases to run (default abcde; the extras are p, x and h).
Exit status 0 if the table was made, 1 if a case could not be run (a stage was refused or no run was found in more than a tenth of the runs),
2 for a mistake on the command line. Needs numpy (requirements.txt). Takes about 10 seconds for the default runs.
"""
import argparse
import math
import os
import sys
import time
from dataclasses import dataclass, replace

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "analysis"))
import stage_compare as sc          # noqa: E402  (the method under test, written from docs/stage-algorithm.md)

# ---- the driver (m/s, m/s^2) ----
A_LAT = 4.5                 # lateral acceleration the driver accepts
A_ACC = 3.0                 # acceleration limit: from rest, 11 m/s after 20 m, the "about 11 m/s, 40 km/h" of section 7
A_BRK = 5.0                 # braking limit: stopping from 14 m/s in 20 m, the "about 14 m/s" of section 7
V_MARKING = 18.0            # top speed of the marking drive

# ---- the GPS ----
RHO = 0.7                   # AR(1) coefficient of the position noise between two fixes one second apart
SIGMA_SPEED = 0.12          # Doppler speed noise, m/s
SIGMA_COURSE = 2.0          # course noise, degrees
COURSE_MIN_SPEED = 0.8      # below this measured speed the course is not given (-1)
HACC = 3.5                  # reported accuracy of every fix, m
FIX_PERIOD = 1.0            # seconds between fixes

# ---- the simulation ----
RUNS_PER_STAGE = 25         # a fresh marking drive (a fresh stage) for every this many runs
GRID_M = 0.25               # step of the speed-profile grid; the true times are exact for constant acceleration inside a cell
LEAD_M = 300.0              # road before the start mark and after the finish mark, for rolling starts and finishes
PARK_S = 12.0               # seconds the car sits still before it leaves and after it stops
ROLL_LEAD_M = 250.0         # a rolling run begins this far before the start line and ends this far after the finish line
SECTION7_SIGMA_M = 3.0      # the sigma along the road of a line crossing that section 7 uses
HAIRPIN_SPACINGS = (30.0, 20.0, 16.0, 12.0)     # extra case h: distance between neighbouring legs of the hairpin road, m
BAD_RUN_S = 5.0             # extra case h: a stage time this far off means the finish was found on the wrong leg


# =====================================================================================================================================
# The road: straights and circular arcs, in closed form
# =====================================================================================================================================

class Road:
    """A centre line made of straights and circular arcs, in a flat frame (x east, y north, metres; compass headings). `parts` is a list of
    ("straight", length) and ("arc", radius, degrees) where degrees > 0 turns right (clockwise) and < 0 turns left. The stage's marks are at
    arc positions `mark_s0 = lead` and `mark_s1 = length - run_out`; the road extends beyond them so that a run can roll through the lines.
    The origin (lat0, lon0) places the frame on the Earth with the same flat-frame formulas as stage_compare.Geo."""

    def __init__(self, parts, lead=LEAD_M, run_out=LEAD_M, heading0=70.0, lat0=2.7605, lon0=101.7355):
        self.geo = sc.Geo(lat0, lon0)
        starts, kinds, lengths, radii, turns, x0, y0, th0 = [], [], [], [], [], [], [], []
        s, x, y, th = 0.0, 0.0, 0.0, math.radians(heading0)
        for part in parts:
            starts.append(s)
            x0.append(x)
            y0.append(y)
            th0.append(th)
            if part[0] == "straight":
                kinds.append(0)
                lengths.append(part[1])
                radii.append(math.inf)
                turns.append(0.0)
                x, y = x + part[1] * math.sin(th), y + part[1] * math.cos(th)
            else:
                radius, angle = part[1], math.radians(part[2])
                kinds.append(1)
                lengths.append(abs(angle) * radius)
                radii.append(radius)
                turns.append(1.0 if angle > 0 else -1.0)
                sign = turns[-1]
                new_th = th + angle
                cx, cy = x + sign * radius * math.cos(th), y - sign * radius * math.sin(th)       # the centre of the arc
                x, y = cx + sign * radius * (-math.cos(new_th)), cy + sign * radius * math.sin(new_th)
                th = new_th
            s += lengths[-1]
        self.length = s
        self.mark_s0 = lead
        self.mark_s1 = s - run_out
        self.seg_s = np.array(starts)
        self.seg_kind = np.array(kinds)
        self.seg_len = np.array(lengths)
        self.seg_r = np.array(radii)
        self.seg_turn = np.array(turns)
        self.seg_x0, self.seg_y0, self.seg_th0 = np.array(x0), np.array(y0), np.array(th0)

    def at(self, s):
        """Position, compass heading (radians) and curvature (1/m, >= 0) of the centre line at arc positions `s` (an array)."""
        s = np.asarray(s, dtype=float)
        i = np.clip(np.searchsorted(self.seg_s, s, side="right") - 1, 0, len(self.seg_s) - 1)
        d = s - self.seg_s[i]
        th0, x0, y0 = self.seg_th0[i], self.seg_x0[i], self.seg_y0[i]
        arc = self.seg_kind[i] == 1
        radius = np.where(arc, self.seg_r[i], 1.0)
        sign = self.seg_turn[i]
        th = np.where(arc, th0 + sign * d / radius, th0)
        # straight
        xs, ys = x0 + d * np.sin(th0), y0 + d * np.cos(th0)
        # arc: centre c = p0 + sign*R*(cos th0, -sin th0); p = c + sign*R*(-cos th, sin th)
        cx, cy = x0 + sign * radius * np.cos(th0), y0 - sign * radius * np.sin(th0)
        xa, ya = cx - sign * radius * np.cos(th), cy + sign * radius * np.sin(th)
        return np.where(arc, xa, xs), np.where(arc, ya, ys), th, np.where(arc, 1.0 / radius, 0.0)

    def latlon(self, x, y):
        """Latitude and longitude (arrays) of flat-frame points, the inverse formulas of section 2."""
        lat = self.geo.lat0 + np.degrees(np.asarray(y) / sc.EARTH_RADIUS_M)
        lon = self.geo.lon0 + np.degrees(np.asarray(x) / (sc.EARTH_RADIUS_M * self.geo.cos_lat0))
        return lat, lon

    def nearest_s(self, x, y, step=0.5):
        """Arc position of the road point nearest to (x, y) (a brute-force search on a fine grid)."""
        ss = np.arange(0.0, self.length, step)
        xs, ys, _, _ = self.at(ss)
        return float(ss[np.argmin((xs - x) ** 2 + (ys - y) ** 2)])


def straight(length):
    return ("straight", length)


def arc(radius, degrees):
    return ("arc", radius, degrees)


def mountain_road():
    """A 3.1 km road between the marks (plus 300 m before and after): straights of 200 to 600 m and arcs of radius 60 to 200 m. Both the start and
    the finish line are on straights long enough for a car to reach 30 m/s, so a rolling run can pass them at its top speed."""
    return Road([straight(LEAD_M), straight(350), arc(120, 90), straight(200), arc(60, -150), straight(250), arc(200, 60), straight(600),
                 arc(90, -100), straight(400), arc(150, 45), straight(500), straight(LEAD_M)])


def hairpin_road(spacing=30.0):
    """A hillside road: 200 m straights joined by 180 degree turns (radius spacing / 2), so that neighbouring legs run `spacing` metres apart in
    opposite directions. 30 m apart is the road of case (e); the extra case h makes it tighter."""
    parts = [straight(60), straight(220)]
    for k in range(6):
        parts += [arc(spacing / 2.0, 180 if k % 2 == 0 else -180), straight(200)]
    parts += [straight(60)]
    return Road(parts, lead=60.0, run_out=60.0, heading0=0.0)


# =====================================================================================================================================
# The driver: a speed profile along the road, exact times
# =====================================================================================================================================

class Drive:
    """The motion of a car along `road` from arc position s_from to s_to under the driver's limits: speed <= min(top speed, sqrt(a_lat / curvature)),
    acceleration <= A_ACC, braking <= A_BRK, starting at speed `v_start` and ending at `v_end` (None = free). Speed squared is limited on a grid of
    GRID_M (the forward and the backward pass are running minimums, so they are exact); inside a cell the acceleration is constant, so times and
    positions are exact for the model. `t_depart` is the time the car leaves s_from (before that it sits there at speed 0 if v_start is 0)."""

    def __init__(self, road, s_from, s_to, v_start, v_end, v_top, t_depart=0.0):
        self.road = road
        self.v_end = v_end
        self.t_depart = t_depart
        n = int(math.ceil((s_to - s_from) / GRID_M))
        s = np.linspace(s_from, s_to, n + 1)
        kappa = road.at(s)[3]
        w_lim = np.minimum(v_top ** 2, A_LAT / np.maximum(kappa, 1e-12))          # speed squared
        if v_start is not None:
            w_lim[0] = min(w_lim[0], v_start ** 2)
        if v_end is not None:
            w_lim[-1] = min(w_lim[-1], v_end ** 2)
        w = 2 * A_ACC * s + np.minimum.accumulate(w_lim - 2 * A_ACC * s)           # forward pass: acceleration limit
        w = -2 * A_BRK * s + np.minimum.accumulate((w + 2 * A_BRK * s)[::-1])[::-1]  # backward pass: braking limit
        v = np.sqrt(np.maximum(w, 0.0))
        self.s, self.w, self.v = s, w, v
        ds = np.diff(s)
        self.t = np.concatenate([[0.0], np.cumsum(2.0 * ds / (v[:-1] + v[1:]))])    # constant acceleration inside a cell
        self.accel = (w[1:] - w[:-1]) / (2.0 * ds)
        self.duration = self.t[-1]
        self.s_from, self.s_to = s_from, s_to

    def time_at(self, s):
        """Seconds after departure at which the car is at arc position s (inside [s_from, s_to])."""
        i = int(np.clip(np.searchsorted(self.s, s, side="right") - 1, 0, len(self.s) - 2))
        f = (s - self.s[i]) / (self.s[i + 1] - self.s[i])
        v_here = math.sqrt(max(self.w[i] + (self.w[i + 1] - self.w[i]) * f, 0.0))
        if self.v[i] + v_here <= 0.0:                    # standing still at s_from (only if s is outside [s_from, s_to])
            return float(self.t[i])
        return float(self.t[i] + 2.0 * (s - self.s[i]) / (self.v[i] + v_here))

    def speed_at(self, s):
        """Speed (m/s) at arc position s."""
        i = int(np.clip(np.searchsorted(self.s, s, side="right") - 1, 0, len(self.s) - 2))
        f = (s - self.s[i]) / (self.s[i + 1] - self.s[i])
        return math.sqrt(max(self.w[i] + (self.w[i + 1] - self.w[i]) * f, 0.0))

    def state(self, t):
        """Arc position and speed (arrays) at times `t` (seconds on the run's clock): parked at s_from before t_depart, parked at s_to after
        arriving (only meaningful if v_end is 0)."""
        tau = np.clip(np.asarray(t, dtype=float) - self.t_depart, 0.0, self.duration)
        i = np.clip(np.searchsorted(self.t, tau, side="right") - 1, 0, len(self.t) - 2)
        dt = tau - self.t[i]
        s = self.s[i] + self.v[i] * dt + 0.5 * self.accel[i] * dt ** 2
        v = self.v[i] + self.accel[i] * dt
        return np.clip(s, self.s_from, self.s_to), np.maximum(v, 0.0)


# =====================================================================================================================================
# The GPS
# =====================================================================================================================================

def ar1(n, sigma, rng):
    """n values of a zero-mean AR(1) noise with standard deviation `sigma` and coefficient RHO, started in its stationary state."""
    out = np.empty(n)
    shocks = rng.standard_normal(n)
    out[0] = sigma * shocks[0]
    gain = math.sqrt(1.0 - RHO * RHO) * sigma
    for k in range(1, n):
        out[k] = RHO * out[k - 1] + gain * shocks[k]
    return out


def make_fixes(road, drive, times, sigma, rng):
    """GPS fixes (stage_compare.Fix) of the car at `times`: the true position plus AR(1) noise of `sigma` metres per axis, the Doppler speed with
    0.12 m/s noise, the course with 2 degrees of noise (-1 below 0.8 m/s), hAcc 3.5."""
    s, v = drive.state(times)
    x, y, theta, _ = road.at(s)
    n = len(times)
    px = x + (ar1(n, sigma, rng) if sigma > 0 else 0.0)
    py = y + (ar1(n, sigma, rng) if sigma > 0 else 0.0)
    lat, lon = road.latlon(px, py)
    speed = np.maximum(0.0, v + (rng.normal(0.0, SIGMA_SPEED, n) if sigma > 0 else 0.0))
    course = np.degrees(theta) + (rng.normal(0.0, SIGMA_COURSE, n) if sigma > 0 else 0.0)
    course = np.where(speed < COURSE_MIN_SPEED, -1.0, np.mod(course, 360.0))
    return [sc.Fix(float(times[k]), float(lat[k]), float(lon[k]), float(speed[k]), float(course[k]), HACC) for k in range(n)]


def fix_times(duration, rng):
    """Times of 1 Hz fixes from a random phase (so that the lines fall at random places between two fixes) to `duration`."""
    t0 = rng.uniform(0.0, FIX_PERIOD)
    return t0 + FIX_PERIOD * np.arange(int((duration - t0) / FIX_PERIOD) + 1)


# =====================================================================================================================================
# Marking a stage (section 4) and building it
# =====================================================================================================================================

def accept_mark(fixes, now):
    """Section 4 'Marking': the position the app would take for a mark pressed at time `now`, or None if it would not accept it. It accepts
    only if the last 4 fixes all have 0 <= speed <= 0.8 and 0 <= hAcc <= 12, lie within 6 m of their average, and the newest is at most 3 s old.
    The mark is the average of up to the latest 5 still fixes (in the flat frame at the newest fix, converted back), rounded to 7 decimals as
    written in the MARK events. `fixes` are the fixes that have arrived, oldest first."""
    def still(f):
        return 0 <= f.speed <= 0.8 and 0 <= f.hacc <= 12
    if len(fixes) < 4 or now - fixes[-1].t > 3.0:
        return None
    last = fixes[-4:]
    if not all(still(f) for f in last):
        return None
    geo = sc.Geo(fixes[-1].lat, fixes[-1].lon)
    pts = [geo.xy(f.lat, f.lon) for f in last]
    mx, my = sum(p[0] for p in pts) / 4.0, sum(p[1] for p in pts) / 4.0
    if any(math.hypot(p[0] - mx, p[1] - my) > 6.0 for p in pts):
        return None
    use = [f for f in fixes[-5:] if still(f)]
    use = use[-5:]
    pts = [geo.xy(f.lat, f.lon) for f in use]
    lat, lon = geo.latlon(sum(p[0] for p in pts) / len(pts), sum(p[1] for p in pts) / len(pts))
    return round(lat, 7), round(lon, 7)


def simulate_marking(road, sigma, rng):
    """A marking drive on `road`: parked at the start mark, MARK_START, a standing start, a braking stop at the finish mark, MARK_FINISH.
    Returns (start mark, finish mark, drive fixes) as build_stage wants them, or None if a mark was not accepted."""
    s0, s1 = road.mark_s0, road.mark_s1
    t_depart = PARK_S + rng.uniform(0.0, 3.0)
    drive = Drive(road, s0, s1, 0.0, 0.0, V_MARKING, t_depart=t_depart)
    t_end = t_depart + drive.duration + PARK_S
    fixes = make_fixes(road, drive, fix_times(t_end, rng), sigma, rng)
    start = finish = None
    i0 = i1 = None
    t_arrive = t_depart + drive.duration
    for k, f in enumerate(fixes):                 # the driver presses MARK_START once the car has sat still for 8 s, and again each second until accepted
        if start is None and 8.0 <= f.t < t_depart:
            start = accept_mark(fixes[:k + 1], f.t)
            i0 = k if start is not None else None
        if finish is None and f.t >= t_arrive + 8.0:          # and MARK_FINISH 8 s after the car has stopped at the finish mark
            finish = accept_mark(fixes[:k + 1], f.t)
            i1 = k if finish is not None else None
    if start is None or finish is None:
        return None
    return start, finish, fixes[i0 + 1:i1 + 1]


@dataclass
class World:
    """One stage built from one marking drive, with the true places where the road crosses its two lines."""
    stage: object
    road: Road
    s_start: float          # arc position at which the true centre line crosses the start line
    s_finish: float


def crossing_arc(road, gate, geo_stage, guess, window=60.0):
    """The arc position at which the true centre line of `road` crosses the stage line `gate` (a stage_compare.Gate in the frame of the stage whose
    Geo is `geo_stage`), looked for within `window` metres of arc position `guess`: the first sign change of `along` on a 5 cm grid, then bisected
    on the formulas. (The guess comes from where the line should be, 20 m from a mark; the nearest road point to the line would do on most roads,
    but on a hairpin road it can be a neighbouring leg.) Returns None if the road does not cross the line there."""
    lat, lon = geo_stage.latlon(gate.cx, gate.cy)
    cx, cy = road.geo.xy(lat, lon)
    ss = np.arange(max(0.0, guess - window), min(road.length, guess + window), 0.05)
    x, y, _, _ = road.at(ss)
    q = (x - cx) * gate.ux + (y - cy) * gate.uy
    hits = np.nonzero((q[:-1] < 0) & (q[1:] >= 0))[0]
    if len(hits) == 0:
        return None
    i = int(hits[np.argmin(np.abs(ss[hits] - guess))])
    lo, hi = float(ss[i]), float(ss[i + 1])
    for _ in range(60):
        mid = 0.5 * (lo + hi)
        xm, ym, _, _ = road.at(np.array([mid]))
        if (xm[0] - cx) * gate.ux + (ym[0] - cy) * gate.uy < 0:
            lo = mid
        else:
            hi = mid
    return 0.5 * (lo + hi)


def build_world(road, sigma, rng, tries=20):
    """Simulate a marking drive and build the stage from it with stage_compare.build_stage; find where the road crosses its lines."""
    for _ in range(tries):
        marked = simulate_marking(road, sigma, rng)
        if marked is None:
            continue
        start, finish, drive = marked
        try:
            stage = sc.build_stage(start, finish, drive, "st_sim", "Simulated road")
        except sc.StageRefused:
            continue
        s_start = crossing_arc(road, stage.start_gate, stage.geo, road.mark_s0 + sc.LINE_INSET_M)
        s_finish = crossing_arc(road, stage.finish_gate, stage.geo, road.mark_s1 - sc.LINE_INSET_M)
        if s_start is None or s_finish is None:
            continue
        return World(stage, road, s_start, s_finish)
    return None


# =====================================================================================================================================
# One run
# =====================================================================================================================================

@dataclass
class Scenario:
    """What a case simulates."""
    key: str
    label: str
    road_factory: object
    sigma: float                # GPS noise, metres per axis
    start: str                  # "rolling" or "standing"
    finish: str                 # "rolling" or "stop"
    v_top: tuple                # the driver's top speed in a run is drawn from this (low, high), m/s
    park_before_line: object    # None: parked at the start mark; metres: parked this far before the start line
    runs: int


@dataclass
class Outcome:
    """What one scored run gave."""
    error: float                # measured minus true stage time, s
    true_time: float
    v_start: float              # true speeds at the two lines, m/s
    v_finish: float
    result: object              # the RunResult
    drive: object
    fixes: list
    world: object


def simulate_run(world, scn, rng):
    """One run of the stage: a driver with a random top speed, fixes from a random phase, scored by stage_compare.score_recording. Returns an
    Outcome, or None if the scorer did not find exactly one finished run."""
    road = world.road
    v_top = rng.uniform(*scn.v_top)
    if scn.start == "rolling":
        s_from, v_start, t_depart = max(0.0, world.s_start - ROLL_LEAD_M), v_top, 0.0       # already at its top speed on the straight
    else:
        s_from = road.mark_s0 if scn.park_before_line is None else world.s_start - scn.park_before_line
        v_start, t_depart = 0.0, PARK_S + rng.uniform(0.0, 3.0)
    if scn.finish == "stop":
        s_to, v_end = road.mark_s1, 0.0
    else:
        s_to, v_end = min(road.length, world.s_finish + ROLL_LEAD_M), None
    if not s_from < world.s_start < world.s_finish < s_to:          # a line outside the part of the road that is driven: no run to measure
        return None
    drive = Drive(road, s_from, s_to, v_start, v_end, v_top, t_depart=t_depart)
    t_end = t_depart + drive.duration + (PARK_S if v_end == 0.0 else 0.0)
    fixes = make_fixes(road, drive, fix_times(t_end, rng), scn.sigma, rng)
    finished = [r for r in sc.score_recording(world.stage, fixes, "sim.csv") if r.finished]
    if len(finished) != 1:
        return None
    r = finished[0]
    true_time = drive.time_at(world.s_finish) - drive.time_at(world.s_start)
    return Outcome(r.total_s - true_time, true_time, drive.speed_at(world.s_start), drive.speed_at(world.s_finish), r, drive, fixes, world)


def formula_sigma(outcomes):
    """Section 7: the standard deviation of a stage time that the formula predicts, 3 m * sqrt(1/v_start^2 + 1/v_finish^2), taken over the runs
    as the root of the mean variance (the speeds at the lines differ from run to run)."""
    var = [SECTION7_SIGMA_M ** 2 * (1.0 / o.v_start ** 2 + 1.0 / o.v_finish ** 2) for o in outcomes]
    return math.sqrt(sum(var) / len(var))


def run_case(scn, runs, rng):
    """Simulate `runs` runs of a scenario, with a fresh stage for every RUNS_PER_STAGE of them. Returns (outcomes, worlds, number of failures)."""
    outcomes, worlds, failures = [], [], 0
    while len(outcomes) + failures < runs:
        world = build_world(scn.road_factory(), scn.sigma, rng)
        if world is None:
            failures += min(RUNS_PER_STAGE, runs - len(outcomes) - failures)
            continue
        worlds.append(world)
        for _ in range(min(RUNS_PER_STAGE, runs - len(outcomes) - failures)):
            o = simulate_run(world, scn, rng)
            if o is None:
                failures += 1
            else:
                outcomes.append(o)
    return outcomes, worlds, failures


# =====================================================================================================================================
# The cases
# =====================================================================================================================================

def stage_moved(world, metres):
    """The same stage with its start line `metres` further along the true road (the gate keeps its direction; the route starts at the new place).
    Returns (the moved Stage, the arc position at which the true road crosses its start line)."""
    stage, road = world.stage, world.road
    x, y, _, _ = road.at(np.array([world.s_start + metres]))
    lat, lon = road.latlon(x, y)
    nx, ny = stage.geo.xy(float(lat[0]), float(lon[0]))                      # the new place, in the frame of the stage
    d, _ = stage.route.project(nx, ny, 0.0, 0.0, stage.route.length)         # how far along the stage's route it is
    points = [(nx, ny)] + [pt for pt, s in zip(stage.route_points, stage.route.s) if s > d + 0.01]
    gate = sc.Gate(nx, ny, stage.start_gate.bearing, stage.start_gate.half)
    moved = sc.Stage(stage.id, stage.name, stage.created_utc_ms, stage.origin, stage.start_mark, stage.finish_mark, gate, stage.finish_gate, points)
    return moved, crossing_arc(road, gate, stage.geo, world.s_start + metres)


def case_moved_start(scn, runs, rng, metres=5.0):
    """(d) The same runs scored against a stage and against the stage with its start line `metres` further along the road: the change in the
    stage time. Returns (measured shrink, true shrink, failures), arrays of (time against the first stage) - (time against the moved one)."""
    measured, true, failures = [], [], 0
    done = 0
    while done < runs:
        world = build_world(scn.road_factory(), scn.sigma, rng)
        if world is None:
            failures += 1
            done += 1
            continue
        moved, s_moved = stage_moved(world, metres)
        for _ in range(min(RUNS_PER_STAGE, runs - done)):
            done += 1
            o = simulate_run(world, scn, rng)
            if o is None:
                failures += 1
                continue
            again = [r for r in sc.score_recording(moved, o.fixes, "sim.csv") if r.finished]
            if len(again) != 1:
                failures += 1
                continue
            measured.append(o.result.total_s - again[0].total_s)
            true.append(o.drive.time_at(s_moved) - o.drive.time_at(world.s_start))
    return np.array(measured), np.array(true), failures


def profile_errors(outcomes):
    """(e) Profile error in metres of every point of every run: the measured profile time at route distance 25 k minus the true time at which the
    car is at the road point that belongs to the route point at that distance, times the true speed there. The road point is the nearest one
    among those that lie within 120 m of the previous one, ahead, and run the way the route runs there (so that a point near a hairpin finds its
    own leg, never a neighbouring one). Returns the array of errors (m)."""
    errors = []
    cache = {}
    for o in outcomes:
        world, stage, road = o.world, o.world.stage, o.world.road
        if id(road) not in cache:
            grid = np.arange(0.0, road.length, 0.5)
            xs, ys, th, _ = road.at(grid)
            cache[id(road)] = (grid, xs, ys, np.degrees(th) % 360.0)
        grid, xs, ys, heading = cache[id(road)]
        t0 = o.drive.time_at(world.s_start)
        step = o.result.step_m
        s_true = world.s_start
        for k, measured in enumerate(o.result.profile):
            d = min(k * step, stage.route.length)
            px, py = stage.route.point_at(d)
            j = stage.route.segment_at(d)
            route_heading = sc.bearing(stage.route.x[j + 1] - stage.route.x[j], stage.route.y[j + 1] - stage.route.y[j])
            lat, lon = stage.geo.latlon(px, py)
            rx, ry = road.geo.xy(lat, lon)
            diff = np.abs((heading - route_heading + 180.0) % 360.0 - 180.0)
            allowed = (grid >= s_true) & (grid <= s_true + 120.0) & (diff < 60.0)
            dist2 = np.where(allowed, (xs - rx) ** 2 + (ys - ry) ** 2, np.inf)
            if not np.isfinite(dist2).any():              # no road point runs that way near here: the route itself is wrong, skip the point
                continue
            s_true = min(float(grid[np.argmin(dist2)]), world.s_finish)
            t_true = o.drive.time_at(s_true) - t0
            errors.append((measured - t_true) * o.drive.speed_at(s_true))
    return np.array(errors)


# =====================================================================================================================================
# The report
# =====================================================================================================================================

def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0].strip(), formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--runs", type=int, help="runs per case (default 300 for a, 500 for b and c, 300 for d, 40 for e, 100 for each road of h)")
    ap.add_argument("--seed", type=int, default=1, help="random seed (default 1); every case has its own stream derived from it")
    ap.add_argument("--quick", action="store_true", help="one tenth of the default number of runs")
    ap.add_argument("--cases", default="abcde", help="letters of the cases to run (default abcde); extras: x = noise-free check, "
                    "p = (a) again at 30 m/s, h = (e) with legs 30, 20, 16 and 12 m apart")
    args = ap.parse_args(argv)
    if args.seed < 0:
        ap.error("--seed must be 0 or more")
    default_runs = {"x": 20, "a": 300, "p": 300, "b": 500, "c": 500, "d": 300, "e": 40, "h": 100}
    runs = dict((k, args.runs if args.runs else (max(10, v // 10) if args.quick else v)) for k, v in default_runs.items())
    t0 = time.time()

    def rng_for(key):
        """Every case has its own random stream, so that a case gives the same numbers whichever other cases are run with it."""
        return np.random.default_rng([args.seed, ord(key)])

    mountain = mountain_road
    scenarios = {
        "x": Scenario("x", "noise-free check: rolling, no GPS noise", mountain, 0.0, "rolling", "rolling", (15.0, 25.0), None, runs["x"]),
        "a": Scenario("a", "rolling start and finish, 15-25 m/s, 2.5 m noise", mountain, 2.5, "rolling", "rolling", (15.0, 25.0), None, runs["a"]),
        "p": Scenario("p", "(a) again at 30 m/s, 2.5 m noise (extra)", mountain, 2.5, "rolling", "rolling", (30.0, 30.0), None, runs["p"]),
        "b": Scenario("b", "standing start at the mark, braking stop, 3 m noise", mountain, 3.0, "standing", "stop", (20.0, 30.0), None, runs["b"]),
        "c": Scenario("c", "as (b), car parked 10 m before the start line", mountain, 3.0, "standing", "stop", (20.0, 30.0), 10.0, runs["c"]),
        "d": Scenario("d", "start line moved 5 m along, rolling at 30 m/s, 2.5 m noise", mountain, 2.5, "rolling", "rolling", (30.0, 30.0), None, runs["d"]),
        "e": Scenario("e", "hairpin road, legs 30 m apart, 3 m noise", hairpin_road, 3.0, "standing", "stop", (14.0, 14.0), None, runs["e"]),
        "h": Scenario("h", "hairpin roads, legs 30 to 12 m apart, 3 m noise (extra)", hairpin_road, 3.0, "standing", "stop", (14.0, 14.0), None, runs["h"]),
    }
    # (case letter, what the section 9 table of docs/stage-algorithm.md says about it)
    spec9 = {"a": "0.12 (formula 0.14)", "b": "0.34, mean +0.01 (formula 0.35)", "c": "0.39, mean +0.03", "d": "-0.167", "e": "rms 4 m, worst 19 m"}
    rows, notes, status = [], [], 0

    def no_row(key, label, failures):
        """The row of a case that gave nothing to measure."""
        rows.append((key, label, 0, failures, "-", "-", "-", "-", spec9.get(key, "-")))

    def time_row(key, label, o_list, failures):
        """The row of a case whose result is the error of the stage time."""
        err = np.array([o.error for o in o_list])
        pred = "-" if key == "x" else "%.3f" % formula_sigma(o_list)           # the formula is about a noisy GPS
        mean_v = (np.mean([o.v_start for o in o_list]), np.mean([o.v_finish for o in o_list]))
        rows.append((key, label, len(err), failures, "%+.3f" % err.mean(), "%.3f" % err.std(ddof=1), pred, "%.1f / %.1f" % mean_v, spec9.get(key, "-")))

    def profile_row(key, label, o_list, failures, spec_text):
        """The row of a case whose result is the error of the profile, in metres."""
        err = profile_errors(o_list)
        if not err.size:
            return no_row(key, label, failures)
        rows.append((key, label, len(o_list), failures, "rms %.1f m" % math.sqrt(np.mean(err ** 2)), "worst %.1f m" % np.abs(err).max(), "-",
                     "%.1f / %.1f" % (np.mean([o.v_start for o in o_list]), np.mean([o.v_finish for o in o_list])), spec_text))

    for key in args.cases:
        if key not in scenarios:
            ap.error("unknown case %r (choose from abcdehpx)" % key)
    for key in [k for k in "xapbcdeh" if k in args.cases]:
        scn = scenarios[key]
        print("case %s: %s ..." % (key, scn.label), end=" ", flush=True)
        t1 = time.time()
        rng = rng_for(key)
        if key == "d":
            measured, true, failures = case_moved_start(scn, scn.runs, rng)
            if failures > scn.runs // 10 or not len(measured):
                status = 1
            if len(measured):
                rows.append((key, scn.label, len(measured), failures, "%+.3f" % -measured.mean(), "%.4f" % (measured - true).std(ddof=1), "-",
                             "true %+.3f" % -true.mean(), spec9[key]))
            else:
                no_row(key, scn.label, failures)
        elif key == "h":
            for spacing in HAIRPIN_SPACINGS:           # the road of (e) with its legs closer and closer together
                tight = replace(scn, road_factory=lambda sp=spacing: hairpin_road(sp))
                outcomes, worlds, failures = run_case(tight, tight.runs, rng)
                bad = sum(1 for o in outcomes if abs(o.error) > BAD_RUN_S)
                profile_row(key, "hairpin legs %.0f m apart, 3 m noise: |time error| > %.0f s in %d of %d runs" % (spacing, BAD_RUN_S, bad, len(outcomes)),
                            outcomes, failures, spec9["e"] if spacing == 30.0 else "-")
        else:
            outcomes, worlds, failures = run_case(scn, scn.runs, rng)
            if failures > scn.runs // 10 or not outcomes:
                status = 1
            d0 = [w.s_start - w.road.mark_s0 for w in worlds]
            d1 = [w.road.mark_s1 - w.s_finish for w in worlds]
            if worlds:
                notes.append("(%s) the true road distance from the start mark to the start line: %.1f m (sd %.1f); from the finish line to the finish mark: "
                             "%.1f m (sd %.1f); over %d stage(s), one marking drive each" % (key, np.mean(d0), np.std(d0), np.mean(d1), np.std(d1), len(worlds)))
            if not outcomes:
                no_row(key, scn.label, failures)
            elif key == "e":
                profile_row(key, scn.label, outcomes, failures, spec9[key])
            else:
                time_row(key, scn.label, outcomes, failures)
        print("%.0f s" % (time.time() - t1))

    head = ("case", "what", "runs", "failed", "mean err s", "std err s", "formula s", "speed at lines m/s", "docs section 9")
    print("\nError of the stage time (measured minus true), seconds; (d) is the change of the time, (e) the profile error in metres")
    widths = [max(len(str(r[i])) for r in [head] + rows) for i in range(len(head))]
    for r in [head] + rows:
        print("  ".join(str(r[i]).ljust(widths[i]) for i in range(len(head))))
    for note in notes:
        print("\n" + note, end="")
    print("\n\nformula s = the section 7 prediction 3 m * sqrt(1/v_start^2 + 1/v_finish^2) with the true speeds at the lines (root of the mean variance)")
    print("failed = runs in which the scorer did not find exactly one finished run (or a line lay outside the part of the road that was driven); they are "
          "left out of the figures")
    print("total time %.0f s, seed %d" % (time.time() - t0, args.seed))
    return status


if __name__ == "__main__":
    sys.exit(main())

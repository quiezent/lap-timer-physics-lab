#!/usr/bin/env python3
"""
stage_compare.py - make a road stage from a marking drive, time every run of it in a GPS recording, and compare runs

    python analysis/stage_compare.py build MARKING.csv [--id ID] [--name NAME] [--out stage.txt]
    python analysis/stage_compare.py score STAGE SESSION.csv [SESSION2.csv ...] [--out-dir DIR]
    python analysis/stage_compare.py compare STAGE [RUN.txt|SESSION.csv ...] [--bundle BUNDLE.zip]
                                              [--against best|previous|RUNID] [--plot FILE.png]
    python analysis/stage_compare.py selftest [--fixtures DIR] [--real-log CSV]

A second, independent implementation of the stage method of docs/stage-algorithm.md, written from that page alone (nothing here was copied from or
checked against the Android source while it was written). `build` turns a marking recording (the MARK_START / MARK_FINISH events and the GPS fixes
between them) into a stage file; `score` finds every run of a stage in one or more session CSVs and writes one run file per run (docs/stage-format.md);
`compare` puts runs of one stage side by side: the difference in total time, a table of sections, the "about the same" verdict with its uncertainty
band, and the warnings (the stage may be a stage.txt, a folder with stage.txt and run_*.txt, or a bundle zip, which is read the careful way
docs/stage-format.md demands); `selftest` recomputes every expected result in analysis/stage_fixtures from the inputs with this code and compares
them with the rule of that folder's README. The functions below are numbered after the sections of docs/stage-algorithm.md they implement, and the
module can be imported (`import stage_compare`) by other scripts, e.g. sim/simulate_stage.py.

Exit status 0 if everything worked (selftest: every group passed), 1 if a stage was refused, a file was bad, or any selftest group failed,
2 for a mistake on the command line.
Fast (the whole selftest, including the 28 MB real recording, takes under a second). Needs only the standard library; matplotlib is imported
only for --plot, and without it the comparison is still printed and the status is 1 to tell you the figure is missing.
"""
import argparse
import csv
import datetime
import glob
import io
import math
import os
import re
import sys
import time
import zipfile
from dataclasses import dataclass

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
DEFAULT_FIXTURES = os.path.join(HERE, "stage_fixtures")
DEFAULT_REAL_LOG = os.path.join(REPO, "data", "sepang-2026-10-13", "session_20261013_095638.csv")

# ---- constants of the method (section number in brackets) ----------------------------------------------------------------------------
EARTH_RADIUS_M = 6378137.0          # [2] the flat frame
GATE_HALF_M = 25.0                  # [3] half width of a stage line
STAGE_MIN_SPEED = 1.5               # [3] crossing(): slowest car that can cross a stage line, m/s
STAGE_MIN_FORWARD = 0.25            # [3] crossing(): cosine between the reported velocity and the line's direction
LAP_MIN_SPEED = 3.0                 # [3] the lap timer's line (no forward test)
MOVING_SPEED = 1.5                  # [4] a drive fix with this speed is "moving"
MAX_BUILD_HACC = 30.0               # [4] drive fixes less accurate than this are skipped
MIN_VERTEX_GAP_M = 3.0              # [4] drive fixes closer than this to the last vertex are skipped
MIN_STAGE_LENGTH_M = 300.0          # [4]
MIN_START_FINISH_M = 150.0          # [4]
LINE_INSET_M = 20.0                 # [4] lines are 20 m in from the marks
LINE_DIRECTION_SPAN_M = 40.0        # [4] bearing of a line from points 40 m along the route
STEP_M = 25.0                       # [6] spacing of the profile
APPROACH, RUNNING, POST, FINISHED, DNF = "APPROACH", "RUNNING", "POST", "FINISHED", "DNF"   # [6] phases
MAX_FIX_GAP_S = 3.0                 # [6] "near": consecutive fixes at most this far apart are joined into a crossing
FALSE_START_S_LIMIT = 300.0         # [6] a start line crossed again before this route distance is a false start
MIN_STAGE_TIME_S = 10.0             # [6] a finish crossing sooner than this after the start is not a finish
MIN_FINISH_FRACTION = 0.7           # [6] ... nor one before 70 % of the route
POST_SECONDS = 5.0                  # [6] a run is FINISHED 5 s after the finish crossing
SIGMA_ALONG_M = 3.0                 # [7] uncertainty of a line crossing along the road
SECTION_CHOICES_M = (250, 500, 1000, 2000, 5000, 10000)    # [7]
MAX_SECTIONS = 12
WARN_GAP_S, WARN_OFF_ROUTE_M, WARN_LINE_GAP_S, WARN_ACCURACY_M = 5.0, 100.0, 2.5, 25.0     # [7]
UNKNOWN_LINE_SPEED = 12.0           # [7] used for sigma when a run has no line speed

# ---- limits of the bundle reader (docs/stage-format.md, "The bundle") ----------------------------------------------------------------
BUNDLE_ROOT = "tracklogger-stages"
MAX_BUNDLE_BYTES = 50 * 1024 * 1024
MAX_BUNDLE_ENTRIES = 5000
MAX_ENTRY_BYTES = 5 * 1024 * 1024
MAX_BUNDLE_TOTAL_BYTES = 32 * 1024 * 1024    # the sum of all entries, expanded (5000 entries of 5 MB each could otherwise reach 25 GB); the app's cap too
_ID_CHARS = "A-Za-z0-9_-"
_ID_RE = re.compile("[%s]{1,64}" % _ID_CHARS)
# The top folder is optional (the app reads a zip made by hand from its `stages` folder too); everything else must match exactly.
_BUNDLE_STAGE_RE = re.compile("(?:" + BUNDLE_ROOT + "/)?([%s]{1,64})/stage\\.txt" % _ID_CHARS)
_BUNDLE_RUN_RE = re.compile("(?:" + BUNDLE_ROOT + "/)?([%s]{1,64})/run_([%s]{1,64})\\.txt" % (_ID_CHARS, _ID_CHARS))


# =====================================================================================================================================
# Small helpers: numbers, text
# =====================================================================================================================================

_INT_RE = re.compile(r"[+-]?[0-9]+")
_DEC_RE = re.compile(r"[+-]?(?:[0-9]+\.?[0-9]*|\.[0-9]+)(?:[eE][+-]?[0-9]+)?")


def parse_int(text):
    """An integer written with ASCII digits (optional sign), or None. (Python's int() would also take '1_0'.)"""
    text = text.strip()
    return int(text) if _INT_RE.fullmatch(text) else None


def parse_float(text):
    """A decimal number, 'nan' or 'inf', or None if the text is not one. (Python's float() would also take '1_0'.)"""
    text = text.strip()
    if _DEC_RE.fullmatch(text):
        return float(text)
    if text.lower().lstrip("+-") in ("nan", "inf", "infinity"):
        return float(text)
    return None


def finite_or(value, default):
    """`value` if it is a finite number, else `default` (used to turn NaN speeds or accuracies into 'not given')."""
    return value if math.isfinite(value) else default


def round_half_up(x):
    """Round to the nearest integer, halves upward (what 'round' means for a time in milliseconds)."""
    return int(math.floor(x + 0.5))


def format_clock(seconds):
    """194.808 -> '3:14.8' (minutes can pass 59: 3725.0 -> '62:05.0')."""
    tenths = round_half_up(seconds * 10.0)
    return "%d:%04.1f" % (tenths // 600, (tenths % 600) / 10.0)


def plain(text):
    """Make a free text safe for a one-line file value: no control characters, commas or semicolons."""
    text = "".join(" " if (ord(c) < 32 or ord(c) == 127 or c in ",;") else c for c in text)
    return " ".join(text.split())


# =====================================================================================================================================
# Section 1. Fixes
# =====================================================================================================================================

@dataclass
class Fix:
    """Section 1: one GPS fix. `speed` m/s over ground, `bearing` the direction of travel in degrees, `hacc` the reported accuracy in
    metres; these three are negative when the phone did not give them. `t` is in seconds."""
    t: float
    lat: float
    lon: float
    speed: float = -1.0
    bearing: float = -1.0
    hacc: float = -1.0


@dataclass
class Event:
    """An `E` row of a session CSV: its name, its time in microseconds, the values after the name (text), and `nfix`, the number of usable
    fixes read before it (which is what 'between MARK_START and MARK_FINISH' is measured in)."""
    name: str
    t_us: int
    values: list
    nfix: int


class Session:
    """What read_session() found in a session CSV: the usable fixes in file order, the events, the start time of the recording."""

    def __init__(self, path):
        self.path = path
        self.name = os.path.basename(path)
        self.start_utc_ms = None        # from the '# start_utc_ms,<integer>' header line; None if absent
        self.fixes = []                 # usable fixes in file order (not yet sorted)
        self.events = []
        self.n_bad_numbers = 0          # L rows with 12 fields whose numbers did not parse
        self.n_unusable = 0             # fixes dropped by the usable test


def is_usable(fix):
    """Section 1: t, lat, lon are numbers, |lat| <= 90 and |lon| <= 180."""
    return (math.isfinite(fix.t) and math.isfinite(fix.lat) and math.isfinite(fix.lon)
            and abs(fix.lat) <= 90.0 and abs(fix.lon) <= 180.0)


def fix_from_row(fields):
    """Section 1: the Fix of an `L` row split on commas (exactly 12 fields), or None if a number it needs does not parse.
    Fields counted from 1 with the kind letter as field 1: t_us 2, lat 3, lon 4, speed 6, bearing 7, hAcc 8."""
    t_us = parse_int(fields[1])
    lat, lon = parse_float(fields[2]), parse_float(fields[3])
    speed, bearing, hacc = parse_float(fields[5]), parse_float(fields[6]), parse_float(fields[7])
    if t_us is None or None in (lat, lon, speed, bearing, hacc):
        return None
    # a speed, course or accuracy that is not a finite number is as good as not given
    return Fix(t_us / 1e6, lat, lon, finite_or(speed, -1.0), finite_or(bearing, -1.0), finite_or(hacc, -1.0))


def read_session(path):
    """Section 1 (`LogReader`): read the `L` rows (exactly 12 fields), the `E` rows (at least 3 fields) and the `# start_utc_ms,` header of a
    session CSV; every other row (the A and G sensor rows are most of a 28 MB file) is skipped as fast as possible."""
    ses = Session(path)
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            if line.startswith("L,"):
                fields = line.rstrip("\r\n").split(",")
                if len(fields) != 12:
                    continue
                fix = fix_from_row(fields)
                if fix is None:
                    ses.n_bad_numbers += 1
                elif not is_usable(fix):
                    ses.n_unusable += 1
                else:
                    ses.fixes.append(fix)
            elif line.startswith("E,"):
                fields = line.rstrip("\r\n").split(",")
                t_us = parse_int(fields[1]) if len(fields) >= 3 else None
                if t_us is not None:
                    ses.events.append(Event(fields[2], t_us, fields[3:], len(ses.fixes)))
            elif line.startswith("# start_utc_ms,"):
                value = parse_int(line.split(",", 1)[1])
                if value is not None and ses.start_utc_ms is None:
                    ses.start_utc_ms = value
    return ses


def clean_fixes(fixes):
    """Section 1: sort the usable fixes by t (stable) and drop every fix whose t is not greater than the one before it (the first of equal
    times wins). This is what the scorer is fed."""
    kept = []
    for fix in sorted(fixes, key=lambda f: f.t):
        if kept and not fix.t > kept[-1].t:
            continue
        kept.append(fix)
    return kept


# =====================================================================================================================================
# Section 2. The flat frame and the placed fix
# =====================================================================================================================================

class Geo:
    """Section 2: the flat frame around an origin (lat0, lon0). x metres east, y metres north."""

    def __init__(self, lat0, lon0):
        self.lat0 = lat0
        self.lon0 = lon0
        self.cos_lat0 = math.cos(math.radians(lat0))

    def x(self, lon):
        return math.radians(lon - self.lon0) * EARTH_RADIUS_M * self.cos_lat0

    def y(self, lat):
        return math.radians(lat - self.lat0) * EARTH_RADIUS_M

    def xy(self, lat, lon):
        return self.x(lon), self.y(lat)

    def lat(self, y):
        return self.lat0 + math.degrees(y / EARTH_RADIUS_M)

    def lon(self, x):
        return self.lon0 + math.degrees(x / (EARTH_RADIUS_M * self.cos_lat0))

    def latlon(self, x, y):
        return self.lat(y), self.lon(x)


def bearing(dx, dy):
    """Section 2: compass bearing of the vector (dx east, dy north), degrees clockwise from north, plus 360 if negative."""
    b = math.degrees(math.atan2(dx, dy))
    return b + 360.0 if b < 0 else b


def angle_diff(a, b):
    """Section 2: the smaller angle between two bearings, 0 to 180 degrees."""
    d = abs(a - b) % 360.0
    return d if d <= 180.0 else 360.0 - d


@dataclass
class Pt:
    """Section 2: a fix placed in the flat frame: position (x, y), velocity (vx, vy) and speed. `hacc` is carried along (the quality figures
    of section 6 need the worst reported accuracy)."""
    t: float
    x: float
    y: float
    vx: float
    vy: float
    speed: float
    hacc: float = -1.0


def place_fix(fix, geo, prev):
    """Section 2: put a Fix in the frame as a Pt, given the previous Pt of the same pass (None for the first).
    The velocity comes from the reported speed and bearing if both are given, else from the step since `prev`, else it is zero."""
    x, y = geo.xy(fix.lat, fix.lon)
    if fix.speed >= 0 and fix.bearing >= 0:
        vx = fix.speed * math.sin(math.radians(fix.bearing))
        vy = fix.speed * math.cos(math.radians(fix.bearing))
    elif prev is not None and fix.t > prev.t:
        vx = (x - prev.x) / (fix.t - prev.t)
        vy = (y - prev.y) / (fix.t - prev.t)
    else:
        vx = vy = 0.0
    speed = fix.speed if fix.speed >= 0 else math.hypot(vx, vy)
    return Pt(fix.t, x, y, vx, vy, speed, fix.hacc)


# =====================================================================================================================================
# Section 3. A timing line
# =====================================================================================================================================

class Gate:
    """Section 3: a line segment centred on (cx, cy), perpendicular to `bearing`, `half` metres each side, counted only for a car travelling
    along `bearing` (compass degrees)."""

    def __init__(self, cx, cy, bearing_deg, half=GATE_HALF_M):
        self.cx = cx
        self.cy = cy
        self.bearing = bearing_deg
        self.half = half
        self.ux = math.sin(math.radians(bearing_deg))
        self.uy = math.cos(math.radians(bearing_deg))

    def along(self, x, y):
        """Distance past the line along its direction: negative before the line, positive after."""
        return (x - self.cx) * self.ux + (y - self.cy) * self.uy

    def across(self, x, y):
        """Distance from the line's centre along the line."""
        return -(x - self.cx) * self.uy + (y - self.cy) * self.ux

    def crossing(self, a, b, min_speed, min_forward):
        """Section 3 `crossing(a, b, minSpeed, minForward)` for two consecutive placed fixes a then b: the time at which the car crossed the
        line, or None."""
        # 1. a before the line, b on or after it
        q0 = self.along(a.x, a.y)
        q1 = self.along(b.x, b.y)
        if not (q0 < 0 and q1 >= 0):
            return None
        # 2. fast enough
        if not max(a.speed, b.speed) >= min_speed:
            return None
        # 3. heading the right way, by the velocity the phone reported at the faster of the two fixes
        if min_forward > 0:
            f = a if a.speed >= b.speed else b
            vn = math.hypot(f.vx, f.vy)
            if not vn > 0:
                return None
            if not (f.vx * self.ux + f.vy * self.uy) / vn >= min_forward:
                return None
        # 4. time runs forward
        dt = b.t - a.t
        if not dt > 0:
            return None

        # 5. the cubic Hermite path through both fixes with the reported velocities at the ends
        def path(u):
            h00 = 2 * u ** 3 - 3 * u ** 2 + 1
            h10 = u ** 3 - 2 * u ** 2 + u
            h01 = -2 * u ** 3 + 3 * u ** 2
            h11 = u ** 3 - u ** 2
            return (h00 * a.x + h10 * dt * a.vx + h01 * b.x + h11 * dt * b.vx,
                    h00 * a.y + h10 * dt * a.vy + h01 * b.y + h11 * dt * b.vy)

        # 6. the first sign change of `along` on 16 equal steps (if none: the whole interval)
        lo, hi = 0.0, 1.0
        prev_q = self.along(*path(0.0))
        for i in range(1, 17):
            u = i / 16.0
            q = self.along(*path(u))
            if prev_q < 0 and q >= 0:
                lo, hi = (i - 1) / 16.0, u
                break
            prev_q = q
        # 7. 40 bisections
        for _ in range(40):
            mid = (lo + hi) / 2.0
            if self.along(*path(mid)) < 0:
                lo = mid
            else:
                hi = mid
        u = (lo + hi) / 2.0
        # 8. the crossing must be on the segment
        if abs(self.across(*path(u))) > self.half:
            return None
        return a.t + u * dt


def speed_at(tc, a, b):
    """Section 6: the speed at time tc between placed fixes a and b, by linear interpolation of their speeds."""
    k = min(1.0, max(0.0, (tc - a.t) / (b.t - a.t)))
    return a.speed + k * (b.speed - a.speed)


# =====================================================================================================================================
# Section 5. Placing a fix on the route
# =====================================================================================================================================

class Route:
    """Section 5: a polyline with vertices (x[j], y[j]) and s[j], the distance along it to each vertex. `length` = s[-1]."""

    def __init__(self, points):
        self.x = [p[0] for p in points]
        self.y = [p[1] for p in points]
        self.s = [0.0]
        for j in range(1, len(points)):
            self.s.append(self.s[-1] + math.hypot(self.x[j] - self.x[j - 1], self.y[j] - self.y[j - 1]))
        self.length = self.s[-1]

    def segment_at(self, d):
        """Section 5 `segmentAt(d)`: 0 if d <= 0; n-2 if d >= length; otherwise the largest j with s[j] <= d."""
        n = len(self.s)
        if d <= 0:
            return 0
        if d >= self.length:
            return n - 2
        lo, hi = 0, n - 1            # invariant: s[lo] <= d < s[hi]
        while hi - lo > 1:
            mid = (lo + hi) // 2
            if self.s[mid] <= d:
                lo = mid
            else:
                hi = mid
        return lo

    def point_at(self, d):
        """Section 4 `pointAt(d)`: linear interpolation along the segment that contains d (d clamped into [0, length])."""
        d = min(self.length, max(0.0, d))
        j = self.segment_at(d)
        seg = self.s[j + 1] - self.s[j]
        f = (d - self.s[j]) / seg if seg > 0 else 0.0
        return (self.x[j] + f * (self.x[j + 1] - self.x[j]), self.y[j] + f * (self.y[j + 1] - self.y[j]))

    def project(self, px, py, hint, behind, ahead, heading=None):
        """Section 5 `project(px, py, hint, behind, ahead, heading)`: the route distance of the point nearest to (px, py) among the segments
        from `hint - behind` to `hint + ahead`, preferring segments that run the way `heading` (a bearing, or None) points.
        Returns (route distance, distance from the route)."""
        j0 = self.segment_at(max(0.0, hint - behind))
        j1 = self.segment_at(min(self.length, hint + ahead))
        best_score = None
        best = (0.0, 0.0)
        for j in range(j0, j1 + 1):
            ax, ay = self.x[j], self.y[j]
            dx, dy = self.x[j + 1] - ax, self.y[j + 1] - ay
            len2 = dx * dx + dy * dy
            f = 0.0
            if len2 > 0:
                f = min(1.0, max(0.0, ((px - ax) * dx + (py - ay) * dy) / len2))
            dist = math.hypot(px - (ax + f * dx), py - (ay + f * dy))
            score = dist
            if heading is not None and len2 > 0:
                diff = angle_diff(heading, bearing(dx, dy))
                if diff > 60:
                    score += 40.0 * (diff - 60.0) / 120.0
            if best_score is None or score < best_score:        # the first one wins a tie
                best_score = score
                best = (self.s[j] + f * math.sqrt(len2), dist)
        return best


# =====================================================================================================================================
# Stage and run files: one structure that can be both written and compared (see Num)
# =====================================================================================================================================

class Num:
    """A number together with the count of decimals it is printed with. Files are written from lists of lines whose words are text or Num;
    the selftest compares the same lists with the expected files, so a value is judged before it is rounded for printing."""
    __slots__ = ("value", "decimals")

    def __init__(self, value, decimals):
        self.value = value
        self.decimals = decimals

    def text(self):
        return "%.*f" % (self.decimals, self.value)


def render_lines(lines):
    """Write a list of lines (each a list of words: str or Num) as the text of a file."""
    return "".join(" ".join(w if isinstance(w, str) else w.text() for w in line) + "\n" for line in lines)


class FormatError(ValueError):
    """A stage or run file that the strict reader refuses; the message says why in plain words."""


def _is_id(text):
    return _ID_RE.fullmatch(text) is not None


def _split_file(text, magic):
    """The strict reader's first step: the first line must be `magic`, the last line `end`. Returns the (key, rest-of-line) pairs between
    them. Blank lines are ignored; a key the reader does not know is the caller's to skip."""
    lines = [ln.rstrip("\r") for ln in text.split("\n")]
    while lines and lines[-1].strip() == "":
        lines.pop()
    if not lines or lines[0] != magic:
        raise FormatError("the first line is not '%s'" % magic)
    if lines[-1] != "end":
        raise FormatError("there is no 'end' line (a write that was cut short)")
    entries = []
    for ln in lines[1:-1]:
        if ln.strip() == "":
            continue
        key, _, rest = ln.partition(" ")
        entries.append((key, rest))
    return entries


def _numbers(rest, count, what):
    """`count` finite numbers separated by single spaces, or a FormatError."""
    words = rest.split(" ")
    if len(words) != count:
        raise FormatError("'%s' needs %d numbers" % (what, count))
    values = []
    for w in words:
        v = parse_float(w)
        if v is None or not math.isfinite(v):
            raise FormatError("'%s' has a bad number: %r" % (what, w))
        values.append(v)
    return values


def _integer(rest, what):
    v = parse_int(rest)
    if v is None:
        raise FormatError("'%s' needs an integer" % what)
    return v


# ---- the stage ------------------------------------------------------------------------------------------------------------------------

class Stage:
    """A stage as the scorer sees it (sections 4 and 5): the frame origin, the marks, the two lines and the route, plus the Geo and Route
    objects made from them. `route_points` are (x, y) in the frame; the route runs from the middle of the start line to the middle of the
    finish line, and its length is the stage's `length_m`."""

    def __init__(self, stage_id, name, created_utc_ms, origin, start_mark, finish_mark, start_gate, finish_gate, route_points):
        self.id = stage_id
        self.name = name
        self.kind = "road"
        self.created_utc_ms = created_utc_ms
        self.origin = origin                    # (lat, lon)
        self.start_mark = start_mark            # (lat, lon)
        self.finish_mark = finish_mark          # (lat, lon)
        self.start_gate = start_gate            # Gate
        self.finish_gate = finish_gate
        self.route_points = list(route_points)
        self.geo = Geo(origin[0], origin[1])
        self.route = Route(self.route_points)
        self.length_m = self.route.length

    def tokens(self):
        """The lines of stage.txt (docs/stage-format.md): marks and origin to 7 decimals, gates and route to 3."""
        def gate_line(key, g):
            return [key, Num(g.cx, 3), Num(g.cy, 3), Num(g.bearing, 3), Num(g.half, 1)]
        lines = [["TRACKLOGGER-STAGE", "1"], ["id", self.id], ["name", self.name], ["kind", self.kind],
                 ["created_utc_ms", Num(self.created_utc_ms, 0)],
                 ["origin", Num(self.origin[0], 7), Num(self.origin[1], 7)],
                 ["start_mark", Num(self.start_mark[0], 7), Num(self.start_mark[1], 7)],
                 ["finish_mark", Num(self.finish_mark[0], 7), Num(self.finish_mark[1], 7)],
                 gate_line("start_gate", self.start_gate), gate_line("finish_gate", self.finish_gate),
                 ["route", str(len(self.route_points))]]
        lines += [["r", Num(x, 3), Num(y, 3)] for x, y in self.route_points]
        lines.append(["end"])
        return lines

    def text(self):
        return render_lines(self.tokens())

    def read_back(self):
        """The same stage as it comes back from its file ("the app scores against the stage as read back", section 4)."""
        return parse_stage_text(self.text())


def parse_stage_text(text, expect_id=None):
    """The strict reader of stage.txt (docs/stage-format.md). Raises FormatError with a plain reason if the file is not whole and valid;
    `expect_id` (the folder name in a bundle) must equal the `id` line when given."""
    entries = _split_file(text, "TRACKLOGGER-STAGE 1")
    single = {}
    route = []
    route_count = None
    for key, rest in entries:
        if key == "r":
            x, y = _numbers(rest, 2, "r")
            route.append((x, y))
        elif key in ("id", "name", "kind", "created_utc_ms", "origin", "start_mark", "finish_mark", "start_gate", "finish_gate", "route"):
            if key in single:
                raise FormatError("the line '%s' appears twice" % key)
            single[key] = rest
        # any other key is skipped (a later version may add lines)
    for key in ("id", "kind", "origin", "start_mark", "finish_mark", "start_gate", "finish_gate", "route"):
        if key not in single:
            raise FormatError("the line '%s' is missing" % key)
    stage_id = single["id"]
    if not _is_id(stage_id):
        raise FormatError("the id %r is not 1 to 64 characters of a-z A-Z 0-9 _ -" % stage_id)
    if expect_id is not None and stage_id != expect_id:
        raise FormatError("the id %r differs from the folder %r" % (stage_id, expect_id))
    if single["kind"] != "road":
        raise FormatError("the kind %r is not 'road'" % single["kind"])
    name = single.get("name", "")
    if len(name) > 60 or any(ord(c) < 32 or ord(c) == 127 or c in ",;" for c in name):
        raise FormatError("the name is longer than 60 characters or has a comma, semicolon or control character")
    created = _integer(single.get("created_utc_ms", "0"), "created_utc_ms")

    def latlon(key):
        lat, lon = _numbers(single[key], 2, key)
        if abs(lat) > 90 or abs(lon) > 180:
            raise FormatError("'%s' is not a place on Earth" % key)
        return lat, lon

    origin, start_mark, finish_mark = latlon("origin"), latlon("start_mark"), latlon("finish_mark")

    def gate(key):
        cx, cy, brg, half = _numbers(single[key], 4, key)
        if not half > 0:
            raise FormatError("'%s' has no width" % key)
        return Gate(cx, cy, brg, half)

    start_gate, finish_gate = gate("start_gate"), gate("finish_gate")
    route_count = _integer(single["route"], "route")
    if not 2 <= route_count <= 200000:
        raise FormatError("the route must have 2 to 200000 vertices, not %d" % route_count)
    if len(route) != route_count:
        raise FormatError("the route says %d vertices but has %d" % (route_count, len(route)))
    return Stage(stage_id, name, created, origin, start_mark, finish_mark, start_gate, finish_gate, route)


def load_stage_file(path):
    """Read a stage.txt from disk with the strict reader."""
    with open(path, "r", encoding="utf-8") as fh:
        return parse_stage_text(fh.read())


# ---- a run ----------------------------------------------------------------------------------------------------------------------------

@dataclass
class RunResult:
    """Section 6 'the result of a run', as saved in a run file (docs/stage-format.md). For a run that did not finish: total_s and profile are
    None, finish_gap_s is 0 and the finish speed -1."""
    stage_id: str
    run_id: str
    csv: str
    utc_ms: int
    finished: bool
    status: str
    total_s: object
    length_m: float
    step_m: float
    fixes: int
    max_gap_s: float
    max_gap_at_m: float
    max_acc_m: float
    off_route_max_m: float
    start_gap_s: float
    finish_gap_s: float
    start_speed: float
    finish_speed: float
    profile: object

    def tokens(self):
        """The lines of run_<id>.txt (docs/stage-format.md): 20 profile values to a line."""
        lines = [["TRACKLOGGER-RUN", "1"], ["stage", self.stage_id], ["run", self.run_id], ["csv", self.csv if self.csv else '""'],
                 ["utc_ms", Num(self.utc_ms, 0)], ["finished", "1" if self.finished else "0"], ["status", self.status]]
        if self.finished:
            lines.append(["total_s", Num(self.total_s, 3)])
        lines.append(["length_m", Num(self.length_m, 2)])
        lines.append(["step_m", Num(self.step_m, 1)])
        lines.append(["quality", Num(self.fixes, 0), Num(self.max_gap_s, 3), Num(self.max_gap_at_m, 1), Num(self.max_acc_m, 1),
                      Num(self.off_route_max_m, 1), Num(self.start_gap_s, 3), Num(self.finish_gap_s, 3)])
        lines.append(["line_speeds", Num(self.start_speed, 2), Num(self.finish_speed, 2)])
        if self.finished:
            lines.append(["n", Num(len(self.profile), 0)])
            for i in range(0, len(self.profile), 20):
                lines.append(["p"] + [Num(v, 3) for v in self.profile[i:i + 20]])
        lines.append(["end"])
        return lines

    def text(self):
        return render_lines(self.tokens())


def parse_run_text(text, expect_stage=None, expect_run=None):
    """The strict reader of run_<id>.txt (docs/stage-format.md). `expect_stage` / `expect_run` (the folder and file name in a bundle) must
    equal the `stage` / `run` lines when given. Raises FormatError with a plain reason."""
    entries = _split_file(text, "TRACKLOGGER-RUN 1")
    single = {}
    profile = []
    for key, rest in entries:
        if key == "p":
            profile.extend(_numbers(rest, len(rest.split(" ")), "p"))
        elif key in ("stage", "run", "csv", "utc_ms", "finished", "status", "total_s", "length_m", "step_m", "quality", "line_speeds", "n"):
            if key in single:
                raise FormatError("the line '%s' appears twice" % key)
            single[key] = rest
    for key in ("stage", "run", "finished", "status", "length_m", "step_m", "quality"):
        if key not in single:
            raise FormatError("the line '%s' is missing" % key)
    stage_id, run_id = single["stage"], single["run"]
    if not _is_id(stage_id) or not _is_id(run_id):
        raise FormatError("a bad stage or run id")
    if expect_stage is not None and stage_id != expect_stage:
        raise FormatError("the stage %r differs from the folder %r" % (stage_id, expect_stage))
    if expect_run is not None and run_id != expect_run:
        raise FormatError("the run %r differs from the file name %r" % (run_id, expect_run))
    csv_name = single.get("csv", "")
    if csv_name == '""':
        csv_name = ""
    if single["finished"] not in ("0", "1"):
        raise FormatError("'finished' must be 0 or 1")
    finished = single["finished"] == "1"
    (length_m,) = _numbers(single["length_m"], 1, "length_m")
    (step_m,) = _numbers(single["step_m"], 1, "step_m")
    if not length_m > 0 or not step_m > 0:
        raise FormatError("the length or the step is not positive")
    q = _numbers(single["quality"], 7, "quality")
    if "line_speeds" in single:
        v_start, v_finish = _numbers(single["line_speeds"], 2, "line_speeds")
    else:
        v_start = v_finish = -1.0           # files from before these were kept have no such line
    total, prof = None, None
    if finished:
        for key in ("total_s", "n"):
            if key not in single:
                raise FormatError("a finished run needs the line '%s'" % key)
        (total,) = _numbers(single["total_s"], 1, "total_s")
        n = _integer(single["n"], "n")
        if n < 2 or len(profile) != n:
            raise FormatError("the profile says %d values but has %d" % (n, len(profile)))
        prof = profile
    return RunResult(stage_id, run_id, csv_name, _integer(single.get("utc_ms", "0"), "utc_ms"), finished, single["status"], total, length_m,
                     step_m, int(q[0]), q[1], q[2], q[3], q[4], q[5], q[6], v_start, v_finish, prof)


def load_run_file(path):
    """Read a run_<id>.txt from disk with the strict reader."""
    with open(path, "r", encoding="utf-8") as fh:
        return parse_run_text(fh.read())


# =====================================================================================================================================
# Section 4. Making a stage from a marking drive
# =====================================================================================================================================

class StageRefused(Exception):
    """Section 4: the drive cannot make a stage. `code` is one of no_movement, too_few_fixes, route_too_short, loop; the message says why
    in plain words."""

    def __init__(self, code, message):
        Exception.__init__(self, message)
        self.code = code
        self.message = message


def build_stage_raw(start, finish, drive, stage_id, name, created_utc_ms=0):
    """Section 4 (`StageBuilder`), steps 1 to 7, in full precision: `start` and `finish` are (lat, lon) of the two marks, `drive` the usable
    fixes between them in time order. Returns a Stage whose numbers are not yet rounded for the file; raises StageRefused."""
    geo = Geo(start[0], start[1])                                       # 1. the frame has its origin at the start mark
    fx, fy = geo.xy(finish[0], finish[1])
    moving = [i for i, f in enumerate(drive) if f.speed >= MOVING_SPEED]    # 2. the moving part
    if not moving:
        raise StageRefused("no_movement", "the car did not move between the two marks")
    i0, i1 = moving[0], moving[-1]
    verts = [(0.0, 0.0)]                                                # 3. vertices
    for f in drive[i0:i1 + 1]:
        if f.hacc > MAX_BUILD_HACC:
            continue
        x, y = geo.xy(f.lat, f.lon)
        if math.hypot(x - verts[-1][0], y - verts[-1][1]) < MIN_VERTEX_GAP_M:
            continue
        verts.append((x, y))
    if len(verts) > 1 and math.hypot(fx - verts[-1][0], fy - verts[-1][1]) < MIN_VERTEX_GAP_M:
        verts[-1] = (fx, fy)
    else:
        verts.append((fx, fy))
    if len(verts) < 3:
        raise StageRefused("too_few_fixes", "too few GPS fixes")
    raw = Route(verts)                                                  # 4. the raw route
    if raw.length < MIN_STAGE_LENGTH_M:
        raise StageRefused("route_too_short", "the drive between the marks was only %.0f m; a stage needs at least %.0f m"
                           % (raw.length, MIN_STAGE_LENGTH_M))
    if math.hypot(fx, fy) < MIN_START_FINISH_M:
        raise StageRefused("loop", "the finish mark is only %.0f m from the start mark; a stage runs from one place to another, not round a loop"
                           % math.hypot(fx, fy))
    s0, s1 = LINE_INSET_M, raw.length - LINE_INSET_M                    # 5. where the lines go
    p_start, p_finish = raw.point_at(s0), raw.point_at(s1)
    p_after = raw.point_at(min(s0 + LINE_DIRECTION_SPAN_M, s1))         # 6. line directions
    p_before = raw.point_at(max(s1 - LINE_DIRECTION_SPAN_M, s0))
    start_gate = Gate(p_start[0], p_start[1], bearing(p_after[0] - p_start[0], p_after[1] - p_start[1]), GATE_HALF_M)
    finish_gate = Gate(p_finish[0], p_finish[1], bearing(p_finish[0] - p_before[0], p_finish[1] - p_before[1]), GATE_HALF_M)
    points = [p_start]                                                  # 7. the route of the stage
    for i in range(len(verts)):
        if s0 + 0.01 < raw.s[i] < s1 - 0.01:
            points.append(verts[i])
    points.append(p_finish)
    return Stage(stage_id, name, created_utc_ms, tuple(start), tuple(start), tuple(finish), start_gate, finish_gate, points)


def build_stage(start, finish, drive, stage_id, name, created_utc_ms=0):
    """Section 4: build_stage_raw() and then read the stage back from its text, because the scorer works on the rounded file (the origin and
    marks to 7 decimals, gates and route to 3 decimals). Raises StageRefused."""
    return build_stage_raw(start, finish, drive, stage_id, name, created_utc_ms).read_back()


class MarkingError(Exception):
    """A marking recording that has no usable MARK_START / MARK_FINISH pair."""


def marking_from_session(ses):
    """Section 4 / docs/stage-format.md: the marks and the drive of a marking recording. MARK_START and MARK_FINISH carry `lat, lon,
    accuracy_m`; the drive is the usable fixes that arrived between the two events (file order), cleaned like a scored recording.
    Returns (start (lat, lon), finish (lat, lon), drive, mark_finish_t_us)."""
    finish_ev = next((e for e in ses.events if e.name == "MARK_FINISH"), None)
    if finish_ev is None:
        raise MarkingError("the recording has no MARK_FINISH event")
    start_ev = None
    for e in ses.events:
        if e is finish_ev:
            break
        if e.name == "MARK_START":
            start_ev = e                    # the last MARK_START before the first MARK_FINISH
    if start_ev is None:
        raise MarkingError("the recording has no MARK_START event before its MARK_FINISH")

    def place_of(ev):
        lat = parse_float(ev.values[0]) if len(ev.values) >= 2 else None
        lon = parse_float(ev.values[1]) if len(ev.values) >= 2 else None
        if lat is None or lon is None or not (abs(lat) <= 90 and abs(lon) <= 180):
            raise MarkingError("the %s event has no usable position" % ev.name)
        return lat, lon

    drive = clean_fixes(ses.fixes[start_ev.nfix:finish_ev.nfix])
    return place_of(start_ev), place_of(finish_ev), drive, finish_ev.t_us


def default_stage_id(utc_ms):
    """A stage id like the app's: 'st' and the date and time the stage was marked (UTC here; the app uses the phone's local time)."""
    when = datetime.datetime.fromtimestamp(utc_ms / 1000.0, datetime.timezone.utc)
    return "st" + when.strftime("%Y%m%d_%H%M%S")


# =====================================================================================================================================
# Section 6. Following a run
# =====================================================================================================================================

class StageRun:
    """Section 6 (`StageRun`): follows one run of a stage fix by fix. Call feed() with consecutive fixes (already sorted and de-duplicated, see
    clean_fixes); look at `phase` and, when the run is over (`over`) or the recording has ended, ask result()."""

    def __init__(self, stage):
        self.stage = stage
        self.phase = APPROACH
        self.prev = None                    # the previous placed fix of this run
        self.last_t = None                  # time of the last fix given to feed()
        self.s_now = 0.0                    # route distance of the latest fix placed on the route; never decreases
        self.placed = []                    # (t, s) of the fixes placed so far
        self.t_start = None
        self.t_finish = None
        self.start_speed = -1.0
        self.finish_speed = -1.0
        self.profile = None
        self._clear_quality()
        self.finish_gap = 0.0

    def _clear_quality(self):
        self.n_placed = 0
        self.max_gap = 0.0
        self.max_gap_at = 0.0
        self.max_hacc = -1.0
        self.off_max = 0.0
        self.start_gap = 0.0

    @property
    def over(self):
        return self.phase in (FINISHED, DNF)

    # ---- one fix ----
    def feed(self, fix):
        """Section 6, 'each fix'."""
        if self.over:
            return
        if self.last_t is not None and not fix.t > self.last_t:
            return
        self.last_t = fix.t
        cur = place_fix(fix, self.stage.geo, self.prev)
        prev = self.prev
        if self.phase == APPROACH:
            if prev is not None and cur.t - prev.t <= MAX_FIX_GAP_S:
                tc = self.stage.start_gate.crossing(prev, cur, STAGE_MIN_SPEED, STAGE_MIN_FORWARD)
                if tc is not None:
                    self._begin(tc, cur, prev)
        elif self.phase == RUNNING:
            near = cur.t - prev.t <= MAX_FIX_GAP_S
            tc = None
            if near and self.s_now < FALSE_START_S_LIMIT:
                tc = self.stage.start_gate.crossing(prev, cur, STAGE_MIN_SPEED, STAGE_MIN_FORWARD)
            if tc is not None:
                self._begin(tc, cur, prev)          # a false start: the run is timed from the later crossing
            else:
                s, dist = self._locate(cur, prev)
                tf = None
                if near:
                    tf = self.stage.finish_gate.crossing(prev, cur, STAGE_MIN_SPEED, STAGE_MIN_FORWARD)
                if (tf is not None and tf - self.t_start >= MIN_STAGE_TIME_S
                        and s >= MIN_FINISH_FRACTION * self.stage.route.length):
                    self.s_now = s
                    self._finish(tf, cur, prev)
                else:
                    self._place(cur, prev, s, dist)
        elif self.phase == POST:
            if fix.t >= self.t_finish + POST_SECONDS:
                self.phase = FINISHED
        self.prev = cur

    def _begin(self, tc, cur, prev):
        """Section 6 begin(tc): the run starts at time tc; the first fix after the line is the first one placed."""
        self.t_start = tc
        self.phase = RUNNING
        self.s_now = 0.0
        self.placed = []
        self._clear_quality()
        self.start_gap = cur.t - prev.t
        self.start_speed = speed_at(tc, prev, cur)
        s, dist = self._locate(cur, prev)
        self._place(cur, prev, s, dist)

    def _locate(self, cur, prev):
        """Section 6 locate(cur): (route distance, distance from the route) of a fix; changes nothing."""
        route = self.stage.route
        dt = cur.t - prev.t
        ahead = 1.5 * max(cur.speed, 5.0) * max(dt, 1.0) + 60.0
        heading = bearing(cur.vx, cur.vy) if cur.speed >= 3.0 else None
        s, dist = route.project(cur.x, cur.y, self.s_now, 40.0, ahead, heading)
        if dist > 60.0:                         # lost: a GPS gap, a detour
            s2, dist2 = route.project(cur.x, cur.y, self.s_now, 40.0, route.length, heading)
            if dist2 + 20.0 < dist:
                s, dist = s2, dist2
        s = max(s, self.s_now)                  # the car does not go backwards along the route
        s = min(s, route.length)
        return s, dist

    def _place(self, cur, prev, s, dist):
        """Section 6 place(cur, s, dist): count the fix and remember the quality figures."""
        self.n_placed += 1
        dt = cur.t - prev.t
        if dt > self.max_gap:
            self.max_gap = dt
            self.max_gap_at = self.s_now        # the route distance where the gap began
        if cur.hacc > self.max_hacc:
            self.max_hacc = cur.hacc
        self.s_now = s
        if dist > self.off_max:
            self.off_max = dist
        self.placed.append((cur.t, s))

    def _finish(self, tf, cur, prev):
        """Section 6 finish(tf): the car crossed the finish line at tf; `cur`, the first fix after it, is not part of the run."""
        self.t_finish = tf
        self.finish_gap = cur.t - prev.t
        self.finish_speed = speed_at(tf, prev, cur)
        self.profile = self._build_profile()
        self.phase = POST

    def _build_profile(self):
        """Section 6 'the profile': the time at which the car had reached route distance k * 25, in seconds after the start line."""
        length = self.stage.route.length
        tt, ss = [self.t_start], [0.0]
        for t, s in self.placed:
            if t <= self.t_start or t >= self.t_finish:
                continue
            if s <= ss[-1] + 0.01:                  # stopped or jittering: the earliest time at each distance is kept
                continue
            if s >= length - 0.01:
                continue
            tt.append(t)
            ss.append(s)
        tt.append(self.t_finish)
        ss.append(length)
        m = len(tt)
        n = int(math.ceil(length / STEP_M))
        profile = []
        j = 0
        for k in range(n + 1):
            s = min(STEP_M * k, length)
            while j < m - 2 and ss[j + 1] < s:
                j += 1
            f = (s - ss[j]) / (ss[j + 1] - ss[j]) if ss[j + 1] > ss[j] else 0.0
            f = min(1.0, max(0.0, f))
            profile.append(tt[j] + f * (tt[j + 1] - tt[j]) - self.t_start)
        return profile

    # ---- the result ----
    def result(self, run_id, csv_name="", start_utc_ms=None):
        """Section 6 'the result of a finished run' (or, if the run is still RUNNING, the did-not-finish result of the end of the recording)."""
        finished = self.profile is not None
        utc = 0 if start_utc_ms is None else round_half_up(self.t_start * 1000.0 + start_utc_ms)
        return RunResult(
            stage_id=self.stage.id, run_id=run_id, csv=csv_name, utc_ms=utc, finished=finished,
            status="ok" if finished else "the recording ended before the finish line",
            total_s=(self.t_finish - self.t_start) if finished else None,
            length_m=self.stage.route.length, step_m=STEP_M, fixes=self.n_placed, max_gap_s=self.max_gap, max_gap_at_m=self.max_gap_at,
            max_acc_m=self.max_hacc, off_route_max_m=self.off_max, start_gap_s=self.start_gap,
            finish_gap_s=self.finish_gap if finished else 0.0,
            start_speed=self.start_speed, finish_speed=self.finish_speed if finished else -1.0,
            profile=self.profile if finished else None)


def _utf16_units(text):
    """The UTF-16 code units of a string (a character outside the first plane is two units; a lone surrogate is one)."""
    raw = text.encode("utf-16-le", "surrogatepass")
    return [raw[i] | (raw[i + 1] << 8) for i in range(0, len(raw), 2)]


def run_id_base(csv_name):
    """Section 6 'run ids', the part before '_<n>': the session file's name without any folder part, without '.csv' (lower case only) and
    without one leading 'session_'. Used as it is if it is at most 56 UTF-16 units of A-Z a-z 0-9 _ - (every name the recorder writes);
    otherwise every unit outside A-Z a-z 0-9 - becomes '_', the result is cut to 48 units, and '-' plus the 8 hex digits of the 32-bit FNV-1a
    hash of the whole base name (over its UTF-16 units) are added."""
    n = csv_name or ""
    n = n[max(n.rfind("/"), n.rfind("\\")) + 1:]
    if n.endswith(".csv"):
        n = n[:-4]
    if n.startswith("session_"):
        n = n[len("session_"):]
    units = _utf16_units(n)
    if len(units) <= 56 and (len(units) == 0 or re.fullmatch("[%s]{1,64}" % _ID_CHARS, n)):
        return n
    kept = []
    for u in units[:48]:
        ok = (0x61 <= u <= 0x7A) or (0x41 <= u <= 0x5A) or (0x30 <= u <= 0x39) or u == 0x2D
        kept.append(chr(u) if ok else "_")
    h = 0x811C9DC5
    for u in units:
        h ^= u
        h = (h * 0x01000193) & 0xFFFFFFFF
    return "".join(kept) + "-%08x" % h


def make_run_id(csv_name, number):
    """Section 6 'run ids': run_id_base(csv_name), then '_' and the number of the run in that file, counting from 1; 'run' if the base is empty."""
    base = run_id_base(csv_name)
    return (base if base else "run") + "_%d" % number


def score_recording(stage, fixes, csv_name="", start_utc_ms=None):
    """Section 6 `Scorer.scoreAll`: follow every run of `stage` in a recording. `fixes` are the usable fixes (they are sorted and de-duplicated
    here). When a run is over, its result is kept and a fresh run starts with the NEXT fix; at the end a run still RUNNING is closed as did
    not finish, one in POST counts as finished, one in APPROACH leaves nothing. Returns a list of RunResult."""
    results = []
    run = StageRun(stage)
    for fix in clean_fixes(fixes):
        run.feed(fix)
        if run.over:
            results.append(run.result(make_run_id(csv_name, len(results) + 1), csv_name, start_utc_ms))
            run = StageRun(stage)
    if run.phase in (RUNNING, POST):
        results.append(run.result(make_run_id(csv_name, len(results) + 1), csv_name, start_utc_ms))
    return results


def score_session(stage, session_path):
    """score_recording() on a session CSV file: reads the file, uses its name for the run ids and its header for utc_ms."""
    ses = read_session(session_path)
    return score_recording(stage, ses.fixes, ses.name, ses.start_utc_ms)


# =====================================================================================================================================
# Section 7. Comparing two runs
# =====================================================================================================================================

class CompareError(Exception):
    """Two runs that cannot be compared; the message says why."""


def section_length(length_m):
    """Section 7: the first of 250, 500, 1000, 2000, 5000, 10000 m that makes at most 12 sections (10000 if none does)."""
    for section in SECTION_CHOICES_M:
        if length_m / section <= MAX_SECTIONS:
            return float(section)
    return 10000.0


def sigma_speed(v):
    """Section 7: the speed used for the uncertainty of a line crossing: max(line speed, 3) m/s, or 12 m/s if the line speed is unknown."""
    return UNKNOWN_LINE_SPEED if v < 0 else max(v, 3.0)


def warnings_for(run):
    """Section 7 'warnings': plain-words problems of a run, from its quality figures."""
    out = []
    if run.max_gap_s > WARN_GAP_S:
        out.append("a gap of %.1f s between two GPS fixes, %.0f m along the route" % (run.max_gap_s, run.max_gap_at_m))
    if run.off_route_max_m > WARN_OFF_ROUTE_M:
        out.append("the car was %.0f m from the route" % run.off_route_max_m)
    if run.start_gap_s > WARN_LINE_GAP_S:
        out.append("the start line was crossed between fixes %.1f s apart" % run.start_gap_s)
    if run.finished and run.finish_gap_s > WARN_LINE_GAP_S:
        out.append("the finish line was crossed between fixes %.1f s apart" % run.finish_gap_s)
    if run.max_acc_m > WARN_ACCURACY_M:
        out.append("the worst reported GPS accuracy was %.0f m" % run.max_acc_m)
    return out


def incomparable_reason(a, b):
    """Section 7: why two runs cannot be compared, or None. They must be finished, of the same stage, with the same step and profile length,
    and `length_m` within 0.5 m."""
    if not a.finished or not b.finished:
        return "run %s did not finish" % (a.run_id if not a.finished else b.run_id)
    if a.stage_id != b.stage_id:
        return "the runs belong to different stages (%s and %s)" % (a.stage_id, b.stage_id)
    if a.step_m != b.step_m or len(a.profile) != len(b.profile):
        return "the runs have different profile steps or lengths"
    if abs(a.length_m - b.length_m) > 0.5:
        return "the stage length differs by more than 0.5 m (%.2f and %.2f)" % (a.length_m, b.length_m)
    return None


@dataclass
class Section:
    """One row of the section table: the route distances it spans and the times of A and B over it."""
    from_m: float
    to_m: float
    time_a: float
    time_b: float

    @property
    def delta(self):
        return self.time_a - self.time_b


class Compare:
    """Section 7 (`Compare`): run A (the one looked at) against run B (the baseline). Positive differences mean A was slower."""

    def __init__(self, a, b):
        why = incomparable_reason(a, b)
        if why:
            raise CompareError(why)
        self.a, self.b = a, b
        n = len(a.profile)
        self.delta = [x - y for x, y in zip(a.profile, b.profile)]          # delta curve
        self.total_delta = a.total_s - b.total_s
        self.section_m = section_length(a.length_m)
        per = int(round(self.section_m / a.step_m))
        self.sections = []
        k0 = 0
        while k0 < n - 1:
            k1 = min(n - 1, k0 + per)
            self.sections.append(Section(k0 * a.step_m, min(k1 * a.step_m, a.length_m),
                                         a.profile[k1] - a.profile[k0], b.profile[k1] - b.profile[k0]))
            k0 += per
        worst = max(self.sections, key=lambda s: s.delta)
        best = min(self.sections, key=lambda s: s.delta)
        self.slowest = worst if worst.delta > 0 else None          # the section where A lost most
        self.fastest = best if best.delta < 0 else None            # the section where A gained most
        speeds = [sigma_speed(v) for v in (a.start_speed, a.finish_speed, b.start_speed, b.finish_speed)]
        self.sigma = SIGMA_ALONG_M * math.sqrt(sum(1.0 / (v * v) for v in speeds))
        self.band = max(0.4, 2.0 * self.sigma)
        self.about_the_same = abs(self.total_delta) < self.band

    def tokens(self):
        """The block of one pair in compare_expected.txt of the fixtures (see analysis/stage_fixtures/README.md)."""
        lines = [["pair", self.a.run_id, self.b.run_id], ["total", Num(self.total_delta, 6)], ["sigma", Num(self.sigma, 6)],
                 ["band", Num(self.band, 6)], ["section_m", Num(self.section_m, 1)]]
        for s in self.sections:
            lines.append(["sec", Num(s.from_m, 1), Num(s.to_m, 1), Num(s.time_a, 6), Num(s.time_b, 6), Num(s.delta, 6)])
        lines.append(["most_lost", Num(self.slowest.from_m, 1) if self.slowest else "none"])
        lines.append(["most_gained", Num(self.fastest.from_m, 1) if self.fastest else "none"])
        return lines


def choose_baseline(runs, a, against):
    """Section 7: the baseline B for run `a` among the finished `runs`: 'best' (the fastest other run), 'previous' (the latest run that
    started before `a`), or a run id. Returns a RunResult or None."""
    others = [r for r in runs if r.finished and r.run_id != a.run_id]
    if against == "best":
        return min(others, key=lambda r: (r.total_s, r.run_id)) if others else None
    if against == "previous":
        earlier = [r for r in others if (r.utc_ms, r.run_id) < (a.utc_ms, a.run_id)]
        return max(earlier, key=lambda r: (r.utc_ms, r.run_id)) if earlier else None
    return next((r for r in others if r.run_id == against), None)


def format_compare(c):
    """A comparison as text for people (the output of the command line): the difference of the totals, the verdict, the section table."""
    a, b = c.a, c.b
    out = []
    out.append("run %s (%s) against %s (%s)" % (a.run_id, format_clock(a.total_s), b.run_id, format_clock(b.total_s)))
    if c.about_the_same:
        verdict = "about the same: the difference is inside the %.2f s band" % c.band
    else:
        verdict = "%s than the baseline" % ("slower" if c.total_delta > 0 else "faster")
    out.append("  difference %+.3f s, %s" % (c.total_delta, verdict))
    out.append("  how far to trust it: sigma %.2f s (from the speeds at the four lines), so a difference under %.2f s counts as about the same" % (c.sigma, c.band))
    out.append("  speed at the start / finish line, m/s:  this run %s / %s,  baseline %s / %s" % (
        _speed_text(a.start_speed), _speed_text(a.finish_speed), _speed_text(b.start_speed), _speed_text(b.finish_speed)))
    out.append("  sections of %.0f m (positive = this run was behind):" % c.section_m)
    out.append("      from m      to m    this s  baseline s    delta s")
    for s in c.sections:
        mark = "  <- slowest part" if s is c.slowest else ("  <- fastest part" if s is c.fastest else "")
        out.append("   %8.0f %9.0f %9.3f %11.3f %+10.3f%s" % (s.from_m, s.to_m, s.time_a, s.time_b, s.delta, mark))
    if c.slowest:
        out.append("  slowest part: %.0f to %.0f m, %+.3f s" % (c.slowest.from_m, c.slowest.to_m, c.slowest.delta))
    if c.fastest:
        out.append("  fastest part: %.0f to %.0f m, %+.3f s" % (c.fastest.from_m, c.fastest.to_m, c.fastest.delta))
    for label, run in (("this run", a), ("baseline", b)):
        for w in warnings_for(run):
            out.append("  warning (%s %s): %s" % (label, run.run_id, w))
    return "\n".join(out)


def _speed_text(v):
    return "?" if v < 0 else "%.1f" % v


PLOT_COLOURS = ("#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300")      # one colour per comparison, in this fixed order


def plot_compare(comparisons, path):
    """Section 7 'delta curve': a figure of the difference to the baseline against route distance, one thin line per comparison (at most six),
    with the 'about the same' band of each as two dashed lines. matplotlib is imported here and only here; returns False (and says so) when it
    is not installed."""
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        print("matplotlib is not installed: the figure %s was not written" % path, file=sys.stderr)
        return False
    ink, quiet, surface = "#0b0b0b", "#52514e", "#fcfcfb"
    drawn = comparisons[:len(PLOT_COLOURS)]
    fig, ax = plt.subplots(figsize=(9, 4.6), facecolor=surface)
    ax.set_facecolor(surface)
    for c, colour in zip(drawn, PLOT_COLOURS):
        xs = [min(k * c.a.step_m, c.a.length_m) for k in range(len(c.delta))]
        ax.plot(xs, c.delta, color=colour, linewidth=1.6, label="%s vs %s: %+.2f s (dashed: about-the-same band +-%.2f s)" % (
            c.a.run_id, c.b.run_id, c.total_delta, c.band))
        ax.axhline(c.band, color=colour, linewidth=0.8, linestyle=(0, (4, 3)), alpha=0.6)
        ax.axhline(-c.band, color=colour, linewidth=0.8, linestyle=(0, (4, 3)), alpha=0.6)
    ax.axhline(0.0, color=quiet, linewidth=0.8)
    ax.set_xlabel("route distance (m)", color=quiet)
    ax.set_ylabel("this run minus baseline (s); positive = behind", color=quiet)
    ax.set_title("Difference to the baseline along the stage" + ("" if len(drawn) == len(comparisons) else " (first %d of %d)" % (len(drawn), len(comparisons))),
                 color=ink, fontsize=11)
    ax.tick_params(colors=quiet)
    ax.grid(color="#e4e3df", linewidth=0.6)
    for side in ("top", "right"):
        ax.spines[side].set_visible(False)
    for side in ("left", "bottom"):
        ax.spines[side].set_color("#c3c2b7")
    legend = ax.legend(fontsize=8, frameon=False, loc="upper left")
    for text in legend.get_texts():
        text.set_color(ink)
    fig.tight_layout()
    fig.savefig(path, dpi=130, facecolor=surface)
    plt.close(fig)
    return True


# =====================================================================================================================================
# Reading stages, runs and bundles from disk
# =====================================================================================================================================

class BundleRefused(Exception):
    """A bundle zip that is refused as a whole (too big, too many entries, a zip bomb, not a zip)."""


class Bundle:
    """What the careful bundle reader found: stages by id, their runs, and counts of the entries it ignored and of the files it skipped."""

    def __init__(self):
        self.stages = {}            # stage id -> Stage
        self.runs = {}              # stage id -> list of RunResult
        self.ignored = 0            # entries whose names are not exactly one of the two patterns
        self.skipped = 0            # files with a matching name that the strict reader refused
        self.skipped_why = []


def read_bundle(source):
    """docs/stage-format.md 'The bundle': read a bundle zip (a path, or the bytes of one) the careful way. Only entries named exactly
    `[tracklogger-stages/]<id>/stage.txt` or `[tracklogger-stages/]<id>/run_<id>.txt` are looked at; the zip is refused if it is over 50 MB, has more
    than 5000 entries, an entry expands to more than 5 MB, or all of them together expand to more than 32 MB (the real size is counted while reading, not the size the zip claims); every file
    goes through the strict readers, and a bad one is skipped and counted. Raises BundleRefused."""
    if isinstance(source, (bytes, bytearray)):
        size, fileobj = len(source), io.BytesIO(source)
    else:
        size, fileobj = os.path.getsize(source), source
    if size > MAX_BUNDLE_BYTES:
        raise BundleRefused("the bundle is %d bytes; more than %d MB is refused" % (size, MAX_BUNDLE_BYTES // (1024 * 1024)))
    try:
        zf = zipfile.ZipFile(fileobj)
    except (zipfile.BadZipFile, OSError) as err:
        raise BundleRefused("not a readable zip file (%s)" % err)
    with zf:
        infos = zf.infolist()
        if len(infos) > MAX_BUNDLE_ENTRIES:
            raise BundleRefused("the bundle has %d entries; more than %d is refused" % (len(infos), MAX_BUNDLE_ENTRIES))
        bundle = Bundle()
        seen = set()
        total = 0
        stage_files, run_files = [], []
        for info in infos:
            name = info.filename
            m_stage, m_run = _BUNDLE_STAGE_RE.fullmatch(name), _BUNDLE_RUN_RE.fullmatch(name)
            if info.is_dir() or not (m_stage or m_run):
                bundle.ignored += 1
                continue
            if name in seen:                                    # the same name twice: only the first is considered
                bundle.skipped += 1
                bundle.skipped_why.append("%s: the name appears twice" % name)
                continue
            seen.add(name)
            if info.file_size > MAX_ENTRY_BYTES:
                raise BundleRefused("%s claims to expand to %d bytes; more than %d MB is refused" % (name, info.file_size, MAX_ENTRY_BYTES // (1024 * 1024)))
            try:
                with zf.open(info) as fh:
                    data = fh.read(MAX_ENTRY_BYTES + 1)         # never trust the size in the zip: count what really comes out
            except Exception as err:                            # a damaged, encrypted or unsupported entry is a bad file, not a crash
                bundle.skipped += 1
                bundle.skipped_why.append("%s: %s" % (name, err))
                continue
            if len(data) > MAX_ENTRY_BYTES:
                raise BundleRefused("%s expands to more than %d MB" % (name, MAX_ENTRY_BYTES // (1024 * 1024)))
            total += len(data)
            if total > MAX_BUNDLE_TOTAL_BYTES:
                raise BundleRefused("the bundle expands to more than %d MB in all" % (MAX_BUNDLE_TOTAL_BYTES // (1024 * 1024)))
            (stage_files if m_stage else run_files).append((name, m_stage or m_run, data))
        for name, m, data in stage_files:
            try:
                bundle.stages[m.group(1)] = parse_stage_text(data.decode("utf-8"), expect_id=m.group(1))
            except (FormatError, UnicodeDecodeError) as err:
                bundle.skipped += 1
                bundle.skipped_why.append("%s: %s" % (name, err))
        for name, m, data in run_files:
            try:
                run = parse_run_text(data.decode("utf-8"), expect_stage=m.group(1), expect_run=m.group(2))
            except (FormatError, UnicodeDecodeError) as err:
                bundle.skipped += 1
                bundle.skipped_why.append("%s: %s" % (name, err))
                continue
            bundle.runs.setdefault(m.group(1), []).append(run)
    return bundle


def load_runs_in_folder(folder, stage_id=None):
    """All `run_<id>.txt` files of a folder, read with the strict reader. Returns (runs, notes); a file that is bad, or that belongs to
    another stage, is left out and named in the notes."""
    runs, notes = [], []
    for path in sorted(glob.glob(os.path.join(folder, "run_*.txt"))):
        try:
            run = load_run_file(path)
        except (FormatError, OSError, UnicodeDecodeError) as err:
            notes.append("skipped %s: %s" % (os.path.basename(path), err))
            continue
        if stage_id is not None and run.stage_id != stage_id:
            notes.append("skipped %s: it is a run of stage %s" % (os.path.basename(path), run.stage_id))
            continue
        runs.append(run)
    return runs, notes


def is_zip_file(path):
    try:
        with open(path, "rb") as fh:
            return fh.read(4) == b"PK\x03\x04"
    except OSError:
        return False


def load_stage_from_disk(path):
    """A stage.txt, or a folder holding stage.txt and run_*.txt. Returns (stage, runs saved beside it, notes)."""
    if os.path.isdir(path):
        folder, stage_path = path, os.path.join(path, "stage.txt")
    else:
        folder, stage_path = os.path.dirname(os.path.abspath(path)), path
    stage = load_stage_file(stage_path)
    runs, notes = load_runs_in_folder(folder, stage.id)
    return stage, runs, notes


def load_stage_and_runs(stage_arg, bundle_path=None, stage_id=None):
    """Find a stage and the runs saved with it. `stage_arg` is a stage.txt, a folder holding stage.txt and run_*.txt, a bundle zip, or (with
    `bundle_path`) the id of a stage in that bundle (or a stage.txt / folder whose id says which stage of the bundle is meant). A bundle holding
    several stages needs `stage_id` or such an argument. Returns (stage, runs, notes); raises FormatError, BundleRefused or OSError."""
    if bundle_path is None and os.path.isfile(stage_arg) and is_zip_file(stage_arg):
        bundle_path, stage_arg = stage_arg, None                        # the first argument is itself the bundle
    if bundle_path is None:
        return load_stage_from_disk(stage_arg)
    bundle = read_bundle(bundle_path)
    notes = []
    if bundle.ignored or bundle.skipped:
        notes.append("bundle: %d entries ignored, %d files skipped%s" % (bundle.ignored, bundle.skipped, "".join("\n    " + w for w in bundle.skipped_why)))
    wanted = stage_id
    if wanted is None and stage_arg is not None:
        wanted = load_stage_from_disk(stage_arg)[0].id if os.path.exists(stage_arg) else stage_arg
    if wanted is None and len(bundle.stages) == 1:
        wanted = next(iter(bundle.stages))
    known = ", ".join(sorted(bundle.stages)) or "none"
    if wanted is None:
        raise BundleRefused("the bundle holds %d stages (%s); name one" % (len(bundle.stages), known))
    if wanted not in bundle.stages:
        raise BundleRefused("the bundle has no stage %r (it has: %s)" % (wanted, known))
    return bundle.stages[wanted], bundle.runs.get(wanted, []), notes


# =====================================================================================================================================
# The selftest: every expected result in analysis/stage_fixtures, recomputed
# =====================================================================================================================================

MAX_SHOWN = 6                   # differences printed per failing group
ABS_SLACK = 1e-9                # the README's 'plus 1e-9'


def decimals_of(word):
    """Decimals printed in a number word of an expected file: '12.345' -> 3, '7' -> 0."""
    return len(word.split(".", 1)[1]) if "." in word else 0


def diff_lines(mine, expected_text, label="", skip_comments=False):
    """Compare lists of lines (words: str or Num) with the text of an expected file by the rule of analysis/stage_fixtures/README.md: a number
    agrees if it is within half a unit of the last printed decimal (plus 1e-9) of the expected word; text must be identical. Returns the
    differences, each a sentence with the numbers."""
    def words(line):
        out = []
        for w in line:
            if isinstance(w, str):
                out.extend(w.split(" "))
            else:
                out.append(w)
        return out

    expected = [ln.split(" ") for ln in expected_text.splitlines() if ln.strip() != "" and not (skip_comments and ln.startswith("#"))]
    mine = [words(ln) for ln in mine]
    diffs = []
    if len(mine) != len(expected):
        diffs.append("%s: %d lines, expected %d" % (label, len(mine), len(expected)))
    for i, (m, e) in enumerate(zip(mine, expected)):
        where = "%s line %d (%s)" % (label, i + 1, e[0] if e else "")
        if len(m) != len(e):
            diffs.append("%s: %d words, expected %d: %r" % (where, len(m), len(e), " ".join(w if isinstance(w, str) else w.text() for w in m)))
            continue
        for j, (a, b) in enumerate(zip(m, e)):
            if isinstance(a, Num):
                x = parse_float(b)
                if x is None:
                    diffs.append("%s word %d: computed %s but expected the text %r" % (where, j + 1, a.text(), b))
                    continue
                allowed = 0.5 * 10.0 ** (-decimals_of(b)) + ABS_SLACK
                if not abs(a.value - x) <= allowed:
                    diffs.append("%s word %d: %.10g, expected %s (differs by %.3g, allowed %.3g)" % (where, j + 1, a.value, b, a.value - x, allowed))
            elif a != b:
                diffs.append("%s word %d: %r, expected %r" % (where, j + 1, a, b))
    return diffs


class Group:
    """One line of the selftest report: a label, the verdict, and the differences found."""

    def __init__(self, label):
        self.label = label
        self.diffs = []
        self.detail = ""
        self.skipped = False

    @property
    def ok(self):
        return not self.diffs and not self.skipped


def _read_text(path):
    with open(path, "r", encoding="utf-8") as fh:
        return fh.read()


def _fixture_stage_facts(path):
    """The id, name and created_utc_ms of an expected stage file: the facts a marking recording cannot tell."""
    stage = load_stage_file(path)
    return stage.id, stage.name, stage.created_utc_ms


def check_gates(fixtures):
    """Section 3: the 400 line-crossing cases of gates.csv, to 1e-6 s."""
    g = Group("gates.csv: the line-crossing step")
    path = os.path.join(fixtures, "gates.csv")
    n = n_none = 0
    with open(path, newline="", encoding="utf-8") as fh:
        lines = [ln for ln in fh if ln.strip()]
        lines[0] = lines[0].lstrip("# ")                    # the header line starts '# case,lat0,...'
        for row in csv.DictReader(lines):
            n += 1
            geo = Geo(float(row["lat0"]), float(row["lon0"]))
            gate = Gate(float(row["gate_cx"]), float(row["gate_cy"]), float(row["gate_bearing"]), float(row["half"]))
            fa = Fix(float(row["ta"]), float(row["lata"]), float(row["lona"]), float(row["spa"]), float(row["bra"]))
            fb = Fix(float(row["tb"]), float(row["latb"]), float(row["lonb"]), float(row["spb"]), float(row["brb"]))
            pa = place_fix(fa, geo, None)
            pb = place_fix(fb, geo, pa)
            got = gate.crossing(pa, pb, float(row["min_speed"]), float(row["min_forward"]))
            want = row["expected_crossing_s_or_none"]
            if want == "none":
                n_none += 1
                if got is not None:
                    g.diffs.append("case %s: found a crossing at %.9f s, expected none" % (row["case"], got))
            elif got is None:
                g.diffs.append("case %s: found none, expected %s" % (row["case"], want))
            elif abs(got - float(want)) > 1e-6:
                g.diffs.append("case %s: %.9f s, expected %s (differs by %.3g s)" % (row["case"], got, want, got - float(want)))
    g.detail = "%d cases (%d with a crossing, %d none) agree to 1e-6 s" % (n, n - n_none, n_none)
    return g


def _unescape_name(text):
    """The names in run_ids.csv write every UTF-16 unit outside printable ASCII, and the backslash, as \\uXXXX."""
    units, i = [], 0
    while i < len(text):
        if text[i] == "\\" and text[i + 1:i + 2] == "u":
            units.append(int(text[i + 2:i + 6], 16))
            i += 6
        else:
            units.append(ord(text[i]))
            i += 1
    return b"".join(u.to_bytes(2, "little") for u in units).decode("utf-16-le", "surrogatepass")


def check_run_ids(fixtures):
    """Section 6 'run ids': the id of the third run found in each of the odd file names of run_ids.csv."""
    g = Group("run_ids.csv: ids of runs from odd file names")
    n = 0
    for ln in _read_text(os.path.join(fixtures, "run_ids.csv")).split("\n"):
        if not ln.strip() or ln.startswith("#"):
            continue
        case, escaped, want = ln.split("\t")
        n += 1
        got = make_run_id(_unescape_name(escaped), 3)
        if got != want:
            g.diffs.append("case %s (%s): %s, expected %s" % (case, escaped, got, want))
    g.detail = "%d names, every id the same as the app's" % n
    return g


def check_refusals(fixtures):
    """Section 4: the four marking drives that cannot make a stage, and why."""
    g = Group("refusals: drives that cannot make a stage")
    folder = os.path.join(fixtures, "refusals")
    expected = {}
    for ln in _read_text(os.path.join(folder, "expected.txt")).splitlines():
        if ln.strip() and not ln.startswith("#"):
            name, code = ln.split()
            expected[name] = code
    for name in sorted(expected):
        ses = read_session(os.path.join(folder, name))
        try:
            start, finish, drive, _ = marking_from_session(ses)
            build_stage(start, finish, drive, "st_refusal", "refusal")
            got = "a stage was built"
        except StageRefused as err:
            got = err.code
        except MarkingError as err:
            got = "marking error: %s" % err
        if got != expected[name]:
            g.diffs.append("%s: refused as %r, expected %r" % (name, got, expected[name]))
    g.detail = "%d drives refused for the expected reason" % len(expected)
    return g


def check_build(folder, label):
    """Section 4: the stage rebuilt from marking.csv against stage.txt."""
    g = Group("%s: stage built from marking.csv" % label)
    expected_path = os.path.join(folder, "stage.txt")
    stage_id, name, created = _fixture_stage_facts(expected_path)
    ses = read_session(os.path.join(folder, "marking.csv"))
    start, finish, drive, _ = marking_from_session(ses)
    try:
        stage = build_stage_raw(start, finish, drive, stage_id, name, created)
    except StageRefused as err:
        g.diffs.append("the stage was refused: %s" % err.message)
        return g
    g.diffs = diff_lines(stage.tokens(), _read_text(expected_path), "stage.txt")
    g.detail = "%d route vertices, %.2f m" % (len(stage.route_points), stage.length_m)
    return g


def check_scoring(folder, session_path, label):
    """Sections 5 and 6: every run found in one recording against the run files expected for it. Scored on the expected stage.txt, read the
    way the app reads it."""
    ses_name = os.path.basename(session_path)
    stem = ses_name[:-4]
    if stem.startswith("session_"):
        stem = stem[len("session_"):]
    g = Group("%s: runs in %s" % (label, ses_name))
    stage = load_stage_file(os.path.join(folder, "stage.txt"))
    ses = read_session(session_path)
    results = score_recording(stage, ses.fixes, ses.name, ses.start_utc_ms)
    expected_files = {}
    for path in glob.glob(os.path.join(folder, "expected", "run_%s_*.txt" % stem)):
        run_id = os.path.basename(path)[len("run_"):-len(".txt")]
        if re.fullmatch(re.escape(stem) + "_[0-9]+", run_id):
            expected_files[run_id] = path
    mine = dict((r.run_id, r) for r in results)
    for run_id in sorted(set(mine) - set(expected_files)):
        g.diffs.append("%s: a run was found that is not expected" % run_id)
    for run_id in sorted(set(expected_files) - set(mine)):
        g.diffs.append("%s: an expected run was not found" % run_id)
    for run_id in sorted(set(mine) & set(expected_files)):
        g.diffs += diff_lines(mine[run_id].tokens(), _read_text(expected_files[run_id]), "run_%s.txt" % run_id)
    done = sum(1 for r in results if r.finished)
    g.detail = "%d runs (%d finished, %d not)" % (len(results), done, len(results) - done)
    return g


def check_compare(folder, label):
    """Section 7: every ordered pair of finished runs, computed from the expected run files as stored, against compare_expected.txt."""
    g = Group("%s: comparison of every pair of runs" % label)
    runs = [load_run_file(p) for p in sorted(glob.glob(os.path.join(folder, "expected", "run_*.txt")))]
    runs = sorted((r for r in runs if r.finished), key=lambda r: r.run_id)
    text = _read_text(os.path.join(folder, "compare_expected.txt"))
    blocks, current = {}, None
    for ln in text.splitlines():
        if ln.startswith("pair "):
            current = tuple(ln.split()[1:3])
            blocks[current] = []
        if current is not None and not ln.startswith("end"):
            blocks[current].append(ln)
    mine = {}
    for a in runs:
        for b in runs:
            if a is not b:
                mine[(a.run_id, b.run_id)] = Compare(a, b)
    for key in sorted(set(mine) - set(blocks)):
        g.diffs.append("pair %s %s: computed but not in the expected file" % key)
    for key in sorted(set(blocks) - set(mine)):
        g.diffs.append("pair %s %s: expected but not computed" % key)
    for key in sorted(set(mine) & set(blocks)):
        g.diffs += diff_lines(mine[key].tokens(), "\n".join(blocks[key]), "pair %s %s" % key)
    g.detail = "%d ordered pairs of %d runs" % (len(mine), len(runs))
    return g


def check_real(fixtures, real_log, explicit):
    """Sections 4 and 6 on the real Sepang recording: the pseudo-stage cut out of it (real/marks.txt) and the runs found in the whole file.
    Returns two Groups. If the 28 MB file is absent the groups are skipped with a message (a FAIL if the path was given with --real-log)."""
    build_g = Group("real: pseudo-stage cut out of the Sepang recording")
    runs_g = Group("real: runs found in the whole Sepang recording")
    if not os.path.isfile(real_log):
        for g in (build_g, runs_g):
            if explicit:
                g.diffs.append("the real log %s does not exist" % real_log)
            else:
                g.skipped = True
                g.detail = "%s is not there (give --real-log CSV to run this group)" % real_log
        return [build_g, runs_g]
    folder = os.path.join(fixtures, "real")
    marks = {}
    for ln in _read_text(os.path.join(folder, "marks.txt")).splitlines():
        if ln.strip() and not ln.startswith("#"):
            key, _, value = ln.partition(" ")
            marks[key] = value.strip()
    t_start, t_finish = int(marks["start_fix_t_us"]) / 1e6, int(marks["finish_fix_t_us"]) / 1e6
    ses = read_session(real_log)
    fixes = clean_fixes(ses.fixes)
    by_t = dict((f.t, i) for i, f in enumerate(fixes))
    if t_start not in by_t or t_finish not in by_t:
        build_g.diffs.append("the two mark fixes (t_us %s and %s) are not in the log" % (marks["start_fix_t_us"], marks["finish_fix_t_us"]))
    else:
        i0, i1 = by_t[t_start], by_t[t_finish]
        start, finish = (fixes[i0].lat, fixes[i0].lon), (fixes[i1].lat, fixes[i1].lon)
        drive = fixes[i0 + 1:i1]                      # every fix between the two marks
        expected_path = os.path.join(folder, "stage.txt")
        stage_id, name, created = _fixture_stage_facts(expected_path)
        try:
            stage = build_stage_raw(start, finish, drive, stage_id, name, created)
            build_g.diffs = diff_lines(stage.tokens(), _read_text(expected_path), "stage.txt")
            build_g.detail = "%d route vertices, %.2f m" % (len(stage.route_points), stage.length_m)
        except StageRefused as err:
            build_g.diffs.append("the stage was refused: %s" % err.message)
    runs_g = check_scoring(folder, real_log, "real")
    runs_g.label = "real: runs found in the whole Sepang recording"
    return [build_g, runs_g]


def check_bundle_reader():
    """docs/stage-format.md 'The bundle': the careful reader, tried on bundles built here (a good one among hostile names, bad files, the three
    caps, a damaged zip, a zip whose directory lies about a size). The fixtures hold no bundle, so these cases are this script's own."""
    g = Group("bundle reader: names, caps, strict parsing")
    stage_text = _SELFTEST_STAGE_TEXT
    stage = parse_stage_text(stage_text)
    run = RunResult(stage.id, "r1", "session_x.csv", 1, True, "ok", 10.0, stage.length_m, 25.0, 5, 1.0, 0.0, 3.0, 1.0, 1.0, 1.0, 20.0, 20.0,
                    [0.0, 5.0, 10.0])
    good_run = run.text()
    root = BUNDLE_ROOT + "/" + stage.id + "/"

    def zip_bytes(entries, deflate=True):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED if deflate else zipfile.ZIP_STORED) as zf:
            for name, data in entries:
                zf.writestr(name, data)
        return buf.getvalue()

    def check_refused(what, data):
        try:
            read_bundle(data)
        except BundleRefused:
            return
        g.diffs.append("%s was not refused" % what)

    # 1. one good stage and one good run among entries that must be ignored (names off the two patterns) or skipped (bad files)
    ignored_names = ["../evil.txt", "/abs/stage.txt", BUNDLE_ROOT + "/../stage.txt", BUNDLE_ROOT + "\\" + stage.id + "\\stage.txt",
                     root + "notes.txt", root + "sub/stage.txt", BUNDLE_ROOT + "/bad id/stage.txt", root + "stage.txt\n",
                     "other/" + root + "stage.txt", root + "run_.txt", root]
    entries = [(root + "stage.txt", stage_text), (root + "run_r1.txt", good_run),
               (root + "run_r2.txt", good_run),                                            # the file name says r2, the content r1: bad
               (BUNDLE_ROOT + "/other/stage.txt", stage_text.replace("end\n", ""))]        # no 'end' line (and another id): bad
    entries += [(name, "x") for name in ignored_names]
    b = read_bundle(zip_bytes(entries))
    if list(b.stages) != [stage.id] or len(b.runs.get(stage.id, [])) != 1:
        g.diffs.append("good bundle: found stages %s and %d run(s), expected [%r] and 1 run" % (list(b.stages), len(b.runs.get(stage.id, [])), stage.id))
    if b.skipped != 2:
        g.diffs.append("good bundle: %d files skipped, expected 2" % b.skipped)
    if b.ignored != len(ignored_names):
        g.diffs.append("good bundle: %d entries ignored, expected %d" % (b.ignored, len(ignored_names)))
    # 1b. the top folder may be missing (a zip made by hand from the app's stages folder), and folder entries may be present
    b = read_bundle(zip_bytes([(stage.id + "/", ""), (stage.id + "/stage.txt", stage_text), (stage.id + "/run_r1.txt", good_run)]))
    if list(b.stages) != [stage.id] or len(b.runs.get(stage.id, [])) != 1 or b.skipped != 0:
        g.diffs.append("a bundle without the top folder: found stages %s and %d run(s), %d skipped; expected [%r], 1 run, 0 skipped"
                       % (list(b.stages), len(b.runs.get(stage.id, [])), b.skipped, stage.id))
    # 2. the caps
    check_refused("an entry that expands to more than 5 MB", zip_bytes([(root + "stage.txt", "a" * (MAX_ENTRY_BYTES + 1))]))
    check_refused("5001 entries", zip_bytes([("e%d.txt" % i, "") for i in range(MAX_BUNDLE_ENTRIES + 1)], deflate=False))
    check_refused("a zip of more than 50 MB", b"\0" * (MAX_BUNDLE_BYTES + 1))
    check_refused("bytes that are not a zip", b"PK\x03\x04 this is not a zip")
    b = read_bundle(zip_bytes([("e%d.txt" % i, "") for i in range(MAX_BUNDLE_ENTRIES - 1)] + [(root + "stage.txt", stage_text)], deflate=False))
    if list(b.stages) != [stage.id]:
        g.diffs.append("a bundle of exactly 5000 entries was not read")
    # 3. a zip whose directory says an entry is small while it really expands past the cap: refused, or skipped as damaged, never read
    lie = bytearray(zip_bytes([(root + "stage.txt", "0" * (MAX_ENTRY_BYTES + 1024))]))
    for signature, offset in ((b"PK\x03\x04", 22), (b"PK\x01\x02", 24)):          # the uncompressed-size fields of the two headers
        pos = bytes(lie).find(signature)
        lie[pos + offset:pos + offset + 4] = (100).to_bytes(4, "little")
    try:
        b = read_bundle(bytes(lie))
        if b.stages:
            g.diffs.append("a zip with a lying size was read as a stage")
    except BundleRefused:
        pass
    g.detail = "good entries kept; %d hostile names ignored, 2 bad files skipped and counted; the caps and a lying zip refused" % len(ignored_names)
    return g


def check_round_trip():
    """The writers and the strict readers agree: a stage and a run written, read back and written again give the same text; the strict
    reader refuses a file with a missing 'end', a wrong route count, an unknown kind."""
    g = Group("file formats: write, read back, refuse the broken")
    stage = parse_stage_text(_SELFTEST_STAGE_TEXT)
    if parse_stage_text(stage.text()).text() != stage.text():
        g.diffs.append("a stage does not survive write, read, write")
    bad = {"missing end": _SELFTEST_STAGE_TEXT.replace("end\n", ""),
           "wrong route count": _SELFTEST_STAGE_TEXT.replace("route 4", "route 5"),
           "unknown kind": _SELFTEST_STAGE_TEXT.replace("kind road", "kind rally"),
           "bad number": _SELFTEST_STAGE_TEXT.replace("start_gate 0.000", "start_gate 0.0_0"),
           "nan": _SELFTEST_STAGE_TEXT.replace("start_gate 0.000", "start_gate nan"),
           "route of one": "TRACKLOGGER-STAGE 1\nid a\nkind road\norigin 1 1\nstart_mark 1 1\nfinish_mark 1 1\nstart_gate 0 0 0 25\n"
                           "finish_gate 0 0 0 25\nroute 1\nr 0 0\nend\n"}
    for what, text in bad.items():
        try:
            parse_stage_text(text)
            g.diffs.append("the strict reader accepted a stage with: " + what)
        except FormatError:
            pass
    unknown = _SELFTEST_STAGE_TEXT.replace("kind road\n", "kind road\nfuture_key 1 2 3\n")
    try:
        parse_stage_text(unknown)
    except FormatError as err:
        g.diffs.append("an unknown key was not skipped: %s" % err)
    g.detail = "stage and run files round-trip; 6 kinds of broken file refused; unknown keys skipped"
    return g


_SELFTEST_STAGE_TEXT = """TRACKLOGGER-STAGE 1
id st_selftest
name Selftest stage
kind road
created_utc_ms 0
origin 3.1234567 101.7654321
start_mark 3.1234567 101.7654321
finish_mark 3.1301234 101.7654321
start_gate 0.000 0.000 0.000 25.0
finish_gate 0.000 700.000 0.000 25.0
route 4
r 0.000 0.000
r 0.000 250.000
r 0.000 500.000
r 0.000 700.000
end
"""


def run_selftest(fixtures, real_log, real_log_explicit):
    """Run every group, print one PASS / FAIL / SKIP line each (with the first differences of a FAIL) and a summary. Returns the exit status."""
    t0 = time.time()
    if not os.path.isdir(fixtures):
        print("FAIL  the fixtures folder %s does not exist" % fixtures)
        return 1
    groups = [check_gates(fixtures), check_run_ids(fixtures), check_refusals(fixtures)]
    for road in ("road1", "road2"):
        folder = os.path.join(fixtures, road)
        groups.append(check_build(folder, road))
        for session in sorted(glob.glob(os.path.join(folder, "session_*.csv"))):
            groups.append(check_scoring(folder, session, road))
        groups.append(check_compare(folder, road))
    groups += check_real(fixtures, real_log, real_log_explicit)
    groups += [check_bundle_reader(), check_round_trip()]
    n_pass = n_fail = n_skip = 0
    for g in groups:
        if g.skipped:
            n_skip += 1
            print("SKIP  %s -- %s" % (g.label, g.detail))
        elif g.ok:
            n_pass += 1
            print("PASS  %s -- %s" % (g.label, g.detail))
        else:
            n_fail += 1
            print("FAIL  %s -- %d difference(s)" % (g.label, len(g.diffs)))
            for d in g.diffs[:MAX_SHOWN]:
                print("        " + d)
            if len(g.diffs) > MAX_SHOWN:
                print("        ... and %d more" % (len(g.diffs) - MAX_SHOWN))
    print("\n%d PASS, %d FAIL, %d SKIP (%d groups, %.1f s)" % (n_pass, n_fail, n_skip, len(groups), time.time() - t0))
    return 1 if n_fail else 0


# =====================================================================================================================================
# The command line
# =====================================================================================================================================

def cmd_build(args):
    ses = read_session(args.marking)
    try:
        start, finish, drive, finish_t_us = marking_from_session(ses)
    except MarkingError as err:
        print("Cannot make a stage from %s: %s." % (args.marking, err))
        return 1
    created = 0 if ses.start_utc_ms is None else ses.start_utc_ms + round_half_up(finish_t_us / 1000.0)
    stage_id = args.id or (default_stage_id(created) if created else "st_" + re.sub("[^%s]" % _ID_CHARS, "_", os.path.splitext(ses.name)[0])[:60])
    if not _is_id(stage_id):
        print("The id %r is not 1 to 64 characters of a-z A-Z 0-9 _ -." % stage_id)
        return 1
    name = plain(args.name if args.name else "Stage " + stage_id)[:60]
    try:
        stage = build_stage(start, finish, drive, stage_id, name, created)
    except StageRefused as err:
        print("No stage: %s." % err.message)
        return 1
    with open(args.out, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(stage.text())
    print("wrote %s: stage %s \"%s\", %.1f m between the lines (they are %.0f m in from each mark), %d route vertices" % (
        args.out, stage.id, stage.name, stage.length_m, LINE_INSET_M, len(stage.route_points)))
    return 0


def print_run_table(results):
    """The table printed by `score`: run id, finished, time, speed at the two lines, warnings."""
    rows = [("run id", "finished", "time", "start m/s", "finish m/s", "warnings")]
    for r in results:
        warn = warnings_for(r)
        if not r.finished:
            warn = [r.status] + warn
        rows.append((r.run_id, "yes" if r.finished else "no", format_clock(r.total_s) if r.finished else "-",
                     _speed_text(r.start_speed), _speed_text(r.finish_speed), "; ".join(warn) if warn else "-"))
    widths = [max(len(row[i]) for row in rows) for i in range(5)]
    for row in rows:
        print("  ".join(row[i].ljust(widths[i]) for i in range(5)) + "  " + row[5])


def cmd_score(args):
    try:
        stage = load_stage_file(os.path.join(args.stage, "stage.txt") if os.path.isdir(args.stage) else args.stage)
    except (FormatError, OSError, UnicodeDecodeError) as err:
        print("Cannot read the stage %s: %s" % (args.stage, err))
        return 1
    status = 0
    for path in args.sessions:
        try:
            results = score_session(stage, path)
        except OSError as err:
            print("Cannot read %s: %s" % (path, err))
            status = 1
            continue
        print("%s: %d run(s) of stage %s" % (os.path.basename(path), len(results), stage.id))
        if results:
            print_run_table(results)
        if args.out_dir:
            os.makedirs(args.out_dir, exist_ok=True)
            for r in results:
                with open(os.path.join(args.out_dir, "run_%s.txt" % r.run_id), "w", encoding="utf-8", newline="\n") as fh:
                    fh.write(r.text())
    return status


def cmd_compare(args):
    try:
        stage, saved, notes = load_stage_and_runs(args.stage, args.bundle, args.stage_id)
    except (FormatError, BundleRefused, OSError, UnicodeDecodeError) as err:
        print("Cannot read the stage: %s" % err)
        return 1
    for note in notes:
        print("note: " + note)
    given = []                                  # the runs named on the command line: each is looked at
    for path in args.runs:
        try:
            if path.lower().endswith(".csv"):
                given += score_session(stage, path)
            else:
                run = load_run_file(path)
                if run.stage_id != stage.id:
                    print("note: skipped %s: it is a run of stage %s, not %s" % (path, run.stage_id, stage.id))
                    continue
                given.append(run)
        except (FormatError, OSError, UnicodeDecodeError) as err:
            print("Cannot read %s: %s" % (path, err))
            return 1
    known = dict((r.run_id, r) for r in saved)
    known.update((r.run_id, r) for r in given)
    runs = sorted(known.values(), key=lambda r: (r.utc_ms, r.run_id))
    finished = [r for r in runs if r.finished]
    print("stage %s \"%s\": %.1f m; %d run(s) known, %d finished" % (stage.id, stage.name, stage.length_m, len(runs), len(finished)))
    for r in runs:
        if not r.finished:
            print("  run %s did not finish (%s)" % (r.run_id, r.status))
    looked_at = [r for r in (given if given else runs[-1:]) if r.finished and r.run_id != args.against]    # each named run, or else the newest
    if not looked_at:
        print("No finished run to look at.")
        return 1
    comparisons = []
    for a in looked_at:
        b = choose_baseline(finished, a, args.against)
        print("")
        if b is None:
            print("run %s: no baseline to compare with (--against %s)" % (a.run_id, args.against))
            continue
        try:
            c = Compare(a, b)
        except CompareError as err:
            print("run %s against %s cannot be compared: %s" % (a.run_id, b.run_id, err))
            continue
        comparisons.append(c)
        print(format_compare(c))
    if not comparisons:
        return 1
    if args.plot and not plot_compare(comparisons, args.plot):
        return 1
    return 0


def cmd_selftest(args):
    explicit = args.real_log is not None
    return run_selftest(args.fixtures, args.real_log if explicit else DEFAULT_REAL_LOG, explicit)


def make_parser():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0].strip(), formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="command")
    sub.required = True

    p = sub.add_parser("build", help="build a stage from a marking recording")
    p.add_argument("marking", metavar="MARKING.csv", help="session CSV with MARK_START and MARK_FINISH events and the drive between them")
    p.add_argument("--id", help="stage id, 1 to 64 characters of a-z A-Z 0-9 _ - (default: 'st' and the UTC time of the marking)")
    p.add_argument("--name", help="stage name, at most 60 characters (default: 'Stage <id>')")
    p.add_argument("--out", default="stage.txt", help="where to write the stage file (default: stage.txt)")
    p.set_defaults(func=cmd_build)

    p = sub.add_parser("score", help="find and time every run of a stage in session recordings")
    p.add_argument("stage", metavar="STAGE", help="stage.txt, or a folder holding one")
    p.add_argument("sessions", metavar="SESSION.csv", nargs="+", help="one or more session CSVs")
    p.add_argument("--out-dir", metavar="DIR", help="write one run_<id>.txt per run here (default: print the table only)")
    p.set_defaults(func=cmd_score)

    p = sub.add_parser("compare", help="compare runs of one stage")
    p.add_argument("stage", metavar="STAGE", help="stage.txt, a folder with stage.txt and run_*.txt, a bundle zip, or (with --bundle) a stage id")
    p.add_argument("runs", metavar="RUN.txt|SESSION.csv", nargs="*", help="runs to look at (a session CSV is scored first); default: the newest saved run")
    p.add_argument("--bundle", metavar="BUNDLE.zip", help="read the stage and its runs from this bundle (STAGE then names the stage in it)")
    p.add_argument("--stage-id", metavar="ID", help="which stage of a bundle holding several")
    p.add_argument("--against", default="best", metavar="best|previous|RUNID", help="the baseline: the best other run, the previous run, or a run id")
    p.add_argument("--plot", metavar="FILE.png", help="also draw the difference to the baseline against route distance (needs matplotlib)")
    p.set_defaults(func=cmd_compare)

    p = sub.add_parser("selftest", help="recompute every expected result of analysis/stage_fixtures")
    p.add_argument("--fixtures", default=DEFAULT_FIXTURES, metavar="DIR", help="the fixtures folder (default: stage_fixtures next to this script)")
    p.add_argument("--real-log", metavar="CSV", help="the real Sepang recording (default: data/sepang-2026-10-13/session_20261013_095638.csv)")
    p.set_defaults(func=cmd_selftest)
    return ap


def main(argv=None):
    args = make_parser().parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())

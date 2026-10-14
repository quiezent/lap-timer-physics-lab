#!/usr/bin/env python3
"""
pin_compare.py - make a road stage from two pins on an ordinary recording (docs/stage-algorithm.md, section 11.1 to 11.8)

    python3 analysis/pin_compare.py coords "<text>"
    python3 analysis/pin_compare.py build SESSION.csv START FINISH [--id ID] [--name NAME] [--created MS] [--out FILE]
    python3 analysis/pin_compare.py selftest [--fixtures DIR] [--seed N] [--no-own]

A second, independent implementation of section 11.1 to 11.8 of the method page (the track of a recording, snapping a place to it, the two
positions and their rules, the two lines, the stage, the guard against a road that crosses a line twice, and reading a place from text),
written from that page alone: nothing here was copied from or checked against the Android source. It builds on analysis/stage_compare.py
(also written from the page) for reading session CSVs, the flat frame (Geo), the timing line (Gate), the stage file (Stage: written by the same
code as every other stage), scoring and the comparison helpers; what it relies on there was checked against sections 1 to 6 of the page.

    coords TEXT      prints `ok LAT LON` (9 decimals) and the 6-decimal form shown to the driver, or `refused: <why>`.
    build ...        START and FINISH are `pos:METRES` (a distance along the track) or `at:LAT,LON` (a place). Prints the stage file on
                     stdout (the sentences of 11.7 go to stderr), or `refused: <category>: <why>`.
    selftest         the fixture folder analysis/stage_fixtures/pins/ (coords.tsv, tracks.tsv, passes.tsv, cases.tsv, stages/, runs/) is
                     recomputed and compared by the rule of its README; then this script's own checks (synthetic roads, every refusal,
                     the examples of 11.8, a fuzz of the text reader) run. If the fixture folder is not there yet it says
                     "fixtures not found yet" and goes on with the own checks.

Exit status: 0 everything passed; 1 a check failed, or build/coords refused; 2 selftest passed but the fixtures are not there (nothing was compared
with the expected results), or a mistake on the command line.

Importable: parse_coords(text) -> (lat, lon) or raises Refused; Track(fixes) (raises Refused 'no_drive'; `.holes` are the holes of 11.1 step 5 and
`.time_at(d)` is timeAt of step 6); build_stage(track_or_fixes, start_pin, finish_pin, stage_id, name, created_utc_ms) -> the stage as read back from
its file, or raises Refused with .category in no_drive, no_pass_start, no_pass_finish, wrong_way, near_end, too_short, loop, hole_start, hole_finish,
break, guard_start, guard_finish. A pin is ("pos", metres) or ("at", lat, lon).
"""
import argparse
import bisect
import math
import os
import random
import re
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import stage_compare as sc          # noqa: E402  (reused: session reading, Geo, Gate, Stage + file writer, scoring, diff helpers)

DEFAULT_FIXTURES = sc.DEFAULT_FIXTURES
DEFAULT_REAL_LOG = sc.DEFAULT_REAL_LOG

# ---- constants of the method (section number in brackets) ----------------------------------------------------------------------------
TRACK_MAX_HACC = 30.0               # [11.1] a fix with a worse reported accuracy is not kept
TRACK_MIN_SPEED = 1.0               # [11.1] a fix with a known speed under this (m/s) is not kept
TRACK_MIN_VERTEX_GAP = 3.0          # [11.1] a kept fix is a vertex if it is at least this far from the last vertex
TRACK_MIN_VERTICES = 3              # [11.1]
SNAP_LIMIT = 60.0                   # [11.2] segments this close (or closer) to the place form passes
PASS_SPREAD = 10.0                  # [11.2] passes further than the nearest one by more than this are dropped
PIN_MARGIN = 20.0                   # [11.3] rule 2: this much track before the start and after the finish
MIN_STAGE_LENGTH = 300.0            # [11.3] rule 3
MIN_STRAIGHT = 150.0                # [11.3] rule 4
HOLE_SECONDS = 3.0                  # [11.1 step 5] consecutive fixes further apart in time than this (s) are a hole
BREAK_SECONDS, BREAK_METRES = 300.0, 100.0   # [11.3 rule 7] a hole longer in time AND further apart in metres than this is a break
DIRECTION_HALF_SPAN = 20.0          # [11.4] the line's direction is taken over the 40 m around the pin
GATE_HALF = 25.0                    # [11.4]
ROUTE_MARGIN = 0.01                 # [11.5]
GUARD_WIDTH = 35.0                  # [11.6]
GUARD_START_MIN, GUARD_START_MAX = 30.0, 300.0
GUARD_FINISH_FRACTION, GUARD_FINISH_END = 0.7, 30.0
GAP_SECONDS, GAP_METRES = 20.0, 100.0        # [11.7]
OFFSET_REPORT_M = 5.0               # [11.7]
MAX_TEXT_UNITS = 4000               # [11.8]

CATEGORIES = ("no_drive", "no_pass_start", "no_pass_finish", "wrong_way", "near_end", "too_short", "loop", "hole_start", "hole_finish", "break",
              "guard_start", "guard_finish")


class Refused(Exception):
    """Something section 11 refuses. `message` is a short sentence; `category` is one of CATEGORIES for a refused recording or pin pair
    (11.1 to 11.6) and None for a text that is not a place (11.8)."""

    def __init__(self, message, category=None):
        Exception.__init__(self, message)
        self.message = message
        self.category = category


# =====================================================================================================================================
# 11.8  Reading coordinates (Coords)
# =====================================================================================================================================

# "White space" is exactly these 28 characters (11.8): U+0009 to U+000D, U+001C to U+0020, U+00A0, U+1680, U+2000 to U+200A, U+2028, U+2029,
# U+202F, U+205F and U+3000. Not U+0085, U+180E, U+200B, U+FEFF. Written out, as the page does, and not looked up in a category table.
_WHITE_RANGES = ((0x09, 0x0D), (0x1C, 0x20), (0xA0, 0xA0), (0x1680, 0x1680), (0x2000, 0x200A), (0x2028, 0x2029), (0x202F, 0x202F),
                 (0x205F, 0x205F), (0x3000, 0x3000))
WHITE = frozenset(c for lo, hi in _WHITE_RANGES for c in range(lo, hi + 1))
MINUS_SIGNS = "-\u2212"
SIGNS = "+-\u2212"
DEGREE_MARKS = "\u00b0\u00ba\u02da"
MINUTE_MARKS = "'\u2019\u2032"
SECOND_MARKS = "\"\u201d\u2033"
MARKS = DEGREE_MARKS + MINUTE_MARKS + SECOND_MARKS
LETTERS = "NSEWnsew"

_NUM = r"-?[0-9]+(?:\.[0-9]+)?"                  # the number of a link: ASCII digits only
# Case is ASCII only (11.8). The page says "without regard to case" for the detection of a link and for the third pattern (the names q, ll, query,
# center, and %2C / %2c); it is silent for `!3d!4d`, `@`, `/place/` and for the `goo.gl/` test, which are matched the same way here (a fixture
# reads `/PLACE/` as a place).
_LINK_PATTERNS = tuple(re.compile(p, re.ASCII | re.IGNORECASE) for p in (
    "!3d(" + _NUM + ")!4d(" + _NUM + ")",                                            # the place itself
    "@(" + _NUM + "),(" + _NUM + ")",                                                # the middle of the map
    "[?&](?:q|ll|query|center)=(" + _NUM + ")(?:,|%2C)(" + _NUM + ")",              # a query
    "/place/(" + _NUM + "),(" + _NUM + ")"))
_ASCII_LOWER = {c: c + 32 for c in range(ord("A"), ord("Z") + 1)}
_GEO_START = re.compile("[Gg][Ee][Oo]:")
_GEO_BODY = re.compile("(" + _NUM + "),(" + _NUM + ")(?:,(" + _NUM + "))?")
_PLAIN_NUMBER = re.compile(r"([+\-\u2212]?)([0-9]+(?:\.[0-9]*)?|\.[0-9]+)")


def is_white(ch):
    return ord(ch) in WHITE


def utf16_length(text):
    """The length in UTF-16 code units (a character outside the first plane counts 2, a lone surrogate 1)."""
    return len(text.encode("utf-16-le", "surrogatepass")) // 2


def trim_white(text):
    i, j = 0, len(text)
    while i < j and ord(text[i]) in WHITE:
        i += 1
    while j > i and ord(text[j - 1]) in WHITE:
        j -= 1
    return text[i:j]


def format_coords(lat, lon):
    """How the text is shown back to the driver: `lat, lon` to 6 decimals."""
    return "%.6f, %.6f" % (lat, lon)


def _is_map_link(t):
    low = t.translate(_ASCII_LOWER)
    if "://" in low or low.startswith("www."):
        return True
    i = low.find("google.")
    return i >= 0 and low.find("/maps", i + len("google.")) >= 0


def _link_place(t):
    low = t.translate(_ASCII_LOWER)
    if "goo.gl/" in low:
        raise Refused("a short link needs the internet to open: open it in Google Maps and copy the numbers under the place's name instead")
    for pattern in _LINK_PATTERNS:
        m = pattern.search(t)
        if m:
            return float(m.group(1)), float(m.group(2))
    raise Refused("I could not find a place in this link")


def _geo_place(t):
    rest = t[4:]
    cut = [i for i in (rest.find("?"), rest.find(";")) if i >= 0]
    if cut:
        rest = rest[:min(cut)]
    m = _GEO_BODY.fullmatch(rest)
    if not m:
        raise Refused("a geo: link needs a latitude and a longitude, and an optional altitude, as plain numbers separated by commas")
    return float(m.group(1)), float(m.group(2))


def _plain_tokens(t):
    """11.8 rule 3: the tokens of a plain text, ('n', value, carries_a_sign) and ('l', 'N'|'S'|'E'|'W'); marks are skipped."""
    tokens = []
    i, n = 0, len(t)
    while i < n:
        ch = t[i]
        if ord(ch) in WHITE or ch == "," or ch == ";":
            i += 1
            continue
        m = _PLAIN_NUMBER.match(t, i)
        if m:
            j = m.end()
            if j < n and t[j] in ".+-\u2212":
                raise Refused("a number runs straight into the next one (%r): that is a typing mistake" % t[max(0, i):j + 1])
            value = float(m.group(2))
            if m.group(1) and m.group(1) in MINUS_SIGNS:
                value = -value
            tokens.append(("n", value, m.group(1) != "", m.group(2)))
            i = j
            continue
        if ch in LETTERS:
            tokens.append(("l", ch.upper()))
            i += 1
            continue
        if ch in MARKS:
            i += 1
            continue
        raise Refused("I do not understand the character %r" % ch)
    return tokens


def _is_whole(value):
    """11.8: whole numbers are judged by value (`3.0` is whole)."""
    return math.isfinite(value) and value == math.floor(value)


def _degrees(group):
    """One coordinate of the lettered form: 1, 2 or 3 numbers (value, carries a sign, text), degrees [minutes [seconds]], unsigned, to a value in
    degrees."""
    if not 1 <= len(group) <= 3:
        raise Refused("a coordinate is degrees, or degrees and minutes, or degrees minutes and seconds; I found %d numbers" % len(group))
    if any(signed for _, signed, _ in group):
        raise Refused("a number next to a letter cannot carry its own sign")
    values = [v for v, _, _ in group]
    if len(values) >= 2 and not _is_whole(values[0]):
        raise Refused("degrees must be a whole number when minutes follow")
    if len(values) == 3 and not _is_whole(values[1]):
        raise Refused("minutes must be a whole number when seconds follow")
    if len(values) >= 2 and not values[1] < 60:
        raise Refused("minutes must be under 60")
    if len(values) == 3 and not values[2] < 60:
        raise Refused("seconds must be under 60")
    total = values[0]
    if len(values) >= 2:
        total = total + values[1] / 60.0
    if len(values) == 3:
        total = total + values[2] / 3600.0
    return total


def _plain_place(t):
    """11.8 rule 3. Returns (lat, lon, is_plain_pair)."""
    tokens = _plain_tokens(t)
    letters = [tk for tk in tokens if tk[0] == "l"]
    numbers = [tk for tk in tokens if tk[0] == "n"]
    if letters:
        if len(letters) != 2:
            raise Refused("I found %d letters; a place with letters has exactly two, one of N S and one of E W" % len(letters))
        if sorted(("NS".find(l[1]) >= 0) for l in letters) != [False, True]:
            raise Refused("I need one of N S and one of E W")
        groups = []
        if tokens[0][0] == "l":                              # each letter starts a coordinate
            for tk in tokens:
                if tk[0] == "l":
                    groups.append((tk[1], []))
                else:
                    groups[-1][1].append((tk[1], tk[2], tk[3]))
        else:                                                # each letter ends one
            current = []
            for tk in tokens:
                if tk[0] == "n":
                    current.append((tk[1], tk[2], tk[3]))
                else:
                    groups.append((tk[1], current))
                    current = []
            if current:
                raise Refused("there are numbers after the last letter")
        lat = lon = None
        for letter, group in groups:
            value = _degrees(group)
            if letter in "SW":
                value = -value
            if letter in "NS":
                lat = value
            else:
                lon = value
        return lat, lon, False
    if len(numbers) != 2:
        raise Refused("I found %d numbers; a place is a latitude and a longitude" % len(numbers))
    return numbers[0][1], numbers[1][1], True


def parse_coords(text):
    """Section 11.8: the (lat, lon) in degrees of a text the driver typed or pasted, or raises Refused."""
    if not isinstance(text, str):
        raise Refused("this is not text")
    t = trim_white(text)
    if t == "":
        raise Refused("there is no text")
    if utf16_length(t) > MAX_TEXT_UNITS:
        raise Refused("the text is longer than %d characters" % MAX_TEXT_UNITS)
    plain_pair = False
    if _is_map_link(t):
        lat, lon = _link_place(t)
    elif _GEO_START.match(t):
        lat, lon = _geo_place(t)
    else:
        lat, lon, plain_pair = _plain_place(t)
    if not abs(lat) <= 90 or not abs(lon) <= 180:
        if plain_pair and not abs(lat) <= 90 and abs(lon) <= 90:
            raise Refused("the latitude must be between -90 and 90: did you swap them?")
        raise Refused("a latitude is between -90 and 90 and a longitude between -180 and 180")
    if lat == 0 and lon == 0:
        raise Refused("0, 0 is what a failed look-up returns, not a place")
    return lat, lon


# =====================================================================================================================================
# 11.1  The track of a recording (Track) and 11.2  Snapping a coordinate to it (Track.passes)
# =====================================================================================================================================

class Track:
    """11.1: the polyline of a recording. `Track(fixes)` takes the usable fixes of a recording (they are sorted and de-duplicated here, which
    does nothing to fixes that already are) and raises Refused('no_drive') when there are fewer than 3 vertices.
    Attributes: t, lat, lon (of each vertex), x, y (in the track frame), s (distance along the track), length, geo (the track frame, origin at
    vertex 0); n_vertices (also len(track)); holes: [(t0, t1, metres)] of the pairs of consecutive usable fixes (kept or not) more than 3.0 s
    apart, `metres` the straight distance between those two fixes in the track frame."""

    def __init__(self, fixes):
        usable = [f for f in fixes if sc.is_usable(f)]
        everything = sc.clean_fixes(usable)                       # all the usable fixes, sorted and de-duplicated (section 1)
        kept = [f for f in everything
                if not f.hacc > TRACK_MAX_HACC and not (0.0 <= f.speed < TRACK_MIN_SPEED)]
        self.t, self.lat, self.lon, self.x, self.y = [], [], [], [], []
        self.geo = None
        for f in kept:
            if self.geo is None:
                self.geo = sc.Geo(f.lat, f.lon)
                x, y = self.geo.xy(f.lat, f.lon)
            else:
                x, y = self.geo.xy(f.lat, f.lon)
                if math.hypot(x - self.x[-1], y - self.y[-1]) < TRACK_MIN_VERTEX_GAP:
                    continue
            self.t.append(f.t)
            self.lat.append(f.lat)
            self.lon.append(f.lon)
            self.x.append(x)
            self.y.append(y)
        if len(self.x) < TRACK_MIN_VERTICES:
            raise Refused("there is no drive in this recording", "no_drive")
        self.s = [0.0]
        for i in range(1, len(self.x)):
            self.s.append(self.s[-1] + math.hypot(self.x[i] - self.x[i - 1], self.y[i] - self.y[i - 1]))
        self.length = self.s[-1]
        # 11.1 step 5: the holes are found on all the usable fixes, whether kept in step 1 or not, and not on the vertices
        self.holes = []
        for before, after in zip(everything, everything[1:]):
            if after.t - before.t > HOLE_SECONDS:
                x0, y0 = self.geo.xy(before.lat, before.lon)
                x1, y1 = self.geo.xy(after.lat, after.lon)
                self.holes.append((before.t, after.t, math.hypot(x1 - x0, y1 - y0)))

    @property
    def n_vertices(self):
        return len(self.x)

    def __len__(self):
        return len(self.x)

    def _segment(self, d):
        """Section 5 segmentAt(d): 0 if d <= 0; n-2 if d >= length; otherwise the largest j with s[j] <= d."""
        if d <= 0:
            return 0
        if d >= self.length:
            return len(self.s) - 2
        return bisect.bisect_right(self.s, d) - 1

    def point_at(self, d):
        """pointAt(d): the point (track frame) at distance d along the polyline, d clamped into [0, length]."""
        d = min(self.length, max(0.0, d))
        j = self._segment(d)
        f = (d - self.s[j]) / (self.s[j + 1] - self.s[j])
        return (self.x[j] + f * (self.x[j + 1] - self.x[j]), self.y[j] + f * (self.y[j + 1] - self.y[j]))

    def latlon_at(self, d):
        """The latitude and longitude of pointAt(d): the track frame's inverse."""
        return self.geo.latlon(*self.point_at(d))

    def time_at(self, d):
        """11.1 step 6, timeAt(d): the time of the point at distance d (clamped into [0, length]), interpolated on its segment."""
        d = min(self.length, max(0.0, d))
        j = self._segment(d)
        f = (d - self.s[j]) / (self.s[j + 1] - self.s[j])
        return self.t[j] + f * (self.t[j + 1] - self.t[j])

    def segment_fits(self, lat, lon):
        """For a place: [(d_j, position) for every segment j], d_j the distance of the place from segment j (clamped projection, no heading,
        as section 5) and position = s[j] + f * length(j) of the nearest point."""
        px, py = self.geo.xy(lat, lon)
        fits = []
        for j in range(len(self.x) - 1):
            ax, ay = self.x[j], self.y[j]
            dx, dy = self.x[j + 1] - ax, self.y[j + 1] - ay
            len2 = dx * dx + dy * dy
            f = 0.0
            if len2 > 0:
                f = min(1.0, max(0.0, ((px - ax) * dx + (py - ay) * dy) / len2))
            d = math.hypot(px - (ax + f * dx), py - (ay + f * dy))
            fits.append((d, self.s[j] + f * math.hypot(dx, dy)))
        return fits

    def closest_approach(self, lat, lon):
        """min d_j: the distance of the place from the track, in metres."""
        return min(d for d, _ in self.segment_fits(lat, lon))

    def all_passes(self, lat, lon):
        """11.2 before the last paragraph: every pass within the snap limit, [(position, offset)] in order of position."""
        passes = []
        run = None
        for d, pos in self.segment_fits(lat, lon):
            if d <= SNAP_LIMIT:
                if run is None:
                    run = (pos, d)
                elif d < run[1]:                                    # the first one on a tie stays
                    run = (pos, d)
            elif run is not None:
                passes.append(run)
                run = None
        if run is not None:
            passes.append(run)
        return passes

    def passes(self, lat, lon):
        """11.2: [(position, offset), ...] in order of position: the passes of the place within 60 m of the track, without those whose offset is
        more than 10 m above the smallest offset. Empty if the place is more than 60 m from the track."""
        passes = self.all_passes(lat, lon)
        if not passes:
            return []
        nearest = min(offset for _, offset in passes)
        return [(pos, offset) for pos, offset in passes if offset <= nearest + PASS_SPREAD]


# =====================================================================================================================================
# 11.3 to 11.6  The two positions, the lines, the stage, the guard
# =====================================================================================================================================

def _no_pass(track, lat, lon, which):
    return Refused("the %s place is %.0f m from the nearest point of the drive; a place must be within %.0f m of it"
                   % (which, track.closest_approach(lat, lon), SNAP_LIMIT), "no_pass_" + which)


def _candidates(track, pin, which):
    """The candidates (position, offset) of one pin. Raises Refused: `near_end` for a position that is not a finite number (NaN, +-infinity),
    `no_pass_<which>` for a coordinate with no pass (11.3, the order of the checks)."""
    if pin[0] == "pos":
        try:
            value = float(pin[1])
        except (TypeError, ValueError):
            value = float("nan")
        if not math.isfinite(value):
            raise Refused(_RULE_WORDS["near_end"], "near_end")
        return [(value, 0.0)]
    if pin[0] == "at":
        found = track.passes(float(pin[1]), float(pin[2]))
        if not found:
            raise _no_pass(track, float(pin[1]), float(pin[2]), which)
        return found
    raise ValueError("a pin is ('pos', metres) or ('at', lat, lon), not %r" % (pin,))


_RULE_WORDS = {
    "wrong_way": "the finish comes before the start on this drive: it was driven the other way round",
    "near_end": "the recording must show the car arriving at the start line and leaving the finish line: there must be at least %.0f m of "
                "track before the start and %.0f m after the finish" % (PIN_MARGIN, PIN_MARGIN),
    "too_short": "the stage would be shorter than %.0f m" % MIN_STAGE_LENGTH,
    "loop": "the finish is less than %.0f m from the start in a straight line: a stage runs from one place to another, not round a loop"
            % MIN_STRAIGHT,
    "hole_start": "the GPS gave no position for %.0f s around the start pin, so the phone could not see the car cross a line there",
    "hole_finish": "the GPS gave no position for %.0f s around the finish pin, so the phone could not see the car cross a line there",
    "break": "between the two pins the recording has a break of %.0f s, with the car %.0f m apart across it: the GPS was lost, or this is two "
             "drives. A stage has to be one continuous drive.",
}


def _failed_rule(track, a, b):
    """Rules 2 to 7 of 11.3 for a pair with b > a: None if all hold, else (category, sentence) of the first that does not."""
    if not (a >= PIN_MARGIN and b <= track.length - PIN_MARGIN):                                          # rule 2
        return "near_end", _RULE_WORDS["near_end"]
    if not b - a >= MIN_STAGE_LENGTH:                                                                     # rule 3
        return "too_short", _RULE_WORDS["too_short"]
    pa, pb = track.point_at(a), track.point_at(b)
    if not math.hypot(pb[0] - pa[0], pb[1] - pa[1]) >= MIN_STRAIGHT:                                      # rule 4
        return "loop", _RULE_WORDS["loop"]
    ta, tb = track.time_at(a), track.time_at(b)
    around_a = [t1 - t0 for t0, t1, _ in track.holes if t0 <= ta <= t1]                                   # rule 5, the ends count
    if around_a:
        return "hole_start", _RULE_WORDS["hole_start"] % max(around_a)
    around_b = [t1 - t0 for t0, t1, _ in track.holes if t0 <= tb <= t1]                                   # rule 6
    if around_b:
        return "hole_finish", _RULE_WORDS["hole_finish"] % max(around_b)
    breaks = [(t1 - t0, metres) for t0, t1, metres in track.holes
              if t1 - t0 > BREAK_SECONDS and metres > BREAK_METRES and t1 > ta and t0 < tb]               # rule 7
    if breaks:
        seconds, metres = max(breaks)
        return "break", _RULE_WORDS["break"] % (seconds, metres)
    return None


def resolve_pins(track, start_pin, finish_pin):
    """11.3: the start and finish positions (a, b) and the passes they came from. Returns (a, b, offset_a, offset_b, passes_a, passes_b); raises
    Refused with the category of the page. The order of the checks is the page's: the recording (the Track), the start pin (a position that is not
    finite: near_end; a coordinate with no pass: no_pass_start), the finish pin in the same way, then the pairs."""
    cand_a = _candidates(track, start_pin, "start")
    cand_b = _candidates(track, finish_pin, "finish")
    first_failure = None
    for a, offset_a in cand_a:
        for b, offset_b in cand_b:
            if not b > a:                                                                                 # rule 1
                continue
            failed = _failed_rule(track, a, b)
            if failed is None:
                return a, b, offset_a, offset_b, cand_a, cand_b
            if first_failure is None:
                first_failure = failed
    if first_failure is None:
        raise Refused(_RULE_WORDS["wrong_way"], "wrong_way")
    raise Refused(first_failure[1], first_failure[0])


def _make_stage(track, a, b, start_mark, finish_mark, stage_id, name, created_utc_ms):
    """11.4 and 11.5: the stage (not yet rounded) for the positions a < b. `start_mark` / `finish_mark` are the coordinates the driver gave,
    or None for a pin given by position (then the latitude and longitude of the line's centre)."""
    pa, pb = track.point_at(a), track.point_at(b)
    lat_a, lon_a = track.geo.latlon(*pa)
    lat_b, lon_b = track.geo.latlon(*pb)
    # 11.4: the direction of the track over the 40 m around each pin (the distances never need clamping because of rule 2)
    ahead, behind = track.point_at(a + DIRECTION_HALF_SPAN), track.point_at(a - DIRECTION_HALF_SPAN)
    bearing_a = sc.bearing(ahead[0] - behind[0], ahead[1] - behind[1])
    ahead, behind = track.point_at(b + DIRECTION_HALF_SPAN), track.point_at(b - DIRECTION_HALF_SPAN)
    bearing_b = sc.bearing(ahead[0] - behind[0], ahead[1] - behind[1])
    # 11.5
    start_mark = (start_mark[0], start_mark[1]) if start_mark is not None else (lat_a, lon_a)
    finish_mark = (finish_mark[0], finish_mark[1]) if finish_mark is not None else (lat_b, lon_b)
    geo = sc.Geo(start_mark[0], start_mark[1])                        # the stage frame: origin at the start mark, unrounded
    start_centre, finish_centre = geo.xy(lat_a, lon_a), geo.xy(lat_b, lon_b)
    route = [start_centre]
    for i in range(len(track.x)):
        if a + ROUTE_MARGIN < track.s[i] < b - ROUTE_MARGIN:
            route.append(geo.xy(track.lat[i], track.lon[i]))        # a vertex is placed from its fix's latitude and longitude
    route.append(finish_centre)
    start_gate = sc.Gate(start_centre[0], start_centre[1], bearing_a, GATE_HALF)
    finish_gate = sc.Gate(finish_centre[0], finish_centre[1], bearing_b, GATE_HALF)
    return sc.Stage(stage_id, name, int(created_utc_ms), start_mark, start_mark, finish_mark, start_gate, finish_gate, route)


def forward_crossings(gate, points, distances, width=GUARD_WIDTH):
    """11.6: the route distances u of the forward crossings of `gate` by the polyline `points` (distances `distances`): segments with
    along(q[k]) < 0 <= along(q[k+1]) whose crossing point, found by linear interpolation, is at most `width` from the line's centre."""
    found = []
    for k in range(len(points) - 1):
        q0 = gate.along(points[k][0], points[k][1])
        q1 = gate.along(points[k + 1][0], points[k + 1][1])
        if q0 < 0 <= q1:
            f = (0.0 - q0) / (q1 - q0)
            x = points[k][0] + f * (points[k + 1][0] - points[k][0])
            y = points[k][1] + f * (points[k + 1][1] - points[k][1])
            if abs(gate.across(x, y)) <= width:
                found.append(distances[k] + f * (distances[k + 1] - distances[k]))
    return found


def check_guard(stage):
    """11.6: raises Refused('guard_start' or 'guard_finish') if the finished stage would be mistimed. The start gate is checked first."""
    points, distances, total = stage.route_points, stage.route.s, stage.route.length
    for u in forward_crossings(stage.start_gate, points, distances):
        if GUARD_START_MIN < u <= GUARD_START_MAX:
            raise Refused("the road crosses the start line again %.0f m into the stage (a loop or tight bends close to it). A run would be "
                          "timed wrongly. Move that pin to a straighter place." % u, "guard_start")
    for u in forward_crossings(stage.finish_gate, points, distances):
        if GUARD_FINISH_FRACTION * total <= u < total - GUARD_FINISH_END:
            raise Refused("the road crosses the finish line again %.0f m into the stage (a loop or tight bends close to it). A run would be "
                          "timed wrongly. Move that pin to a straighter place." % u, "guard_finish")


class PinResult:
    """What build_stage_ex() found: the stage as read back from its file (`stage`) and as built (`raw`, not yet rounded), the positions, the
    passes and the sentences of 11.7 (`notes`: not part of any file)."""

    def __init__(self, stage, raw, a, b, offset_a, offset_b, passes_a, passes_b, notes, gaps, track=None):
        self.stage, self.raw, self.a, self.b, self.track = stage, raw, a, b, track
        self.offset_a, self.offset_b = offset_a, offset_b
        self.passes_a, self.passes_b = passes_a, passes_b
        self.notes, self.gaps = notes, gaps


def stage_gaps(track, a, b):
    """11.7: the gaps: segments of the track between the pins (a segment is between two consecutive vertices; one that the pin cuts counts) with a
    time difference over 20 s and a length over 100 m. Returns [(distance into the stage where it begins, seconds, metres)]."""
    gaps = []
    for j in range(len(track.x) - 1):
        if track.s[j + 1] > a and track.s[j] < b:
            seconds = track.t[j + 1] - track.t[j]
            metres = track.s[j + 1] - track.s[j]
            if seconds > GAP_SECONDS and metres > GAP_METRES:
                gaps.append((max(track.s[j], a) - a, seconds, metres))
    return gaps


def build_stage_ex(track_or_fixes, start_pin, finish_pin, stage_id, name, created_utc_ms=0, guard_on_read_back=False):
    """11.1 to 11.7. Returns a PinResult, or raises Refused (category in CATEGORIES)."""
    track = track_or_fixes if isinstance(track_or_fixes, Track) else Track(track_or_fixes)
    a, b, offset_a, offset_b, passes_a, passes_b = resolve_pins(track, start_pin, finish_pin)
    raw = _make_stage(track, a, b, (start_pin[1], start_pin[2]) if start_pin[0] == "at" else None,
                      (finish_pin[1], finish_pin[2]) if finish_pin[0] == "at" else None, stage_id, name, created_utc_ms)
    stage = raw.read_back()
    check_guard(stage if guard_on_read_back else raw)
    notes = []
    for label, pin, offset, passes, used in (("start", start_pin, offset_a, passes_a, a), ("finish", finish_pin, offset_b, passes_b, b)):
        if pin[0] == "at":
            if offset > OFFSET_REPORT_M:
                notes.append("the %s place is %.0f m from the road the car drove; the line is put on the road, at the nearest point" % (label, offset))
            if len(passes) > 1:
                notes.append("the %s place is passed %d times in this recording; pass %d, at %.0f m, was used"
                             % (label, len(passes), [p for p, _ in passes].index(used) + 1, used))
    gaps = stage_gaps(track, a, b)
    for position, seconds, metres in gaps:
        notes.append("GPS was lost for %.0f s over %.0f m, %.0f m into the stage: the road is drawn as a straight line there" % (seconds, metres, position))
    return PinResult(stage, raw, a, b, offset_a, offset_b, passes_a, passes_b, notes, gaps, track)


def build_stage(track_or_fixes, start_pin, finish_pin, stage_id, name, created_utc_ms=0):
    """11.1 to 11.6: the stage as read back from its file (the app scores against that), or raises Refused with a category."""
    return build_stage_ex(track_or_fixes, start_pin, finish_pin, stage_id, name, created_utc_ms).stage


def build_stage_raw(track_or_fixes, start_pin, finish_pin, stage_id, name, created_utc_ms=0):
    """The same stage before its numbers are rounded for the file (what the fixture comparison judges, as stage_compare's check_build does)."""
    return build_stage_ex(track_or_fixes, start_pin, finish_pin, stage_id, name, created_utc_ms).raw


def parse_pin(text):
    """`pos:METRES` or `at:LAT,LON` (the forms of the fixture files and of the command line) to a pin."""
    if text.startswith("pos:"):
        return ("pos", float(text[4:]))
    if text.startswith("at:"):
        parts = text[3:].split(",")
        if len(parts) != 2:
            raise ValueError("a place is at:LAT,LON, not %r" % text)
        return ("at", float(parts[0]), float(parts[1]))
    raise ValueError("a pin is pos:METRES or at:LAT,LON, not %r" % text)


# =====================================================================================================================================
# The fixture files of analysis/stage_fixtures/pins/ (see the README there)
# =====================================================================================================================================

FIXTURE_CREATED_UTC_MS = 1791885600000
MAX_SHOWN = 8


def unescape_text(text):
    """The text columns write every UTF-16 code unit outside printable ASCII, and the backslash, as \\uXXXX (lower-case hex)."""
    units, i = [], 0
    while i < len(text):
        if text[i] == "\\" and text[i + 1:i + 2] == "u" and re.fullmatch("[0-9A-Fa-f]{4}", text[i + 2:i + 6] or "x"):
            units.append(int(text[i + 2:i + 6], 16))
            i += 6
        else:
            raw = text[i].encode("utf-16-le", "surrogatepass")
            units.extend(raw[k] | (raw[k + 1] << 8) for k in range(0, len(raw), 2))
            i += 1
    return b"".join(u.to_bytes(2, "little") for u in units).decode("utf-16-le", "surrogatepass")


def escape_text(text):
    """The inverse, for showing a text in a report."""
    out = []
    raw = text.encode("utf-16-le", "surrogatepass")
    for k in range(0, len(raw), 2):
        unit = raw[k] | (raw[k + 1] << 8)
        out.append(chr(unit) if 0x20 <= unit <= 0x7E and unit != 0x5C else "\\u%04x" % unit)
    return "".join(out)


def data_lines(path):
    """The lines of a tab separated fixture file that are not comments or empty, without their line end (a text column may begin or end with
    spaces, so nothing else is stripped)."""
    with open(path, "r", encoding="utf-8", newline="") as fh:
        text = fh.read()
    lines = []
    for ln in text.replace("\r\n", "\n").split("\n"):
        if ln == "" or ln.startswith("#"):
            continue
        lines.append(ln)
    return lines


def decimals_of(word):
    return sc.decimals_of(word)


def agrees(mine, printed):
    """The README's rule: within half a unit of the last printed decimal (plus 1e-9) of the printed number."""
    expected = sc.parse_float(printed)
    if expected is None:
        return False
    return abs(mine - expected) <= 0.5 * 10.0 ** (-decimals_of(printed)) + sc.ABS_SLACK


class Recordings:
    """The recordings the fixture files name (relative to analysis/stage_fixtures/, or REAL), read once. A Track or a Refused is cached too."""

    def __init__(self, fixtures, real_log):
        self.fixtures, self.real_log = fixtures, real_log
        self.sessions, self.tracks = {}, {}

    def path(self, name):
        return self.real_log if name == "REAL" else os.path.join(self.fixtures, name)

    def available(self, name):
        return os.path.isfile(self.path(name))

    def session(self, name):
        if name not in self.sessions:
            self.sessions[name] = sc.read_session(self.path(name))
        return self.sessions[name]

    def track(self, name):
        """(Track, None) or (None, Refused)."""
        if name not in self.tracks:
            try:
                self.tracks[name] = (Track(self.session(name).fixes), None)
            except Refused as err:
                self.tracks[name] = (None, err)
        return self.tracks[name]


def _group(label):
    return sc.Group(label)


def _skip(group, why):
    group.skipped = True
    group.detail = why
    return group


def check_coords_tsv(pins_dir):
    g = _group("pins/coords.tsv: places read from text (11.8)")
    path = os.path.join(pins_dir, "coords.tsv")
    if not os.path.isfile(path):
        return _skip(g, "coords.tsv not found yet")
    n = n_ok = 0
    for ln in data_lines(path):
        fields = ln.split("\t")
        if len(fields) != 2:
            g.diffs.append("a line with %d columns, expected 2: %r" % (len(fields), ln[:60]))
            continue
        text, want = unescape_text(fields[0]), fields[1]
        n += 1
        try:
            lat, lon = parse_coords(text)
            got = "ok"
        except Refused:
            got = "refused"
        except Exception as err:                                  # anything else is a bug of this script
            g.diffs.append("%s: raised %s: %s" % (fields[0], type(err).__name__, err))
            continue
        if want == "refused":
            if got != "refused":
                g.diffs.append("%s: read as %s, %s; expected refused" % (fields[0], format_coords(lat, lon), "ok"))
        elif want.startswith("ok "):
            words = want.split(" ")
            if got != "ok":
                g.diffs.append("%s: refused; expected %s" % (fields[0], want))
            elif len(words) != 3 or not (agrees(lat, words[1]) and agrees(lon, words[2])):
                g.diffs.append("%s: %.9f %.9f; expected %s" % (fields[0], lat, lon, want))
            else:
                n_ok += 1
        else:
            g.diffs.append("%s: cannot read the expected result %r" % (fields[0], want))
    g.detail = "%d texts (%d read as places, %d refused), every one as the app reads it" % (n, n_ok, n - n_ok)
    return g


def check_tracks_tsv(pins_dir, recs):
    g = _group("pins/tracks.tsv: the track of each recording (11.1)")
    path = os.path.join(pins_dir, "tracks.tsv")
    if not os.path.isfile(path):
        return _skip(g, "tracks.tsv not found yet")
    n = 0
    for ln in data_lines(path):
        fields = ln.split("\t")
        name = fields[0]
        if not recs.available(name):
            g.diffs.append("%s: the recording is not there" % name) if name != "REAL" else None
            continue
        n += 1
        track, refused = recs.track(name)
        if fields[1:2] == ["no_drive"]:
            if track is not None:
                g.diffs.append("%s: a track of %d vertices, %.3f m; expected no_drive" % (name, track.n_vertices, track.length))
        elif len(fields) != 3:
            g.diffs.append("%s: cannot read the expected result %r" % (name, fields[1:]))
        elif track is None:
            g.diffs.append("%s: refused (%s); expected %s vertices, %s m" % (name, refused.category, fields[1], fields[2]))
        else:
            if str(track.n_vertices) != fields[1]:
                g.diffs.append("%s: %d vertices, expected %s" % (name, track.n_vertices, fields[1]))
            if not agrees(track.length, fields[2]):
                g.diffs.append("%s: length %.6f m, expected %s" % (name, track.length, fields[2]))
    g.detail = "%d recordings: vertices and length" % n
    return g


def format_passes(passes):
    return ";".join("%.3f:%.3f" % p for p in passes) if passes else "none"


def check_passes_tsv(pins_dir, recs):
    g = _group("pins/passes.tsv: places snapped to a track (11.2)")
    path = os.path.join(pins_dir, "passes.tsv")
    if not os.path.isfile(path):
        return _skip(g, "passes.tsv not found yet")
    n = 0
    for ln in data_lines(path):
        fields = ln.split("\t")
        if len(fields) != 4:
            g.diffs.append("a line with %d columns, expected 4: %r" % (len(fields), ln[:60]))
            continue
        name, lat, lon, want = fields[0], float(fields[1]), float(fields[2]), fields[3]
        if not recs.available(name):
            g.diffs.append("%s: the recording is not there" % name) if name != "REAL" else None
            continue
        n += 1
        track, refused = recs.track(name)
        label = "%s %s %s" % (name, fields[1], fields[2])
        if track is None:
            g.diffs.append("%s: no track (%s); expected %s" % (label, refused.category, want))
            continue
        got = track.passes(lat, lon)
        if want == "none":
            if got:
                g.diffs.append("%s: passes %s; expected none" % (label, format_passes(got)))
            continue
        expected = [tuple(p.split(":")) for p in want.split(";")]
        if len(expected) != len(got):
            g.diffs.append("%s: %d passes %s; expected %d: %s" % (label, len(got), format_passes(got), len(expected), want))
            continue
        for (pos, offset), (ep, eo) in zip(got, expected):
            if not (agrees(pos, ep) and agrees(offset, eo)):
                g.diffs.append("%s: %s; expected %s" % (label, format_passes(got), want))
                break
    g.detail = "%d places snapped: positions and offsets" % n
    return g


def check_cases_tsv(pins_dir, recs):
    g_cat = _group("pins/cases.tsv: the result of each pair of pins (11.3 to 11.6)")
    g_stage = _group("pins/stages: the stage file of each ok case (11.4, 11.5)")
    g_run = _group("pins/runs: the first run of the source recording on that stage (6)")
    path = os.path.join(pins_dir, "cases.tsv")
    if not os.path.isfile(path):
        for g in (g_cat, g_stage, g_run):
            _skip(g, "cases.tsv not found yet")
        return [g_cat, g_stage, g_run]
    n = n_ok = n_stage = n_run = 0
    for ln in data_lines(path):
        fields = ln.split("\t")
        if len(fields) != 5:
            g_cat.diffs.append("a line with %d columns, expected 5: %r" % (len(fields), ln[:60]))
            continue
        case, name, start_text, finish_text, want = fields
        if not recs.available(name):
            g_cat.diffs.append("%s: the recording %s is not there" % (case, name)) if name != "REAL" else None
            continue
        n += 1
        track, refused = recs.track(name)
        try:
            start_pin, finish_pin = parse_pin(start_text), parse_pin(finish_text)
        except ValueError as err:
            g_cat.diffs.append("%s: %s" % (case, err))
            continue
        result = None
        if track is None:
            got = refused.category
        else:
            try:
                result = build_stage_ex(track, start_pin, finish_pin, "pin_" + case, "Pin case " + case, FIXTURE_CREATED_UTC_MS)
                got = "ok"
            except Refused as err:
                got = err.category
            except Exception as err:
                g_cat.diffs.append("%s: raised %s: %s" % (case, type(err).__name__, err))
                continue
        if got != want:
            g_cat.diffs.append("%s: %s, expected %s" % (case, got, want))
        if got != "ok" or want != "ok":
            continue
        n_ok += 1
        stage_path = os.path.join(pins_dir, "stages", case + ".txt")
        if not os.path.isfile(stage_path):
            g_stage.diffs.append("%s: the expected stage file stages/%s.txt is not there" % (case, case))
        else:
            n_stage += 1
            g_stage.diffs += sc.diff_lines(result.raw.tokens(), sc._read_text(stage_path), "stages/%s.txt" % case)
        run_path = os.path.join(pins_dir, "runs", case + ".txt")
        ses = recs.session(name)
        runs = sc.score_recording(result.stage, ses.fixes, ses.name, ses.start_utc_ms)
        if runs and os.path.isfile(run_path):
            n_run += 1
            g_run.diffs += sc.diff_lines(runs[0].tokens(), sc._read_text(run_path), "runs/%s.txt" % case)
        elif runs:
            g_run.diffs.append("%s: a run was found (%s) but runs/%s.txt is not there" % (case, runs[0].run_id, case))
        elif os.path.isfile(run_path):
            g_run.diffs.append("%s: no run was found, but runs/%s.txt is there" % (case, case))
    g_cat.detail = "%d cases (%d ok), every result as the app's" % (n, n_ok)
    g_stage.detail = "%d stage files" % n_stage
    g_run.detail = "%d run files" % n_run
    return [g_cat, g_stage, g_run]


def fixture_groups(fixtures, real_log):
    pins_dir = os.path.join(fixtures, "pins")
    names = ("coords.tsv", "tracks.tsv", "passes.tsv", "cases.tsv")
    if not os.path.isdir(pins_dir) or not any(os.path.isfile(os.path.join(pins_dir, n)) for n in names):
        g = _group("pins fixtures")
        _skip(g, "fixtures not found yet (%s has no coords.tsv, tracks.tsv, passes.tsv, cases.tsv)" % pins_dir)
        return [g], False
    recs = Recordings(fixtures, real_log)
    groups = [check_coords_tsv(pins_dir), check_tracks_tsv(pins_dir, recs), check_passes_tsv(pins_dir, recs)]
    groups += check_cases_tsv(pins_dir, recs)
    return groups, True


# =====================================================================================================================================
# This script's own checks: synthetic roads (straights and arcs), every refusal, the examples of 11.8, a fuzz of the text reader
# =====================================================================================================================================

class Road:
    """A road made of straights and arcs in a local metric frame: parts are ('line', length) or ('arc', radius, degrees) with degrees > 0 for a
    turn to the right (clockwise). at(d) gives (x, y, heading in degrees clockwise from north) exactly."""

    def __init__(self, parts, heading=0.0, x=0.0, y=0.0):
        self.parts = []
        d = 0.0
        h = math.radians(heading)
        for part in parts:
            if part[0] == "line":
                length = float(part[1])
                self.parts.append(("line", d, length, x, y, h, 0.0, 0.0))
                x += length * math.sin(h)
                y += length * math.cos(h)
            else:
                radius, sweep = float(part[1]), math.radians(part[2])
                length = radius * abs(sweep)
                self.parts.append(("arc", d, length, x, y, h, radius, sweep))
                x, y, h = self._arc(x, y, h, radius, sweep, length)[0:3]
            d += length
        self.length = d
        self.starts = [p[1] for p in self.parts]

    @staticmethod
    def _arc(x, y, h, radius, sweep, travelled):
        phi = travelled / radius
        if sweep >= 0:                                               # to the right
            cx, cy = x + radius * math.cos(h), y - radius * math.sin(h)
            return cx - radius * math.cos(h + phi), cy + radius * math.sin(h + phi), h + phi
        cx, cy = x - radius * math.cos(h), y + radius * math.sin(h)  # to the left
        return cx + radius * math.cos(h - phi), cy - radius * math.sin(h - phi), h - phi

    def at(self, d):
        d = min(self.length, max(0.0, d))
        k = max(0, bisect.bisect_right(self.starts, d) - 1)
        kind, d0, length, x, y, h, radius, sweep = self.parts[k]
        if kind == "line":
            along = d - d0
            return x + along * math.sin(h), y + along * math.cos(h), math.degrees(h) % 360.0
        px, py, hh = self._arc(x, y, h, radius, sweep, d - d0)
        return px, py, math.degrees(hh) % 360.0


def road_fixes(road, schedule, origin=(3.5, 101.6), noise=0.0, rng=None, hacc=3.5, with_speed=True, with_bearing=True):
    """Fixes of a car on `road`: schedule is a list of (t, distance along the road, speed). Positions are exact unless `noise` (metres,
    Gaussian in x and y) is given."""
    geo = sc.Geo(origin[0], origin[1])
    fixes = []
    for t, d, v in schedule:
        x, y, h = road.at(d)
        if noise:
            x += rng.gauss(0.0, noise)
            y += rng.gauss(0.0, noise)
        lat, lon = geo.latlon(x, y)
        fixes.append(sc.Fix(t, lat, lon, v if with_speed else -1.0, h if (with_bearing and v >= 0.5) else -1.0, hacc))
    return fixes, geo


def steady(v, d0, d1, t0=0.0, dt=1.0):
    """Schedule: constant speed v from distance d0 to d1 (inclusive of the last fix before d1), one fix every dt seconds."""
    out, i = [], 0
    while d0 + v * dt * i <= d1 + 1e-9:
        out.append((t0 + dt * i, d0 + v * dt * i, v))
        i += 1
    return out


def parked(d, t0, n, dt=1.0):
    return [(t0 + dt * i, d, 0.0) for i in range(n)]


class Checks:
    """A group of the own checks: every check is counted, a failure is a sentence."""

    def __init__(self, label):
        self.group = _group("own: " + label)
        self.count = 0

    def true(self, condition, message):
        self.count += 1
        if not condition:
            self.group.diffs.append(message)

    def eq(self, got, want, message):
        self.count += 1
        if got != want:
            self.group.diffs.append("%s: got %r, expected %r" % (message, got, want))

    def near(self, got, want, tolerance, message):
        self.count += 1
        if got is None or not abs(got - want) <= tolerance:
            self.group.diffs.append("%s: got %r, expected %r (+-%g)" % (message, got, want, tolerance))

    def refuses(self, function, category, message):
        self.count += 1
        try:
            result = function()
        except Refused as err:
            if err.category != category:
                self.group.diffs.append("%s: refused as %s (%s), expected %s" % (message, err.category, err.message, category))
            return
        except Exception as err:
            self.group.diffs.append("%s: raised %s: %s" % (message, type(err).__name__, err))
            return
        self.group.diffs.append("%s: not refused (%r), expected %s" % (message, result if not hasattr(result, "text") else "a stage", category))

    def done(self):
        self.group.detail = "%d checks" % self.count
        return self.group


def _coords_table():
    """(text, expected (lat, lon) or None for refused): the examples of 11.8 first, then the edges of its grammar."""
    s3d = 3 + 8 / 60 + 20.4 / 3600
    s101 = 101 + 41 / 60 + 12.7 / 3600
    nb = "\u00a0"
    return [
        # plain, without letters (the page's examples and the signs)
        ("3.1234 101.5678", (3.1234, 101.5678)), ("3.1234, 101.5678", (3.1234, 101.5678)), ("3.1234;101.5678", (3.1234, 101.5678)),
        ("  3.1234,101.5678  ", (3.1234, 101.5678)), ("-3.1234 -101.5678", (-3.1234, -101.5678)), ("+3.1234 +101.5678", (3.1234, 101.5678)),
        ("\u22123.5 101.5", (-3.5, 101.5)), ("3.5 \u2212101.5", (3.5, -101.5)), ("3 101", (3.0, 101.0)), ("3. 101.", (3.0, 101.0)),
        (".5 .25", (0.5, 0.25)), ("-.5 +.25", (-0.5, 0.25)), ("3.1234\u00b0 101.5678\u00b0", (3.1234, 101.5678)),
        ("3.1234\u00ba, 101.5678\u02da", (3.1234, 101.5678)), ("3,1234 101,5678", None), ("3,1234", None), ("3.1234", None),
        ("3.1234 101.5678 5", None), ("", None), ("   ", None), (",;,", None), ("\u00b0 \u00b0", None),
        ("90 180", (90.0, 180.0)), ("-90 -180", (-90.0, -180.0)), ("90.0000001 5", None), ("5 180.0000001", None), ("91 5", None),
        ("101.5678, 3.1234", None), ("0 0", None), ("0.0, -0.0", None), ("0, 0.000001", (0.0, 0.000001)),
        # letters
        ("N 3.1234 E 101.5678", (3.1234, 101.5678)), ("3.1234 N, 101.5678 E", (3.1234, 101.5678)), ("3.1234N 101.5678E", (3.1234, 101.5678)),
        ("n 3.1234 e 101.5678", (3.1234, 101.5678)), ("101.5678 E 3.1234 N", (3.1234, 101.5678)), ("E 101.5678 N 3.1234", (3.1234, 101.5678)),
        ("S 3.1234 W 101.5678", (-3.1234, -101.5678)), ("3.1234 s 101.5678 w", (-3.1234, -101.5678)),
        ("3\u00b008'20.4\"N 101\u00b041'12.7\"E", (s3d, s101)), ("3 8 20.4 N 101 41 12.7 E", (s3d, s101)),
        ("N 3\u00b0 8' 20.4\" E 101\u00b0 41' 12.7\"", (s3d, s101)), ("3\u00b008.34'N 101\u00b041.5'E", (3 + 8.34 / 60, 101 + 41.5 / 60)),
        ("3\u00b008\u203220.4\u2033N 101\u00b041\u203212.7\u2033E", (s3d, s101)), ("3\u00b008\u201920.4\u201dN 101\u00b041\u201912.7\u201dE", (s3d, s101)),
        ("N 3 E 101", (3.0, 101.0)), ("3 N 101 E", (3.0, 101.0)), ("3.0 8 N 101 E", (3 + 8 / 60, 101.0)),
        ("0 N 0 E", None), ("0 S 0 W", None), ("N 95 E 10", None), ("N 10 E 181", None), ("-3.1234 N 101 E", None), ("+3.1234 N 101 E", None),
        ("3 -8 N 101 E", None), ("3 60 N 101 E", None), ("3 59.99 N 101 E", (3 + 59.99 / 60, 101.0)), ("3 8 60 N 101 E", None),
        ("3.5 8 N 101 E", None), ("3 8.5 20 N 101 E", None), ("3 8 20 5 N 101 E", None), ("N 3 N 4", None), ("N 3 E 101 W 5", None),
        ("3 N", None), ("3 N 101 E 7", None), ("N E 3 101", None), ("3 N E 101", None), ("N 3 101 E", None), ("3 N 101", None),
        ("North 3 East 101", None), ("3 N 101 E x", None), ("3 N 101 E.", None),
        # tokens that run into each other, and characters that are not numbers
        ("3.1234.5 101", None), ("3.1234-101.5678", None), ("3.1234+101.5678", None), ("3.1234\u2212101.5678", None), ("3..5 101", None),
        ("3.1234 -101.5678", (3.1234, -101.5678)), ("3.1234,-101.5678", (3.1234, -101.5678)), ("5-", None), ("1e5 3", None),
        ("\u0663.\u0661\u0662\u0663\u0664 \u0661\u0660\u0661", None), ("\uff13.\uff11 101", None), ("3.1 101 \u200b", None), ("3.1234 \u2013101.5", None),
        # white space
        ("3.1234" + nb + "101.5678", (3.1234, 101.5678)), ("3.1234\u202f101.5678", (3.1234, 101.5678)), ("3.1234\u2007101.5678", (3.1234, 101.5678)),
        ("3.1234\u3000101.5678", (3.1234, 101.5678)), ("3.1234\u2028101.5678\u2029", (3.1234, 101.5678)), ("3.1234\x1c101.5678", (3.1234, 101.5678)),
        ("3.1234\x0b101.5678\x0c", (3.1234, 101.5678)), ("3.1234\t101.5678\r\n", (3.1234, 101.5678)), ("3.1234\u0085101.5678", None),
        ("3.1234\u200b101.5678", None), ("3.1234\ufeff101.5678", None), ("\u00a0 3.1234 101.5678 \u3000", (3.1234, 101.5678)),
        ("\u0085 3.1234 101.5678", None), ("\u180e3.1234 101.5678", None),
        # geo: links
        ("geo:3.1234,101.5678", (3.1234, 101.5678)), ("GEO:3.1234,101.5678", (3.1234, 101.5678)), ("Geo:3.1234,101.5678,12.5", (3.1234, 101.5678)),
        ("geo:3.1234,101.5678;u=35", (3.1234, 101.5678)), ("geo:3.1234,101.5678?z=15", (3.1234, 101.5678)),
        ("geo:3.1234,101.5678,12;crs=wgs84;u=3", (3.1234, 101.5678)), ("geo:-3,-101", (-3.0, -101.0)), ("  geo:3.1234,101.5678  ", (3.1234, 101.5678)),
        ("geo:0,0?q=3.1234,101.5678", None), ("geo:3.1234", None), ("geo:", None), ("geo:3.1234,101.5678,12,4", None),
        ("geo:3.1234,101.5678,abc", None), ("geo:+3.1234,101.5678", None), ("geo:3.1234, 101.5678", None), ("geo: 3.1234,101.5678", None),
        ("geo:3.1234,.5", None), ("geo:3.,5", None), ("geo:91,5", None), ("geo:3.1234;101.5678", None), ("geo:3.1234,101.5678,", None),
        # map links
        ("https://www.google.com/maps/@3.1234,101.5678,15z", (3.1234, 101.5678)), ("www.google.com/maps/@3.1234,101.5678,15z", (3.1234, 101.5678)),
        ("google.com/maps/@3.1234,101.5678,15z", (3.1234, 101.5678)), ("https://www.google.com/maps/place/X/@3.1,101.5,17z/data=!3d3.2!4d101.6",
                                                                          (3.2, 101.6)),
        ("https://maps.example.com/?q=3.1234,101.5678", (3.1234, 101.5678)), ("https://x.com/a?z=1&ll=3.1234%2C101.5678&b=2", (3.1234, 101.5678)),
        ("https://x.com/a?query=-3.1234,-101.5678", (-3.1234, -101.5678)), ("https://x.com/a?center=3,101", (3.0, 101.0)),
        ("https://www.google.com/maps/place/3.1234,101.5678", (3.1234, 101.5678)), ("https://x.com/#q=3.1,101.5", None),
        ("https://maps.app.goo.gl/AbC123", None), ("https://goo.gl/maps/AbC123", None), ("https://x.com/@3.1,101.5?goo.gl/", None),
        ("maps.google.com/?q=3.1234,101.5678", None), ("https://x.com/", None), ("https://x.com/?q=3.1234 101.5678", None),
        ("https://x.com/?q=3.1234%2c101.5678", (3.1234, 101.5678)), ("https://x.com/?Q=3.1234%2C101.5678", (3.1234, 101.5678)),
        ("https://x.com/@3.1234%2C101.5678", None),
        ("https://www.google.com/maps/@95.0,101.5,15z/data=!3d3.0!4d101.5", (3.0, 101.5)), ("https://x.com/data=!3d95.0!4d101.5/@3.0,101.5,15z", None),
        ("https://x.com/@0,0,15z", None), ("https://x.com/@3.1,101.5,15z/@4.1,102.5", (3.1, 101.5)), ("HTTPS://x.com/@3.1,101.5", (3.1, 101.5)),
        ("WWW.google.com/maps/@3.1,101.5,15z", (3.1, 101.5)), ("Google.com/Maps/@3.1,101.5", (3.1, 101.5)),
        ("HTTPS://GOO.GL/maps/abc", None), ("https://x.com/!3D3.1!4D101.5", (3.1, 101.5)), ("https://x.com/PLACE/3.1,101.5", (3.1, 101.5)),
        ("https://x.com/Place/3.1,101.5", (3.1, 101.5)), ("https://x.com/@\u0663.\u0661,101.5", None),
        ("https://x.com/@3.1,101.5,15z/place/9,9", (3.1, 101.5)), ("https://x.com/place/9,9/@3.1,101.5", (3.1, 101.5)),
        ("https://x.com/!3d3.1!4d101.5!3d4.1!4d102.5", (3.1, 101.5)), ("https://x.com/!3d3.1!4d", None), ("see https://x.com/@3.1,101.5 ok", (3.1, 101.5)),
        # only `goo.gl/` makes a short link (11.8): the old "maps.app.goo.gl" test is gone, and the test is ASCII-case-blind
        ("https://maps.app.goo.gl?q=3.1,101.5", (3.1, 101.5)), ("https://maps.app.goo.gl/?q=3.1,101.5", None), ("https://x.com/?q=3.1,101.5&u=GOO.GL/", None),
        ("https://x.com/?q=3.1,101.5&u=goo.gl", (3.1, 101.5)), ("https://goo.gl", None),
        # case is ASCII only: U+017F and U+212A fold to s and k in Unicode but are not letters here
        ("google.com/map\u017f/@3.1,101.5", None), ("3.1234 \u017f 101.5678 e", None), ("3.1234 N 101.5678 \u212a", None),
        ("HTTP://X.COM/?LL=3.1,101.5", (3.1, 101.5)), ("https://x.com/?Query=3.1,101.5", (3.1, 101.5)), ("https://x.com/?CENTER=3.1%2C101.5", (3.1, 101.5)),
        # the leftmost query wins, whichever name it has
        ("https://x.com/?ll=3.1,101.5&q=4.1,102.5", (3.1, 101.5)), ("https://x.com/?q=4.1,102.5&ll=3.1,101.5", (4.1, 102.5)),
        ("https://x.com/?z=1&query=3.1,101.5&ll=4.1,102.5", (3.1, 101.5)),
        # a number of a link is `-?\d+(\.\d+)?` and nothing is said about what follows it, so nothing is required (a reading, see the docstring of _LINK_PATTERNS)
        ("https://x.com/@3.1,101.5.7", (3.1, 101.5)), ("see https://x.com/@3.1,101.5.", (3.1, 101.5)), ("https://x.com/@3.1,101.5z", (3.1, 101.5)),
        ("https://x.com/@3.1,101.5abc", (3.1, 101.5)), ("https://x.com/@3.1.,101.5", None), ("https://x.com/@3.1,-101.5", (3.1, -101.5)),
        # what makes a map link: `www.` only at the start, `google.` then `/maps` in that order and with anything between (google.co.uk), and `://`
        ("www.example.com/@3.1,101.5,15z", (3.1, 101.5)), ("see www.example.com/@3.1,101.5,15z", None),
        ("google.co.uk/maps/@3.1,101.5,15z", (3.1, 101.5)), ("x.com/maps/@3.1,101.5 google.com", None), ("google.com/@3.1,101.5", None),
        # the order of the patterns when two match (!3d, @, query, place), the first match is final, the comma of a query has no white space round it
        ("https://x.com/place/4.1,102.5?q=3.1,101.5", (3.1, 101.5)), ("https://x.com/@95,101.5/@3.0,101.5", None),
        ("https://x.com/@95,101.5,15z/?q=3.0,101.5", None), ("https://x.com/?q=3.1, 101.5", None), ("https://x.com/?q=3.1 ,101.5", None),
        ("https://x.com/?q=3.1,101.5", (3.1, 101.5)), ("https://x.com/?&q=3.1,101.5", (3.1, 101.5)), ("https://x.com/&ll=3.1,101.5", (3.1, 101.5)),
        ("https://x.com/q=3.1,101.5", None), ("https://x.com/;q=3.1,101.5", None),
        # a link with Arabic-Indic digits (every pattern is ASCII only), and with the Unicode minus
        ("https://x.com/?q=\u0663.1,101.5", None), ("https://x.com/!3d\u0663!4d101.5", None), ("https://x.com/@\u22123.1,101.5", None),
        # `geo:` is told apart from a link by what the text contains first: a link wins when `://` is there, and `goo.gl/` only matters for a link
        ("geo:3.1,101.5?u=goo.gl/", (3.1, 101.5)), ("geo:3.1,101.5?q=http://x", None), ("geo:3.1,101.5?q=4.1,102.5://", (4.1, 102.5)),
        ("geo:3.1234,101.5678;crs=wgs84;u=3", (3.1234, 101.5678)), ("geo:3.1234,101.5678?z=15;u=3", (3.1234, 101.5678)),
        ("geo:3.1234,101.5678;u=3?z=15", (3.1234, 101.5678)),
        # trimming removes the 28 characters of the page and nothing else: the other control characters (which Java's String.trim would remove) are refused
        ("\x013.1234 101.5678", None), ("3.1234 101.5678\x1b", None), ("\x00 3.1234 101.5678", None), ("3.1234 101.5678\x7f", None), ("\x083.1234 101.5678", None),
        ("\x0e3.1234 101.5678", None), ("3.1234\x01101.5678", None), ("geo:3.1,101.5\x01", None),
        # marks are skipped wherever they are: after white space, first in the text, minute and second marks in a pair without letters
        ("3.1234 \u00b0 101.5678", (3.1234, 101.5678)), ("\u00b03.1234 101.5678", (3.1234, 101.5678)), ("3.1234' 101.5678'", (3.1234, 101.5678)),
        ("3.1234\u2032 101.5678\u2033", (3.1234, 101.5678)), ("3.1234\u00b0'" + chr(34) + " 101.5678", (3.1234, 101.5678)), ("\u2019 3.1234 101.5678", (3.1234, 101.5678)),
        ("3.1234\u02da, 101.5678\u00ba", (3.1234, 101.5678)), ("3 8\u2019 N 101 41\u2019 E", (3 + 8 / 60, 101 + 41 / 60)),
        ("3 8 20\u201d N 101 41 12\u201d E", (3 + 8 / 60 + 20 / 3600, 101 + 41 / 60 + 12 / 3600)),
        # whole numbers by value (`3.0` is whole, so is `8.0`), signs next to a letter, and more or fewer letters than two
        ("3 8.0 20 N 101 E", (3 + 8 / 60 + 20 / 3600, 101.0)), ("3.0 8 20 N 101 E", (3 + 8 / 60 + 20 / 3600, 101.0)), ("3. 8. 20 N 101 E", (3 + 8 / 60 + 20 / 3600, 101.0)),
        ("N 3 E 101 W", None), ("N 3 E 101 W 4", None), ("N 3 N 4", None), ("N 3 S 4", None), ("E 3 W 4", None), ("N 3 E", None), ("N", None),
        ("N 3 E 101 N", None), ("3 N 101 E 5 S", None),
        # the range: zero in one place is fine, (0, 0) is not, in a link as in plain text
        ("0, 101.5", (0.0, 101.5)), ("3.1, 0", (3.1, 0.0)), ("https://x.com/@0,101.5,15z", (0.0, 101.5)), ("https://x.com/@3.1,0", (3.1, 0.0)),
        ("geo:0,101.5", (0.0, 101.5)), ("geo:0,0", None), ("https://x.com/?q=0,0", None),
        # four numbers for one coordinate, sixty minutes
        ("3 8 20 5 N 101 E", None), ("N 3 8 20 5 E 101", None), ("3 60 N 101 E", None), ("3 8 60 N 101 E", None), ("3 8 59.999 N 101 E", (3 + 8 / 60 + 59.999 / 3600, 101.0)),
    ]


def own_coords():
    c = Checks("coords: the examples of 11.8 and the edges of its grammar")
    for text, want in _coords_table():
        try:
            got = parse_coords(text)
        except Refused:
            got = None
        except Exception as err:
            c.group.diffs.append("%s raised %s: %s" % (escape_text(text), type(err).__name__, err))
            continue
        if want is None:
            c.true(got is None, "%s: read as %s, expected refused" % (escape_text(text), got))
        else:
            c.true(got is not None and abs(got[0] - want[0]) < 1e-12 and abs(got[1] - want[1]) < 1e-12,
                   "%s: read as %s, expected %s" % (escape_text(text), got, want))
    # the length limit counts UTF-16 code units, after trimming
    base = "3.1234 " + " " * (MAX_TEXT_UNITS - 7 - 8) + "101.5678"
    c.eq(len(base), MAX_TEXT_UNITS, "test text")
    c.true(parse_coords(base) == (3.1234, 101.5678), "a text of exactly 4000 characters is read")
    c.true(parse_coords("   " + base + "\u00a0\u00a0") == (3.1234, 101.5678), "trimming comes before the length test")
    try:
        parse_coords("3.1234 " + " " * (MAX_TEXT_UNITS - 7 - 7) + "101.5678")
        c.true(False, "a text of 4001 characters was read")
    except Refused:
        c.true(True, "")
    link = "https://x.com/@3.1,101.5"
    emoji = "\U0001F600"
    fits = link + emoji * ((MAX_TEXT_UNITS - len(link)) // 2)              # exactly 4000 UTF-16 units or one under
    c.eq(utf16_length(fits) <= MAX_TEXT_UNITS, True, "test text length")
    c.true(parse_coords(fits) == (3.1, 101.5), "a link of %d UTF-16 units is read" % utf16_length(fits))
    too_long = fits + emoji
    c.eq(utf16_length(too_long) > MAX_TEXT_UNITS and len(too_long) < MAX_TEXT_UNITS, True, "test text length")
    try:
        parse_coords(too_long)
        c.true(False, "a link of %d UTF-16 units (%d characters) was read" % (utf16_length(too_long), len(too_long)))
    except Refused:
        c.true(True, "")
    # the white-space set is the page's list of 28 characters, tried one by one: each is trimmed at both ends and separates two numbers
    listed = ([0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1C, 0x1D, 0x1E, 0x1F, 0x20, 0xA0, 0x1680] + list(range(0x2000, 0x200B))
              + [0x2028, 0x2029, 0x202F, 0x205F, 0x3000])
    c.eq(len(listed), 28, "the page lists 28 white-space characters")
    c.eq(sorted(WHITE), sorted(listed), "the white-space set is those 28 characters and no others")
    for cp in listed:
        ch = chr(cp)
        c.eq(parse_coords(ch + "3.1234" + ch + "101.5678" + ch), (3.1234, 101.5678), "U+%04X is trimmed and separates two numbers" % cp)
    for cp in (0x85, 0x180E, 0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF, 0xAD, 0x61C, 0x2800, 0x3164):                    # not white space
        for text in (chr(cp) + "3.1234 101.5678", "3.1234 101.5678" + chr(cp), "3.1234" + chr(cp) + "101.5678"):
            c.refuses(lambda: parse_coords(text), None, "U+%04X is not white space: %s" % (cp, escape_text(text)))
    # a refusal of a swapped pair says so
    try:
        parse_coords("101.5678, 3.1234")
    except Refused as err:
        c.true("swap" in err.message, "a swapped pair: the sentence says 'did you swap them?'")
    return c.done()


def _fmt_decimal(rng, lat, lon):
    sep = rng.choice([" ", ", ", ",", "; ", ";", "\u00a0", "\t", " , "])
    mark = rng.choice(["", "\u00b0", "\u00ba"])
    return "%s%s%s%s%s" % (lat, mark, sep, lon, mark)


def own_coords_roundtrip(seed):
    """Random places written in every style the page allows must read back to the same numbers."""
    c = Checks("coords: random places in every style read back to the same numbers")
    rng = random.Random(seed)
    for _ in range(3000):
        lat = round(rng.uniform(-90, 90), rng.randint(0, 9))
        lon = round(rng.uniform(-180, 180), rng.randint(0, 9))
        if lat == 0 and lon == 0:
            continue
        slat, slon = repr(lat), repr(lon)
        if "e" in slat or "e" in slon:
            continue
        styles = [("plain", _fmt_decimal(rng, slat, slon), (lat, lon))]
        alat, alon = abs(lat), abs(lon)
        nl, el = ("N" if lat >= 0 else "S"), ("E" if lon >= 0 else "W")
        if rng.random() < 0.5:
            nl, el = nl.lower(), el.lower()
        if lat != 0 and lon != 0:
            styles.append(("suffix", "%r %s%s%r %s" % (alat, nl, rng.choice([",", " ", ", "]), alon, el), (lat, lon)))
            styles.append(("prefix", "%s %r %s %r" % (nl, alat, el, alon), (lat, lon)))
            styles.append(("swapped", "%r %s, %r %s" % (alon, el, alat, nl), (lat, lon)))
            d1, d2 = int(alat), int(alon)
            m1, m2 = (alat - d1) * 60, (alon - d2) * 60
            styles.append(("dm", "%d\u00b0%r'%s %d\u00b0%r'%s" % (d1, m1, nl, d2, m2, el), (lat, lon)))
        styles.append(("geo", "geo:%r,%r%s" % (lat, lon, rng.choice(["", ",12.5", ";u=3", "?z=14", ",3;crs=wgs84"])), (lat, lon)))
        styles.append(("link@", "https://www.google.com/maps/@%r,%r,15z" % (lat, lon), (lat, lon)))
        styles.append(("link!3d", "https://www.google.com/maps/place/Somewhere/@1.5,2.5,17z/data=!4m5!3m4!1s0x0:0x1!8m2!3d%r!4d%r" % (lat, lon), (lat, lon)))
        styles.append(("link?q", "https://maps.google.com/maps?hl=en&q=%r%s%r" % (lat, rng.choice([",", "%2C"]), lon), (lat, lon)))
        for style, text, want in styles:
            try:
                got = parse_coords(text)
            except Refused as err:
                c.group.diffs.append("%s %r: refused (%s)" % (style, text, err.message))
                continue
            if style in ("dm",):
                c.true(abs(got[0] - want[0]) < 1e-9 and abs(got[1] - want[1]) < 1e-9, "%s %r: read as %s, expected %s" % (style, text, got, want))
            else:
                c.true(got == want, "%s %r: read as %s, expected %s" % (style, text, got, want))
    return c.done()


def _valid_samples(rng, n):
    """Texts that read as places, in every style (the material the mutations start from)."""
    out = []
    for _ in range(n):
        lat = round(rng.uniform(-89, 89), rng.randint(0, 7))
        lon = round(rng.uniform(-179, 179), rng.randint(0, 7))
        a, b = abs(lat), abs(lon)
        nl, el = ("N" if lat >= 0 else "S"), ("E" if lon >= 0 else "W")
        out.append(rng.choice([
            "%r, %r" % (lat, lon), "%r %r" % (lat, lon), "%r\u00b0 %r\u00b0" % (lat, lon), "%r %s, %r %s" % (a, nl, b, el),
            "%s %r %s %r" % (nl, a, el, b), "%d\u00b0%d'%r\"%s %d\u00b0%d'%r\"%s" % (int(a), rng.randint(0, 59), rng.uniform(0, 59.9), nl,
                                                                              int(b), rng.randint(0, 59), rng.uniform(0, 59.9), el),
            "%d %r %s %d %r %s" % (int(a), rng.uniform(0, 59.9), nl, int(b), rng.uniform(0, 59.9), el),
            "geo:%r,%r" % (lat, lon), "geo:%r,%r,12;u=3" % (lat, lon), "https://www.google.com/maps/@%r,%r,15z" % (lat, lon),
            "https://maps.google.com/?q=%r%%2C%r" % (lat, lon), "https://www.google.com/maps/place/%r,%r" % (lat, lon),
            "https://x.com/data=!3d%r!4d%r" % (lat, lon), "www.google.com/maps?ll=%r,%r&z=3" % (lat, lon)]))
    return out


def own_coords_fuzz(seed, rounds=60000):
    """Random texts built from the pieces of the grammar, and mutations of texts that are places: only Refused may ever be raised, and an
    answer is always a finite place inside the ranges."""
    c = Checks("coords: fuzz, nothing but Refused is ever raised")
    rng = random.Random(seed)
    pieces = ["3", "101", ".5", "5.", "0", "00", "-", "+", "\u2212", ".", ",", ";", " ", "\t", "\u00a0", "\u0085", "\u200b", "\u3000", "\x1c", "N", "s", "E", "w",
              "x", "\u00b0", "\u00ba", "\u02da", "'", "\u2019", "\u2032", "\"", "\u201d", "\u2033", "geo:", "GEO:", "http://", "https://", "www.", "google.",
              "/maps", "/place/", "@", "!3d", "!4d", "?q=", "&ll=", "%2C", "%2c", "goo.gl/", "maps.app.goo.gl", "9" * 30, "9" * 400, "\u0663", "\uff13",
              "\ud83d", "\ude00", "\U0001F600", "e", "1e5", "60", "59.99", "180", "90", "91", "?", "#", "=", "(", ")", "\\", "\x00", "\x7f"]
    stats = {"refused": 0, "accepted": 0}

    def try_text(text):
        try:
            lat, lon = parse_coords(text)
        except Refused:
            stats["refused"] += 1
            return
        except Exception as err:
            c.group.diffs.append("%s raised %s: %s" % (escape_text(text), type(err).__name__, err))
            return
        stats["accepted"] += 1
        c.true(abs(lat) <= 90 and abs(lon) <= 180 and not (lat == 0 and lon == 0) and math.isfinite(lat) and math.isfinite(lon),
               "%s gave %r, %r" % (escape_text(text), lat, lon))

    for _ in range(rounds):
        try_text("".join(rng.choice(pieces) for _ in range(rng.randint(0, 14))))
    for text in _valid_samples(rng, rounds // 4):
        try_text(text)
        chars = list(text)
        for _ in range(rng.randint(1, 3)):                                # one to three random edits
            op = rng.randint(0, 4)
            i = rng.randrange(len(chars) + 1)
            if op == 0 and chars:
                del chars[min(i, len(chars) - 1)]
            elif op == 1:
                chars.insert(i, rng.choice(pieces))
            elif op == 2 and chars:
                chars[min(i, len(chars) - 1)] = rng.choice(pieces)
            elif op == 3 and len(chars) > 1:
                j = min(i, len(chars) - 2)
                chars[j], chars[j + 1] = chars[j + 1], chars[j]
            elif chars:
                j = min(i, len(chars) - 1)
                chars[j:j] = chars[j:j + rng.randint(1, 6)]
        try_text("".join(chars))
    for _ in range(rounds // 4):                                          # whole random strings, including lone surrogates
        try_text("".join(chr(rng.choice([rng.randint(0, 0x7F), rng.randint(0x80, 0x2FFF), rng.randint(0xD800, 0xDFFF), rng.randint(0x10000, 0x10FFFF)]))
                         for _ in range(rng.randint(0, 12))))
    c.true(stats["accepted"] > 5000, "the fuzz read only %d texts as places" % stats["accepted"])
    c.group.detail = "%d random and mutated texts (%d read as places), only Refused raised" % (stats["refused"] + stats["accepted"], stats["accepted"])
    return c.group


def own_track():
    c = Checks("track (11.1)")
    rng = random.Random(11)
    road = Road([("line", 3000.0)])
    sched = parked(0.0, 0.0, 6) + steady(20.0, 0.0, 3000.0, t0=6.0) + parked(3000.0, 6 + 151, 6)
    fixes, geo = road_fixes(road, sched, noise=0.0)
    for f in fixes[:6] + fixes[-6:]:
        f.speed = 0.3                                                     # a parked phone: speed known and under 1 m/s
    track = Track(fixes)
    c.eq(track.n_vertices, 151, "vertices of a straight 3000 m road at 20 m/s (the parked fixes are not kept)")
    c.near(track.length, 3000.0, 1e-6, "length of the track")
    c.near(track.point_at(1234.5)[1], 1234.5, 1e-6, "pointAt on a road going north")
    c.near(track.point_at(-5)[1], 0.0, 1e-9, "pointAt below 0 is clamped")
    c.near(track.point_at(1e9)[1], 3000.0, 1e-6, "pointAt above the length is clamped")
    lat, lon = track.latlon_at(1000.0)
    c.near(geo.y(lat) - geo.y(fixes[6].lat), 1000.0, 1e-6, "latlon_at(1000) is 1000 m north of the first vertex")
    # sorting and de-duplication come first: a shuffled copy and a copy with a repeated time give the same track
    shuffled = fixes[:]
    rng.shuffle(shuffled)
    c.near(Track(shuffled).length, track.length, 1e-9, "the same fixes in any order")
    dup = fixes[:30] + [sc.Fix(fixes[29].t, fixes[29].lat + 0.01, fixes[29].lon, 20.0, 0.0, 3.5)] + fixes[30:]
    c.near(Track(dup).length, track.length, 1e-6, "a repeated time: the first of equal times wins")
    # the filters
    def straight(n, speed=10.0, hacc=3.0, step=10.0):
        fx, _ = road_fixes(Road([("line", 10000.0)], heading=90.0), [(float(i), step * i, speed) for i in range(n)], origin=(3.0, 101.0), hacc=hacc)
        return fx
    c.eq(Track(straight(10, speed=1.0)).n_vertices, 10, "speed exactly 1.0 is kept")
    c.refuses(lambda: Track(straight(10, speed=0.999)), "no_drive", "speed 0.999 is dropped, nothing left")
    c.eq(Track(straight(10, speed=-1.0)).n_vertices, 10, "an unknown speed is kept")
    c.eq(Track(straight(10, hacc=30.0)).n_vertices, 10, "hAcc exactly 30 is kept")
    c.refuses(lambda: Track(straight(10, hacc=30.0001)), "no_drive", "hAcc over 30 is dropped")
    c.eq(Track(straight(10, hacc=-1.0)).n_vertices, 10, "an unknown hAcc is kept")
    mixed = straight(10)
    mixed[4].hacc = 80.0
    mixed[5].speed = 0.2
    c.eq(Track(mixed).n_vertices, 8, "two bad fixes of ten are left out")
    c.near(Track(mixed).length, 70.0 + 20.0, 1e-3, "and the road is simply longer between their neighbours (10 + 10 + 20 + 20 + 10 + 10 + 10)")
    c.eq(Track(straight(20, step=2.9)).n_vertices, 10, "fixes 2.9 m apart: every second one is a vertex (5.8 m)")
    c.eq(Track(straight(20, step=3.1)).n_vertices, 20, "fixes 3.1 m apart: every one is a vertex")
    c.eq(Track(straight(4, step=3.1)).n_vertices, 4, "four vertices")
    c.eq(Track(straight(3, step=3.1)).n_vertices, 3, "three vertices are enough")
    c.refuses(lambda: Track(straight(2, step=30.0)), "no_drive", "two vertices")
    c.refuses(lambda: Track(straight(30, step=1.0, speed=0.5)), "no_drive", "a recording of a parked phone")
    c.refuses(lambda: Track([]), "no_drive", "no fixes")
    c.eq(Track(straight(30, step=1.3)).n_vertices, 10, "30 fixes 1.3 m apart: a vertex every third fix (3.9 m)")
    c.near(Track(straight(30, step=1.3)).length, 27 * 1.3, 1e-3, "and 35.1 m of track")
    # unusable fixes (section 1) are dropped
    bad = straight(10)
    bad[3].lat = 95.0
    c.eq(Track(bad).n_vertices, 9, "a fix with |lat| > 90 is not usable")
    return c.done()


def own_passes():
    c = Checks("snapping a place (11.2)")
    road = Road([("line", 2000.0)], heading=0.0)
    fixes, geo = road_fixes(road, steady(20.0, 0.0, 2000.0))
    track = Track(fixes)
    x0 = geo.x(fixes[0].lon)
    def at(x, y):
        return geo.latlon(x0 + x, y + geo.y(fixes[0].lat))
    p = track.passes(*at(10.0, 500.0))
    c.eq(len(p), 1, "one pass")
    c.near(p[0][0], 500.0, 1e-6, "position of a place 10 m beside the road")
    c.near(p[0][1], 10.0, 1e-6, "its offset")
    c.eq(track.passes(*at(59.9, 700.0))[0][1] < 60, True, "59.9 m away is within the snap limit")
    c.eq(track.passes(*at(60.5, 700.0)), [], "60.5 m away: no pass")
    c.near(track.closest_approach(*at(100.0, 700.0)), 100.0, 1e-6, "the closest approach")
    near_end = track.passes(*at(5.0, -20.0))
    c.eq(len(near_end), 1, "a place 20 m before the start of the track is within 60 m of its first segment")
    c.near(near_end[0][0], 0.0, 1e-9, "and snaps to position 0")
    # out and back: two passes, the second one the wrong way round; both offsets within a few metres
    ob = Road([("line", 1000.0), ("arc", 20.0, 180.0), ("line", 1000.0)], heading=0.0)
    sched = steady(20.0, 0.0, ob.length)
    fx, geo2 = road_fixes(ob, sched)
    tr = Track(fx)
    x0b, y0b = geo2.x(fx[0].lon), geo2.y(fx[0].lat)
    pp = tr.passes(*geo2.latlon(x0b + 2.0, y0b + 500.0))                     # 2 m east of the way up; the way down is 40 m east
    c.eq(len(pp), 1, "out and back, a place on the way up: the way down (40 m away) is dropped by the 10 m rule")
    pp = tr.passes(*geo2.latlon(x0b + 20.0, y0b + 500.0))                    # 20 m east: halfway between the two carriageways
    c.eq(len(pp), 2, "out and back, a place between the two: both passes stay")
    c.true(pp[0][0] < pp[1][0], "in order of position")
    c.near(pp[0][1], pp[1][1], 1e-3, "with the same offset")
    # a hairpin 8 m between legs keeps both legs, 30 m only the near one
    for gap, count in ((8.0, 2), (30.0, 1), (9.5, 2), (10.5, 1)):
        hp = Road([("line", 400.0), ("arc", gap / 2.0, 180.0), ("line", 400.0)], heading=0.0)
        fx2, g3 = road_fixes(hp, steady(5.0, 0.0, hp.length))
        t3 = Track(fx2)
        x3, y3 = g3.x(fx2[0].lon), g3.y(fx2[0].lat)
        got = t3.passes(*g3.latlon(x3, y3 + 200.0))
        c.eq(len(got), count, "legs %.1f m apart, a place on the first leg" % gap)
    # a road driven twice the same way, joined by a straight: all passes stay
    loop = Road([("line", 800.0), ("arc", 100.0, 180.0), ("line", 100.0), ("arc", 100.0, 180.0)], heading=0.0)
    return c.done()


def _straight_case(heading=0.0, v=20.0, length=3000.0, origin=(3.5, 101.6), noise=0.0, seed=5):
    road = Road([("line", length)], heading=heading)
    fixes, geo = road_fixes(road, parked(0.0, 0.0, 5) + steady(v, 0.0, length, t0=5.0), origin=origin, noise=noise, rng=random.Random(seed))
    for f in fixes[:5]:
        f.speed, f.bearing = 0.2, -1.0
    return road, fixes, geo


def own_build():
    c = Checks("building a stage (11.3 to 11.5)")
    for heading in (0.0, 90.0, 180.0, 270.0, 37.0):
        road, fixes, geo = _straight_case(heading=heading)
        track = Track(fixes)
        res = build_stage_ex(track, ("pos", 500.0), ("pos", 2500.0), "t1", "Test", 123)
        st = res.stage
        c.near(st.length_m, 2000.0, 5e-3, "heading %g: the stage length is b - a" % heading)
        c.eq(st.start_gate.bearing, round(heading, 3), "heading %g: bearing of the start line" % heading) if heading in (0.0, 90.0, 180.0, 270.0) else \
            c.near(st.start_gate.bearing, heading, 2e-3, "heading %g: bearing of the start line" % heading)
        c.near(st.finish_gate.bearing, heading, 2e-3, "heading %g: bearing of the finish line" % heading)
        c.eq(st.start_gate.half, 25.0, "half width")
        c.eq((st.start_mark, st.origin), (st.origin, st.origin), "origin is the start mark")
        c.eq(len(st.route_points), 2 + 99, "route points: pa, the 99 vertices at 520 ... 2480, pb (heading %g)" % heading)
        c.near(st.start_gate.cx, 0.0, 1e-3, "the start line is at the origin when the pin is by position")
        c.near(st.start_gate.cy, 0.0, 1e-3, "")
        c.eq(st.text().split("\n")[0], "TRACKLOGGER-STAGE 1", "magic")
        c.eq(st.created_utc_ms, 123, "created")
        # the line centres are where the road is: finish centre is 2000 m from the start centre along the heading
        fx, fy = st.finish_gate.cx, st.finish_gate.cy
        c.near(math.hypot(fx, fy), 2000.0, 5e-3, "finish line 2000 m from the start line (heading %g)" % heading)
        c.near(sc.bearing(fx, fy), heading, 2e-3, "in the direction of the road")
        # the marks: the finish mark is the latitude and longitude of the finish line's centre
        lat_f, lon_f = st.geo.latlon(fx, fy)
        c.near(st.finish_mark[0], lat_f, 2e-7, "finish mark latitude (heading %g)" % heading)
        c.near(st.finish_mark[1], lon_f, 2e-7, "finish mark longitude")
        # scoring the source recording against the stage finds one run of the right time
        runs = sc.score_recording(st, fixes, "straight.csv", 1791885600000)
        c.eq(len(runs), 1, "one run in the source recording (heading %g)" % heading)
        if runs:
            c.true(runs[0].finished, "the run finished (heading %g)" % heading)
            c.near(runs[0].total_s, 100.0, 1e-3, "the stage time is 2000 m / 20 m/s (heading %g)" % heading)
    # by coordinate: a place 12 m beside the road at 800 m and 40 m beside it at 2300 m
    road, fixes, geo = _straight_case(heading=0.0)
    track = Track(fixes)
    x0, y0 = geo.x(fixes[5].lon), geo.y(fixes[5].lat)
    pa_ll, pb_ll = geo.latlon(x0 + 12.0, y0 + 800.0), geo.latlon(x0 - 40.0, y0 + 2300.0)
    res = build_stage_ex(track, ("at", pa_ll[0], pa_ll[1]), ("at", pb_ll[0], pb_ll[1]), "t2", "Test two", 0)
    st = res.stage
    c.near(res.a, 800.0, 1e-5, "by coordinate: position of the start")
    c.near(res.b, 2300.0, 1e-5, "by coordinate: position of the finish")
    c.near(res.offset_a, 12.0, 1e-5, "offset of the start")
    c.near(res.offset_b, 40.0, 1e-5, "offset of the finish")
    c.eq(st.origin, (round(pa_ll[0], 7), round(pa_ll[1], 7)), "the origin is the coordinate the driver gave")
    c.eq(st.start_mark, st.origin, "start mark is the coordinate")
    c.eq(st.finish_mark, (round(pb_ll[0], 7), round(pb_ll[1], 7)), "finish mark is the coordinate")
    c.near(st.start_gate.cx, -12.0, 2e-3, "the line is on the road, 12 m west of the coordinate (x)")
    c.near(st.start_gate.cy, 0.0, 2e-3, "(y)")
    c.near(st.length_m, 1500.0, 5e-3, "length 1500 m")
    c.eq(len(res.notes), 2, "two sentences: both coordinates are more than 5 m from the road")
    # a place exactly 5 m away is not reported
    res = build_stage_ex(track, ("at", *geo.latlon(x0 + 5.0, y0 + 800.0)), ("pos", 2300.0), "t3", "T", 0)
    c.eq(res.notes, [], "5 m from the road is not reported")
    # mixed pins
    res = build_stage_ex(track, ("pos", 600.0), ("at", *pb_ll), "t4", "T", 0)
    c.near(res.stage.length_m, 1700.0, 5e-3, "a position and a coordinate")
    return c.done()


def own_refusals():
    c = Checks("every refusal (11.1, 11.3, 11.6)")
    road, fixes, geo = _straight_case()
    track = Track(fixes)
    L = track.length
    P = lambda a, b: (lambda: build_stage(track, ("pos", a), ("pos", b), "r", "R", 0))
    x0, y0 = geo.x(fixes[5].lon), geo.y(fixes[5].lat)
    ll = lambda x, y: ("at",) + geo.latlon(x0 + x, y0 + y)
    c.eq(P(500.0, 2500.0)().length_m > 1999, True, "a good pair")
    c.refuses(P(2500.0, 500.0), "wrong_way", "finish before start")
    c.refuses(P(1000.0, 1000.0), "wrong_way", "equal positions")
    c.refuses(P(19.999, 1000.0), "near_end", "start at 19.999")
    c.eq(P(20.0, 1000.0)().length_m > 979, True, "start at exactly 20 is fine")
    c.refuses(P(-5.0, 1000.0), "near_end", "start at -5")
    c.refuses(P(500.0, L - 19.999), "near_end", "finish 19.999 m before the end")
    c.eq(P(500.0, L - 20.0)().length_m > 2000, True, "finish exactly 20 m before the end")
    c.refuses(P(500.0, L + 50.0), "near_end", "finish beyond the end")
    c.refuses(P(500.0, 799.999), "too_short", "299.999 m")
    c.eq(P(500.0, 800.0)().length_m > 299, True, "exactly 300 m")
    c.refuses(P(float("nan"), 1000.0), "near_end", "a start that is not a number")
    c.refuses(P(500.0, float("nan")), "near_end", "a finish that is not a number")
    c.refuses(P(float("nan"), float("nan")), "near_end", "both not numbers")
    c.refuses(P(float("inf"), 1000.0), "near_end", "an infinite start")
    c.refuses(P(500.0, float("inf")), "near_end", "an infinite finish")
    c.refuses(P(500.0, float("-inf")), "near_end", "a finish at minus infinity is not a finite number")
    c.refuses(P(float("-inf"), 1000.0), "near_end", "a start at minus infinity")
    c.refuses(lambda: build_stage(track, ("pos", 500.0), ll(0.0, 3000.0 + 70.0), "r", "R", 0), "no_pass_finish", "a finish 70 m past the end of the track and 0 m aside")
    c.refuses(lambda: build_stage(track, ll(100.0, 500.0), ("pos", 1500.0), "r", "R", 0), "no_pass_start", "a start 100 m beside the road")
    c.refuses(lambda: build_stage(track, ll(100.0, 500.0), ll(100.0, 1500.0), "r", "R", 0), "no_pass_start", "both without a pass: the start is named")
    c.refuses(lambda: build_stage(track, ("pos", float("nan")), ll(100.0, 1500.0), "r", "R", 0), "near_end",
              "NaN start and a finish without a pass: the start pin is checked first (11.3, the order of the checks)")
    c.refuses(lambda: build_stage(track, ll(0.0, 1500.0), ll(0.0, 500.0), "r", "R", 0), "wrong_way", "by coordinate, driven the other way round")
    # the loop rule: a stage whose pins are 300 m apart on the road but less than 150 m in a straight line
    circle = Road([("line", 100.0), ("arc", 40.0, 360.0), ("line", 600.0)], heading=0.0)
    cf, cg = road_fixes(circle, steady(10.0, 0.0, circle.length))
    ctrack = Track(cf)
    c.refuses(lambda: build_stage(ctrack, ("pos", 100.0), ("pos", 100.0 + 2 * math.pi * 40 - 1.0), "r", "R", 0), "too_short", "one lap of a 40 m circle is 251 m: too short")
    big = Road([("line", 100.0), ("arc", 60.0, 360.0), ("line", 600.0)], heading=0.0)
    bf, bg = road_fixes(big, steady(10.0, 0.0, big.length))
    btrack = Track(bf)
    c.refuses(lambda: build_stage(btrack, ("pos", 100.0), ("pos", 100.0 + 2 * math.pi * 60 - 1.0), "r", "R", 0), "loop", "one lap of a 60 m circle is 376 m and the pins are 0 m apart")
    c.refuses(lambda: build_stage(btrack, ("pos", 100.0), ("pos", 100.0 + 2 * math.pi * 60 + 110.0), "r", "R", 0), "loop", "a lap and a bit: the pins are 120 m apart in a straight line")
    c.refuses(lambda: build_stage([], ("pos", 500.0), ("pos", 1500.0), "r", "R", 0), "no_drive", "no fixes")
    c.refuses(lambda: build_stage(fixes[:3], ("pos", 500.0), ("pos", 1500.0), "r", "R", 0), "no_drive", "three fixes of a parked phone")
    return c.done()


def _switchback(w, leg=100.0, legs=3, lead=0.0, tail=0.0, heading=0.0):
    parts = []
    if lead:
        parts.append(("line", lead))
    for k in range(legs):
        parts.append(("line", leg))
        if k < legs - 1:
            parts.append(("arc", w / 2.0, 180.0 if k % 2 == 0 else -180.0))
    if tail:
        parts.append(("line", tail))
    return Road(parts, heading=heading)


def own_guard():
    c = Checks("the guard (11.6): switchbacks w metres apart, the pins in the middle of the first / last leg")
    for w in (6.0, 12.0, 16.0, 20.0, 30.0, 35.0, 40.0):
        # the start pin in the middle of the first leg, three legs of 100 m, then a long straight to the finish pin
        road = _switchback(w, legs=3, tail=900.0)
        fixes, _ = road_fixes(road, steady(5.0, 0.0, road.length))
        track = Track(fixes)
        finish = track.length - 100.0
        try:
            build_stage(track, ("pos", 50.0), ("pos", finish), "g", "G", 0)
            got = "ok"
        except Refused as err:
            got = err.category
        # the third leg is 2w from the first: a forward crossing within 35 m of the line when 2w <= 35
        c.eq(got, "guard_start" if 2 * w <= 35 else "ok", "start pin on the first leg, legs %g m apart" % w)
        # the finish pin in the middle of the last leg, the switchbacks at the end
        road = _switchback(w, legs=3, lead=1000.0)
        fixes, _ = road_fixes(road, steady(5.0, 0.0, road.length))
        track = Track(fixes)
        try:
            build_stage(track, ("pos", 100.0), ("pos", track.length - 50.0), "g", "G", 0)
            got = "ok"
        except Refused as err:
            got = err.category
        c.eq(got, "guard_finish" if 2 * w <= 35 else "ok", "finish pin on the last leg, legs %g m apart" % w)
    # the guard is a property of the stage, not of the lines being close to the pin: a route that comes back over the start line late is fine
    road = _switchback(12.0, leg=200.0, legs=3, tail=900.0)
    fixes, _ = road_fixes(road, steady(5.0, 0.0, road.length))
    track = Track(fixes)
    # leg 1 is 200 m: u of the third-leg crossing = 100 + 19 + 200 + 19 + 100 = 438 > 300 -> not refused
    c.eq(build_stage(track, ("pos", 100.0), ("pos", track.length - 100.0), "g", "G", 0) is not None, True, "u = 438 m is beyond the 300 m of the start rule")
    # u just inside 300: tune the leg length so 2 * leg + pi * w is below / above 300
    for leg, expect in ((100.0, "guard_start"), (120.0, "guard_start"), (125.0, "guard_start"), (145.0, "ok"), (160.0, "ok")):
        road = _switchback(12.0, leg=leg, legs=3, tail=900.0)
        fixes, _ = road_fixes(road, steady(5.0, 0.0, road.length))
        track = Track(fixes)
        u = 2 * leg + math.pi * 12.0
        try:
            build_stage(track, ("pos", leg / 2.0), ("pos", track.length - 100.0), "g", "G", 0)
            got = "ok"
        except Refused as err:
            got = err.category
        c.eq(got, expect, "leg %g m: the third leg is crossed %.0f m into the stage (30 < u <= 300 refuses)" % (leg, u))
    # a loop: the road passes over its own start line 200 m in (a circle of 120 m circumference ... crosses the line again)
    return c.done()


def own_guard_crossing_rules():
    """11.6 on hand-made routes: the thresholds of u, the width of 35, and the direction of the crossing."""
    c = Checks("the guard (11.6): thresholds of u and of the width, on hand-made routes")
    gate = sc.Gate(0.0, 0.0, 0.0, 25.0)
    def crossing(offset, y0=-10.0, y1=10.0):
        pts = [(offset, y0), (offset, y1)]
        return forward_crossings(gate, pts, [0.0, math.hypot(0, y1 - y0)])
    c.eq(len(crossing(35.0)), 1, "across = 35 exactly counts")
    c.eq(len(crossing(35.0001)), 0, "across = 35.0001 does not")
    c.eq(len(crossing(-35.0)), 1, "negative side too")
    c.eq(len(crossing(10.0, 10.0, -10.0)), 0, "a crossing against the direction of the line is not forward")
    c.eq(len(crossing(10.0, 0.0, 10.0)), 0, "along(q0) = 0 is not < 0: starting on the line is not a crossing")
    c.eq(len(crossing(10.0, -10.0, 0.0)), 1, "ending exactly on the line is a crossing (along(q1) = 0 >= 0)")
    c.near(crossing(10.0)[0], 10.0, 1e-12, "u is the distance along the segment")
    return c.done()


def own_run_check():
    """The pins land where they were put: scoring the source recording on the stage finds one run of the time between the pins."""
    c = Checks("run check: the stage time of the source recording is the time between the pins")
    rng = random.Random(7)
    parts = [("line", 600.0), ("arc", 150.0, 90.0), ("line", 700.0), ("arc", 80.0, -120.0), ("line", 500.0), ("arc", 300.0, 45.0), ("line", 900.0)]
    road = Road(parts, heading=20.0)
    # a driver who speeds up and slows down: distance(t) from a smooth speed profile
    sched, t, d = [], 0.0, 0.0
    while d <= road.length:
        v = 15.0 + 8.0 * math.sin(t / 25.0)
        sched.append((t, d, v))
        d += v
        t += 1.0
    t_of_d = lambda dist: _interp([s[1] for s in sched], [s[0] for s in sched], dist)
    for noise in (0.0, 0.0, 2.5):
        fixes, geo = road_fixes(road, sched, noise=noise, rng=rng)
        track = Track(fixes)
        for a, b in ((100.0, 1500.0), (800.0, 2600.0), (1500.0, 2900.0)):
            res = build_stage_ex(track, ("pos", a), ("pos", b), "rc", "RC", 0)
            runs = sc.score_recording(res.stage, fixes, "rc.csv", 0)
            c.true(len(runs) >= 1 and runs[0].finished, "noise %g, pins %g-%g: a finished run" % (noise, a, b))
            if runs and runs[0].finished:
                # the track's own distance is the polyline's, so the stage time is the time the car needed for the track distance a..b
                expected = _interp(track.s, track.t, b) - _interp(track.s, track.t, a)
                tolerance = 0.06 if noise == 0.0 else 1.0
                c.near(runs[0].total_s, expected, tolerance, "noise %g, pins %g-%g: stage time against the time between the pins" % (noise, a, b))
                c.near(runs[0].length_m, res.stage.length_m, 1e-6, "the run's length is the stage's")
                c.eq(len(runs), 1, "noise %g, pins %g-%g: exactly one run in the source recording" % (noise, a, b))
    return c.done()


def _interp(xs, ys, x):
    if x <= xs[0]:
        return ys[0]
    if x >= xs[-1]:
        return ys[-1]
    k = bisect.bisect_right(xs, x) - 1
    f = (x - xs[k]) / (xs[k + 1] - xs[k])
    return ys[k] + f * (ys[k + 1] - ys[k])


def own_guard_read_back_equivalence(seed):
    """Whether the guard judges the stage as built or as read back from its file: random switchback roads, pins at random places."""
    c = Checks("the guard on the built stage and on the stage read back from its file agree")
    rng = random.Random(seed)
    disagreements = 0
    for _ in range(300):
        w = rng.uniform(8, 45)
        leg = rng.uniform(60, 220)
        road = _switchback(w, leg=leg, legs=rng.randint(3, 5), lead=rng.choice([0.0, 300.0]), tail=rng.choice([0.0, 600.0]))
        fixes, _ = road_fixes(road, steady(rng.uniform(4, 12), 0.0, road.length), noise=rng.choice([0.0, 2.0]), rng=rng)
        try:
            track = Track(fixes)
        except Refused:
            continue
        a = rng.uniform(20, track.length - 340)
        b = rng.uniform(a + 300, track.length - 20)
        outcomes = []
        for flag in (True, False):
            try:
                build_stage_ex(track, ("pos", a), ("pos", b), "g", "G", 0, guard_on_read_back=flag)
                outcomes.append("ok")
            except Refused as err:
                outcomes.append(err.category)
        if outcomes[0] != outcomes[1]:
            disagreements += 1
            c.group.diffs.append("pins %.3f %.3f: read back %s, as built %s" % (a, b, outcomes[0], outcomes[1]))
    c.group.detail = "300 random switchback roads, %d disagreements" % disagreements
    return c.group


def in_a_hole(track, position):
    """Whether the time of a position (interpolated along the vertices, by this script's own _interp) is in a hole of the recording, the ends
    included: a line there can never be crossed (11.1 step 5)."""
    t = _interp(track.s, track.t, position)
    return any(t0 <= t <= t1 for t0, t1, _ in track.holes)


def first_drive_end(track, stop_seconds=30.0):
    """The track distance at which the first drive of a recording ends: the first jump of more than `stop_seconds` between two vertices (a long
    stop; the parked fixes are not vertices), or the end of the track. Only used to choose pins for this script's own checks."""
    for i in range(len(track.t) - 1):
        if track.t[i + 1] - track.t[i] > stop_seconds:
            return track.s[i]
    return track.length


def own_real_inputs(real_log):
    """Pins on the reduced session files of the fixtures and on the real Sepang recording (a circuit): stages are made, written, read back, and
    the recording is scored against them; the stage time is compared with the time between the pins taken from the track."""
    c = Checks("real-looking inputs: road1, road2, the Sepang recording")
    names = sorted(os.path.join(DEFAULT_FIXTURES, r, f) for r in ("road1", "road2") for f in os.listdir(os.path.join(DEFAULT_FIXTURES, r))
                   if f.startswith("session_") and f.endswith(".csv"))
    if os.path.isfile(real_log):
        names.append(real_log)
    worst = 0.0
    built = 0
    for path in names:
        base = os.path.basename(path)
        ses = sc.read_session(path)
        try:
            track = Track(ses.fixes)
        except Refused as err:
            c.true(False, "%s: %s" % (base, err.message))
            continue
        end = 3000.0 if path == real_log else first_drive_end(track)
        for lo, hi in ((0.1, 0.5), (0.3, 0.6), (0.05, 0.9)):
            a, b = end * lo, end * hi
            if b - a < 320.0:
                continue
            try:
                res = build_stage_ex(track, ("pos", a), ("pos", b), "real", "Real", 0)
            except Refused as err:
                c.true(err.category in ("guard_start", "guard_finish", "loop", "near_end", "hole_start", "hole_finish", "break"),
                       "%s %g-%g: refused as %s" % (base, a, b, err.category))
                if err.category == "hole_start":
                    c.true(in_a_hole(track, a), "%s %g-%g: refused as hole_start, but the start is not in a hole" % (base, a, b))
                if err.category == "hole_finish":
                    c.true(in_a_hole(track, b) and not in_a_hole(track, a), "%s %g-%g: refused as hole_finish without a finish in a hole" % (base, a, b))
                continue
            c.true(not in_a_hole(track, a) and not in_a_hole(track, b), "%s %g-%g: built although a pin is in a hole" % (base, a, b))
            built += 1
            if "103000" in base:                                           # a false start: the track doubles back, so positions are not progress
                continue
            runs = sc.score_recording(res.stage, ses.fixes, base, ses.start_utc_ms)
            c.true(len(runs) >= 1 and runs[0].finished, "%s %.0f-%.0f: a finished run" % (base, a, b))
            if runs and runs[0].finished:
                expected = _interp(track.s, track.t, b) - _interp(track.s, track.t, a)
                diff = abs(runs[0].total_s - expected)
                worst = max(worst, diff)
                c.true(diff < 1.5, "%s %.0f-%.0f: stage time %.3f s but the track says %.3f s" % (base, a, b, runs[0].total_s, expected))
                c.near(runs[0].length_m, res.stage.length_m, 1e-6, "length of the run")
    c.group.detail = "%d stages built on %d recordings; the stage time is within %.2f s of the time between the pins, worst case" % (built, len(names), worst)
    return c.group


def own_text_roundtrip():
    c = Checks("the stage file (11.5): written, read back, written again")
    road, fixes, geo = _straight_case(heading=33.0)
    track = Track(fixes)
    st = build_stage(track, ("pos", 400.0), ("pos", 2900.0), "pin_x", "Pin case x", 1791885600000)
    c.eq(sc.parse_stage_text(st.text()).text(), st.text(), "write, read, write")
    lines = st.text().split("\n")
    c.eq(lines[:2], ["TRACKLOGGER-STAGE 1", "id pin_x"], "first lines")
    c.eq(lines[2:5], ["name Pin case x", "kind road", "created_utc_ms 1791885600000"], "name, kind, created")
    c.true(re.fullmatch(r"origin -?\d+\.\d{7} -?\d+\.\d{7}", lines[5]) is not None, "origin to 7 decimals: " + lines[5])
    c.true(re.fullmatch(r"start_gate -?\d+\.\d{3} -?\d+\.\d{3} \d+\.\d{3} 25\.0", lines[8]) is not None, "gate to 3 decimals: " + lines[8])
    c.eq(lines[-2:], ["end", ""], "ends with end")
    return c.done()


def _rows_fixes(rows, origin=(3.5, 101.6)):
    """Fixes from rows of (t, x, y, speed, hAcc), the positions in metres east and north of `origin`."""
    geo = sc.Geo(origin[0], origin[1])
    out = []
    for t, x, y, v, hacc in rows:
        lat, lon = geo.latlon(x, y)
        out.append(sc.Fix(float(t), lat, lon, v, 0.0 if v >= 0.5 else -1.0, hacc))
    return out


def _line_rows(t0, d0, seconds, v=20.0, x=0.0, hacc=3.0):
    """Rows of a car driving north along a straight road: `seconds` + 1 fixes, one a second from time `t0` at distance `d0`."""
    return [(t0 + i, x, d0 + v * i, v, hacc) for i in range(int(seconds) + 1)]


def _polyline_rows(points, v=10.0):
    """Rows (t, x, y, speed, hAcc) of a car driving the polyline `points` (metres east and north) at v m/s, a fix every second, the corners and the
    last point included."""
    rows, t, carry = [], 0.0, 0.0
    for (x0, y0), (x1, y1) in zip(points, points[1:]):
        length = math.hypot(x1 - x0, y1 - y0)
        d = carry
        while d <= length + 1e-9:
            f = min(1.0, d / length)
            rows.append((t, x0 + f * (x1 - x0), y0 + f * (y1 - y0), v, 3.0))
            t += 1.0
            d += v
        carry = d - length
    rows.append((t, points[-1][0], points[-1][1], v, 3.0))
    return rows


def _outcome(track, a, b):
    """'ok', or the category a pair of positions is refused with."""
    try:
        build_stage_ex(track, ("pos", a), ("pos", b), "o", "O", 0)
        return "ok"
    except Refused as err:
        return err.category


def _hand_track(holes=None, v=10.0, length=5000.0):
    """A straight road north at v m/s with a fix every second, so that timeAt(d) = d / v; `holes` (t0, t1, metres) are set by hand when given, so
    that the thresholds can be tried exactly."""
    track = Track(_rows_fixes(_line_rows(0.0, 0.0, length / v, v=v)))
    if holes is not None:
        track.holes = list(holes)
    return track


def own_holes():
    c = Checks("holes and timeAt (11.1 steps 5 and 6)")
    # a hole is a gap of MORE than 3.0 s between two consecutive usable fixes
    for gap, count in ((3.0, 0), (2.999, 0), (3.0000001, 1), (3.001, 1), (3.5, 1), (10.0, 1), (400.0, 1)):
        track = Track(_rows_fixes(_line_rows(0.0, 0.0, 10) + _line_rows(10.0 + gap, 300.0, 10)))
        c.eq(len(track.holes), count, "two fixes %g s apart: %d hole(s)" % (gap, count))
    track = Track(_rows_fixes(_line_rows(0.0, 0.0, 10) + _line_rows(14.0, 300.0, 10)))
    c.eq([(h[0], h[1]) for h in track.holes], [(10.0, 14.0)], "the hole remembers the times of the two fixes either side of it")
    c.near(track.holes[0][2], 100.0, 0.05, "and their straight distance (200 m and 300 m north)")
    # judged on all the usable fixes, whether kept or not: a fix that is left out of the track (hAcc 80) still arrives, so 2 s + 2 s is no hole
    rows = _line_rows(0.0, 0.0, 9) + [(11.0, 0.0, 220.0, 20.0, 80.0)] + _line_rows(13.0, 260.0, 5)
    track = Track(_rows_fixes(rows))
    c.eq(track.holes, [], "a fix that is not kept still counts: no hole between kept fixes 4 s apart")
    c.eq(max(b - a for a, b in zip(track.t, track.t[1:])), 4.0, "(the vertices around it are 4 s apart)")
    rows = _line_rows(0.0, 0.0, 9) + [(11.0, 0.0, 220.0, 20.0, 80.0), (14.5, 0.0, 280.0, 20.0, 3.0)] + _line_rows(15.5, 300.0, 5)
    c.eq(len(Track(_rows_fixes(rows)).holes), 1, "but a real gap next to it (11 s to 14.5 s) is one")
    # a long stand is not a hole: its fixes keep arriving every second although the track leaves them out
    rows = _line_rows(0.0, 0.0, 20, v=10.0) + [(21.0 + i, 0.0, 200.0, 0.2, 3.0) for i in range(100)] + _line_rows(121.0, 200.0, 20, v=10.0)
    track = Track(_rows_fixes(rows))
    c.eq(track.holes, [], "a stand of 100 s with a fix every second: no hole")
    c.true(max(b - a for a, b in zip(track.t, track.t[1:])) > 100.0, "(although two vertices of the track are more than 100 s apart)")
    # the distance of a hole is between its two fixes, not between the vertices around them
    rows = [(float(i), 0.0, 10.0 * i, 10.0, 3.0) for i in range(60)] + [(60.0, 0.0, 591.5, 10.0, 3.0), (361.0, 0.0, 690.4, 10.0, 3.0)]
    rows += [(361.0 + i, 0.0, 690.4 + 10.0 * i, 10.0, 3.0) for i in range(1, 60)]
    track = Track(_rows_fixes(rows))
    c.eq(len(track.holes), 1, "one hole, 301 s")
    c.near(track.holes[0][2], 98.9, 0.05, "its distance is that of its two fixes (98.9 m), not of the vertices around it (100.4 m)")
    c.eq(_outcome(track, 100.0, 1000.0), "ok", "so 98.9 m is not more than 100 m: not a break")
    # timeAt: interpolated on its segment, clamped
    track = Track(_rows_fixes([(100.0 + i, 0.0, 20.0 * i, 20.0, 3.0) for i in range(51)]))
    c.near(track.time_at(0.0), 100.0, 1e-9, "timeAt(0)")
    c.near(track.time_at(10.0), 100.5, 1e-6, "timeAt(10): halfway along the first segment is halfway in time")
    c.near(track.time_at(30.0), 101.5, 1e-6, "timeAt(30)")
    c.near(track.time_at(990.0), 149.5, 1e-6, "timeAt(990)")
    c.near(track.time_at(track.length), 150.0, 1e-9, "timeAt(L)")
    c.near(track.time_at(-50.0), 100.0, 1e-9, "clamped below")
    c.near(track.time_at(5000.0), 150.0, 1e-9, "clamped above")
    track = Track(_rows_fixes([(0.0, 0.0, 0.0, 10.0, 3.0), (2.0, 0.0, 100.0, 10.0, 3.0), (3.0, 0.0, 200.0, 10.0, 3.0), (5.0, 0.0, 230.0, 10.0, 3.0)]))
    c.near(track.time_at(50.0), 1.0, 1e-6, "unequal speeds: timeAt(50)")
    c.near(track.time_at(150.0), 2.5, 1e-6, "timeAt(150)")
    c.near(track.time_at(215.0), 4.0, 1e-6, "timeAt(215)")
    c.eq(track.time_at(track.s[1]), 2.0, "at a vertex: that vertex's time")
    return c.done()


def own_rules():
    c = Checks("rules 5 to 7 of 11.3: a pin in a hole, a break between the pins, and the order of the rules")
    # --- a real hole of 10 s (the fixes at 61 .. 69 s are missing) on a road driven at 20 m/s
    track = Track(_rows_fixes([(float(i), 0.0, 20.0 * i, 20.0, 3.0) for i in range(151) if not 60 < i < 70]))
    c.eq([(h[0], h[1]) for h in track.holes], [(60.0, 70.0)], "the hole of the recording")
    c.eq((track.t[60], track.t[61]), (60.0, 70.0), "the vertices around it")
    s0, s1 = track.s[60], track.s[61]
    mid = (s0 + s1) / 2.0
    for a, want, what in ((s0, "hole_start", "at the vertex before the hole (the end counts)"), (s1, "hole_start", "at the vertex after it (the end counts)"),
                          (mid, "hole_start", "inside it"), (s0 - 0.01, "ok", "just before it"), (s1 + 0.01, "ok", "just after it")):
        c.eq(_outcome(track, a, a + 1000.0), want, "start pin %s" % what)
    for b, want, what in ((s0, "hole_finish", "at the vertex before the hole"), (s1, "hole_finish", "at the vertex after it"),
                          (mid, "hole_finish", "inside it"), (s0 - 0.01, "ok", "just before it"), (s1 + 0.01, "ok", "just after it")):
        c.eq(_outcome(track, 100.0, b), want, "finish pin %s" % what)
    c.eq(_outcome(track, 100.0, 2500.0), "ok", "a hole of 10 s between the pins is no problem")
    # --- the closed ends, exactly: holes set by hand on a road at 10 m/s (timeAt(s[k]) is exactly t[k] = k)
    for hole, a, want, what in (((30.0, 40.0, 0.0), 30, "hole_start", "t0 = timeAt(a)"), ((30.0, 40.0, 0.0), 40, "hole_start", "t1 = timeAt(a)"),
                                ((30.0, 40.0, 0.0), 41, "ok", "just after t1"), ((30.0, 40.0, 0.0), 29, "ok", "just before t0")):
        track = _hand_track([hole])
        c.eq(_outcome(track, track.s[a], 4000.0), want, "start pin: %s" % what)
    for hole, b, want, what in (((400.0, 410.0, 0.0), 400, "hole_finish", "t0 = timeAt(b)"), ((390.0, 400.0, 0.0), 400, "hole_finish", "t1 = timeAt(b)"),
                                ((400.0, 410.0, 0.0), 399, "ok", "just before t0"), ((390.0, 400.0, 0.0), 401, "ok", "just after t1")):
        track = _hand_track([hole])
        c.eq(_outcome(track, 300.0, track.s[b]), want, "finish pin: %s" % what)
    # --- the thresholds of a break (both numbers must be exceeded) and its two overlap conditions; pins at 30 s and 400 s
    for hole, want, what in (((60.0, 360.0, 500.0), "ok", "exactly 300 s"), ((60.0, 360.001, 500.0), "break", "300.001 s"),
                             ((60.0, 361.0, 100.0), "ok", "exactly 100 m"), ((60.0, 361.0, 100.0001), "break", "100.0001 m"),
                             ((60.0, 361.0, 99.0), "ok", "99 m"), ((60.0, 360.0, 100000.0), "ok", "far apart but only 300 s"),
                             ((60.0, 380.0, 5000.0), "break", "320 s and 5000 m"), ((60.0, 380.0, 5.0), "ok", "320 s but 5 m: the phone slept at one place"),
                             ((60.0, 361.0, 500.0), "break", "301 s and 500 m")):
        c.eq(_outcome(_hand_track([hole]), 300.0, 4000.0), want, "a hole of %s between the pins" % what)
    c.eq(_outcome(_hand_track([(60.0, 361.0, 500.0)]), 4000.0, 4800.0), "ok", "a break before the start pin (t1 < timeAt(a)) is not between the pins")
    c.eq(_outcome(_hand_track([(200.0, 501.0, 500.0)]), 300.0, 1000.0), "ok", "a break after the finish pin (t0 > timeAt(b)) is not between the pins")
    c.eq(_outcome(_hand_track([(10.0, 311.0, 500.0)]), 300.0, 4000.0), "hole_start", "a break that covers the start pin is a hole at the pin first")
    c.eq(_outcome(_hand_track([(160.0, 471.0, 500.0)]), 300.0, 4000.0), "hole_finish", "and one that covers the finish pin a hole at that pin")
    # --- real fixes: the same road driven in two parts, the car moving on `jump` m between them
    def two_parts(gap, jump):
        rows = _line_rows(0.0, 0.0, 100) + _line_rows(100.0 + gap, 2000.0 + jump, 100)
        return Track(_rows_fixes(rows))
    for gap, jump, want in ((301.0, 300.0, "break"), (300.0, 300.0, "ok"), (300.5, 300.0, "break"), (600.0, 99.0, "ok"), (600.0, 101.0, "break"),
                            (3.5, 300.0, "ok"), (50.0, 1000.0, "ok")):
        track = two_parts(gap, jump)
        c.eq(_outcome(track, 500.0, 2000.0 + jump + 500.0), want, "a hole of %g s and %g m between the pins" % (gap, jump))
    track = two_parts(301.0, 300.0)
    c.eq(_outcome(track, 500.0, 1500.0), "ok", "both pins before the break")
    c.eq(_outcome(track, 2400.0, 3400.0), "ok", "both pins after the break")
    # --- the order of the rules: the first to fail gives the category
    c.eq(_outcome(_hand_track([(0.0, 5.0, 0.0)]), 10.0, 1000.0), "near_end", "near_end before hole_start")
    c.eq(_outcome(_hand_track([(95.0, 105.0, 0.0)]), 1000.0, 1100.0), "too_short", "too_short before hole_start")
    c.eq(_outcome(_hand_track([(95.0, 105.0, 0.0)]), 1000.0, 1000.0 + 299.0), "too_short", "(again)")
    c.eq(_outcome(_hand_track([(95.0, 105.0, 0.0), (195.0, 205.0, 0.0)]), 1000.0, 2000.0), "hole_start", "hole_start before hole_finish")
    c.eq(_outcome(_hand_track([(395.0, 405.0, 0.0), (60.0, 361.0, 500.0)]), 300.0, 4000.0), "hole_finish", "hole_finish before break")
    c.eq(_outcome(_hand_track([(25.0, 35.0, 0.0), (60.0, 361.0, 500.0)]), 300.0, 4000.0), "hole_start", "hole_start before break (the break is between the pins too)")
    # --- rule 4 near its edge: the road goes 100 m east to the start pin, E m further, and comes back; the finish pin is 300 m after the start pin, so
    # its distance from the start pin is 2 E - 300 m in a straight line (not exactly 150: that would be a comparison of two computed floats)
    for east, want in ((224.9, "loop"), (224.95, "loop"), (225.05, "ok"), (225.1, "ok"), (200.0, "loop"), (250.0, "ok")):
        points = [(-100.0, 0.0), (east, 0.0), (-100.0, 0.0)]
        rows, t, d = [], 0.0, 0.0
        for (x0, y0), (x1, y1) in zip(points, points[1:]):
            length = math.hypot(x1 - x0, y1 - y0)
            n = int(length // 10.0)
            for i in range(n + 1):
                f = i * 10.0 / length
                rows.append((t, x0 + f * (x1 - x0), y0 + f * (y1 - y0), 10.0, 3.0))
                t += 1.0
            if length - n * 10.0 > 4.0:
                rows.append((t, x1, y1, 10.0, 3.0))
                t += 1.0
        track = Track(_rows_fixes(rows))
        c.eq(_outcome(track, 100.0, 400.0), want, "the pins are 300 m apart on the road and %.2f m apart in a straight line" % abs(2 * east - 300.0))
    big = Road([("line", 100.0), ("arc", 60.0, 360.0), ("line", 600.0)], heading=0.0)
    btrack = Track(road_fixes(big, steady(10.0, 0.0, big.length))[0])
    btrack.holes = [(5.0, 15.0, 0.0)]
    c.eq(_outcome(btrack, 100.0, 100.0 + 2 * math.pi * 60 - 1.0), "loop", "loop before hole_start")
    return c.done()


def _twice():
    """The same 2000 m road driven twice from its start, 600 s apart: drive 1 is s = 0 .. 2000 (t = 0 .. 100 s), drive 2 is s = 4000 .. 6000
    (t = 700 .. 800 s); the 2000 m between them are the jump back to the start (a hole of 600 s)."""
    return Track(_rows_fixes(_line_rows(0.0, 0.0, 100) + _line_rows(700.0, 0.0, 100)))


def own_pairs():
    c = Checks("the first pair that meets the rules (11.3), the category of the first pair when none does, the order of the checks")
    track = _twice()
    c.eq([round(h[2]) for h in track.holes], [2000], "a hole of 2000 m between the two drives")
    ll = lambda x, y: ("at",) + track.geo.latlon(x, y)
    start, finish = ll(2.0, 500.0), ll(2.0, 1500.0)
    # the straight jump from the end of drive 1 back to the start of drive 2 lies on the road too, so it is a pass of its own (inside the hole)
    c.eq([round(p) for p, _ in track.passes(start[1], start[2])], [500, 3500, 4500], "the start coordinate: a pass in each drive and one on the jump")
    c.eq([round(p) for p, _ in track.passes(finish[1], finish[2])], [1500, 2500, 5500], "and the finish coordinate")

    def run(extra, start_pin=start, finish_pin=finish):
        tr = _twice()
        tr.holes = tr.holes + list(extra)
        try:
            res = build_stage_ex(tr, start_pin, finish_pin, "p", "P", 0)
            return (round(res.a), round(res.b))
        except Refused as err:
            return err.category
    c.eq(run([]), (500, 1500), "the first pair is used when it meets the rules")
    c.eq(run([(20.0, 30.0, 200.0)]), (4500, 5500), "start pass of drive 1 in a hole: every pair from 500 is skipped, and (3500, 5500) starts in the big hole")
    c.eq(run([(70.0, 80.0, 0.0)]), (4500, 5500), "finish pass of drive 1 in a hole: (500, 1500), (500, 2500) are holes, (500, 5500) joins two drives")
    c.eq(run([(20.0, 30.0, 200.0), (720.0, 730.0, 0.0)]), "hole_start", "every pair fails: the category of the first pair (hole_start)")
    c.eq(run([(20.0, 30.0, 200.0), (770.0, 780.0, 0.0)]), "hole_start", "the first pair's category (hole_start), not the last pair's (hole_finish)")
    c.eq(run([(70.0, 80.0, 0.0), (770.0, 780.0, 0.0)]), "hole_finish", "the finish pass of each drive in a hole: hole_finish")
    c.eq(run([], finish, start), "hole_finish", "finish before start: a pass of the finish does lie after a pass of the start; the first pair with b > a is (1500, 3500)")
    # wrong_way only when no pass of the finish lies after a pass of the start
    single = Track(_rows_fixes(_line_rows(0.0, 0.0, 150)))
    c.refuses(lambda: build_stage(single, ("at",) + single.geo.latlon(2.0, 1500.0), ("at",) + single.geo.latlon(2.0, 500.0), "p", "P", 0),
              "wrong_way", "one drive, the finish before the start")
    # the pairs are tried in order of a and then of b (not of b and then a). Two passes at each place, the start place at the origin and the finish
    # place 151 m east of it: of the four pairs only (a1, b1) is a loop (149 m apart in a straight line), so (a1, b2) is the first that meets the rules
    # when a comes first, and (a2, b1) when b does
    road = Track(_rows_fixes(_polyline_rows([(0.0, -200.0), (1.0, 0.0), (1.0, 300.0), (-1.0, 300.0), (-1.0, 0.0), (-1.0, -200.0), (150.0, -200.0),
                                              (150.0, 300.0), (152.0, 300.0), (152.0, -300.0)])))
    start2, finish2 = ("at",) + road.geo.latlon(0.0, 200.0), ("at",) + road.geo.latlon(151.0, 200.0)
    starts2, finishes2 = [p for p, _ in road.passes(start2[1], start2[2])], [p for p, _ in road.passes(finish2[1], finish2[2])]
    c.eq(([round(p) for p in starts2], [round(p) for p in finishes2]), ([200, 800], [1347, 1941]), "two passes at each place")
    c.eq([_outcome(road, a, b) for a in starts2 for b in finishes2], ["loop", "ok", "ok", "ok"], "of the four pairs only the first is a loop")
    res = build_stage_ex(road, start2, finish2, "p", "P", 0)
    c.eq((round(res.a), round(res.b)), (200, 1941), "the first pair in order of a and then of b: (a1, b2), not (a2, b1)")
    # the order of the checks (11.3): the recording, the start pin, the finish pin, only then the pairs
    far = ("at",) + single.geo.latlon(500.0, 1000.0)
    nan, inf = ("pos", float("nan")), ("pos", float("inf"))
    good = ("pos", 1000.0)
    for start_pin, finish_pin, want, what in ((nan, far, "near_end", "a start that is not a number, then a finish off the drive"),
                                              (far, nan, "no_pass_start", "a start off the drive, then a finish that is not a number"),
                                              (far, far, "no_pass_start", "both off the drive"), (good, far, "no_pass_finish", "only the finish off the drive"),
                                              (good, nan, "near_end", "only the finish not a number"), (inf, far, "near_end", "an infinite start, a finish off the drive"),
                                              (far, ("pos", float("-inf")), "no_pass_start", "a start off the drive, an infinite finish"),
                                              (nan, nan, "near_end", "both not numbers"), (("pos", 5.0), far, "no_pass_finish", "a start too near the end, a finish off the drive")):
        c.refuses(lambda: build_stage(single, start_pin, finish_pin, "p", "P", 0), want, what)
    c.refuses(lambda: build_stage([], nan, far, "p", "P", 0), "no_drive", "no drive comes before every pin")
    c.refuses(lambda: build_stage(_rows_fixes(_line_rows(0.0, 0.0, 1)), far, inf, "p", "P", 0), "no_drive", "(again, with two fixes)")
    c.true(math.isnan(parse_pin("pos:NaN")[1]) and parse_pin("pos:Infinity")[1] == float("inf") and parse_pin("pos:-Infinity")[1] == float("-inf"),
           "the fixtures' spellings of NaN and the infinities are read")
    return c.done()


def own_guard_as_built():
    c = Checks("the guard (11.6) judges the numbers as built, before they are rounded for the file")
    # three legs 2 w apart: the third leg crosses the start line 2 w from its centre, forward. At 2 w = 35.000045 that is 0.045 mm more than the 35 m
    # of the rule (so, as built, no refusal), but the points of the file are rounded to the millimetre and the stage read back from it is refused
    for two_w, as_built, read_back in ((35.000045, "ok", "guard_start"), (35.000075, "ok", "guard_start"), (34.9999, "guard_start", "guard_start"),
                                       (35.01, "ok", "ok")):
        road = _switchback(two_w / 2.0, legs=3, tail=900.0)
        fixes, _ = road_fixes(road, steady(5.0, 0.0, road.length))
        track = Track(fixes)
        outcomes = []
        for flag in (False, True):
            try:
                build_stage_ex(track, ("pos", 50.0), ("pos", track.length - 100.0), "g", "G", 0, guard_on_read_back=flag)
                outcomes.append("ok")
            except Refused as err:
                outcomes.append(err.category)
        c.eq(outcomes[0], as_built, "legs %.6f m apart: the guard on the numbers as built" % two_w)
        c.eq(outcomes[1], read_back, "legs %.6f m apart: (the stage read back from its file would give this)" % two_w)
    return c.done()


def own_guard_once():
    c = Checks("the guard (11.6) is made on the chosen pair only")
    switch = _switchback(12.0, leg=100.0, legs=3, tail=1500.0)           # drive 1: three legs 12 m apart (x = 0, 12, 24), then north at x = 24
    first = road_fixes(switch, steady(5.0, 0.0, switch.length))[0]
    second = road_fixes(Road([("line", 1800.0)], x=5.0), steady(20.0, 0.0, 1800.0, t0=3600.0))[0]   # drive 2: straight north at x = 5, later
    track = Track(first + second)
    ll = lambda x, y: ("at",) + track.geo.latlon(x, y)
    start, finish = ll(-2.5, 50.0), ll(14.5, 1200.0)
    pa, pb = track.passes(start[1], start[2]), track.passes(finish[1], finish[2])
    c.true(len(pa) >= 2 and len(pb) >= 2, "each coordinate has a pass in each drive (%d and %d passes)" % (len(pa), len(pb)))
    if len(pa) >= 2 and len(pb) >= 2:
        c.eq(_outcome(track, pa[-1][0], pb[-1][0]), "ok", "the pair in the second drive is a good stage")
        c.refuses(lambda: build_stage(track, ("pos", pa[0][0]), ("pos", pb[0][0]), "g", "G", 0), "guard_start",
                  "the pair in the first drive meets the rules and the guard refuses it")
        c.refuses(lambda: build_stage(track, start, finish, "g", "G", 0), "guard_start", "by coordinate: the first pair is the one chosen, and no other is tried")
    return c.done()


def own_guard_edges():
    """11.6 on hand-made stages: every threshold of u exactly, and the start gate first."""
    c = Checks("the guard (11.6): the edges of its two windows, on hand-made stages")
    mark = (3.5, 101.6)

    def verdict(points):
        end = points[-1]
        stage = sc.Stage("h", "H", 0, mark, mark, mark, sc.Gate(0.0, 0.0, 0.0, 25.0), sc.Gate(end[0], end[1], 0.0, 25.0), points)
        try:
            check_guard(stage)
            return "ok"
        except Refused as err:
            return err.category
    # the start gate: the route goes back over the start line at route distance u = 2 h
    for h, want in ((5.0, "ok"), (15.0, "ok"), (15.00005, "guard_start"), (16.0, "guard_start"), (149.99995, "guard_start"), (150.0, "guard_start"),
                    (150.00005, "ok"), (200.0, "ok")):
        c.eq(verdict([(0.0, 0.0), (0.0, -h), (0.0, 0.0), (0.0, 600.0)]), want, "the start line is crossed again at u = %g m" % (2 * h))
    # the finish gate: the route crosses the finish line at u = F, then goes on h m and back; R = F + 2 h
    for f, h, want, what in ((700.0, 150.0, "guard_finish", "u = 0.7 R exactly"), (699.999, 150.0, "ok", "u a little under 0.7 R"),
                             (1000.0, 15.0, "ok", "u = R - 30 exactly"), (1000.0, 15.5, "guard_finish", "u = R - 31"),
                             (1000.0, 14.0, "ok", "u = R - 28"), (500.0, 400.0, "ok", "u = 0.38 R"), (900.0, 100.0, "guard_finish", "u = 0.82 R")):
        c.eq(verdict([(0.0, 0.0), (0.0, f), (0.0, f + h), (0.0, f)]), want, "the finish line is crossed at u = %g of R = %g (%s)" % (f, f + 2 * h, what))
    # both windows hit: the start gate is checked first
    c.eq(verdict([(0.0, 0.0), (0.0, -15.5), (0.0, 0.0), (0.0, 500.0), (0.0, 515.5), (0.0, 500.0)]), "guard_start", "both gates are crossed again: the start is named")
    c.eq(verdict([(0.0, 0.0), (0.0, 500.0), (0.0, 515.5), (0.0, 500.0)]), "guard_finish", "(the finish alone)")
    # "has a forward crossing with ...": it is any of the crossings that counts, not the first one
    c.eq(verdict([(0.0, 0.0), (0.0, -5.0), (0.0, 5.0), (0.0, -50.0), (0.0, 60.0), (0.0, 500.0)]), "guard_start",
         "the start line is crossed forward at u = 10 (outside the window) and again at u = 120 (inside)")
    c.eq(verdict([(0.0, 0.0), (0.0, 120.0), (0.0, -60.0), (0.0, 130.0), (0.0, 50.0), (0.0, 100.0)]), "guard_finish",
         "the finish line is crossed at u = 100 of R = 620 (outside the window) and again at u = 460 (inside)")
    c.eq(verdict([(0.0, 0.0), (0.0, -5.0), (0.0, 5.0), (0.0, 600.0)]), "ok", "a single crossing at u = 10 is outside the window")
    c.eq(verdict([(0.0, 0.0), (0.0, 120.0), (0.0, 60.0), (0.0, 100.0)]), "ok", "the finish line is crossed at u = 100 of R = 220 only: before 0.7 R")
    return c.done()


def own_notes():
    c = Checks("what the builder also says (11.7): the gaps")
    track = Track(_rows_fixes(_line_rows(0.0, 0.0, 50) + _line_rows(75.0, 1500.0, 100)))        # no fix for 25 s between 1000 m and 1500 m
    res = build_stage_ex(track, ("pos", 200.0), ("pos", 2500.0), "n", "N", 0)
    c.eq(len(res.gaps), 1, "a segment between the pins with 25 s and 500 m is a gap")
    if res.gaps:
        c.near(res.gaps[0][0], 800.0, 1e-6, "reported 800 m into the stage")
        c.near(res.gaps[0][1], 25.0, 1e-9, "25 s")
        c.near(res.gaps[0][2], 500.0, 1e-3, "500 m")
    c.true(any("GPS was lost" in note for note in res.notes), "and it is said")
    for seconds, metres, count in ((20.0, 500.0, 0), (20.5, 500.0, 1), (25.0, 99.0, 0), (25.0, 101.0, 1)):
        tr = Track(_rows_fixes(_line_rows(0.0, 0.0, 50) + _line_rows(50.0 + seconds, 1000.0 + metres, 100)))
        c.eq(len(build_stage_ex(tr, ("pos", 200.0), ("pos", 2300.0), "n", "N", 0).gaps), count, "%g s and %g m: %d gap(s)" % (seconds, metres, count))
    c.eq(len(build_stage_ex(track, ("pos", 1600.0), ("pos", 2500.0), "n", "N", 0).gaps), 0, "a gap before the start pin is not reported")
    c.eq(len(build_stage_ex(track, ("pos", 200.0), ("pos", 800.0), "n", "N", 0).gaps), 0, "nor one after the finish pin")
    # a gap is judged on the vertices, so a stretch of fixes that the track leaves out (hAcc 80) is one although it is no hole
    rows = _line_rows(0.0, 0.0, 49) + [(50.0 + i, 0.0, 1000.0 + 20.0 * i, 20.0, 80.0) for i in range(25)] + _line_rows(75.0, 1500.0, 100)
    track = Track(_rows_fixes(rows))
    c.eq(track.holes, [], "25 s of fixes with hAcc 80, one a second: no hole")
    res = build_stage_ex(track, ("pos", 200.0), ("pos", 2500.0), "n", "N", 0)
    c.eq(len(res.gaps), 1, "but a gap: the vertices around it are 26 s and 520 m apart")
    if res.gaps:
        c.near(res.gaps[0][0], 780.0, 1e-6, "which begins 780 m into the stage")
    res = build_stage_ex(track, ("pos", 1200.0), ("pos", 2500.0), "n", "N", 0)        # a pin inside the segment: it is cut, and still counts (a reading)
    c.eq([round(g[0], 3) for g in res.gaps], [0.0], "a gap cut by the start pin is reported, from the pin")
    return c.done()


def own_groups(real_log, seed):
    groups = [own_coords(), own_coords_roundtrip(seed), own_coords_fuzz(seed), own_track(), own_holes(), own_passes(), own_build(), own_refusals(),
              own_pairs(), own_rules(), own_guard(), own_guard_crossing_rules(), own_guard_edges(), own_guard_once(), own_guard_as_built(), own_run_check(),
              own_guard_read_back_equivalence(seed), own_notes(), own_text_roundtrip(), own_real_inputs(real_log)]
    return groups


def run_selftest(fixtures, real_log, seed, own):
    t0 = time.time()
    groups, found = fixture_groups(fixtures, real_log)
    if own:
        for make in (lambda: own_groups(real_log, seed),):
            try:
                groups += make()
            except Exception as err:                                  # a bug in this script must show, not hide the other groups
                import traceback
                traceback.print_exc()
                g = _group("own checks")
                g.diffs.append("raised %s: %s" % (type(err).__name__, err))
                groups.append(g)
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
            for d in g.diffs[:sc.MAX_SHOWN]:
                print("        " + d)
            if len(g.diffs) > sc.MAX_SHOWN:
                print("        ... and %d more" % (len(g.diffs) - sc.MAX_SHOWN))
    print("\n%d PASS, %d FAIL, %d SKIP (%d groups, %.1f s)" % (n_pass, n_fail, n_skip, len(groups), time.time() - t0))
    if not found:
        print("fixtures not found yet: nothing was compared with the expected results of %s" % os.path.join(fixtures, "pins"))
    if n_fail:
        return 1
    return 0 if found else 2


# =====================================================================================================================================
# The command line
# =====================================================================================================================================

def cmd_coords(args):
    try:
        lat, lon = parse_coords(args.text)
    except Refused as err:
        print("refused: %s" % err.message)
        return 1
    print("ok %.9f %.9f" % (lat, lon))
    print("shown as %s" % format_coords(lat, lon))
    return 0


def cmd_build(args):
    path = DEFAULT_REAL_LOG if args.session == "REAL" else args.session
    try:
        ses = sc.read_session(path)
    except OSError as err:
        print("Cannot read %s: %s" % (path, err))
        return 2
    try:
        start_pin, finish_pin = parse_pin(args.start), parse_pin(args.finish)
    except ValueError as err:
        print(err)
        return 2
    created = args.created if args.created is not None else (ses.start_utc_ms or 0)
    stage_id = args.id or "pin_" + re.sub("[^A-Za-z0-9_-]", "_", os.path.splitext(ses.name)[0])[:56]
    name = sc.plain(args.name if args.name else "Pins on " + ses.name)[:60]
    try:
        result = build_stage_ex(ses.fixes, start_pin, finish_pin, stage_id, name, created)
    except Refused as err:
        print("refused: %s: %s" % (err.category, err.message))
        return 1
    except ValueError as err:
        print("Cannot make the stage file: %s" % err)
        return 2
    text = result.stage.text()
    if args.out:
        with open(args.out, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text)
        print("wrote %s: stage %s \"%s\", %.1f m, start at %.1f m, finish at %.1f m of a %d-vertex track of %.1f m"
              % (args.out, result.stage.id, result.stage.name, result.stage.length_m, result.a, result.b, result.track.n_vertices,
                 result.track.length), file=sys.stderr)
    else:
        sys.stdout.write(text)
    for note in result.notes:
        print("note: " + note, file=sys.stderr)
    return 0


def cmd_selftest(args):
    explicit = args.real_log is not None
    return run_selftest(args.fixtures, args.real_log if explicit else DEFAULT_REAL_LOG, args.seed, not args.no_own)


def make_parser():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0].strip(), formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="command")
    sub.required = True
    p = sub.add_parser("coords", help="read a place from text (11.8)")
    p.add_argument("text", metavar="TEXT")
    p.set_defaults(func=cmd_coords)
    p = sub.add_parser("build", help="make a stage from two pins on a recording")
    p.add_argument("session", metavar="SESSION.csv", help="a session CSV (or REAL for the Sepang recording in data/)")
    p.add_argument("start", metavar="START", help="pos:METRES or at:LAT,LON")
    p.add_argument("finish", metavar="FINISH", help="pos:METRES or at:LAT,LON")
    p.add_argument("--id", help="stage id (default: pin_ and the file name)")
    p.add_argument("--name", help="stage name, at most 60 characters")
    p.add_argument("--created", type=int, help="created_utc_ms (default: the recording's start time, or 0)")
    p.add_argument("--out", help="write the stage file here instead of printing it")
    p.set_defaults(func=cmd_build)
    p = sub.add_parser("selftest", help="compare with analysis/stage_fixtures/pins and run this script's own checks")
    p.add_argument("--fixtures", default=DEFAULT_FIXTURES, metavar="DIR", help="the stage_fixtures folder (default: next to this script)")
    p.add_argument("--real-log", metavar="CSV", help="the real Sepang recording (default: data/sepang-2026-10-13/session_20261013_095638.csv)")
    p.add_argument("--seed", type=int, default=2026, help="seed of the random checks")
    p.add_argument("--no-own", action="store_true", help="only the fixture folder")
    p.set_defaults(func=cmd_selftest)
    return ap


def main(argv=None):
    args = make_parser().parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())

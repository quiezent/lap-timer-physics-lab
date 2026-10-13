#!/usr/bin/env python3
"""
check_log.py - is this recording in the log format, and will the analysis be able to use it?   (docs/log-format.md)

    python analysis/check_log.py session.csv [--quiet]

Run it on the first file a new recorder (an iPhone app, a converter, a new Android build) produces.  It checks what goes wrong most often:
units (g instead of m/s^2, km/h instead of m/s, deg/s instead of rad/s), the sign of the accelerometer, the clocks, rates, gaps and
row counts.  Each line is PASS, WARN or FAIL; the exit code is 1 if anything FAILs.  It reads the file with the same loader the analysis
uses (analyze.load_session), so a file that passes here is one the analysis can open.
"""
import argparse
import math
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import analyze as AZ                                                    # noqa: E402

G0 = 9.80665


class Report:
    def __init__(self, quiet):
        self.quiet = quiet
        self.n = dict(PASS=0, WARN=0, FAIL=0)

    def add(self, level, what, detail=""):
        self.n[level] += 1
        if self.quiet and level == "PASS":
            return
        print("%-5s %-34s %s" % (level, what, detail))


def raw_counts(path):
    c = {}
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            if line and line[0] not in "#\r\n":
                k = line.split(",", 1)[0]
                c[k] = c.get(k, 0) + 1
    return c


def rate(t):
    if len(t) < 3:
        return float("nan"), float("nan")
    d = np.diff(t)
    return 1.0 / float(np.median(d)), float(d.max())


def local_xy(lat, lon):
    lat0 = math.radians(float(np.median(lat)))
    return (np.radians(lon - lon[0]) * 6371000.0 * math.cos(lat0), np.radians(lat - lat[0]) * 6371000.0)


def check(path, quiet=False):
    R = Report(quiet)
    D = AZ.load_session(path)
    A, Gy, L, S, E, K = D["A"], D["G"], D["L"], D["S"], D["E"], D["K"]
    raw = raw_counts(path)
    print("%s: %s" % (os.path.basename(path), ", ".join("%s %d" % (k, raw[k]) for k in sorted(raw))))

    # ---- structure
    for kind, arr in (("A", A), ("G", Gy), ("L", L)):
        if len(arr) == 0:
            R.add("FAIL", "%s rows present" % kind, "none found (field counts: A and G 5, L 12, S 4, K 4)")
        else:
            lost = raw.get(kind, 0) - len(arr)
            R.add("PASS" if lost <= 0.002 * raw.get(kind, 1) else "WARN", "%s rows readable" % kind,
                  "%d kept%s" % (len(arr), "" if lost <= 0 else ", %d dropped (repeated or unreadable)" % lost))
    if D["bad"]:
        R.add("WARN", "unknown or malformed rows", "%d ignored" % D["bad"])
    if len(A) == 0 or len(Gy) == 0 or len(L) == 0:
        return R
    names = [e[1] for e in E]
    R.add("PASS" if names[:1] == ["START"] else "WARN", "first event is START", "events: %s" % (", ".join(sorted(set(names))) or "none"))
    R.add("PASS" if names[-1:] == ["STOP"] else "WARN", "last event is STOP", "" if names[-1:] == ["STOP"] else "no STOP row: the recording was cut short, the last second may be missing")

    # ---- rates and gaps
    for kind, arr, lo in (("A", A, 40.0), ("G", Gy, 40.0)):
        hz, gap = rate(arr[:, 0])
        R.add("PASS" if hz >= lo and gap < 1.0 else "WARN", "%s rate" % kind, "%.0f Hz, longest gap %.2f s" % (hz, gap) + ("" if hz >= lo else "  (want 50 Hz or more, ideally 100)"))
    hz, gap = rate(L[:, 0])
    n_gaps = int(np.sum(np.diff(L[:, 0]) > 5.0))
    R.add("PASS" if hz >= 0.9 else "WARN", "GPS rate", "%.2f Hz, longest gap %.1f s, %d gaps over 5 s" % (hz, gap, n_gaps))
    dur = float(max(A[-1, 0], Gy[-1, 0], L[-1, 0]) - min(A[0, 0], Gy[0, 0], L[0, 0]))
    R.add("PASS", "duration", "%.1f min" % (dur / 60.0))

    # ---- units
    mag = np.linalg.norm(A[:, 1:4], axis=1)
    # A car driven hard feels more than 1 g (braking and cornering add to gravity), so the median is not the number to look at: the quietest
    # 2 % of the samples should be near gravity (crests, kerbs, vibration and a sensor that reads a few per cent low pull it below 9.81).
    lo, med = float(np.percentile(mag, 2)), float(np.median(mag))
    if 7.0 <= lo <= 11.5:
        R.add("PASS", "accelerometer units", "quietest 2%% of |a| = %.2f m/s^2 (gravity is 9.81), median %.2f" % (lo, med))
    elif 0.7 <= lo <= 1.3:
        R.add("FAIL", "accelerometer units", "quietest 2%% of |a| = %.2f: this looks like g, not m/s^2 (multiply by 9.80665)" % lo)
    else:
        R.add("FAIL", "accelerometer units", "quietest 2%% of |a| = %.2f: expected about 9.8 m/s^2" % lo)
    gm = np.linalg.norm(Gy[:, 1:4], axis=1)
    p99 = float(np.percentile(gm, 99))
    R.add("PASS" if p99 < 8.0 else "FAIL", "gyroscope units", "99th percentile |w| = %.2f %s" % (p99, "rad/s" if p99 < 8.0 else "-> looks like deg/s, not rad/s (multiply by pi/180)"))
    sp = L[:, 4]
    ok = (sp >= 0)
    x, y = local_xy(L[:, 1], L[:, 2])
    dt = np.diff(L[:, 0])
    dist = np.hypot(np.diff(x), np.diff(y))
    sel = (dt > 0.5) & (dt < 2.0) & (sp[1:] > 5) & (sp[:-1] > 5) & ok[1:] & ok[:-1]
    if sel.sum() >= 20:
        ratio = float(np.median(0.5 * (sp[1:][sel] + sp[:-1][sel]) / (dist[sel] / dt[sel])))
        if 0.9 <= ratio <= 1.1:
            R.add("PASS", "GPS speed units", "reported speed / distance between fixes = %.2f (m/s)" % ratio)
        else:
            guess = {3.6: "km/h (divide by 3.6)", 2.237: "mph (divide by 2.237)", 1.944: "knots (divide by 1.944)"}
            near = min(guess, key=lambda k: abs(k - ratio))
            R.add("FAIL", "GPS speed units", "reported speed / distance between fixes = %.2f; %s" % (ratio, "this looks like " + guess[near] if abs(near - ratio) < 0.25 else "expected 1.0 (m/s)"))
    else:
        R.add("WARN", "GPS speed units", "not enough moving fixes to check")
    inside = (np.abs(L[:, 1]) <= 90).all() and (np.abs(L[:, 2]) <= 180).all()
    R.add("PASS" if inside else "FAIL", "GPS coordinates", "latitude/longitude in degrees" if inside else "out of range: expected degrees")
    hacc = L[:, 6][L[:, 6] >= 0]
    if len(hacc):
        R.add("PASS" if np.median(hacc) < 10 else "WARN", "GPS accuracy", "median horizontal accuracy %.1f m" % np.median(hacc))

    # ---- the sign of the accelerometer, from the file itself.
    # up = mean accelerometer vector (specific force points up).  Yaw rate about 'up' is positive when the car turns left, i.e. when the GPS
    # bearing falls.  With the Android sign convention the two are negatively correlated; a flipped accelerometer flips the correlation.
    up = A[:, 1:4].mean(axis=0)
    if np.linalg.norm(up) > 0.5 * G0:
        up = up / np.linalg.norm(up)
        w_up = Gy[:, 1:4] @ up
        t = L[:, 0]
        brg = L[:, 5]
        good = (brg >= 0) & (sp > 8)
        rows = np.where(good[1:] & good[:-1] & (np.diff(t) > 0.5) & (np.diff(t) < 2.0))[0]
        dth = np.array([(brg[i + 1] - brg[i] + 180.0) % 360.0 - 180.0 for i in rows])
        dtt = np.array([t[i + 1] - t[i] for i in rows])
        cs = np.concatenate([[0.0], np.cumsum(0.5 * (w_up[1:] + w_up[:-1]) * np.diff(Gy[:, 0]))])
        turn = np.array([np.interp(t[i + 1], Gy[:, 0], cs) - np.interp(t[i], Gy[:, 0], cs) for i in rows])        # integrated yaw over each GPS interval, rad
        if len(rows) >= 30 and np.std(dth) > 0.5:
            r = float(np.corrcoef(np.degrees(turn), dth)[0, 1])
            if r < -0.6:
                R.add("PASS", "accelerometer sign", "yaw about 'up' against GPS bearing change: r = %.2f (as expected, negative)" % r)
            elif r > 0.6:
                R.add("FAIL", "accelerometer sign", "r = %.2f: the accelerometer sign looks flipped (a phone flat on a table should read +9.81 on z)" % r)
            else:
                R.add("WARN", "accelerometer sign", "r = %.2f: no clear relation (a phone that moved a lot, or too little turning)" % r)
        else:
            R.add("WARN", "accelerometer sign", "not enough turning to check")
        R.add("PASS" if abs(np.linalg.norm(A[:, 1:4].mean(axis=0)) - G0) < 1.2 else "WARN", "gravity in the vector mean",
              "|mean a| = %.2f m/s^2 (about 9.8 if the phone stayed put)" % np.linalg.norm(A[:, 1:4].mean(axis=0)))

    # ---- clocks
    for kind in ("A", "G"):
        lags = np.array([k[2] for k in K if k[1] == kind])
        if len(lags):
            R.add("PASS" if lags.min() >= 0 and np.median(lags) < 150 else "WARN", "%s delivery lag (K rows)" % kind,
                  "median %.0f ms, max %.0f ms%s" % (np.median(lags), lags.max(), "  <- negative: a different clock?" if lags.min() < 0 else ""))
        else:
            R.add("WARN", "%s delivery lag (K rows)" % kind, "no K rows; the analysis cannot check that the clocks agree")
    inside_t = (L[:, 0].min() >= min(A[0, 0], Gy[0, 0]) - 5.0) and (L[:, 0].max() <= max(A[-1, 0], Gy[-1, 0]) + 5.0)
    R.add("PASS" if inside_t else "FAIL", "GPS and sensors share a clock", "GPS from %.1f to %.1f s, sensors from %.1f to %.1f s" % (L[0, 0], L[-1, 0], A[0, 0], A[-1, 0]))
    cal = [e for e in E if e[1] == "CAL"]
    if cal:
        good = len(cal[0][2]) >= 8 and cal[0][2][7] >= 1
        R.add("PASS" if good else "WARN", "stationary calibration", "car judged still" if good else "the car was not still (CAL ok = 0): the analysis fits the orientation from the drive")
    else:
        R.add("WARN", "stationary calibration", "none: the analysis will fit the orientation from the drive")
    laps = [e for e in E if e[1] == "LAP"]
    if laps:
        R.add("PASS", "LAP events", "%d laps timed by the recorder" % max(0, len(laps) - 1))
    return R


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("csv")
    ap.add_argument("--quiet", action="store_true", help="print only warnings and failures")
    a = ap.parse_args(argv)
    R = check(a.csv, a.quiet)
    print("\n%d pass, %d warn, %d fail" % (R.n["PASS"], R.n["WARN"], R.n["FAIL"]))
    return 1 if R.n["FAIL"] else 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ultimate.py - stitch your best demonstrated sections into an "ultimate lap" and measure every lap against it.

    python ultimate.py session_a.csv session_b.csv ... [--out DIR] [--sf LAT,LON]

Needs numpy, plus matplotlib for the figures (--no-plots skips them), and analyze.py next to this file.

What it does
  1. Runs analyze.py on every session (speed, long. g, lateral g, curvature, GPS).
  2. Builds a map of the circuit from your own fastest lap and puts every lap on it: progress along the track,
     from the GPS fixes (position) and the integrated speed (what happens between fixes).
  3. Cuts the lap in the middle of each long straight. A section is "one corner or a few linked corners and the
     straights around them". The cut points are where speed is high and lap-to-lap alignment errors cost least time.
  4. Times every section of every lap. A lap that leaves a corner faster gets a free head start into the next
     section, so each section time is corrected for its entry and exit speed (a "price" in seconds per m/s).
  5. Picks the best section of every kind from all laps, shrinks lucky outliers back towards the average
     (so measurement noise is not mistaken for skill) and adds them up: the ultimate lap.
  6. Reports each lap's gap to the ultimate lap by section, the driver-input differences by corner (brake point,
     peak braking, minimum speed, throttle pick-up, exit speed) and how early in a lap the lap time can be predicted.

Honest limits: speeds, brake and throttle are inferred from a phone's GPS (about 1 Hz) and motion sensors. Section
times are good to roughly +-0.05 s; the report prints its own error bars. The ultimate lap is "the best you have
shown, in pieces", not a physical limit. See the README for what was and was not tested.
"""
import argparse
import contextlib
import hashlib
import io
import math
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import analyze as A                                    # noqa: E402

G0 = A.G0
FS = A.FS

# ------------------------------------------------------------------ tunables
KNOT_DT = 6.0            # s between the knots of the progress-offset spline
SIG_DD = 2.0             # m, prior on how fast the offset between integrated speed and GPS progress may bend
GRID = 1.0               # m, distance grid of the aligned laps
MIN_STRAIGHT = 80.0      # m, shortest straight that gets a cut
STRAIGHT_K = 1.0 / 700.0  # 1/m, curvature below this is "straight"
SLOW_LAP = 1.07          # laps slower than this times the typical fast lap are in/out/traffic laps
POOR_FIX_S = 3.0         # s, a cut with no GPS fix this close is "poorly aligned"
SLOW_V = 0.93            # a lap crossing a cut slower than this fraction of the typical speed is not a clean lap there
CLOSE_V = 12.0           # m/s, with fewer than 3 fast loops, a loop whose end speed differs this much from its start is an out/in lap
SIG_V = 0.15 ** 2        # (m/s)^2, variance of the fused speed at a cut
SIG_FLOOR = 1.2          # m, floor on the position uncertainty of a cut (correlated GPS error does not average away)
PRICE_MAX_D = 600.0      # m, a speed gained at a cut is followed no further than this
BRAKE_STOP_G = -0.20     # g, the carried-over speed advantage ends where the reference lap starts braking harder than this
MAX_APEX_DIST = 25.0     # m, a model corner further than this from your map is dropped (its part of the track is not on your map)
APEX_DEADBAND = 8.0      # m, a model apex within this of where your laps actually slow most is left where the model put it
APEX_REACH = 90.0        # m, how far from the model's apex the slowest point of your laps is looked for


# ====================================================================================================
# sessions
# ====================================================================================================
def _why(e):
    """One line saying why a file could not be used."""
    msg = str(e.code) if isinstance(e, SystemExit) else "%s: %s" % (type(e).__name__, e)
    return " ".join(msg.split())[:200]


def load_sessions(paths, sf=None, workdir=None, quiet=True, skipped=None):
    """Run the analyzer on every file and keep what the engine needs.  With a `skipped` list, a file that cannot be used
    is noted there (name, reason) and left out; without one the error is raised."""
    sessions = []
    origin = None
    names = set()
    seen = {}                                              # content hash -> file name, to catch the same recording given twice
    for ip, path in enumerate(paths):
        name = os.path.splitext(os.path.basename(path))[0]
        try:
            with open(path, "rb") as fh_:
                digest = hashlib.sha1(fh_.read()).hexdigest()
        except OSError:
            digest = None
        if digest is not None and digest in seen and skipped is not None:
            skipped.append((os.path.basename(path), "same recording as %s" % seen[digest], None))
            continue
        if name in names:                                  # two folders, same file name: keep the sessions apart
            name = "%s_%d" % (name, ip + 1)
        out = os.path.join(workdir or os.path.dirname(os.path.abspath(path)), name + "_analysis")
        try:
            buf = io.StringIO()
            with (contextlib.redirect_stdout(buf) if quiet else contextlib.nullcontext()):
                res = A.analyze(path, out, sf=sf, plots=False)
            Lg = res["L"]
            lat0, lon0 = Lg[0, 1], Lg[0, 2]
            org = origin if origin is not None else (lat0, lon0)
            tau = float(res["fa"]["tau"])
            x, y = A.to_enu(Lg[:, 1], Lg[:, 2], org[0], org[1])
            tg = res["tg"]
            sess = dict(path=path, name=name, res=res, tg=tg, v=res["v"],
                        pint=res["s"], lg=res["a_long"] / G0, lat=res["a_lat"] / G0, kap=res["kap"],
                        fx=x, fy=y, ft=Lg[:, 0] - tau, fv=np.maximum(Lg[:, 4], 0), fh=Lg[:, 6], fa=Lg[:, 0] + Lg[:, 10] * 1e-3,
                        fsa=Lg[:, 7], along=res["a_long"], tau=tau, origin=org, analysis_dir=out)
            # offset between the analyzer's local frame (first fix of this session) and the common frame
            dx, dy = A.to_enu(np.array([lat0]), np.array([lon0]), org[0], org[1])
            sess["off"] = (float(dx[0]), float(dy[0]))
        except (SystemExit, Exception) as e:               # KeyboardInterrupt is not caught
            if skipped is None:
                raise
            skipped.append((os.path.basename(path), _why(e), e))
            continue
        origin = org
        names.add(name)
        if digest is not None:
            seen[digest] = os.path.basename(path)
        sessions.append(sess)
    return sessions, origin


# ====================================================================================================
# reference path (the map), projection and progress
# ====================================================================================================
def _resample_closed(xy, step):
    P = np.vstack([xy, xy[:1]])
    d = np.hypot(*np.diff(P, axis=0).T)
    keep = np.r_[True, d > 1e-6]
    P = np.vstack([P[:-1][keep[:-1]], P[-1:]]) if not keep.all() else P
    d = np.hypot(*np.diff(P, axis=0).T)
    s = np.r_[0, np.cumsum(d)]
    sn = np.arange(0, s[-1], step)
    return np.column_stack([np.interp(sn, s, P[:, 0]), np.interp(sn, s, P[:, 1])]), float(s[-1])


class RefPath:
    """A closed reference line with arc length and curvature."""

    def __init__(self, xy, step=2.0, smooth_m=6.0):
        pts, _ = _resample_closed(xy, step)
        sig = max(smooth_m / step, 0.5)
        k = int(4 * sig) + 1
        w = np.exp(-0.5 * (np.arange(-k, k + 1) / sig) ** 2)
        w /= w.sum()
        sm = np.column_stack([np.convolve(np.r_[pts[-k:, i], pts[:, i], pts[:k, i]], w, mode="same")[k:-k] for i in (0, 1)])
        self.xy, self.L = _resample_closed(sm, step)
        self.n = len(self.xy)
        self.ds = self.L / self.n
        self.s = np.arange(self.n) * self.ds
        d = np.roll(self.xy, -1, axis=0) - self.xy
        th = np.unwrap(np.arctan2(d[:, 1], d[:, 0]))
        dth = (np.diff(np.r_[th, th[0]]) + np.pi) % (2 * np.pi) - np.pi
        self.kap_raw = dth / np.maximum(np.hypot(d[:, 0], d[:, 1]), 1e-6)       # +ve = left turn
        sg = 16.0 / self.ds
        kk = int(4 * sg) + 1
        wk = np.exp(-0.5 * (np.arange(-kk, kk + 1) / sg) ** 2)
        wk /= wk.sum()
        self.kap = np.convolve(np.r_[self.kap_raw[-kk:], self.kap_raw, self.kap_raw[:kk]], wk, mode="same")[kk:-kk]

    def point(self, p):
        """x, y at (array of) arc length p (any real, wraps)."""
        q = np.mod(p, self.L) / self.ds
        i = np.floor(q).astype(int) % self.n
        f = q - np.floor(q)
        j = (i + 1) % self.n
        return self.xy[i, 0] * (1 - f) + self.xy[j, 0] * f, self.xy[i, 1] * (1 - f) + self.xy[j, 1] * f

    def nearest_global(self, x, y):
        d2 = (self.xy[:, 0] - x) ** 2 + (self.xy[:, 1] - y) ** 2
        i = int(np.argmin(d2))
        return i, math.sqrt(float(d2[i]))

    def project_near(self, x, y, p_exp, half):
        """Project (x, y) onto the line near unwrapped arc length p_exp (within +-half metres).
        Returns unwrapped arc length and the distance to the line."""
        base = (p_exp % self.L) / self.ds
        nw = int(half / self.ds) + 2
        idx = (np.floor(base).astype(int) + np.arange(-nw, nw + 1)) % self.n
        d2 = (self.xy[idx, 0] - x) ** 2 + (self.xy[idx, 1] - y) ** 2
        k = int(np.argmin(d2))
        i = int(idx[k])
        best = (float("inf"), 0.0, 0)
        for ii in (i - 1, i):
            a = self.xy[ii % self.n]
            b = self.xy[(ii + 1) % self.n]
            ab = b - a
            tt = float(np.clip(np.dot(np.array([x, y]) - a, ab) / max(float(np.dot(ab, ab)), 1e-9), 0.0, 1.0))
            c = a + tt * ab
            dd = math.hypot(x - c[0], y - c[1])
            if dd < best[0]:
                best = (dd, tt, ii % self.n)
        dd, tt, ii = best
        p_loc = (ii + tt) * self.ds
        P = p_loc + self.L * round((p_exp - p_loc) / self.L)
        return float(P), float(dd)


def build_reference(sess):
    res = sess["res"]
    best = res["best"]
    if best is None:
        raise SystemExit("%s: no complete fast lap found, nothing to build a map from." % sess["name"])
    tt, px, py = res["tt"], res["px"], res["py"]
    sel = (tt >= best["ta"]) & (tt <= best["tb"])
    xy = np.column_stack([px[sel] + sess["off"][0], py[sel] + sess["off"][1]])
    return RefPath(xy)


def track_fixes(ref, sess, max_dist=40.0):
    """Unwrapped progress along `ref` for every GPS fix (NaN where the car is not on this circuit)."""
    X, Y, T, V = sess["fx"], sess["fy"], sess["ft"], sess["fv"]
    n = len(X)
    P = np.full(n, np.nan)
    D = np.full(n, np.nan)
    k0 = None
    for k in range(n):
        if V[k] > 8.0:
            i, d = ref.nearest_global(X[k], Y[k])
            if d < 0.75 * max_dist:
                k0, p_hint = k, ref.s[i]
                break
    if k0 is None:
        return P, D
    P_prev, t_prev = p_hint, T[k0]
    for k in range(k0, n):
        step = V[k] * max(T[k] - t_prev, 0.0) if k > k0 else 0.0
        p_exp = P_prev + step
        p, d = ref.project_near(X[k], Y[k], p_exp, 40.0 + 0.7 * step)
        if d > max_dist:
            P_prev, t_prev = p_exp, T[k]
            continue
        P[k], D[k] = p, d
        P_prev, t_prev = p, T[k]
    return P, D


def fit_progress(sess, P, sig_obs=2.0):
    """Progress(t) = integrated speed + a smooth offset fitted to the GPS progress (robust Bayesian smoothing spline).
    Returns dict with the 100 Hz progress, per-fix residuals and the offset's posterior variance."""
    tg, pint = sess["tg"], sess["pint"]
    ok = np.isfinite(P)
    t = sess["ft"][ok]
    y = P[ok] - np.interp(t, tg, pint)
    if len(t) < 20:
        raise SystemExit("%s: too few GPS fixes on the circuit to build progress." % sess["name"])
    knots = np.arange(t[0] - KNOT_DT, t[-1] + 2 * KNOT_DT, KNOT_DT)
    nk = len(knots)
    ia = np.clip(np.searchsorted(knots, t, side="right") - 1, 0, nk - 2)
    fr = (t - knots[ia]) / KNOT_DT
    H = np.zeros((len(t), nk))
    H[np.arange(len(t)), ia] = 1 - fr
    H[np.arange(len(t)), ia + 1] = fr
    Dm = np.zeros((nk - 2, nk))
    for i in range(nk - 2):
        Dm[i, i:i + 3] = [1, -2, 1]
    # weights from the reported accuracy (about hAcc/1.6 per axis), floored
    hs = sess["fh"][ok]
    sig = np.where(hs > 0, np.clip(hs / 1.6, 1.0, 6.0), sig_obs)
    w = 1.0 / sig ** 2
    d = np.zeros(nk)
    for it in range(8):
        r = y - H @ d
        c = 1.5 * sig
        wr = np.where(np.abs(r) > c, c / np.maximum(np.abs(r), 1e-9), 1.0) * w
        Hm = H.T @ (H * wr[:, None]) + Dm.T @ Dm / SIG_DD ** 2 + 1e-9 * np.eye(nk)
        d = np.linalg.solve(Hm, H.T @ (wr * y))
    r = y - H @ d
    cov = np.linalg.inv(Hm)
    P_grid = pint + np.interp(tg, knots, d)
    P_mono = np.maximum.accumulate(P_grid) + 1e-7 * np.arange(len(tg))
    return dict(P=P_mono, knots=knots, d=d, cov=cov, resid=r, t_fix=t, sigma_fix=sig,
                t0=float(t[0]), t1=float(t[-1]))


def sigma_at(fit, tq):
    """Posterior std (m) of the progress offset at times tq."""
    knots = fit["knots"]
    nk = len(knots)
    ia = np.clip(np.searchsorted(knots, tq, side="right") - 1, 0, nk - 2)
    fr = np.clip((tq - knots[ia]) / KNOT_DT, 0, 1)
    out = np.empty(len(tq))
    cov = fit["cov"]
    for k in range(len(tq)):
        a, f = ia[k], fr[k]
        v = (1 - f) ** 2 * cov[a, a] + f ** 2 * cov[a + 1, a + 1] + 2 * f * (1 - f) * cov[a, a + 1]
        out[k] = math.sqrt(max(v, 0.0))
    return out


# ====================================================================================================
# cuts (mid-straight) and laps
# ====================================================================================================
def curvature_profile(sessions, fits, ref, smooth_m=12.0):
    """Median signed curvature (1/m, left +) against arc length of `ref`, from the gyro of every lap driven.
    The gyro is far cleaner than the curvature of a path made from 1 Hz GPS fixes, so straights are found from it."""
    L, n = ref.L, ref.n
    rows = []
    for sess, fit in zip(sessions, fits):
        tg, P, kap = sess["tg"], fit["P"], sess["kap"]
        i0 = int(np.searchsorted(tg, fit["t0"]))
        i1 = min(int(np.searchsorted(tg, fit["t1"])), len(tg) - 1)
        if i1 - i0 < 100:
            continue
        Pw, tw = P[i0:i1 + 1], tg[i0:i1 + 1]
        for k in range(int(math.floor(Pw[0] / L)), int(math.floor(Pw[-1] / L)) + 1):
            pg = ref.s + k * L
            ok = (pg >= Pw[0]) & (pg <= Pw[-1])
            if ok.sum() < 0.5 * n:
                continue
            row = np.full(n, np.nan)
            row[ok] = np.interp(np.interp(pg[ok], Pw, tw), tg, kap)
            rows.append(row)
    if not rows:
        return None
    with np.errstate(all="ignore"):
        med = np.nanmedian(np.array(rows), axis=0)
    med = np.where(np.isfinite(med), med, 0.0)
    w = max(int(round(smooth_m / ref.ds)), 1)
    ker = np.ones(w) / w
    pad = np.r_[med[-w:], med, med[:w]]
    return np.convolve(pad, ker, mode="same")[w:-w], len(rows)


def find_cuts(ref, min_len=MIN_STRAIGHT, kthr=STRAIGHT_K, gap_m=24.0):
    """Mid-points of the straights of the reference line (curvature below `kthr`, at least `min_len` long, gaps
    shorter than `gap_m` closed), ordered starting with the longest straight.  Returns (cuts [m], lengths [m])."""
    n = ref.n
    low = np.abs(ref.kap) < kthr
    if low.all() or not low.any():
        return [], []
    start = int(np.argmin(low))                       # rotate so index 0 is a non-straight point
    low2 = np.roll(low, -start)
    # close short gaps between two straight runs
    runs, i = [], 0
    while i < n:
        j = i
        while j < n and low2[j] == low2[i]:
            j += 1
        runs.append([i, j, bool(low2[i])])
        i = j
    for r in range(1, len(runs) - 1):
        if (not runs[r][2]) and (runs[r][1] - runs[r][0]) * ref.ds < gap_m and runs[r - 1][2] and runs[r + 1][2]:
            low2[runs[r][0]:runs[r][1]] = True
    segs, i = [], 0
    while i < n:
        if low2[i]:
            j = i
            while j < n and low2[j]:
                j += 1
            if (j - i) * ref.ds >= min_len:
                segs.append((i, j))
            i = j
        else:
            i += 1
    if not segs:
        return [], []
    segs.sort(key=lambda ab: -(ab[1] - ab[0]))
    longest = segs[0]
    mids = [((0.5 * (a + b) + start) % n) * ref.ds for a, b in segs]
    c0 = ((0.5 * (longest[0] + longest[1]) + start) % n) * ref.ds
    cuts = sorted(mids, key=lambda p: (p - c0) % ref.L)
    lens = {m: (b - a) * ref.ds for m, (a, b) in zip(mids, segs)}
    return cuts, [lens[c] for c in cuts]


def align_lap(sess, fit, lev0, L, grid=GRID):
    """The lap that starts at progress `lev0`, resampled on a distance grid (m from the lap's start)."""
    tg = sess["tg"]
    n = int(round(L / grid))
    p = np.arange(n + 1) * grid
    t = np.interp(lev0 + p, fit["P"], tg)
    return dict(p=p, t=t - t[0], t_abs=float(t[0]), v=np.interp(t, tg, sess["v"]), lg=np.interp(t, tg, sess["lg"]),
                lat=np.interp(t, tg, sess["lat"]), kap=np.interp(t, tg, sess["kap"]))


def make_laps(sess, ref, cuts, fit, floor=None):
    """Cut crossing times, speeds and timing noise for every complete lap of one session (cut 0 starts and ends it)."""
    tg = sess["tg"]
    Pg = fit["P"]
    L = ref.L
    c = np.array(cuts)
    M = len(c)
    rel = (c - c[0]) % L
    i0 = int(np.searchsorted(tg, fit["t0"]))
    i1 = min(int(np.searchsorted(tg, fit["t1"])), len(tg) - 1)
    p_first, p_last = Pg[i0], Pg[i1]
    k_lo = int(math.floor((p_first - c[0]) / L)) - 1
    k_hi = int(math.ceil((p_last - c[0]) / L)) + 1
    laps = []
    for k in range(k_lo, k_hi):
        lev = c[0] + k * L + np.r_[rel, L]
        if lev[0] < p_first or lev[-1] > p_last:
            continue
        tc = np.interp(lev, Pg, tg)
        vc = np.array([float(np.mean(np.interp(tc[j] + np.linspace(-0.15, 0.15, 7), tg, sess["v"]))) for j in range(M + 1)])
        sx = np.sqrt(sigma_at(fit, tc) ** 2 + (SIG_FLOOR if floor is None else floor) ** 2)    # m: fit uncertainty plus the floor
        near = np.array([float(np.min(np.abs(sess["ft"] - t))) for t in tc])
        lap = dict(session=sess["name"], k=k, tc=tc, vc=vc, sx=sx, st=sx / np.maximum(vc, 3.0), poor=near > POOR_FIX_S,
                   time=float(tc[-1] - tc[0]), levels=lev)
        lap["al"] = align_lap(sess, fit, lev[0], L)
        laps.append(lap)
    return laps


def lap_filter(laps):
    """Flag fast laps (not in/out laps or traffic) and, per section, the laps that are clean there: a GPS fix near both
    cuts and a normal speed at both cuts (a lap that lifts on a straight is not a clean sample of the next corner)."""
    if not laps:
        return
    times = np.array([l["time"] for l in laps])
    fastt = np.sort(times)[:max(1, (len(times) + 1) // 2)]
    ref_t = float(np.median(fastt))
    for l in laps:
        l["fast"] = bool(l["time"] <= SLOW_LAP * ref_t)
    if sum(l["fast"] for l in laps) < 3:
        # too few laps to judge the cut speeds against each other (the "typical" lap would be the lap itself), so ask the
        # one thing a flying lap must do: end at about the speed it started at.  An out-lap leaving the pits does not.
        for l in laps:
            if abs(float(l["vc"][-1] - l["vc"][0])) > CLOSE_V:
                l["fast"] = False
    fl = [l for l in laps if l["fast"]]
    if not fl:
        for l in laps:
            l["ok_sec"] = np.zeros(len(l["vc"]) - 1, bool)
            l["clean"] = False
        return
    vmed = np.median(np.array([l["vc"] for l in fl]), axis=0)
    for l in laps:
        vok = l["vc"] >= SLOW_V * vmed
        pk = ~l["poor"]
        l["ok_sec"] = l["fast"] & pk[:-1] & pk[1:] & vok[:-1] & vok[1:]
        l["clean"] = bool(l["ok_sec"].all())


# ====================================================================================================
# speed prices, section values, shrinkage and the ultimate lap
# ====================================================================================================
DEFAULT_ENV = dict(pw=150.0, k=2.5e-4, c=0.20, a_t=8.0, v_knee=24.0)       # generic fast road-car-on-slicks envelope


def speed_envelope(laps, q=0.90, bw=4.0):
    """Upper envelope of forward acceleration against speed (m/s^2 against m/s), from the near-straight running of the
    fast laps: A(v) = min(a_t, pw/v - k v^2 - c).  Falls back to a generic car when there is too little data."""
    vs, aa = [], []
    for l in laps:
        if not l.get("fast"):
            continue
        al = l["al"]
        m = (np.abs(al["lat"]) < 0.20) & (al["v"] > 8.0)
        vs.append(al["v"][m])
        aa.append(al["lg"][m] * G0)
    if not vs:
        return dict(DEFAULT_ENV, fitted=False)
    v, a = np.concatenate(vs), np.concatenate(aa)
    edges = np.arange(8.0, v.max() + bw, bw)
    cen, env = [], []
    for lo, hi in zip(edges[:-1], edges[1:]):
        sel = (v >= lo) & (v < hi)
        if sel.sum() >= 80:
            cen.append(0.5 * (lo + hi))
            env.append(float(np.quantile(a[sel], q)))
    if len(cen) < 5:
        return dict(DEFAULT_ENV, fitted=False)
    cen, env = np.array(cen), np.array(env)
    ik = int(np.argmax(env))
    v_knee = float(cen[ik])
    hi_m = cen >= v_knee
    if hi_m.sum() < 3:
        return dict(DEFAULT_ENV, fitted=False)
    X = np.column_stack([1.0 / cen[hi_m], -cen[hi_m] ** 2, -np.ones(hi_m.sum())])
    coef, *_ = np.linalg.lstsq(X, env[hi_m], rcond=None)
    pw, k, c = float(max(coef[0], 20.0)), float(max(coef[1], 5e-5)), float(max(coef[2], 0.0))
    return dict(pw=pw, k=k, c=c, a_t=float(env[ik]), v_knee=v_knee, fitted=True, cen=cen, env=env)


def env_dAdv(env, v):
    """dA/dv (1/s, negative): how fast a speed advantage melts away on a full-throttle straight."""
    v = np.asarray(v, float)
    d = -(env["pw"] / v ** 2 + 2.0 * env["k"] * v)
    d = np.where(v < env["v_knee"], -0.01, d)           # traction-limited: an advantage barely decays
    return np.clip(d, -0.5, -0.005)


def cut_prices(laps, cuts_rel, env):
    """sigma_c [s per m/s]: the lap time saved, over the rest of the straight, by crossing cut c 1 m/s faster.
    sigma = integral of v0/v^3 * exp(integral dA/dv / v ds) ds from the cut to where the typical lap starts braking."""
    fl = [l for l in laps if l.get("fast")]
    if not fl:
        return np.full(len(cuts_rel), 0.05)
    v_ref = np.median([l["al"]["v"] for l in fl], axis=0)
    g_ref = np.median([l["al"]["lg"] for l in fl], axis=0)
    n = len(v_ref) - 1
    out = []
    for c in cuts_rel:
        i0 = int(round(c / GRID)) % n
        v0 = float(v_ref[i0])
        expo, sig = 0.0, 0.0
        for j in range(int(PRICE_MAX_D / GRID)):
            jj = (i0 + j) % n
            if g_ref[jj] < BRAKE_STOP_G:
                break
            vj = max(float(v_ref[jj]), 5.0)
            sig += v0 / vj ** 3 * math.exp(expo) * GRID
            expo += float(env_dAdv(env, vj)) / vj * GRID
        out.append(sig)
    return np.array(out)


class Ultimate:
    """Section table of all laps, shrinkage, and the stitched ultimate lap."""

    def __init__(self, laps, cuts_rel, env=None, lap_ids=None):
        self.laps = laps
        self.M = len(cuts_rel)
        self.cuts_rel = np.array(cuts_rel)
        self.env = env if env is not None else speed_envelope(laps)
        self.price = cut_prices(laps, cuts_rel, self.env)
        self.build()

    def build(self):
        laps, M, price = self.laps, self.M, self.price
        nb = len(laps)
        self.T = np.full((nb, M), np.nan)
        self.E = np.full((nb, M), np.nan)
        self.se2 = np.full((nb, M), np.nan)
        self.ok = np.zeros((nb, M), bool)
        for b, l in enumerate(laps):
            for i in range(M):
                if not l["ok_sec"][i]:
                    continue
                j = (i + 1) % M
                self.T[b, i] = l["tc"][i + 1] - l["tc"][i]
                self.E[b, i] = self.T[b, i] + price[i] * l["vc"][i] - price[j] * l["vc"][i + 1]
                self.se2[b, i] = l["st"][i] ** 2 + l["st"][i + 1] ** 2 + (SIG_V * (price[i] ** 2 + price[j] ** 2))
                self.ok[b, i] = True
        self.mu, self.V, self.sr2 = np.zeros(M), np.zeros(M), np.zeros(M)
        self.Ehat = np.full((nb, M), np.nan)
        self.rho = np.full((nb, M), np.nan)
        for i in range(M):
            idx = np.where(self.ok[:, i])[0]
            if len(idx) == 0:
                continue
            e = self.E[idx, i]
            self.mu[i] = float(np.median(e))
            if len(idx) > 1:
                self.V[i] = float(np.var(e, ddof=1))
            s2 = float(np.mean(self.se2[idx, i]))
            self.sr2[i] = max(self.V[i] - s2, 0.1 * self.V[i]) if len(idx) > 1 else 0.0
            for b in idx:
                r = self.sr2[i] / (self.sr2[i] + self.se2[b, i]) if self.sr2[i] > 0 else 1.0
                self.rho[b, i] = r
                self.Ehat[b, i] = self.mu[i] + r * (self.E[b, i] - self.mu[i]) if len(idx) > 1 else self.E[b, i]
        self.pick_raw = np.array([int(np.nanargmin(np.where(self.ok[:, i], self.E[:, i], np.nan))) if self.ok[:, i].any() else -1
                                  for i in range(M)])
        self.pick = np.array([int(np.nanargmin(np.where(self.ok[:, i], self.Ehat[:, i], np.nan))) if self.ok[:, i].any() else -1
                              for i in range(M)])
        okc = self.pick >= 0
        self.valid = bool(okc.all())
        self.sec_raw = np.array([self.E[self.pick_raw[i], i] if self.pick_raw[i] >= 0 else np.nan for i in range(M)])
        self.sec = np.array([self.Ehat[self.pick[i], i] if self.pick[i] >= 0 else np.nan for i in range(M)])
        self.sec_sd = np.array([math.sqrt(self.rho[self.pick[i], i] * self.se2[self.pick[i], i]) if self.pick[i] >= 0 else np.nan
                                for i in range(M)])
        self.comp_raw = float(np.nansum(self.sec_raw)) if self.valid else float("nan")
        self.comp = float(np.nansum(self.sec)) if self.valid else float("nan")
        self.comp_sd = float(math.sqrt(np.nansum(self.sec_sd ** 2))) if self.valid else float("nan")
        # the best complete loop is itself a demonstrated 'stitch' of one lap: the ultimate lap cannot be slower than it
        full = np.where(self.ok.all(axis=1))[0]
        self.best_loop_b, self.best_loop = -1, float("nan")
        self.single_source = False
        if len(full):
            tl = [float(np.sum(self.E[b])) for b in full]
            jb = int(np.argmin(tl))
            self.best_loop_b, self.best_loop = int(full[jb]), tl[jb]
            if self.valid and self.comp > self.best_loop:
                self.pick = np.full(M, self.best_loop_b)
                self.sec = self.E[self.best_loop_b].copy()
                self.sec_sd = np.array([math.sqrt(self.se2[self.best_loop_b, i]) for i in range(M)])
                self.comp = self.best_loop
                self.comp_sd = float(math.sqrt(np.sum(self.sec_sd ** 2) * 0.35))      # the joins inside one lap cancel
                self.single_source = True

    def profile(self):
        """Time of the ultimate lap against distance (m after cut 0, on the 1 m grid): each section is the source lap's own
        profile, stretched a few per cent so its duration is the section's value at the typical speed over the cut."""
        M = self.M
        L = self.laps[0]["al"]["p"][-1]
        p = self.laps[0]["al"]["p"]
        w = np.array([float(np.median([l["vc"][c] for l, ok in zip(self.laps, self.ok[:, c % M]) if ok] or [np.nan]))
                      for c in range(M + 1)])
        w[M] = w[0]
        t_u = np.zeros(len(p))
        acc = 0.0
        self.sec_dur = np.zeros(M)
        for i in range(M):
            b = self.pick[i]
            a0 = self.cuts_rel[i]
            a1 = self.cuts_rel[i + 1] if i + 1 < M else L
            S_i = self.sec[i] + self.price[(i + 1) % M] * w[i + 1] - self.price[i] * w[i]
            self.sec_dur[i] = S_i
            al = self.laps[b]["al"]
            ia0, ia1 = int(round(a0 / GRID)), int(round(a1 / GRID))
            T_src = al["t"][ia1] - al["t"][ia0]
            f = S_i / T_src if T_src > 0 else 1.0
            t_u[ia0:ia1 + 1] = acc + f * (al["t"][ia0:ia1 + 1] - al["t"][ia0])
            acc += S_i
        self.t_ult = t_u
        return t_u

    # ---- gaps of every lap against the ultimate lap
    def gaps(self):
        """(laps x sections) E - ultimate section value; NaN where the lap is not clean in that section."""
        return self.E - self.sec[None, :]

    def lap_gap(self):
        g = self.gaps()
        full = self.ok.all(axis=1)
        return np.where(full, np.nansum(g, axis=1), np.nan)


# ====================================================================================================
# pipeline
# ====================================================================================================
def gps_scatter(sessions, ref):
    """How far GPS fixes scatter sideways about the map on the straights (m), and the timing floor that follows from it.
    On a straight every lap runs nearly the same line, so the sideways spread is mostly GPS error (plus a little line
    variation, which makes the estimate err on the cautious side).  The floor is 0.6 x the spread, kept in [1.0, 3.0] m."""
    d, vs = [], []
    for s in sessions:
        P, D = s.get("P_fix"), s.get("D_fix")
        if P is None:
            continue
        ok = np.isfinite(P) & np.isfinite(D)
        if not ok.any():
            continue
        kap = np.interp(np.mod(P[ok], ref.L), np.arange(ref.n) * ref.ds, np.abs(ref.kap))
        st = kap < 1.0 / 1500.0
        d += list(D[ok][st])
        vs += list(s["fv"][ok][st])
    # only fixes taken at racing speed: a car parked or crawling in the pits sits 20-40 m beside the track line, and those
    # fixes are not GPS error
    d, vs = np.asarray(d), np.asarray(vs)
    if len(d):
        keep = vs >= 0.6 * float(np.percentile(vs, 75))
        d = d[keep]
        d = d[np.abs(d) < 30.0]
    if len(d) < 60:
        return None, SIG_FLOOR
    mad = 1.4826 * float(np.median(np.abs(d - np.median(d))))
    sc = 0.5 * (float(np.std(d)) + mad)
    return sc, float(np.clip(0.6 * sc, 1.0, 3.0))


def prepare(paths, sf=None, workdir=None, quiet=True):
    """Sessions -> map -> progress -> cuts -> laps.  Returns a dict with everything the later stages need.  When several
    files are given, one that cannot be used (empty, no GPS, not this circuit...) is left out and listed under `skipped`,
    as long as another one can be used."""
    skipped = []
    sessions, origin = load_sessions(paths, sf, workdir, quiet, skipped=skipped)
    if not sessions:
        if len(paths) == 1 or len(skipped) == 1:
            raise skipped[0][2]                                     # the single file's own error, as it was raised
        raise SystemExit("None of the %d files could be used: %s" % (len(paths), "; ".join("%s (%s)" % s[:2] for s in skipped)))
    cand = [s for s in sessions if s["res"]["best"] is not None]
    if not cand:
        raise SystemExit("No complete fast lap in any session: a lap needs to cross the start/finish line twice (try --sf LAT,LON).")
    s_ref = min(cand, key=lambda s: s["res"]["best"]["time"])
    ref = build_reference(s_ref)
    warnings = []
    lap_d = [l["dist"] for l in s_ref["res"]["laps"] if l.get("flying")]
    if lap_d:
        md = float(np.median(lap_d))
        if abs(ref.L - md) > 0.02 * md:
            warnings.append("The map drawn from your best lap is %.0f m long, but a typical lap measures %.0f m. Part of that lap probably "
                            "had no GPS, so the map, the corner numbers and the section times may be wrong." % (ref.L, md))
    for s in sessions:
        gl = [g for g in s["res"].get("gaps", []) if g[1] > 5.0]
        if gl:
            warnings.append("%s: GPS had %d stretch(es) with no fix for more than 5 s (longest %.0f s); loops that cross one carry wider "
                            "error bars." % (s["name"], len(gl), max(g[1] for g in gl)))
    fits, good = [], []
    for s in sessions:
        P, D = track_fixes(ref, s)
        s["P_fix"], s["D_fix"] = P, D
        try:
            fits.append(fit_progress(s, P))
            good.append(s)
        except SystemExit as e:
            if s is s_ref or len(sessions) == 1:
                raise
            skipped.append((os.path.basename(s["path"]), _why(e), e))
    sessions = good
    prof = curvature_profile(sessions, fits, ref)
    ref.kap_geo = ref.kap
    if prof is not None:
        ref.kap = prof[0]
    cuts, lens = find_cuts(ref)
    if not cuts:
        raise SystemExit("Could not find any straight to cut the lap on.")
    scatter, floor = gps_scatter(sessions, ref)
    laps = []
    for s, f in zip(sessions, fits):
        laps += make_laps(s, ref, cuts, f, floor)
    lap_filter(laps)
    cuts_rel = [(c - cuts[0]) % ref.L for c in cuts]
    return dict(sessions=sessions, fits=fits, ref=ref, cuts=cuts, lens=lens, cuts_rel=cuts_rel, laps=laps, origin=origin,
                gps_scatter=scatter, sig_floor=floor, skipped=[s[:2] for s in skipped], warnings=warnings)


# ====================================================================================================
# the Sepang model (corner numbers, apex positions) and corner events
# ====================================================================================================
import json                                                                          # noqa: E402

MODEL_NPZ = os.path.join(HERE, "sepang_model.npz")
MODEL_JSON = os.path.join(HERE, "sepang_model.json")


def load_sepang(origin, npz=None, js=None):
    """Sepang full Grand Prix layout in the engine's ENU frame, or None when the model files are missing.
    Racing line, 15 numbered corners with their apex nodes, and the physics prior (see sepang_model.json)."""
    npz, js = npz or MODEL_NPZ, js or MODEL_JSON
    if not (os.path.exists(npz) and os.path.exists(js)):
        return None
    z = np.load(npz)
    meta = json.load(open(js))
    g = meta["georef"]
    P = np.column_stack([z["xr"], z["yr"]])
    th = g["theta_rad"]
    Rm = np.array([[math.cos(th), -math.sin(th)], [math.sin(th), math.cos(th)]])
    enu = (P - np.array(meta["centre_xy"])) @ Rm.T + np.array(g["shift_m"])
    dx, dy = A.to_enu(np.array([g["lat0"]]), np.array([g["lon0"]]), origin[0], origin[1])
    enu = enu + np.array([float(dx[0]), float(dy[0])])
    n = len(enu)
    corners = meta["corners"]

    def eff_apex(c):
        # the physics apex sits on the edge of the window for corners that never reach a speed minimum of their own
        edge = min(abs(c["s_apex"] - c["s_in"]), abs(c["s_apex"] - c["s_out"]))
        c["relocated"] = bool(edge < 10.0)
        return c["s_pk"] if edge < 10.0 else c["s_apex"]
    i_apex = np.array([int(round(eff_apex(c) / 5.0)) % n for c in corners])
    return dict(enu=enu, n=n, Lr=float(z["Lr"]), corners=corners, i_apex=i_apex, meta=meta, v120=z["v120"], v110=z["v110"],
                v130=z["v130"], sr=z["sr"])


def register_map(ref, model, iters=40, trim=0.8):
    """Rigid fit (rotation + shift) of the engine's map onto the model racing line by trimmed ICP.  Returns (R, t, rms) with
    model_xy = R @ map_xy + t, or None when the two do not look like the same circuit (rms above 12 m)."""
    B = model["enu"]
    Bc = np.vstack([B, B[:1]])
    seg = np.hypot(*np.diff(Bc, axis=0).T)
    t_ = np.r_[0, np.cumsum(seg)]
    sn = np.arange(0, t_[-1], 2.5)
    Bd = np.column_stack([np.interp(sn, t_, Bc[:, 0]), np.interp(sn, t_, Bc[:, 1])])
    Ax = ref.xy[::2]
    R, t = np.eye(2), np.zeros(2)
    rms = float("inf")
    for _ in range(iters):
        X = Ax @ R.T + t
        d2 = ((X[:, None, 0] - Bd[None, :, 0]) ** 2 + (X[:, None, 1] - Bd[None, :, 1]) ** 2)
        j = np.argmin(d2, axis=1)
        dm = np.sqrt(d2[np.arange(len(X)), j])
        keep = dm <= np.quantile(dm, trim)
        a, b = Ax[keep], Bd[j[keep]]
        ca, cb = a.mean(axis=0), b.mean(axis=0)
        H = (a - ca).T @ (b - cb)
        Uu, _, Vt = np.linalg.svd(H)
        Rn = Vt.T @ Uu.T
        if np.linalg.det(Rn) < 0:
            Vt[-1] *= -1
            Rn = Vt.T @ Uu.T
        tn = cb - Rn @ ca
        rms_n = float(np.sqrt(np.mean(dm[keep] ** 2)))
        R, t = Rn, tn
        if abs(rms - rms_n) < 1e-4:
            rms = rms_n
            break
        rms = rms_n
    if rms > 12.0:
        return None
    return R, t, rms


def sepang_corners(ref, model, reg, cuts):
    """Apex position of every numbered corner, as metres after cut 0 along the engine's map.  None if the order is wrong."""
    R, t, rms = reg
    out = []
    for c, ia in zip(model["corners"], model["i_apex"]):
        pm = model["enu"][ia]
        xe = R.T @ (pm - t)                                  # model point expressed in the map's frame
        i, d = ref.nearest_global(float(xe[0]), float(xe[1]))
        p = ref.s[i]
        out.append(dict(n=int(c["n"]), dir=c["dir"], name=c.get("name", ""), rel=float((p - cuts[0]) % ref.L), dist_to_map=d,
                        prior=None if c.get("relocated") else c.get("prior", {}).get("1.2")))
    # a corner whose model position is far from your own map is not on the part of the circuit your map covers (a GPS gap in
    # the lap the map was drawn from, or another layout): drop it instead of reading numbers from the wrong place
    far = [c for c in out if c["dist_to_map"] > MAX_APEX_DIST]
    if len(far) > 2:
        return None
    out = [c for c in out if c["dist_to_map"] <= MAX_APEX_DIST]
    rels = [c["rel"] for c in out]
    # the 15 corners must come in the published order around the lap (allowing the lap to start inside the list)
    k0 = int(np.argmin(rels))
    seq = rels[k0:] + rels[:k0]
    if any(b <= a for a, b in zip(seq[:-1], seq[1:])):
        return None
    return out


def detect_corners(ref, cuts, on=1.0 / 450.0, off=1.0 / 900.0, min_len=20.0):
    """Corners of an unknown circuit from the gyro curvature profile of the map: |curvature| above `on`, closed below `off`,
    split where the sign changes.  Numbered in driving order from cut 0."""
    k = ref.kap
    n = ref.n
    i0 = int(np.argmin(np.abs(k)))
    order = (np.arange(n) + i0) % n
    segs, cur, state = [], None, False
    for idx in order:
        a = abs(k[idx])
        if not state and a > on:
            state, cur = True, [idx, idx]
        elif state:
            cur[1] = idx
            if a < off:
                state = False
                segs.append(tuple(cur))
    if state:
        segs.append(tuple(cur))
    wins = []
    for a, b in segs:
        idxs = [(a + j) % n for j in range(((b - a) % n) + 1)]
        sg = np.sign(k[idxs])
        st = 0
        for j in range(1, len(idxs) + 1):
            if j == len(idxs) or sg[j] != sg[st]:
                wins.append(idxs[st:j])
                st = j
    wins = [w for w in wins if len(w) * ref.ds >= min_len]
    out = []
    for w in wins:
        w = np.array(w)
        ipk = int(w[np.argmax(np.abs(k[w]))])
        out.append(dict(rel=float((ref.s[ipk] - cuts[0]) % ref.L), dir="L" if k[ipk] > 0 else "R", name="",
                        r_min=float(1.0 / abs(k[ipk])), prior=None))
    out.sort(key=lambda c: c["rel"])
    for i, c in enumerate(out, 1):
        c["n"] = i
    return out


def _smooth_periodic(a, w):
    if w <= 1:
        return np.asarray(a, float)
    ker = np.ones(w) / w
    pad = np.r_[a[-w:], a, a[:w]]
    return np.convolve(pad, ker, mode="same")[w:-w]


def _sweeps(vf, p):
    """Falling sweeps of a speed trace in driving order: zig-zag extrema with hysteresis p (m/s).  Returns [(i_peak, i_trough), ...]."""
    out = []
    hi = 0
    lo = 0
    peak = None
    for i in range(len(vf)):
        x = vf[i]
        if peak is None:
            if x > vf[hi]:
                hi = i
            elif vf[hi] - x >= p:
                peak, lo = hi, i
        else:
            if x < vf[lo]:
                lo = i
            elif x - vf[lo] >= p:
                out.append((peak, lo))
                peak, hi = None, i
    if peak is not None:
        out.append((peak, lo))
    return out



def corner_events(v, a, lat, ds, ia, prev_ia, next_ia, linked=False, lat_ref=None, w_apex=60.0, back_max=600.0, g_on=-0.30, g_pick=0.10,
                  d_exit=100.0, sm_m=3.0, gap_m=6.0, p_sweep=3.3, dv_min=4.2, trough_max=150.0, brake=True,
                  next_brake=None, sm_peak_m=12.0, exit_margin=25.0):
    """Driver-input events of one corner on a periodic profile.  v [m/s], a [g, forward], lat [g, left +] on a grid of ds metres;
    ia = index of the corner's reference apex, prev_ia/next_ia those of its neighbours.  The same rules are applied to the
    simulator's exact profiles and to the measured ones.
      v_min      lowest speed within +-w of the apex (w shrinks next to a neighbouring corner)
      apex_off   where that is relative to the reference apex (m, + after)
      brake_pos  where the main braking zone starts (deceleration first beyond 0.30 g, or 35 % of the zone's peak if that is more),
                 metres from the reference apex (negative = before),
                 brake_peak its strongest sustained deceleration (g): the best 0.2 s average (a 12 m window), not the best instant; on a real
                 phone the mount rings at 2-10 Hz and a single lap's instantaneous peak moves by 0.1-0.2 g from that alone
      v_brake    speed at that moment
      pickup     metres after the lowest-speed point until acceleration first exceeds 0.10 g; NaN if that does not happen before the
                 next corner's braking zone starts (the acceleration found after it would belong to the next corner)
      v_exit100  speed 100 m after the lowest-speed point (NaN when the next corner is closer than that: the number would then
                 describe the next corner's entry or exit, and it moves a lot with a metre or two of position error; also NaN when
                 the next corner's braking zone starts less than `exit_margin` m beyond that point, because then the number
                 mostly measures how early each lap braked for the next corner)
      turn_in    metres before the lowest-speed point where cornering first reaches 70% of lat_ref, the corner's typical peak (negative);
                 NaN when the 60/70/80% crossings are more than 20 m apart (a two-stage corner has no single turn-in)
      lat_peak   peak cornering within +-w of the apex (g)
    NaN where the corner has no such phase (a flat-out sweeper has no braking run).  linked=True marks a corner that never
    reaches a speed minimum of its own: only its cornering and the speed at the apex are reported.
    next_brake = metres before the NEXT corner's reference apex at which its braking zone starts (positive), NaN/None if it has none."""
    n = len(v)
    w_i = max(int(round(sm_m / ds)), 1)
    a_pk = _smooth_periodic(a, max(int(round(sm_peak_m / ds)), 1))     # sustained braking: the strongest ~0.2 s
    a = _smooth_periodic(a, w_i)
    lat = _smooth_periodic(lat, w_i)
    gp = ((ia - prev_ia) % n) * ds
    gn = ((next_ia - ia) % n) * ds
    wb = int(min(w_apex, 0.45 * gp) / ds)
    wf = int(min(w_apex, 0.45 * gn) / ds)
    near = (ia + np.arange(-wb, wf + 1)) % n
    nb = float(next_brake) if next_brake is not None and np.isfinite(next_brake) else None
    d_nb = (gn - nb) if nb is not None else None        # distance from this apex to where the next corner's braking starts
    if linked:
        i_min = int(ia)
    else:
        seg = v[near]
        jn = int(np.argmin(seg))
        edge = max(int(round(4.0 / ds)), 1)
        if edge <= jn <= len(near) - 1 - edge:
            i_min = int(near[jn])
        else:
            # the lowest point of the window is on its edge: that is the slope of the next corner's entry or the last one's exit,
            # not a minimum of this corner.  Use the lowest dip inside the window; with none, this lap has no minimum here.
            cand = [p for p in range(edge, len(near) - edge) if seg[p] <= seg[p - edge:p + edge + 1].min()]
            if cand:
                i_min = int(near[min(cand, key=lambda p: seg[p])])
            else:
                linked = True
                i_min = int(ia)
    out = dict(v_min=float(v[i_min]), apex_off=float((((i_min - ia) + n // 2) % n - n // 2) * ds),
               brake_pos=float("nan"), brake_peak=float("nan"), v_brake=float("nan"), pickup=float("nan"),
               v_exit100=float("nan"), turn_in=float("nan"), lat_peak=float("nan"))
    if not linked:
        if gn >= d_exit and (d_nb is None or d_nb >= d_exit + exit_margin):
            out["v_exit100"] = float(v[(i_min + int(round(d_exit / ds))) % n])
        # braking zone: the falling sweeps of the speed trace before the lowest-speed point (a long corner that tightens
        # twice, like T1, or a braking-while-turning sequence, like T13-T14, holds several); the zone is the nearest sweep that
        # sheds at least half as much speed as the biggest one, and it starts where deceleration first exceeds g_on
        span = int(min(back_max, gp - 10.0) / ds)
        if span > 3 and brake:
            seq = (i_min - np.arange(0, span)) % n                # backwards from the slowest point
            fwd = seq[::-1]                                        # the same samples in driving order, ending at the slowest point
            sw = [r for r in _sweeps(v[fwd], p_sweep) if (len(fwd) - 1 - r[1]) * ds <= trough_max]    # must end near this corner
            if sw:
                dv = [v[fwd[a0]] - v[fwd[a1]] for a0, a1 in sw]
                a0, a1 = next(r for r, d in zip(sw, dv) if d >= 0.5 * max(dv))
                zone = a[fwd[a0:a1 + 1]]
                # onset = deceleration beyond 0.30 g, or beyond 35 % of this zone's peak if that is more: lifting off at 250 km/h
                # can already decelerate a car at 0.25-0.3 g (drag, engine braking), and that must not count as braking
                hit = np.where(zone <= min(g_on, 0.35 * float(zone.min())))[0]
                if len(hit) and v[fwd[a0]] - v[fwd[a1]] >= dv_min:      # a lift that sheds less than 15 km/h is not a braking zone
                    i_brk = int(fwd[a0 + hit[0]])
                    out["brake_pos"] = float((((i_brk - ia) + n // 2) % n - n // 2) * ds)     # a track position: metres from the reference apex (negative = before)
                    out["brake_peak"] = float(-a_pk[fwd[a0:a1 + 1]].min())
                    out["v_brake"] = float(v[i_brk])
        reach = 400.0 if d_nb is None else min(400.0, max(d_nb - 5.0, 0.0))     # acceleration after the next braking zone is not this corner's
        fw = (i_min + np.arange(0, int(reach / ds))) % n          # throttle pick-up
        up = np.where(a[fw] > g_pick)[0]
        if len(up):
            out["pickup"] = float(up[0] * ds)
    # cornering
    wc = (ia + np.arange(-int(w_apex / ds), int(w_apex / ds) + 1)) % n
    i_pk = int(wc[np.argmax(np.abs(lat[wc]))])
    out["lat_peak"] = float(abs(lat[i_pk]))
    if lat_ref is not None and lat_ref > 0.30:
        sp = int(min(back_max, gp - 10.0) / ds)
        if sp > 3:
            seq = (i_min - np.arange(0, sp)) % n
            cross = []
            for f in (0.6, 0.7, 0.8):                          # where cornering reaches 60 / 70 / 80 % of its typical peak
                if abs(lat[i_min]) < f * lat_ref:
                    cross.append(float("nan"))
                    continue
                below = np.where(np.abs(lat[seq]) < f * lat_ref)[0]
                cross.append(float(-below[0] * ds) if len(below) else float("nan"))
            # a corner whose lateral load builds in two stages has no single turn-in point: leave it out
            if np.all(np.isfinite(cross)) and max(cross) - min(cross) <= 20.0:
                out["turn_in"] = cross[1]
    return out


EVENT_KEYS = ["v_min", "brake_pos", "brake_peak", "v_brake", "pickup", "v_exit100", "turn_in", "lat_peak"]


def section_of(rel, cuts_rel, L):
    """Index of the section (cut i to cut i+1, lap measured from cut 0) that contains `rel`."""
    c = list(cuts_rel) + [L]
    for i in range(len(cuts_rel)):
        if c[i] <= rel < c[i + 1]:
            return i
    return len(cuts_rel) - 1


def recentre_apexes(vmed, ia, n):
    """The model's apex positions come from map data and a physics model, so a real car can be slowest tens of metres from them.
    Move each corner's reference apex to where the median fast lap really runs slowest when that is more than APEX_DEADBAND
    from the model's position (looked for within +-APEX_REACH, never past 45 % of the way to a neighbouring corner).  A corner
    with no minimum of its own inside that window (linked corners) keeps the model's position."""
    K = len(ia)
    new = list(ia)
    for k in range(K):
        gp = ((ia[k] - ia[k - 1]) % n) * GRID
        gn = ((ia[(k + 1) % K] - ia[k]) % n) * GRID
        wb = int(min(APEX_REACH, 0.45 * gp) / GRID)
        wf = int(min(APEX_REACH, 0.45 * gn) / GRID)
        win = (ia[k] + np.arange(-wb, wf + 1)) % n
        j = int(np.argmin(vmed[win]))
        if 3 <= j <= len(win) - 4 and abs((j - wb) * GRID) > APEX_DEADBAND:
            new[k] = int(win[j])
    return new


def build_events(laps, corners, cuts_rel, L):
    """Driver-input events of every corner of every lap, and which corners never reach a speed minimum of their own."""
    K = len(corners)
    n = len(laps[0]["al"]["p"]) - 1
    ia = [int(round(c["rel"] / GRID)) % n for c in corners]
    fl = [l for l in laps if l["fast"]] or laps
    vmed = np.median([l["al"]["v"][:n] for l in fl], axis=0)
    ia0 = list(ia)
    ia = recentre_apexes(vmed, ia, n)
    apex_shift = [float((((ia[k] - ia0[k]) + n // 2) % n - n // 2) * GRID) for k in range(K)]
    latmed = np.median([np.abs(l["al"]["lat"][:n]) for l in fl], axis=0)
    linked, lat_ref = [], []
    for k in range(K):
        gp = ((ia[k] - ia[k - 1]) % n) * GRID
        gn = ((ia[(k + 1) % K] - ia[k]) % n) * GRID
        wb, wf = int(min(60.0, 0.45 * gp) / GRID), int(min(60.0, 0.45 * gn) / GRID)
        win = (ia[k] + np.arange(-wb, wf + 1)) % n
        j = int(np.argmin(vmed[win]))
        linked.append(bool(j <= 2 or j >= len(win) - 3))
        wc = (ia[k] + np.arange(-60, 61)) % n
        lat_ref.append(float(latmed[wc].max()))
    sec = [section_of(ia[k] * GRID, cuts_rel, L) for k in range(K)]
    # which corners have a braking zone at all: decided once, on the median of the fast laps, so noise in one lap cannot
    # turn a lift into a braking zone (or back)
    amed = np.median([l["al"]["lg"][:n] for l in fl], axis=0)
    brake_ok, nbp = [], []
    for k in range(K):
        em = corner_events(vmed, amed, latmed, GRID, ia[k], ia[k - 1], ia[(k + 1) % K], linked=linked[k], lat_ref=None)
        brake_ok.append(bool(np.isfinite(em["brake_pos"])))
        nbp.append(-float(em["brake_pos"]) if brake_ok[-1] else float("nan"))       # metres before the apex where its braking starts
    nbrake = [nbp[(k + 1) % K] for k in range(K)]                                   # ... of the NEXT corner, as corner k sees it
    ev = []
    for l in laps:
        al = l["al"]
        v, a, lat = al["v"][:n], al["lg"][:n], al["lat"][:n]
        ev.append([corner_events(v, a, lat, GRID, ia[k], ia[k - 1], ia[(k + 1) % K], linked=linked[k], lat_ref=lat_ref[k],
                                 brake=brake_ok[k], next_brake=nbrake[k]) for k in range(K)])
    return dict(ia=ia, linked=linked, lat_ref=lat_ref, sec=sec, ev=ev, K=K, brake_ok=brake_ok, apex_shift=apex_shift, nbrake=nbrake)


def event_deltas(E, ult):
    """delta[key][lap, corner] = this lap's value minus the value of the lap that supplies the ultimate lap's section."""
    K = E["K"]
    nb = len(E["ev"])
    out = {}
    for key in EVENT_KEYS:
        d = np.full((nb, K), np.nan)
        val = np.array([[E["ev"][b][k][key] for k in range(K)] for b in range(nb)])
        for k in range(K):
            src = ult.pick[E["sec"][k]]
            if src < 0:
                continue
            for b in range(nb):
                if ult.ok[b, E["sec"][k]] and np.isfinite(val[b, k]) and np.isfinite(val[src, k]):
                    d[b, k] = val[b, k] - val[src, k]
        out[key] = d
    return out


# ====================================================================================================
# causal replay: what a live display could have known, and lap-outcome prediction
# ====================================================================================================
def causal_speed(sess):
    """Forward-only version of the analyzer's speed fusion (no smoother): the speed a live display would have had.
    A GPS speed is used when it arrives (fix time + delivery lag), carried forward from the instant it refers to."""
    tg, a = sess["tg"], sess["along"]
    n = len(tg)
    dt = 1.0 / FS
    qv, qb = (0.45 * dt) ** 2, 0.10 ** 2 * dt
    cum = np.cumsum(a) * dt
    ia = np.ceil((sess["fa"] - tg[0]) * FS).astype(int)
    im = np.round((sess["ft"] - tg[0]) * FS).astype(int)
    meas = {}
    for i in range(len(ia)):
        if 0 <= im[i] < n and 0 <= ia[i] < n and ia[i] >= im[i]:
            sacc = sess["fsa"][i]
            r = (max(0.08, 0.5 * sacc) if sacc > 0 else 0.15) ** 2 + 0.05 ** 2
            meas[ia[i]] = (sess["fv"][i] + cum[ia[i]] - cum[im[i]], r)
    out = np.zeros(n)
    first = min(meas) if meas else 0
    v = meas[first][0] if meas else 0.0
    b = 0.0
    P00, P01, P11 = 1.0, 0.0, 0.25
    for k in range(n):
        if k > 0:
            v = v + (a[k - 1] - b) * dt
            P00, P01, P11 = P00 - 2 * dt * P01 + dt * dt * P11 + qv, P01 - dt * P11, P11 + qb
        m = meas.get(k)
        if m is not None:
            z, r = m
            innov = z - v
            if abs(innov) < 3.0 + 6.0 * math.sqrt(P00):
                S = P00 + r
                K0, K1 = P00 / S, P01 / S
                v += K0 * innov
                b += K1 * innov
                P11 = P11 - K1 * P01
                P01 = (1 - K0) * P01
                P00 = (1 - K0) * P00
        out[k] = max(v, 0.0)
    return out


def causal_progress(sess, v_c, p_fix, q_acc=0.02):
    """Progress along the map as a live display could have it: integrated causal speed plus an offset that a 2-state
    Kalman filter (offset, drift) updates whenever a GPS fix arrives.  Returns NaN before the first usable fix."""
    tg = sess["tg"]
    dt = 1.0 / FS
    pint = np.cumsum(v_c) * dt
    ta_all, tm_all = sess["fa"], sess["ft"]
    order = np.argsort(ta_all)
    x = np.zeros(2)
    Pm = np.diag([25.0, 0.25])
    t_prev = None
    ta_l, d_l, dd_l = [], [], []
    hs = sess["fh"]
    for k in order:
        if not np.isfinite(p_fix[k]):
            continue
        ta, tm = ta_all[k], tm_all[k]
        y = p_fix[k] - float(np.interp(tm, tg, pint))
        sig = float(np.clip(hs[k] / 1.6, 1.0, 6.0)) if hs[k] > 0 else 2.0
        if t_prev is None:
            x = np.array([y, 0.0])
            t_prev = ta
            ta_l.append(ta); d_l.append(x[0]); dd_l.append(x[1])
            continue
        h = ta - t_prev
        F = np.array([[1.0, h], [0.0, 1.0]])
        x = F @ x
        Q = q_acc * np.array([[h ** 3 / 3, h * h / 2], [h * h / 2, h]])
        Pm = F @ Pm @ F.T + Q
        H = np.array([1.0, -(ta - tm)])
        S = float(H @ Pm @ H) + sig ** 2
        innov = y - float(H @ x)
        if abs(innov) > 4.0 * math.sqrt(S) + 3.0:
            t_prev = ta                                    # outlier fix: keep the prediction
            ta_l.append(ta); d_l.append(x[0]); dd_l.append(x[1])
            continue
        K = (Pm @ H) / S
        x = x + K * innov
        Pm = Pm - np.outer(K, H @ Pm)
        t_prev = ta
        ta_l.append(ta); d_l.append(x[0]); dd_l.append(x[1])
    if not ta_l:
        return np.full(len(tg), np.nan)
    ta_a, d_a, dd_a = np.array(ta_l), np.array(d_l), np.array(dd_l)
    kk = np.searchsorted(ta_a, tg, side="right") - 1
    out = np.where(kk >= 0, pint + d_a[np.clip(kk, 0, None)] + dd_a[np.clip(kk, 0, None)] * (tg - ta_a[np.clip(kk, 0, None)]), np.nan)
    return out


CHECKPOINTS = np.arange(0.05, 0.951, 0.05)


def lap_checkpoints(sess, ref, cut0, lap, P_c, cps=CHECKPOINTS):
    """Elapsed time at each lap-fraction checkpoint, from the causal progress (first time each progress level is reached)."""
    tg = sess["tg"]
    L = ref.L
    L0 = cut0 + lap["k"] * L
    ok = np.isfinite(P_c)
    t, P = tg[ok], np.maximum.accumulate(P_c[ok]) + 1e-9 * np.arange(ok.sum())
    if len(P) < 10 or P[0] > L0 or P[-1] < L0 + L:
        return None
    t0 = float(np.interp(L0, P, t))
    return np.array([float(np.interp(L0 + c * L, P, t)) - t0 for c in cps]), float(np.interp(L0 + L, P, t)) - t0


def predictor_eval(prep, t_final=None, q_acc=0.02, min_laps=4):
    """Replay every clean lap as if it were being driven now and predict its final time at each checkpoint from the elapsed
    time alone (causal GPS, no hindsight).  The typical lap is built from the other laps (leave-one-out).
      B0  no look at the lap in progress: the mean time of the other laps
      M0  the deviation from the typical lap stays as it is now: elapsed + typical time-to-go
      M1  the typical time-to-go scaled by the current pace ratio
      M2  a straight-line fit of final time on elapsed time across the other laps, shrunk towards slope 1
      M3  the average of M1 and M2
    The 'potential' (what the lap would take if the rest were driven like the ultimate lap) is returned too.
    Returns dict(cps, rmse{model: array}, pred{model: {lap: array}}, final{lap}, potential{lap}, n, ids)."""
    cuts, ref, laps = prep["cuts"], prep["ref"], prep["laps"]
    cps = CHECKPOINTS
    ecp = {}
    for sess in prep["sessions"]:
        v_c = causal_speed(sess)
        P_c = causal_progress(sess, v_c, sess["P_fix"], q_acc=q_acc)
        for b, l in enumerate(laps):
            if l["session"] != sess["name"] or not l.get("clean"):
                continue
            got = lap_checkpoints(sess, ref, cuts[0], l, P_c)
            if got is not None:
                ecp[b] = got[0]
    ids = sorted(ecp)
    if len(ids) < min_laps:
        return None
    fin = {b: (t_final[b] if t_final is not None and b in t_final else laps[b]["al"]["t"][-1]) for b in ids}
    L = laps[ids[0]]["al"]["p"][-1]
    pgrid = laps[ids[0]]["al"]["p"]
    pred = {m: {} for m in ("B0", "M0", "M1", "M2", "M3")}
    potential = {}
    for b in ids:
        others = [x for x in ids if x != b]
        E = np.array([ecp[x] for x in others])                         # (n_others, checkpoints)
        F = np.array([fin[x] for x in others])
        e = ecp[b]
        pred["B0"][b] = np.full(len(cps), F.mean())
        togo = (F[:, None] - E).mean(axis=0)
        pred["M0"][b] = e + togo
        pred["M1"][b] = e + togo * (e / E.mean(axis=0))
        xm = E.mean(axis=0)
        sxx = ((E - xm) ** 2).sum(axis=0)
        sxy = ((E - xm) * (F[:, None] - F.mean())).sum(axis=0)
        lam = 0.05 * len(others)                                       # (0.22 s)^2 per lap of prior weight on slope 1
        beta = (sxy + lam * 1.0) / (sxx + lam)
        pred["M2"][b] = F.mean() + beta * (e - xm)
        pred["M3"][b] = 0.5 * (pred["M1"][b] + pred["M2"][b])
        use = [l for i, l in enumerate(laps) if l.get("fast") and i != b]
        if len(use) >= 2:
            u = Ultimate(use, prep["cuts_rel"])
            if u.valid:
                tp = u.profile()
                potential[b] = e + (u.comp - np.array([float(np.interp(c * L, pgrid, tp)) for c in cps]))
    rmse = {m: np.array([math.sqrt(np.mean([(pred[m][b][i] - fin[b]) ** 2 for b in ids])) for i in range(len(cps))]) for m in pred}
    return dict(cps=cps, rmse=rmse, pred=pred, final=fin, potential=potential, n=len(ids), ids=ids)


# ====================================================================================================
# the whole analysis
# ====================================================================================================
def locate_corners(prep, use_model=True):
    """Numbered corners along the lap.  Sepang's 15 official turns when the map registers on the model, else the corners the
    gyro finds (C1, C2, ...).  Returns (corners sorted along the lap, info dict)."""
    ref, cuts = prep["ref"], prep["cuts"]
    model = load_sepang(prep["origin"]) if use_model else None
    if model is not None:
        reg = register_map(ref, model)
        if reg is not None:
            corners = sepang_corners(ref, model, reg, cuts)
            if corners is not None:
                corners.sort(key=lambda c: c["rel"])
                for c in corners:
                    c["label"] = "T%d" % c["n"]
                return corners, dict(layout="Sepang International Circuit, full Grand Prix layout", registered=True,
                                     reg_rms=float(reg[2]), model=model)
    corners = detect_corners(ref, cuts)
    for c in corners:
        c["label"] = "C%d" % c["n"]
    return corners, dict(layout="not recognised as Sepang GP: corners found from your own laps", registered=False, reg_rms=None,
                         model=None)


def section_names(corners, E, M):
    """'T1-T3' style name of every section (cut i to cut i+1); 'straight' if it holds no corner."""
    names = []
    for i in range(M):
        ks = [corners[k]["label"] for k in range(len(corners)) if E["sec"][k] == i]
        names.append("straight" if not ks else (ks[0] if len(ks) == 1 else "%s-%s" % (ks[0], ks[-1])))
    return names


def run(paths, out, sf=None, use_model=True, replay=True, quiet=True):
    prep = prepare(paths, sf=sf, workdir=out, quiet=quiet)
    laps = prep["laps"]
    if not any(l.get("fast") for l in laps):
        raise SystemExit("Found %d complete loop(s) but no usable flying lap: every one starts or ends far below racing speed "
                         "(an out-lap or in-lap). A loop runs from the middle of the front straight to the middle of the next "
                         "time round, so a usable one needs both crossings at racing speed." % len(laps))
    ult = Ultimate(laps, prep["cuts_rel"])
    if not ult.valid:
        bad = [i for i in range(ult.M) if ult.pick[i] < 0]
        raise SystemExit("No clean lap through section(s) %s: need at least one lap with GPS at both ends of every section." % bad)
    ult.profile()
    corners, info = locate_corners(prep, use_model)
    E = build_events(laps, corners, prep["cuts_rel"], prep["ref"].L)
    names = section_names(corners, E, ult.M)
    pred = predictor_eval(prep) if replay else None
    return dict(prep=prep, ult=ult, corners=corners, info=info, E=E, deltas=event_deltas(E, ult), names=names, pred=pred,
                replay=bool(replay))


# ====================================================================================================
# command line
# ====================================================================================================
def main(argv=None):
    for st in (sys.stdout, sys.stderr):                      # a file name the console cannot show must not stop the analysis
        try:
            st.reconfigure(errors="replace")
        except Exception:
            pass
    ap = argparse.ArgumentParser(description="Stitch your best demonstrated sections into an ultimate lap and measure every lap against it.")
    ap.add_argument("csv", nargs="+", help="one or more Track Logger CSV files (all are pooled into one analysis)")
    ap.add_argument("--out", default=None, help="output folder (default: ultimate_out next to the first file)")
    ap.add_argument("--sf", default=None, help="start/finish line as LAT,LON (only needed if the analyzer cannot find the laps)")
    ap.add_argument("--no-model", action="store_true", help="do not use the built-in Sepang model (other circuits or layouts)")
    ap.add_argument("--no-plots", action="store_true")
    ap.add_argument("--no-replay", action="store_true", help="skip the lap-time predictor replay")
    a = ap.parse_args(argv)
    import glob
    paths = []
    for p in a.csv:
        if not os.path.exists(p) and any(ch in p for ch in "*?["):
            paths += sorted(glob.glob(p))                    # Windows shells do not expand wildcards themselves
        else:
            paths.append(p)
    bad = [p for p in paths if not os.path.isfile(p)]
    if not paths or bad:
        raise SystemExit("Cannot find the session file(s): %s" % ", ".join(bad or a.csv))
    try:
        sf = tuple(float(x) for x in a.sf.split(",")) if a.sf else None
        if sf is not None and len(sf) != 2:
            raise ValueError("need two numbers")
    except ValueError:
        raise SystemExit("--sf needs LATITUDE,LONGITUDE, for example --sf 2.7608,101.7380")
    out = a.out or os.path.join(os.path.dirname(os.path.abspath(paths[0])), "ultimate_out")
    os.makedirs(out, exist_ok=True)
    R = run(paths, out, sf=sf, use_model=not a.no_model, replay=not a.no_replay)
    import ultimate_report as RP
    S, files = RP.write(R, out, plots=not a.no_plots)
    u = R["ult"]
    print("%s" % R["info"]["layout"])
    print("%d loops (%d clean), %d sections: %s" % (len(R["prep"]["laps"]), len(S["clean"]), u.M, ", ".join(R["names"])))
    print("fastest loop %s   ultimate lap %s +-%.2f   (before noise correction %s)" % (
        RP.fmt_t(S["best_time"]), RP.fmt_t(u.comp), u.comp_sd, RP.fmt_t(u.comp_raw)))
    for name, why in R["prep"].get("skipped", []):
        print("left out %s: %s" % (name, why))
    print("report: %s" % os.path.join(out, "ultimate_report.md"))


if __name__ == "__main__":
    main()

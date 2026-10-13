#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
analyze.py - turn a Track Logger session into the channels from the corner figure.

    python analyze.py session_20261013_093012.csv
    python analyze.py session_20261013_093012.csv --out results --sf 2.7608,101.7370

Needs only numpy and matplotlib.

What it does
  1. Reads the raw phone-frame log (accelerometer, gyro, GPS).
  2. Finds the 5 s the car stood still at the start: that gives "up" and the gyro bias.
  3. Finds the car's forward axis by regressing the phone's horizontal acceleration against
     the GPS speed derivative (so the phone can be mounted at any angle).
  4. Builds car-frame channels at 100 Hz:
        long_g   +accelerating / -braking          (the pedal proxy: brake = negative, throttle = positive)
        lat_g    +left / -right                    (what the tyres are doing)
        yaw rate and curvature = yaw rate / speed  (the steering proxy: curvature is proportional to wheel angle)
        speed    GPS speed (Doppler) fused with the integrated accelerometer (Kalman + smoother)
  5. Splits laps at a line (automatic, or --sf lat,lon), compares laps, finds corners and measures
     the brake ramp, release, trail braking, throttle pick-up and turn-in curvature ramp per corner.

Outputs (in --out): report.md, channels.csv, lap_table.csv, corner_table.csv,
                    fig_best_lap.png, fig_gg.png, fig_laps.png, fig_map.png, fig_corners.png

Honest limits: pedals are not measured, only inferred from acceleration (a car coasting at 0 g
is "off both pedals"; full throttle is not distinguishable from 70 % once power-limited).
GPS in a phone is ~1 Hz; the 100 Hz channels between fixes come from the inertial sensors.
"""
import argparse
import math
import os
import sys

import numpy as np

G0 = 9.80665
FS = 100.0                       # analysis grid, Hz

# ------------------------------------------------------------------ palette (light surface)
SURF, INK, INK2, MUTED, GRID, BASE = "#fcfcfb", "#0b0b0b", "#52514e", "#898781", "#e1e0d9", "#c3c2b7"
BLUE, ORANGE, AQUA = "#2a78d6", "#eb6834", "#1baf7a"


# ================================================================== loading
def _nums(parts):
    """Floats of a row's fields; a field that is not a finite number (text, empty, nan, inf) makes the row unusable."""
    v = [float(x) for x in parts]
    if not all(math.isfinite(x) for x in v):
        raise ValueError("non-finite value")
    return v


def load_session(path):
    """Parse the CSV written by the app. Tolerates a truncated last line and unknown rows."""
    meta = []
    A, Gy, L, S, E, K = [], [], [], [], [], []
    bad = 0
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            if line[0] == "#":
                meta.append(line[1:].strip())
                continue
            p = line.split(",")
            k = p[0]
            try:
                if k == "A" and len(p) == 5:
                    A.append(_nums(p[1:]))
                elif k == "G" and len(p) == 5:
                    Gy.append(_nums(p[1:]))
                elif k == "L" and len(p) == 12:
                    L.append(_nums(p[1:]))
                elif k == "S" and len(p) == 4:
                    S.append(_nums(p[1:]))
                elif k == "E" and len(p) >= 3:
                    E.append((_nums(p[1:2])[0] * 1e-6, p[2], _nums(p[3:])))
                elif k == "K" and len(p) == 4:
                    K.append((_nums(p[1:2])[0] * 1e-6, p[2], _nums(p[3:4])[0]))
                else:
                    bad += 1
            except ValueError:
                bad += 1

    def arr(x, n):
        a = np.array(x, dtype=float) if x else np.zeros((0, n))
        if len(a):
            a[:, 0] *= 1e-6                      # microseconds -> seconds
            a = a[np.argsort(a[:, 0], kind="stable")]
            keep = np.concatenate([[True], np.diff(a[:, 0]) > 0])
            a = a[keep]
        return a

    D = dict(meta=meta, A=arr(A, 4), G=arr(Gy, 4), L=arr(L, 11), S=arr(S, 3), E=E, K=K, bad=bad)
    return D


def fix_sensor_clock(D):
    """Put the IMU rows back on the GPS clock if the phone stamped them on another one.

    Android says sensor events use the elapsed-realtime clock, the same one as the GPS fixes, and the app logs
    K rows (arrival time minus event time) to check it. A few ms to ~100 ms is normal delivery delay. A negative
    lag (an event arriving before it happened) or a lag of tens of seconds or more can only be a different clock:
    an event stamped C too late arrives C early, so K = -C and the true time is t + K.
    A lag of 1.5-30 s could also be genuine batching of sensor events, whose time stamps are still right, so that
    range is only reported, never corrected. Returns a list of notes for the report."""
    notes = []
    shift = {}
    for kind, key in (("A", "A"), ("G", "G")):
        ks = [k[2] for k in D["K"] if k[1] == kind]
        if not ks or len(D[key]) == 0:
            continue
        lag = float(np.median(ks))
        if lag > 30000.0 or lag < -200.0:
            D[key] = D[key].copy()
            D[key][:, 0] += lag * 1e-3
            shift[kind] = lag * 1e-3
            notes.append("%s rows (K lag %.0f ms): shifted by %+.3f s" % (kind, lag, lag * 1e-3))
    if "A" in shift:
        # CAL / CALFAIL rows are stamped from an accelerometer event, so they sit on the same clock
        D["E"] = [((te + shift["A"]) if name in ("CAL", "CALFAIL") else te, name, vals) for (te, name, vals) in D["E"]]
    return notes


def meta_value(meta, key):
    for m in meta:
        if m.startswith(key + ","):
            return m[len(key) + 1:]
    return ""


def gsmooth(x, sigma):
    """Zero-phase Gaussian smoothing along axis 0 (sigma in samples)."""
    if sigma <= 0.3 or len(x) < 5:
        return x
    r = int(4 * sigma) + 1
    k = np.exp(-0.5 * (np.arange(-r, r + 1) / sigma) ** 2)
    k /= k.sum()
    if x.ndim == 1:
        xp = np.pad(x, r, mode="edge")
        return np.convolve(xp, k, mode="valid")
    return np.column_stack([gsmooth(x[:, i], sigma) for i in range(x.shape[1])])


# ================================================================== orientation
def find_static(tg, ag, gg, Lt, Lv, win_s=3.0, step_s=0.5):
    n = len(tg)
    W, step = int(win_s * FS), int(step_s * FS)
    z3 = np.zeros((1, 3))
    cs = np.vstack([z3, np.cumsum(ag, axis=0)])
    cs2 = np.vstack([z3, np.cumsum(ag ** 2, axis=0)])
    gs = np.vstack([z3, np.cumsum(gg, axis=0)])
    gs2 = np.vstack([z3, np.cumsum(gg ** 2, axis=0)])
    for i0 in range(0, n - W, step):
        i1 = i0 + W
        m = (cs[i1] - cs[i0]) / W
        noise = math.sqrt(max(((cs2[i1] - cs2[i0]) / W - m ** 2).sum(), 0.0))
        gm = (gs[i1] - gs[i0]) / W
        grms = math.sqrt(max(((gs2[i1] - gs2[i0]) / W - gm ** 2).sum(), 0.0))
        vmax = 0.0
        if len(Lt):
            sel = (Lt >= tg[i0] - 1.0) & (Lt <= tg[i1 - 1] + 1.0)
            if sel.any():
                vmax = Lv[sel].max()
        if noise < 1.0 and grms < 0.08 and vmax < 0.6 and abs(np.linalg.norm(m) - G0) < 1.0:
            return i0, i1, m, gm, noise
    return None


def find_cruise(tg, ag, gg, Lt, Lv):
    """No standstill in the file: use steady straight-line driving (no yaw, no speed change), where the
    accelerometer reads gravity only. Returns (mean accel, gyro bias, n samples) or None."""
    if len(Lt) < 10:
        return None
    vg = np.interp(tg, Lt, Lv)
    dv = np.gradient(gsmooth(vg, 1.0 * FS)) * FS
    gbias = np.median(gg, axis=0)
    w = np.linalg.norm(gsmooth(gg - gbias, 0.2 * FS), axis=1)
    m = (vg > 8.0) & (vg * w < 0.12) & (np.abs(dv) < 0.12)       # lateral and longitudinal accel both ~0
    if m.sum() < 300:
        return None
    return ag[m].mean(axis=0), np.median(gg[m], axis=0), int(m.sum())


def forward_axis(tg, ag, yaw_up, up, Lt, Lv):
    """Forward direction (unit vector, phone axes) from  horizontal accel  ~  d(GPS speed)/dt.
    Also scans a GPS time lag. Returns dict."""
    k = int(np.argmin(np.abs(up)))
    a0 = np.zeros(3)
    a0[k] = 1.0
    e1 = a0 - up * np.dot(a0, up)
    e1 /= np.linalg.norm(e1)
    e2 = np.cross(up, e1)
    h = np.column_stack([ag @ e1, ag @ e2])
    cs = np.vstack([np.zeros((1, 2)), np.cumsum(h, axis=0)])
    cy = np.concatenate([[0.0], np.cumsum(np.abs(yaw_up))])
    n = len(tg)
    if len(Lt) < 6:
        raise SystemExit("Too few GPS fixes to find the forward axis (need a moving car with GPS).")
    tp, tn, tm = Lt[:-2], Lt[2:], Lt[1:-1]
    y_all = (Lv[2:] - Lv[:-2]) / (tn - tp)
    base_ok = ((tn - tp) < 2.6) & (Lv[1:-1] > 4.0)
    best = None
    scan = []
    for tau in np.arange(-0.5, 1.2001, 0.02):
        ia = np.searchsorted(tg, tp - tau)
        ib = np.searchsorted(tg, tn - tau)
        ok = base_ok & (ia > 0) & (ib < n) & (ib - ia > 50)
        if ok.sum() < 6:
            continue
        iaa, ibb, ya = ia[ok], ib[ok], y_all[ok]
        cnt = (ibb - iaa).astype(float)
        A = (cs[ibb] - cs[iaa]) / cnt[:, None]
        yawm = (cy[ibb] - cy[iaa]) / cnt
        sel = yawm < 0.12
        if sel.sum() < 6:
            sel = np.ones(len(ya), bool)
        c, *_ = np.linalg.lstsq(A[sel], ya[sel], rcond=None)
        r = ya[sel] - A[sel] @ c
        sse = float((r ** 2).sum())
        sst = float(((ya[sel] - ya[sel].mean()) ** 2).sum()) + 1e-12
        scan.append((float(tau), sse))
        if best is None or sse < best["sse"]:
            best = dict(tau=float(tau), sse=sse, r2=1 - sse / sst, c=c, n=int(sel.sum()))
    if best is not None and len(scan) > 4:
        sc = np.array(scan)
        k = int(np.argmin(sc[:, 1]))
        if 0 < k < len(sc) - 1:
            y0, y1, y2 = sc[k - 1, 1], sc[k, 1], sc[k + 1, 1]
            den = y0 - 2 * y1 + y2
            if den > 0:
                best["tau"] = float(sc[k, 0] + 0.5 * (y0 - y2) / den * (sc[1, 0] - sc[0, 0]))
    if best is None:
        raise SystemExit("Could not find the forward axis: no stretch with GPS speed above 4 m/s.")
    c = best["c"]
    gain = float(np.linalg.norm(c))
    f = (c[0] * e1 + c[1] * e2) / gain
    best["f"] = f
    best["gain"] = gain
    return best


def dot_rows(x, v):
    """x (n,3) . v, where v is one axis (3,) or one axis per sample (n,3)."""
    return x @ v if v.ndim == 1 else np.einsum("ij,ij->i", x, v)


# ================================================================== orientation from the drive itself
def _gps_windows(tg, cs, Lt, Lv, Lb, tau):
    """One row per interior GPS fix: the accelerometer's mean over the 2 s around the fix (phone axes), the forward
    acceleration of the car from the GPS speed, and its sideways acceleration (left positive) from speed x course change."""
    n = len(tg)
    k = np.arange(1, len(Lt) - 1)
    if len(k) < 5:
        return None
    dt = Lt[k + 1] - Lt[k - 1]
    ok = (dt < 2.6) & (Lv[k] > 6.0) & (Lb[k - 1] >= 0) & (Lb[k] >= 0) & (Lb[k + 1] >= 0)
    ia = np.searchsorted(tg, Lt[k - 1] - tau)
    ib = np.searchsorted(tg, Lt[k + 1] - tau)
    ok &= (ia > 0) & (ib < n) & (ib - ia > 50)
    if ok.sum() < 20:
        return None
    k, dt, ia, ib = k[ok], dt[ok], ia[ok], ib[ok]
    am = (cs[ib] - cs[ia]) / (ib - ia)[:, None]
    ax = (Lv[k + 1] - Lv[k - 1]) / dt
    db = (Lb[k + 1] - Lb[k - 1] + 180.0) % 360.0 - 180.0
    vbar = 0.25 * Lv[k - 1] + 0.5 * Lv[k] + 0.25 * Lv[k + 1]
    ay = -vbar * np.radians(db) / dt
    return dict(t=Lt[k], am=am, ax=ax, ay=ay)


def _procrustes(am, ax, ay, w):
    """Rotation q (columns = forward, left, up, in phone axes) and gain s that minimise
    sum w |am - s q [ax, ay, g]|^2. Also returns the residual length of every row."""
    tm = np.column_stack([ax, ay, np.full(len(ax), G0)])
    m = (am * w[:, None]).T @ tm
    u_, s_, vt = np.linalg.svd(m)
    d = 1.0 if np.linalg.det(u_ @ vt) > 0 else -1.0
    q = u_ @ np.diag([1.0, 1.0, d]) @ vt
    den = float((w[:, None] * tm ** 2).sum())
    s = float((s_[0] + s_[1] + d * s_[2]) / den) if den > 0 else 1.0
    r = np.linalg.norm(am - s * (tm @ q.T), axis=1)
    return q, s, r


def _robust_procrustes(am, ax, ay, w0):
    """Procrustes fit that drops the rows it cannot explain (GPS glitches, bumps), a few passes."""
    w0 = np.asarray(w0, float)
    wk = w0.copy()
    sel = w0 > 0.05 * w0.max()
    for _ in range(3):
        q, s, r = _procrustes(am, ax, ay, wk)
        wk = w0 * (r < max(2.5 * float(np.median(r[sel])), 1.0))
    q, s, r = _procrustes(am, ax, ay, wk)
    rms = float(np.sqrt(np.sum(wk * r ** 2) / max(float(wk.sum()), 1e-9)))
    return q, s, rms, wk


def orientation_from_gps(tg, ag, gg, Lt, Lv, Lb, tau0, span_s=45.0, step_s=10.0):
    """Phone-to-car rotation from the drive itself, for a file with no standstill (the recording began on the move, or the
    car never stopped). The accelerometer's mean over each 2 s between GPS fixes must equal  forward * a_x + left * a_y + up * g,
    where a_x (from the GPS speed) and a_y (speed x course change) are known: the rotation that fits best, over a sliding
    window of about a minute, is the mounting angle - and it is allowed to change slowly (a phone that is not rigidly fixed
    creeps). Also finds the GPS time lag and the gyro bias. Returns None if the drive does not excite both axes."""
    cs = np.vstack([np.zeros((1, 3)), np.cumsum(ag, axis=0)])
    scan = []
    for tau in np.arange(tau0 - 0.30, tau0 + 0.3001, 0.05):
        W = _gps_windows(tg, cs, Lt, Lv, Lb, tau)
        if W is None:
            return None
        q, s, r = _procrustes(W["am"], W["ax"], W["ay"], np.ones(len(W["t"])))
        scan.append((float(tau), float(np.mean(np.minimum(r ** 2, 9.0)))))
    sc = np.array(scan)
    k = int(np.argmin(sc[:, 1]))
    tau = float(sc[k, 0])
    if 0 < k < len(sc) - 1:
        y0, y1, y2 = sc[k - 1, 1], sc[k, 1], sc[k + 1, 1]
        den = y0 - 2 * y1 + y2
        if den > 0:
            tau = float(sc[k, 0] + 0.5 * (y0 - y2) / den * (sc[1, 0] - sc[0, 0]))
    W = _gps_windows(tg, cs, Lt, Lv, Lb, tau)
    T, AM, AX, AY = W["t"], W["am"], W["ax"], W["ay"]
    if math.sqrt(float(np.mean(AX ** 2))) < 1.5 or math.sqrt(float(np.mean(AY ** 2))) < 1.5:
        return None                                  # no braking or no cornering to see the axes with
    qg, sg, rmsg, _ = _robust_procrustes(AM, AX, AY, np.ones(len(T)))
    # sliding fits
    centers = np.arange(tg[0], tg[-1] + step_s, step_s)
    qc = np.zeros((len(centers), 3, 3))
    local = np.zeros(len(centers), bool)
    for ic, c in enumerate(centers):
        sigma = span_s / 3.0
        for _ in range(3):
            w = np.exp(-0.5 * ((T - c) / sigma) ** 2)
            w[w < 1e-3] = 0.0
            neff = float(w.sum())
            if neff >= 10.0:
                sx = math.sqrt(float((w * AX ** 2).sum()) / neff)
                sy = math.sqrt(float((w * AY ** 2).sum()) / neff)
                if sx > 1.5 and sy > 1.5:
                    qc[ic] = _robust_procrustes(AM, AX, AY, w)[0]
                    local[ic] = True
                    break
            sigma *= 1.6
        if not local[ic]:
            qc[ic] = qg
    # to the 100 Hz grid: blend the two neighbouring fits, then make the blend a rotation again (done on a 10 Hz grid)
    t10 = tg[::10]
    idx = np.clip(np.searchsorted(centers, t10, side="right") - 1, 0, len(centers) - 2)
    lam = np.clip((t10 - centers[idx]) / step_s, 0.0, 1.0)
    qi = (1.0 - lam)[:, None, None] * qc[idx] + lam[:, None, None] * qc[idx + 1]
    u_, _, vt = np.linalg.svd(qi)
    qi = u_ @ vt
    out = {}
    for j, name in enumerate(("f", "lat", "up")):
        out[name] = np.column_stack([np.interp(tg, t10, qi[:, i, j]) for i in range(3)])
    # angle between each sliding fit and the whole-drive fit: how much the phone moved in its mount
    ang = [math.degrees(math.acos(max(-1.0, min(1.0, float(np.dot(qc[i][:, 0], qg[:, 0])))))) for i in range(len(centers)) if local[i]]
    angu = [math.degrees(math.acos(max(-1.0, min(1.0, float(np.dot(qc[i][:, 2], qg[:, 2])))))) for i in range(len(centers)) if local[i]]
    # the gyro bias: the mean reading minus the yaw the drive really had (a lap of a circuit always turns the same way)
    okb = (Lv > 3.0) & (Lb >= 0)
    dtl = np.diff(Lt)
    sel = okb[:-1] & okb[1:] & (dtl < 2.6)
    dbr = np.radians((np.diff(Lb) + 180.0) % 360.0 - 180.0)[sel]
    net = -float(dbr.sum()) / max(float(dtl[sel].sum()), 1.0)        # mean yaw rate, left positive
    uav = out["up"].mean(axis=0)
    uav /= np.linalg.norm(uav)
    gbias = gg.mean(axis=0) - uav * net
    var = float(np.mean(np.sum((AM - AM.mean(axis=0)) ** 2, axis=1)))
    out.update(gbias=gbias, gnorm=float(np.median(dot_rows(ag, out["up"]))), tau=tau, gain=sg, rms=rmsg, n=int(len(T)),
               r2=1.0 - rmsg ** 2 / max(var, 1e-9),
               wander_med=float(np.median(ang)) if ang else 0.0, wander_max=float(np.max(ang)) if ang else 0.0,
               wander_up_med=float(np.median(angu)) if angu else 0.0, frac_local=float(local.mean()), qg=qg)
    return out


# ================================================================== speed fusion
def fuse_speed(tg, a_long, Lt, Lv, Lsacc, tau, q_scale=1.0, diag=None):
    """Kalman filter + RTS smoother.  State: speed v and accelerometer bias b.  Inputs: integrated a_long
    and GPS speed fixes. Returns smoothed v (m/s) on the grid, and the bias estimate.

    q_scale multiplies the accelerometer's error variance: 1 suits a phone that is rigidly fixed and well calibrated; a
    loose mount or an uncertain orientation needs more (the caller measures how much from the filter's own innovations).
    A fix far from the prediction is first treated as a glitch and skipped, but a second one in a row means the
    prediction is what is wrong, so the speed is reset to the GPS value instead of staying locked out for minutes.
    diag (a dict) receives the median normalised innovation (about 0.45 for a filter that is tuned right), the
    number of resets and the number of fixes used."""
    n = len(tg)
    dt = 1.0 / FS
    sig_a, sig_b = 0.45 * math.sqrt(q_scale), 0.10 * q_scale ** 0.25    # per-sample accel noise (m/s2), bias walk (m/s2/sqrt(s))
    qv, qb = (sig_a * dt) ** 2, sig_b ** 2 * dt
    # measurement schedule
    idx = np.round((Lt - tau - tg[0]) * FS).astype(int)
    meas = {}
    for i in range(len(Lt)):
        if 0 <= idx[i] < n:
            sacc = Lsacc[i]
            r = (max(0.08, 0.5 * sacc) if sacc > 0 else 0.15) ** 2
            meas[idx[i]] = (Lv[i], r)
    nis, resets, consec = [], 0, 0
    xp = np.zeros((n, 2))
    pp = np.zeros((n, 3))
    xf = np.zeros((n, 2))
    pf = np.zeros((n, 3))
    first = min(meas) if meas else 0
    v = meas[first][0] if meas else 0.0
    b = 0.0
    P00, P01, P11 = 1.0, 0.0, 0.25
    gated = 0
    for k in range(n):
        if k > 0:
            v = v + (a_long[k - 1] - b) * dt
            P00, P01, P11 = P00 - 2 * dt * P01 + dt * dt * P11 + qv, P01 - dt * P11, P11 + qb
        xp[k, 0], xp[k, 1] = v, b
        pp[k, 0], pp[k, 1], pp[k, 2] = P00, P01, P11
        m = meas.get(k)
        if m is not None:
            z, r = m
            innov = z - v
            if abs(innov) < 3.0 + 6.0 * math.sqrt(P00):
                S = P00 + r
                nis.append(innov * innov / S)
                K0, K1 = P00 / S, P01 / S
                v += K0 * innov
                b = max(-1.5, min(1.5, b + K1 * innov))
                P11 = P11 - K1 * P01
                P01 = (1 - K0) * P01
                P00 = (1 - K0) * P00
                consec = 0
            else:
                gated += 1
                consec += 1
                if consec >= 2:                      # the prediction is lost, not the GPS: start again from the fix
                    v, P00, P01 = z, r + 0.25, 0.0
                    resets += 1
                    consec = 0
        xf[k, 0], xf[k, 1] = v, b
        pf[k, 0], pf[k, 1], pf[k, 2] = P00, P01, P11
    xs = xf.copy()
    for k in range(n - 2, -1, -1):
        a_, b_, d_ = pf[k]
        # C = Pf F^T inv(Pp[k+1]),  F = [[1,-dt],[0,1]]
        m00, m01 = a_ - dt * b_, b_
        m10, m11 = b_ - dt * d_, d_
        p, q, r_ = pp[k + 1]
        det = p * r_ - q * q
        if abs(det) < 1e-18:
            continue
        i00, i01, i11 = r_ / det, -q / det, p / det
        c00 = m00 * i00 + m01 * i01
        c01 = m00 * i01 + m01 * i11
        c10 = m10 * i00 + m11 * i01
        c11 = m10 * i01 + m11 * i11
        d0 = xs[k + 1, 0] - xp[k + 1, 0]
        d1 = xs[k + 1, 1] - xp[k + 1, 1]
        xs[k, 0] = xf[k, 0] + c00 * d0 + c01 * d1
        xs[k, 1] = xf[k, 1] + c10 * d0 + c11 * d1
    if diag is not None:
        diag.update(nis_med=float(np.median(nis)) if nis else 0.0, resets=resets, used=len(nis), gated=gated)
    return np.maximum(xs[:, 0], 0.0), xs[:, 1], gated


# ================================================================== GPS path, laps
def to_enu(lat, lon, lat0, lon0):
    R = 6378137.0
    x = np.radians(lon - lon0) * R * math.cos(math.radians(lat0))
    y = np.radians(lat - lat0) * R
    return x, y


def hermite_path(Lt, X, Y, V, B, step=0.05, max_gap=2.5):
    """20 Hz position from 1 Hz fixes using cubic Hermite (position + velocity vector at each fix)."""
    vx, vy = V * np.sin(np.radians(B)), V * np.cos(np.radians(B))
    bad_b = B < 0
    if bad_b.any():                                  # no bearing: use finite differences
        dx = np.gradient(X, Lt)
        dy = np.gradient(Y, Lt)
        vx = np.where(bad_b, dx, vx)
        vy = np.where(bad_b, dy, vy)
    tt = np.arange(Lt[0], Lt[-1], step)
    i = np.clip(np.searchsorted(Lt, tt, side="right") - 1, 0, len(Lt) - 2)
    d = Lt[i + 1] - Lt[i]
    u = (tt - Lt[i]) / d
    h00 = 2 * u ** 3 - 3 * u ** 2 + 1
    h10 = u ** 3 - 2 * u ** 2 + u
    h01 = -2 * u ** 3 + 3 * u ** 2
    h11 = u ** 3 - u ** 2
    px = h00 * X[i] + h10 * d * vx[i] + h01 * X[i + 1] + h11 * d * vx[i + 1]
    py = h00 * Y[i] + h10 * d * vy[i] + h01 * Y[i + 1] + h11 * d * vy[i + 1]
    ok = d <= max_gap
    return tt, px, py, ok


def find_crossings(tt, px, py, ok, p0, d0, half_width=30.0):
    q = (px - p0[0]) * d0[0] + (py - p0[1]) * d0[1]
    w = -(px - p0[0]) * d0[1] + (py - p0[1]) * d0[0]
    idx = np.where((q[:-1] < 0) & (q[1:] >= 0) & ok[:-1] & ok[1:] & (np.abs(w[:-1]) < half_width))[0]
    out = []
    for i in idx:
        frac = -q[i] / (q[i + 1] - q[i])
        t = tt[i] + frac * (tt[i + 1] - tt[i])
        if not out or t - out[-1] > 20.0:
            out.append(t)
    return np.array(out)


# ================================================================== corners
def segments(mask):
    d = np.diff(np.concatenate([[0], mask.astype(int), [0]]))
    return list(zip(np.where(d == 1)[0], np.where(d == -1)[0]))


def merge_close(segs, gap):
    out = []
    for a, b in segs:
        if out and a - out[-1][1] < gap:
            out[-1] = (out[-1][0], b)
        else:
            out.append((a, b))
    return out


def linfit(x, y):
    if len(x) < 4:
        return float("nan"), float("nan")
    A = np.column_stack([x, np.ones_like(x)])
    c, *_ = np.linalg.lstsq(A, y, rcond=None)
    r = y - A @ c
    sst = ((y - y.mean()) ** 2).sum() + 1e-12
    return float(c[0]), float(1 - (r ** 2).sum() / sst)


def split_complex(sv, lat_s, a, b):
    """Split one long cornering stretch [a, b) into separate corners: one per distinct speed minimum
    (prominence >= 6 km/h, >= 60 m apart); at a left/right change the cut is where lateral g crosses zero."""
    seg = sv[a:b]
    if len(seg) < 5:
        return [(a, b)]
    idx = np.where((seg[1:-1] <= seg[:-2]) & (seg[1:-1] < seg[2:]))[0] + 1
    mins = [int(i) for i in idx]
    while len(mins) > 1:
        dropped = False
        for k in range(len(mins) - 1):
            m0, m1 = mins[k], mins[k + 1]
            prom = seg[m0:m1 + 1].max() - max(seg[m0], seg[m1])
            if prom < 6.0 / 3.6 or (m1 - m0) < 60:
                del mins[k if seg[m0] > seg[m1] else k + 1]
                dropped = True
                break
        if not dropped:
            break
    if not mins:
        return [(a, b)]
    cuts = [0]
    for k in range(len(mins) - 1):
        m0, m1 = mins[k], mins[k + 1]
        cut = m0 + int(np.argmax(seg[m0:m1 + 1]))
        if np.sign(lat_s[a + m0]) != np.sign(lat_s[a + m1]):
            ls = lat_s[a + m0:a + m1 + 1]
            zc = np.where(np.sign(ls[:-1]) != np.sign(ls[1:]))[0]
            if len(zc):
                cut = m0 + int(zc[len(zc) // 2])
        cuts.append(cut)
    cuts.append(len(seg))
    return [(a + cuts[i], a + cuts[i + 1]) for i in range(len(cuts) - 1)]


def _detect_corners(sg, v, lg, tg_lap, ka, ch_lat):
    lat_abs = gsmooth(np.abs(ch_lat), 6)
    vs = gsmooth(v, 8)
    ls = gsmooth(ch_lat, 6)
    mask = lat_abs > 0.35
    raw = merge_close(segments(mask), 40)
    segs = []
    for a, b in raw:
        segs.extend(split_complex(vs, ls, a, b))
    segs = [(a, b) for a, b in segs if b - a >= 35 and lat_abs[a:b].max() > 0.5]
    out = []
    prev_end = 0
    for a, b in segs:
        ap = a + int(np.argmin(v[a:b]))
        c = dict(a=int(a), b=int(b), apex=int(ap), s_apex=float(sg[ap]), v_min=float(v[ap] * 3.6),
                 lat_pk=float(np.abs(ch_lat[a:b]).max()), dirn="L" if ch_lat[a:b].mean() > 0 else "R")
        # braking zone before the corner (but after the previous corner)
        lo = max(0, a - 300, prev_end)
        hi = max(lo + 5, min(ap, a + (b - a) // 2))
        j = lo + int(np.argmin(lg[lo:hi]))
        if lg[j] < -0.30:
            i0 = j
            while i0 > lo and lg[i0] < -0.10:
                i0 -= 1
            i1 = j
            while i1 < len(lg) - 1 and lg[i1] < -0.10 and i1 < ap + 60:
                i1 += 1
            pk = lg[j]
            # application: first sample at 90 % of peak deceleration; release: last sample still above 90 %
            up_ = np.where(lg[i0:j + 1] <= 0.9 * pk)[0]
            ja = i0 + int(up_[0]) if len(up_) else j
            dn_ = np.where(lg[j:i1 + 1] <= 0.9 * pk)[0]
            jr = j + int(dn_[-1]) if len(dn_) else j
            c.update(brake_peak_g=float(-pk),
                     brake_start_m=float(sg[ap] - sg[i0]),
                     brake_len_m=float(sg[i1] - sg[i0]),
                     v_entry=float(v[i0] * 3.6),
                     t_apply=float(tg_lap[ja] - tg_lap[i0]),
                     t_release=float(tg_lap[i1] - tg_lap[jr]),
                     trail_s=float(((lg[i0:i1 + 1] < -0.15) & (np.abs(ch_lat[i0:i1 + 1]) > 0.4)).sum()
                                   * np.mean(np.diff(tg_lap[i0:i1 + 1])) if i1 > i0 else 0.0))
        # throttle pick-up after the apex
        hi2 = min(len(lg), ap + 500)
        pu = np.where(lg[ap:hi2] > 0.10)[0]
        if len(pu):
            ip = ap + int(pu[0])
            win = slice(ip, min(len(lg), ip + 400))
            pk2 = lg[win].max()
            tgt = min(0.40, 0.8 * pk2)
            j2 = ip + int(np.argmax(lg[win] >= tgt))
            c.update(pickup_m=float(sg[ip] - sg[ap]), accel_pk_g=float(pk2),
                     t_ramp=float(tg_lap[j2] - tg_lap[ip]), v_exit=float(v[min(len(v) - 1, ap + 100)] * 3.6))
        # turn-in: curvature (wheel angle) ramp from 20 % to 80 % of its peak; a straight line = a clothoid
        kk = np.abs(ka)
        kpk_i = a + int(np.argmax(kk[a:b]))
        kpk = kk[kpk_i]
        if kpk > 0:
            i20 = kpk_i
            while i20 > max(0, a - 120) and kk[i20] > 0.2 * kpk:
                i20 -= 1
            i80 = kpk_i
            while i80 > i20 and kk[i80] > 0.8 * kpk:
                i80 -= 1
            sel = np.arange(i20, i80 + 1)
            m, r2 = linfit(sg[sel], kk[sel]) if len(sel) >= 8 else (float('nan'), float('nan'))
            if m == m and m > 0:
                c.update(turnin_dk_ds=m, turnin_r2=r2, turnin_len_m=float(sg[sel[-1]] - sg[sel[0]]),
                         k_peak=float(kpk))
        out.append(c)
        prev_end = b
    return out


def corner_metrics(sg, v, lg, tg_lap, ka, ch_lat, lap_time=None, lap_len=None, pad=400):
    """Per-corner measurements on one lap resampled to a 1 m distance grid.
    sg: distance (m); v: m/s; lg: long g; ch_lat: lat g (signed); ka: curvature (1/m); tg_lap: time (s).
    For a closed lap the arrays are wrapped by `pad` m so a braking zone that straddles the line is seen."""
    n = len(sg)
    if lap_time is None or lap_len is None or n < 2 * pad:
        return _detect_corners(sg, v, lg, tg_lap, ka, ch_lat)

    def ext(x, off=0.0):
        return np.concatenate([x[-pad:] - off, x, x[:pad] + off])
    out = _detect_corners(ext(sg, lap_len), ext(v), ext(lg), ext(tg_lap, lap_time), ext(ka), ext(ch_lat))
    res = []
    for c in out:
        if pad <= c["apex"] < pad + n:
            for k in ("a", "b", "apex"):
                c[k] = int(np.clip(c[k] - pad, 0, n - 1))
            c["s_apex"] = float(sg[c["apex"]])
            res.append(c)
    return res


# ================================================================== plotting
def _plt():
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 10.5, "axes.linewidth": 1.0})
    return plt


def _style(ax, ygrid=True, xgrid=False):
    ax.set_facecolor(SURF)
    for sp in ["top", "right"]:
        ax.spines[sp].set_visible(False)
    for sp in ["left", "bottom"]:
        ax.spines[sp].set_color(BASE)
    ax.tick_params(axis="both", length=0, labelsize=9.5, labelcolor=INK2, pad=4)
    if ygrid:
        ax.grid(axis="y", color=GRID, lw=1.0, ls="-")
    if xgrid:
        ax.grid(axis="x", color=GRID, lw=1.0, ls="-")
    ax.set_axisbelow(True)


def _title(fig, title, sub):
    H = fig.get_figheight()
    fig.text(0.05, 1 - 0.30 / H, title, fontsize=14.5, fontweight="bold", color=INK, ha="left", va="center")
    fig.text(0.05, 1 - 0.64 / H, sub, fontsize=9.5, color=INK2, ha="left", va="center")


def _top(fig):
    return 1 - 1.05 / fig.get_figheight()


# ================================================================== main analysis
def analyze(path, outdir, sf=None, plots=True):
    os.makedirs(outdir, exist_ok=True)
    rep = []

    def say(s=""):
        print(s)
        rep.append(s)

    D = load_session(path)
    clock_notes = fix_sensor_clock(D)
    A, Gy, L = D["A"], D["G"], D["L"]
    if len(A) < 500:
        raise SystemExit("Not enough accelerometer data in the file (%d rows)." % len(A))
    say("# Track Logger analysis")
    say("")
    say("File: `%s`" % os.path.basename(path))
    say("Phone: %s | Android %s" % (meta_value(D["meta"], "device"), meta_value(D["meta"], "android")))
    say("")
    say("## Data quality")
    dur = A[-1, 0] - A[0, 0]
    say("- Duration %.1f min | accelerometer %.0f Hz (%d rows) | gyro %s | GPS fixes %d%s" % (
        dur / 60, len(A) / dur, len(A),
        ("%.0f Hz" % (len(Gy) / (Gy[-1, 0] - Gy[0, 0]))) if len(Gy) > 100 else "MISSING",
        len(L), (" (%.2f Hz)" % ((len(L) - 1) / (L[-1, 0] - L[0, 0]))) if len(L) > 2 else ""))
    if D["bad"]:
        say("- %d unreadable rows were skipped (a cut-off last line is normal)." % D["bad"])
    mr = meta_value(D["meta"], "accel")
    if "range=" in mr:
        try:
            rng_ = float(mr.split("range=")[1].split(",")[0])
            if rng_ < 25.0:
                say("- WARNING: this phone's accelerometer only reads up to %.1f g; hard braking and cornering may clip." % (rng_ / G0))
        except ValueError:
            pass
    gaps = np.diff(A[:, 0])
    if gaps.max() > 0.25:
        say("- WARNING: accelerometer stalled %d time(s), longest %.2f s." % ((gaps > 0.25).sum(), gaps.max()))
    if clock_notes:
        say("- NOTE: this phone stamped its sensor events on a different clock than the GPS. Corrected using the "
            "K rows (%s). Lap times and positions are unaffected; sensor/GPS alignment is good to about 20 ms." % "; ".join(clock_notes))
    else:
        ks = [k[2] for k in D["K"]]
        if ks and (max(ks) > 1500 or min(ks) < -50):
            say("- WARNING: sensor clock lag looks odd (K rows %.0f..%.0f ms); alignment may be off." % (min(ks), max(ks)))
    if len(L) < 10:
        raise SystemExit("Fewer than 10 GPS fixes: no GPS lock during the session.")
    # time-base sanity: GPS and IMU must overlap
    if L[0, 0] > A[-1, 0] or L[-1, 0] < A[0, 0]:
        raise SystemExit("GPS and sensor time stamps do not overlap (different clocks on this phone).")
    good = (L[:, 6] < 0) | (L[:, 6] <= 25)
    L = L[good]
    hacc = L[:, 6][L[:, 6] > 0]
    sac = L[:, 7][L[:, 7] > 0]
    say("- GPS: median accuracy %s m, speed accuracy %s m/s, median delivery lag %.0f ms" % (
        ("%.1f" % np.median(hacc)) if len(hacc) else "n/a", ("%.2f" % np.median(sac)) if len(sac) else "n/a",
        np.median(L[:, 10])))

    # ---------------- uniform grid
    t0 = max(A[0, 0], Gy[0, 0] if len(Gy) > 100 else A[0, 0])
    t1 = min(A[-1, 0], Gy[-1, 0] if len(Gy) > 100 else A[-1, 0])
    tg = np.arange(t0, t1, 1.0 / FS)
    ag = np.column_stack([np.interp(tg, A[:, 0], A[:, i]) for i in (1, 2, 3)])
    has_gyro = len(Gy) > 100
    gg = np.column_stack([np.interp(tg, Gy[:, 0], Gy[:, i]) for i in (1, 2, 3)]) if has_gyro else np.zeros_like(ag)
    Lt, Lv = L[:, 0], np.maximum(L[:, 4], 0)
    Lsacc = L[:, 7]

    # ---------------- stationary window -> up, gyro bias
    cal = None
    for (te, name, vals) in D["E"]:
        if name == "CAL" and len(vals) >= 8 and vals[7] >= 1:
            i0 = int(np.searchsorted(tg, te - 4.0))
            i1 = int(np.searchsorted(tg, te))
            if i1 - i0 > 200:
                m = ag[i0:i1].mean(axis=0)
                if abs(np.linalg.norm(m) - G0) < 1.0:
                    cal = (i0, i1, m, gg[i0:i1].mean(axis=0), vals[3])
    src = "app calibration window"
    moving_only = False
    if cal is None:
        r = find_static(tg, ag, gg, Lt, Lv)
        if r is None:
            # no standstill anywhere in the file: a first guess now, replaced by a fit to the drive itself below
            moving_only = True
            cr = find_cruise(tg, ag, gg, Lt, Lv)
            if cr is not None:
                cal = (0, len(tg), cr[0], cr[1], float("nan"))
                src = "steady cruising"
            else:
                cal = (0, len(tg), ag.mean(axis=0), np.median(gg, axis=0), float("nan"))
                src = "session average"
        else:
            cal = r
            src = "found automatically"
    i0, i1, gmean, gbias, noise = cal
    gnorm = float(np.linalg.norm(gmean))
    up = gmean / gnorm
    yaw_up = (gg - gbias) @ up

    # ---------------- forward axis
    fa = forward_axis(tg, ag, yaw_up, up, Lt, Lv)
    f = fa["f"]
    lat_ax = np.cross(up, f)
    tau = fa["tau"]
    orient = orientation_from_gps(tg, ag, gg, Lt, Lv, L[:, 5], tau) if moving_only else None
    if orient is not None:
        up, f, lat_ax = orient["up"], orient["f"], orient["lat"]
        gbias, gnorm, tau = orient["gbias"], orient["gnorm"], orient["tau"]
        yaw_up = dot_rows(gg - gbias, up)
        fa = dict(tau=tau, n=orient["n"], r2=orient["r2"], gain=orient["gain"])
        say("- No standstill in this file (the recording began on the move, or the car never stopped), so the phone's "
            "mounting angle was fitted to the drive itself: the accelerometer against the GPS speed change and the GPS "
            "course change, over a sliding window of about a minute (%d GPS intervals, residual %.1f m/s2, GPS time lag %.2f s)." % (
                orient["n"], orient["rms"], tau))
        if orient["wander_med"] > 3.0 or orient["wander_max"] > 8.0:
            say("  - WARNING: the phone did not stay put. Its angle about the vertical drifted by %.0f degrees (median) and up to "
                "%.0f degrees from its average, and its tilt by %.0f degrees. Braking and cornering numbers from the accelerometer "
                "are less certain than usual; GPS speeds and lap times are not affected much." % (
                    orient["wander_med"], orient["wander_max"], orient["wander_up_med"]))
        if abs(orient["gain"] - 1.0) > 0.03:
            say("  - The accelerometer reads %.0f %% %s than the GPS-derived accelerations (a mount that flexes does this)." % (
                abs(orient["gain"] - 1.0) * 100, "lower" if orient["gain"] < 1 else "higher"))
    else:
        if moving_only:
            if src == "steady cruising":
                say("- WARNING: the car never stood still for 3 s. Orientation taken from steady straight-line driving "
                    "instead (a few degrees less accurate). Start each session parked and still.")
            else:
                say("- WARNING: the car never stood still and the drive could not be used to find the mounting angle. "
                    "Orientation taken from the session average, which can be 10-15 degrees off on a one-directional "
                    "circuit. Start each session parked.")
        say("- Stationary calibration (%s) at t = %.0f..%.0f s: gravity %.2f m/s2, noise %.2f m/s2" % (
            src, tg[i0], tg[min(i1, len(tg) - 1)], gnorm, noise))
        say("- Forward axis found from %d GPS acceleration samples: R2 = %.2f, gain %.2f, GPS time lag %.2f s" % (
            fa["n"], fa["r2"], fa["gain"], fa["tau"]))
    if fa["r2"] < 0.6:
        say("  - WARNING: low R2. The phone may have moved in its mount, or the drive had too few straights.")

    # ---------------- car-frame channels
    a_long_raw = dot_rows(ag, f)
    a_lat_raw = dot_rows(ag, lat_ax)
    a_up = dot_rows(ag, up) - gnorm
    sg_ = 0.04 * FS
    a_long = gsmooth(a_long_raw, sg_)
    a_lat = gsmooth(a_lat_raw, sg_)
    yaw = gsmooth(yaw_up, sg_) if has_gyro else None

    # the accelerometer's error model is widened until the filter's own innovations say it is right (a loose mount or a
    # fitted orientation needs far more than a rigid, calibrated phone does)
    q_scale = 1.0
    for _it in range(8):
        fdiag = {}
        v, bias, gated = fuse_speed(tg, a_long_raw, Lt, Lv, Lsacc, tau, q_scale=q_scale, diag=fdiag)
        q_new = min(max(q_scale * fdiag["nis_med"] / 0.455, 1.0), 3000.0) if fdiag["nis_med"] > 0 else q_scale
        if abs(math.log(q_new / q_scale)) < 0.4:
            break
        q_scale = q_new
    vg_at = np.interp(tg, Lt - tau, Lv)
    fuse_rms = float(np.sqrt(np.mean((np.interp(Lt - tau, tg, v) - Lv) ** 2)))
    say("- Speed fusion: accelerometer bias %.2f m/s2 (median), %d GPS fixes skipped as outliers, %d resets to GPS; "
        "fused speed agrees with the GPS fixes to %.2f m/s rms%s" % (
            float(np.median(bias)), gated, fdiag["resets"], fuse_rms,
            (" (the accelerometer's error model was widened %.0f-fold: it does not follow the car as closely as a rigidly "
             "mounted, calibrated phone would)" % q_scale) if q_scale > 1.5 else ""))
    if fuse_rms > 1.0:
        say("  - WARNING: the fused speed and the GPS disagree by more than 1 m/s rms; speeds between GPS fixes are uncertain.")
    if yaw is None:
        yaw = a_lat / np.maximum(v, 3.0)
        say("- No gyro: yaw rate estimated from lateral acceleration (noisier).")
    kap = np.where(v > 3.0, yaw / np.maximum(v, 3.0), 0.0)
    kap = gsmooth(kap, 0.05 * FS)
    mv = v > 5.0
    if mv.sum() > 500:
        cc = np.corrcoef(a_lat[mv], v[mv] * yaw[mv])[0, 1]
        ratio = float(np.dot(a_lat[mv], v[mv] * yaw[mv]) / np.dot(v[mv] * yaw[mv], v[mv] * yaw[mv]))
        say("- Cross-check: accelerometer lateral g vs speed x gyro yaw rate: correlation %.3f, ratio %.2f "
            "(1.00 = perfect; a mismatch of 5-10 %% is roll and mount flex)." % (cc, ratio))
        if cc < 0.5:
            say("  - WARNING: weak agreement. Check the mount and re-run with a fresh session.")
    s = np.concatenate([[0.0], np.cumsum(0.5 * (v[1:] + v[:-1])) / FS])

    # ---------------- GPS path and laps
    lat0, lon0 = L[0, 1], L[0, 2]
    X, Y = to_enu(L[:, 1], L[:, 2], lat0, lon0)
    tt, px, py, okp = hermite_path(Lt - tau, X, Y, Lv, L[:, 5])      # fix time stamps are late by tau
    step_tt = float(tt[1] - tt[0]) if len(tt) > 1 else 0.05
    gaps = []                                       # (start time, length) of every stretch with no GPS fix for more than 2.5 s
    if len(tt) > 1 and (~okp).any():
        edges = np.diff(np.r_[0, (~okp).astype(int), 0])
        for a_, b_ in zip(np.where(edges == 1)[0], np.where(edges == -1)[0]):
            gaps.append((float(tt[a_]), float((b_ - a_) * step_tt)))
    if gaps:
        say("- GPS gaps: %d stretch(es) with no fix for more than 2.5 s, longest %.0f s." % (len(gaps), max(g_[1] for g_ in gaps)))
    if sf is not None:
        x0, y0 = to_enu(np.array([sf[0]]), np.array([sf[1]]), lat0, lon0)
        p0 = np.array([x0[0], y0[0]])
        dd = np.hypot(px - p0[0], py - p0[1])
        k0 = int(np.argmin(dd))
        say("- Start/finish line given: closest pass %.0f m from it." % dd[k0])
    else:
        # the lap line goes at the fastest point of the session, but only where GPS fixes support the path: inside a GPS gap the
        # fused speed can drift high and the crossings there cannot be found (laps would silently merge)
        sup = np.zeros(len(tg), bool)
        if len(tt) > 1:
            ix = np.clip(np.searchsorted(tt, tg, side="right") - 1, 0, len(tt) - 1)
            sup = okp[ix] & (tg >= tt[0]) & (tg <= tt[-1]) & (np.abs(v - vg_at) < 3.0)
        k_t = int(np.argmax(np.where(sup, v, -1.0))) if sup.any() else int(np.argmax(v))
        tk = tg[k_t]
        k0 = int(np.argmin(np.abs(tt - tk)))
        p0 = np.array([px[k0], py[k0]])
    kk = min(max(k0, 1), len(tt) - 2)
    dvec = np.array([px[kk + 1] - px[kk - 1], py[kk + 1] - py[kk - 1]])
    dvec /= max(np.linalg.norm(dvec), 1e-9)
    cross = find_crossings(tt, px, py, okp, p0, dvec)
    say("")
    say("## Laps")
    if sf is None:
        say("Lap line: automatic, at the fastest point of the session (lat %.6f, lon %.6f). Pass `--sf lat,lon` to use "
            "the real start/finish line." % (lat0 + p0[1] / 111320.0,
                                             lon0 + p0[0] / (111320.0 * math.cos(math.radians(lat0)))))
    laps = []
    if len(cross) >= 2:
        for i in range(len(cross) - 1):
            ta, tb = cross[i], cross[i + 1]
            ia, ib = int(np.searchsorted(tg, ta)), int(np.searchsorted(tg, tb))
            if ib - ia < 100:
                continue
            mt = (tt >= ta) & (tt < tb)
            laps.append(dict(i=i + 1, ta=ta, tb=tb, time=tb - ta, dist=float(s[ib] - s[ia]),
                             vmax=float(v[ia:ib].max() * 3.6), vmin=float(v[ia:ib].min() * 3.6),
                             nogps=float(np.sum(~okp[mt]) * step_tt)))       # seconds of the lap spent in a stretch with no fix for > 2.5 s
    flying = []
    if laps:
        tsort = sorted(l["time"] for l in laps)
        ref_t = float(np.median(tsort[:max(1, (len(tsort) + 1) // 2)]))
        med_d = float(np.median([l["dist"] for l in laps]))
        for l in laps:
            l["flying"] = l["time"] <= 1.08 * ref_t and abs(l["dist"] - med_d) < 0.06 * med_d
            if l["flying"]:
                flying.append(l)
    # the map is drawn from the best lap, so a lap with a stretch of missing GPS (a straight line across the gap) is only used
    # when every flying lap has one
    best = min(flying, key=lambda l: (l["nogps"] > 0.0, l["nogps"], l["time"])) if flying else None
    if laps:
        say("")
        say("| Lap | Time | Distance m | Max km/h | Min km/h | Note |")
        say("|---|---|---|---|---|---|")
        for l in laps:
            mm, ss = divmod(l["time"], 60)
            note = "BEST" if l is best else ("" if l["flying"] else "in/out/slow lap")
            if l["nogps"] > 0.0:
                note = (note + " " if note else "") + "(no GPS for %.0f s)" % l["nogps"]
            say("| %d | %d:%06.3f | %.0f | %.0f | %.0f | %s |" % (l["i"], mm, ss, l["dist"], l["vmax"], l["vmin"], note))
    else:
        say("No complete laps were found (fewer than two crossings). The whole drive is analysed as one run.")

    # ---------------- reference run on a distance grid
    if best is not None:
        ref = best
        ia, ib = int(np.searchsorted(tg, ref["ta"])), int(np.searchsorted(tg, ref["tb"]))
    else:
        mov = np.where(v > 2.0)[0]
        ia, ib = (int(mov[0]), int(mov[-1])) if len(mov) else (0, len(tg) - 1)
        ref = dict(i=0, ta=tg[ia], tb=tg[ib], time=tg[ib] - tg[ia], dist=float(s[ib] - s[ia]))
    Lref = ref["dist"]
    grid = np.arange(0.0, Lref, 1.0)

    def resample(l):
        ia_, ib_ = int(np.searchsorted(tg, l["ta"])), int(np.searchsorted(tg, l["tb"]))
        sr = (s[ia_:ib_] - s[ia_]) * (Lref / max(l["dist"], 1.0))
        sr = np.maximum.accumulate(sr + 1e-6 * np.arange(len(sr)))
        out = {}
        for name, arr in (("v", v), ("lg", a_long / G0), ("tg", a_lat / G0), ("k", kap), ("yaw", yaw), ("t", tg)):
            out[name] = np.interp(grid, sr, arr[ia_:ib_])
        out["t"] = out["t"] - l["ta"]
        return out

    R = resample(ref)
    closed = best is not None
    corners = corner_metrics(grid, R["v"], gsmooth(R["lg"], 3), R["t"], R["k"], gsmooth(R["tg"], 3),
                             lap_time=ref["time"] if closed else None, lap_len=Lref if closed else None)
    for n_, c in enumerate(corners, 1):
        c["id"] = n_

    say("")
    say("## Corners (reference: %s)" % ("best lap" if best is not None else "whole drive"))
    if corners:
        say("")
        say("| # | dir | apex m | entry km/h | min km/h | exit km/h | brake from (m before apex) | peak brake g | "
            "apply s | release s | trail s | peak lat g | pickup m after apex | accel ramp s | turn-in dk/ds | turn-in R2 |")
        say("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")

        def g_(c, k, fmt="%.1f"):
            return fmt % c[k] if k in c and c[k] == c[k] else "-"
        for c in corners:
            say("| C%d | %s | %.0f | %s | %.0f | %s | %s | %s | %s | %s | %s | %.2f | %s | %s | %s | %s |" % (
                c["id"], c["dirn"], c["s_apex"], g_(c, "v_entry", "%.0f"), c["v_min"], g_(c, "v_exit", "%.0f"),
                g_(c, "brake_start_m", "%.0f"), g_(c, "brake_peak_g", "%.2f"), g_(c, "t_apply", "%.2f"),
                g_(c, "t_release", "%.2f"), g_(c, "trail_s", "%.2f"), c["lat_pk"], g_(c, "pickup_m", "%.0f"),
                g_(c, "t_ramp", "%.2f"), g_(c, "turnin_dk_ds", "%.1e"), g_(c, "turnin_r2", "%.2f")))
        say("")
        say("How to read it: *apply* = time from first brake pressure (-0.1 g) to 90 % of peak deceleration; "
            "*release* = time from 90 % of peak back to -0.1 g; *trail* = time braking AND cornering together; "
            "*accel ramp* = time from +0.1 g to +0.4 g (or 80 % of the peak if lower) on the way out; *turn-in dk/ds* = how fast "
            "curvature (the wheel angle) is wound on per metre, and *R2* = how straight that ramp is (1.00 = a perfect "
            "linear ramp).")
    else:
        say("No corners above 0.5 g were found in this run.")

    # ---------------- g-g
    sel = v > 8.0
    lgf, ltf = (a_long[sel] / G0), (a_lat[sel] / G0)
    if len(lgf):
        say("")
        say("## g-g envelope (speed > 8 m/s)")
        say("- Peak braking %.2f g | peak acceleration %.2f g | peak lateral %.2f g left, %.2f g right" % (
            -np.percentile(lgf, 0.3), np.percentile(lgf, 99.7), np.percentile(ltf, 99.7), -np.percentile(ltf, 0.3)))

    # ---------------- files
    step = int(FS // 10)
    lap_id = np.zeros(len(tg), int)
    for l in laps:
        lap_id[int(np.searchsorted(tg, l["ta"])):int(np.searchsorted(tg, l["tb"]))] = l["i"]
    xx = np.interp(tg, tt, px)
    yy = np.interp(tg, tt, py)
    with open(os.path.join(outdir, "channels.csv"), "w") as f_:
        f_.write("t_s,lap,dist_m,speed_kmh,long_g,lat_g_left_pos,yaw_dps_left_pos,curvature_1pm,vert_g_delta,x_east_m,y_north_m\n")
        for k in range(0, len(tg), step):
            f_.write("%.2f,%d,%.1f,%.2f,%.3f,%.3f,%.2f,%.5f,%.3f,%.1f,%.1f\n" % (
                tg[k] - tg[0], lap_id[k], s[k], v[k] * 3.6, a_long[k] / G0, a_lat[k] / G0,
                math.degrees(yaw[k]), kap[k], a_up[k] / G0, xx[k], yy[k]))
    with open(os.path.join(outdir, "lap_table.csv"), "w") as f_:
        f_.write("lap,time_s,distance_m,vmax_kmh,vmin_kmh,flying\n")
        for l in laps:
            f_.write("%d,%.3f,%.1f,%.1f,%.1f,%d\n" % (l["i"], l["time"], l["dist"], l["vmax"], l["vmin"], l["flying"]))
    keys = ["id", "dirn", "s_apex", "v_entry", "v_min", "v_exit", "brake_start_m", "brake_len_m", "brake_peak_g",
            "t_apply", "t_release", "trail_s", "lat_pk", "pickup_m", "accel_pk_g", "t_ramp", "turnin_dk_ds",
            "turnin_r2", "turnin_len_m", "k_peak"]
    with open(os.path.join(outdir, "corner_table.csv"), "w") as f_:
        f_.write(",".join(keys) + "\n")
        for c in corners:
            f_.write(",".join(str(c.get(k, "")) for k in keys) + "\n")

    res = dict(tg=tg, v=v, s=s, a_long=a_long, a_lat=a_lat, yaw=yaw, kap=kap, laps=laps, best=best, ref=ref,
               grid=grid, R=R, corners=corners, X=X, Y=Y, L=L, p0=p0, resample=resample, fa=fa, up=up, f=f,
               flying=flying, px=px, py=py, tt=tt, gaps=gaps)
    if plots:
        make_plots(res, outdir, say)
    with open(os.path.join(outdir, "report.md"), "w", encoding="utf-8") as f_:   # utf-8: a file name may not be plain ASCII
        f_.write("\n".join(rep) + "\n")
    print("\nFiles written to %s" % os.path.abspath(outdir))
    return res


def make_plots(res, outdir, say):
    plt = _plt()
    grid, R, corners, laps, best, flying = res["grid"], res["R"], res["corners"], res["laps"], res["best"], res["flying"]
    ref = res["ref"]
    ttl = "Best lap" if best is not None else "Whole drive"

    # ---- figure 1: channels along the lap
    fig, axs = plt.subplots(4, 1, figsize=(12, 8.6), dpi=150, facecolor=SURF, sharex=True)
    fig.subplots_adjust(left=0.07, right=0.985, top=_top(fig), bottom=0.07, hspace=0.18)
    _title(fig, "%s: speed, pedals and wheel along the lap" % ttl,
           "Brake = negative long. g | throttle = positive long. g | wheel = curvature (yaw rate / speed). Shaded = corners.")
    for a in axs:
        _style(a)
        for c in corners:
            a.axvspan(grid[c["a"]], grid[c["b"] - 1], color=GRID, alpha=0.55, lw=0)
    axs[0].plot(grid, R["v"] * 3.6, color=BLUE, lw=1.8)
    axs[0].set_ylabel("km/h", color=INK2)
    axs[1].axhline(0, color=BASE, lw=1.0)
    axs[1].plot(grid, R["lg"], color=ORANGE, lw=1.5)
    axs[1].set_ylabel("long. g", color=INK2)
    axs[2].axhline(0, color=BASE, lw=1.0)
    axs[2].plot(grid, R["tg"], color=AQUA, lw=1.5)
    axs[2].set_ylabel("lateral g (left +)", color=INK2)
    axs[3].axhline(0, color=BASE, lw=1.0)
    axs[3].plot(grid, R["k"] * 1000, color=INK2, lw=1.4)
    axs[3].set_ylabel("curvature 1/km", color=INK2)
    axs[3].set_xlabel("distance along lap (m)", color=INK2)
    for c in corners:
        axs[0].text(grid[c["apex"]], R["v"][c["apex"]] * 3.6 - 6, "C%d" % c["id"], ha="center", va="top",
                    fontsize=8.5, color=INK2)
    fig.savefig(os.path.join(outdir, "fig_best_lap.png"), facecolor=SURF)
    plt.close(fig)

    # ---- figure 2: g-g
    tg_, v = res["tg"], res["v"]
    sel = v > 8.0
    lgf, ltf = res["a_long"][sel] / G0, res["a_lat"][sel] / G0
    fig, ax = plt.subplots(figsize=(7.2, 7.2), dpi=150, facecolor=SURF)
    fig.subplots_adjust(left=0.12, right=0.97, top=_top(fig), bottom=0.10)
    _style(ax, ygrid=False)
    ax.grid(color=GRID, lw=1.0)
    ax.scatter(ltf, lgf, s=3, color=BLUE, alpha=0.12, lw=0)
    ang = np.arctan2(lgf, ltf)
    r = np.hypot(ltf, lgf)
    bins = np.linspace(-np.pi, np.pi, 37)
    env = []
    for i in range(36):
        m = (ang >= bins[i]) & (ang < bins[i + 1])
        env.append((0.5 * (bins[i] + bins[i + 1]), np.percentile(r[m], 98) if m.sum() > 20 else np.nan))
    env = np.array(env)
    okm = ~np.isnan(env[:, 1])
    if okm.sum() > 6:
        a_ = np.append(env[okm, 0], env[okm, 0][0])
        r_ = np.append(env[okm, 1], env[okm, 1][0])
        ax.plot(r_ * np.cos(a_), r_ * np.sin(a_), color=ORANGE, lw=2.0)
        ax.text(0.02, 0.97, "orange = envelope you actually used (98th percentile by direction)",
                transform=ax.transAxes, fontsize=9, color=INK2, va="top")
    ax.set_xlabel("lateral g (left +)", color=INK2)
    ax.set_ylabel("longitudinal g (accelerating +)", color=INK2)
    lim = max(1.6, float(np.nanmax(r)) * 1.05) if len(r) else 1.6
    ax.set_xlim(-lim, lim)
    ax.set_ylim(-lim, lim)
    ax.set_aspect("equal")
    ax.axhline(0, color=BASE, lw=1.0)
    ax.axvline(0, color=BASE, lw=1.0)
    _title(fig, "g-g diagram of the session", "Every sample above 8 m/s. A full circle with no gaps = the tyres were used in every direction.")
    fig.savefig(os.path.join(outdir, "fig_gg.png"), facecolor=SURF)
    plt.close(fig)

    # ---- figure 3: laps overlay and delta
    if best is not None and len(flying) >= 2:
        fig, axs = plt.subplots(2, 1, figsize=(12, 7.2), dpi=150, facecolor=SURF, sharex=True,
                                gridspec_kw=dict(height_ratios=[1.15, 1.0]))
        fig.subplots_adjust(left=0.07, right=0.93, top=_top(fig), bottom=0.08, hspace=0.12)
        _style(axs[0])
        _style(axs[1])
        _title(fig, "Lap by lap", "Blue = best lap. Grey = other flying laps. Below: time lost (+) or gained (-) against the best lap.")
        Rb = res["resample"](best)
        for l in flying:
            if l is best:
                continue
            Rl = res["resample"](l)
            axs[0].plot(grid, Rl["v"] * 3.6, color=BASE, lw=1.0)
            d = Rl["t"] - Rb["t"]
            axs[1].plot(grid, d, color=BASE, lw=1.0)
            axs[1].text(grid[-1] + 8, d[-1], "L%d  %+.2f s" % (l["i"], d[-1]), fontsize=8.5, color=INK2, va="center")
        axs[0].plot(grid, Rb["v"] * 3.6, color=BLUE, lw=2.0)
        axs[1].axhline(0, color=BLUE, lw=2.0)
        axs[0].set_ylabel("km/h", color=INK2)
        axs[1].set_ylabel("delta time (s)", color=INK2)
        axs[1].set_xlabel("distance along lap (m)", color=INK2)
        mm, ss = divmod(best["time"], 60)
        axs[0].text(0.01, 0.06, "best: lap %d, %d:%06.3f" % (best["i"], mm, ss), transform=axs[0].transAxes,
                    fontsize=10, color=BLUE, fontweight="bold")
        fig.savefig(os.path.join(outdir, "fig_laps.png"), facecolor=SURF)
        plt.close(fig)

    # ---- figure 4: map coloured by speed
    L = res["L"]
    X, Y = res["X"], res["Y"]
    fig, ax = plt.subplots(figsize=(8.4, 8.0), dpi=150, facecolor=SURF)
    fig.subplots_adjust(left=0.08, right=0.90, top=_top(fig), bottom=0.07)
    _style(ax, ygrid=False)
    px, py, tt = res["px"], res["py"], res["tt"]
    vv = np.interp(tt, res["tg"], res["v"]) * 3.6
    from matplotlib.collections import LineCollection
    pts = np.column_stack([px, py]).reshape(-1, 1, 2)
    segs = np.concatenate([pts[:-1], pts[1:]], axis=1)
    lc = LineCollection(segs, array=vv[:-1], cmap="viridis", linewidths=2.4)
    ax.add_collection(lc)
    ax.autoscale()
    ax.set_aspect("equal")
    cb = fig.colorbar(lc, ax=ax, fraction=0.035, pad=0.02)
    cb.set_label("km/h", color=INK2)
    cb.outline.set_edgecolor(BASE)
    cb.ax.tick_params(colors=INK2, length=0)
    ax.plot(res["p0"][0], res["p0"][1], marker="s", color=ORANGE, ms=8, ls="none")
    ax.text(res["p0"][0], res["p0"][1], "  line", color=INK2, fontsize=9, va="center")
    if best is not None:
        ia = int(np.searchsorted(tt, best["ta"]))
        for c in corners:
            g = int(c["s_apex"])
            tgl = best["ta"] + (res["R"]["t"][min(g, len(res["R"]["t"]) - 1)])
            k = int(np.searchsorted(tt, tgl))
            k = min(k, len(px) - 1)
            ax.text(px[k], py[k], "C%d" % c["id"], fontsize=9, fontweight="bold", color=INK, ha="center", va="bottom")
    ax.set_xlabel("east (m)", color=INK2)
    ax.set_ylabel("north (m)", color=INK2)
    _title(fig, "Where you drove", "GPS path coloured by fused speed. C1, C2... = corners found, in order from the line.")
    fig.savefig(os.path.join(outdir, "fig_map.png"), facecolor=SURF)
    plt.close(fig)

    # ---- figure 5: per-corner small multiples (long g and lat g against time from apex)
    if corners:
        n = len(corners)
        cols = 4
        rows = int(math.ceil(n / cols))
        Hh = 2.6 * rows + 1.5
        fig, axs = plt.subplots(rows, cols, figsize=(13, Hh), dpi=130, facecolor=SURF, squeeze=False)
        fig.subplots_adjust(left=0.05, right=0.985, top=1 - 1.25 / Hh, bottom=0.05, wspace=0.22, hspace=0.55)
        _title(fig, "Each corner: brake and throttle against time from the apex",
               "Bold = reference lap, thin = other flying laps. A clean ramp is a straight slope; a wobble is the hand or foot hunting.")
        from matplotlib.lines import Line2D
        fig.legend(handles=[Line2D([0], [0], color=ORANGE, lw=2.2, label="long. g  (below 0 = braking, above 0 = throttle)"),
                            Line2D([0], [0], color=AQUA, lw=2.2, label="lateral g  (absolute)")],
                   loc="upper left", bbox_to_anchor=(0.05, 1 - 0.78 / Hh), ncol=2, frameon=False, fontsize=9.5,
                   labelcolor=INK2, handlelength=1.6)
        laps_draw = [l for l in flying if l is not best][:5]
        cache = {l["i"]: res["resample"](l) for l in laps_draw}
        for idx, c in enumerate(corners):
            a = axs[idx // cols][idx % cols]
            _style(a)
            ap = c["apex"]
            for l in laps_draw:
                Rl = cache[l["i"]]
                tr = Rl["t"] - Rl["t"][ap]
                m = (tr > -7) & (tr < 5)
                a.plot(tr[m], Rl["lg"][m], color=ORANGE, lw=0.7, alpha=0.35)
                a.plot(tr[m], np.abs(Rl["tg"][m]), color=AQUA, lw=0.7, alpha=0.35)
            tr = R["t"] - R["t"][ap]
            m = (tr > -7) & (tr < 5)
            a.plot(tr[m], R["lg"][m], color=ORANGE, lw=2.0)
            a.plot(tr[m], np.abs(R["tg"][m]), color=AQUA, lw=2.0)
            a.axhline(0, color=BASE, lw=1.0)
            a.axvline(0, color=BASE, lw=1.0)
            a.set_title("C%d  %s  apex %.0f km/h" % (c["id"], c["dirn"], c["v_min"]), fontsize=9.5, color=INK, loc="left")
            a.set_ylim(-1.8, 1.8)
        for idx in range(n, rows * cols):
            axs[idx // cols][idx % cols].axis("off")
        fig.savefig(os.path.join(outdir, "fig_corners.png"), facecolor=SURF)
        plt.close(fig)


def main():
    for st in (sys.stdout, sys.stderr):                      # a file name the console cannot show must not stop the analysis
        try:
            st.reconfigure(errors="replace")
        except Exception:
            pass
    ap = argparse.ArgumentParser(description="Analyse a Track Logger session CSV.")
    ap.add_argument("csv")
    ap.add_argument("--out", default=None, help="output folder (default: <csv name>_analysis)")
    ap.add_argument("--sf", default=None, help="start/finish line as LAT,LON (optional)")
    ap.add_argument("--no-plots", action="store_true")
    a = ap.parse_args()
    out = a.out or (os.path.splitext(a.csv)[0] + "_analysis")
    sf = None
    if a.sf:
        la, lo = a.sf.split(",")
        sf = (float(la), float(lo))
    analyze(a.csv, out, sf=sf, plots=not a.no_plots)


if __name__ == "__main__":
    main()

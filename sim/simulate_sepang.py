#!/usr/bin/env python3
"""Simulate a Sepang track-day session as the Android app would log it, with planted driver mistakes and exact truth.

Every flying lap is a physically consistent quasi-steady-state (QSS) lap of a 991.2 GT3 on a driven line, where the
driver's execution in each of the 15 corners is described by four numbers (all planted, all known):
    beta   braking effort   (1.0 = full grip-limited braking, lower = brakes earlier)
    gv     corner speed     (1.0 = grip-limited, lower = slower through the corner)
    lam    throttle pick-up (m; 0 = instant, larger = later full throttle after the apex)
    dlt    line             (m wider than the ideal line at the apex; longer path, lower grip-limited speed)
Truth outputs: exact time at which each lap passes each racing-line node, exact lap times, true per-corner metrics,
the perfect lap, and a function that re-simulates any mix of laps' corners (the physically consistent composite).
"""
import argparse
import json
import math
import os
import sys
from dataclasses import replace

import numpy as np
from numpy.random import default_rng
from scipy.interpolate import CubicSpline

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
ENGINE_DIR = os.environ.get("ENGINE_DIR", os.path.join(REPO, "analysis"))      # analyze.py / ultimate.py live here
OUT = os.environ.get("SIM_OUT", os.path.join(HERE, "out"))                    # simulated sessions and analysis folders are written here
sys.path.insert(0, os.path.join(REPO, "physics"))
from gt3_model import Car, a_accel, a_brake, G as GM   # noqa: E402

MODEL_DIR = os.environ.get("SEPANG_MODEL_DIR", os.path.join(REPO, "analysis"))
R_E = 6378137.0
G0 = 9.80665
DS = 0.5                                                  # fine grid [m]
CAR = replace(Car(), eta=0.80, CdA=0.66, mu_x=1.2, mu_y=1.2)


# ======================================================================================== model / path
class Model:
    def __init__(self, d=MODEL_DIR):
        z = np.load(f"{d}/sepang_model.npz")
        meta = json.load(open(f"{d}/sepang_model.json"))
        self.meta = meta
        g = meta["georef"]
        P = np.column_stack([z["xr"], z["yr"]])
        Pc = np.array(meta["centre_xy"])
        th = g["theta_rad"]
        Rm = np.array([[math.cos(th), -math.sin(th)], [math.sin(th), math.cos(th)]])
        self.enu = (P - Pc) @ Rm.T + np.array(g["shift_m"])      # racing line in ENU metres about (lat0, lon0)
        self.lat0, self.lon0 = g["lat0"], g["lon0"]
        self.n = len(P)
        self.sr = z["sr"]
        self.Lr = float(z["Lr"])
        self.s_center = z["s"]
        self.corners = meta["corners"]
        self.K = len(self.corners)
        # node index of each corner apex / window (centreline nodes are 5 m apart, same count as the racing line)
        self.i_apex = np.array([int(round(c["s_apex"] / 5.0)) % self.n for c in self.corners])
        self.i_in = np.array([int(round(c["s_in"] / 5.0)) % self.n for c in self.corners])
        self.i_out = np.array([int(round(c["s_out"] / 5.0)) % self.n for c in self.corners])
        self.sign = np.array([1.0 if c["dir"] == "R" else -1.0 for c in self.corners])   # +1 right (centre on right)
        # left normal of travel
        d = np.roll(self.enu, -1, axis=0) - self.enu
        t = d / np.linalg.norm(d, axis=1)[:, None]
        self.nleft = np.column_stack([-t[:, 1], t[:, 0]])

    def to_latlon(self, x, y):
        lat = self.lat0 + np.degrees(y / R_E)
        lon = self.lon0 + np.degrees(x / (R_E * math.cos(math.radians(self.lat0))))
        return lat, lon


def node_offsets(M, dlt):
    """Lateral offset [m] at every racing-line node from the per-corner apex offsets (Gaussian bumps)."""
    n = M.n
    off = np.zeros(n)
    idx = np.arange(n)
    for k in range(M.K):
        dist = np.minimum(np.abs(idx - M.i_apex[k]), n - np.abs(idx - M.i_apex[k])) * 5.0
        off += dlt[k] * M.sign[k] * np.exp(-0.5 * (dist / 35.0) ** 2)
    # keep the start/finish area on the racing line so consecutive laps join
    dist0 = np.minimum(idx, n - idx) * 5.0
    taper = np.clip((dist0 - 40.0) / 80.0, 0.0, 1.0)
    return off * taper


def fine_path(M, dlt):
    """Spline the driven line, resample to DS metres.  Returns dict with x, y (ENU), kappa (left +), node_pos
    (fine index at which each of the n nodes is passed), s (arc length), L."""
    nodes = M.enu + M.nleft * node_offsets(M, dlt)[:, None]
    P = np.vstack([nodes, nodes[:1]])
    chord = np.r_[0, np.cumsum(np.hypot(*np.diff(P, axis=0).T))]
    cs = CubicSpline(chord, P, bc_type="periodic")
    u = np.arange(0, chord[-1], 0.25)
    xy = cs(u)
    seg = np.hypot(*np.diff(np.vstack([xy, xy[:1]]), axis=0).T)
    arc = np.r_[0, np.cumsum(seg)]
    Ltot = arc[-1]
    sf = np.arange(0, Ltot, DS)
    uf = np.interp(sf, arc[:-1], u)
    xyf = cs(uf)
    d1 = cs(uf, 1)
    d2 = cs(uf, 2)
    kap = (d1[:, 0] * d2[:, 1] - d1[:, 1] * d2[:, 0]) / np.maximum((d1[:, 0] ** 2 + d1[:, 1] ** 2) ** 1.5, 1e-9)
    th = np.arctan2(d1[:, 1], d1[:, 0])
    node_s = np.interp(chord[:-1], u, arc[:-1])            # arc length at which each node is passed
    return dict(x=xyf[:, 0], y=xyf[:, 1], kap=kap, th=th, s=sf, L=float(Ltot), node_s=node_s)


# ======================================================================================== QSS with a driver
def qss_driver(path, M, prm, mu_scale=1.0):
    """Periodic QSS on `path`; prm = dict(beta[K], gv[K], lam[K]).  Returns v (m/s) on the fine grid."""
    n = len(path["s"])
    kk = np.abs(path["kap"])
    car = replace(CAR, mu_x=CAR.mu_x * mu_scale, mu_y=CAR.mu_y * mu_scale)
    k0 = car.mu_y * 0.5 * car.rho * car.ClA / car.m
    vl = np.full(n, 120.0)
    m = kk > k0
    vl[m] = np.minimum(120.0, np.sqrt(car.mu_y * GM / (kk[m] - k0)))
    # map fine index -> fractional node index
    node_idx = np.interp(path["s"], np.r_[path["node_s"], path["L"]], np.r_[np.arange(M.n), M.n])
    # per-node region parameters
    beta = np.ones(n)
    gv = np.ones(n)
    accm = np.ones(n)
    K = M.K
    ia = M.i_apex.astype(float)
    order = np.argsort(ia)
    ia_s = ia[order]
    # braking effort: nodes between the previous apex and the next apex use the next apex's beta
    nxt = np.searchsorted(ia_s, node_idx, side="left") % K
    beta = np.asarray(prm["beta"])[order][nxt]
    # throttle: nodes after an apex (up to the next apex, max 300 m) use that corner's pick-up scale
    prv = (np.searchsorted(ia_s, node_idx, side="right") - 1) % K
    c_prev = order[prv]
    dist_after = ((node_idx - ia_s[prv]) % M.n) * 5.0
    lam = np.asarray(prm["lam"])[c_prev]
    accm = np.where((dist_after < 300.0) & (lam > 0), 1.0 - 0.7 * np.exp(-dist_after / np.maximum(lam, 1e-6)), 1.0)
    # corner speed fraction inside each corner window
    gvk = np.asarray(prm["gv"])
    for k in range(K):
        a, b = M.i_in[k], M.i_out[k]
        w = (node_idx >= a) & (node_idx <= b) if a <= b else ((node_idx >= a) | (node_idx <= b))
        gv[w] = np.minimum(gv[w], gvk[k])
    vl = vl * gv
    ds = DS
    vf = np.minimum(vl, 40.0)
    for _ in range(3):
        for i in range(n + 5):
            j = i % n
            jn = (j + 1) % n
            ay = vf[j] ** 2 * kk[j]
            a, _ = a_accel(car, vf[j], ay)
            a = max(a, 0.0) * accm[j]
            vf[jn] = min(vl[jn], math.sqrt(max(vf[j] ** 2 + 2 * a * ds, 1.0)))
    vb = vf.copy()
    for _ in range(3):
        for i in range(n - 1, -6, -1):
            j = i % n
            jp = (j - 1) % n
            ay = vb[j] ** 2 * kk[j]
            dec = a_brake(car, vb[j], ay) * beta[j]
            vb[jp] = min(vl[jp], math.sqrt(vb[j] ** 2 + 2 * dec * ds))
    v = np.minimum(vf, vb)
    return v


def lap_time(path, v):
    vv = 0.5 * (v + np.roll(v, -1))
    return float(np.sum(DS / vv))


PERFECT = dict(beta=np.ones(15), gv=np.ones(15), lam=np.zeros(15), dlt=np.zeros(15))


def simulate_lap(M, prm, mu_scale=1.0):
    path = fine_path(M, prm["dlt"])
    v = qss_driver(path, M, prm, mu_scale)
    t = np.r_[0, np.cumsum(DS / (0.5 * (v[1:] + v[:-1])))]
    t_total = t[-1] + DS / (0.5 * (v[-1] + v[0]))
    return path, v, t, float(t_total)


# ======================================================================================== planted parameters
def sample_params(rng, nlaps, K, form=1.0, a=0.4, scale=1.0):
    """Per lap and corner: shortfalls, log-normal, correlated with a lap-wide 'form' factor."""
    f = rng.normal(0, 1, nlaps) * form
    out = []
    for j in range(nlaps):
        z = a * f[j] + math.sqrt(1 - a * a) * rng.normal(0, 1, K)
        z2 = a * f[j] + math.sqrt(1 - a * a) * rng.normal(0, 1, K)
        z3 = a * f[j] + math.sqrt(1 - a * a) * rng.normal(0, 1, K)
        z4 = a * f[j] + math.sqrt(1 - a * a) * rng.normal(0, 1, K)
        beta = 1.0 - np.minimum(scale * 0.040 * np.exp(0.6 * z), 0.25)
        gv = 1.0 - np.minimum(scale * 0.016 * np.exp(0.5 * z2), 0.10)
        lam = scale * 20.0 * np.exp(0.7 * z3)
        dlt = np.minimum(scale * 0.8 * np.exp(0.6 * z4), 3.0) - 0.2
        out.append(dict(beta=beta, gv=gv, lam=lam, dlt=dlt))
    return out, f


def mix(params, choice):
    """Parameters of the composite where corner k is taken from lap choice[k]."""
    K = len(choice)
    return dict(beta=np.array([params[choice[k]]["beta"][k] for k in range(K)]),
                gv=np.array([params[choice[k]]["gv"][k] for k in range(K)]),
                lam=np.array([params[choice[k]]["lam"][k] for k in range(K)]),
                dlt=np.array([params[choice[k]]["dlt"][k] for k in range(K)]))


def true_metrics(M, path, v):
    """Exact per-corner metrics from the physical profile (rules shared with the engine's definitions)."""
    n = len(v)
    a = np.gradient(0.5 * v * v, DS) / G0            # long accel [g] along the path
    node_idx = np.interp(path["s"], np.r_[path["node_s"], path["L"]], np.r_[np.arange(M.n), M.n])
    out = []
    ia_sorted = np.sort(M.i_apex)
    for k in range(M.K):
        ia = M.i_apex[k]
        # fine index of the model apex node
        f_apex = int(np.argmin(np.abs(node_idx - ia)))
        lo = f_apex - int(500 / DS)
        # window between the previous corner apex and this apex
        prev = ia_sorted[(np.searchsorted(ia_sorted, ia) - 1) % M.K]
        gap = ((ia - prev) % M.n) * 5.0
        span = int(min(600.0, gap - 10.0) / DS)
        idx = (f_apex - np.arange(span, 0, -1)) % n
        w_v = v[idx]
        # apex: minimum speed within +-60 m of the model apex
        near = (f_apex + np.arange(-int(60 / DS), int(60 / DS) + 1)) % n
        f_min = int(near[np.argmin(v[near])])
        # brake-on: last crossing of -0.30 g (going backwards from the minimum) = first sample of the braking run
        seq = (f_min - np.arange(0, span)) % n
        acc = a[seq]
        brk = np.where(acc > -0.30)[0]
        if len(brk) > 1 and brk[0] == 0 and False:
            pass
        # walk back from the apex while decel stays beyond -0.30 g at least once, then find where it first exceeds
        j = 0
        while j < len(acc) and acc[j] > -0.30:
            j += 1                                  # skip the part right at the apex where decel is already released
        found = j < len(acc)
        if found:
            while j < len(acc) and acc[j] <= -0.30:
                j += 1
            f_brk = int(seq[min(j, len(seq) - 1)])
            brake_pos = -((f_min - f_brk) % n) * DS     # metres before the apex (negative)
            peak = float(-acc[:j].min())
            v_brk = float(v[f_brk])
        else:
            brake_pos, peak, v_brk = float("nan"), float("nan"), float("nan")
        # throttle pick-up: first sample after the apex where long accel exceeds +0.10 g
        fw = (f_min + np.arange(0, int(400 / DS))) % n
        up = np.where(a[fw] > 0.10)[0]
        pick = float(up[0] * DS) if len(up) else float("nan")
        f100 = (f_min + int(100 / DS)) % n
        out.append(dict(v_min=float(v[f_min] * 3.6), apex_off=float(((f_min - f_apex + n // 2) % n - n // 2) * DS),
                        brake_pos=float(brake_pos), brake_peak=peak, v_brake=float(v_brk * 3.6) if v_brk == v_brk else float("nan"),
                        pickup=pick, v_exit100=float(v[f100] * 3.6)))
    return out


# ======================================================================================== session synthesis
def ar1(rng, n, sig, rho):
    out = np.zeros(n)
    e = rng.normal(0, sig * math.sqrt(1 - rho ** 2), n)
    out[0] = rng.normal(0, sig)
    for i in range(1, n):
        out[i] = rho * out[i - 1] + e[i]
    return out


def rot(yaw_d, pitch_d, roll_d):
    cy, sy_ = math.cos(math.radians(yaw_d)), math.sin(math.radians(yaw_d))
    cp, sp = math.cos(math.radians(pitch_d)), math.sin(math.radians(pitch_d))
    cr, sr = math.cos(math.radians(roll_d)), math.sin(math.radians(roll_d))
    Rz = np.array([[cy, -sy_, 0], [sy_, cy, 0], [0, 0, 1]])
    Ry = np.array([[cp, 0, sp], [0, 1, 0], [-sp, 0, cp]])
    Rx = np.array([[1, 0, 0], [0, cr, -sr], [0, sr, cr]])
    return Rz @ Ry @ Rx


def run(a):
    rng = default_rng(a.seed)
    M = Model()
    K = M.K
    nl = a.laps
    params, form = sample_params(rng, nl, K, form=a.form, a=a.corr, scale=a.scale)
    sims = []
    for j in range(nl):
        path, v, t, T = simulate_lap(M, params[j])
        sims.append((path, v, t, T))
        print("  lap %d: %d:%06.3f  (L %.1f m, form %+.2f)" % (j + 1, int(T // 60), T % 60, path["L"], form[j]), flush=True)
    perf_path, perf_v, perf_t, perf_T = simulate_lap(M, PERFECT)
    print("  perfect line: %d:%06.3f" % (int(perf_T // 60), perf_T % 60))
    # all-best-component lap
    best = dict(beta=np.max([p["beta"] for p in params], axis=0), gv=np.max([p["gv"] for p in params], axis=0),
                lam=np.min([p["lam"] for p in params], axis=0), dlt=np.min([p["dlt"] for p in params], axis=0))
    ab_path, ab_v, ab_t, ab_T = simulate_lap(M, best)
    print("  all-best-components lap: %d:%06.3f" % (int(ab_T // 60), ab_T % 60))
    # out-lap and in-lap use the perfect path slowed down
    laps = [("out", perf_path, perf_v * a.out_factor)] + [("fly", sims[j][0], sims[j][1]) for j in range(nl)] + \
           [("in", perf_path, perf_v * a.in_factor)]
    # ---- build the session path as a concatenation, blending the speed profile across lap boundaries
    pieces_s, pieces_x, pieces_y, pieces_k, pieces_th, pieces_v, lap_start = [], [], [], [], [], [], []
    ell = 0.0
    for name, p, v in laps:
        lap_start.append(ell)
        pieces_s.append(p["s"] + ell)
        pieces_x.append(p["x"]); pieces_y.append(p["y"]); pieces_k.append(p["kap"]); pieces_th.append(p["th"])
        pieces_v.append(v)
        ell += p["L"]
    lap_start.append(ell)
    frac_end = 0.45
    s_all = np.concatenate(pieces_s)
    x_all = np.concatenate(pieces_x); y_all = np.concatenate(pieces_y)
    k_all = np.concatenate(pieces_k); th_all = np.concatenate(pieces_th)
    v_all = np.concatenate(pieces_v)
    # blend v across boundaries (Gaussian, sigma 120 m) -- keeps the profile continuous where two laps join
    sig = int(35 / DS)
    kern = np.exp(-0.5 * (np.arange(-4 * sig, 4 * sig + 1) / sig) ** 2); kern /= kern.sum()
    pad = 4 * sig
    vp = np.pad(v_all, pad, mode="edge")
    vs = np.convolve(vp, kern, mode="valid")
    w = np.zeros(len(v_all))
    for b in lap_start[1:-1]:
        i = int(b / DS)
        w[max(0, i - 2 * sig):i + 2 * sig] = 1.0
    wk = np.exp(-0.5 * (np.arange(-4 * sig, 4 * sig + 1) / sig) ** 2); wk /= wk.max()
    wsm = np.clip(np.convolve(np.pad(w, pad), wk / wk.sum(), mode="valid"), 0, 1)
    v_all = v_all * (1 - wsm) + vs * wsm
    # truncate: the in-lap stops after frac_end of a lap
    total = lap_start[-2] + frac_end * (lap_start[-1] - lap_start[-2])
    keep = s_all <= total
    s_all, x_all, y_all, k_all, th_all, v_all = s_all[keep], x_all[keep], y_all[keep], k_all[keep], th_all[keep], v_all[keep]
    v_all = np.minimum(v_all, np.sqrt(2 * 4.0 * np.maximum(s_all, 0.05)))              # gentle launch
    v_all = np.minimum(v_all, np.sqrt(2 * 3.0 * np.maximum(total - s_all, 0.05)))      # stop at the end of the in-lap
    v_all = np.maximum(v_all, 0.4)
    tmid = np.concatenate([[0], np.cumsum(DS / (0.5 * (v_all[1:] + v_all[:-1])))])
    t_end = tmid[-1]
    T0, T1 = 12.0, 10.0
    T = T0 + t_end + T1
    tu = np.arange(0, T, 0.01)
    sk = np.interp(tu - T0, tmid, s_all, left=0.0, right=s_all[-1])
    vk = np.interp(sk, s_all, v_all)
    moving = (tu > T0) & (tu < T0 + t_end)
    vk = np.where(moving, vk, 0.0)
    dvds = np.gradient(v_all, DS)
    ak = np.where(moving, vk * np.interp(sk, s_all, dvds), 0.0)
    kk = np.interp(sk, s_all, k_all)
    xk = np.interp(sk, s_all, x_all); yk = np.interp(sk, s_all, y_all)
    thk = np.interp(sk, s_all, np.unwrap(th_all))
    ay = vk ** 2 * kk
    yaw = vk * kk
    # exact lap boundary times (node 0 of every lap path is the start/finish line)
    sf_times = [float(np.interp(b, s_all, tmid) + T0) for b in lap_start[:-1]]
    sf_times.append(float(np.interp(min(lap_start[-1], s_all[-1]), s_all, tmid) + T0))
    # exact time of every lap passing every racing-line node (flying laps only)
    node_times = np.zeros((nl, M.n))
    for j in range(nl):
        p = laps[j + 1][1]
        base = lap_start[j + 1]
        node_times[j] = np.interp(base + p["node_s"], s_all, tmid) + T0 - sf_times[j + 1]
    lap_times_true = np.array([sf_times[j + 2] - sf_times[j + 1] for j in range(nl)])
    # ---- body motion, specific force in the car frame, phone mounting
    phi = np.radians(0.45) * (-ak / G0)
    rho = np.radians(1.10) * (ay / G0)
    fx = ak - G0 * np.sin(phi)
    fy = ay + G0 * np.sin(rho)
    fz = G0 * np.cos(phi) * np.cos(rho) + 0.12 * np.sin(2 * np.pi * 1.8 * tu)
    om_roll = np.gradient(rho, 0.01); om_pitch = np.gradient(phi, 0.01)
    R = rot(37.0, -28.0, 11.0)
    bias_a = np.array([0.05, -0.03, 0.08]); bias_g = np.array([0.004, -0.002, 0.003])

    def accel_at(t):
        f = np.column_stack([np.interp(t, tu, fx), np.interp(t, tu, fy), np.interp(t, tu, fz)])
        f[:, 0] += 0.12 * np.sin(2 * np.pi * 1.8 * t + 1.0)
        return f @ R.T + bias_a + rng.normal(0, a.anoise, (len(t), 3))

    def gyro_at(t):
        wv = np.column_stack([np.interp(t, tu, om_roll), np.interp(t, tu, om_pitch), np.interp(t, tu, yaw)])
        return wv @ R.T + bias_g + rng.normal(0, 0.004, (len(t), 3))

    ta = np.arange(0.02, T - 0.05, 0.01) + rng.normal(0, 0.0008, len(np.arange(0.02, T - 0.05, 0.01)))
    tgy = np.arange(0.03, T - 0.05, 0.01) + rng.normal(0, 0.0008, len(np.arange(0.03, T - 0.05, 0.01)))
    ta.sort(); tgy.sort()
    A = accel_at(ta); Gy = gyro_at(tgy)
    # ---- GPS
    tf = np.arange(3.37, T - 1, 1.0)
    # one dropout in a flying lap
    t_drop = T0 + 0.3 * t_end + rng.uniform(0, 0.4 * t_end)
    tf = tf[~((tf > t_drop) & (tf < t_drop + a.dropout))]
    tt = tf - a.gps_lag
    px = np.interp(tt, tu, xk); py = np.interp(tt, tu, yk); pv = np.interp(tt, tu, vk); pth = np.interp(tt, tu, thk)
    ex = ar1(rng, len(tf), a.gps_sigma, a.gps_rho); ey = ar1(rng, len(tf), a.gps_sigma, a.gps_rho)
    out_m = rng.random(len(tf)) < a.outlier
    ex = ex + out_m * rng.normal(0, 9.0, len(tf)); ey = ey + out_m * rng.normal(0, 9.0, len(tf))
    px = px + ex; py = py + ey
    pvn = np.abs(pv + rng.normal(0, a.speed_sigma, len(tf)))
    bearing = (90.0 - np.degrees(pth) + rng.normal(0, 1.5, len(tf))) % 360.0
    lat, lon = M.to_latlon(px, py)
    rows = []
    for t, aa in zip(ta, A):
        rows.append((t, "A,%d,%.6g,%.6g,%.6g" % (round(t * 1e6), aa[0], aa[1], aa[2])))
    for t, ww in zip(tgy, Gy):
        rows.append((t, "G,%d,%.6g,%.6g,%.6g" % (round(t * 1e6), ww[0], ww[1], ww[2])))
    utc0 = 1760320000000
    for i in range(len(tf)):
        hb = 0 if pvn[i] < 0.5 else bearing[i]
        rows.append((tf[i] + 0.12, "L,%d,%.8f,%.8f,%.1f,%.3f,%.1f,%.1f,%.2f,%.1f,%d,%d" % (
            round(tf[i] * 1e6), lat[i], lon[i], 21.0 + rng.normal(0, 1), pvn[i], hb,
            max(1.5, a.gps_sigma * 1.6 + rng.uniform(-0.5, 0.5)), 0.25, 2.0, utc0 + round(tf[i] * 1000), 120)))
    for k in range(1, int(T)):
        rows.append((k + 0.5, "S,%d,%d,%d" % (round((k + 0.5) * 1e6), 28, 17 + (k % 3))))
    for k in range(0, int(T), 30):
        rows.append((k + 0.01, "K,%d,A,%d" % (round((k + 0.01) * 1e6), 3)))
        rows.append((k + 0.02, "K,%d,G,%d" % (round((k + 0.02) * 1e6), 3)))
    wa = (ta >= 1) & (ta < 5)
    mm = A[wa].mean(axis=0); noise_a = math.sqrt(((A[wa] - mm) ** 2).mean(axis=0).sum())
    wg = (tgy >= 1) & (tgy < 5); mg = Gy[wg].mean(axis=0)
    rows.append((0.0, "E,0,START"))
    rows.append((5.0, "E,%d,CAL,%.6g,%.6g,%.6g,%.4f,%.6g,%.6g,%.6g,1" % (5.0e6, mm[0], mm[1], mm[2], noise_a, mg[0], mg[1], mg[2])))
    rows.append((T, "E,%d,STOP" % round(T * 1e6)))
    rows.sort(key=lambda r: r[0])
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    with open(a.out, "w") as f:
        f.write("# TrackLogger 1.2 (synthetic Sepang)\n# device,Synthetic Phone\n# android,14,sdk,34\n")
        for _, line in rows:
            f.write(line + "\n")
    # ---- truth
    metrics = [true_metrics(M, sims[j][0], sims[j][1]) for j in range(nl)]
    tr = a.out.replace(".csv", "_truth.npz")
    np.savez(tr, seed=a.seed, nl=nl, lap_times=lap_times_true, sf_times=np.array(sf_times), node_times=node_times,
             form=form, T0=T0, tend=t_end,
             beta=np.array([p["beta"] for p in params]), gv=np.array([p["gv"] for p in params]),
             lam=np.array([p["lam"] for p in params]), dlt=np.array([p["dlt"] for p in params]),
             perfect_T=perf_T, allbest_T=ab_T,
             tu=tu[::10], xk=xk[::10], yk=yk[::10], vk=vk[::10], sk=sk[::10],
             metrics=json.dumps(metrics))
    print("wrote %s: %.0f s, %d accel rows, %d gps fixes, dropout %.1f s at t=%.0f" % (
        a.out, T, len(ta), len(tf), a.dropout, t_drop))
    print("true flying lap times:", " ".join("%d:%06.3f" % (int(x // 60), x % 60) for x in lap_times_true))
    return M, params, sims


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--laps", type=int, default=7)
    ap.add_argument("--out", default=os.path.join(OUT, "s1.csv"))
    ap.add_argument("--form", type=float, default=1.0)
    ap.add_argument("--corr", type=float, default=0.4)
    ap.add_argument("--scale", type=float, default=1.0)
    ap.add_argument("--gps-sigma", dest="gps_sigma", type=float, default=2.0)
    ap.add_argument("--gps-rho", dest="gps_rho", type=float, default=0.9)
    ap.add_argument("--gps-lag", dest="gps_lag", type=float, default=0.25)
    ap.add_argument("--speed-sigma", dest="speed_sigma", type=float, default=0.10)
    ap.add_argument("--outlier", type=float, default=0.01)
    ap.add_argument("--dropout", type=float, default=4.5)
    ap.add_argument("--anoise", type=float, default=0.35)
    ap.add_argument("--out-factor", dest="out_factor", type=float, default=0.72)
    ap.add_argument("--in-factor", dest="in_factor", type=float, default=0.70)
    run(ap.parse_args())


if __name__ == "__main__":
    main()

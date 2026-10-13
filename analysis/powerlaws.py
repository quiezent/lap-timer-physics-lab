#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
powerlaws.py - read one session through three power laws and find where the lap time hides.

    python analysis/powerlaws.py SESSION.csv [--out DIR] [--no-plots]

It runs the ultimate-lap engine (ultimate.py) on the session, then asks four questions of the aligned laps:

  1. BRAKING.  Stopping distance goes with speed squared, so speed squared falls in a straight line with distance
     while the brakes are on.  How straight is it on your laps, how hard do you brake on average, and what does a
     metre of braking-point error do to the speed you arrive with?
  2. CORNERING.  Grip-limited corner speed goes with the square root of the radius (v^2 = a * r).  Which exponent
     does your own driving follow, how much of the available grip does each corner use, and does a wider arc buy speed?
  3. STRAIGHTS.  A speed advantage at a corner exit melts away on the straight because drag grows with speed squared
     and power-limited acceleration falls as 1/v.  What is one km/h at each exit worth in seconds, by physics and by
     your own laps?
  4. WHERE THE TIME GOES.  Per corner window and lap, how much time was lost against the median lap, and how
     concentrated is the loss?

Output (in --out): numbers.json (every figure quoted in the report), tables/*.csv and figures/*.png.
Needs numpy; matplotlib for the figures.  Everything it prints about a session is a measurement from a phone:
read the caveats in reports/sepang-2026-10-13/REPORT.md before acting on any single number.
"""
import argparse
import csv
import json
import math
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import ultimate as U                                           # noqa: E402

G0 = 9.80665
KMH = 3.6
BOOT = 2000                                                    # bootstrap resamples (fixed seed, so the output is repeatable)
RNG_SEED = 1


# ====================================================================================================
# data access
# ====================================================================================================
class Session:
    """The aligned laps of one engine run, as plain arrays on the engine's 1 m distance grid."""

    def __init__(self, R):
        self.R = R
        laps = R["prep"]["laps"]
        self.laps = laps
        self.nb = len(laps)
        self.n = len(laps[0]["al"]["p"]) - 1                    # grid cells in one loop (loop = middle of straight to middle of straight)
        n = self.n
        self.V = np.array([l["al"]["v"][:n + 1] for l in laps])         # m/s
        self.T = np.array([l["al"]["t"][:n + 1] for l in laps])         # s since the loop's start
        self.LG = np.array([l["al"]["lg"][:n + 1] for l in laps])       # g, forward positive
        self.LAT = np.array([l["al"]["lat"][:n + 1] for l in laps])     # g, left positive
        self.KAP = np.array([l["al"]["kap"][:n + 1] for l in laps])     # 1/m, from yaw rate / speed
        self.fast = np.array([bool(l["fast"]) for l in laps])
        self.clean = np.array([bool(l["clean"]) for l in laps])
        self.time = np.array([float(l["time"]) for l in laps])
        self.E = R["E"]
        self.corners = R["corners"]
        self.K = len(self.corners)
        self.labels = [c["label"] for c in self.corners]
        self.ia = [int(x) for x in self.E["ia"]]
        self.linked = [bool(x) for x in self.E["linked"]]
        self.brake_ok = [bool(x) for x in self.E["brake_ok"]]
        self.ult = R["ult"]
        self.ok = np.array(self.ult.ok, bool)                           # laps x sections: good GPS at both ends, normal speed
        self.cuts = [int(round(c)) for c in R["prep"]["cuts_rel"]]
        self.L = float(R["prep"]["ref"].L)
        self.names = list(R["names"])
        keys = ["v_min", "apex_off", "brake_pos", "brake_peak", "v_brake", "pickup", "v_exit100", "turn_in", "lat_peak"]
        self.keys = keys
        self.EV = np.array([[[self.E["ev"][b][k][key] for key in keys] for k in range(self.K)] for b in range(self.nb)], float)
        self.nums = [self._lap_no(b) for b in range(self.nb)]
        v_fast = self.V[self.fast]
        self.vmed = np.median(v_fast, axis=0)
        self.gmed = np.median(self.LG[self.fast], axis=0)

    def _lap_no(self, b):
        return b + 1

    def ev(self, b, k, key):
        return self.EV[b, k, self.keys.index(key)]

    # ---- sections a stretch of the lap overlaps
    def sections_of(self, s0, s1):
        cb = self.cuts + [int(math.ceil(self.L))]
        return [i for i in range(len(self.cuts)) if not (s1 <= cb[i] or s0 >= cb[i + 1])]

    def trusted(self, b, s0, s1):
        """True if lap b is a fast lap whose every section overlapping [s0, s1] has good GPS and a normal speed at both ends."""
        return bool(self.fast[b] and all(self.ok[b, i] for i in self.sections_of(s0, min(s1, int(self.L)))))

    def trusted_run(self, b, i_a, i_b):
        """trusted() for a stretch that may run past the end of the loop into the very next lap."""
        n = self.n
        if i_b <= n:
            return self.trusted(b, i_a, i_b)
        nxt = b + 1
        if nxt >= self.nb or self.laps[nxt]["session"] != self.laps[b]["session"] or self.laps[nxt]["k"] != self.laps[b]["k"] + 1:
            return False
        return self.trusted(b, i_a, n) and self.trusted(nxt, 0, i_b - n)

    def elapsed(self, b, i_a, i_b):
        """Time from grid cell i_a to i_b of loop b; a window that runs past the end of the loop continues into the next loop.
        None if that next loop does not exist or is not the very next lap."""
        n = self.n
        if i_b <= n:
            return float(self.T[b, i_b] - self.T[b, i_a])
        nxt = b + 1
        if nxt >= self.nb or self.laps[nxt]["session"] != self.laps[b]["session"] or self.laps[nxt]["k"] != self.laps[b]["k"] + 1:
            return None
        return float(self.T[b, n] - self.T[b, i_a] + self.T[nxt, i_b - n])


def boot_slope(x, y, rng, reps=BOOT):
    """Least-squares slope with a 90 % bootstrap interval over the laps."""
    x, y = np.asarray(x, float), np.asarray(y, float)
    a = np.polyfit(x, y, 1)[0]
    bs = []
    for _ in range(reps):
        i = rng.integers(0, len(x), len(x))
        if np.ptp(x[i]) > 0:
            bs.append(np.polyfit(x[i], y[i], 1)[0])
    lo, hi = np.percentile(bs, [5, 95])
    return float(a), float(lo), float(hi)


def f(x, nd=3):
    return None if x is None or not np.isfinite(x) else round(float(x), nd)


# ====================================================================================================
# 1. braking: speed squared against distance
# ====================================================================================================
def braking(S):
    n = S.n
    rows = []
    for k in range(S.K):
        if not S.brake_ok[k]:
            continue
        for b in np.where(S.fast)[0]:
            bp = S.ev(b, k, "brake_pos")
            off = S.ev(b, k, "apex_off")
            if not (np.isfinite(bp) and np.isfinite(off)):
                continue
            i_on = int(round(S.ia[k] + bp))
            i_min = int(round(S.ia[k] + off))
            span = i_min - i_on
            if span < 20 or span > 600:
                continue
            idx = (i_on + np.arange(span + 1)) % n
            v = S.V[b, idx]
            x = np.arange(span + 1, dtype=float)
            v2 = v ** 2
            A = np.polyfit(x, v2, 1)
            fit = np.polyval(A, x)
            r2 = 1.0 - float(np.sum((v2 - fit) ** 2) / np.sum((v2 - v2.mean()) ** 2))
            rows.append(dict(corner=S.labels[k], lap=S.nums[b], onset_m=-bp, span_m=span, v_on_kmh=v[0] * KMH, v_min_kmh=v[-1] * KMH,
                             avg_decel_g=(v[0] ** 2 - v[-1] ** 2) / (2 * span) / G0, slope_decel_g=-A[0] / 2 / G0, r2_v2_line=r2,
                             peak_g=S.ev(b, k, "brake_peak")))
    summ = []
    for k in range(S.K):
        sel = [r for r in rows if r["corner"] == S.labels[k]]
        if len(sel) < 6:
            continue
        a = np.array([r["slope_decel_g"] for r in sel])                 # steady deceleration: slope of the straight line in speed^2
        a_avg = np.array([r["avg_decel_g"] for r in sel])               # (speed at onset^2 - slowest speed^2) / (2 x distance)
        on = np.array([r["onset_m"] for r in sel])
        vm = np.array([r["v_min_kmh"] for r in sel]) / KMH
        sens = float(np.median(a) * G0 / np.median(vm) * KMH)           # km/h at the slowest point per metre of braking point: a / v
        summ.append(dict(corner=S.labels[k], laps=len(sel), median_r2=float(np.median([r["r2_v2_line"] for r in sel])),
                         median_decel_g=float(np.median(a)), decel_p25=float(np.percentile(a, 25)), decel_p75=float(np.percentile(a, 75)),
                         median_avg_decel_g=float(np.median(a_avg)),
                         onset_median_m=float(np.median(on)), onset_min_m=float(on.min()), onset_max_m=float(on.max()), onset_sd_m=float(on.std(ddof=1)),
                         v_on_median_kmh=float(np.median([r["v_on_kmh"] for r in sel])),
                         v_min_median_kmh=float(np.median(vm) * KMH), v_min_sd_kmh=float(np.std(vm, ddof=1) * KMH),
                         corr_onset_vs_decel=float(np.corrcoef(on, a)[0, 1]), sens_kmh_per_m=sens))
    r2 = np.array([r["r2_v2_line"] for r in rows])
    return rows, summ, dict(n_lap_zones=len(rows), median_r2=float(np.median(r2)), share_r2_above_09=float(np.mean(r2 > 0.9)),
                            p10_r2=float(np.percentile(r2, 10)))


def first_corner_cost(S, rng, k=0, lead=300):
    """How long the `lead` metres before the apex take against the braking point (corner k, normally T1), and how that
    carries into the section time.  Only laps whose sections there are trusted."""
    i_ap = S.ia[k]
    s0 = i_ap - lead
    rows = []
    for b in range(S.nb):
        if not S.trusted(b, max(s0, 0), i_ap):
            continue
        bp = S.ev(b, k, "brake_pos")
        if not np.isfinite(bp):
            continue
        sec = S.ult.E[b, S.E["sec"][k]]
        i_min = int(round(i_ap + S.ev(b, k, "apex_off")))
        i_on = int(round(i_ap + bp))
        a_avg = (S.V[b, i_on] ** 2 - S.V[b, i_min] ** 2) / (2 * (i_min - i_on)) / G0
        rows.append(dict(lap=S.nums[b], onset_m=-bp, window_s=float(S.T[b, i_ap] - S.T[b, s0]), avg_decel_g=a_avg,
                         v_min_kmh=S.ev(b, k, "v_min") * KMH, section_value_s=float(sec) if np.isfinite(sec) else None))
    on = np.array([r["onset_m"] for r in rows])
    w = np.array([r["window_s"] for r in rows])
    sl, lo, hi = boot_slope(on, w, rng)
    out = dict(corner=S.labels[k], lead_m=lead, laps=len(rows), slope_s_per_m=sl, slope_lo=lo, slope_hi=hi, corr=float(np.corrcoef(on, w)[0, 1]),
               onset_min_m=float(on.min()), onset_max_m=float(on.max()), window_min_s=float(w.min()), window_max_s=float(w.max()),
               corr_onset_vs_decel=float(np.corrcoef(on, [r["avg_decel_g"] for r in rows])[0, 1]))
    sv = [(r["onset_m"], r["section_value_s"]) for r in rows if r["section_value_s"] is not None]
    if len(sv) > 5:
        out["corr_onset_vs_section"] = float(np.corrcoef([a for a, _ in sv], [b for _, b in sv])[0, 1])
        out["section_laps"] = len(sv)
    return rows, out


# ====================================================================================================
# 2. cornering: speed against radius, grip used, does a wider arc buy speed?
# ====================================================================================================
def cornering(S, rng):
    n = S.n
    per = []
    lap_rows = []
    for k in range(S.K):
        if S.linked[k]:
            continue                                            # no speed minimum of its own
        vm, rr, aa, lp, ratio = [], [], [], [], []
        for b in np.where(S.fast)[0]:
            v = S.ev(b, k, "v_min")
            off = S.ev(b, k, "apex_off")
            if not (np.isfinite(v) and np.isfinite(off)):
                continue
            i = int(round(S.ia[k] + off)) % n
            w = (i + np.arange(-6, 7)) % n
            kap = abs(float(np.median(S.KAP[b, w])))
            if kap < 1e-4:
                continue
            vm.append(v)
            rr.append(1.0 / kap)
            aa.append(v * v * kap / G0)
            lp.append(S.ev(b, k, "lat_peak"))
            ratio.append(abs(float(np.median(S.LAT[b, w]))) / (v * v * kap / G0))     # accelerometer's sideways g over the gyro's, same place
            lap_rows.append(dict(corner=S.labels[k], lap=S.nums[b], v_min_kmh=v * KMH, radius_m=1.0 / kap, apex_lat_g=v * v * kap / G0,
                                 peak_lat_g=S.ev(b, k, "lat_peak")))
        if len(vm) < 8:
            continue
        vm, rr, aa, lp, ratio = map(np.array, (vm, rr, aa, lp, ratio))
        A = np.polyfit(np.log(rr), np.log(vm), 1)
        per.append(dict(corner=S.labels[k], laps=len(vm), v_min_kmh=float(np.median(vm) * KMH), v_min_sd_kmh=float(np.std(vm, ddof=1) * KMH),
                        v_min_lo_kmh=float(vm.min() * KMH), v_min_hi_kmh=float(vm.max() * KMH),
                        radius_m=float(np.median(rr)), radius_lo_m=float(rr.min()), radius_hi_m=float(rr.max()),
                        apex_lat_g=float(np.median(aa)), peak_lat_g=float(np.median(lp)), accel_over_gyro=float(np.median(ratio)),
                        within_exponent=float(A[0]), within_corr=float(np.corrcoef(np.log(rr), np.log(vm))[0, 1])))
    v = np.array([p["v_min_kmh"] for p in per]) / KMH
    r = np.array([p["radius_m"] for p in per])
    lat = np.array([p["peak_lat_g"] for p in per])
    ap = np.array([p["apex_lat_g"] for p in per])
    A = np.polyfit(np.log(r), np.log(v), 1)
    resid = np.log(v) - np.polyval(A, np.log(r))
    loo = []
    for j in range(len(per)):
        m = np.arange(len(per)) != j
        loo.append(float(np.polyfit(np.log(r[m]), np.log(v[m]), 1)[0]))
    bs = []
    for _ in range(BOOT):
        i = rng.integers(0, len(per), len(per))
        if np.ptp(np.log(r[i])) > 0:
            bs.append(np.polyfit(np.log(r[i]), np.log(v[i]), 1)[0])
    # peak lateral g against speed squared (a downforce signature would show up here)
    B = np.polyfit(v ** 2, lat, 1)
    lo_v = float(np.median(v[v <= np.median(v)])); hi_v = float(np.median(v[v > np.median(v)]))
    out = dict(corners_used=[p["corner"] for p in per], exponent=float(A[0]), exponent_ci90=[float(x) for x in np.percentile(bs, [5, 95])],
               exponent_loo_min=min(loo), exponent_loo_max=max(loo), resid_sd_log=float(resid.std(ddof=2)),
               apex_lat_g_min=float(ap.min()), apex_lat_g_max=float(ap.max()), apex_lat_g_median=float(np.median(ap)),
               peak_lat_vs_v2_slope_g_per_ms2=float(B[0]), peak_lat_vs_v2_intercept_g=float(B[1]),
               peak_lat_vs_v2_corr=float(np.corrcoef(v ** 2, lat)[0, 1]), peak_lat_slow_half_g=float(np.median(lat[v <= np.median(v)])),
               peak_lat_fast_half_g=float(np.median(lat[v > np.median(v)])), slow_half_speed_kmh=lo_v * KMH, fast_half_speed_kmh=hi_v * KMH)
    rat = np.array([p["accel_over_gyro"] for p in per])
    out.update(accel_over_gyro_median=float(np.median(rat)), accel_over_gyro_min=float(rat.min()), accel_over_gyro_max=float(rat.max()),
               accel_over_gyro_worst_deviation=float(np.max(np.abs(rat - 1.0))))
    return per, lap_rows, out


def aero_expectation(v_lo_kmh, v_hi_kmh):
    """Extra lateral g the published downforce figures (point-mass model in physics/gt3_model.py) give between two speeds."""
    sys.path.insert(0, os.path.join(os.path.dirname(HERE), "physics"))
    from gt3_model import Car
    car = Car()
    f = lambda v: car.mu_y * car.dforce(v / KMH) / (car.m * 9.81)
    return float(f(v_hi_kmh) - f(v_lo_kmh)), dict(ClA=car.ClA, mass=car.m, mu_y=car.mu_y)


# ====================================================================================================
# 3. straights: what a km/h at the exit is worth
# ====================================================================================================
def exit_value(S, rng):
    """What is one km/h at the exit of each corner worth?  Two answers, side by side:
      model - the engine's own speed envelope (power-limited acceleration A(v) = pw/v - k v^2 - c), integrated down the straight
              that follows the corner: sigma = integral of v0/v^3 * exp(integral of A'(v)/v ds) ds;
      data  - the slope of 'time over that straight' against 'speed 100 m after the apex' across the laps (bootstrap 90 % interval)."""
    n = S.n
    env = S.ult.env
    out = []
    for k in range(S.K):
        ve = np.array([S.ev(b, k, "v_exit100") for b in range(S.nb)])
        sel = [b for b in range(S.nb) if S.fast[b] and np.isfinite(ve[b])]
        if len(sel) < 8:
            continue
        i_a = S.ia[k] + 100
        if i_a >= n:
            continue                                            # exit lies in the next loop: leave it
        j = 1
        while j < U.PRICE_MAX_D and S.gmed[(i_a + j) % n] > U.BRAKE_STOP_G:
            j += 1
        i_b = i_a + int(j)                                      # first metre where the median lap is braking (or 600 m)
        v0 = float(S.vmed[i_a]); expo = 0.0; sig = 0.0
        for q in range(int(j)):
            vj = max(float(S.vmed[(i_a + q) % n]), 5.0)
            sig += v0 / vj ** 3 * math.exp(expo) * U.GRID
            expo += float(U.env_dAdv(env, vj)) / vj * U.GRID
        x, y = [], []
        for b in sel:
            if not S.trusted_run(b, i_a, i_b):
                continue
            tt = S.elapsed(b, i_a, i_b)
            if tt is None:
                continue
            x.append(S.V[b, i_a] * KMH)
            y.append(tt)
        other_apex = [S.ia[c] for c in range(S.K) if c != k and i_a < S.ia[c] < i_b]
        row = dict(corner=S.labels[k], window_start_m=i_a, window_m=int(j), straight_is_clear=(len(other_apex) == 0), model_s_per_kmh=sig / KMH,
                   exit_kmh_median=float(np.median([ve[b] for b in sel]) * KMH), exit_kmh_best=float(max(ve[b] for b in sel) * KMH),
                   exit_kmh_worst=float(min(ve[b] for b in sel) * KMH), laps_for_range=len(sel), laps_for_slope=len(x))
        row["model_gain_best_vs_median_s"] = row["model_s_per_kmh"] * (row["exit_kmh_best"] - row["exit_kmh_median"])
        if len(x) >= 8 and np.ptp(x) > 0:
            sl, lo, hi = boot_slope(x, y, rng)
            row.update(emp_s_per_kmh=-sl, emp_lo=-hi, emp_hi=-lo, emp_corr=float(np.corrcoef(x, y)[0, 1]), emp_speed_spread_kmh=float(np.ptp(x)))
        out.append(row)
    return out


# ====================================================================================================
# 4. where the time goes: corner windows, excess time, concentration
# ====================================================================================================
def windows(S):
    mids = [(S.ia[k] + S.ia[k + 1]) // 2 for k in range(S.K - 1)]
    starts = [0] + mids
    ends = mids + [S.n]
    return starts, ends


def excess(S, min_other=6):
    starts, ends = windows(S)
    dt = np.full((S.nb, S.K), np.nan)
    valid = np.zeros((S.nb, S.K), bool)
    for b in range(S.nb):
        for k in range(S.K):
            if S.trusted(b, starts[k], ends[k]):
                valid[b, k] = True
                dt[b, k] = S.T[b, ends[k]] - S.T[b, starts[k]]
    exc = np.full((S.nb, S.K), np.nan)
    for b in range(S.nb):
        for k in range(S.K):
            if not valid[b, k]:
                continue
            oth = [c for c in range(S.nb) if c != b and valid[c, k]]
            if len(oth) >= min_other:
                exc[b, k] = dt[b, k] - float(np.median(dt[oth, k]))
    return exc, valid, starts, ends


def concentration(S, exc, rng):
    """How lumpy is the time lost?  Compared with what pure noise of the same everyday size would give."""
    ok = np.isfinite(exc)
    vals = exc[ok]
    pos = np.sort(exc[ok & (exc > 0)])[::-1]
    cells = int(ok.sum())
    n5 = max(1, int(round(0.05 * cells)))
    cum = np.cumsum(pos) / pos.sum()
    # everyday scatter of a window against the median lap: robust sd from the median absolute deviation
    robust_sd = float(1.4826 * np.median(np.abs(vals - np.median(vals))))
    shares = []
    for _ in range(2000):
        z = np.sort(np.maximum(rng.normal(0.0, robust_sd, cells), 0.0))[::-1]
        shares.append(z[:n5].sum() / z.sum())
    clear = pos[pos >= 3 * robust_sd]
    return dict(rated_lap_windows=cells, windows_with_loss=int(len(pos)), total_loss_s=float(pos.sum()), robust_sd_s=robust_sd,
                top5pct_windows=n5, top5pct_share=float(cum[n5 - 1]), top5pct_share_if_only_noise=float(np.mean(shares)),
                top10_share=float(cum[9]), top20_share=float(cum[19]),
                events_ge_half_s=int((pos >= 0.5).sum()), events_ge_half_share=float(pos[pos >= 0.5].sum() / pos.sum()),
                events_ge_half_s_per_lap=float(pos[pos >= 0.5].sum() / S.fast.sum()),
                clear_events_3sd=int(len(clear)), clear_events_3sd_share=float(clear.sum() / pos.sum()), expected_clear_events_if_only_noise=float(cells * 0.5 * math.erfc(3 / math.sqrt(2))),
                median_loss_s=float(np.median(pos)))


def events_table(S, exc, valid, starts, ends, top=20):
    rows = []
    for b in range(S.nb):
        for k in range(S.K):
            if np.isfinite(exc[b, k]):
                rows.append((exc[b, k], b, k))
    rows.sort(reverse=True)
    out = []
    for e, b, k in rows[:top]:
        seg = slice(starts[k], ends[k] + 1)
        oth = [c for c in range(S.nb) if c != b and valid[c, k]]
        out.append(dict(lap=S.nums[b], corner=S.labels[k], excess_s=float(e), window_start_m=starts[k], window_end_m=ends[k],
                        min_speed_kmh=float(S.V[b, seg].min() * KMH), min_speed_median_of_others_kmh=float(np.median([S.V[c, seg].min() for c in oth]) * KMH),
                        hardest_decel_g=float(-S.LG[b, seg].min()), clean_lap=bool(S.clean[b])))
    return out


def per_corner_loss(S, exc):
    out = []
    for k in range(S.K):
        col = exc[:, k]
        m = np.isfinite(col)
        if m.sum() < 6:
            continue
        pos = np.where(col[m] > 0, col[m], 0.0)
        out.append(dict(corner=S.labels[k], rated_laps=int(m.sum()), mean_loss_s=float(pos.mean()), median_excess_s=float(np.median(col[m])),
                        sd_s=float(col[m].std(ddof=1)), worst_s=float(col[m].max())))
    out.sort(key=lambda r: -r["mean_loss_s"])
    return out


def apex_price(S, exc_valid, starts, ends, rng):
    """What is one km/h more at the slowest point of a corner worth, on these laps?  Regression of the corner window's time
    (middle of the gap before the corner to the middle of the gap after it) on the lap's slowest speed there."""
    out = []
    for k in range(S.K):
        if S.linked[k]:
            continue
        x, y = [], []
        for b in range(S.nb):
            if not exc_valid[b, k]:
                continue
            v = S.ev(b, k, "v_min")
            if not np.isfinite(v):
                continue
            x.append(v * KMH)
            y.append(float(S.T[b, ends[k]] - S.T[b, starts[k]]))
        if len(x) < 8 or np.ptp(x) < 3:
            continue
        sl, lo, hi = boot_slope(x, y, rng)
        out.append(dict(corner=S.labels[k], laps=len(x), slowest_speed_sd_kmh=float(np.std(x, ddof=1)), s_per_kmh=-sl, lo=-hi, hi=-lo,
                        corr=float(np.corrcoef(x, y)[0, 1])))
    return out


def anatomy(S, events, valid):
    """For each big event, how the driver's inputs at that corner differed from the median of the other rated laps."""
    rows = []
    for e in events:
        b = e["lap"] - 1
        k = S.labels.index(e["corner"])
        oth = [c for c in range(S.nb) if c != b and valid[c, k]]
        def d(key, scale=1.0, sign=1.0):
            mine = S.ev(b, k, key)
            others = [S.ev(c, k, key) for c in oth if np.isfinite(S.ev(c, k, key))]
            if not np.isfinite(mine) or len(others) < 4:
                return None
            return float(sign * scale * (mine - np.median(others)))
        rows.append(dict(lap=e["lap"], corner=e["corner"], excess_s=e["excess_s"], slowest_speed_vs_median_kmh=d("v_min", KMH),
                         braked_earlier_by_m=d("brake_pos", 1.0, -1.0), peak_brake_vs_median_g=d("brake_peak"),
                         throttle_pickup_later_by_m=d("pickup"), speed_100m_after_vs_median_kmh=d("v_exit100", KMH),
                         peak_lateral_vs_median_g=d("lat_peak")))
    return rows


def pace_gap(S, exc_valid, starts, ends):
    """The pace part, as opposed to the one-off events: per corner, the typical (median) lap against the best three rated laps."""
    out = []
    for k in range(S.K):
        idx = [b for b in range(S.nb) if exc_valid[b, k]]
        if len(idx) < 8:
            continue
        dt = np.array([S.T[b, ends[k]] - S.T[b, starts[k]] for b in idx])
        order = np.argsort(dt)
        best = [idx[i] for i in order[:3]]
        med = float(np.median(dt))
        row = dict(corner=S.labels[k], rated_laps=len(idx), typical_window_s=med, best3_window_s=float(np.mean(dt[order[:3]])),
                   typical_minus_best3_s=med - float(np.mean(dt[order[:3]])), best_laps="/".join(str(S.nums[b]) for b in best))
        for key, scale, name in (("v_min", KMH, "slowest_speed_kmh"), ("v_exit100", KMH, "speed_100m_after_kmh")):
            allv = [S.ev(b, k, key) * scale for b in idx if np.isfinite(S.ev(b, k, key))]
            bv = [S.ev(b, k, key) * scale for b in best if np.isfinite(S.ev(b, k, key))]
            row[name + "_typical"] = float(np.median(allv)) if allv else None
            row[name + "_best3"] = float(np.mean(bv)) if bv else None
        out.append(row)
    out.sort(key=lambda r: -r["typical_minus_best3_s"])
    return out


def lap_summary(S, exc):
    rows = []
    for b in range(S.nb):
        col = exc[b]
        fin = np.isfinite(col)
        rows.append(dict(loop=S.nums[b], loop_time_s=float(S.time[b]), fast=bool(S.fast[b]), clean=bool(S.clean[b]), windows_rated=int(fin.sum()),
                         total_excess_s=float(np.nansum(col)) if fin.any() else None,
                         worst_corner=S.labels[int(np.nanargmax(col))] if fin.any() else None, worst_excess_s=float(np.nanmax(col)) if fin.any() else None))
    return rows



# ====================================================================================================
# output
# ====================================================================================================
def write_csv(path, rows):
    if not rows:
        return
    cols = list(rows[0].keys())
    with open(path, "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(cols)
        for r in rows:
            w.writerow(["" if r[c] is None else (("%.4f" % r[c]).rstrip("0").rstrip(".") if isinstance(r[c], float) else r[c]) for c in cols])


def jsonable(o):
    if isinstance(o, dict):
        return {k: jsonable(v) for k, v in o.items()}
    if isinstance(o, (list, tuple)):
        return [jsonable(v) for v in o]
    if isinstance(o, (np.floating, float)):
        return None if not np.isfinite(o) else float("%.6g" % o)   # significant digits, so a drag coefficient like 0.000187 is not rounded to 0.00019
    if isinstance(o, (np.integer,)):
        return int(o)
    if isinstance(o, (np.bool_,)):
        return bool(o)
    if isinstance(o, np.ndarray):
        return jsonable(o.tolist())
    return o


def analyse(csv_path, work):
    os.makedirs(work, exist_ok=True)
    R = U.run([csv_path], work, replay=False)
    S = Session(R)
    rng = np.random.default_rng(RNG_SEED)
    res = {}
    zone_rows, zone_summ, zone_all = braking(S)
    t1_rows, t1 = first_corner_cost(S, rng)
    corner_summ, corner_laps, corner_fit = cornering(S, rng)
    aero_g, aero_par = aero_expectation(corner_fit["slow_half_speed_kmh"], corner_fit["fast_half_speed_kmh"])
    corner_fit["aero_expected_extra_g_between_halves"] = aero_g
    corner_fit["aero_model_parameters"] = aero_par
    exits = exit_value(S, rng)
    exc, valid, starts, ends = excess(S)
    conc = concentration(S, exc, rng)
    ev = events_table(S, exc, valid, starts, ends)
    pcl = per_corner_loss(S, exc)
    aprice = apex_price(S, valid, starts, ends, rng)
    anat = anatomy(S, ev, valid)
    pgap = pace_gap(S, valid, starts, ends)
    lsum = lap_summary(S, exc)
    res.update(session=os.path.basename(csv_path), loops=S.nb, fast_loops=int(S.fast.sum()), clean_loops=int(S.clean.sum()), map_length_m=S.L,
               ultimate_lap_s=float(S.ult.comp), ultimate_sd_s=float(S.ult.comp_sd),
               best_loop_s=float(np.min(S.time[S.fast])), typical_clean_loop_s=float(np.median(S.time[S.clean])),
               braking=dict(overall=zone_all, first_corner=t1, per_corner=zone_summ),
               cornering=dict(fit=corner_fit, per_corner=corner_summ),
               exit_value=exits, apex_price=aprice, concentration=conc, per_corner_loss=pcl,
               speed_envelope=dict(pw_m2_s3=float(S.ult.env["pw"]), k_per_m=float(S.ult.env["k"]), v_knee_ms=float(S.ult.env["v_knee"])))
    tables = {"braking_zones": zone_rows, "braking_summary": zone_summ, "first_corner_braking": t1_rows, "corner_speed_by_corner": corner_summ,
              "corner_speed_by_lap": corner_laps, "exit_speed_value": exits, "biggest_losses": ev, "loss_by_corner": pcl,
              "apex_speed_value": aprice, "event_anatomy": anat, "pace_gap_by_corner": pgap, "loop_summary": lsum}
    # excess matrix for the heat map
    mat = []
    for b in range(S.nb):
        r = {"lap": S.nums[b], "loop_time_s": float(S.time[b]), "fast": bool(S.fast[b]), "clean": bool(S.clean[b])}
        for k in range(S.K):
            r[S.labels[k]] = None if not np.isfinite(exc[b, k]) else float(exc[b, k])
        mat.append(r)
    tables["time_excess_by_lap_and_corner"] = mat
    return S, res, tables, dict(exc=exc, valid=valid, starts=starts, ends=ends)


def main(argv=None):
    ap = argparse.ArgumentParser(description="Braking, cornering and straight-line power laws, and where the lap time hides.")
    ap.add_argument("csv", help="one Track Logger session CSV")
    ap.add_argument("--out", default=None, help="output folder (default: powerlaws_out next to the CSV)")
    ap.add_argument("--work", default=None, help="folder for the engine's intermediate files (default: OUT/_work)")
    ap.add_argument("--no-plots", action="store_true")
    a = ap.parse_args(argv)
    if not os.path.isfile(a.csv):
        raise SystemExit("Cannot find %s" % a.csv)
    out = a.out or os.path.join(os.path.dirname(os.path.abspath(a.csv)), "powerlaws_out")
    work = a.work or os.path.join(out, "_work")
    os.makedirs(os.path.join(out, "tables"), exist_ok=True)
    S, res, tables, extra = analyse(a.csv, work)
    for name, rows in tables.items():
        write_csv(os.path.join(out, "tables", name + ".csv"), rows)
    with open(os.path.join(out, "numbers.json"), "w") as fh:
        json.dump(jsonable(res), fh, indent=1)
    if not a.no_plots:
        try:
            import powerlaws_figures as PF
        except ImportError as e:
            print("figures skipped: %s" % e)
        else:
            os.makedirs(os.path.join(out, "figures"), exist_ok=True)
            PF.make_all(S, res, tables, extra, os.path.join(out, "figures"))
    b = res["braking"]
    print("%s: %d loops (%d fast, %d clean); ultimate %.3f +- %.2f" % (res["session"], res["loops"], res["fast_loops"], res["clean_loops"],
                                                                      res["ultimate_lap_s"], res["ultimate_sd_s"]))
    print("braking: speed^2 is a straight line against distance in %d lap-zones, median R2 %.3f" % (b["overall"]["n_lap_zones"], b["overall"]["median_r2"]))
    c = res["cornering"]["fit"]
    print("cornering: speed ~ radius^%.2f (90%% interval %.2f-%.2f) over %d corners; apex lateral g %.2f-%.2f" % (
        c["exponent"], c["exponent_ci90"][0], c["exponent_ci90"][1], len(c["corners_used"]), c["apex_lat_g_min"], c["apex_lat_g_max"]))
    k = res["concentration"]
    print("time lost: top %d of %d lap-windows (%.0f%%) hold %.0f%% of the loss" % (k["top5pct_windows"], k["rated_lap_windows"], 5.0, 100 * k["top5pct_share"]))
    print("written to %s" % out)


if __name__ == "__main__":
    main()

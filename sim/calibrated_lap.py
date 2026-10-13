#!/usr/bin/env python3
"""
calibrated_lap.py - does the point-mass physics reproduce the real lap time when it is given what the log says?

    python3 sim/calibrated_lap.py reports/sepang-2026-10-13/numbers.json [--out reports/sepang-2026-10-13/tables/model_lap_from_log.csv]

The quasi-steady-state lap solver in simulate_sepang.py drives a perfect lap (ideal racing line, every corner at the grip limit,
braking and power at their limits).  With the published figures for a 991.2 GT3 it says 2:25.2.  Here the car is changed, step by step,
to what the phone measured on the straights (powerlaws.py wrote the fitted speed envelope  A(v) = pw/v - k v^2  into numbers.json):

    engine   eta * P = pw * m          (so the push at the wheels is what the straights showed)
    drag     0.5 * rho * CdA / m = k
    grip     mu_x (braking and traction) and mu_y (cornering) on a grid, because the log can only bound them:
             steady braking 0.93-1.02 g, sideways g at the slowest points 0.86-1.05 g, highest sideways g in each corner 1.0-1.3 g.

It prints the lap time of the perfect driver for each grid cell.  This is a consistency check on the physics, not a prediction: the grip
numbers are read off the same laps.  The point it makes is how much of the lap time is decided by the grip assumption.
"""
import argparse
import csv
import json
import os
import sys
from dataclasses import replace

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(REPO, "physics"))
import simulate_sepang as SS                                         # noqa: E402
from gt3_model import Car, a_brake                                    # noqa: E402


def clock(t):
    return "%d:%05.2f" % (t // 60, t % 60)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("numbers", help="numbers.json written by analysis/powerlaws.py")
    ap.add_argument("--out", default=None, help="CSV to write")
    a = ap.parse_args(argv)
    num = json.load(open(a.numbers))
    env = num["speed_envelope"]
    m0 = Car().m
    eta = env["pw_m2_s3"] * m0 / Car().P
    cda = env["k_per_m"] * m0 * 2.0 / Car().rho
    M = SS.Model()

    def solve(car):
        SS.CAR = car
        return SS.simulate_lap(M, SS.PERFECT, 1.0)[3]

    rows = []
    published = replace(Car(), eta=0.80, CdA=0.66, mu_x=1.2, mu_y=1.2)
    rows.append(dict(step="published figures (eta 0.80, CdA 0.66)", mu_x=1.2, mu_y=1.2, braking_g_at_150kmh=a_brake(published, 150 / 3.6) / 9.81, lap_s=solve(published)))
    base = replace(Car(), eta=eta, CdA=cda, crr=0.0, mu_x=1.2, mu_y=1.2)
    rows.append(dict(step="engine and drag from the log (eta %.3f, CdA %.3f)" % (eta, cda), mu_x=1.2, mu_y=1.2, braking_g_at_150kmh=a_brake(base, 150 / 3.6) / 9.81, lap_s=solve(base)))
    for mux in (0.80, 0.87, 0.95, 1.05, 1.20):
        for muy in (1.0, 1.1, 1.2, 1.3):
            car = replace(base, mu_x=mux, mu_y=muy)
            rows.append(dict(step="log engine + grip grid", mu_x=mux, mu_y=muy, braking_g_at_150kmh=a_brake(car, 150 / 3.6) / 9.81, lap_s=solve(car)))
    print("%-52s %5s %5s %9s %9s" % ("step", "mu_x", "mu_y", "brake g", "lap"))
    for r in rows:
        print("%-52s %5.2f %5.2f %9.2f %9s" % (r["step"], r["mu_x"], r["mu_y"], r["braking_g_at_150kmh"], clock(r["lap_s"])))
    print("\nreal laps in the log: best %s, typical clean %s, ultimate %s" % (clock(num["best_loop_s"]), clock(num["typical_clean_loop_s"]), clock(num["ultimate_lap_s"])))
    # what is corner speed worth in this car?  Drive one corner at 97 % of the grip-limited speed and see what the lap costs.
    mid = replace(base, mu_x=0.91, mu_y=1.15)
    SS.CAR = mid
    path, v, _, t0 = SS.simulate_lap(M, SS.PERFECT, 1.0)
    worth = []
    for k in range(M.K):
        prm = dict(beta=np.ones(M.K), gv=np.ones(M.K), lam=np.zeros(M.K), dlt=np.zeros(M.K))
        prm["gv"][k] = 0.97
        tk = SS.simulate_lap(M, prm, 1.0)[3]
        c = M.corners[k]
        sel = (path["s"] >= min(c["s_in"], c["s_out"])) & (path["s"] <= max(c["s_in"], c["s_out"]))
        vmin = float(v[sel].min()) * 3.6
        worth.append(dict(corner="T%d" % (k + 1), model_slowest_kmh=vmin, lap_cost_of_3pct_s=tk - t0, model_s_per_kmh_at_slowest_point=(tk - t0) / (0.03 * vmin)))
    print("\ncar: log engine, mu_x 0.91, mu_y 1.15 -> perfect lap %s.  Cost of driving one corner at 97%% of its grip-limited speed:" % clock(t0))
    for r in worth:
        print("  %-4s slowest %5.1f km/h   %5.3f s   %.4f s per km/h" % (r["corner"], r["model_slowest_kmh"], r["lap_cost_of_3pct_s"], r["model_s_per_kmh_at_slowest_point"]))
    if a.out:
        base_dir = os.path.dirname(os.path.abspath(a.out))
        with open(os.path.join(base_dir, "model_corner_speed_value.csv"), "w", newline="") as fh:
            w = csv.writer(fh)
            w.writerow(["corner", "model_slowest_kmh", "lap_cost_of_3pct_s", "model_s_per_kmh_at_slowest_point"])
            for r in worth:
                w.writerow([r["corner"], "%.1f" % r["model_slowest_kmh"], "%.3f" % r["lap_cost_of_3pct_s"], "%.4f" % r["model_s_per_kmh_at_slowest_point"]])
    if a.out:
        os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
        with open(a.out, "w", newline="") as fh:
            w = csv.writer(fh)
            w.writerow(["step", "mu_x", "mu_y", "braking_g_at_150kmh", "lap_s", "lap"])
            for r in rows:
                w.writerow([r["step"], r["mu_x"], r["mu_y"], "%.3f" % r["braking_g_at_150kmh"], "%.2f" % r["lap_s"], clock(r["lap_s"])])


if __name__ == "__main__":
    main()

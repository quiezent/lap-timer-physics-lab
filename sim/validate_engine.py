"""Validate the ultimate-lap engine against simulated Sepang sessions with exact truth.

usage: python3 validate_engine.py out/s1.csv [out/s2.csv ...]   (each CSV has a matching *_truth.npz)
"""
import json
import math
import os
import sys
import time

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
ENGINE_DIR = os.environ.get("ENGINE_DIR", os.path.join(REPO, "analysis"))      # analyze.py / ultimate.py live here
OUT = os.environ.get("SIM_OUT", os.path.join(HERE, "out"))                    # simulated sessions and analysis folders are written here
sys.path.insert(0, ENGINE_DIR)
sys.path.insert(0, HERE)
import analyze as A                      # noqa: E402
import ultimate as U                     # noqa: E402
import simulate_sepang as SS             # noqa: E402

_MODEL = None


def model():
    global _MODEL
    if _MODEL is None:
        _MODEL = SS.Model()
    return _MODEL


def frac_node(enu, x, y):
    d2 = (enu[:, 0] - x) ** 2 + (enu[:, 1] - y) ** 2
    i = int(np.argmin(d2))
    n = len(enu)
    best = (1e18, 0.0)
    for ii in (i - 1, i):
        a = enu[ii % n]
        b = enu[(ii + 1) % n]
        ab = b - a
        t = float(np.clip(np.dot(np.array([x, y]) - a, ab) / np.dot(ab, ab), 0, 1))
        c = a + t * ab
        dd = math.hypot(x - c[0], y - c[1])
        if dd < best[0]:
            best = (dd, (ii % n) + t)
    return best[1], best[0]


class Truth:
    """Exact quantities of one simulated session, expressed at the engine's cuts."""

    def __init__(self, prep, tr):
        M = model()
        self.M = M
        self.tr = tr
        origin = prep["origin"]
        dx, dy = A.to_enu(np.array([M.lat0]), np.array([M.lon0]), origin[0], origin[1])
        self.enu = M.enu + np.array([float(dx[0]), float(dy[0])])
        self.n = M.n
        self.nl = int(tr["nl"])
        ext = np.column_stack([tr["node_times"], tr["lap_times"]])
        self.absT = np.array([tr["sf_times"][j + 1] + ext[j] for j in range(self.nl)])        # (nl, n+1)
        ref = prep["ref"]
        self.cut_nodes = []
        for c in prep["cuts"]:
            x, y = ref.point(np.array([c]))
            f, _ = frac_node(self.enu, float(x[0]), float(y[0]))
            self.cut_nodes.append(f)
        self.cut_nodes = np.array(self.cut_nodes)
        self.tu, self.vk = tr["tu"], tr["vk"]
        K = M.K
        ia = M.i_apex.astype(float)
        nc = len(self.cut_nodes)
        sec_of_corner = np.zeros(K, int)
        for k in range(K):
            for i in range(nc):
                a, b = self.cut_nodes[i], self.cut_nodes[(i + 1) % nc]
                if (ia[k] - a) % self.n < (b - a) % self.n:
                    sec_of_corner[k] = i
        self.sec_of_corner = sec_of_corner
        self.params = dict(beta=tr["beta"], gv=tr["gv"], lam=tr["lam"], dlt=tr["dlt"])
        # engine lap -> truth lap
        self.lap_map = []
        for l in prep["laps"]:
            tt = np.array([np.interp(self.cut_nodes[0], np.arange(self.n + 1), self.absT[j]) for j in range(self.nl)])
            j = int(np.argmin(np.abs(tt - l["tc"][0])))
            self.lap_map.append(j if abs(tt[j] - l["tc"][0]) < 2.0 else -1)

    def t_at(self, j, f):
        return float(np.interp(f, np.arange(self.n + 1), self.absT[j]))

    def true_E(self, laps, price):
        """Exact section value of every engine lap: true section time + the engine's prices on the TRUE cut speeds."""
        nb, nc = len(laps), len(self.cut_nodes)
        E = np.full((nb, nc), np.nan)
        T = np.full((nb, nc), np.nan)
        for b in range(nb):
            j = self.lap_map[b]
            if j < 0:
                continue
            f0 = self.cut_nodes[0]
            for i in range(nc):
                fa, fb = self.cut_nodes[i], self.cut_nodes[(i + 1) % nc]
                # absolute times on the truth lap that contains the engine lap's cut i / i+1
                ta = self.t_at(j, fa) if fa >= f0 else self.t_at(j + 1, fa) if j + 1 < self.nl else np.nan
                tb = self.t_at(j, fb) if fb >= f0 else self.t_at(j + 1, fb) if j + 1 < self.nl else np.nan
                if i == nc - 1:
                    tb = self.t_at(j + 1, f0) if j + 1 < self.nl else np.nan
                if not (np.isfinite(ta) and np.isfinite(tb)):
                    continue
                va = float(np.interp(ta, self.tu, self.vk))
                vb = float(np.interp(tb, self.tu, self.vk))
                T[b, i] = tb - ta
                E[b, i] = T[b, i] + price[i] * va - price[(i + 1) % nc] * vb
        return E, T

    def physical_composite(self, choice_lap):
        """Re-simulate the lap whose corner k is driven as in truth lap choice_lap[k]."""
        prm = SS.mix([dict(beta=self.params["beta"][j], gv=self.params["gv"][j], lam=self.params["lam"][j],
                           dlt=self.params["dlt"][j]) for j in range(self.nl)], list(choice_lap))
        return SS.simulate_lap(self.M, prm)[3]

    def best_choice(self, start, max_sweeps=3):
        """Coordinate descent over sections (all corners of a section from one lap) starting from `start`."""
        nc = len(self.cut_nodes)
        cur = list(start)                                          # per-section truth lap
        def choice_of(sec_laps):
            return [sec_laps[self.sec_of_corner[k]] for k in range(self.M.K)]
        best_T = self.physical_composite(choice_of(cur))
        for _ in range(max_sweeps):
            moved = False
            for i in range(nc):
                for j in range(self.nl):
                    if j == cur[i]:
                        continue
                    trial = list(cur)
                    trial[i] = j
                    Tj = self.physical_composite(choice_of(trial))
                    if Tj < best_T - 1e-6:
                        best_T, cur, moved = Tj, trial, True
            if not moved:
                break
        return cur, best_T, choice_of


def analyse(csv, workdir=None, verbose=True, descend=True):
    tr = np.load(csv.replace(".csv", "_truth.npz"), allow_pickle=True)
    workdir = workdir or f"{OUT}/an_val"
    os.makedirs(workdir, exist_ok=True)
    t0 = time.time()
    prep = U.prepare([csv], workdir=workdir)
    laps = prep["laps"]
    ult = U.Ultimate(laps, prep["cuts_rel"])
    T = Truth(prep, tr)
    nc = len(prep["cuts"])
    Etrue, Ttrue = T.true_E(laps, ult.price)
    out = dict(csv=csv, ncuts=nc, nlaps=len(laps), seconds=time.time() - t0)
    # --- section value errors (engine - truth), per section de-meaned (the cut position offsets are constant per cut)
    errT, errE = [], []
    for i in range(nc):
        m = ult.ok[:, i] & np.isfinite(Etrue[:, i])
        if m.sum() < 2:
            continue
        dT = ult.T[m, i] - Ttrue[m, i]
        dE = ult.E[m, i] - Etrue[m, i]
        errT += list(dT - dT.mean())
        errE += list(dE - dE.mean())
    out["rms_T"] = float(np.sqrt(np.mean(np.square(errT)))) if errT else np.nan
    out["rms_E"] = float(np.sqrt(np.mean(np.square(errE)))) if errE else np.nan
    # --- composite
    pick_truth = [T.lap_map[ult.pick[i]] if ult.pick[i] >= 0 else -1 for i in range(nc)]
    out["valid"] = ult.valid
    out["comp_raw"], out["comp"], out["comp_sd"] = ult.comp_raw, ult.comp, ult.comp_sd
    out["perfect"] = float(tr["perfect_T"])
    out["allbest"] = float(tr["allbest_T"])
    best_lap_engine = min([l["time"] for l in laps if l["fast"]])
    out["best_lap_engine"] = best_lap_engine
    out["best_lap_true"] = float(np.min(tr["lap_times"]))
    out["prices"] = ult.price.tolist()
    if ult.valid and min(pick_truth) >= 0:
        T_true_engine = T.physical_composite([pick_truth[T.sec_of_corner[k]] for k in range(T.M.K)])
        out["comp_true_of_engine_choice"] = T_true_engine
        out["claim_err_raw"] = ult.comp_raw - T_true_engine
        out["claim_err"] = ult.comp - T_true_engine
        if descend:
            cur, best_T, choice_of = T.best_choice(pick_truth)
            # also start from the best true lap
            jb = int(np.argmin(tr["lap_times"]))
            cur2, best_T2, _ = T.best_choice([jb] * nc, max_sweeps=2)
            if best_T2 < best_T:
                cur, best_T = cur2, best_T2
            out["comp_true_opt"] = best_T
            out["regret"] = T_true_engine - best_T
            out["opt_choice"] = cur
        out["engine_choice"] = pick_truth
    if verbose:
        print_one(out, ult, T, laps)
    return out, ult, T, prep


def fmt(t):
    return "%d:%06.3f" % (int(t // 60), t % 60)


def print_one(o, ult, T, laps):
    print("\n==== %s: %d cuts, %d laps (%.1f s) ====" % (os.path.basename(o["csv"]), o["ncuts"], o["nlaps"], o["seconds"]))
    print("price [s per m/s]: " + " ".join("%.3f" % p for p in o["prices"]))
    print("section time error (engine-truth, de-meaned) RMS %.3f s; price-corrected section value RMS %.3f s" % (o["rms_T"], o["rms_E"]))
    print("best lap engine %s (true %s)" % (fmt(o["best_lap_engine"]), fmt(o["best_lap_true"])))
    if o["valid"]:
        print("ultimate lap: raw %s  shrunk %s +-%.3f   (perfect %s, all-best-components %s)" % (
            fmt(o["comp_raw"]), fmt(o["comp"]), o["comp_sd"], fmt(o["perfect"]), fmt(o["allbest"])))
        if "comp_true_of_engine_choice" in o:
            print("physically re-simulated composite of the engine's choice: %s -> claim error raw %+.3f s, shrunk %+.3f s" % (
                fmt(o["comp_true_of_engine_choice"]), o["claim_err_raw"], o["claim_err"]))
        if "regret" in o:
            print("best achievable mix found by search %s -> regret of the engine's choice %.3f s" % (fmt(o["comp_true_opt"]), o["regret"]))
            print("engine choice (truth lap per section):", o["engine_choice"], " search optimum:", o["opt_choice"])
    else:
        print("composite not valid (some section has no clean lap)")


if __name__ == "__main__":
    for csv in sys.argv[1:]:
        analyse(csv)

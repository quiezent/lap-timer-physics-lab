"""Stage-1 test of the engine: map -> progress -> cuts -> laps, judged against the simulator's exact node times."""
import json
import math
import sys

import numpy as np

import os
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
ENGINE_DIR = os.environ.get("ENGINE_DIR", os.path.join(REPO, "analysis"))      # analyze.py / ultimate.py live here
OUT = os.environ.get("SIM_OUT", os.path.join(HERE, "out"))                    # simulated sessions and analysis folders are written here
sys.path.insert(0, ENGINE_DIR)
sys.path.insert(0, HERE)
import analyze as A                      # noqa: E402
import ultimate as U                     # noqa: E402
import simulate_sepang as SS             # noqa: E402


def frac_node(enu, x, y):
    """Fractional node index of the point (x, y) projected on the closed polyline `enu`."""
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


def main(csv, truth_path, quiet=False):
    sessions, origin = U.load_sessions([csv], workdir=f"{OUT}/an_stage1")
    sess = sessions[0]
    ref = U.build_reference(sess)
    P, D = U.track_fixes(ref, sess)
    fit = U.fit_progress(sess, P)
    prof, nrows = U.curvature_profile([sess], [fit], ref)
    ref.kap = prof
    cuts, lens = U.find_cuts(ref)
    laps = U.make_laps(sess, ref, cuts, fit)
    U.lap_filter(laps)
    tr = np.load(truth_path, allow_pickle=True)
    M = SS.Model()
    dx, dy = A.to_enu(np.array([M.lat0]), np.array([M.lon0]), origin[0], origin[1])
    enu = M.enu + np.array([float(dx[0]), float(dy[0])])
    n = M.n
    nodet = tr["node_times"]
    sf = tr["sf_times"]
    nl = int(tr["nl"])
    ext = np.column_stack([nodet, tr["lap_times"]])          # node n == next lap's S/F
    absT = np.array([sf[j + 1] + ext[j] for j in range(nl)])  # (nl, n+1)
    print("map length %.1f m (true line length %.1f), %d cuts from %d laps of gyro curvature, straight lengths %s" % (
        ref.L, M.Lr, len(cuts), nrows, np.round(lens).astype(int).tolist()))
    print("model straight mid-points (centreline s): front 137, 1377, 1727, 2416, 2971, 3693, 4647")
    # cut positions on the model, apex-labelled
    cut_nodes = []
    for m, c in enumerate(cuts):
        x, y = ref.point(np.array([c]))
        f, dd = frac_node(enu, float(x[0]), float(y[0]))
        cut_nodes.append(f)
        # corners before/after
        ia = M.i_apex.astype(float)
        before = [k + 1 for k in range(M.K) if (f - ia[k]) % n < n / 2]
        nxt = int(np.argmin([(ia[k] - f) % n for k in range(M.K)])) + 1
        prv = int(np.argmin([(f - ia[k]) % n for k in range(M.K)])) + 1
        print("  cut %d: map s=%7.1f  model node %7.2f (%.0f m)  off-line %.1f m   between T%d and T%d" % (
            m, c, f, f * 5.0, dd, prv, nxt))
    cut_nodes = np.array(cut_nodes)
    # ---- compare crossing times
    rows = []
    for li, l in enumerate(laps):
        for m in range(len(cuts) + 1):
            mm = m % len(cuts)
            f = cut_nodes[mm]
            tcr = l["tc"][m]
            tt = np.array([np.interp(f, np.arange(n + 1), absT[j]) for j in range(nl)])
            j = int(np.argmin(np.abs(tt - tcr)))
            if abs(tt[j] - tcr) < 2.0:
                rows.append((li, m, j, tcr - tt[j], l["st"][m], l["sx"][m], l["poor"][m]))
    rows = np.array(rows, dtype=float)
    print("\nengine laps:", len(laps), " matched crossings:", len(rows))
    print("lap  time(engine)  truth-lap  fast clean  poor")
    for li, l in enumerate(laps):
        sel = rows[rows[:, 0] == li]
        jj = int(np.round(np.median(sel[:, 2]))) if len(sel) else -1
        print("%3d  %9.3f  %s  %s %s  %s" % (li, l["time"], ("%9.3f" % tr["lap_times"][jj]) if jj >= 0 else "     -   ",
                                          l["fast"], l["clean"], int(l["poor"].sum())))
    # per-cut de-meaned error
    print("\ncut  mean-offset(s)  std(s)  claimed-sigma_t(s)")
    err_all = []
    for m in range(len(cuts)):
        sel = rows[(rows[:, 1] % len(cuts) == m) & (rows[:, 6] == 0)]
        if len(sel) < 2:
            continue
        e = sel[:, 3] - sel[:, 3].mean()
        err_all += list(e)
        print("%3d  %+8.3f   %6.3f   %6.3f" % (m, sel[:, 3].mean(), e.std(ddof=1), sel[:, 4].mean()))
    err_all = np.array(err_all)
    print("overall cut-time error RMS %.3f s (n=%d)" % (math.sqrt(np.mean(err_all ** 2)), len(err_all)))
    # section-time errors (difference of adjacent cuts)
    print("\nsection-time errors, flying laps (engine - truth, mean removed per section):")
    for m in range(len(cuts)):
        errs = []
        for li, l in enumerate(laps):
            if l["poor"][m] or l["poor"][m + 1]:
                continue
            a = rows[(rows[:, 0] == li) & (rows[:, 1] == m)]
            b = rows[(rows[:, 0] == li) & (rows[:, 1] == m + 1)]
            if len(a) and len(b):
                errs.append((b[0, 3] - a[0, 3]))
        errs = np.array(errs)
        if len(errs) > 1:
            print("  section %d: n=%d  mean %+.3f  std %.3f" % (m, len(errs), errs.mean(), errs.std(ddof=1)))
    return dict(laps=laps, cuts=cuts, fit=fit, ref=ref, sess=sess, rows=rows, cut_nodes=cut_nodes, tr=tr, M=M)


if __name__ == "__main__":
    csv = sys.argv[1] if len(sys.argv) > 1 else os.path.join(OUT, "s1.csv")
    main(csv, csv.replace(".csv", "_truth.npz"))

"""Layer-2 test: registration of the map onto the Sepang model, corner events of measured laps vs exact truth."""
import json
import math
import os
import sys

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
import validate_engine as V              # noqa: E402

METRICS = ["v_min", "brake_pos", "brake_peak", "v_brake", "pickup", "v_exit100", "turn_in", "lat_peak"]
G0 = SS.G0


def truth_events(T, corners_model_nodes, linked, lat_ref, brake_ok, shifts=None):
    """Corner events of every truth lap, on the exact profile, with the shared rules."""
    M = T.M
    out = []
    for j in range(T.nl):
        prm = dict(beta=T.params["beta"][j], gv=T.params["gv"][j], lam=T.params["lam"][j], dlt=T.params["dlt"][j])
        path, v, t, tot = SS.simulate_lap(M, prm)
        n = len(v)
        a = np.gradient(0.5 * v * v, SS.DS) / G0
        lat = v * v * path["kap"] / G0
        node_idx = np.interp(path["s"], np.r_[path["node_s"], path["L"]], np.r_[np.arange(M.n), M.n])
        ia = [int(np.argmin(np.abs(node_idx - nd))) for nd in corners_model_nodes]
        if shifts is not None:                                  # the engine moved the reference apexes onto the data: do the same here
            ia = [(i + int(round(sh / SS.DS))) % n for i, sh in zip(ia, shifts)]
        ev = []
        for k in range(len(ia)):
            ev.append(U.corner_events(v, a, lat, SS.DS, ia[k], ia[k - 1], ia[(k + 1) % len(ia)], linked=linked[k], lat_ref=lat_ref[k], brake=brake_ok[k]))
        out.append(ev)
    return out


def main(csv):
    tr = np.load(csv.replace(".csv", "_truth.npz"), allow_pickle=True)
    prep = U.prepare([csv], workdir=f"{OUT}/an_events")
    laps, ref, cuts = prep["laps"], prep["ref"], prep["cuts"]
    ult = U.Ultimate(laps, prep["cuts_rel"])
    model = U.load_sepang(prep["origin"])
    reg = U.register_map(ref, model)
    print("registration rms %.2f m, shift %s, rotation %.2f deg" % (reg[2], np.round(reg[1], 1), math.degrees(math.atan2(reg[0][1, 0], reg[0][0, 0]))))
    corners = U.sepang_corners(ref, model, reg, cuts)
    print("corners registered:", None if corners is None else len(corners))
    T = V.Truth(prep, tr)
    # truth apex position (m after cut 0 along the racing line) for comparison
    sr, Lr = model["sr"], model["Lr"]
    f0 = T.cut_nodes[0]
    s0 = float(np.interp(f0, np.arange(len(sr) + 1), np.r_[sr, Lr]))
    print("T#  dir  engine_rel  truth_rel  diff   (m after cut 0)")
    for c, ia in zip(corners, model["i_apex"]):
        trel = (sr[ia] - s0) % Lr
        print("T%-2d  %s   %8.1f   %8.1f  %+6.1f   dist_to_map %.1f" % (c["n"], c["dir"], c["rel"], trel, c["rel"] - trel, c["dist_to_map"]))
    # sort corners by position along the lap
    order = sorted(range(len(corners)), key=lambda k: corners[k]["rel"])
    corners_s = [corners[k] for k in order]
    nodes_s = [model["i_apex"][k] for k in order]
    EV = U.build_events(laps, corners_s, prep["cuts_rel"], ref.L)
    print("linked corners:", [corners_s[k]["n"] for k in range(len(corners_s)) if EV["linked"][k]])
    tev = truth_events(T, nodes_s, EV["linked"], EV["lat_ref"], EV["brake_ok"], shifts=EV.get("apex_shift"))
    print("brake_ok:", [corners_s[k]["n"] for k in range(len(corners_s)) if EV["brake_ok"][k]])
    # engine events
    errs = {m: [] for m in METRICS}
    derr = {m: [] for m in METRICS}
    per_corner = {}
    for b, l in enumerate(laps):
        j = T.lap_map[b]
        if j < 0 or not l["ok_sec"].all():
            continue
        for k, c in enumerate(corners_s):
            e = EV["ev"][b][k]
            tv = tev[j][k]
            for m in METRICS:
                if np.isfinite(e[m]) and np.isfinite(tv[m]):
                    per_corner.setdefault((c["n"], m), []).append((e[m] - tv[m], j))
    if os.environ.get("DUMP"):
        json.dump({"%d|%s" % k: [(float(e), int(j)) for e, j in v] for k, v in per_corner.items()}, open(os.environ["DUMP"], "w"))
    # summarise
    print("\nmetric      n    bias     std(within corner)   RMS error (delta between laps ~ std*sqrt2)")
    for m in METRICS:
        allv = []
        within = []
        for (cn, mm), lst in per_corner.items():
            if mm != m or len(lst) < 3:
                continue
            e = np.array([x[0] for x in lst])
            allv += list(e)
            within += list(e - e.mean())
        if allv:
            sc = 3.6 if m in ("v_min", "v_brake", "v_exit100") else 1.0
            print("%-10s %4d  %+8.3f   %8.3f %s" % (m, len(allv), sc * np.mean(allv), sc * np.std(within), "km/h" if sc > 1 else ("g" if m in ("brake_peak", "lat_peak") else "m")))
    print("\nper-corner within-corner std of the error (metres / g / km/h):")
    hdr = "T   " + " ".join("%-10s" % m for m in METRICS)
    print(hdr)
    for c in sorted(corners_s, key=lambda c: c["n"]):
        row = []
        for m in METRICS:
            lst = per_corner.get((c["n"], m), [])
            if len(lst) >= 3:
                e = np.array([x[0] for x in lst])
                sc = 3.6 if m in ("v_min", "v_brake", "v_exit100") else 1.0
                row.append("%5.2f(%d)" % (sc * e.std(), len(lst)))
            else:
                row.append("   -     ")
        print("T%-2d " % c["n"] + " ".join("%-10s" % r for r in row))
    return prep, ult, corners_s, tev


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else os.path.join(OUT, "s1.csv"))

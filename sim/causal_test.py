"""Test the causal speed/progress against truth: elapsed-time error at the lap checkpoints."""
import sys, time, numpy as np
import os
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
ENGINE_DIR = os.environ.get("ENGINE_DIR", os.path.join(REPO, "analysis"))      # analyze.py / ultimate.py live here
OUT = os.environ.get("SIM_OUT", os.path.join(HERE, "out"))                    # simulated sessions and analysis folders are written here
sys.path.insert(0, ENGINE_DIR); sys.path.insert(0, HERE)
import ultimate as U, validate_engine as V

def main(csv, q_acc=0.02, verbose=True):
    tr = np.load(csv.replace(".csv", "_truth.npz"), allow_pickle=True)
    prep = U.prepare([csv], workdir=f"{OUT}/an_causal")
    sess, ref, cuts, laps = prep["sessions"][0], prep["ref"], prep["cuts"], prep["laps"]
    t0 = time.time()
    vc = U.causal_speed(sess)
    t1 = time.time()
    Pc = U.causal_progress(sess, vc, sess["P_fix"], q_acc=q_acc)
    t2 = time.time()
    T = V.Truth(prep, tr)
    v_off = sess["v"]
    ok = np.arange(len(vc)) > 2000
    print("causal speed vs smoothed: rms %.3f m/s (%.1f s to compute); progress filter %.1f s" % (np.sqrt(np.mean((vc[ok] - v_off[ok]) ** 2)), t1 - t0, t2 - t1))
    # elapsed-time error at checkpoints: the truth elapsed time from the node times
    errs = []
    for b, l in enumerate(laps):
        j = T.lap_map[b]
        if j < 0 or not l["fast"]:
            continue
        got = U.lap_checkpoints(sess, ref, cuts[0], l, Pc)
        if got is None:
            continue
        e_c, T_c = got
        # truth: time of the lap at the checkpoint positions. position -> node fraction via the map positions
        L = ref.L
        tt = []
        for c in U.CHECKPOINTS:
            x, y = ref.point(np.array([cuts[0] + c * L]))
            f, _ = V.frac_node(T.enu, float(x[0]), float(y[0]))
            # time of that node on the truth lap containing this engine lap
            tn = T.t_at(j, f) if f >= T.cut_nodes[0] else T.t_at(j + 1, f)
            errs.append(tn)
        t_start = T.t_at(j, T.cut_nodes[0])
        tt = np.array(errs[-len(U.CHECKPOINTS):]) - t_start
        e_true = tt
        errs_lap = e_c - e_true
        errs.append(None)
        errs[-1] = errs_lap
    return None

if __name__ == "__main__":
    main(sys.argv[1])

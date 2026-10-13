import sys, json, numpy as np
import os
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
ENGINE_DIR = os.environ.get("ENGINE_DIR", os.path.join(REPO, "analysis"))      # analyze.py / ultimate.py live here
OUT = os.environ.get("SIM_OUT", os.path.join(HERE, "out"))                    # simulated sessions and analysis folders are written here
sys.path.insert(0, ENGINE_DIR)
os.makedirs(OUT, exist_ok=True)
import ultimate as U
csvs = sys.argv[2:]
tag = sys.argv[1]
res = []
for c in csvs:
    prep = U.prepare([c], workdir=OUT + "/an_pp_" + tag)
    pr = U.predictor_eval(prep)
    if pr is None:
        print(c, "no predictor"); continue
    res.append(dict(csv=c, n=int(pr["n"]) if np.ndim(pr["n"]) == 0 else int(np.sum(pr["n"])), cps=list(map(float, pr["cps"])),
                    rmse={k: [float(x) for x in v] for k, v in pr["rmse"].items()}))
    print(c, "laps", res[-1]["n"], flush=True)
json.dump(res, open(OUT + "/pooled_pred_%s.json" % tag, "w"))

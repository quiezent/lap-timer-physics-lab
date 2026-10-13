import json, sys, os
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
ENGINE_DIR = os.environ.get("ENGINE_DIR", os.path.join(REPO, "analysis"))      # analyze.py / ultimate.py live here
OUT = os.environ.get("SIM_OUT", os.path.join(HERE, "out"))                    # simulated sessions and analysis folders are written here
sys.path.insert(0, HERE)
import numpy as np
import validate_engine as V


def clean(o):
    if isinstance(o, dict):
        return {k: clean(v) for k, v in o.items()}
    if isinstance(o, (list, tuple)):
        return [clean(v) for v in o]
    if isinstance(o, (np.floating, np.integer)):
        return o.item()
    if isinstance(o, np.ndarray):
        return clean(o.tolist())
    return o


if __name__ == "__main__":
    tag = sys.argv[1]
    res = []
    for csv in sys.argv[2:]:
        o, ult, T, prep = V.analyse(csv, workdir=os.path.dirname(os.path.abspath(csv)) + "/an_" + os.path.basename(csv)[:-4], verbose=True)
        res.append(clean(o))
        json.dump(res, open(os.path.dirname(os.path.abspath(csv)) + f"/result_{tag}.json", "w"), indent=1)

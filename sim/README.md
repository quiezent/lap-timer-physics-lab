# The simulator and the tests built on it

A real log has no answer key. This folder makes one: a simulated GT3 drives a simulated Sepang with driver mistakes I planted on purpose, a simulated phone records it into the same CSV the app writes ([`../docs/log-format.md`](../docs/log-format.md)),
and the exact truth is saved beside it. The analysis then has to find what I planted. What came out of that is in [`../docs/validation.md`](../docs/validation.md).

A caution that applies to everything here: the simulator is **my own model** of the track, the car, the driver and the phone. Passing against it says the method works when my assumptions hold. It does not say it works on a real phone; the one real session is the test for that.

## Files

| File | What it does |
|---|---|
| `simulate_sepang.py` | Drives a perfect lap (a quasi-steady-state solver: ideal line, every corner at the grip limit) and flying laps with planted errors in four numbers per corner (braking effort, corner speed, throttle pick-up, line), then writes the app's CSV, with GPS delay, noise, dropouts and outliers, a random mounting angle, and `*_truth.npz` with the exact lap profiles and per-corner truth. Options: `--seed --laps --gps-sigma --gps-lag --dropout ...` |
| `validate_engine.py` | Runs `analysis/ultimate.py` on a simulated session and compares it with the truth: section times, the ultimate lap and its error bar, the choice of pieces against the best mix, the speed fusion, the lap predictor |
| `run_val.py` | Command line for the above: `python sim/run_val.py TAG session.csv [more.csv ...]` writes `result_TAG.json` next to the CSV |
| `run_batch.sh` | `bash sim/run_batch.sh N` simulates N fresh sessions (seeds 301 upward, 6 to 10 laps, GPS error 2 to 4 m), judges each, and prints the table that `docs/validation.md` quotes. About a minute per session |
| `summarise_batch.py` | Builds that table from the `result_*.json` files |
| `events_test.py` | Tests the corner events (braking point, slowest speed, exit speed, throttle pick-up, peak braking) against the truth |
| `stage1_test.py` | Tests the first stage of the engine (map, progress along the track, section cuts, laps) against the exact node times |
| `causal_test.py`, `pooled_pred.py` | The "live" speed and progress filters and the lap-time predictor: error against the truth, pooled over sessions |
| `calibrated_lap.py` | Not a test of the engine: asks whether the point-mass physics reproduces the real lap when it is given what the real log showed (engine, drag, grip on a grid). Writes the two `model_*` tables in the report |

`../physics/gt3_model.py` is the car model they all use.

## Running

```
pip install -r requirements.txt
bash sim/run_batch.sh 8                         # eight fresh sessions, a table at the end
SIM_OUT=/tmp/sim bash sim/run_batch.sh 4        # write somewhere else (the default folder, sim/out, is git-ignored)

python sim/simulate_sepang.py --seed 7 --laps 8 --gps-sigma 3 --out /tmp/s7.csv
python sim/run_val.py S7 /tmp/s7.csv
```

The scripts find the analysis in `../analysis` by default; set `ENGINE_DIR` to test another copy of it.

The output of `bash sim/run_batch.sh 8` is stored in [`results/batch_b301-b308.txt`](results/batch_b301-b308.txt). It is what `docs/validation.md` quotes, and a second run from a fresh clone gave the same numbers.

## What would make this better

- A simulator for **point-to-point stages** (see the roadmap, section 2), with known gate positions, so the stage timer and the effect of a start/finish line placed a few metres wrong can be tested with the truth in hand.
- Other circuits.
- A "real driver" noise model fitted to more than one real session. My planted errors are simpler than real driving, which is part of why the lap predictor was weaker on the real session than on the simulated ones.

#!/usr/bin/env python3
"""Summarise the result_*.json files that sim/run_val.py writes: one line per simulated session and the totals."""
import json
import sys

import numpy as np


def main(paths):
    rows = []
    for p in paths:
        for r in json.load(open(p)):
            rows.append(r)
    if not rows:
        raise SystemExit("no results")
    print("%-10s %5s %9s %9s %9s %9s %9s %9s" % ("session", "laps", "sect rms", "claim err", "bar", "err/bar", "regret", "fast loop"))
    ce, bar, rg, rt, bl = [], [], [], [], []
    for r in rows:
        name = r["csv"].split("/")[-1]
        e = r["claim_err"]
        ce.append(e); bar.append(r["comp_sd"]); rg.append(r["regret"]); rt.append(r["rms_T"])
        bl.append(r["best_lap_engine"] - r["best_lap_true"])
        print("%-10s %5d %8.3fs %+8.3fs %8.3fs %9.2f %8.3fs %+8.3fs" % (name, r["nlaps"], r["rms_T"], e, r["comp_sd"], e / r["comp_sd"], r["regret"], bl[-1]))
    ce, bar, rg, rt = map(np.array, (ce, bar, rg, rt))
    print("\n%d sessions: section-time error %.3f s rms; ultimate-lap claim error %+.3f s mean, %.3f s rms (%.3f s worst); "
          "error / printed bar rms %.2f; piece choice %.3f s worse than the best mix on average (worst %.3f s); "
          "fastest usable loop differs from the true fastest loop by %.3f s (median), %.3f s (largest; a loop the engine could not use is the usual cause)" % (
              len(rows), np.sqrt(np.mean(rt ** 2)), ce.mean(), np.sqrt(np.mean(ce ** 2)), np.abs(ce).max(), np.sqrt(np.mean((ce / bar) ** 2)), rg.mean(), rg.max(),
              np.median(np.abs(bl)), np.abs(bl).max()))


if __name__ == "__main__":
    main(sys.argv[1:])

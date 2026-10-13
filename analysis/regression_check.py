#!/usr/bin/env python3
"""
regression_check.py - did a change to the analysis move any result on the committed Sepang session?

    python analysis/regression_check.py [--strict] [--skip-ultimate] [--keep DIR]

It re-runs ultimate.py and powerlaws.py on data/sepang-2026-10-13/session_20261013_095638.csv into a temporary folder
and compares what they write with what is committed in reports/sepang-2026-10-13/:

    engine/ultimate_sections.csv, engine/ultimate_corners.csv, engine/session_*_analysis/{lap_table,corner_table,channels}.csv
    numbers.json (every figure the report quotes) and tables/*.csv (except the two model_* tables, which sim/calibrated_lap.py writes)

The runs are deterministic, so on the same library versions everything is identical. On other versions of numpy/scipy a value can move in its
last printed digit, so by default a number counts as equal when it is within one unit of the last digit that is printed (numbers.json: within
2e-5 relative). --strict asks for exact equality instead.

Where a result only differs in the last printed digit the output says how many cells did. Inside GitHub Actions the failures (and those
last-digit differences) are also written as workflow annotations, so they can be read from the run page and the API without the log.

Exit status 0 if nothing moved, 1 if anything did. If you changed the analysis ON PURPOSE, re-run the two scripts into reports/sepang-2026-10-13/
(see docs/analysis-manual.md), commit the new outputs with your change, and say in the pull request which numbers moved and why.
Takes about two minutes. Needs numpy, scipy and matplotlib (requirements.txt).
"""
import argparse
import csv
import glob
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
SESSION = os.path.join(REPO, "data", "sepang-2026-10-13", "session_20261013_095638.csv")
COMMITTED = os.path.join(REPO, "reports", "sepang-2026-10-13")
MAX_SHOWN = 8
MAX_ANNOTATED = 12                              # lines per GitHub annotation


def annotate(level, title, lines):
    """One GitHub Actions workflow command (shown on the run page); does nothing outside Actions."""
    if os.environ.get("GITHUB_ACTIONS") != "true" or not lines:
        return
    esc = lambda t: t.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    esc_prop = lambda t: esc(t).replace(":", "%3A").replace(",", "%2C")
    print("::%s title=%s::%s" % (level, esc_prop(title), esc("\n".join(lines))), flush=True)


def cell_equal(a, b, strict):
    """a is the committed text, b the new text of one CSV cell."""
    if a == b:
        return True
    if strict:
        return False
    try:
        x, y = float(a), float(b)
    except ValueError:
        return False
    if x != x and y != y:                      # both nan
        return True
    if "e" in a.lower():                       # 1.2e-04 style: compare relatively
        return abs(x - y) <= 1e-2 * max(abs(x), abs(y), 1e-300)
    decimals = len(a.split(".")[1]) if "." in a else 0
    return abs(x - y) <= 1.0001 * 10 ** (-decimals)


def compare_csv(path_old, path_new, strict):
    with open(path_old, newline="", encoding="utf-8") as f:
        old = list(csv.reader(f))
    with open(path_new, newline="", encoding="utf-8") as f:
        new = list(csv.reader(f))
    diffs = []
    if old and new and old[0] != new[0]:
        diffs.append("header differs: %s -> %s" % (old[0], new[0]))
        return diffs
    if len(old) != len(new):
        diffs.append("%d rows committed, %d rows now" % (len(old), len(new)))
    for i, (ro, rn) in enumerate(zip(old, new)):
        if len(ro) != len(rn):
            diffs.append("row %d has %d fields, was %d" % (i + 1, len(rn), len(ro)))
            continue
        for j, (a, b) in enumerate(zip(ro, rn)):
            if not cell_equal(a, b, strict):
                col = old[0][j] if old and j < len(old[0]) else str(j)
                diffs.append("row %d, %s: %s -> %s" % (i + 1, col, a, b))
    return diffs


def flatten(o, prefix=""):
    if isinstance(o, dict):
        for k in sorted(o):
            yield from flatten(o[k], prefix + "/" + str(k))
    elif isinstance(o, list):
        for i, v in enumerate(o):
            yield from flatten(v, prefix + "[%d]" % i)
    else:
        yield prefix, o


def compare_json(path_old, path_new, strict):
    old = dict(flatten(json.load(open(path_old, encoding="utf-8"))))
    new = dict(flatten(json.load(open(path_new, encoding="utf-8"))))
    diffs = []
    for k in sorted(set(old) | set(new)):
        if k not in new:
            diffs.append("%s: gone (was %r)" % (k, old[k]))
        elif k not in old:
            diffs.append("%s: new (%r)" % (k, new[k]))
        else:
            a, b = old[k], new[k]
            if a == b:
                continue
            if (not strict and isinstance(a, (int, float)) and isinstance(b, (int, float))
                    and not isinstance(a, bool) and not isinstance(b, bool)
                    and abs(a - b) <= 2e-5 * max(abs(a), abs(b)) + 1e-12):
                continue
            diffs.append("%s: %r -> %r" % (k, a, b))
    return diffs


def run(cmd):
    p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, universal_newlines=True)
    if p.returncode != 0:
        print(p.stdout[-3000:])
        raise SystemExit("FAILED to run: " + " ".join(cmd))


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--strict", action="store_true", help="exact equality instead of 'within the last printed digit'")
    ap.add_argument("--skip-ultimate", action="store_true", help="only re-run powerlaws.py (about 20 s)")
    ap.add_argument("--keep", metavar="DIR", help="write the new outputs here and keep them, instead of a temporary folder")
    args = ap.parse_args()

    if not os.path.exists(SESSION):
        raise SystemExit("the committed session is missing: " + SESSION)
    tmp = None
    if args.keep:
        out = os.path.abspath(args.keep)
        os.makedirs(out, exist_ok=True)
    else:
        tmp = tempfile.TemporaryDirectory()
        out = tmp.name

    checks = []                                    # (label, path_old, path_new, kind)
    if not args.skip_ultimate:
        print("running ultimate.py ...", flush=True)
        run([sys.executable, os.path.join(HERE, "ultimate.py"), SESSION, "--out", os.path.join(out, "engine")])
        for name in ("ultimate_sections.csv", "ultimate_corners.csv"):
            checks.append(("engine/" + name, os.path.join(COMMITTED, "engine", name), os.path.join(out, "engine", name), "csv"))
        for d in sorted(glob.glob(os.path.join(COMMITTED, "engine", "session_*_analysis"))):
            base = os.path.basename(d)
            for name in ("lap_table.csv", "corner_table.csv", "channels.csv"):
                checks.append(("engine/%s/%s" % (base, name), os.path.join(d, name), os.path.join(out, "engine", base, name), "csv"))
    print("running powerlaws.py ...", flush=True)
    run([sys.executable, os.path.join(HERE, "powerlaws.py"), SESSION, "--out", os.path.join(out, "pl"), "--work", os.path.join(out, "pl_work")])
    checks.append(("numbers.json", os.path.join(COMMITTED, "numbers.json"), os.path.join(out, "pl", "numbers.json"), "json"))
    for p in sorted(glob.glob(os.path.join(COMMITTED, "tables", "*.csv"))):
        name = os.path.basename(p)
        if name.startswith("model_"):
            continue                               # written by sim/calibrated_lap.py, not by these two scripts
        checks.append(("tables/" + name, p, os.path.join(out, "pl", "tables", name), "csv"))

    bad = 0
    noisy = 0
    errors, notices = [], []
    for label, old, new, kind in checks:
        if not os.path.exists(new):
            print("FAIL  %s: the script did not write it" % label)
            errors.append("%s: the script did not write it" % label)
            bad += 1
            continue
        compare = compare_json if kind == "json" else compare_csv
        diffs = compare(old, new, args.strict)
        exact = diffs if args.strict else compare(old, new, True)
        if diffs:
            bad += 1
            print("FAIL  %s: %d difference(s)" % (label, len(diffs)))
            for d in diffs[:MAX_SHOWN]:
                print("        " + d)
            if len(diffs) > MAX_SHOWN:
                print("        ... and %d more" % (len(diffs) - MAX_SHOWN))
            errors.append("%s: %d difference(s)" % (label, len(diffs)))
            errors.extend("    " + d for d in diffs[:MAX_SHOWN])
        elif exact:
            noisy += 1
            print("PASS  %s (%d value(s) differ in the last printed digit)" % (label, len(exact)))
            notices.append("%s: %d value(s) differ in the last printed digit" % (label, len(exact)))
            notices.extend("    " + d for d in exact[:4])
        else:
            print("PASS  " + label)
    print("\n%d of %d outputs unchanged%s%s" % (len(checks) - bad - noisy, len(checks),
                                               "" if not noisy else "; %d within the last printed digit" % noisy,
                                               "" if bad == 0 else "; %d moved" % bad))
    annotate("error", "regression_check: results moved", errors[:MAX_ANNOTATED * 4])
    annotate("notice", "regression_check: last-digit differences", notices[:MAX_ANNOTATED * 4])
    if args.keep:
        print("new outputs kept in " + out)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())

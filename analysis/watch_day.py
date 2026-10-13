#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
watch_day.py - keep the ultimate-lap report up to date while the day's sessions arrive.

    python watch_day.py FOLDER [--out DIR] [--every SECONDS] [--pattern "session_*.csv"] [--no-plots] [--no-model]

Copy each session file from the phone into FOLDER as the day goes on (cable, Drive, Files app...). Whenever a new or
changed file has stopped growing, every matching file in FOLDER is analysed together and the report in --out
(default: FOLDER/ultimate_out) is rewritten, so the ultimate lap and the gaps update after each session.
Stop with Ctrl-C. Needs the same things as ultimate.py (numpy, matplotlib for figures, analyze.py next to this file).
"""
import argparse
import glob
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)


def snapshot(paths):
    """{path: (size, mtime)} of the files that exist and are not empty."""
    out = {}
    for p in paths:
        try:
            st = os.stat(p)
        except OSError:
            continue
        if st.st_size > 0:
            out[p] = (st.st_size, st.st_mtime)
    return out


def main(argv=None):
    for st in (sys.stdout, sys.stderr):                      # a folder or file name the console cannot show must not stop the watcher
        try:
            st.reconfigure(errors="replace")
        except Exception:
            pass
    ap = argparse.ArgumentParser(description="Re-run the ultimate-lap analysis whenever a new session file arrives.")
    ap.add_argument("folder", help="folder the session files are copied into")
    ap.add_argument("--out", default=None, help="report folder (default: FOLDER/ultimate_out)")
    ap.add_argument("--every", type=float, default=5.0, help="seconds between looks at the folder (default 5)")
    ap.add_argument("--pattern", default="session_*.csv", help="which files are sessions (default session_*.csv)")
    ap.add_argument("--no-plots", action="store_true")
    ap.add_argument("--no-model", action="store_true", help="do not use the built-in Sepang model")
    ap.add_argument("--once", action="store_true", help="analyse what is there now and exit")
    a = ap.parse_args(argv)
    if not os.path.isdir(a.folder):
        raise SystemExit("Cannot find the folder: %s" % a.folder)
    import ultimate as U
    out = a.out or os.path.join(a.folder, "ultimate_out")
    flags = (["--no-plots"] if a.no_plots else []) + (["--no-model"] if a.no_model else [])
    done = {}                    # snapshot that the last report was made from
    prev = {}                    # snapshot at the previous look, to see that files have stopped growing
    print("Watching %s for %s  (Ctrl-C to stop)" % (os.path.abspath(a.folder), a.pattern))
    try:
        while True:
            now = snapshot(sorted(glob.glob(os.path.join(a.folder, a.pattern))))
            settled = {p: v for p, v in now.items() if prev.get(p) == v} if not a.once else now
            if settled and settled != done:
                names = sorted(os.path.basename(p) for p in settled)
                print("\n%s  analysing %d file(s): %s" % (time.strftime("%H:%M:%S"), len(names), ", ".join(names)))
                try:
                    U.main(sorted(settled) + ["--out", out] + flags)
                except SystemExit as e:
                    if e.code not in (None, 0):
                        print("  could not analyse yet: %s" % e)
                except Exception as e:                                  # keep watching whatever one file does
                    print("  analysis failed: %s: %s" % (type(e).__name__, e))
                done = settled
            if a.once:
                break
            prev = now
            time.sleep(max(a.every, 0.5))
    except KeyboardInterrupt:
        print("\nStopped.")


if __name__ == "__main__":
    main()

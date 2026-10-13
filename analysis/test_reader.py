#!/usr/bin/env python3
"""
test_reader.py - does the reader (analyze.load_session) take every kind of row the app writes?

    python analysis/test_reader.py

It writes small recordings into a temporary folder, one per case, reads them and checks what comes back. It needs numpy only and takes
under a second. It was added with the stage rows of release 1.4, when the first stage recording I checked made check_log.py report
"3 unknown or malformed rows": the reader dropped every event whose values are words (a stage id, a reason) because it tried to read
them as numbers. The log format (docs/log-format.md) says unknown events are ignored; these three are known and must be kept.

Exit status 0 if every case passes, 1 otherwise.
"""
import os
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import analyze  # noqa: E402

HEADER = ["# TrackLogger 1.4 (test)", "# device,synthetic"]
BASE = [
    "E,0,START",
    "A,10000,0.1,0.2,9.8",
    "G,10000,0.0,0.0,0.01",
    "L,500000,3.5558770,101.6565321,40.0,12.5,90.0,3.5,0.3,5.0,1791885600500,150",
    "S,1000000,20,14",
    "K,1000000,A,7",
]
STAGE_EVENTS = [
    "E,1500000,STAGE,mountain_road",
    "E,2100000,MARK_START,3.5558770,101.6565321,3.5",
    "E,60000000,STAGE_START",
    "E,300123456,STAGE_FINISH,240.123",
    "E,301000000,STAGE_DNF,stopped for 20 minutes",
    "E,302000000,STAGE_ABORT,moved away from the start line",
    "E,303000000,MARK_FINISH,3.5600000,101.6700000,4.0",
]
RESULTS = []


def read(lines, newline="\n", tail=""):
    with tempfile.TemporaryDirectory() as d:
        path = os.path.join(d, "session_20261013_100000.csv")
        with open(path, "w", encoding="utf-8", newline="") as f:
            f.write(newline.join(HEADER + lines) + tail)
        return analyze.load_session(path)


def check(name, ok, detail=""):
    RESULTS.append(bool(ok))
    print("%s  %s%s" % ("PASS" if ok else "FAIL", name, ("   [" + detail + "]") if detail else ""))


def main():
    # 1. every event the app writes since 1.4, with the values it writes
    D = read(BASE + STAGE_EVENTS + ["E,400000000,STOP"])
    ev = {name: (t, vals) for (t, name, vals) in D["E"]}
    check("R1 no row is counted as bad when a recording holds every stage event", D["bad"] == 0, "bad=%d" % D["bad"])
    check("R2 all nine event names are kept", sorted(ev) == sorted(
        ["START", "STAGE", "MARK_START", "STAGE_START", "STAGE_FINISH", "STAGE_DNF", "STAGE_ABORT", "MARK_FINISH", "STOP"]), str(sorted(ev)))
    check("R3 STAGE keeps its id as text", ev.get("STAGE", (0, None))[1] == ["mountain_road"], str(ev.get("STAGE")))
    check("R4 STAGE_DNF and STAGE_ABORT keep their reasons as text",
          ev.get("STAGE_DNF", (0, None))[1] == ["stopped for 20 minutes"] and ev.get("STAGE_ABORT", (0, None))[1] == ["moved away from the start line"],
          "%s %s" % (ev.get("STAGE_DNF"), ev.get("STAGE_ABORT")))
    check("R5 STAGE_FINISH and MARK_* keep their numbers",
          abs(ev["STAGE_FINISH"][1][0] - 240.123) < 1e-9 and ev["MARK_FINISH"][1] == [3.56, 101.67, 4.0] and ev["MARK_START"][1][2] == 3.5,
          "%s %s" % (ev["STAGE_FINISH"], ev["MARK_FINISH"]))
    check("R6 event times are in seconds", abs(ev["STAGE_START"][0] - 60.0) < 1e-9 and abs(ev["STAGE_FINISH"][0] - 300.123456) < 1e-9,
          "%s %s" % (ev["STAGE_START"][0], ev["STAGE_FINISH"][0]))
    check("R7 the sensor and GPS rows around them are all there", len(D["A"]) == 1 and len(D["G"]) == 1 and len(D["L"]) == 1 and len(D["S"]) == 1 and len(D["K"]) == 1)

    # 2. things the reader must still refuse or ignore
    D = read(BASE + ["E,5000000,CAL,1,2,not_a_number,0.1,0,0,0,1", "E,6000000,SOMETHING_NEW,1,2,3", "X,1,2,3", "A,2,3", "E,7000000"])
    names = [n for (_, n, _) in D["E"]]
    check("R8 a numeric event with a word where a number belongs (CAL) is still dropped", "CAL" not in names, "names=%s" % names)
    check("R9 an event the reader does not know is kept, with its numbers",
          "SOMETHING_NEW" in names and [v for (_, n, v) in D["E"] if n == "SOMETHING_NEW"][0] == [1.0, 2.0, 3.0], str(names))
    check("R10 the bad CAL, an unknown row kind, an A row with too few fields and a bare E row are the four rows counted as bad", D["bad"] == 4, "bad=%d" % D["bad"])

    # 3. line ends and a cut-off last line
    D = read(BASE + STAGE_EVENTS, newline="\r\n", tail="\r\nL,600000000,3.55")
    check("R11 CRLF line ends, and a last line cut off in the middle of a GPS row: all 8 events are read and the cut row is the one bad row",
          len(D["E"]) == 8 and D["bad"] == 1 and len(D["L"]) == 1, "events=%d bad=%d gps=%d" % (len(D["E"]), D["bad"], len(D["L"])))

    ok = all(RESULTS)
    print("\n%d PASS, %d FAIL" % (sum(RESULTS), len(RESULTS) - sum(RESULTS)))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

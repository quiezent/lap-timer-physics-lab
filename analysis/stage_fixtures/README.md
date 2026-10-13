# Test files for the stage method

Inputs and expected results for anything that implements [`docs/stage-algorithm.md`](../../docs/stage-algorithm.md): the Python tools in this folder's parent, an iPhone app, a spreadsheet. **The expected results were made by the Android app's own Java code**
(`android/tests/test/app/tracklog/StageFixtures.java`) from the inputs below; the Android tests regenerate them from the inputs and fail if the committed files differ (`ST.FX1`), so they cannot drift away from the app unnoticed.
[`../stage_compare.py`](../stage_compare.py) `selftest` reads the inputs with the Python implementation, which was written from the method page alone, and checks that it reproduces every expected result.

All recordings are **reduced session files**: the header, `L` (GPS) rows and the events only, no sensor rows. They are synthetic (roads made of straights and arcs with a driver of known speed and 1 Hz GPS with correlated noise, fixed random seeds)
except for the real-log part, which uses the Sepang recording in `data/`.

| Path | What it is | Expected results |
|---|---|---|
| `road1/marking.csv` | A marking drive on a 4.7 km mountain road: parked, `MARK_START`, drive, parked, `MARK_FINISH` | `road1/stage.txt` (the stage built from the two marks and the fixes between them) |
| `road1/session_*.csv` | Five recordings of that road: a rolling run then a standing start and braking stop; a false start (cross the line, stop, back up, go again) with stretches of no speed or course, some bad accuracies, one row twice and two rows swapped; a run with a 12 s GPS hole then a run cut short by the end of the recording; a faster and a slower rolling run | `road1/expected/run_<id>.txt` for every run found in them (run ids as in `docs/stage-format.md`), and `road1/compare_expected.txt` (every ordered pair of finished runs: totals, sections, the uncertainty band) |
| `road2/` | The same for a hillside road with hairpin legs 30 m apart; one run has no speed or course in any fix | the same kinds of file |
| `refusals/*.csv` | Four marking drives that cannot make a stage | `refusals/expected.txt`: for each file, why (`no_movement`, `too_few_fixes`, `route_too_short`, `loop`) |
| `gates.csv` | 400 cases for the line-crossing step alone: a frame origin, a line, two GPS fixes, the speed and direction limits | the last column of each row: the crossing time in seconds, or `none` |
| `run_ids.csv` | 35 session file names, tab separated, with the odd ones the recorder never writes (spaces, accents, an emoji, a lone surrogate, names over the length limit, `.CSV`, a folder part); every UTF-16 code unit outside printable ASCII, and the backslash, is written `\uXXXX` | the last column: the id of the third run found in a file of that name (`docs/stage-algorithm.md` section 6, "Run ids") |
| `real/marks.txt` | Which two fixes of the real Sepang recording (`data/sepang-2026-10-13/...csv`) are the marks of a pseudo-stage cut out of lap 3 | `real/stage.txt`, `real/expected/run_*.txt` (16 runs: one per pass of the lap line) |

**Comparing results.** A number in an expected file is printed with a fixed number of decimals; accept your own value if it differs from the printed one by no more than half a unit of the last printed place
(plus 1e-9): a value that sits on a rounding boundary may print either way. `gates.csv` is full precision: agree to 1e-6 s. `compare_expected.txt` is computed from the run files **as stored** (the profile with 3 decimals), not from unrounded results.
Text (ids, names, status words, magic lines) must be identical.

**Regenerating** (only after a deliberate change to the method, which must also change `docs/stage-algorithm.md`):

```
JAVA_EXTRA="-Dreal.log=data/sepang-2026-10-13/session_20261013_095638.csv -Dfixtures.out=analysis/stage_fixtures" bash android/tests/run.sh fixtures
```

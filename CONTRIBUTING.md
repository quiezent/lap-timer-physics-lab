# Contributing

This project started as one conversation between a driver and me (I'm Claude, an AI model made by Anthropic) and it is meant to keep growing:
an iPhone recorder, more circuits, better analysis, and the stages that now exist on Android. If you want to add to it, this page is how to do that without breaking what is already here.
The big picture is in the [README](README.md); what I'd build next, in order, is in [`docs/roadmap.md`](docs/roadmap.md).

## Before you send anything

- **Licence.** None has been chosen yet; that is the repository owner's decision, not mine, and I'd like it settled before outside code arrives so you know the terms you are contributing under.
  If you want to contribute now, please open an issue first and say what you plan to do. Third-party data and tools are listed in [`NOTICE.md`](NOTICE.md) and their terms stay with them.
- **Safety.** The app is for a phone that is mounted and left alone while the car moves. Nothing here should ask a driver to look at or touch a phone on the move. Stages (release 1.4) are used on open roads, so while a stage is armed, recording or being marked and the car is not known to be stopped, **no screen may show a time, a difference or a comparison**
  (`StageLive.numbersAllowed()` is the one place that decides, and `StageUiTests` fail if a number gets through); the only live feedback is the beeps; and nothing may prompt a driver to go faster. A live display for a closed road or a track is a separate feature with its own switch, not a change to this rule.
- **Privacy.** A GPS trace shows where someone lives if it starts or ends at their home. Only share logs from circuits and closed events; cut the first and last minutes if they pass a house; look at the file before you post it.
  The log format holds the device model and nothing else about a person (see [`docs/log-format.md`](docs/log-format.md)).
- **Never commit the signing key** (`*.jks`, `*.keystore`, `*.p12`). `.gitignore` blocks them; please don't force it.

## Where help is most useful

1. **An iPhone recorder** in [`ios/`](ios). It has to write the same CSV the Android app writes, so every script here works on it unchanged. The contract is [`docs/log-format.md`](docs/log-format.md) and the test is `python analysis/check_log.py your_session.csv`.
   The first real iPhone file is worth more than any amount of code: the checks in `check_log.py` came from my guesses about what goes wrong.
2. **Stages** (built in 1.4, see [`docs/stages.md`](docs/stages.md)): a real-road test against a stopwatch or a second device, splits, the point-to-point power-law analysis, an iPhone implementation that matches `docs/stage-algorithm.md` and the files in `analysis/stage_fixtures`. What is open is listed in [`docs/roadmap.md`](docs/roadmap.md) section 2.
3. **More circuits**: a course file for each one and a script that builds the circuit data from the TUMFTM racetrack database.
4. **Data**: sessions from other drivers, cars, phones and circuits (see the end of the roadmap for what I'd most like), with a short README beside the file like [`data/sepang-2026-10-13/README.md`](data/sepang-2026-10-13/README.md).
5. **Tests**: the analysis has a regression check on the real log, a test of the reader (`analysis/test_reader.py`) and, for stages, a second implementation checked against shared fixtures, but no unit tests of the engine itself, and the powerlaws layer in particular could use some. The checks below also run on every push and pull request through `.github/workflows/checks.yml` (all three jobs pass on GitHub's runners as of commit 8cbd8ea, 13 Oct 2026); if the workflow itself breaks later, a fix to that file is a welcome pull request.
6. **Corrections**. If a number in a document is wrong, or something I said does not hold up, that is the most valuable thing of all. Say so in an issue with what you ran.

## What every change has to pass

```
# the Android recorder and lap timer (needs only a JDK, 8 or newer; no Android SDK)
JAVA_EXTRA=-Dreal.log=data/sepang-2026-10-13/session_20261013_095638.csv bash android/tests/run.sh all
                                                 # 324 required checks, all must pass (315 if you leave out the real-log replay)

# any recorder (Android, iPhone, anything else) against the format contract
python analysis/check_log.py path/to/session.csv  # no FAIL lines (a WARN is explained in the output)

# the analysis, on the committed real log (about 40 seconds)
pip install -r requirements.txt
python analysis/regression_check.py              # re-runs ultimate.py and powerlaws.py and compares every output with reports/sepang-2026-10-13/
python analysis/test_reader.py                   # the reader takes every kind of row the app writes (11 checks, under a second)

# the stage method, written a second time from docs/stage-algorithm.md (about 10 seconds)
python analysis/stage_compare.py selftest        # reproduces every expected result in analysis/stage_fixtures, byte for byte for the files
python sim/simulate_stage.py --quick             # the stage simulation, a tenth of the runs
```

`regression_check.py` prints PASS or FAIL for each output file and exits with 1 if anything moved. The runs are deterministic, so on the same library versions and the same kind of machine everything is identical; on other versions or another CPU a number can move in its last bits or last printed digit, which the check tolerates (`--strict` does not).
A result that moves more than that (the ultimate lap 2:31.78 ± 0.07, the cornering exponent 0.50, the braking R² 0.973 are the headline ones) means something changed in the analysis, and a change like that needs to be explained in the pull request, not just committed.
If you change the analysis on purpose, re-run both scripts into [`reports/sepang-2026-10-13/`](reports/sepang-2026-10-13/) (the commands are in `docs/analysis-manual.md` and at the end of the report), commit the regenerated files in the same pull request, and say which numbers moved and why.
The check catches changes, not mistakes: it cannot tell a better analysis from a worse one, only a different one.

For the simulator (it has the exact truth, so it is the strongest test of the engine): `bash sim/run_batch.sh 8`, described in [`sim/README.md`](sim/README.md). It takes about a minute per session.

## Style, in short

- **Java.** Plain Android SDK, no libraries, no Gradle (`android/build.sh` is the whole build). Java 8 language level, because that is what the build targets. Anything that can be tested without a phone goes in a class with no Android imports, like `LapTimer.java`, with a test in `android/tests`.
  The recorder never filters, rotates or calibrates: judgement belongs to the analysis, where it can be re-run.
- **Python.** numpy, scipy and matplotlib only. A script starts with a docstring that says what it does and how to run it, takes its input and output paths as arguments, is deterministic, and prints what it did.
- **Numbers.** A number in a document comes from code or a table in this repository, with its interval where it has one, and the sentence says how much was tested ("ran on one session" is a fact; "works" is not).
  If a result can't be trusted, show it as blank and say why instead of showing a guess.
- **Documents.** Plain words. A claim and the test that supports it should be findable from each other (see [`docs/validation.md`](docs/validation.md)).

## A pull request should say

What changed and why; what you ran (the commands above, or the part that applies) and what it printed; what you did **not** test, plainly; and, for a new recorder, the first `check_log.py` result on a real drive.

Thank you for helping a driver find another tenth.

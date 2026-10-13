# Contributing

This project started as one conversation between a driver and me (I'm Claude, an AI model made by Anthropic) and it is meant to keep growing:
an iPhone recorder, stage timing with start and finish GPS points, more circuits, better analysis. If you want to add to it, this page is how to do that without breaking what is already here.
The big picture is in the [README](README.md); what I'd build next, in order, is in [`docs/roadmap.md`](docs/roadmap.md).

## Before you send anything

- **Licence.** None has been chosen yet; that is the repository owner's decision, not mine, and I'd like it settled before outside code arrives so you know the terms you are contributing under.
  If you want to contribute now, please open an issue first and say what you plan to do. Third-party data and tools are listed in [`NOTICE.md`](NOTICE.md) and their terms stay with them.
- **Safety.** The app is for a phone that is mounted and left alone while the car moves. Nothing here should ask a driver to look at or touch a phone on the move, and stage timing is for closed roads and permitted events, not for public roads.
- **Privacy.** A GPS trace shows where someone lives if it starts or ends at their home. Only share logs from circuits and closed events; cut the first and last minutes if they pass a house; look at the file before you post it.
  The log format holds the device model and nothing else about a person (see [`docs/log-format.md`](docs/log-format.md)).
- **Never commit the signing key** (`*.jks`, `*.keystore`, `*.p12`). `.gitignore` blocks them; please don't force it.

## Where help is most useful

1. **An iPhone recorder** in [`ios/`](ios). It has to write the same CSV the Android app writes, so every script here works on it unchanged. The contract is [`docs/log-format.md`](docs/log-format.md) and the test is `python analysis/check_log.py your_session.csv`.
   The first real iPhone file is worth more than any amount of code: the checks in `check_log.py` came from my guesses about what goes wrong.
2. **Stage timing**: start and finish gates given as GPS points, splits, a stage screen, and a point-to-point mode in the analysis. The design, with its tests, is in [`docs/roadmap.md`](docs/roadmap.md) section 2.
3. **More circuits**: a course file for each one and a script that builds the circuit data from the TUMFTM racetrack database.
4. **Data**: sessions from other drivers, cars, phones and circuits (see the end of the roadmap for what I'd most like), with a short README beside the file like [`data/sepang-2026-10-13/README.md`](data/sepang-2026-10-13/README.md).
5. **Tests**: the analysis has no automated regression test yet; CI that runs the checks below on every pull request does not exist yet.
6. **Corrections**. If a number in a document is wrong, or something I said does not hold up, that is the most valuable thing of all. Say so in an issue with what you ran.

## What every change has to pass

```
# the Android recorder and lap timer (needs only a JDK, 8 or newer; no Android SDK)
bash android/tests/run.sh all                    # 140 required checks; all must pass

# any recorder (Android, iPhone, anything else) against the format contract
python analysis/check_log.py path/to/session.csv  # no FAIL lines (a WARN is explained in the output)

# the analysis, on the committed real log
pip install -r requirements.txt
python analysis/ultimate.py  data/sepang-2026-10-13/session_20261013_095638.csv --out /tmp/ultimate
python analysis/powerlaws.py data/sepang-2026-10-13/session_20261013_095638.csv --out /tmp/powerlaws
```

After the last two, compare with what is committed in [`reports/sepang-2026-10-13/`](reports/sepang-2026-10-13/) (`numbers.json` and the tables).
The run is deterministic, so on the same library versions the numbers should be identical; on other versions a last digit may move.
A headline number that moves more than that (the ultimate lap 2:31.78 ± 0.07, the cornering exponent 0.50, the braking R² 0.973) means something changed in the analysis, and a change like that needs to be explained in the pull request, not just committed.
If you change the analysis on purpose, re-run it on the committed log, commit the regenerated `reports/` files in the same pull request, and say which numbers moved and why.

For the simulator (it has the exact truth, so it is the strongest test of the engine): `bash sim/run_batch.sh 8`, described in [`sim/README.md`](sim/README.md). It takes about 50 seconds per session.

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

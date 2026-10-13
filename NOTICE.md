# Third-party material

This repository builds on open data and tools. I list them here so that anyone reusing the project knows what came from where.
I am an AI model and not a lawyer: if you plan to redistribute this project, read the licences below yourself.

## Circuit geometry (data, used in `analysis/sepang_model.npz` and `analysis/sepang_model.json`)

| What | Source | Licence |
|---|---|---|
| Sepang centreline and track widths | [TUMFTM/racetrack-database](https://github.com/TUMFTM/racetrack-database), `tracks/Sepang.csv` | LGPL-3.0 |
| Georeference polyline (to place the centreline on a map) | [bacinger/f1-circuits](https://github.com/bacinger/f1-circuits), `circuits/my-1999.geojson` | MIT |
| Turn numbering and direction sequence | published lists (MotoGP.com spec list, Wikipedia, Driver61); facts, not copied text | n/a |

`sepang_model.npz` and `sepang_model.json` are derived from the first two: I resampled the centreline, fitted a racing line to it and
added corner positions, a simple physics prior and the turn numbers. They are therefore a derived work of an LGPL-3.0 data set, and I
treat them as carrying the same licence. The full list of sources is also inside `sepang_model.json` under `"sources"`.

The track data are not a survey. The corner positions can be tens of metres off, which is why the analysis moves each corner's reference
apex to where the driver's own laps run slowest.

## Build tools (downloaded by `android/fetch_tools.sh`, not stored here)

| Tool | Used for | Licence |
|---|---|---|
| Android 13 (API 33) stub `android.jar` from [Sable/android-platforms](https://github.com/Sable/android-platforms) | compile-time only | Apache-2.0 (Android Open Source Project) |
| [Apktool](https://github.com/iBotPeaches/Apktool) 2.9.3 (its bundled `aapt2`) | packaging resources | Apache-2.0 |
| [jadx](https://github.com/skylot/jadx) 1.5.3 (its bundled R8/D8) | Java bytecode to dex | Apache-2.0 |
| [uber-apk-signer](https://github.com/patrickfav/uber-apk-signer) 1.3.0 | zipalign and signing | Apache-2.0 |

## Python packages (installed with pip, not stored here)

numpy, scipy and matplotlib (BSD-style licences).

## The recording

`data/sepang-2026-10-13/session_20261013_095638.csv` is a real recording from the driver's own phone, published by the driver. It holds no name,
account or identifier beyond the phone model (see `data/sepang-2026-10-13/README.md`).

## This project's own licence

None has been chosen yet; see the "Licence" section of the top-level `README.md`.

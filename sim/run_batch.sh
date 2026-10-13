#!/usr/bin/env bash
# Simulate a batch of fresh Sepang sessions and judge the ultimate-lap engine against the truth the simulator knows.
#
#   sim/run_batch.sh [N]          N sessions (default 6), written to $SIM_OUT (default sim/out, which is git-ignored)
#
# Each session differs in seed, number of flying laps and GPS noise; every one has driver mistakes planted at random.
# The summary table at the end is what docs/validation.md quotes.  About 50 s per session on a laptop.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${SIM_OUT:-$HERE/out}"
N="${1:-6}"
mkdir -p "$OUT"
LAPS=(8 6 10 8 7 9 8 10 6 8 9 7)
SIGMA=(2.0 3.0 2.0 4.0 3.0 2.0 4.0 3.0 2.0 3.0 4.0 2.0)
for i in $(seq 1 "$N"); do
  seed=$((300 + i)); laps=${LAPS[$(( (i-1) % ${#LAPS[@]} ))]}; sig=${SIGMA[$(( (i-1) % ${#SIGMA[@]} ))]}
  python3 -B "$HERE/simulate_sepang.py" --seed "$seed" --laps "$laps" --gps-sigma "$sig" --out "$OUT/b$seed.csv" > /dev/null
  python3 -B "$HERE/run_val.py" "B$seed" "$OUT/b$seed.csv" > "$OUT/b$seed.log" 2>&1
  echo "simulated and judged b$seed: $laps flying laps, GPS sigma $sig m"
done
python3 -B "$HERE/summarise_batch.py" "$OUT"/result_B*.json

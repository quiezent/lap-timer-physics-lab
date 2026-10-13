#!/usr/bin/env bash
# Builds fakes + test code + the REAL Recorder.java, RecorderService.java, Exporter.java and runs the harness.
#   run.sh [all|s1|s2|s3|p1|p2] [srcdir | path/to/Recorder.java]
# Default sources: ../src/app/tracklog (compiled in place, never copied or modified).
# If the 2nd argument is a directory it must contain Recorder.java, RecorderService.java, Exporter.java and LapTimer.java;
# if it is a file it replaces Recorder.java only.
# Needs only a JDK (javac and java, version 8 or newer); no Android SDK: the tests run against fakes of the Android classes in ./fakes.
set -euo pipefail
H="$(cd "$(dirname "$0")" && pwd)"
MODE="${1:-all}"
SRC="$H/../src/app/tracklog"
REC="$SRC/Recorder.java"
if [ -n "${2:-}" ]; then
  if [ -d "$2" ]; then SRC="$2"; REC="$SRC/Recorder.java"; else REC="$2"; fi
fi
CLS="${CLS_DIR:-$H/out/classes}"
mkdir -p "$CLS"
echo "sources: Recorder=$REC  RecorderService/Exporter from $SRC"
md5sum "$REC" "$SRC/RecorderService.java" "$SRC/Exporter.java" | sed 's/^/  md5 /'
javac -encoding UTF-8 -Xlint:all -d "$CLS" $(find "$H/fakes" "$H/test" -name '*.java') "$REC" "$SRC/RecorderService.java" "$SRC/Exporter.java" "$SRC/LapTimer.java"
exec java ${JAVA_EXTRA:-} -cp "$CLS" app.tracklog.Harness "$MODE" "$H/out"

#!/usr/bin/env bash
# Builds fakes + test code + the REAL Recorder.java, RecorderService.java, Exporter.java, the lap timer and the stage classes and runs the harness.
#   run.sh [all|s1|s2|s3|p1|p2|l1|st|fixtures] [srcdir | path/to/Recorder.java]
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
# Everything in the app's package: the recorder, the service, the plain-Java classes behind them (the lap timer and the stage code) and
# the two screens, which run here against fakes of the few Android widgets they use (tests/fakes/android/view, widget, app).
PURE=$(find "$SRC" -maxdepth 1 -name '*.java' ! -name Recorder.java | sort)
javac -encoding UTF-8 -Xlint:all -d "$CLS" $(find "$H/fakes" "$H/test" -name '*.java') "$REC" $PURE
# The shared test files for other implementations of the stage method (analysis/stage_fixtures) are checked when the folder is there.
FX="$H/../../analysis/stage_fixtures"
FXOPT=""
if [ -d "$FX" ]; then FXOPT="-Dfixtures.dir=$(cd "$FX" && pwd)"; fi
#   run.sh fixtures      writes new stage fixtures to $FIXTURES_OUT (default: out/stage_fixtures); pass -Dreal.log=... in JAVA_EXTRA to include the real-log part
exec java ${JAVA_EXTRA:-} $FXOPT -cp "$CLS" app.tracklog.Harness "$MODE" "$H/out"

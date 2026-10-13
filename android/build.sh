#!/usr/bin/env bash
# Builds the Track Logger APK without Gradle or Android Studio.
#
#   TOOLS=/path/to/tools ./build.sh
#
# TOOLS must contain (all downloaded from GitHub releases / raw GitHub):
#   sdkjar/android33.jar                  Android 13 (API 33) stub jar, compile-time only
#   aapt2/aapt2                           Linux aapt2, extracted from the Apktool jar
#   jadx/x/lib/jadx-1.5.3-all.jar         contains R8/D8 (the Java-bytecode to dex converter)
#   signer/uber-apk-signer.jar            zipalign + APK signature schemes v1/v2/v3
# Needs JDK 11+ (javac, keytool) and zip.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
TOOLS="${TOOLS:?set TOOLS to the tools directory (see header)}"
ANDROID_JAR="$TOOLS/sdkjar/android33.jar"
AAPT2="$TOOLS/aapt2/aapt2"
D8CP="$TOOLS/jadx/x/lib/jadx-1.5.3-all.jar"
SIGNER="$TOOLS/signer/uber-apk-signer.jar"
OUT="$HERE/build"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/compiled" "$OUT/dex"

echo "[1/5] javac (Java 8 bytecode, no lambdas needed)"
javac --release 8 -encoding UTF-8 -Xlint:all -cp "$ANDROID_JAR" -d "$OUT/classes" $(find "$HERE/src" -name '*.java')

echo "[2/5] D8: class files -> classes.dex (min API 29)"
java -cp "$D8CP" com.android.tools.r8.D8 --release --min-api 29 --lib "$ANDROID_JAR" \
     --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "[3/5] aapt2: manifest + resources"
"$AAPT2" compile --dir "$HERE/res" -o "$OUT/compiled/res.zip"
"$AAPT2" link -o "$OUT/base.apk" -I "$ANDROID_JAR" --manifest "$HERE/AndroidManifest.xml" \
     --min-sdk-version 29 --target-sdk-version 33 --version-code 5 --version-name 1.4 "$OUT/compiled/res.zip"

echo "[4/5] add classes.dex"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -j "$OUT/unsigned.apk" classes.dex)

echo "[5/5] zipalign + sign"
if [ ! -f "$HERE/tracklogger.jks" ]; then
  keytool -genkeypair -keystore "$HERE/tracklogger.jks" -storepass tracklog -keypass tracklog \
          -alias tracklog -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Track Logger" >/dev/null 2>&1
fi
java -jar "$SIGNER" -a "$OUT/unsigned.apk" --ks "$HERE/tracklogger.jks" --ksAlias tracklog \
     --ksPass tracklog --ksKeyPass tracklog -o "$OUT/signed"
ls -la "$OUT/signed"

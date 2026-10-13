#!/usr/bin/env bash
# Downloads the build tools used by build.sh into ./tools (about 170 MB, all from GitHub).
#   ./fetch_tools.sh && TOOLS="$PWD/tools" ./build.sh
set -euo pipefail
T="${1:-$PWD/tools}"
mkdir -p "$T/sdkjar" "$T/apktool" "$T/signer" "$T/jadx/x" "$T/aapt2"

# Android 13 (API 33) stub jar, compile-time only
curl -fL -o "$T/sdkjar/android33.jar" \
  "https://raw.githubusercontent.com/Sable/android-platforms/master/android-33/android.jar"

# aapt2 (Linux x86-64) is bundled inside the Apktool jar
curl -fL -o "$T/apktool/apktool.jar" \
  "https://github.com/iBotPeaches/Apktool/releases/download/v2.9.3/apktool_2.9.3.jar"
unzip -p "$T/apktool/apktool.jar" prebuilt/linux/aapt2_64 > "$T/aapt2/aapt2"
chmod +x "$T/aapt2/aapt2"

# R8/D8 (Java bytecode -> dex) is bundled inside the jadx fat jar
curl -fL -o "$T/jadx/jadx.zip" \
  "https://github.com/skylot/jadx/releases/download/v1.5.3/jadx-1.5.3.zip"
(cd "$T/jadx/x" && unzip -q -o ../jadx.zip 'lib/*')

# zipalign + APK signing
curl -fL -o "$T/signer/uber-apk-signer.jar" \
  "https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar"

echo "tools ready in $T"

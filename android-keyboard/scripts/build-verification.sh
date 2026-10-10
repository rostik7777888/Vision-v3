#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-$ROOT/../.hoplite/android-tools/sdk}"
export JAVA_HOME="${JAVA_HOME:-$(mise where java@temurin-17)}"
export PATH="$JAVA_HOME/bin:$PATH"
TOOLS="$SDK/build-tools/35.0.0"; ANDROID="$SDK/platforms/android-35/android.jar"; OUT="$ROOT/build/verification"
mkdir -p "$OUT/classes" "$OUT/dex"
"$TOOLS/aapt2" link -o "$OUT/base.apk" -I "$ANDROID" --manifest "$ROOT/tests/android/AndroidManifest.xml"
javac -source 8 -target 8 -encoding UTF-8 -classpath "$ANDROID" -d "$OUT/classes" "$ROOT/tests/android/src/com/trost/keyboard/verification/VerificationRunner.java"
jar cf "$OUT/classes.jar" -C "$OUT/classes" .
"$TOOLS/d8" --min-api 24 --lib "$ANDROID" --output "$OUT/dex" "$OUT/classes.jar"
cp "$OUT/base.apk" "$OUT/unsigned.apk"; (cd "$OUT/dex" && zip -q -j "$OUT/unsigned.apk" classes*.dex)
"$TOOLS/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$TOOLS/apksigner" sign --ks "$ROOT/../.hoplite/android-tools/trost-development.jks" --ks-key-alias trost-dev --ks-pass pass:android --key-pass pass:android --out "$OUT/verification.apk" "$OUT/aligned.apk"

#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-$ROOT/../.hoplite/android-tools/sdk}"
TOOLS="$SDK/build-tools/35.0.0"
export JAVA_HOME="${JAVA_HOME:-$(mise where java@temurin-17)}"
export PATH="$JAVA_HOME/bin:$PATH"
APKTOOL="$ROOT/../.hoplite/android-tools/apktool.jar"
OUT="$ROOT/build"
rm -rf "$OUT/base" "$OUT/stubs" "$OUT/classes" "$OUT/dex" "$OUT/overlay" "$OUT/overlay.zip"
mkdir -p "$OUT/stubs" "$OUT/classes" "$OUT/dex"
java -jar "$APKTOOL" d -f "$ROOT/vendor/trost-keyboard-1.8.apk" -o "$OUT/base" > "$OUT/decode.log"
find "$ROOT/stubs" -name '*.java' > "$OUT/stubs.txt"
javac -encoding UTF-8 -source 8 -target 8 -classpath "$SDK/platforms/android-35/android.jar" -d "$OUT/stubs" @"$OUT/stubs.txt"
find "$ROOT/src" -name '*.java' > "$OUT/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -classpath "$SDK/platforms/android-35/android.jar:$OUT/stubs" -d "$OUT/classes" @"$OUT/sources.txt"
jar cf "$OUT/classes.jar" -C "$OUT/classes" .
jar cf "$OUT/stubs.jar" -C "$OUT/stubs" .
"$TOOLS/d8" --min-api 24 --lib "$SDK/platforms/android-35/android.jar" --classpath "$OUT/stubs.jar" --output "$OUT/dex" "$OUT/classes.jar"
(cd "$OUT/dex" && zip -q "$OUT/overlay.zip" classes.dex)
java -jar "$APKTOOL" d -f --no-res "$OUT/overlay.zip" -o "$OUT/overlay" > "$OUT/overlay.log"
python3 "$ROOT/scripts/patch-apk.py" "$OUT/base" "$OUT/overlay" "$ROOT"
java -jar "$APKTOOL" b "$OUT/base" -o "$OUT/unsigned.apk" > "$OUT/assemble.log"
"$TOOLS/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
KEY="${TROST_KEYSTORE:-$ROOT/../.hoplite/android-tools/trost-development.jks}"
if [[ ! -f "$KEY" ]]; then
    keytool -genkeypair -keystore "$KEY" -storepass android -keypass android -alias trost-dev -keyalg RSA -keysize 2048 -validity 3650 -dname 'CN=trost development' -noprompt
fi
"$TOOLS/apksigner" sign --ks "$KEY" --ks-key-alias trost-dev --ks-pass pass:android --key-pass pass:android --out "$OUT/fishka-typing-1.9.3.apk" "$OUT/aligned.apk"
"$TOOLS/apksigner" verify "$OUT/fishka-typing-1.9.3.apk"
echo "$OUT/fishka-typing-1.9.3.apk"

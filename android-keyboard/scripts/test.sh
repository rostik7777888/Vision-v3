#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/.."
export JAVA_HOME="${JAVA_HOME:-$(mise where java@temurin-17)}"
export PATH="$JAVA_HOME/bin:$PATH"
rm -rf "$ROOT/build/tests"
mkdir -p "$ROOT/build/tests"
SDK="${ANDROID_HOME:-$ROOT/../.hoplite/android-tools/sdk}"
find "$ROOT/tests/com" -name '*Test.java' -print > "$ROOT/build/test-sources.txt"
javac -encoding UTF-8 -classpath "$SDK/platforms/android-35/android.jar" -sourcepath "$ROOT/src:$ROOT/tests:$ROOT/stubs" -d "$ROOT/build/tests" @"$ROOT/build/test-sources.txt"
python3 - "$ROOT/vendor/trost-keyboard-1.8.apk" "$ROOT/build/test-assets" <<'PY_ASSETS'
import sys,zipfile,pathlib
out=pathlib.Path(sys.argv[2]);out.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(sys.argv[1]) as source:
    for lang in ['en','ru']:
        name=lang+'-freq.tsv';(out/name).write_bytes(source.read('assets/'+name))
PY_ASSETS
while IFS= read -r path; do
    class="${path#"$ROOT/tests/"}"; class="${class%.java}"; class="${class//\//.}"
    if [[ "$class" == com.trost.ioskeyboard.PredictionModelTest ]]; then
        java -ea -cp "$ROOT/build/tests:$SDK/platforms/android-35/android.jar" "$class" "$ROOT/build/test-assets"
    else
        java -ea -cp "$ROOT/build/tests:$SDK/platforms/android-35/android.jar" "$class"
    fi
    echo "$class: passed"
done < "$ROOT/build/test-sources.txt"
python3 "$ROOT/tests/test_audio.py"
python3 -m py_compile "$ROOT/scripts/patch-apk.py"
bash -n "$ROOT/scripts/"*.sh
echo 'History, adaptive T9, MP3 provenance and script checks passed'

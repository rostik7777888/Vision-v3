#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/.."
export JAVA_HOME="${JAVA_HOME:-$(mise where java@temurin-17)}"
export PATH="$JAVA_HOME/bin:$PATH"
mkdir -p "$ROOT/build/tests"
javac -encoding UTF-8 -d "$ROOT/build/tests" "$ROOT/src/com/trost/ioskeyboard/ClipboardHistory.java" "$ROOT/src/com/trost/ioskeyboard/PredictionModel.java" "$ROOT/tests/com/trost/ioskeyboard/ClipboardHistoryTest.java" "$ROOT/tests/com/trost/ioskeyboard/PredictionModelTest.java"
java -ea -cp "$ROOT/build/tests" com.trost.ioskeyboard.ClipboardHistoryTest
java -ea -cp "$ROOT/build/tests" com.trost.ioskeyboard.PredictionModelTest
python3 "$ROOT/tests/test_audio.py"
python3 -m py_compile "$ROOT/scripts/patch-apk.py"
bash -n "$ROOT/scripts/"*.sh
echo 'History, adaptive T9, MP3 provenance and script checks passed'

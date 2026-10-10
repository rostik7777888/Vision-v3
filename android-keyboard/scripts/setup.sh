#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TOOLS_DIR="$ROOT_DIR/.hoplite/android-tools"
SDK_ROOT="$TOOLS_DIR/sdk"
JAVA_PIN_FILE="$ROOT_DIR/.java-version"
SDK_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip"

die() {
  printf 'setup: %s\n' "$*" >&2
  exit 1
}

[[ "$(uname -s)" == Linux && "$(uname -m)" == x86_64 ]] || die 'this setup currently supports Linux x86_64 only'
[[ -f "$JAVA_PIN_FILE" ]] || die "missing Java pin: $JAVA_PIN_FILE"
JAVA_PIN="$(tr -d '[:space:]' < "$JAVA_PIN_FILE")"
[[ "$JAVA_PIN" == 17 ]] || die "expected JDK 17 pin, got: $JAVA_PIN"
JAVA_PIN="temurin-17"
mkdir -p "$TOOLS_DIR" "$SDK_ROOT"
command -v flock >/dev/null 2>&1 || die 'flock is required to serialize SDK setup'
exec 9>"$TOOLS_DIR/.setup.lock"
flock -x 9

if command -v mise >/dev/null 2>&1; then
  JAVA_HOME="$(mise where "java@$JAVA_PIN" 2>/dev/null || true)"
  if [[ -z "$JAVA_HOME" ]]; then
    mise install "java@$JAVA_PIN"
    JAVA_HOME="$(mise where "java@$JAVA_PIN")"
  fi
elif [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
  :
else
  die 'mise or JAVA_HOME with JDK 17 is required'
fi

JAVA_VERSION="$("$JAVA_HOME/bin/java" -version 2>&1 | sed -n '1s/.*version "\([^"]*\)".*/\1/p')"
[[ "$JAVA_VERSION" == 17* ]] || die "JDK 17 required; found ${JAVA_VERSION:-unknown} at $JAVA_HOME"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"

download() {
  local url="$1" destination="$2" temporary="${2}.part"
  curl --fail --location --retry 3 --retry-all-errors --connect-timeout 30 --max-time 600 "$url" -o "$temporary"
  mv -f "$temporary" "$destination"
}

APKTOOL="$TOOLS_DIR/apktool.jar"
if [[ ! -s "$APKTOOL" ]]; then
  download 'https://github.com/iBotPeaches/Apktool/releases/download/v2.12.1/apktool_2.12.1.jar' "$APKTOOL"
fi
APKTOOL_VERSION="$("$JAVA_HOME/bin/java" -jar "$APKTOOL" --version 2>&1 | tail -n 1)"
[[ "$APKTOOL_VERSION" == 2.12.1 ]] || die "expected apktool 2.12.1, found $APKTOOL_VERSION"

JADX_DIR="$TOOLS_DIR/jadx"
JADX_JAR="$JADX_DIR/lib/jadx-1.5.6-all.jar"
if [[ ! -s "$JADX_JAR" ]]; then
  JADX_ARCHIVE="$TOOLS_DIR/jadx.zip"
  if [[ ! -s "$JADX_ARCHIVE" ]]; then
    download 'https://github.com/skylot/jadx/releases/download/v1.5.6/jadx-1.5.6.zip' "$JADX_ARCHIVE"
  fi
  EXTRACT_DIR="$(mktemp -d "$TOOLS_DIR/.jadx.XXXXXX")"
  unzip -q "$JADX_ARCHIVE" -d "$EXTRACT_DIR"
  [[ -s "$EXTRACT_DIR/lib/jadx-1.5.6-all.jar" ]] || die 'jadx archive does not contain the pinned 1.5.6 release'
  rm -rf "$JADX_DIR"
  mv "$EXTRACT_DIR" "$JADX_DIR"
  rm -rf "$EXTRACT_DIR"
fi
JADX_VERSION="$("$JAVA_HOME/bin/java" -jar "$JADX_JAR" --version 2>&1 | tail -n 1)"
[[ "$JADX_VERSION" == 1.5.6 ]] || die "expected jadx 1.5.6, found $JADX_VERSION"

SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -x "$SDKMANAGER" ]]; then
  SDK_ARCHIVE="$TOOLS_DIR/commandlinetools-linux-16111833_latest.zip"
  [[ -s "$SDK_ARCHIVE" ]] || download "$SDK_TOOLS_URL" "$SDK_ARCHIVE"
  EXTRACT_DIR="$(mktemp -d "$TOOLS_DIR/.sdk-tools.XXXXXX")"
  unzip -q "$SDK_ARCHIVE" -d "$EXTRACT_DIR"
  [[ -x "$EXTRACT_DIR/cmdline-tools/bin/sdkmanager" ]] || die 'Android command-line tools archive is invalid'
  mkdir -p "$SDK_ROOT/cmdline-tools"
  rm -rf "$SDK_ROOT/cmdline-tools/latest"
  mv "$EXTRACT_DIR/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
  rm -rf "$EXTRACT_DIR" "$SDK_ARCHIVE"
fi

set +o pipefail
if yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses >/dev/null; then
  :
else
  set -o pipefail
  die 'could not accept Android SDK licenses'
fi
set -o pipefail

"$SDKMANAGER" --sdk_root="$SDK_ROOT" --channel=0 \
  'platform-tools' 'platforms;android-35' 'build-tools;35.0.0'

printf 'JDK: %s (%s)\n' "$JAVA_HOME" "$JAVA_VERSION"
printf 'apktool: %s (2.12.1)\n' "$APKTOOL"
printf 'jadx: %s (1.5.6)\n' "$JADX_JAR"
printf 'Android SDK: %s\n' "$SDK_ROOT"
printf 'Build packages: platform-tools, platforms;android-35, build-tools;35.0.0\n'

if [[ "${ANDROID_EMULATOR_SETUP:-0}" == 1 ]]; then
  env ANDROID_EMULATOR_SETUP=0 bash "$ROOT_DIR/android-keyboard/scripts/emulator.sh"
fi

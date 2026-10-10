#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TOOLS_DIR="$ROOT_DIR/.hoplite/android-tools"
SDK_ROOT="$TOOLS_DIR/sdk"
AVD_NAME="trost-aosp-api29-x86_64"
AVD_HOME="$TOOLS_DIR/avd"
ANDROID_USER_HOME="$TOOLS_DIR/android-home"
EVIDENCE_DIR="$TOOLS_DIR/emulator-evidence"
APK="$ROOT_DIR/.hoplite/attachments/art_upload_49416b5668e246d69c571a85057e5854/trost-keyboard-1.8.apk"
BOOT_TIMEOUT_SECONDS="${ANDROID_EMULATOR_BOOT_TIMEOUT:-360}"
DEVICE="emulator-5554"

die() {
  printf 'emulator: %s\n' "$*" >&2
  exit 1
}

mkdir -p "$TOOLS_DIR"
command -v flock >/dev/null 2>&1 || die 'flock is required to serialize emulator setup'
exec 9>"$TOOLS_DIR/.emulator.lock"
flock -x 9

if [[ ! -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" || ! -x "$SDK_ROOT/platform-tools/adb" ]]; then
  env ANDROID_EMULATOR_SETUP=0 bash "$ROOT_DIR/android-keyboard/scripts/setup.sh"
fi

JAVA_PIN="temurin-$(tr -d '[:space:]' < "$ROOT_DIR/.java-version")"
if command -v mise >/dev/null 2>&1; then
  JAVA_HOME="$(mise where "java@$JAVA_PIN")"
elif [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" ]]; then
  die 'mise or JAVA_HOME with JDK 17 is required'
fi

export JAVA_HOME
export PATH="$JAVA_HOME/bin:$SDK_ROOT/cmdline-tools/latest/bin:$SDK_ROOT/platform-tools:$PATH"
export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export ANDROID_AVD_HOME="$AVD_HOME"
export ANDROID_USER_HOME
mkdir -p "$AVD_HOME" "$ANDROID_USER_HOME" "$EVIDENCE_DIR"

SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
ADB="$SDK_ROOT/platform-tools/adb"
EMULATOR="$SDK_ROOT/emulator/emulator"
AVDMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/avdmanager"
SYSTEM_IMAGE="$SDK_ROOT/system-images/android-29/default/x86_64/source.properties"

"$SDKMANAGER" --sdk_root="$SDK_ROOT" --channel=0 'emulator' 'system-images;android-29;default;x86_64'
[[ -x "$EMULATOR" && -f "$SYSTEM_IMAGE" ]] || die 'emulator 37 and the API 29 AOSP x86_64 image were not installed'

if ! ldconfig -p 2>/dev/null | grep -q 'libxkbfile\.so\.1'; then
  LOCAL_LIBS="$TOOLS_DIR/local-libs"
  DEB="$TOOLS_DIR/libxkbfile1_1.1.0-1build4_amd64.deb"
  mkdir -p "$LOCAL_LIBS"
  if [[ ! -s "$DEB" ]]; then
    curl --fail --location --retry 3 --retry-all-errors --connect-timeout 20 --max-time 120 \
      'https://archive.ubuntu.com/ubuntu/pool/main/libx/libxkbfile/libxkbfile1_1.1.0-1build4_amd64.deb' -o "$DEB"
  fi
  dpkg-deb -x "$DEB" "$LOCAL_LIBS"
  export LD_LIBRARY_PATH="$LOCAL_LIBS/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
fi

if [[ ! -f "$AVD_HOME/$AVD_NAME.avd/config.ini" ]]; then
  printf 'no\n' | timeout 45s "$AVDMANAGER" create avd \
    --force --name "$AVD_NAME" --package 'system-images;android-29;default;x86_64' --device pixel_2
fi
CONFIG="$AVD_HOME/$AVD_NAME.avd/config.ini"
[[ -f "$CONFIG" ]] || die "AVD config was not created: $CONFIG"
python3 - "$CONFIG" <<'PY'
import sys
from pathlib import Path

path = Path(sys.argv[1])
values = {
    "hw.cpu.ncore": "2",
    "hw.ramSize": "2048",
    "hw.lcd.width": "720",
    "hw.lcd.height": "1600",
    "hw.lcd.density": "294",
    "hw.keyboard": "yes",
    "hw.gpu.enabled": "yes",
    "hw.gpu.mode": "software",
    "hw.audioInput": "no",
    "hw.audioOutput": "no",
    "hw.camera.front": "none",
    "hw.camera.back": "none",
    "disk.dataPartition.size": "4G",
    "fastboot.forceColdBoot": "yes",
    "fastboot.forceFastBoot": "no",
    "snapshot.present": "no",
    "showDeviceFrame": "no",
}
lines = [line for line in path.read_text().splitlines() if not any(line.startswith(f"{key}=") for key in values)]
lines.extend(f"{key}={value}" for key, value in values.items())
path.write_text("\n".join(lines) + "\n")
PY

if timeout 5s "$ADB" -s "$DEVICE" get-state >/dev/null 2>&1; then
  die "$DEVICE is already in use; refusing to change or stop another emulator"
fi

GPU_HELP="$(timeout 12s "$EMULATOR" -help-gpu 2>&1 || true)"
GPU_MODE="${ANDROID_EMULATOR_GPU:-}"
if [[ -z "$GPU_MODE" ]]; then
  if grep -qw lavapipe <<<"$GPU_HELP"; then GPU_MODE=lavapipe; else GPU_MODE=software; fi
fi
[[ "$GPU_MODE" == lavapipe || "$GPU_MODE" == software ]] || die 'ANDROID_EMULATOR_GPU must be lavapipe or software'

LOG="$TOOLS_DIR/emulator-api29.log"
nohup "$EMULATOR" -avd "$AVD_NAME" -port 5554 -no-window -no-audio -no-boot-anim \
  -no-snapshot -accel off -cores 2 -memory 2048 -skin 720x1600 -dpi-device 294 \
  -gpu "$GPU_MODE" -camera-front none -camera-back none >"$LOG" 2>&1 </dev/null &
EMULATOR_PID=$!
printf 'Started emulator 37 on %s (API 29 AOSP, GPU %s, PID %s); boot deadline %ss\n' \
  "$DEVICE" "$GPU_MODE" "$EMULATOR_PID" "$BOOT_TIMEOUT_SECONDS"

BOOTED=0
for ((elapsed = 0; elapsed < BOOT_TIMEOUT_SECONDS; elapsed += 5)); do
  if ! kill -0 "$EMULATOR_PID" 2>/dev/null; then
    tail -n 80 "$LOG" >&2
    die 'emulator process exited before Android boot completed'
  fi
  if [[ "$(timeout 8s "$ADB" -s "$DEVICE" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)" == 1 ]]; then
    BOOTED=1
    break
  fi
  sleep 5
done
[[ "$BOOTED" == 1 ]] || { tail -n 80 "$LOG" >&2; die "Android did not boot within ${BOOT_TIMEOUT_SECONDS}s"; }

SDK_LEVEL="$(timeout 10s "$ADB" -s "$DEVICE" shell getprop ro.build.version.sdk | tr -d '\r')"
[[ "$SDK_LEVEL" == 29 ]] || die "expected API 29, got API ${SDK_LEVEL:-unknown}"
WINDOW_SERVICE="$(timeout 15s "$ADB" -s "$DEVICE" shell service check window 2>&1)"
IME_SERVICE="$(timeout 15s "$ADB" -s "$DEVICE" shell service check input_method 2>&1)"
grep -q 'Service window: found' <<<"$WINDOW_SERVICE" || die "window service unavailable: $WINDOW_SERVICE"
grep -q 'Service input_method: found' <<<"$IME_SERVICE" || die "input_method service unavailable: $IME_SERVICE"

[[ -s "$APK" ]] || die "attached keyboard APK not found: $APK"
timeout 150s "$ADB" -s "$DEVICE" install --no-incremental --no-streaming -r "$APK"
IME_ID='com.trost.ioskeyboard/.IosKeyboardService'
timeout 20s "$ADB" -s "$DEVICE" shell ime enable "$IME_ID"
timeout 20s "$ADB" -s "$DEVICE" shell ime set "$IME_ID"
timeout 20s "$ADB" -s "$DEVICE" shell am start -n 'com.trost.ioskeyboard/.MainActivity'
sleep 5

capture_screenshot() {
  local destination="$1"
  if ! timeout 30s "$ADB" -s "$DEVICE" emu screenrecord screenshot "$destination" >/dev/null 2>&1; then
    timeout 30s "$ADB" -s "$DEVICE" exec-out screencap -p > "$destination" || return 1
  fi
  file "$destination"
}

capture_screenshot "$EVIDENCE_DIR/settings.png" || die 'could not capture current keyboard settings screen'

timeout 25s "$ADB" -s "$DEVICE" shell uiautomator dump /sdcard/trost-window.xml >/dev/null 2>&1 || true
UI_XML="$(timeout 15s "$ADB" -s "$DEVICE" exec-out cat /sdcard/trost-window.xml 2>/dev/null || true)"
BOUNDS="$(python3 -c 'import re,sys,xml.etree.ElementTree as ET
try: root=ET.fromstring(sys.stdin.read())
except ET.ParseError: sys.exit(0)
for node in root.iter():
 text=node.attrib.get("text", "")
 if "проверка ввода" in text.casefold():
  m=re.search(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
  if m: print((int(m[1])+int(m[3]))//2, (int(m[2])+int(m[4]))//2); break' <<<"$UI_XML")"
if [[ -n "$BOUNDS" ]]; then
  read -r TAP_X TAP_Y <<<"$BOUNDS"
  timeout 10s "$ADB" -s "$DEVICE" shell input tap "$TAP_X" "$TAP_Y"
  sleep 3
  timeout 10s "$ADB" -s "$DEVICE" shell input text Trost
  sleep 2
  capture_screenshot "$EVIDENCE_DIR/keyboard.png" || die 'could not capture the active keyboard'
else
  printf 'emulator: input-test row not found in the settings accessibility tree\n' >&2
fi

WINDOW_STATE="$(timeout 20s "$ADB" -s "$DEVICE" shell dumpsys window windows 2>&1 | grep -E 'mCurrentFocus|mFocusedApp' | head -n 4 || true)"
ACTIVITY_STATE="$(timeout 20s "$ADB" -s "$DEVICE" shell dumpsys activity activities 2>&1 | grep -E 'mResumedActivity|topResumedActivity' | head -n 4 || true)"
IME_DUMP="$TOOLS_DIR/input-method-api29.txt"
timeout 20s "$ADB" -s "$DEVICE" shell dumpsys input_method > "$IME_DUMP"
CURRENT_IME="$(grep -E -m 3 'mCurMethodId|mCurId|com\.trost\.ioskeyboard' "$IME_DUMP" || true)"
printf '\nWindow service: %s\nInput-method service: %s\n' "$WINDOW_SERVICE" "$IME_SERVICE"
printf 'Window focus:\n%s\nResumed activity:\n%s\nCurrent input method:\n%s\n' \
  "$WINDOW_STATE" "$ACTIVITY_STATE" "$CURRENT_IME"
printf 'Evidence: %s/settings.png %s/keyboard.png\n' "$EVIDENCE_DIR" "$EVIDENCE_DIR"
grep -Eq '(mCurMethodId|mCurId)=.*com\.trost\.ioskeyboard' <<<"$CURRENT_IME" || die 'the Trost input method is not current in dumpsys input_method'
printf 'Android booted and the Trost input method is active; emulator remains running.\n'

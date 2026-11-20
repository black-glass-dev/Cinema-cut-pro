#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
mkdir -p docs/evidence
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
LOG="docs/evidence/COLOR_GRADING_MOBILE_${STAMP}.log"
exec > >(tee "$LOG") 2>&1

APP_ID="com.aistudio.chromapro.xzvqpw"
ACTIVITY="com.example.MainActivity"
REQUIRED_NDK_VERSION="27.2.12479018"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
blockers=()

# Gradle and connected tests should find the same tools even when only the SDK/JDK roots are set.
if [[ -n "${JAVA_HOME:-}" && -d "${JAVA_HOME}/bin" ]]; then
  PATH="${JAVA_HOME}/bin:${PATH}"
fi
if [[ -n "$SDK_ROOT" && -x "$SDK_ROOT/platform-tools/adb" ]]; then
  PATH="$SDK_ROOT/platform-tools:$PATH"
fi
export PATH
if [[ -n "$SDK_ROOT" ]]; then
  export ANDROID_SDK_ROOT="$SDK_ROOT"
fi

printf 'Color grading Android/device gate: %s\n' "$STAMP"
printf 'Branch: %s\n' "$(git branch --show-current)"
printf 'Evidence: %s\n' "$LOG"

JAVA_BIN="$(command -v java || true)"
JAVAC_BIN="$(command -v javac || true)"
ADB_BIN="$(command -v adb || true)"
for pair in "java:$JAVA_BIN" "javac:$JAVAC_BIN" "adb:$ADB_BIN"; do
  name="${pair%%:*}"
  path="${pair#*:}"
  if [[ -n "$path" ]]; then
    printf 'FOUND %s: %s\n' "$name" "$path"
  else
    printf 'MISSING %s\n' "$name"
    blockers+=("$name is not installed or not on PATH")
  fi
done

if [[ -n "$JAVA_BIN" ]]; then
  JAVA_SPEC="$("$JAVA_BIN" -XshowSettings:properties -version 2>&1 | awk -F '= ' '/java.specification.version/ { print $2; exit }' || true)"
  JAVA_MAJOR="${JAVA_SPEC%%.*}"
  if [[ "$JAVA_SPEC" == 1.* ]]; then JAVA_MAJOR="${JAVA_SPEC#1.}"; JAVA_MAJOR="${JAVA_MAJOR%%.*}"; fi
  if [[ ! "$JAVA_MAJOR" =~ ^[0-9]+$ ]]; then
    blockers+=("unable to determine the Java runtime version")
  elif (( 10#$JAVA_MAJOR < 17 )); then
    blockers+=("Java 17+ is required by the Android build; found $JAVA_SPEC")
  else
    printf 'Java specification: %s\n' "$JAVA_SPEC"
  fi
fi

if [[ -n "$JAVAC_BIN" ]]; then
  JAVAC_VERSION="$("$JAVAC_BIN" -version 2>&1 | awk '{ print $2; exit }' || true)"
  JAVAC_MAJOR="${JAVAC_VERSION%%.*}"
  if [[ ! "$JAVAC_MAJOR" =~ ^[0-9]+$ ]]; then
    blockers+=("unable to determine the javac version")
  elif (( 10#$JAVAC_MAJOR < 17 )); then
    blockers+=("javac 17+ is required; found $JAVAC_VERSION")
  else
    printf 'javac version: %s\n' "$JAVAC_VERSION"
  fi
  if [[ -n "${JAVA_MAJOR:-}" && "$JAVAC_MAJOR" != "$JAVA_MAJOR" ]]; then
    blockers+=("java ($JAVA_MAJOR) and javac ($JAVAC_MAJOR) major versions do not match")
  fi
fi

if [[ -z "$SDK_ROOT" || ! -d "$SDK_ROOT" ]]; then
  printf 'MISSING Android SDK: set ANDROID_SDK_ROOT or ANDROID_HOME to an installed SDK.\n'
  blockers+=("Android SDK root is unavailable")
else
  printf 'Android SDK: %s\n' "$SDK_ROOT"
  [[ -d "$SDK_ROOT/platforms" ]] || blockers+=("Android SDK platforms directory is missing")
  [[ -d "$SDK_ROOT/build-tools" ]] || blockers+=("Android SDK build-tools directory is missing")
  [[ -d "$SDK_ROOT/cmake/3.22.1" ]] || blockers+=("Android SDK CMake 3.22.1 is missing")

  # Match the exact NDK pin used by tools/build_ffmpeg_android.sh; an arbitrary installed NDK
  # can make the app's native/FFmpeg build fail even when Gradle can find some NDK.
  NDK_ROOT="${FFMPEG_NDK_ROOT:-}"
  if [[ -z "$NDK_ROOT" && -d "$SDK_ROOT/ndk/$REQUIRED_NDK_VERSION" ]]; then
    NDK_ROOT="$SDK_ROOT/ndk/$REQUIRED_NDK_VERSION"
  fi
  if [[ -z "$NDK_ROOT" ]]; then
    NDK_ROOT="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}"
  fi
  if [[ -z "$NDK_ROOT" || ! -d "$NDK_ROOT" ]]; then
    blockers+=("Android NDK $REQUIRED_NDK_VERSION is unavailable")
  else
    NDK_VERSION="$(sed -n 's/^Pkg.Revision = //p' "$NDK_ROOT/source.properties" 2>/dev/null | head -n 1 || true)"
    if [[ "$NDK_VERSION" != "$REQUIRED_NDK_VERSION" ]]; then
      blockers+=("expected Android NDK $REQUIRED_NDK_VERSION; found ${NDK_VERSION:-unknown} at $NDK_ROOT")
    elif [[ ! -d "$NDK_ROOT/toolchains/llvm/prebuilt" ]]; then
      blockers+=("NDK LLVM toolchain directory is missing at $NDK_ROOT")
    else
      export FFMPEG_NDK_ROOT="$NDK_ROOT"
      export ANDROID_NDK_HOME="$NDK_ROOT"
      export ANDROID_NDK_ROOT="$NDK_ROOT"
      printf 'Android NDK: %s (%s)\n' "$NDK_ROOT" "$NDK_VERSION"
    fi
  fi
fi

DEVICE_SERIAL="${ANDROID_SERIAL:-}"
if [[ -n "$ADB_BIN" ]]; then
  printf '\nADB devices:\n'
  if ! "$ADB_BIN" devices -l; then
    blockers+=("adb devices failed")
  elif [[ -n "$DEVICE_SERIAL" ]]; then
    DEVICE_STATE="$("$ADB_BIN" -s "$DEVICE_SERIAL" get-state 2>/dev/null || true)"
    if [[ "$DEVICE_STATE" != device ]]; then
      blockers+=("ANDROID_SERIAL=$DEVICE_SERIAL is not an authorized, connected device")
    fi
  else
    DEVICE_SERIAL="$("$ADB_BIN" devices | awk 'NR > 1 && $2 == "device" { print $1; exit }' || true)"
    if [[ -z "$DEVICE_SERIAL" ]]; then
      blockers+=("no authorized Android device or running emulator is connected")
    fi
  fi
fi

if [[ ${#blockers[@]} -gt 0 ]]; then
  printf '\nBLOCKED: mobile build/install gate did not start.\n'
  for blocker in "${blockers[@]}"; do printf ' - %s\n' "$blocker"; done
  exit 2
fi

export ANDROID_SERIAL="$DEVICE_SERIAL"
printf '\nSelected device: %s\n' "$DEVICE_SERIAL"
printf 'Device model: %s\n' "$("$ADB_BIN" -s "$DEVICE_SERIAL" shell getprop ro.product.model | tr -d '\r')"
printf 'Android API: %s\n' "$("$ADB_BIN" -s "$DEVICE_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
printf 'Device ABI: %s\n' "$("$ADB_BIN" -s "$DEVICE_SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')"

printf '\n== Focused Color mapper/evaluator JVM tests ==\n'
./gradlew --no-daemon :app:testDebugUnitTest \
  --tests com.example.model.colorgrade.engine.ColorGradeAdjustmentMapperTest \
  --tests com.example.timeline.engine.GradeEvaluationEngineTest

printf '\n== Full JVM unit suite ==\n'
./gradlew --no-daemon :app:testDebugUnitTest

printf '\n== Debug APK build ==\n'
./gradlew --no-daemon :app:assembleDebug

printf '\n== Device instrumentation tests ==\n'
./gradlew --no-daemon :app:connectedDebugAndroidTest

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$APK" ]]; then
  printf 'FAIL: expected debug APK was not produced at %s\n' "$APK"
  exit 1
fi
printf '\nAPK: %s\n' "$APK"
sha256sum "$APK"

printf '\n== Install and launch on %s ==\n' "$DEVICE_SERIAL"
"$ADB_BIN" -s "$DEVICE_SERIAL" install -r "$APK"
"$ADB_BIN" -s "$DEVICE_SERIAL" logcat -c
"$ADB_BIN" -s "$DEVICE_SERIAL" shell am force-stop "$APP_ID"
START_OUTPUT="$("$ADB_BIN" -s "$DEVICE_SERIAL" shell am start -W -n "$APP_ID/$ACTIVITY" 2>&1)"
printf '%s\n' "$START_OUTPUT"
if ! grep -q 'Status: ok' <<< "$START_OUTPUT"; then
  printf 'FAIL: launcher activity did not report a successful start.\n'
  exit 1
fi
printf '\nRecent AndroidRuntime error log (empty output is expected on a clean launch):\n'
"$ADB_BIN" -s "$DEVICE_SERIAL" logcat -d -t 300 -s AndroidRuntime:E

printf '\nMANUAL VISUAL ACCEPTANCE (record observations and screenshots in docs/evidence):\n'
printf '  1. Import the same short SDR H.264 clip; note device/API, APK and clip SHA-256; confirm preview is visible.\n'
printf '  2. Change Temperature, Tint, Contrast, Saturation and a primary wheel; verify paused and playing preview frames change.\n'
printf '  3. Add/select a node, change its wheel and LUT, adjust opacity, disable and reorder it; verify node-local behavior.\n'
printf '  4. Undo/redo each gesture, save/reopen the project, and use Reset All; verify values persist then return to neutral.\n'
printf '  5. Export the supported clip-level primary/curve subset and inspect the MP4; confirm unsupported advanced/multi-node export is rejected before rendering.\n'
printf '\nNOTE: this script automates focused/full JVM tests, APK build, instrumentation, install and launch only; visible grading and MP4 parity require recorded device observation.\n'
printf 'DEVICE GATE READY; visual/export acceptance is not claimed until evidence is recorded.\n'

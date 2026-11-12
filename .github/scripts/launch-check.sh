#!/usr/bin/env bash
# Installs the debug APK on a running emulator, launches it, presses play, and
# fails if it crashes or hangs. Used by the launch-test CI job. Prints the app's
# own logcat so playback issues are visible in the job log.
set -euo pipefail

apk="${1:-app-debug.apk}"
pkg="com.mobiledaw"

adb install -r "$apk"
adb logcat -c
adb shell am start -W -n "$pkg/.MainActivity"
sleep 6

crash() {
  echo "== app logcat =="
  adb logcat -d | grep -iE "mobiledaw|AndroidRuntime|ANR|FATAL" | tail -120 || true
}

if adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime.*$pkg|Force finishing activity.*$pkg"; then
  echo "App crashed on launch"; crash; exit 1
fi

# Press play. The transport button is centred horizontally, just above the tab bar.
size="$(adb shell wm size | tr -d '\r' | awk -F'[: x]+' '{print $(NF-1), $NF}')"
w="$(echo "$size" | awk '{print $1}')"
h="$(echo "$size" | awk '{print $2}')"
px=$(( w / 2 ))
py=$(( h * 86 / 100 ))
echo "Screen ${w}x${h}; tapping play at ${px},${py}"
adb shell input tap "$px" "$py"
sleep 5

# After play: must not have crashed and must still be running.
if adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime.*$pkg|ANR in $pkg"; then
  echo "App crashed or ANR'd during playback"; crash; exit 1
fi
pid="$(adb shell pidof "$pkg" | tr -d '\r')"
if [ -z "$pid" ]; then
  echo "App process is not running after play"; crash; exit 1
fi

echo "== app logcat (first 120 of our lines) =="
adb logcat -d | grep -iE "mobiledaw" | head -120 || true
echo "App launched, played, and is running (pid $pid)"

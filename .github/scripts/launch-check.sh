#!/usr/bin/env bash
# Installs the debug APK on a running emulator, launches it, and fails if it
# crashes on launch or isn't running afterwards. Used by the launch-test CI job.
set -euo pipefail

apk="${1:-app-debug.apk}"
pkg="com.mobiledaw"

adb install -r "$apk"
adb logcat -c
adb shell am start -W -n "$pkg/.MainActivity"

# Let it settle, then check for a crash and that the process is alive.
sleep 8

if adb logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime.*$pkg|Force finishing activity.*$pkg"; then
  echo "App crashed on launch"
  exit 1
fi

pid="$(adb shell pidof "$pkg" | tr -d '\r')"
if [ -z "$pid" ]; then
  echo "App process is not running after launch"
  adb logcat -d | tail -80
  exit 1
fi

echo "App launched and is running (pid $pid)"

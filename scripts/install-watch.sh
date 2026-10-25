#!/usr/bin/env bash
# Build + install debug APK on a connected device (watch over wireless adb or USB dock).
# Usage: scripts/install-watch.sh   (expects exactly one adb device, or ANDROID_SERIAL set)
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
cd "$ROOT"
"$GRADLE" --no-daemon :app:assembleDebug
APK="app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || { echo "APK missing after build" >&2; exit 1; }
echo "installing $APK"
adb install -r "$APK"
echo "launched:"
adb shell monkey -p dev.hermes.wearos -c android.intent.category.LAUNCHER 1 >/dev/null && echo "Hermes on your wrist 🚀"

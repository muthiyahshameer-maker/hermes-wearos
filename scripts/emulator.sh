#!/usr/bin/env bash
# Boot the folder-local Wear OS emulator (headless) and wait until it's up.
# Usage: scripts/emulator.sh [create|up|status]
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

AVD_NAME=hermeswear
IMG="system-images;android-35-ext15;android-wear;x86_64"
EMU_LOG="$DEVKIT/emulator.log"
ADB="$ANDROID_HOME/platform-tools/adb"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

create_avd() {
  mkdir -p "$ANDROID_AVD_HOME"
  if "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" list avd 2>/dev/null | grep -q "$AVD_NAME"; then
    echo "AVD $AVD_NAME exists"
  else
    "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd -n "$AVD_NAME" -k "$IMG" -d 59 -f <<<"no" >/dev/null
    echo "AVD $AVD_NAME created"
  fi
}

wait_boot() {
  "$ADB" wait-for-device
  for _ in $(seq 1 90); do
    b=$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
    [ "$b" = "1" ] && { echo "EMULATOR BOOTED ($("$ADB" devices | sed -n 2p))"; return 0; }
    sleep 5
  done
  echo "timeout waiting for boot — see $EMU_LOG" >&2; return 1
}

case "${1:-up}" in
  create) create_avd ;;
  status) "$ADB" devices; "$ADB" shell getprop sys.boot_completed 2>/dev/null || true ;;
  up)
    create_avd
    if "$ADB" devices | grep -q emulator; then wait_boot; exit $?; fi
    pgrep -f "emulator -avd $AVD_NAME" >/dev/null || {
      nohup "$ANDROID_HOME/emulator/emulator" -avd "$AVD_NAME" \
        -no-window -no-audio -no-boot-anim -no-snapshot-save \
        -gpu swiftshader_indirect -camera-back none -camera-front none \
        >"$EMU_LOG" 2>&1 &
      echo "emulator starting (log: $EMU_LOG)"
    }
    wait_boot
    ;;
  *) echo "usage: $0 [create|up|status]"; exit 2 ;;
esac

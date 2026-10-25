#!/usr/bin/env bash
# M2: fetch the Vosk small-en US STT model and install it into the app's filesDir on the
# connected device/emulator (files/vosk-model-small-en-us; VoskStt.isModelPresent checks it).
#
# Verified 2026-10 on emulator-5554 (Wear OS 5.1, debuggable app):
#   1. download + unzip under devkit/vosk/ (folder-local, skipped if already present)
#   2. tar czf the model dir, `adb push` to /data/local/tmp (world-readable staging)
#   3. `adb shell run-as dev.hermes.wearos tar xzf /data/local/tmp/... -C files`
#      (run-as executes as the app uid: correct ownership; /data/local/tmp grants others
#       execute+read on files so the app uid can read the tarball by path).
#   A direct push into /storage/emulated/0/Android/data/<pkg>/files is unreliable on Wear
#   (Wear apps don't read that path via filesDir), so it is NOT used.
#   `adb exec-in "run-as <pkg> tar xzf - -C files" < tarball` also works but the two-step
#   push is easier to re-run per-step when debugging.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

PKG="dev.hermes.wearos"
VER="vosk-model-small-en-us-0.15"
VOSK_DIR="$DEVKIT/vosk"
ZIP="$VOSK_DIR/$VER.zip"
MODEL="$VOSK_DIR/vosk-model-small-en-us"
ADB="$ANDROID_HOME/platform-tools/adb"
SERIAL_ARGS=()
[ -n "${ANDROID_SERIAL:-}" ] && SERIAL_ARGS=(-s "$ANDROID_SERIAL")
ADB_CMD=("$ADB" ${SERIAL_ARGS[@]+"${SERIAL_ARGS[@]}"})

mkdir -p "$VOSK_DIR"

if [ ! -f "$ZIP" ]; then
  echo "downloading $VER.zip ..."
  curl -fL --retry 3 -o "$ZIP" "https://alphacephei.com/vosk/models/$VER.zip"
fi

if [ ! -d "$MODEL" ]; then
  echo "unzipping ..."
  unzip -q -o "$ZIP" -d "$VOSK_DIR"
  mv -f "$VOSK_DIR/$VER" "$MODEL"
fi

if [ ! -f "$MODEL/final.mdl" ] && [ ! -f "$MODEL/am/final.mdl" ]; then
  echo "ERROR: no final.mdl (root or am/) in $MODEL — bad download?" >&2
  exit 1
fi

TGZ="/tmp/opencode/vosk-model.tgz"
echo "packing -> $TGZ"
tar czf "$TGZ" -C "$VOSK_DIR" vosk-model-small-en-us

echo "pushing to /data/local/tmp ..."
"${ADB_CMD[@]}" push "$TGZ" /data/local/tmp/vosk-model.tgz

echo "extracting into $PKG files/ (run-as) ..."
"${ADB_CMD[@]}" shell run-as "$PKG" rm -rf "files/vosk-model-small-en-us"
"${ADB_CMD[@]}" shell run-as "$PKG" tar xzf /data/local/tmp/vosk-model.tgz -C files
"${ADB_CMD[@]}" shell "run-as $PKG ls files/vosk-model-small-en-us" | head -20

echo "done. Verify in-app: adb shell am broadcast -n $PKG/$PKG.DevAskReceiver -e stt_status 1"

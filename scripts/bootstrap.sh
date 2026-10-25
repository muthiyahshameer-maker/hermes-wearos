#!/usr/bin/env bash
# Bootstrap the folder-local Android devkit + Gradle. No root required.
# Idempotent: safe to re-run; skips what's already installed.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

CT_ZIP_URLS="
https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
"
GRADLE_VERSION="${GRADLE_VERSION:-8.13}"
SDK_PACKAGES=( "platform-tools" "platforms;android-35" "build-tools;35.0.0" )

log() { printf '\033[1;36m[bootstrap]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[bootstrap] %s\033[0m\n' "$*" >&2; exit 1; }

mkdir -p "$DEVKIT" "$GRADLE_USER_HOME"

# --- Android cmdline-tools ---------------------------------------------------
CT="$ANDROID_HOME/cmdline-tools/latest"
if [ -x "$CT/bin/sdkmanager" ]; then
  log "cmdline-tools already present: $("$CT/bin/sdkmanager" --version 2>/dev/null | tail -1)"
else
  log "downloading Android cmdline-tools"
  Z="$DEVKIT/dl/cmdline-tools.zip"
  got=0
  for url in $CT_ZIP_URLS; do
    if curl -fsSL -o "$Z" "$url"; then got=1; break; fi
  done
  [ "$got" = 1 ] || die "could not download cmdline-tools from any mirror"
  rm -rf "$ANDROID_HOME/cmdline-tools/_tmp"; mkdir -p "$ANDROID_HOME/cmdline-tools/_tmp"
  unzip -q "$Z" -d "$ANDROID_HOME/cmdline-tools/_tmp"
  mv "$ANDROID_HOME/cmdline-tools/_tmp/cmdline-tools" "$CT"
  rm -rf "$ANDROID_HOME/cmdline-tools/_tmp"
  log "cmdline-tools installed"
fi

# --- licenses + base SDK packages --------------------------------------------
log "accepting SDK licenses"
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true

log "installing SDK packages: ${SDK_PACKAGES[*]}"
# Skip when everything is already on disk (sdkmanager exits non-zero on a no-op re-run).
need=0
for pkg in "${SDK_PACKAGES[@]}"; do
  case "$pkg" in
    platform-tools) [ -x "$ANDROID_HOME/platform-tools/adb" ] || need=1 ;;
    platforms\;*)   [ -d "$ANDROID_HOME/platforms/${pkg##*;}" ] || need=1 ;;
    build-tools\;*) [ -d "$ANDROID_HOME/build-tools/${pkg##*;}" ] || need=1 ;;
  esac
done
if [ "$need" = 1 ]; then
  yes | "$SDKMANAGER" "${SDK_PACKAGES[@]}" > "$DEVKIT/dl/sdkmanager-install.log" 2>&1 \
    || die "sdkmanager install failed — see $DEVKIT/dl/sdkmanager-install.log"
else
  log "SDK packages already installed"
fi

# --- Gradle -------------------------------------------------------------------
if [ -x "$DEVKIT/gradle/current/bin/gradle" ]; then
  log "gradle already present: $("$GRADLE" --version 2>/dev/null | grep -m1 Gradle || true)"
else
  log "downloading Gradle $GRADLE_VERSION"
  Z="$DEVKIT/dl/gradle-$GRADLE_VERSION-bin.zip"
  [ -f "$Z" ] || curl -fsSL -o "$Z" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
  mkdir -p "$DEVKIT/gradle"
  unzip -q "$Z" -d "$DEVKIT/gradle"
  ln -sfn "$DEVKIT/gradle/gradle-$GRADLE_VERSION" "$DEVKIT/gradle/current"
fi

# --- project wiring ------------------------------------------------------------
echo "sdk.dir=$ANDROID_HOME" > "$ROOT/local.properties"

log "DEVKIT READY"
log "  sdk    : $ANDROID_HOME"
log "  gradle : $DEVKIT/gradle/current"
log "  java   : $JAVA_HOME"

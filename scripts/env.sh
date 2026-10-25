#!/usr/bin/env bash
# Shared environment for all hermes-wearos scripts. Folder-local; never touches system state.
# Sourced by bootstrap.sh / doctor.sh / build.sh / install-watch.sh.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WORKTREES="$(cd "$ROOT/.." && pwd)"

export DEVKIT="${DEVKIT:-$WORKTREES/devkit}"
export ANDROID_HOME="$DEVKIT/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$DEVKIT/gradle-home"
export ANDROID_USER_HOME="$DEVKIT/android"        # avd configs & emulator data, also folder-local
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"  # same dir avdmanager writes to; emulator reads it
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
GRADLE="$DEVKIT/gradle/current/bin/gradle"

mkdir -p "$DEVKIT/dl" 2>/dev/null || true
export PATH="$DEVKIT/gradle/current/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

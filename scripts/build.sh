#!/usr/bin/env bash
# Build debug APK with the folder-local toolchain. Usage: scripts/build.sh [gradle args...]
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/env.sh"
cd "$ROOT"
exec "$GRADLE" --no-daemon assembleDebug "$@"

#!/usr/bin/env bash
# Create the folder-local python venv + install faster-whisper (CPU) for watchkit STT.
# Standalone: no env.sh needed. Idempotent — safe to re-run.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WK="$SCRIPT_DIR/../server/watchkit"
VENV="$WK/.venv"

if [ ! -x "$VENV/bin/python" ]; then
  echo "creating venv: $VENV"
  python3 -m venv "$VENV"
fi
"$VENV/bin/pip" install --quiet --upgrade pip
echo "installing faster-whisper (CPU wheels)…"
# av<16 pin: faster-whisper 1.2.1 calls av.open(metadata_errors=…) which PyAV 16+ dropped.
"$VENV/bin/pip" install --quiet faster-whisper "av<16"
echo "installing Pillow (watchkit /img image scaler)…"
"$VENV/bin/pip" install --quiet Pillow
"$VENV/bin/python" - <<'EOF'
import ctranslate2, faster_whisper, PIL
print(f"ok: faster-whisper {faster_whisper.__version__} / ctranslate2 {ctranslate2.__version__} / Pillow {PIL.__version__}")
EOF

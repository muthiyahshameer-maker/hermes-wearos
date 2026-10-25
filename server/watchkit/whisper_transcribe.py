#!/usr/bin/env python3
"""watchkit STT helper — transcribe one audio file to JSON on stdout.

Invoked by watchkit.mjs as:  .venv/bin/python whisper_transcribe.py <audio-file>
Model "base" (CT2 int8, CPU) is downloaded once into .models/ (folder-local).
Cold start (~2-4s model load) is accepted for v1; see watchkit.mjs header.
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
MODELS = os.path.join(HERE, ".models")
os.environ.setdefault("HF_HOME", os.path.join(MODELS, "hf"))  # keep hub side-cache local

if len(sys.argv) < 2:
    print(json.dumps({"error": "usage: whisper_transcribe.py <audio>"}))
    sys.exit(2)

from faster_whisper import WhisperModel  # noqa: E402  (heavy import after arg check)

model = WhisperModel("base", device="cpu", compute_type="int8", download_root=MODELS)
segments, info = model.transcribe(sys.argv[1], beam_size=1)
text = " ".join(s.text.strip() for s in segments).strip()
print(json.dumps({"text": text, "language": info.language, "duration_s": round(info.duration, 2)}))

#!/usr/bin/env python3
"""watchkit /img helper — scale one image to fit a width and emit WebP JSON on stdout.

Invoked by watchkit.mjs as:  .venv/bin/python img_scale.py <src> <dst> <width>
Fit-within semantics: the source is only ever downscaled (Lanczos) so it fits inside
`width` — never upscaled (blurry stretch on a 480px watch is worse than padding).
Animated sources (gif/apng/webp): first frame only; watchkit surfaces that as X-Note.
Output: WebP quality 82. stdout: {"w":…,"h":…,"note":…} — one line, or exit != 0.
"""
import json
import sys

if len(sys.argv) < 4:
    print(json.dumps({"error": "usage: img_scale.py <src> <dst> <width>"}))
    sys.exit(2)

from PIL import Image  # noqa: E402  (heavy import after arg check)

src, dst, width = sys.argv[1], sys.argv[2], max(16, int(sys.argv[3]))

try:
    im = Image.open(src)
    im.load()  # force decode before we touch frames/mode

    note = None
    if getattr(im, "n_frames", 1) > 1:  # animated: GIF/APNG/multi-frame WebP → first frame
        im.seek(0)
        note = "gif-first-frame"

    # WebP takes RGB/RGBA/L; palette & transparency cases go RGBA so alpha survives.
    if im.mode in ("RGBA", "LA", "PA") or (im.mode == "P" and "transparency" in im.info):
        im = im.convert("RGBA")
    elif im.mode not in ("RGB", "L"):
        im = im.convert("RGB")

    ratio = min(1.0, width / im.width)  # fit-within: downscale only
    out = im.resize((max(1, round(im.width * ratio)), max(1, round(im.height * ratio))), Image.LANCZOS)
    out.save(dst, "WEBP", quality=82, method=4)
    print(json.dumps({"w": out.width, "h": out.height, "note": note}))
except Exception as e:  # undecodable / pixel bomb / write failure → watchkit maps exit 3 to 415
    print(json.dumps({"error": f"{type(e).__name__}: {e}"}))
    sys.exit(3)

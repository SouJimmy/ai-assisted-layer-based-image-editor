#!/usr/bin/env python3
"""Exercise real model inference and, optionally, an injected MPS failure with a real CPU retry."""

import argparse
import sys
from io import BytesIO
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "python-service/src"))
from editor_helper.image_cache import ImageCache
from editor_helper.model_runtime import MobileSamBackend
from PIL import Image, ImageDraw

parser = argparse.ArgumentParser()
parser.add_argument("--fallback", action="store_true")
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
backend = MobileSamBackend(root / "python-service/models/model-manifest.json")
backend.initialize()
image = Image.new("RGB", (128, 96), "white")
ImageDraw.Draw(image).rectangle((20, 15, 80, 75), fill="red")
buffer = BytesIO()
image.save(buffer, "PNG")
cache = ImageCache(backend)
registered = cache.register(buffer.getvalue(), "model-smoke")
if args.fallback:
    if backend.device != "mps":
        raise SystemExit("MPS is unavailable; fallback smoke was not run")
    original = backend._predict

    def injected(rgb, embedding, points):
        if backend.device == "mps":
            raise RuntimeError("deliberately injected MPS failure")
        return original(rgb, embedding, points)

    backend._predict = injected
result = cache.segment(
    {
        "imageId": registered["imageId"],
        "sourceRevision": "model-smoke",
        "requestId": "model-smoke",
        "points": [{"x": 40, "y": 40, "label": 1}],
    }
)
assert 1 <= len(result["candidates"]) <= 3
if args.fallback:
    assert result["deviceUsed"] == "cpu"
print("Real model inference passed:", result["deviceUsed"], len(result["candidates"]), "candidates")

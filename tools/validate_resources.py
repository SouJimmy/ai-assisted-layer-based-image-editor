#!/usr/bin/env python3
"""Measure PSD imports in fresh processes and real-model RSS/MPS retention separately.

Run with python-service/.venv/bin/python. This is a manual resource evaluation,
not a timing-sensitive unit test or an image-quality acceptance test.
"""

import argparse
import gc
import json
import os
import platform
import resource
import subprocess
import sys
import time
from io import BytesIO
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "python-service/src"))


def memory():
    return {
        "rss_bytes": int(subprocess.check_output(["ps", "-o", "rss=", "-p", str(os.getpid())]))
        * 1024,
        "process_peak_rss_bytes": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
        * (1 if sys.platform == "darwin" else 1024),
    }


def psd_worker(path):
    from editor_helper.psd_importer import import_psd

    baseline = memory()
    started = time.perf_counter()
    archive = import_psd(path.read_bytes())
    return {
        "baseline": baseline,
        "after_import": memory(),
        "elapsed_ms": (time.perf_counter() - started) * 1000,
        "archive_bytes": len(archive),
    }


def ai_worker(cycles, seconds):
    from editor_helper.image_cache import ImageCache
    from editor_helper.model_runtime import MobileSamBackend
    from PIL import Image, ImageDraw

    backend = MobileSamBackend(ROOT / "python-service/models/model-manifest.json")
    backend.initialize()
    cache = ImageCache(backend)
    image = Image.new("RGB", (512, 512), "white")
    ImageDraw.Draw(image).rectangle((100, 100, 400, 400), fill="red")
    buffer = BytesIO()
    image.save(buffer, "PNG")
    data = buffer.getvalue()

    def sample():
        if backend.device == "mps":
            backend.torch.mps.synchronize()
        result = memory()
        result["elapsed_seconds"] = time.monotonic() - started
        result["retained_entries"] = len(cache.entries)
        result["retained_tombstones"] = len(cache.deleted)
        result["mps_allocated_bytes"] = (
            backend.torch.mps.current_allocated_memory() if backend.device == "mps" else None
        )
        result["mps_driver_bytes"] = (
            backend.torch.mps.driver_allocated_memory() if backend.device == "mps" else None
        )
        return result

    def cycle():
        registered = cache.register(data, "resources")
        try:
            result = cache.segment(
                {
                    "imageId": registered["imageId"],
                    "sourceRevision": "resources",
                    "requestId": "resources",
                    "points": [{"x": 256, "y": 256, "label": 1}],
                }
            )
            if not 1 <= len(result["candidates"]) <= 3:
                raise RuntimeError("Invalid candidate count")
        finally:
            cache.mark_deleted(registered["imageId"])
            cache.release(registered["imageId"])
        gc.collect()
        if cache.entries or cache.deleted:
            raise RuntimeError("Image cache was not released")

    started = time.monotonic()
    for _ in range(5):
        cycle()
    baseline = sample()
    deadline = time.monotonic() + seconds
    samples = []
    while len(samples) < cycles or time.monotonic() < deadline:
        cycle()
        samples.append(sample())
    result = {
        "device": backend.device,
        "warmup_cycles": 5,
        "baseline": baseline,
        "cycles": len(samples),
        "samples": samples,
    }
    if backend.device == "mps":
        backend.torch.mps.empty_cache()
        result["after_empty_cache"] = sample()
    return result


def child(*arguments):
    completed = subprocess.run(
        [sys.executable, str(Path(__file__).resolve()), *map(str, arguments)],
        cwd=ROOT,
        check=True,
        stdout=subprocess.PIPE,
        text=True,
    )
    return json.loads(completed.stdout)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cycles", type=int, default=30)
    parser.add_argument(
        "--seconds",
        type=float,
        default=0,
        help="Minimum measured AI duration, in addition to the cycle minimum",
    )
    parser.add_argument("--output", type=Path, default=ROOT / "reports/resources")
    parser.add_argument("--psd-child", type=Path, help=argparse.SUPPRESS)
    parser.add_argument("--ai-child", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.cycles < 1 or args.seconds < 0:
        parser.error("cycles must be positive and seconds nonnegative")
    if args.psd_child:
        print(json.dumps(psd_worker(args.psd_child)))
        return
    if args.ai_child:
        print(json.dumps(ai_worker(args.cycles, args.seconds)))
        return

    from PIL import Image
    from psd_tools import PSDImage
    from psd_tools.api.layers import PixelLayer

    args.output.mkdir(parents=True, exist_ok=True)
    result = {"platform": platform.platform(), "python": platform.python_version(), "psd": []}
    for width, height in [(1920, 1080), (3840, 2160)]:
        path = args.output.resolve() / f"{width}x{height}.psd"
        psd = PSDImage.new("RGBA", (width, height))
        for index in range(4):
            PixelLayer.frompil(
                Image.new("RGBA", (width, height), (index * 60, 40, 200, 128)),
                psd,
                name=f"layer-{index}",
            )
        psd.save(path)
        measured = child("--psd-child", path)
        result["psd"].append({"width": width, "height": height, "layers": 4, **measured})
        print(f"PSD {width}x{height}: measured", flush=True)
    result["ai"] = child("--ai-child", "--cycles", args.cycles, "--seconds", args.seconds)
    report = args.output / "resources.json"
    report.write_text(json.dumps(result, indent=2) + "\n")
    print(f"Resource measurements saved to {report}")


if __name__ == "__main__":
    main()

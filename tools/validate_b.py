#!/usr/bin/env python3
"""Manual B-side performance, photographic inference and generated PSD evaluation.

Run with the full service virtual environment. Results are observations, not CI gates.
"""

import argparse
import base64
import gc
import hashlib
import json
import os
import platform
import resource
import statistics
import subprocess
import sys
import time
import urllib.request
from io import BytesIO
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "python-service/src"))
import numpy as np
from editor_helper.image_cache import ImageCache
from editor_helper.model_runtime import MobileSamBackend
from editor_helper.psd_importer import import_psd
from PIL import Image, ImageDraw
from psd_tools import PSDImage
from psd_tools.api.layers import Group, PixelLayer
from psd_tools.constants import BlendMode


def rss_bytes():
    # macOS ru_maxrss is bytes; Linux reports KiB. This is the process lifetime high-water mark.
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss * (
        1 if sys.platform == "darwin" else 1024
    )


def current_rss_bytes():
    return int(subprocess.check_output(["ps", "-o", "rss=", "-p", str(os.getpid())])) * 1024


def timed(call):
    started = time.perf_counter()
    value = call()
    return value, (time.perf_counter() - started) * 1000


def png_bytes(image):
    output = BytesIO()
    image.save(output, "PNG")
    return output.getvalue()


def evaluate_model(output, cycles):
    backend = MobileSamBackend(ROOT / "python-service/models/model-manifest.json")
    _, load_ms = timed(backend.initialize)
    cache = ImageCache(backend)
    results = {"device": backend.device, "load_warmup_ms": load_ms, "sizes": [], "photos": []}
    for width, height in [(1920, 1080), (3840, 2160)]:
        image = Image.new("RGB", (width, height), "white")
        ImageDraw.Draw(image).rectangle(
            (width // 4, height // 4, width * 3 // 4, height * 3 // 4), fill="red"
        )
        data = png_bytes(image)
        registered, register_ms = timed(lambda: cache.register(data, "benchmark"))
        request = {
            "imageId": registered["imageId"],
            "sourceRevision": "benchmark",
            "requestId": "benchmark",
            "points": [{"x": width // 2, "y": height // 2, "label": 1}],
        }
        first, segment_ms = timed(lambda: cache.segment(request))
        request["points"].append({"x": 0, "y": 0, "label": 0})
        repeats = [timed(lambda: cache.segment(request))[1] for _ in range(3)]
        results["sizes"].append(
            {
                "width": width,
                "height": height,
                "register_ms": register_ms,
                "first_segment_ms": segment_ms,
                "additional_median_ms": statistics.median(repeats),
                "additional_max_ms": max(repeats),
                "candidate_count": len(first["candidates"]),
                "rss_peak_bytes": rss_bytes(),
                "cache_bytes": sum(e.size for e in cache.entries.values()),
            }
        )
        cache.mark_deleted(registered["imageId"])
        cache.release(registered["imageId"])
    points = {
        "astronaut": [(250, 250, 1), (10, 10, 0)],
        "chelsea": [(230, 160, 1), (10, 10, 0)],
        "coffee": [(300, 200, 1), (20, 20, 0)],
    }
    panels = []
    for sample in json.loads((ROOT / "tools/validation-images.json").read_text()):
        path = ROOT / ".cache/validation-images" / (sample["name"] + ".png")
        if not path.exists():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(urllib.request.urlopen(sample["url"], timeout=30).read())
        if hashlib.sha256(path.read_bytes()).hexdigest() != sample["sha256"]:
            raise ValueError("Evaluation image checksum mismatch")
        image = Image.open(path).convert("RGB")
        registered = cache.register(path.read_bytes(), sample["name"])
        request = {
            "imageId": registered["imageId"],
            "sourceRevision": sample["name"],
            "requestId": sample["name"],
            "points": [{"x": x, "y": y, "label": label} for x, y, label in points[sample["name"]]],
        }
        response, elapsed = timed(lambda: cache.segment(request))
        candidates = []
        for index, candidate in enumerate(response["candidates"]):
            mask = Image.open(BytesIO(base64.b64decode(candidate["maskPngBase64"]))).convert("L")
            selected = np.asarray(mask) > 0
            candidates.append(
                {"score": candidate["score"], "selected_fraction": float(selected.mean())}
            )
            cutout = Image.new("RGB", image.size, (220, 220, 220))
            cutout.paste(image, mask=mask)
            panel = Image.new("RGB", (300, 245), "white")
            cutout.thumbnail((300, 220))
            panel.paste(cutout, (0, 25))
            ImageDraw.Draw(panel).text(
                (5, 5),
                f"{sample['name']} #{index + 1} score={candidate['score']:.3f}",
                fill="black",
            )
            panels.append(panel)
            mask.save(output / f"{sample['name']}-{index + 1}-mask.png")
        results["photos"].append(
            {
                "name": sample["name"],
                "segment_ms": elapsed,
                "points": request["points"],
                "candidates": candidates,
            }
        )
        cache.mark_deleted(registered["imageId"])
        cache.release(registered["imageId"])
    sheet = Image.new("RGB", (900, 245 * 3), "white")
    for index, panel in enumerate(panels):
        sheet.paste(panel, (index % 3 * 300, index // 3 * 245))
    sheet.save(output / "photo-candidates.png")
    data = png_bytes(Image.new("RGB", (512, 512), "red"))
    samples = []
    for index in range(cycles):
        registered = cache.register(data, "soak")
        cache.segment(
            {
                "imageId": registered["imageId"],
                "sourceRevision": "soak",
                "requestId": str(index),
                "points": [{"x": 256, "y": 256, "label": 1}],
            }
        )
        cache.mark_deleted(registered["imageId"])
        cache.release(registered["imageId"])
        gc.collect()
        samples.append(current_rss_bytes())
    results["soak"] = {
        "cycles": cycles,
        "retained_entries": len(cache.entries),
        "retained_tombstones": len(cache.deleted),
        "rss_samples_bytes": samples,
        "rss_peak_bytes": rss_bytes(),
    }
    return results


def evaluate_psd(output):
    import zipfile

    results = []
    cases = [
        (name, 128, 96, 40 if name == "40-layers" else 2)
        for name in ["plain", "pass-through", "isolated-opacity", "hidden", "multiply", "40-layers"]
    ]
    cases += [
        ("2K-layers", 1920, 1080, 3),
        ("4K-layers", 3840, 2160, 3),
        ("500-nodes", 128, 96, 499),
    ]
    for case, width, height, count in cases:
        psd = PSDImage.new("RGBA", (width, height))
        PixelLayer.frompil(
            Image.new("RGBA", (width - 28, height - 16), (30, 160, 70, 255)), psd, name="background"
        )
        parent = psd
        if case in ("pass-through", "isolated-opacity", "hidden"):
            parent = Group.new(psd, "group")
            parent.blend_mode = (
                BlendMode.PASS_THROUGH if case == "pass-through" else BlendMode.NORMAL
            )
            parent.opacity = 128 if case == "isolated-opacity" else 255
            parent.visible = case != "hidden"
        for index in range(count):
            layer = PixelLayer.frompil(
                Image.new("RGBA", (width - 78, height - 56), (index * 33 % 256, 40, 210, 180)),
                parent,
                name=f"layer-{index}",
                left=10 + index % 40,
                top=12,
            )
            layer.opacity = 180
            if case == "multiply":
                layer.blend_mode = BlendMode.MULTIPLY
        data = BytesIO()
        psd.save(data)
        (output / f"{case}.psd").write_bytes(data.getvalue())
        archive_data, elapsed = timed(lambda: import_psd(data.getvalue()))
        actual = Image.new("RGBA", psd.size)
        with zipfile.ZipFile(BytesIO(archive_data)) as archive:
            manifest = json.loads(archive.read("manifest.json"))
            assets = {a["assetId"]: a for a in manifest["assets"]}
            for node in manifest["nodes"]:
                if (
                    node["importDisposition"] not in ("RASTER", "REPLACEMENT_RASTER")
                    or not node["effectiveVisible"]
                ):
                    continue
                image = Image.open(BytesIO(archive.read(assets[node["assetId"]]["path"]))).convert(
                    "RGBA"
                )
                alpha = np.asarray(image.getchannel("A"), dtype=np.float64)
                image.putalpha(
                    Image.fromarray(np.floor(alpha * node["opacity"] + 0.5).astype("uint8"))
                )
                actual.alpha_composite(image, (node["x"], node["y"]))
        reference = psd.composite(force=True).convert("RGBA")

        def white_pixels(image):
            background = Image.new("RGBA", image.size, "white")
            background.alpha_composite(image)
            return np.asarray(background.convert("RGB"), dtype=np.int16)

        difference = np.abs(white_pixels(actual) - white_pixels(reference))
        actual.save(output / f"{case}-imported.png")
        results.append(
            {
                "case": case,
                "import_ms": elapsed,
                "max_rgb_error_on_white": int(difference.max()),
                "mean_rgb_error_on_white": float(difference.mean()),
                "asset_count": len(assets),
                "warnings": len(manifest["warnings"]),
                "rss_peak_bytes": rss_bytes(),
            }
        )
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cycles", type=int, default=30)
    parser.add_argument("--output", type=Path, default=ROOT / "reports/validation")
    args = parser.parse_args()
    if args.cycles < 1:
        parser.error("cycles must be positive")
    args.output.mkdir(parents=True, exist_ok=True)
    results = {
        "platform": platform.platform(),
        "python": platform.python_version(),
        "model": evaluate_model(args.output, args.cycles),
        "psd": evaluate_psd(args.output),
    }
    (args.output / "python.json").write_text(json.dumps(results, indent=2) + "\n")
    print(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()

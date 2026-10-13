import base64
import math
import threading
import time
import uuid
import warnings
from collections import OrderedDict
from dataclasses import dataclass
from io import BytesIO

import numpy as np
from PIL import Image

from .errors import ServiceError

MAX_PIXELS = 33_554_432
MAX_SIDE = 16384
Image.MAX_IMAGE_PIXELS = MAX_PIXELS


def dimensions(width, height):
    if not (1 <= width <= MAX_SIDE and 1 <= height <= MAX_SIDE and width * height <= MAX_PIXELS):
        raise ServiceError("RESOURCE_LIMIT", "画像サイズが上限を超えています", 413)


def decode_png(data):
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(BytesIO(data)) as image:
                if image.format != "PNG":
                    raise ServiceError("INVALID_IMAGE", "PNG画像を指定してください", 422)
                dimensions(*image.size)
                image.load()
                rgba = image.convert("RGBA")
                bg = Image.new("RGBA", image.size, (255, 255, 255, 255))
                bg.alpha_composite(rgba)
                return np.asarray(bg.convert("RGB")).copy()
    except ServiceError:
        raise
    except Exception as exc:
        raise ServiceError("INVALID_IMAGE", "画像を読み込めません", 422) from exc


@dataclass
class Entry:
    rgb: object
    embedding: object
    revision: str
    used: float
    size: int


class ImageCache:
    """Registration, inference and cleanup run on the model worker.

    Only mark_deleted may run on the API thread; its tombstones are lock-protected.
    """

    def __init__(
        self, backend, max_entries=2, budget=128 * 1024 * 1024, ttl=600, clock=time.monotonic
    ):
        self.backend, self.max_entries, self.budget, self.ttl, self.clock = (
            backend,
            max_entries,
            budget,
            ttl,
            clock,
        )
        self.entries = OrderedDict()
        self.deleted = set()
        self.delete_lock = threading.Lock()

    def _expire(self):
        now = self.clock()
        for key in list(self.entries):
            if now - self.entries[key].used >= self.ttl:
                del self.entries[key]
                with self.delete_lock:
                    self.deleted.discard(key)

    def _size(self, rgb, embedding):
        features = embedding["features"]
        return rgb.nbytes + (
            features.nelement() * features.element_size()
            if hasattr(features, "nelement")
            else features.nbytes
        )

    def register(self, data, revision):
        self._expire()
        rgb = decode_png(data)
        embedding = self.backend.encode(rgb)
        size = self._size(rgb, embedding)
        if size > self.budget:
            raise ServiceError("RESOURCE_LIMIT", "画像キャッシュの上限を超えています", 413)
        while self.entries and (
            len(self.entries) >= self.max_entries
            or sum(e.size for e in self.entries.values()) + size > self.budget
        ):
            evicted, _ = self.entries.popitem(last=False)
            with self.delete_lock:
                self.deleted.discard(evicted)
        image_id = str(uuid.uuid4())
        self.entries[image_id] = Entry(rgb, embedding, revision, self.clock(), size)
        return {
            "imageId": image_id,
            "sourceRevision": revision,
            "width": rgb.shape[1],
            "height": rgb.shape[0],
        }

    def mark_deleted(self, image_id):
        with self.delete_lock:
            if image_id in self.entries:
                self.deleted.add(image_id)

    def release(self, image_id):
        self.entries.pop(image_id, None)
        with self.delete_lock:
            self.deleted.discard(image_id)

    def segment(self, body):
        self._expire()
        with self.delete_lock:
            deleted = body["imageId"] in self.deleted
        entry = None if deleted else self.entries.get(body["imageId"])
        if entry is None:
            raise ServiceError("IMAGE_NOT_FOUND", "画像を再登録してください", 404, True)
        if entry.revision != body["sourceRevision"]:
            raise ServiceError("REVISION_MISMATCH", "画像の世代が一致しません", 409)
        points = body["points"]
        height, width = entry.rgb.shape[:2]
        if not points or len(points) > 64 or not any(p["label"] == 1 for p in points):
            raise ServiceError("INVALID_ARGUMENT", "正クリックを含む1～64個の点が必要です")
        for p in points:
            if not (0 <= p["x"] < width and 0 <= p["y"] < height):
                raise ServiceError("INVALID_ARGUMENT", "クリック座標が画像外です")
        entry.used = self.clock()
        self.entries.move_to_end(body["imageId"])
        masks, scores, entry.embedding = self.backend.predict(entry.rgb, entry.embedding, points)
        candidates = []
        for mask, score in zip(masks, scores):
            if mask.shape != (height, width) or not math.isfinite(float(score)):
                raise ServiceError("INFERENCE_FAILED", "推論結果の形式が不正です", 500)
            if not mask.any():
                continue
            buffer = BytesIO()
            Image.fromarray(np.asarray(mask, dtype=np.uint8) * 255).save(buffer, format="PNG")
            candidates.append(
                {
                    "candidateId": str(uuid.uuid4()),
                    "score": float(score),
                    "maskPngBase64": base64.b64encode(buffer.getvalue()).decode("ascii"),
                }
            )
        with self.delete_lock:
            if body["imageId"] in self.deleted:
                raise ServiceError("IMAGE_NOT_FOUND", "画像は解放されました", 404)
        candidates.sort(key=lambda c: -c["score"])
        if not candidates:
            raise ServiceError(
                "NO_RESULT", "対象が見つかりません。クリック位置を補正してください", 422, True
            )
        return {
            "imageId": body["imageId"],
            "sourceRevision": entry.revision,
            "requestId": body["requestId"],
            "width": width,
            "height": height,
            "deviceUsed": self.backend.device,
            "candidates": candidates[:3],
        }

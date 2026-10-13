"""MobileSAM adapter. Optional dependencies load only when a valid model is configured."""

import hashlib
import json
from pathlib import Path

import numpy as np

from .errors import ServiceError


class MobileSamBackend:
    def __init__(self, manifest):
        self.device = "cpu"
        self.model_version = None
        self.ready = False
        self.generation = 0
        self.manifest = Path(manifest) if manifest else None
        self.predictor = None

    def initialize(self):
        try:
            if self.manifest is None:
                raise ValueError("model manifest not configured")
            metadata = json.loads(self.manifest.read_text())
            if metadata["modelType"] != "vit_t":
                raise ValueError("unsupported model type")
            weights = (self.manifest.parent / metadata["relativeWeightsPath"]).resolve()
            if not weights.is_relative_to(self.manifest.parent.resolve()):
                raise ValueError("weights must be inside model directory")
            digest = hashlib.sha256(weights.read_bytes()).hexdigest()
            if digest != metadata["sha256"]:
                raise ValueError("model hash mismatch")
            import torch
            from mobile_sam import SamPredictor, sam_model_registry

            self.torch, self.registry, self.predictor_type = torch, sam_model_registry, SamPredictor
            self.weights = weights
            self.device = "mps" if torch.backends.mps.is_available() else "cpu"
            self.model_version = digest
            try:
                self._load_and_warm()
            except Exception:
                if self.device != "mps":
                    raise
                self._cpu()
            self.ready = True
        except Exception as exc:
            self.predictor = None
            self.ready = False
            raise ServiceError(
                "MODEL_INIT_FAILED", "モデルの配置と依存ライブラリを確認してください", 503
            ) from exc

    def _load_and_warm(self):
        model = self.registry["vit_t"](checkpoint=str(self.weights))
        model.to(device=self.device).eval()
        self.predictor = self.predictor_type(model)
        with self.torch.inference_mode():
            self.predictor.set_image(np.zeros((64, 64, 3), dtype=np.uint8))
            self.predictor.predict(
                point_coords=np.array([[32, 32]]), point_labels=np.array([1]), multimask_output=True
            )
            self.predictor.reset_image()

    def _cpu(self):
        self.predictor = None
        self.device = "cpu"
        self.generation += 1
        self._load_and_warm()

    def _encode(self, rgb):
        with self.torch.inference_mode():
            self.predictor.set_image(rgb)
            return {
                "features": self.predictor.features.detach().cpu(),
                "input_size": self.predictor.input_size,
                "original_size": self.predictor.original_size,
                "generation": self.generation,
            }

    def encode(self, rgb):
        if not self.ready:
            raise ServiceError("MODEL_NOT_READY", "AIの準備が完了していません", 503, True)
        try:
            return self._encode(rgb)
        except Exception as exc:
            if self.device == "mps":
                try:
                    self._cpu()
                    return self._encode(rgb)
                except Exception as retry:
                    raise ServiceError(
                        "INFERENCE_FAILED", "CPUでの画像解析に失敗しました", 500, True
                    ) from retry
            raise ServiceError("INFERENCE_FAILED", "画像解析に失敗しました", 500, True) from exc

    def _predict(self, rgb, embedding, points):
        if embedding["generation"] != self.generation:
            embedding = self._encode(rgb)
        with self.torch.inference_mode():
            self.predictor.features = embedding["features"].to(self.device)
            self.predictor.input_size = embedding["input_size"]
            self.predictor.original_size = embedding["original_size"]
            self.predictor.is_image_set = True
            masks, scores, _ = self.predictor.predict(
                point_coords=np.array([[p["x"], p["y"]] for p in points], dtype=np.float32),
                point_labels=np.array([p["label"] for p in points], dtype=np.int64),
                multimask_output=True,
            )
        return masks, scores, embedding

    def predict(self, rgb, embedding, points):
        try:
            return self._predict(rgb, embedding, points)
        except Exception as exc:
            if self.device == "mps":
                try:
                    self._cpu()
                    return self._predict(rgb, embedding, points)
                except Exception as retry:
                    raise ServiceError(
                        "INFERENCE_FAILED", "CPUでの推論に失敗しました", 500, True
                    ) from retry
            raise ServiceError("INFERENCE_FAILED", "推論に失敗しました", 500, True) from exc

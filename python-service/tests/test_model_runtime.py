"""Model orchestration tests with fake tensors/devices; never download weights or import PyTorch."""

import hashlib
import json
import tempfile
import types
import unittest
from contextlib import nullcontext
from pathlib import Path
from unittest.mock import patch

import numpy as np
from editor_helper.errors import ServiceError
from editor_helper.model_runtime import MobileSamBackend
from support import FakeTensor as Tensor


class Model:
    def __init__(self, fail_devices):
        self.fail_devices = fail_devices

    def to(self, device):
        self.device = device
        if device in self.fail_devices:
            raise RuntimeError("injected device load failure")
        return self

    def eval(self):
        return self


class Predictor:
    def __init__(self, model):
        self.model = model
        self.features = Tensor()
        self.set_calls = 0

    def set_image(self, rgb):
        self.set_calls += 1
        self.original_size = rgb.shape[:2]
        self.input_size = rgb.shape[:2]

    def reset_image(self):
        self.is_image_set = False

    def predict(self, **kwargs):
        self.last_prompt = kwargs
        return np.ones((3, *self.original_size), dtype=bool), np.array([0.1, 0.2, 0.3]), None


class RuntimeTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        (self.root / "weights.pt").write_bytes(b"fixture weights, not a model")
        self.manifest = self.root / "manifest.json"
        self.metadata = {
            "modelType": "vit_t",
            "relativeWeightsPath": "weights.pt",
            "sha256": hashlib.sha256((self.root / "weights.pt").read_bytes()).hexdigest(),
        }
        self.save()
        self.models = []

    def tearDown(self):
        self.directory.cleanup()

    def save(self):
        self.manifest.write_text(json.dumps(self.metadata))

    def modules(self, mps=False, fail_devices=()):
        torch = types.ModuleType("torch")
        torch.backends = types.SimpleNamespace(mps=types.SimpleNamespace(is_available=lambda: mps))
        torch.inference_mode = nullcontext

        def factory(checkpoint):
            self.assertEqual(checkpoint, str((self.root / "weights.pt").resolve()))
            model = Model(fail_devices)
            self.models.append(model)
            return model

        mobile = types.ModuleType("mobile_sam")
        mobile.sam_model_registry = {"vit_t": factory}
        mobile.SamPredictor = Predictor
        return patch.dict("sys.modules", {"torch": torch, "mobile_sam": mobile})

    def assert_code(self, fn, code):
        with self.assertRaises(ServiceError) as cm:
            fn()
        self.assertEqual(cm.exception.code, code)

    def test_cpu_load_warm_encode_and_predict_reuses_embedding(self):
        with self.modules():
            backend = MobileSamBackend(self.manifest)
            backend.initialize()
            self.assertTrue(backend.ready)
            self.assertEqual(backend.device, "cpu")
            rgb = np.zeros((3, 4, 3), dtype=np.uint8)
            embedding = backend.encode(rgb)
            self.assertEqual(embedding["original_size"], (3, 4))
            before = backend.predictor.set_calls
            masks, scores, used = backend.predict(rgb, embedding, [{"x": 1, "y": 2, "label": 1}])
            self.assertIs(embedding, used)
            self.assertEqual(backend.predictor.set_calls, before)
            self.assertEqual(masks.shape, (3, 3, 4))
            self.assertEqual(backend.predictor.last_prompt["point_coords"].tolist(), [[1.0, 2.0]])

    def test_mps_startup_failure_reloads_cpu_once(self):
        with self.modules(mps=True, fail_devices=("mps",)):
            backend = MobileSamBackend(self.manifest)
            backend.initialize()
            self.assertEqual(backend.device, "cpu")
            self.assertEqual(backend.generation, 1)
            self.assertEqual(len(self.models), 2)

    def test_mps_success_and_stale_embedding_is_recomputed(self):
        with self.modules(mps=True):
            backend = MobileSamBackend(self.manifest)
            backend.initialize()
            self.assertEqual(backend.device, "mps")
            rgb = np.zeros((3, 4, 3), dtype=np.uint8)
            old = backend.encode(rgb)
            backend._cpu()
            _, _, current = backend.predict(rgb, old, [{"x": 1, "y": 1, "label": 1}])
            self.assertEqual(current["generation"], 1)
            self.assertIsNot(current, old)
            self.assertEqual(current["features"].device, "cpu")

    def test_cpu_load_failure_does_not_retry(self):
        with self.modules(fail_devices=("cpu",)):
            backend = MobileSamBackend(self.manifest)
            self.assert_code(backend.initialize, "MODEL_INIT_FAILED")
            self.assertFalse(backend.ready)
            self.assertIsNone(backend.predictor)
            self.assertEqual(len(self.models), 1)

    def test_invalid_manifests_fail_before_loading_model(self):
        cases = [{"modelType": "bad"}, {"relativeWeightsPath": "../escape.pt"}, {"sha256": "bad"}]
        for changes in cases:
            with self.subTest(changes=changes):
                original = dict(self.metadata)
                self.metadata.update(changes)
                self.save()
                self.assert_code(MobileSamBackend(self.manifest).initialize, "MODEL_INIT_FAILED")
                self.metadata = original
        self.manifest.write_text("{")
        self.assert_code(MobileSamBackend(self.manifest).initialize, "MODEL_INIT_FAILED")
        self.assert_code(MobileSamBackend(None).initialize, "MODEL_INIT_FAILED")

    def test_encode_requires_ready(self):
        self.assert_code(lambda: MobileSamBackend(None).encode(None), "MODEL_NOT_READY")

    def test_encode_mps_failure_and_cpu_retry(self):
        with self.modules(mps=True):
            backend = MobileSamBackend(self.manifest)
            backend.initialize()
            with patch.object(
                backend, "_encode", side_effect=[RuntimeError("mps"), {"cpu": "embedding"}]
            ) as encode:
                self.assertEqual(backend.encode(None), {"cpu": "embedding"})
                self.assertEqual(encode.call_count, 2)
            self.assertEqual(backend.device, "cpu")

    def test_encode_double_failure_and_cpu_failure(self):
        with self.modules(mps=True):
            backend = MobileSamBackend(self.manifest)
            backend.initialize()
            with patch.object(backend, "_encode", side_effect=RuntimeError("injected")) as encode:
                self.assert_code(lambda: backend.encode(None), "INFERENCE_FAILED")
                self.assertEqual(encode.call_count, 2)
            with patch.object(backend, "_encode", side_effect=RuntimeError("cpu")) as encode:
                self.assert_code(lambda: backend.encode(None), "INFERENCE_FAILED")
                self.assertEqual(encode.call_count, 1)

    def test_predict_mps_failure_then_real_adapter_cpu_retry(self):
        with self.modules(mps=True):
            backend = MobileSamBackend(self.manifest)
            backend.initialize()
            rgb = np.zeros((3, 4, 3), dtype=np.uint8)
            embedding = backend.encode(rgb)
            original = backend._predict

            def fail_once(*args):
                if backend.device == "mps":
                    raise RuntimeError("injected mps failure")
                return original(*args)

            with patch.object(backend, "_predict", side_effect=fail_once):
                masks, _, new = backend.predict(rgb, embedding, [{"x": 1, "y": 1, "label": 1}])
            self.assertEqual(backend.device, "cpu")
            self.assertEqual(masks.shape, (3, 3, 4))
            self.assertEqual(new["generation"], 1)

    def test_predict_double_failure_is_typed(self):
        with self.modules(mps=True):
            backend = MobileSamBackend(self.manifest)
            backend.initialize()
            with patch.object(backend, "_predict", side_effect=RuntimeError("both")) as predict:
                self.assert_code(lambda: backend.predict(None, None, None), "INFERENCE_FAILED")
                self.assertEqual(predict.call_count, 2)

            with patch.object(backend, "_predict", side_effect=RuntimeError("cpu")) as predict:
                self.assert_code(lambda: backend.predict(None, None, None), "INFERENCE_FAILED")
                self.assertEqual(predict.call_count, 1)

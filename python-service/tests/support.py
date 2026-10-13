"""Small deterministic fixtures for API and cache tests."""

import uuid
from io import BytesIO

import numpy as np
from PIL import Image


class FakeBackend:
    device = "cpu"
    ready = False
    model_version = "test-model"

    def __init__(self):
        self.encoded = 0

    def initialize(self):
        self.ready = True

    def encode(self, rgb):
        self.encoded += 1
        return {"features": np.ones((4, 4), dtype=np.float32), "generation": 0}

    def predict(self, rgb, embedding, points):
        h, w = rgb.shape[:2]
        masks = np.zeros((3, h, w), dtype=bool)
        masks[0, : h // 2, :] = True
        masks[1, :, : w // 2] = True
        masks[2, :, :] = True
        return masks, np.array([0.2, 0.9, 0.5]), embedding


def png(w=6, h=4):
    b = BytesIO()
    Image.new("RGBA", (w, h), (255, 0, 0, 128)).save(b, "PNG")
    return b.getvalue()


def headers(request_id=None):
    return {
        "Authorization": "Bearer secret",
        "X-Api-Version": "1",
        "X-Request-Id": request_id or str(uuid.uuid4()),
    }


class FakeTensor:
    def detach(self):
        return self

    def cpu(self):
        return self

    def to(self, device):
        self.device = device
        return self

    def nelement(self):
        return 4

    def element_size(self):
        return 4

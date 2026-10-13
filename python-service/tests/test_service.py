import asyncio
import json
import threading
import time
import unittest
import uuid
import zipfile
from io import BytesIO
from unittest.mock import patch

import numpy as np
from editor_helper.api import WorkQueue, create_app
from editor_helper.errors import ServiceError
from editor_helper.image_cache import ImageCache
from editor_helper.psd_importer import import_psd
from fastapi.testclient import TestClient
from PIL import Image
from psd_tools import PSDImage
from psd_tools.api.layers import Group, PixelLayer
from psd_tools.constants import BlendMode
from support import FakeBackend, headers, png


class ServiceTests(unittest.TestCase):
    def setUp(self):
        self.backend = FakeBackend()
        self.app = create_app("secret", backend=self.backend)
        self.context = TestClient(self.app)
        self.client = self.context.__enter__()
        # Lifespan starts the initialization asynchronously.
        for _ in range(100):
            if self.client.get("/health", headers=headers()).json()["aiReady"]:
                break
            time.sleep(0.01)

    def tearDown(self):
        self.context.__exit__(None, None, None)

    def register(self):
        r = self.client.post(
            "/image",
            headers=headers(),
            files={"image": ("image.png", png(), "image/png")},
            data={"sourceRevision": "rev-1"},
        )
        self.assertEqual(r.status_code, 201, r.text)
        return r.json()

    def test_auth_and_version(self):
        self.assertEqual(self.client.get("/health").status_code, 401)
        h = headers()
        h["X-Api-Version"] = "2"
        r = self.client.get("/health", headers=h)
        self.assertEqual(r.status_code, 426)
        self.assertEqual(r.json()["error"]["code"], "API_VERSION_UNSUPPORTED")

    def test_real_png_registration_and_candidates(self):
        registered = self.register()
        rid = str(uuid.uuid4())
        body = {
            "imageId": registered["imageId"],
            "sourceRevision": "rev-1",
            "requestId": rid,
            "points": [{"x": 2, "y": 1, "label": 1}],
        }
        for _ in range(2):
            r = self.client.post("/segment", headers=headers(rid), json=body)
            self.assertEqual(r.status_code, 200, r.text)
            self.assertEqual([c["score"] for c in r.json()["candidates"]], [0.9, 0.5, 0.2])
        self.assertEqual(self.backend.encoded, 1)
        self.assertEqual(
            self.client.delete("/image/" + registered["imageId"], headers=headers()).status_code,
            204,
        )
        self.assertEqual(
            self.client.post("/segment", headers=headers(rid), json=body).status_code, 404
        )

    def test_invalid_points_revision_and_json(self):
        registered = self.register()
        rid = str(uuid.uuid4())
        body = {
            "imageId": registered["imageId"],
            "sourceRevision": "wrong",
            "requestId": rid,
            "points": [{"x": 2, "y": 1, "label": 1}],
        }
        self.assertEqual(
            self.client.post("/segment", headers=headers(rid), json=body).status_code, 409
        )
        body["sourceRevision"] = "rev-1"
        body["points"][0]["x"] = 99
        self.assertEqual(
            self.client.post("/segment", headers=headers(rid), json=body).status_code, 400
        )
        body["points"][0]["x"] = True
        self.assertEqual(
            self.client.post("/segment", headers=headers(rid), json=body).status_code, 422
        )
        self.assertEqual(
            self.client.post("/segment", headers=headers(), content=b"{").json()["error"]["code"],
            "INVALID_ARGUMENT",
        )

    def test_no_model_degrades_without_crashing(self):
        with TestClient(create_app("secret")) as client:
            for _ in range(100):
                state = client.get("/health", headers=headers()).json()
                if state["aiError"]:
                    break
                time.sleep(0.01)
            self.assertFalse(state["aiReady"])
            self.assertTrue(state["psdReady"])
            r = client.post(
                "/image",
                headers=headers(),
                files={"image": ("a.png", png(), "image/png")},
                data={"sourceRevision": "r"},
            )
            self.assertEqual(r.status_code, 503)

    def test_invalid_psd_is_typed(self):
        r = self.client.post("/psd/import", headers=headers(), files={"file": ("bad.psd", b"bad")})
        self.assertEqual(r.status_code, 415)
        self.assertEqual(r.json()["error"]["code"], "UNSUPPORTED_FORMAT")


class CacheTests(unittest.TestCase):
    def test_lru_and_expiry(self):
        now = [0]
        b = FakeBackend()
        b.initialize()
        cache = ImageCache(b, max_entries=2, ttl=10, clock=lambda: now[0])
        a = cache.register(png(), "1")["imageId"]
        c = cache.register(png(), "2")["imageId"]
        cache.register(png(), "3")
        self.assertNotIn(a, cache.entries)
        self.assertIn(c, cache.entries)
        now[0] = 11
        cache._expire()
        self.assertEqual(len(cache.entries), 0)

    def test_empty_mask_and_budget(self):
        b = FakeBackend()
        b.initialize()
        cache = ImageCache(b, budget=1)
        with self.assertRaises(ServiceError) as cm:
            cache.register(png(), "r")
        self.assertEqual(cm.exception.code, "RESOURCE_LIMIT")
        cache = ImageCache(b)
        registered = cache.register(png(), "r")
        b.predict = lambda rgb, embedding, points: (
            np.zeros((1, 4, 6), dtype=bool),
            np.array([0.5]),
            embedding,
        )
        with self.assertRaises(ServiceError) as cm:
            cache.segment(
                {
                    "imageId": registered["imageId"],
                    "sourceRevision": "r",
                    "requestId": str(uuid.uuid4()),
                    "points": [{"x": 0, "y": 0, "label": 1}],
                }
            )
        self.assertEqual(cm.exception.code, "NO_RESULT")


class QueueTests(unittest.TestCase):
    def test_timeout_does_not_release_running_slot(self):
        async def exercise():
            queue = WorkQueue(0)
            event = threading.Event()
            try:
                with self.assertRaises(ServiceError) as cm:
                    await queue.run(lambda: event.wait(1), seconds=0.02)
                self.assertEqual(cm.exception.code, "TIMEOUT")
                with self.assertRaises(ServiceError) as cm:
                    await queue.run(lambda: None)
                self.assertEqual(cm.exception.code, "BUSY")
            finally:
                event.set()
                queue.close()

        asyncio.run(exercise())


class PsdTests(unittest.TestCase):
    def archive(self, psd):
        b = BytesIO()
        psd.save(b, encoding="utf-8")
        return zipfile.ZipFile(BytesIO(import_psd(b.getvalue())))

    def test_plain_layers_preserve_raw_alpha_opacity_and_order(self):
        psd = PSDImage.new("RGBA", (8, 6))
        a = PixelLayer.frompil(
            Image.new("RGBA", (2, 2), (255, 0, 0, 128)), psd, name="下層", left=-1, top=1
        )
        a.opacity = 128
        b = PixelLayer.frompil(Image.new("RGBA", (2, 2), (0, 0, 255, 255)), psd, name="上層")
        b.visible = False
        a.name = "下層"
        b.name = "上層"
        with self.archive(psd) as archive:
            manifest = json.loads(archive.read("manifest.json"))
            self.assertEqual([n["name"] for n in manifest["nodes"]], ["下層", "上層"])
            self.assertEqual(manifest["nodes"][0]["x"], -1)
            self.assertFalse(manifest["nodes"][1]["effectiveVisible"])
            self.assertAlmostEqual(manifest["nodes"][0]["opacity"], 128 / 255)
            asset = manifest["assets"][0]
            image = Image.open(BytesIO(archive.read(asset["path"])))
            self.assertEqual(image.getpixel((0, 0)), (255, 0, 0, 128))

    def test_group_opacity_fallback_applies_once(self):
        psd = PSDImage.new("RGBA", (4, 4))
        group = Group.new(psd, "group")
        group.blend_mode = BlendMode.NORMAL
        group.opacity = 128
        PixelLayer.frompil(Image.new("RGBA", (4, 4), (255, 0, 0, 255)), group, name="red")
        PixelLayer.frompil(Image.new("RGBA", (4, 4), (0, 0, 255, 255)), group, name="blue")
        with self.archive(psd) as archive:
            manifest = json.loads(archive.read("manifest.json"))
            render = [
                n
                for n in manifest["nodes"]
                if n["importDisposition"] in ("RASTER", "REPLACEMENT_RASTER")
            ]
            self.assertEqual(len(render), 1)
            self.assertTrue(manifest["warnings"])
            image = Image.open(BytesIO(archive.read(manifest["assets"][0]["path"])))
            self.assertLessEqual(abs(image.getpixel((1, 1))[3] - 128), 1)

    def test_local_composite_preserves_siblings_and_coordinates(self):
        psd = PSDImage.new("RGBA", (12, 10))
        PixelLayer.frompil(Image.new("RGBA", (2, 2), "green"), psd, name="outside")
        group = Group.new(psd, "isolated")
        group.blend_mode = BlendMode.NORMAL
        group.opacity = 128
        PixelLayer.frompil(Image.new("RGBA", (3, 2), "red"), group, left=4, top=3)
        with self.archive(psd) as archive:
            manifest = json.loads(archive.read("manifest.json"))
            nodes = {n["name"]: n for n in manifest["nodes"]}
            self.assertEqual(nodes["outside"]["importDisposition"], "RASTER")
            replacement = nodes["isolated"]
            self.assertEqual(replacement["importDisposition"], "REPLACEMENT_RASTER")
            self.assertEqual((replacement["x"], replacement["y"]), (4, 3))
            self.assertEqual(replacement["opacity"], 1)
            self.assertAlmostEqual(replacement["originalMetadata"]["opacity"], 128 / 255)
            asset = next(a for a in manifest["assets"] if a["assetId"] == replacement["assetId"])
            image = Image.open(BytesIO(archive.read(asset["path"])))
            self.assertEqual(image.size, (3, 2))
            self.assertLessEqual(abs(image.getpixel((0, 0))[3] - 128), 1)

    def test_nested_scopes_are_merged_and_hidden_group_retains_pixels(self):
        psd = PSDImage.new("RGBA", (6, 6))
        outer = Group.new(psd, "outer")
        outer.blend_mode = BlendMode.NORMAL
        outer.visible = False
        inner = Group.new(outer, "inner")
        inner.blend_mode = BlendMode.NORMAL
        PixelLayer.frompil(Image.new("RGBA", (2, 2), "red"), inner, name="visible")
        hidden = PixelLayer.frompil(Image.new("RGBA", (2, 2), "blue"), inner, name="hidden")
        hidden.visible = False
        with self.archive(psd) as archive:
            manifest = json.loads(archive.read("manifest.json"))
            self.assertEqual(len(manifest["assets"]), 1)
            replacement = manifest["nodes"][0]
            self.assertFalse(replacement["effectiveVisible"])
            self.assertTrue(
                all(n["importDisposition"] == "METADATA_ONLY" for n in manifest["nodes"][1:])
            )
            image = Image.open(BytesIO(archive.read(manifest["assets"][0]["path"])))
            self.assertEqual(image.getpixel((0, 0)), (255, 0, 0, 255))

    def test_backdrop_dependency_ascends_to_isolated_parent(self):
        for isolated in (False, True):
            with self.subTest(isolated=isolated):
                psd = PSDImage.new("RGBA", (4, 4))
                parent = Group.new(psd, "parent")
                parent.blend_mode = BlendMode.NORMAL if isolated else BlendMode.PASS_THROUGH
                child = PixelLayer.frompil(Image.new("RGBA", (2, 2), "red"), parent)
                child.blend_mode = BlendMode.MULTIPLY
                PixelLayer.frompil(Image.new("RGBA", (2, 2), "green"), psd, name="outside")
                with self.archive(psd) as archive:
                    manifest = json.loads(archive.read("manifest.json"))
                    replacement = next(
                        n
                        for n in manifest["nodes"]
                        if n["importDisposition"] == "REPLACEMENT_RASTER"
                    )
                    self.assertEqual(replacement["name"], "parent" if isolated else "PSD composite")
                    outside = next(n for n in manifest["nodes"] if n["name"] == "outside")
                    self.assertEqual(
                        outside["importDisposition"], "RASTER" if isolated else "METADATA_ONLY"
                    )

    def test_raw_extraction_failure_recovers_with_warning(self):
        psd = PSDImage.new("RGBA", (4, 4))
        layer = PixelLayer.frompil(Image.new("RGBA", (2, 2), "red"), psd)
        layer.opacity = 128
        with patch.object(PixelLayer, "topil", return_value=None), self.archive(psd) as archive:
            manifest = json.loads(archive.read("manifest.json"))
            self.assertEqual(manifest["warnings"][0]["code"], "EXTRACTION_FAILED")
            self.assertEqual(manifest["nodes"][0]["opacity"], 1)
            image = Image.open(BytesIO(archive.read(manifest["assets"][0]["path"])))
            self.assertLessEqual(abs(image.getpixel((0, 0))[3] - 128), 1)

    def test_empty_isolated_group_keeps_metadata_without_asset(self):
        psd = PSDImage.new("RGBA", (4, 4))
        group = Group.new(psd, "empty")
        group.blend_mode = BlendMode.NORMAL
        with self.archive(psd) as archive:
            manifest = json.loads(archive.read("manifest.json"))
            self.assertEqual(manifest["assets"], [])
            self.assertEqual(manifest["nodes"][0]["importDisposition"], "EMPTY_METADATA")


if __name__ == "__main__":
    unittest.main()

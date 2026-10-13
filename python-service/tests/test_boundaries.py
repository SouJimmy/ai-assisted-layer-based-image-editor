import asyncio
from io import BytesIO
import json
from pathlib import Path
import tempfile
import threading
import time
import types
import unittest
from unittest.mock import patch
import uuid
import zipfile
import numpy as np
from PIL import Image
from fastapi.testclient import TestClient
from psd_tools import PSDImage
from psd_tools.api.layers import Group,PixelLayer
from psd_tools.constants import BlendMode
from editor_helper import api
from editor_helper.api import create_app,WorkQueue
from editor_helper.errors import ServiceError
from editor_helper.image_cache import ImageCache,decode_png,dimensions
from editor_helper.psd_importer import import_psd
from test_service import FakeBackend,png,headers

class ImageBoundaryTests(unittest.TestCase):
    def error_code(self,fn,code):
        with self.assertRaises(ServiceError) as cm:fn()
        self.assertEqual(cm.exception.code,code)
    def test_dimensions_and_non_png_or_truncated_data(self):
        for w,h in [(0,1),(1,0),(16385,1),(16384,16384)]:self.error_code(lambda:dimensions(w,h),'RESOURCE_LIMIT')
        for fmt in ['JPEG','GIF']:
            b=BytesIO();Image.new('RGB',(2,2)).save(b,fmt);self.error_code(lambda:decode_png(b.getvalue()),'INVALID_IMAGE')
        self.error_code(lambda:decode_png(b'not a png'),'INVALID_IMAGE')
        self.error_code(lambda:decode_png(png()[:40]),'INVALID_IMAGE')
    def test_transparent_pixels_use_white_ai_background(self):
        b=BytesIO();Image.new('RGBA',(2,2),(255,0,0,0)).save(b,'PNG')
        self.assertTrue(np.all(decode_png(b.getvalue())==255))
    def test_invalid_prompts_and_predictions(self):
        backend=FakeBackend();backend.initialize();cache=ImageCache(backend);image_id=cache.register(png(),'r')['imageId']
        body={'imageId':image_id,'sourceRevision':'r','requestId':'test','points':[]}
        for points in [[],[{'x':1,'y':1,'label':0}],[{'x':1,'y':1,'label':1}]*65]:
            body['points']=points;self.error_code(lambda:cache.segment(body),'INVALID_ARGUMENT')
        body['points']=[{'x':1,'y':1,'label':1}]
        for mask,score in [(np.ones((1,1),bool),.5),(np.ones((4,6),bool),float('nan'))]:
            backend.predict=lambda *args:(np.array([mask]),np.array([score]),args[1])
            self.error_code(lambda:cache.segment(body),'INFERENCE_FAILED')
    def test_delete_during_inference_discards_results(self):
        backend=FakeBackend();backend.initialize();cache=ImageCache(backend);image_id=cache.register(png(),'r')['imageId'];original=backend.predict
        def predict(*args):cache.mark_deleted(image_id);return original(*args)
        backend.predict=predict
        self.error_code(lambda:cache.segment({'imageId':image_id,'sourceRevision':'r','requestId':'test','points':[{'x':1,'y':1,'label':1}]}),'IMAGE_NOT_FOUND')
    def test_lru_refresh_prevents_recent_image_eviction(self):
        backend=FakeBackend();backend.initialize();cache=ImageCache(backend)
        a=cache.register(png(),'a')['imageId'];b=cache.register(png(),'b')['imageId']
        cache.segment({'imageId':a,'sourceRevision':'a','requestId':'test','points':[{'x':0,'y':0,'label':1}]})
        cache.register(png(),'c');self.assertIn(a,cache.entries);self.assertNotIn(b,cache.entries)
        cache.mark_deleted('missing');cache.release('missing')
    def test_tensor_size_and_empty_candidates(self):
        from test_model_runtime import Tensor
        backend=FakeBackend();cache=ImageCache(backend);self.assertEqual(cache._size(np.zeros((2,2,3),np.uint8),{'features':Tensor()}),28)
        backend.initialize();image_id=cache.register(png(),'r')['imageId'];original=backend.predict
        def mixed(*args):
            masks,scores,embedding=original(*args);masks[0]=False;return masks,scores,embedding
        backend.predict=mixed
        self.assertEqual(len(cache.segment({'imageId':image_id,'sourceRevision':'r','requestId':'r','points':[{'x':0,'y':0,'label':1}]})['candidates']),2)

class ApiBoundaryTests(unittest.TestCase):
    def ready(self,backend=None):
        backend=backend or FakeBackend();backend.initialize()
        return TestClient(create_app('secret',backend=backend))
    def test_empty_token_and_bad_request_id(self):
        with self.assertRaises(ValueError):create_app('')
        with self.ready() as client:
            h=headers();h['X-Request-Id']='bad';self.assertEqual(client.get('/health',headers=h).status_code,400)
            self.assertEqual(client.get('/missing',headers=headers()).status_code,404)
            self.assertEqual(client.post('/health',headers=headers()).status_code,405)
    def test_request_size_limit_before_json_parsing(self):
        with self.ready() as client:
            r=client.post('/segment',headers=headers(),content=b' '*65537)
            self.assertEqual(r.status_code,413);self.assertEqual(r.json()['error']['code'],'RESOURCE_LIMIT')
    def test_missing_file_revision_and_request_id_mismatch(self):
        with self.ready() as client:
            self.assertEqual(client.post('/image',headers=headers(),data={'sourceRevision':'r'}).status_code,400)
            for revision in ['', 'r'*129]:
                self.assertEqual(client.post('/image',headers=headers(),files={'image':('x.png',png())},data={'sourceRevision':revision}).status_code,400)
            body={'imageId':'id','sourceRevision':'r','requestId':str(uuid.uuid4()),'points':[{'x':0,'y':0,'label':1}]}
            self.assertEqual(client.post('/segment',headers=headers(),json=body).status_code,400)
    def test_internal_error_is_sanitized(self):
        backend=FakeBackend();backend.encode=lambda rgb:(_ for _ in ()).throw(RuntimeError('private error contents'))
        with self.ready(backend) as client:
            r=client.post('/image',headers=headers(),files={'image':('x.png',png())},data={'sourceRevision':'r'})
            self.assertEqual(r.status_code,500);self.assertNotIn('private',r.text)
    def test_initialization_exception_is_reported(self):
        backend=FakeBackend();backend.initialize=lambda:(_ for _ in ()).throw(RuntimeError('private'))
        with TestClient(create_app('secret',backend=backend)) as client:
            for _ in range(100):
                health=client.get('/health',headers=headers()).json()
                if health['aiError']:break
                time.sleep(.01)
            self.assertEqual(health['aiError'],'MODEL_INIT_FAILED')
    def test_psd_success_and_missing_upload(self):
        psd=PSDImage.new('RGBA',(2,2));PixelLayer.frompil(Image.new('RGBA',(2,2),(255,0,0,128)),psd,name='sample')
        b=BytesIO();psd.save(b)
        with self.ready() as client:
            self.assertEqual(client.post('/psd/import',headers=headers()).status_code,400)
            r=client.post('/psd/import',headers=headers(),files={'file':('x.psd',b.getvalue())})
            self.assertEqual(r.status_code,200)
            with zipfile.ZipFile(BytesIO(r.content)) as archive:self.assertEqual(json.loads(archive.read('manifest.json'))['canvasWidth'],2)

class QueueBoundaryTests(unittest.TestCase):
    def test_expired_queued_job_is_never_executed(self):
        async def exercise():
            q=WorkQueue(1);started=threading.Event();release=threading.Event();called=[]
            def block():started.set();release.wait(2)
            first=asyncio.create_task(q.run(block,seconds=3))
            try:
                while not started.is_set():await asyncio.sleep(.001)
                with self.assertRaises(ServiceError):await q.run(lambda:called.append(True),seconds=.01)
                release.set();await first
                await asyncio.sleep(.03);self.assertFalse(called)
            finally:release.set();q.close()
        asyncio.run(exercise())
    def test_finished_after_deadline_is_timeout(self):
        async def exercise():
            q=WorkQueue(0)
            try:
                with self.assertRaises(ServiceError):await q.run(lambda:time.sleep(.03),seconds=.005)
                await asyncio.sleep(.05)
                self.assertEqual(await q.run(lambda:'next'),'next')
            finally:q.close()
        asyncio.run(exercise())
    def test_disconnect_cancels_queued_job_without_leaking_slot(self):
        class Request:
            async def is_disconnected(self):return True
        async def exercise():
            q=WorkQueue(1);release=threading.Event();started=threading.Event()
            def block():started.set();release.wait(1)
            first=asyncio.create_task(q.run(block,seconds=2))
            try:
                while not started.is_set():await asyncio.sleep(.001)
                with self.assertRaises(ServiceError) as cm:await q.run(lambda:None,request=Request())
                self.assertEqual(cm.exception.code,'CANCELLED');release.set();await first
                self.assertEqual(await q.run(lambda:'next'),'next')
            finally:release.set();q.close()
        asyncio.run(exercise())
    def test_submit_after_close_releases_slot(self):
        async def exercise():
            q=WorkQueue(0);q.close()
            for _ in range(2):
                with self.assertRaises(RuntimeError):await q.run(lambda:None)
        asyncio.run(exercise())

class PsdBoundaryTests(unittest.TestCase):
    def test_corrupt_psd_is_typed(self):
        with self.assertRaises(ServiceError) as cm:import_psd(b'8BPS\x00\x01'+b'broken')
        self.assertEqual(cm.exception.code,'PSD_PARSE_FAILED')
    def test_layerless_psd_and_pass_through_group(self):
        for grouped in [False,True]:
            psd=PSDImage.new('RGBA',(4,4))
            if grouped:
                parent=Group.new(psd,'hidden');parent.blend_mode=BlendMode.PASS_THROUGH;parent.visible=False
                PixelLayer.frompil(Image.new('RGBA',(2,2),(1,2,3,255)),parent,name='child')
            b=BytesIO();psd.save(b)
            with zipfile.ZipFile(BytesIO(import_psd(b.getvalue()))) as archive:
                manifest=json.loads(archive.read('manifest.json'))
                if grouped:
                    self.assertEqual(len(manifest['assets']),1);self.assertFalse(manifest['nodes'][1]['effectiveVisible'])
                    self.assertEqual(manifest['nodes'][0]['importDisposition'],'GROUP_METADATA')
                else:self.assertEqual(manifest['nodes'][0]['importDisposition'],'REPLACEMENT_RASTER')
    def test_raster_budget_rejects_large_result(self):
        psd=PSDImage.new('RGBA',(4,4));PixelLayer.frompil(Image.new('RGBA',(2,2),(1,2,3,255)),psd,name='sample');b=BytesIO();psd.save(b)
        with patch('editor_helper.psd_importer.MAX_RASTER_BYTES',1):
            with self.assertRaises(ServiceError) as cm:import_psd(b.getvalue())
            self.assertEqual(cm.exception.code,'RESOURCE_LIMIT')
    def test_extraction_failure_is_not_silent(self):
        psd=PSDImage.new('RGBA',(4,4));layer=PixelLayer.frompil(Image.new('RGBA',(2,2),(1,2,3,255)),psd,name='sample')
        with patch('psd_tools.PSDImage.open',return_value=psd),patch.object(PixelLayer,'topil',return_value=None):
            with self.assertRaises(ServiceError) as cm:import_psd(b'8BPS\x00\x01')
            self.assertEqual(cm.exception.code,'PSD_PARSE_FAILED')

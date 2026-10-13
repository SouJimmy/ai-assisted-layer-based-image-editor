import asyncio
from io import BytesIO
import json
import threading
import time
import unittest
import uuid
import zipfile
import numpy as np
from PIL import Image
from fastapi.testclient import TestClient
from psd_tools import PSDImage
from psd_tools.api.layers import PixelLayer,Group
from psd_tools.constants import BlendMode
from editor_helper.api import create_app,WorkQueue
from editor_helper.errors import ServiceError
from editor_helper.image_cache import ImageCache
from editor_helper.psd_importer import import_psd

class FakeBackend:
    device='cpu';ready=False;model_version='test-model'
    def __init__(self): self.encoded=0
    def initialize(self): self.ready=True
    def encode(self,rgb):
        self.encoded+=1
        return {'features':np.ones((4,4),dtype=np.float32),'generation':0}
    def predict(self,rgb,embedding,points):
        h,w=rgb.shape[:2];masks=np.zeros((3,h,w),dtype=bool)
        masks[0,:h//2,:]=True;masks[1,:,:w//2]=True;masks[2,:,:]=True
        return masks,np.array([.2,.9,.5]),embedding

def png(w=6,h=4):
    b=BytesIO();Image.new('RGBA',(w,h),(255,0,0,128)).save(b,'PNG');return b.getvalue()
def headers(request_id=None):
    return {'Authorization':'Bearer secret','X-Api-Version':'1','X-Request-Id':request_id or str(uuid.uuid4())}

class ServiceTests(unittest.TestCase):
    def setUp(self):
        self.backend=FakeBackend();self.app=create_app('secret',backend=self.backend)
        self.context=TestClient(self.app);self.client=self.context.__enter__()
        # Lifespan starts the initialization asynchronously.
        for _ in range(100):
            if self.client.get('/health',headers=headers()).json()['aiReady']: break
            time.sleep(.01)
    def tearDown(self): self.context.__exit__(None,None,None)
    def register(self):
        r=self.client.post('/image',headers=headers(),files={'image':('image.png',png(),'image/png')},data={'sourceRevision':'rev-1'})
        self.assertEqual(r.status_code,201,r.text);return r.json()
    def test_auth_and_version(self):
        self.assertEqual(self.client.get('/health').status_code,401)
        h=headers();h['X-Api-Version']='2';r=self.client.get('/health',headers=h)
        self.assertEqual(r.status_code,426);self.assertEqual(r.json()['error']['code'],'API_VERSION_UNSUPPORTED')
    def test_real_png_registration_and_candidates(self):
        registered=self.register();rid=str(uuid.uuid4())
        body={'imageId':registered['imageId'],'sourceRevision':'rev-1','requestId':rid,'points':[{'x':2,'y':1,'label':1}]}
        for _ in range(2):
            r=self.client.post('/segment',headers=headers(rid),json=body)
            self.assertEqual(r.status_code,200,r.text)
            self.assertEqual([c['score'] for c in r.json()['candidates']],[.9,.5,.2])
        self.assertEqual(self.backend.encoded,1)
        self.assertEqual(self.client.delete('/image/'+registered['imageId'],headers=headers()).status_code,204)
        self.assertEqual(self.client.post('/segment',headers=headers(rid),json=body).status_code,404)
    def test_invalid_points_revision_and_json(self):
        registered=self.register();rid=str(uuid.uuid4())
        body={'imageId':registered['imageId'],'sourceRevision':'wrong','requestId':rid,'points':[{'x':2,'y':1,'label':1}]}
        self.assertEqual(self.client.post('/segment',headers=headers(rid),json=body).status_code,409)
        body['sourceRevision']='rev-1';body['points'][0]['x']=99
        self.assertEqual(self.client.post('/segment',headers=headers(rid),json=body).status_code,400)
        body['points'][0]['x']=True
        self.assertEqual(self.client.post('/segment',headers=headers(rid),json=body).status_code,422)
        self.assertEqual(self.client.post('/segment',headers=headers(),content=b'{').json()['error']['code'],'INVALID_ARGUMENT')
    def test_no_model_degrades_without_crashing(self):
        with TestClient(create_app('secret')) as client:
            for _ in range(100):
                state=client.get('/health',headers=headers()).json()
                if state['aiError']: break
                time.sleep(.01)
            self.assertFalse(state['aiReady']);self.assertTrue(state['psdReady'])
            r=client.post('/image',headers=headers(),files={'image':('a.png',png(),'image/png')},data={'sourceRevision':'r'})
            self.assertEqual(r.status_code,503)
    def test_invalid_psd_is_typed(self):
        r=self.client.post('/psd/import',headers=headers(),files={'file':('bad.psd',b'bad')})
        self.assertEqual(r.status_code,415);self.assertEqual(r.json()['error']['code'],'UNSUPPORTED_FORMAT')

class CacheTests(unittest.TestCase):
    def test_lru_and_expiry(self):
        now=[0];b=FakeBackend();b.initialize();cache=ImageCache(b,max_entries=2,ttl=10,clock=lambda:now[0])
        a=cache.register(png(),'1')['imageId'];c=cache.register(png(),'2')['imageId'];d=cache.register(png(),'3')['imageId']
        self.assertNotIn(a,cache.entries);self.assertIn(c,cache.entries)
        now[0]=11;cache._expire();self.assertEqual(len(cache.entries),0)
    def test_empty_mask_and_budget(self):
        b=FakeBackend();b.initialize();cache=ImageCache(b,budget=1)
        with self.assertRaises(ServiceError) as cm:cache.register(png(),'r')
        self.assertEqual(cm.exception.code,'RESOURCE_LIMIT')
        cache=ImageCache(b);registered=cache.register(png(),'r')
        b.predict=lambda rgb,embedding,points:(np.zeros((1,4,6),dtype=bool),np.array([.5]),embedding)
        with self.assertRaises(ServiceError) as cm:
            cache.segment({'imageId':registered['imageId'],'sourceRevision':'r','requestId':str(uuid.uuid4()),'points':[{'x':0,'y':0,'label':1}]})
        self.assertEqual(cm.exception.code,'NO_RESULT')

class QueueTests(unittest.TestCase):
    def test_timeout_does_not_release_running_slot(self):
        async def exercise():
            queue=WorkQueue(0);event=threading.Event()
            try:
                with self.assertRaises(ServiceError) as cm:await queue.run(lambda:event.wait(1),seconds=.02)
                self.assertEqual(cm.exception.code,'TIMEOUT')
                with self.assertRaises(ServiceError) as cm:await queue.run(lambda:None)
                self.assertEqual(cm.exception.code,'BUSY')
            finally:event.set();queue.close()
        asyncio.run(exercise())

class PsdTests(unittest.TestCase):
    def archive(self,psd):
        b=BytesIO();psd.save(b,encoding="utf-8");return zipfile.ZipFile(BytesIO(import_psd(b.getvalue())))
    def test_plain_layers_preserve_raw_alpha_opacity_and_order(self):
        psd=PSDImage.new('RGBA',(8,6))
        a=PixelLayer.frompil(Image.new('RGBA',(2,2),(255,0,0,128)),psd,name='下層',left=-1,top=1);a.opacity=128
        b=PixelLayer.frompil(Image.new('RGBA',(2,2),(0,0,255,255)),psd,name='上層');b.visible=False;a.name='下層';b.name='上層'
        with self.archive(psd) as archive:
            manifest=json.loads(archive.read('manifest.json'))
            self.assertEqual([n['name'] for n in manifest['nodes']],['下層','上層'])
            self.assertEqual(manifest['nodes'][0]['x'],-1)
            self.assertFalse(manifest['nodes'][1]['effectiveVisible'])
            self.assertAlmostEqual(manifest['nodes'][0]['opacity'],128/255)
            asset=manifest['assets'][0]
            image=Image.open(BytesIO(archive.read(asset['path'])))
            self.assertEqual(image.getpixel((0,0)),(255,0,0,128))
    def test_group_opacity_fallback_applies_once(self):
        psd=PSDImage.new('RGBA',(4,4));group=Group.new(psd,'group');group.blend_mode=BlendMode.NORMAL;group.opacity=128
        PixelLayer.frompil(Image.new('RGBA',(4,4),(255,0,0,255)),group,name='red')
        PixelLayer.frompil(Image.new('RGBA',(4,4),(0,0,255,255)),group,name='blue')
        with self.archive(psd) as archive:
            manifest=json.loads(archive.read('manifest.json'))
            render=[n for n in manifest['nodes'] if n['importDisposition'] in ('RASTER','REPLACEMENT_RASTER')]
            self.assertEqual(len(render),1);self.assertTrue(manifest['warnings'])
            image=Image.open(BytesIO(archive.read(manifest['assets'][0]['path'])))
            self.assertLessEqual(abs(image.getpixel((1,1))[3]-128),1)

class FallbackTests(unittest.TestCase):
    def test_mps_retries_once_on_cpu(self):
        from editor_helper.model_runtime import MobileSamBackend
        backend=MobileSamBackend(None);backend.device='mps';calls=[]
        def predict(rgb,embedding,points):
            calls.append(backend.device)
            if backend.device=='mps': raise RuntimeError('injected MPS failure')
            return 'cpu-result'
        def switch(): backend.device='cpu';backend.generation+=1
        backend._predict=predict;backend._cpu=switch
        self.assertEqual(backend.predict(None,None,None),'cpu-result')
        self.assertEqual(calls,['mps','cpu'])
    def test_cpu_failure_is_not_retried(self):
        from editor_helper.model_runtime import MobileSamBackend
        backend=MobileSamBackend(None);calls=[]
        def predict(*args): calls.append('cpu');raise RuntimeError('injected failure')
        backend._predict=predict
        with self.assertRaises(ServiceError) as cm: backend.predict(None,None,None)
        self.assertEqual(cm.exception.code,'INFERENCE_FAILED');self.assertEqual(calls,['cpu'])
    def test_deleted_cache_entry_cannot_be_used(self):
        backend=FakeBackend();backend.initialize();cache=ImageCache(backend)
        image_id=cache.register(png(),'r')['imageId'];cache.mark_deleted(image_id)
        with self.assertRaises(ServiceError) as cm:
            cache.segment({'imageId':image_id,'sourceRevision':'r','requestId':str(uuid.uuid4()),'points':[{'x':1,'y':1,'label':1}]})
        self.assertEqual(cm.exception.code,'IMAGE_NOT_FOUND')
        cache.release(image_id);self.assertFalse(cache.deleted)

if __name__=='__main__': unittest.main()

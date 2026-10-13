import asyncio
from contextlib import redirect_stdout
from io import StringIO
import json
import os
import runpy
import types
import unittest
from unittest.mock import MagicMock,patch

class EntrypointTests(unittest.TestCase):
    def test_missing_token_fails_before_binding(self):
        from editor_helper.__main__ import main
        with patch.dict(os.environ,{},clear=True),patch('sys.argv',['editor_helper']),patch('sys.stderr',StringIO()):
            with self.assertRaises(SystemExit) as cm:main()
        self.assertEqual(cm.exception.code,2)
    def test_loopback_bind_handshake_and_socket_reuse(self):
        class Server:
            def __init__(self,config):self.started=False
            async def startup(self,sockets=None):self.started=True;self.sockets=sockets
            def run(self,sockets=None):asyncio.run(self.startup(sockets))
        uvicorn=types.ModuleType('uvicorn');uvicorn.Server=Server;uvicorn.Config=lambda app,**kw:kw
        app=types.SimpleNamespace(state=types.SimpleNamespace(instance='fixture-instance'))
        sock=MagicMock();sock.getsockname.return_value=('127.0.0.1',43210)
        socket_factory=MagicMock();socket_factory.return_value.__enter__.return_value=sock
        output=StringIO()
        with patch.dict(os.environ,{'EDITOR_HELPER_TOKEN':'secret'}),patch.dict('sys.modules',{'uvicorn':uvicorn}),\
             patch('socket.socket',socket_factory),patch('editor_helper.api.create_app',return_value=app),\
             patch('sys.argv',['editor_helper','--port','0','--model-manifest','fixture.json']),redirect_stdout(output):
            runpy.run_module('editor_helper.__main__',run_name='__main__')
        event=json.loads(output.getvalue());self.assertEqual(event['event'],'listening');self.assertEqual(event['port'],43210)
        self.assertEqual(event['pid'],os.getpid());self.assertEqual(event['serviceInstanceId'],'fixture-instance')
        sock.bind.assert_called_once_with(('127.0.0.1',0));sock.setblocking.assert_called_once_with(False)

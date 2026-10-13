import argparse
import json
import os
import socket
import sys
import uuid
import uvicorn
from .api import create_app

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--host',choices=['127.0.0.1'],default='127.0.0.1')
    parser.add_argument('--port',type=int,default=0)
    parser.add_argument('--model-manifest')
    args=parser.parse_args()
    token=os.environ.get('EDITOR_HELPER_TOKEN')
    if not token: parser.error('EDITOR_HELPER_TOKEN is required')
    app=create_app(token,args.model_manifest)
    class Server(uvicorn.Server):
        async def startup(self,sockets=None):
            await super().startup(sockets)
            if self.started:
                print(json.dumps({'event':'listening','apiVersion':1,'host':'127.0.0.1',
                    'port':sock.getsockname()[1],'pid':os.getpid(),'serviceInstanceId':app.state.instance}),flush=True)
    with socket.socket(socket.AF_INET,socket.SOCK_STREAM) as sock:
        sock.bind((args.host,args.port));sock.setblocking(False)
        server=Server(uvicorn.Config(app,host=args.host,port=args.port,access_log=False,log_level='warning',timeout_graceful_shutdown=5))
        server.run(sockets=[sock])
if __name__=='__main__': main()

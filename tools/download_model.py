#!/usr/bin/env python3
"""Explicit developer setup; the running service never downloads model weights."""
from pathlib import Path
import hashlib
import json
import urllib.request
ROOT=Path(__file__).resolve().parents[1]/'python-service/models'
manifest=json.loads((ROOT/'model-manifest.json').read_text())
weights=ROOT/manifest['relativeWeightsPath']
if weights.exists() and hashlib.sha256(weights.read_bytes()).hexdigest()==manifest['sha256']:
    print('Model already present and verified')
else:
    with urllib.request.urlopen(manifest['sourceUrl'],timeout=60) as response:
        data=response.read(64*1024*1024+1)
    if len(data)>64*1024*1024 or hashlib.sha256(data).hexdigest()!=manifest['sha256']:
        raise SystemExit('Model size/hash mismatch; no weights were written')
    temporary=weights.with_suffix('.pt.partial')
    temporary.write_bytes(data);temporary.replace(weights)
    print('Model downloaded and verified:',weights)

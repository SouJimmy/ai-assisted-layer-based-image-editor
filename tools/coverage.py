#!/usr/bin/env python3
"""Measure all production Java/Python code; never count test files as production coverage."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import subprocess
import sys
import urllib.request
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser()
parser.add_argument('--baseline',action='store_true',help='Run only the original Java regression and Python test_service suite')
parser.add_argument('--output',default='reports/coverage')
parser.add_argument('--check',action='store_true',help='Enforce Java/Python line >=95%% and branch >=85%%')
args=parser.parse_args()
OUT=ROOT/args.output;OUT.mkdir(parents=True,exist_ok=True)
PYTHON=ROOT/'python-service/.venv/bin/python'
manifest=json.loads((ROOT/'tools/jacoco.json').read_text())
cache=ROOT/'.cache/jacoco';cache.mkdir(parents=True,exist_ok=True)
for item in manifest['artifacts'].values():
    path=cache/item['file']
    if not path.exists():
        data=urllib.request.urlopen(item['url'],timeout=30).read()
        if hashlib.sha256(data).hexdigest()!=item['sha256']:raise SystemExit('JaCoCo checksum mismatch')
        path.write_bytes(data)
    if hashlib.sha256(path.read_bytes()).hexdigest()!=item['sha256']:raise SystemExit('JaCoCo cache checksum mismatch')
agent=cache/manifest['artifacts']['agent']['file'];cli=cache/manifest['artifacts']['cli']['file']
subprocess.run([sys.executable,str(ROOT/'tools/build.py'),'--compile-tests'],cwd=ROOT,check=True)
execution=OUT/'java.exec'
if execution.exists():execution.unlink()
runners=['editor.RegressionTests']
if not args.baseline:runners.append('editor.CoverageTests')
classpath=os.pathsep.join(map(str,[ROOT/'app/build/classes',ROOT/'app/build/test-classes']))
for runner in runners:
    subprocess.run(['java',f'-javaagent:{agent}=destfile={execution},append=true','-Djava.awt.headless=true','-cp',classpath,runner],cwd=ROOT,check=True)
subprocess.run(['java','-jar',str(cli),'report',str(execution),'--classfiles',str(ROOT/'app/build/classes'),
                '--sourcefiles',str(ROOT/'app/src/main/java'),'--html',str(OUT/'java-html'),'--xml',str(OUT/'java.xml')],check=True)
env=dict(os.environ,PYTHONPATH=str(ROOT/'python-service/src'),COVERAGE_FILE=str(OUT/'.coverage'))
pattern='test_service.py' if args.baseline else 'test*.py'
subprocess.run([str(PYTHON),'-m','coverage','run','--branch','--source=editor_helper','-m','unittest','discover','-s','python-service/tests','-p',pattern,'-v'],cwd=ROOT,env=env,check=True)
for action in [['json','-o',str(OUT/'python.json')],['html','-d',str(OUT/'python-html')],['xml','-o',str(OUT/'python.xml')],['report','-m']]:
    subprocess.run([str(PYTHON),'-m','coverage',*action],cwd=ROOT,env=env,check=True)
java=ET.parse(OUT/'java.xml').getroot()
counts={c.attrib['type']:{k:int(c.attrib[k]) for k in ['missed','covered']} for c in java.findall('counter')}
def rate(counter):
    total=counter['covered']+counter['missed'];return 100*counter['covered']/total if total else 100
py=json.loads((OUT/'python.json').read_text())['totals']
summary={'java':{'line':rate(counts['LINE']),'branch':rate(counts['BRANCH']),'counters':counts},
         'python':{'line':100*py['covered_lines']/py['num_statements'],
                   'branch':100*py['covered_branches']/py['num_branches'],'totals':py}}
(OUT/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
print(json.dumps({language:{k:round(v,2) for k,v in data.items() if k in ['line','branch']} for language,data in summary.items()},indent=2))

if args.check:
    failures=[]
    for language,data in summary.items():
        for metric,minimum in [('line',95),('branch',85)]:
            if data[metric]<minimum:failures.append(f'{language} {metric}: {data[metric]:.2f}% < {minimum}%')
    if failures:raise SystemExit('Coverage threshold failed: '+', '.join(failures))
    print('Coverage thresholds passed: all production lines >=95%, branches >=85%')

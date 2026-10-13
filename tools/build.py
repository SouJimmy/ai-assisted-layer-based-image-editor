#!/usr/bin/env python3
"""Dependency-free Java 17 build and B-side regression runner."""
from pathlib import Path
import subprocess
import sys
ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'app/build/classes'
OUT.mkdir(parents=True, exist_ok=True)
sources = sorted((ROOT / 'app/src/main/java').rglob('*.java'))
if '--test' in sys.argv:
    sources += sorted((ROOT / 'app/src/test/java').rglob('*.java'))
subprocess.run(['javac', '--release', '17', '-encoding', 'UTF-8', '-d', str(OUT), *map(str, sources)], check=True)
if '--test' in sys.argv:
    subprocess.run(['java', '-Djava.awt.headless=true', '-cp', str(OUT), 'editor.RegressionTests'], check=True)
else:
    print('Compiled:', OUT)

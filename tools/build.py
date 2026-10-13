#!/usr/bin/env python3
"""Dependency-free Java 17 build. Production and test bytecode stay separate."""
from pathlib import Path
import os
import shutil
import subprocess
import sys
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'app/build/classes'
TEST_OUT=ROOT/'app/build/test-classes'
for directory in [OUT,TEST_OUT]:
    if directory.exists(): shutil.rmtree(directory)
    directory.mkdir(parents=True)
sources=sorted((ROOT/'app/src/main/java').rglob('*.java'))
subprocess.run(['javac','--release','17','-g','-encoding','UTF-8','-d',str(OUT),*map(str,sources)],check=True)
if '--test' in sys.argv or '--compile-tests' in sys.argv:
    tests=sorted((ROOT/'app/src/test/java').rglob('*.java'))
    subprocess.run(['javac','--release','17','-g','-encoding','UTF-8','-cp',str(OUT),'-d',str(TEST_OUT),*map(str,tests)],check=True)
if '--test' in sys.argv:
    runners=['editor.RegressionTests']
    if (TEST_OUT/'editor/CoverageTests.class').exists(): runners.append('editor.CoverageTests')
    for runner in runners:
        subprocess.run(['java','-Djava.awt.headless=true','-cp',os.pathsep.join(map(str,[OUT,TEST_OUT])),runner],check=True)
else: print('Compiled:',OUT)

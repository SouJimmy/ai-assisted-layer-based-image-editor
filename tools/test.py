#!/usr/bin/env python3
"""Run fast tests. Optional --integration additionally starts a loopback service and loads MobileSAM."""

import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PYTHON = ROOT / "python-service/.venv/bin/python"
subprocess.run([sys.executable, str(ROOT / "tools/build.py"), "--test"], cwd=ROOT, check=True)
env = dict(os.environ, PYTHONPATH=str(ROOT / "python-service/src"))
subprocess.run(
    [str(PYTHON), "-m", "unittest", "discover", "-s", "python-service/tests", "-v"],
    cwd=ROOT,
    env=env,
    check=True,
)
if "--integration" in sys.argv:
    (ROOT / "tmp").mkdir(exist_ok=True)
    subprocess.run(
        [
            str(PYTHON),
            "-c",
            "from psd_tools import PSDImage; from psd_tools.api.layers import PixelLayer; from PIL import Image; "
            "p=PSDImage.new('RGBA',(32,32)); PixelLayer.frompil(Image.new('RGBA',(16,16),(255,0,0,128)),p,name='red',left=3,top=4); p.save('tmp/smoke.psd')",
        ],
        cwd=ROOT,
        check=True,
    )
    subprocess.run(
        [
            "java",
            "-Djava.awt.headless=true",
            "-cp",
            os.pathsep.join(
                map(str, [ROOT / "app/build/classes", ROOT / "app/build/test-classes"])
            ),
            "editor.IntegrationSmoke",
            str(ROOT / "python-service"),
            str(ROOT / "tmp/smoke.psd"),
        ],
        cwd=ROOT,
        check=True,
    )

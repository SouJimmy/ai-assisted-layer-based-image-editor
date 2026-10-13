#!/usr/bin/env python3
"""Apply or check the repository's pinned Java/Python formatting rules."""

import argparse
import hashlib
import json
import subprocess
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--check", action="store_true")
args = parser.parse_args()
metadata = json.loads((ROOT / "tools/java-format.json").read_text())
formatter = ROOT / ".cache/java-format" / metadata["file"]
if not formatter.exists():
    data = urllib.request.urlopen(metadata["url"], timeout=30).read()
    if hashlib.sha256(data).hexdigest() != metadata["sha256"]:
        raise SystemExit("Java formatter checksum mismatch")
    formatter.parent.mkdir(parents=True, exist_ok=True)
    formatter.write_bytes(data)
if hashlib.sha256(formatter.read_bytes()).hexdigest() != metadata["sha256"]:
    raise SystemExit("Java formatter cache checksum mismatch")
java_files = sorted((ROOT / "app/src").rglob("*.java"))
java_options = ["--dry-run", "--set-exit-if-changed"] if args.check else ["--replace"]
subprocess.run(
    ["java", "-jar", str(formatter), "--aosp", *java_options, *map(str, java_files)], check=True
)
ruff = ROOT / "python-service/.venv/bin/ruff"
paths = ["python-service/src", "python-service/tests", "tools"]
config = ["--config", "python-service/pyproject.toml"]
subprocess.run([str(ruff), "check", *config, *paths], cwd=ROOT, check=True)
subprocess.run(
    [str(ruff), "format", *config, *(["--check"] if args.check else []), *paths],
    cwd=ROOT,
    check=True,
)

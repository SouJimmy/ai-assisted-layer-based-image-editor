#!/usr/bin/env python3
"""Install locked dependencies into a fresh temporary venv and verify degraded service startup.

Use the existing Python 3.12 interpreter. --wheelhouse installs without network access.
--ai additionally installs the full lock and verifies the configured model.
This does not certify a different OS or a different evaluation PC.
"""

import argparse
import json
import os
import secrets
import selectors
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--wheelhouse", type=Path)
    parser.add_argument(
        "--ai", action="store_true", help="Install full lock and verify real model readiness"
    )
    args = parser.parse_args()
    if args.ai and args.wheelhouse:
        parser.error("--wheelhouse supports the core lock only")
    if sys.version_info[:2] != (3, 12):
        parser.error("Run with Python 3.12")
    with tempfile.TemporaryDirectory(prefix="editor-clean-") as temporary:
        environment = Path(temporary) / "venv"
        subprocess.run([sys.executable, "-m", "venv", str(environment)], check=True)
        python = environment / "bin/python"
        install = [str(python), "-m", "pip", "install"]
        if args.wheelhouse:
            install += ["--no-index", "--find-links", str(args.wheelhouse.resolve())]
        subprocess.run(
            [
                *install,
                "-r",
                str(
                    ROOT
                    / "python-service"
                    / ("requirements.lock" if args.ai else "requirements-core.lock")
                ),
            ],
            check=True,
        )
        subprocess.run([str(python), "-m", "pip", "check"], check=True)
        env = dict(
            os.environ,
            PYTHONPATH=str(ROOT / "python-service/src"),
            PYTHONNOUSERSITE="1",
            EDITOR_HELPER_TOKEN=secrets.token_urlsafe(32),
        )
        env.pop("PYTHONHOME", None)
        subprocess.run(
            [
                str(python),
                "-m",
                "unittest",
                "discover",
                "-s",
                str(ROOT / "python-service/tests"),
                "-q",
            ],
            env=env,
            check=True,
        )
        with (Path(temporary) / "stderr.log").open("w+") as log:
            command = [str(python), "-m", "editor_helper", "--port", "0"]
            if args.ai:
                command += [
                    "--model-manifest",
                    str(ROOT / "python-service/models/model-manifest.json"),
                ]
            process = subprocess.Popen(
                command,
                cwd=temporary,
                env=env,
                stdout=subprocess.PIPE,
                stderr=log,
                text=True,
            )
            try:
                with selectors.DefaultSelector() as selector:
                    selector.register(process.stdout, selectors.EVENT_READ)
                    if not selector.select(timeout=20):
                        raise TimeoutError("Service handshake timeout")
                    handshake = json.loads(process.stdout.readline())
                address = f"http://127.0.0.1:{handshake['port']}/health"
                request = urllib.request.Request(
                    address,
                    headers={
                        "Authorization": "Bearer " + env["EDITOR_HELPER_TOKEN"],
                        "X-Api-Version": "1",
                    },
                )
                deadline = time.monotonic() + (120 if args.ai else 10)
                while True:
                    try:
                        with urllib.request.urlopen(request, timeout=5) as response:
                            health = json.load(response)
                    except TimeoutError:
                        if time.monotonic() >= deadline or process.poll() is not None:
                            raise
                        continue
                    if health["aiReady"] or health["aiError"]:
                        break
                    if time.monotonic() >= deadline:
                        raise TimeoutError("Model readiness state not published")
                    time.sleep(0.05)
                if health["aiReady"] != args.ai or not health["psdReady"]:
                    raise RuntimeError("Unexpected degraded service state")
            finally:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
                    raise RuntimeError("Service did not terminate gracefully")
                process.stdout.close()
        if args.ai:
            subprocess.run([str(python), str(ROOT / "tools/model_smoke.py")], env=env, check=True)
        result = {
            "python": sys.version,
            "installation": "fresh isolated venv",
            "offline": bool(args.wheelhouse),
            "pip_check": "passed",
            "unit_tests": "passed",
            "dynamic_port_startup": "passed",
            "model_unavailable": not health["aiReady"],
            "real_model_inference": "passed" if args.ai else "not requested",
            "psd_ready": health["psdReady"],
            "child_terminated": True,
        }
        output = ROOT / "reports/validation"
        output.mkdir(parents=True, exist_ok=True)
        (
            output / ("clean-environment-ai.json" if args.ai else "clean-environment.json")
        ).write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()

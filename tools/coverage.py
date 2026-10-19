#!/usr/bin/env python3
"""Measure all production Java/Python code; never count test files as production coverage."""

import argparse
import json
import os
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--output", default="reports/coverage")
parser.add_argument(
    "--check", action="store_true", help="Enforce Java/Python line >=95%% and branch >=85%%"
)
args = parser.parse_args()
OUT = ROOT / args.output
OUT.mkdir(parents=True, exist_ok=True)
PYTHON = ROOT / "python-service/.venv/bin/python"
from java_env import environment, gradle

subprocess.run(
    gradle(":processing-backend:test", ":processing-backend:jacocoTestReport"),
    cwd=ROOT,
    env=environment(),
    check=True,
)
java_report = ROOT / "processing-backend/build/reports/jacoco/test/jacocoTestReport.xml"
import shutil

shutil.copyfile(java_report, OUT / "java.xml")
shutil.copytree(java_report.parent / "html", OUT / "java-html", dirs_exist_ok=True)
env = dict(
    os.environ, PYTHONPATH=str(ROOT / "python-service/src"), COVERAGE_FILE=str(OUT / ".coverage")
)
pattern = "test*.py"
subprocess.run(
    [
        str(PYTHON),
        "-m",
        "coverage",
        "run",
        "--branch",
        "--source=editor_helper",
        "-m",
        "unittest",
        "discover",
        "-s",
        "python-service/tests",
        "-p",
        pattern,
        "-v",
    ],
    cwd=ROOT,
    env=env,
    check=True,
)
for action in [
    ["json", "-o", str(OUT / "python.json")],
    ["html", "-d", str(OUT / "python-html")],
    ["xml", "-o", str(OUT / "python.xml")],
    ["report", "-m"],
]:
    subprocess.run([str(PYTHON), "-m", "coverage", *action], cwd=ROOT, env=env, check=True)
java = ET.parse(OUT / "java.xml").getroot()
counts = {
    c.attrib["type"]: {k: int(c.attrib[k]) for k in ["missed", "covered"]}
    for c in java.findall("counter")
}


def rate(counter):
    total = counter["covered"] + counter["missed"]
    return 100 * counter["covered"] / total if total else 100


py = json.loads((OUT / "python.json").read_text())["totals"]
summary = {
    "java": {"line": rate(counts["LINE"]), "branch": rate(counts["BRANCH"]), "counters": counts},
    "python": {
        "line": 100 * py["covered_lines"] / py["num_statements"],
        "branch": 100 * py["covered_branches"] / py["num_branches"],
        "totals": py,
    },
}
(OUT / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
print(
    json.dumps(
        {
            language: {k: round(v, 2) for k, v in data.items() if k in ["line", "branch"]}
            for language, data in summary.items()
        },
        indent=2,
    )
)

if args.check:
    failures = []
    for language, data in summary.items():
        for metric, minimum in [("line", 95), ("branch", 85)]:
            if data[metric] < minimum:
                failures.append(f"{language} {metric}: {data[metric]:.2f}% < {minimum}%")
    if failures:
        raise SystemExit("Coverage threshold failed: " + ", ".join(failures))
    print("Coverage thresholds passed: all production lines >=95%, branches >=85%")

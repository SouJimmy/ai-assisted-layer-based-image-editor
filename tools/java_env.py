"""Use an explicitly configured Java 21, or the optional project-local development JDK."""

import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def environment():
    env = dict(os.environ)
    if not env.get("JAVA_HOME"):
        candidates = sorted((ROOT / ".cache/toolchains").glob("jdk-21*/Contents/Home"))
        if candidates:
            env["JAVA_HOME"] = str(candidates[-1])
    if env.get("JAVA_HOME"):
        env["PATH"] = str(Path(env["JAVA_HOME"]) / "bin") + os.pathsep + env.get("PATH", "")
    return env


def gradle(*tasks):
    return [
        str(ROOT / "gradlew"),
        "--no-daemon",
        "--gradle-user-home",
        str(ROOT / ".cache/gradle"),
        *tasks,
    ]

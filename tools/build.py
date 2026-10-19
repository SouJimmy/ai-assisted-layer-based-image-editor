#!/usr/bin/env python3
"""Build Java 21 modules through the checked-in Gradle Wrapper."""

import subprocess
import sys

from java_env import ROOT, environment, gradle

tasks = [":processing-backend:writeTestClasspath"]
if "--test" in sys.argv:
    tasks.append(":processing-backend:test")
subprocess.run(gradle(*tasks), cwd=ROOT, env=environment(), check=True)

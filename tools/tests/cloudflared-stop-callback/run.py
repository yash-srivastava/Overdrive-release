#!/usr/bin/env python3
"""Execute source-extracted stop callbacks with deterministic local seams; no Android or ADB."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

p = argparse.ArgumentParser()
p.add_argument("--source", type=Path, required=True)
p.add_argument("--work", type=Path, required=True)
p.add_argument("--java-home", type=Path, required=True)
p.add_argument("--compiler-classpath", required=True)
p.add_argument("--stdlib", required=True)
a = p.parse_args()
a.work.mkdir(parents=True, exist_ok=False)

def source(relative, start, end):
    path = a.source / relative
    text = path.read_text()
    first = text.index(start)
    last = text.index(end, first + len(start))
    print(json.dumps({"input": relative, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}), flush=True)
    return text[first:last]

controller = source("app/src/main/java/com/overdrive/app/ui/daemon/CloudflaredController.kt",
                    "    override fun stop(", "    override fun isRunning(")
viewmodel = source("app/src/main/java/com/overdrive/app/ui/viewmodel/DaemonsViewModel.kt",
                   "    fun stopDaemon(", "    fun refreshDaemonStatus(")
fixture = Path(__file__).with_name("Fixture.kt.in").read_text()
# These two explicit negative controls must fail the same behavioral oracle.
clear_url = controller.replace("callback.onError(error)", "_tunnelUrl.postValue(null); callback.onError(error)")
report_stopped = controller.replace("callback.onError(error)",
    'callback.onStatusChanged(DaemonStatus.STOPPED, "Incorrectly stopped"); callback.onError(error)')
if clear_url == controller or report_stopped == controller:
    raise ValueError("negative-control source anchor no longer matches")
body = fixture.replace("@CONTROLLER@", controller).replace("@VIEWMODEL@", viewmodel)
body = body.replace("@BAD_CLEAR@", clear_url).replace("@BAD_STOPPED@", report_stopped)
(a.work / "Fixture.kt").write_text(body)
(a.work / "AndroidClock.kt").write_text(Path(__file__).with_name("AndroidClock.kt.in").read_text())
classes = a.work / "classes"
classes.mkdir()
java = str(a.java_home / "bin/java")
subprocess.run([java, "-Xmx256m", "-cp", a.compiler_classpath,
    "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "11",
    "-classpath", a.stdlib, "-d", str(classes), str(a.work / "Fixture.kt"), str(a.work / "AndroidClock.kt")], check=True)
subprocess.run([java, "-Xmx128m", "-cp", str(classes) + ":" + a.stdlib, "FixtureKt"], check=True)

#!/usr/bin/env python3
"""Configure the real Android build script offline, without executing build tasks.

Run in the same disposable OS sandbox as run.py. Only allowlisted public source
files are copied; local properties, signing material and OAuth files are omitted.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess


CONFIGURATION_INPUTS = (
    "settings.gradle.kts",
    "gradle.properties",
    "gradle/libs.versions.toml",
    "gradle/wrapper/gradle-wrapper.properties",
    "app/build.gradle.kts",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/cpp/CMakeLists.txt",
    "app/src/main/java/com/overdrive/app/logging/DaemonLogConfig.java",
)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--scratch", type=Path, required=True)
    parser.add_argument("--gradle", type=Path, required=True)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--module-cache", type=Path, help="Existing read-only Gradle dependency cache")
    args = parser.parse_args()
    project = args.scratch / "openh264-android-configuration"
    project.mkdir(parents=True, exist_ok=False)
    hashes = {}
    for relative in CONFIGURATION_INPUTS:
        data = (args.source / relative).read_bytes()
        destination = project / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(data)
        hashes[relative] = hashlib.sha256(data).hexdigest()
    (project / "local.properties").write_text("sdk.dir=" + str(args.sdk) + "\n", encoding="utf-8")
    print("ANDROID_CONFIGURATION_PROVENANCE " + json.dumps({
        "source_sha256": hashes,
        "script_changes": [],
        "copied_private_properties_or_signing_material": False,
        "scope": "Actual repository Android/Kotlin plugin configuration; no compile, native or download task executes",
    }), flush=True)
    command = [
        str(args.gradle), "--offline", "--console=plain", "--no-daemon", "--max-workers=1",
        "-Dorg.gradle.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=768m -XX:+UseSerialGC -Dfile.encoding=UTF-8",
        "-Dorg.gradle.java.installations.auto-download=false", ":app:help",
    ]
    env = dict(os.environ)  # The enclosing sandbox starts from an explicit allowlist.
    env["ANDROID_HOME"] = str(args.sdk)
    env["ANDROID_SDK_ROOT"] = str(args.sdk)
    if args.module_cache is not None:
        env["GRADLE_RO_DEP_CACHE"] = str(args.module_cache)
    result = subprocess.run(command, cwd=project, env=env, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=180, check=False)
    output = result.stdout.decode("utf-8", errors="replace")
    print(output[-98304:], flush=True)
    configured = result.returncode == 0 and "> Task :app:help" in output and "BUILD SUCCESSFUL" in output
    print("ANDROID_CONFIGURATION_RESULT " + json.dumps({
        "exit": result.returncode, "actual_android_project_configured": configured,
        "command": command,
    }), flush=True)
    return 0 if configured else (result.returncode or 1)


if __name__ == "__main__":
    raise SystemExit(main())

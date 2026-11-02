#!/usr/bin/env python3
"""Offline regression for the production downloadOpenH264 Gradle task.

Run only in a disposable OS sandbox with no external network and read-only source.
The local proxy supplies inert bytes. No native binary is loaded or executed.
"""

import argparse
import bz2
import hashlib
import http.server
import json
import os
from pathlib import Path
import re
import subprocess
import threading
import unittest


TRUSTED_LIBRARY_SHA256 = "4d9bc54d2d38e53eb7bd551ec61acb8ad8320d8b957bda751cfdbdcbfabc3b07"
HEADERS = ("codec_api.h", "codec_app_def.h", "codec_def.h", "codec_ver.h")


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def original_task(source):
    """Extract the task verbatim, failing visibly if its layout changes."""
    build = source / "app/build.gradle.kts"
    text = build.read_text(encoding="utf-8")
    version = re.search(r'^val openh264Version = "([^"]+)"$', text, re.MULTILINE)
    task = re.search(
        r'^tasks\.register\("downloadOpenH264"\) \{\n.*?^\}',
        text,
        re.MULTILINE | re.DOTALL,
    )
    if not version or not task:
        raise RuntimeError("Task layout changed: update the extraction adapter, not the tested task")
    preamble = version.group(0) + "\n\n"
    return preamble + task.group(0) + "\n", {
        "source": "app/build.gradle.kts",
        "whole_file_sha256": sha256(build.read_bytes()),
        "task_sha256": sha256(task.group(0).encode()),
        "task_first_line": text[:task.start()].count("\n") + 1,
        "task_last_line": text[:task.end()].count("\n") + 1,
        "version": version.group(1),
        "task_changes": [],
    }


class Proxy(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        with self.server.state_lock:
            self.server.requests.append({"method": "GET", "url": self.path})
            payload = self.server.payload
        if self.path != self.server.expected_url:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_CONNECT(self):
        # Never create a tunnel. HTTPS is a real transport boundary in this test.
        with self.server.state_lock:
            self.server.requests.append({"method": "CONNECT", "url": self.path})
        self.send_error(502, "No external transport in this offline test")

    def log_message(self, *_):
        pass


class IntegrityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.script, provenance = original_task(ARGS.source)
        cls.root = ARGS.scratch / "openh264-integrity"
        cls.root.mkdir(parents=True, exist_ok=False)
        (cls.root / "settings.gradle.kts").write_text(
            'rootProject.name = "openh264-integrity-regression"\n', encoding="utf-8"
        )
        (cls.root / "gradle.properties").write_text(
            "org.gradle.workers.max=1\norg.gradle.parallel=false\norg.gradle.daemon=false\n",
            encoding="utf-8",
        )
        (cls.root / "build.gradle.kts").write_text(cls.script, encoding="utf-8")
        cls.output = cls.root / "src/main/cpp/openh264/lib/arm64-v8a/libopenh264.so"
        cls.output.parent.mkdir(parents=True)
        # Header retrieval is outside this binary-input regression. Inert existing
        # files prevent any GitHub request without altering the extracted task.
        include = cls.root / "src/main/cpp/openh264/include/wels"
        include.mkdir(parents=True)
        for header in HEADERS:
            (include / header).write_text("/* unused inert test header */\n", encoding="utf-8")
        cls.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Proxy)
        cls.server.daemon_threads = True
        cls.server.state_lock = threading.Lock()
        cls.server.requests = []
        cls.server.payload = b""
        cls.server.expected_url = (
            "http://ciscobinary.openh264.org/libopenh264-"
            + provenance["version"]
            + "-android-arm64.8.so.bz2"
        )
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.addClassCleanup(cls.server.server_close)
        cls.addClassCleanup(cls.server.shutdown)
        print("SOURCE_PROVENANCE " + json.dumps(provenance), flush=True)

    def setUp(self):
        for name in ("libopenh264.so", "temp", "temp.bz2"):
            (self.output.parent / name).unlink(missing_ok=True)
        with self.server.state_lock:
            self.server.requests.clear()
        # Deterministic, non-executable high-entropy data exceeds the original
        # compressed-size heuristic. It contains neither ELF code nor secrets.
        self.dummy = b"INERT_TEST_DATA_ONLY\n" + hashlib.shake_256(self.id().encode()).digest(8192)
        self.payload = bz2.compress(self.dummy)

    def run_task(self, payload):
        with self.server.state_lock:
            self.server.payload = payload
        port = str(self.server.server_port)
        proxy_options = (
            " -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=" + port
            + " -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=" + port
            + " -Dhttp.nonProxyHosts= -Dhttps.nonProxyHosts="
        )
        env = dict(os.environ)  # The enclosing sandbox supplies an empty allowlist environment.
        env["JAVA_TOOL_OPTIONS"] = env.get("JAVA_TOOL_OPTIONS", "") + proxy_options
        command = [
            str(ARGS.gradle), "--offline", "--console=plain", "--no-daemon", "--max-workers=1",
            "-Dorg.gradle.jvmargs=-Xmx384m -XX:MaxMetaspaceSize=256m -XX:+UseSerialGC -Dfile.encoding=UTF-8",
            "downloadOpenH264",
        ]
        process = subprocess.run(
            command, cwd=self.root, env=env, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, timeout=150, check=False,
        )
        output = process.stdout.decode("utf-8", errors="replace")
        print("GRADLE_OUTPUT " + self.id() + "\n" + output[-16384:], flush=True)
        with self.server.state_lock:
            requests = list(self.server.requests)
        published = self.output.read_bytes() if self.output.is_file() else None
        print("TASK_RESULT " + json.dumps({
            "test": self.id(), "exit": process.returncode, "requests": requests,
            "input_sha256": sha256(payload), "input_bytes": len(payload),
            "output_sha256": sha256(published) if published is not None else None,
            "output_bytes": len(published) if published is not None else 0,
        }), flush=True)
        # Configuration/compiler failures must never masquerade as integrity RED.
        if "Task :downloadOpenH264" not in output:
            self.fail("SETUP ERROR: production task did not execute")
        return process.returncode, published, requests

    def test_01_rejects_unknown_download(self):
        code, published, _ = self.run_task(self.payload)
        self.assertFalse(published == self.dummy, "Unverified network bytes became the native build input")
        self.assertNotEqual(code, 0, "Unavailable trusted native input must fail the build")

    def test_02_rejects_second_unknown_download(self):
        code, published, _ = self.run_task(self.payload)
        self.assertFalse(published == self.dummy, "A second unrelated archive was also accepted")
        self.assertNotEqual(code, 0, "Unavailable trusted native input must fail the build")

    def test_03_rejects_corrupt_cached_library(self):
        self.output.write_bytes(self.dummy)
        code, _, _ = self.run_task(self.payload)
        self.assertNotEqual(code, 0, "An unchecked preexisting native library satisfied the task")

    def test_04_preserves_verified_existing_library(self):
        if ARGS.trusted_library is None:
            self.skipTest("Provide the independently verified, unstripped Cisco 2.6.0 library for this control")
        trusted = ARGS.trusted_library.read_bytes()
        self.assertEqual(sha256(trusted), TRUSTED_LIBRARY_SHA256, "Positive-control input provenance mismatch")
        self.output.write_bytes(trusted)
        code, published, requests = self.run_task(self.payload)
        self.assertEqual(code, 0)
        self.assertTrue(published == trusted, "Verified cached input changed")
        self.assertEqual(requests, [], "A valid existing library should support offline builds")

    def test_05_rejects_invalid_archive(self):
        code, published, _ = self.run_task(b"NOT_A_BZIP2_ARCHIVE\n" + self.dummy)
        self.assertIsNone(published)
        self.assertNotEqual(code, 0, "Download/decompression failure was silently treated as build success")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--scratch", type=Path, required=True)
    parser.add_argument("--gradle", type=Path, required=True, help="Existing local Gradle 8.13 executable")
    parser.add_argument("--trusted-library", type=Path, help="Optional verified Cisco 2.6.0 positive-control input")
    ARGS = parser.parse_args()
    unittest.main(argv=[__file__], verbosity=2, exit=True)

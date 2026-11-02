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
import select
import socket
import ssl
import subprocess
import sys
import threading
import unittest


TRUSTED_LIBRARY_SHA256 = "4d9bc54d2d38e53eb7bd551ec61acb8ad8320d8b957bda751cfdbdcbfabc3b07"
TRUSTED_ARCHIVE_SHA256 = "c702d68c9c8db492a43c1d73a497cea5f31ae5d23e330dcb13bd28cab1dbbf2a"
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
    plugins = re.search(r"^plugins\s*\{", text, re.MULTILINE)
    if not plugins:
        raise RuntimeError("Plugin block layout changed: update the extraction adapter")
    imports = "\n".join(re.findall(r"^import .+$", text[:plugins.start()], re.MULTILINE))
    preamble = imports + "\n\n" + version.group(0) + "\n\n"
    return preamble + task.group(0) + "\n", {
        "source": "app/build.gradle.kts",
        "whole_file_sha256": sha256(build.read_bytes()),
        "task_sha256": sha256(task.group(0).encode()),
        "task_first_line": text[:task.start()].count("\n") + 1,
        "task_last_line": text[:task.end()].count("\n") + 1,
        "version": version.group(1),
        "task_changes": [],
        "imports_sha256": sha256(imports.encode()),
        "import_changes": [],
    }


class Download(http.server.BaseHTTPRequestHandler):
    def serve_payload(self, transport):
        state = self.server.state
        with state.lock:
            state.requests.append({"method": "GET", "url": self.path, "transport": transport})
            payload, mode = state.payload, state.mode
        expected = state.archive_path if transport == "https" else "http://ciscobinary.openh264.org" + state.archive_path
        if self.path != expected:
            self.send_error(404)
            return
        if mode == "redirect":
            self.send_response(302)
            self.send_header("Location", "http://ciscobinary.openh264.org/forbidden-redirect")
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        if mode == "truncate":
            payload = payload[:len(payload) // 2]
        try:
            self.wfile.write(payload)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def log_message(self, *_):
        pass


class SecureDownload(Download):
    def do_GET(self):
        self.serve_payload("https")


class Proxy(Download):
    def do_GET(self):
        self.serve_payload("http")

    def do_CONNECT(self):
        state = self.server.state
        with state.lock:
            state.requests.append({"method": "CONNECT", "url": self.path})
        if self.path != "ciscobinary.openh264.org:443":
            self.send_error(502, "Only the fixture-owned TLS destination is available")
            return
        # The destination is always the owned loopback TLS listener. Never resolve
        # or connect to the requested remote host or any other destination.
        with socket.create_connection(("127.0.0.1", state.tls_port), timeout=10) as upstream:
            self.send_response(200)
            self.end_headers()
            self.wfile.flush()
            peers = (self.connection, upstream)
            while True:
                readable, _, _ = select.select(peers, [], [], 40)
                if not readable:
                    return
                for peer in readable:
                    try:
                        data = peer.recv(65536)
                        if not data:
                            return
                        other = upstream if peer is self.connection else self.connection
                        other.sendall(data)
                    except (BrokenPipeError, ConnectionResetError, socket.timeout):
                        return


class State:
    def __init__(self, archive_path):
        self.lock = threading.Lock()
        self.requests = []
        self.payload = b""
        self.mode = "normal"
        self.archive_path = archive_path
        self.tls_port = 0


def create_fixture_tls(root):
    """Generate scratch-only test keys and an ordinary JVM CA truststore."""
    tls = root / "tls"
    tls.mkdir()
    config = tls / "openssl.cnf"
    config.write_text(
        "[req]\ndistinguished_name=dn\nprompt=no\n[dn]\nCN=unused\n"
        "[v3_ca]\nbasicConstraints=critical,CA:TRUE\nkeyUsage=critical,keyCertSign,cRLSign\n",
        encoding="utf-8",
    )
    commands = [
        ["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
         "-keyout", "ca.key", "-out", "ca.pem", "-subj", "/CN=OpenH264 offline fixture CA",
         "-extensions", "v3_ca"],
        ["openssl", "req", "-newkey", "rsa:2048", "-nodes", "-keyout", "server.key",
         "-out", "server.csr", "-subj", "/CN=ciscobinary.openh264.org"],
        ["openssl", "x509", "-req", "-in", "server.csr", "-CA", "ca.pem", "-CAkey", "ca.key",
         "-CAcreateserial", "-out", "server.pem", "-days", "2", "-extfile", "extensions.txt"],
        ["keytool", "-importcert", "-noprompt", "-alias", "fixture-ca", "-file", "ca.pem",
         "-keystore", "trust.p12", "-storetype", "PKCS12", "-storepass", "fixture-only"],
    ]
    (tls / "extensions.txt").write_text(
        "subjectAltName=DNS:ciscobinary.openh264.org\nextendedKeyUsage=serverAuth\n", encoding="utf-8"
    )
    env = dict(os.environ)
    env["OPENSSL_CONF"] = str(config)
    for command in commands:
        result = subprocess.run(command, cwd=tls, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=30, check=False, env=env)
        if result.returncode:
            raise RuntimeError("TLS fixture preparation failed: " + result.stdout.decode(errors="replace")[-2000:])
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(tls / "server.pem", tls / "server.key")
    return context, tls / "trust.p12"


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
        tls_context, cls.truststore = create_fixture_tls(cls.root)
        cls.state = State(
            "/libopenh264-" + provenance["version"] + "-android-arm64.8.so.bz2"
        )
        cls.tls_server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), SecureDownload)
        cls.tls_server.daemon_threads = True
        cls.tls_server.state = cls.state
        cls.tls_server.socket = tls_context.wrap_socket(cls.tls_server.socket, server_side=True)
        cls.state.tls_port = cls.tls_server.server_port
        cls.tls_thread = threading.Thread(target=cls.tls_server.serve_forever, daemon=True)
        cls.tls_thread.start()
        cls.addClassCleanup(cls.tls_server.server_close)
        cls.addClassCleanup(cls.tls_server.shutdown)
        cls.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Proxy)
        cls.server.daemon_threads = True
        cls.server.state = cls.state
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.addClassCleanup(cls.server.server_close)
        cls.addClassCleanup(cls.server.shutdown)
        print("SOURCE_PROVENANCE " + json.dumps(provenance), flush=True)

    def setUp(self):
        for name in ("libopenh264.so", "temp", "temp.bz2"):
            (self.output.parent / name).unlink(missing_ok=True)
        with self.state.lock:
            self.state.requests.clear()
            self.state.mode = "normal"
        # Deterministic, non-executable high-entropy data exceeds the original
        # compressed-size heuristic. It contains neither ELF code nor secrets.
        self.dummy = b"INERT_TEST_DATA_ONLY\n" + hashlib.shake_256(self.id().encode()).digest(8192)
        self.payload = bz2.compress(self.dummy)

    def run_task(self, payload, mode="normal"):
        with self.state.lock:
            self.state.payload = payload
            self.state.mode = mode
        port = str(self.server.server_port)
        proxy_options = (
            " -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=" + port
            + " -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=" + port
            + " -Dhttp.nonProxyHosts= -Dhttps.nonProxyHosts="
            + " -Djavax.net.ssl.trustStore=" + str(self.truststore)
            + " -Djavax.net.ssl.trustStorePassword=fixture-only -Djavax.net.ssl.trustStoreType=PKCS12"
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
        with self.state.lock:
            requests = list(self.state.requests)
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
        self.last_output = output
        return process.returncode, published, requests

    def assert_clean_staging(self):
        leftovers = sorted(p.name for p in self.output.parent.iterdir() if p.name != "libopenh264.so")
        self.assertEqual(leftovers, [], "Download staging files were retained")

    def trusted_archive(self):
        if ARGS.trusted_archive is None:
            self.skipTest("Provide the verified Cisco2.6.0 archive for TLS controls")
        archive = ARGS.trusted_archive.read_bytes()
        self.assertEqual(sha256(archive), TRUSTED_ARCHIVE_SHA256, "TLS control archive provenance mismatch")
        return archive

    def test_01_rejects_unknown_download(self):
        code, published, _ = self.run_task(self.payload)
        self.assertFalse(published == self.dummy, "Unverified network bytes became the native build input")
        self.assertNotEqual(code, 0, "Unavailable trusted native input must fail the build")
        self.assert_clean_staging()

    def test_02_rejects_second_unknown_download(self):
        code, published, _ = self.run_task(self.payload)
        self.assertFalse(published == self.dummy, "A second unrelated archive was also accepted")
        self.assertNotEqual(code, 0, "Unavailable trusted native input must fail the build")
        self.assert_clean_staging()

    def test_03_rejects_corrupt_cached_library(self):
        self.output.write_bytes(self.dummy)
        code, _, _ = self.run_task(self.payload)
        self.assertNotEqual(code, 0, "An unchecked preexisting native library satisfied the task")
        self.assert_clean_staging()

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
        self.assert_clean_staging()

    def test_05_rejects_invalid_archive(self):
        code, published, _ = self.run_task(b"NOT_A_BZIP2_ARCHIVE\n" + self.dummy)
        self.assertIsNone(published)
        self.assertNotEqual(code, 0, "Download/decompression failure was silently treated as build success")
        self.assert_clean_staging()

    def test_06_accepts_verified_download_over_real_tls(self):
        code, published, requests = self.run_task(self.trusted_archive())
        self.assertEqual(code, 0, self.last_output[-2000:])
        self.assertIsNotNone(published)
        self.assertEqual(sha256(published), TRUSTED_LIBRARY_SHA256)
        self.assertTrue(any(r.get("transport") == "https" for r in requests), "No HTTPS response was consumed")
        self.assertFalse(any(r.get("transport") == "http" for r in requests), "Plain HTTP was used")
        self.assert_clean_staging()

    def test_07_rejects_same_size_wrong_digest_over_real_tls(self):
        archive = bytearray(self.trusted_archive())
        archive[len(archive) // 2] ^= 1
        code, published, requests = self.run_task(bytes(archive))
        self.assertNotEqual(code, 0)
        self.assertIsNone(published)
        self.assertTrue(any(r.get("transport") == "https" for r in requests))
        self.assertIn("SHA-256 mismatch", self.last_output)
        self.assert_clean_staging()

    def test_08_rejects_truncated_tls_response(self):
        code, published, requests = self.run_task(self.trusted_archive(), mode="truncate")
        self.assertNotEqual(code, 0)
        self.assertIsNone(published)
        self.assertTrue(any(r.get("transport") == "https" for r in requests))
        self.assert_clean_staging()

    def test_09_rejects_redirect_without_following_it(self):
        code, published, requests = self.run_task(self.trusted_archive(), mode="redirect")
        self.assertNotEqual(code, 0)
        self.assertIsNone(published)
        self.assertTrue(any(r.get("transport") == "https" for r in requests))
        self.assertFalse(any("forbidden-redirect" in r["url"] for r in requests))
        self.assert_clean_staging()

    def test_10_rejects_same_size_wrong_digest_in_cache(self):
        if ARGS.trusted_library is None:
            self.skipTest("Provide the verified library for the cache-digest control")
        cached = bytearray(ARGS.trusted_library.read_bytes())
        self.assertEqual(sha256(cached), TRUSTED_LIBRARY_SHA256)
        cached[len(cached) // 2] ^= 1
        self.output.write_bytes(cached)
        code, published, requests = self.run_task(self.payload)
        self.assertNotEqual(code, 0)
        self.assertIn("SHA-256 mismatch", self.last_output)
        self.assertTrue(published == cached, "Corrupt cache should remain available for diagnosis")
        self.assertEqual(requests, [], "Invalid existing input should fail before any download")
        self.assert_clean_staging()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--scratch", type=Path, required=True)
    parser.add_argument("--gradle", type=Path, required=True, help="Existing local Gradle 8.13 executable")
    parser.add_argument("--trusted-library", type=Path, help="Optional verified Cisco 2.6.0 positive-control input")
    parser.add_argument("--trusted-archive", type=Path, help="Verified Cisco 2.6.0 bz2 input for real TLS controls")
    parser.add_argument("--android-sdk", type=Path, required=True, help="Existing SDK for mandatory real Android-plugin configuration")
    parser.add_argument("--module-cache", type=Path, help="Existing read-only Gradle dependency cache")
    ARGS = parser.parse_args()
    configuration = [
        sys.executable, str(Path(__file__).with_name("configure-android-project.py")),
        "--source", str(ARGS.source), "--scratch", str(ARGS.scratch),
        "--gradle", str(ARGS.gradle), "--sdk", str(ARGS.android_sdk),
    ]
    if ARGS.module_cache is not None:
        configuration += ["--module-cache", str(ARGS.module_cache)]
    if subprocess.run(configuration, check=False).returncode:
        raise SystemExit("Actual Android-plugin script configuration failed; TLS-only results would be insufficient")
    unittest.main(argv=[__file__], verbosity=2, exit=True)

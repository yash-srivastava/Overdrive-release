#!/usr/bin/env python3
"""Extract the real Java guardian shell template and check duplicate/stale ownership.

Test only the coordination paths using an inert fake pm command and temporary files;
no Android package state is changed. Run: python guardian_test.py
"""
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[3]
source = (ROOT / 'app/src/main/java/com/overdrive/app/server/ServiceModeManager.java').read_text()
expression = source.split('String guardian =', 1)[1].split(';\n        String command =', 1)[0]
# Java quoted literals and small known static operands. Assert full expression
# coverage to make this test fail if the expression is changed unexpectedly.
tokens = re.findall(r'"(?:\\.|[^"\\])*"|shellQuote\((?:GUARD_LOCK|GUARD_MARKER|PRIVATE_RESCUE_SCRIPT)\)|APP_PACKAGE|[+]', expression)
remainder = expression
for token in tokens:
    remainder = remainder.replace(token, '', 1)
assert not remainder.strip(), f'unparsed guardian template: {remainder!r}'
values = {
    'GUARD_LOCK': '/data/local/tmp/overdrive-service-mode-guard.lock',
    'GUARD_MARKER': '/data/local/tmp/overdrive-service-mode-active',
    'PRIVATE_RESCUE_SCRIPT': '/data/local/tmp/overdrive-service-mode-rescue.sh',
    'APP_PACKAGE': 'com.overdrive.app',
}
def decode(token):
    if token == '+': return ''
    if token.startswith('"'): return json.loads(token)
    if token.startswith('shellQuote('):
        raw = values[token[11:-1]]
        return "'" + raw.replace("'", "'\\''") + "'"
    return values[token]
guardian = ''.join(decode(token) for token in tokens)
with tempfile.TemporaryDirectory() as folder:
    base = Path(folder)
    guardian = guardian.replace('/data/local/tmp/', folder + '/')
    script = base / 'guardian.sh'
    script.write_text(guardian)
    bin_dir = base / 'bin'; bin_dir.mkdir()
    (bin_dir / 'pm').write_text('#!/bin/sh\nprintf "package:/fake/overdrive.apk\\n"\n')
    (bin_dir / 'sleep').write_text('#!/bin/sh\n/usr/bin/sleep 0.1\n')
    for tool in ('pm', 'sleep'):
        (bin_dir / tool).chmod(0o755)
    env = dict(os.environ, PATH=f'{bin_dir}:' + os.environ['PATH'])
    marker = base / 'overdrive-service-mode-active'; marker.touch()
    first = subprocess.Popen(['/bin/sh', str(script)], env=env)
    try:
        lock = base / 'overdrive-service-mode-guard.lock'
        deadline = time.monotonic() + 3
        while not (lock / 'pid').exists() and time.monotonic() < deadline:
            time.sleep(0.02)
        assert (lock / 'pid').exists(), 'first guardian never claimed lock'
        assert int((lock / 'pid').read_text()) == first.pid
        second = subprocess.run(['/bin/sh', str(script)], env=env, timeout=3)
        assert second.returncode == 0, 'second guardian did not skip live owner'
        assert int((lock / 'pid').read_text()) == first.pid
        marker.unlink()
        assert first.wait(timeout=3) == 0
        assert not lock.exists(), 'guardian failed to release lock'
        # Interrupted/stale owner: a new guardian recovers an orphaned lock.
        lock.mkdir(); (lock / 'pid').write_text('99999999')
        assert subprocess.run(['/bin/sh', str(script)], env=env, timeout=4).returncode == 0
        assert not lock.exists(), 'stale lock could not be reclaimed'
    finally:
        if first.poll() is None:
            first.kill(); first.wait()
print('PASS guardian duplicate + stale-lock recovery')

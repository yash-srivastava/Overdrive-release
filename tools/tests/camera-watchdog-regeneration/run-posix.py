#!/usr/bin/env python3
"""Explicit Linux/sandbox probe for the real generated camera watchdog.

Mandatory JUnit tests remain portable. This tool fails on unsupported hosts;
it does not skip tests or access a real Android daemon or vehicle.
"""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import sys


def stop_group(process):
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass
    process.wait(timeout=5)


def main():
    if sys.platform != 'linux':
        raise SystemExit('This explicit probe requires a Linux sandbox with /bin/sh and /bin/sleep.')
    parser = argparse.ArgumentParser()
    parser.add_argument('--classpath-file', required=True, type=Path)
    parser.add_argument('--scratch', required=True, type=Path)
    args = parser.parse_args()
    root = args.scratch.resolve()
    root.mkdir(parents=True, exist_ok=False)
    classes = root / 'classes'
    classes.mkdir()
    classpath = args.classpath_file.read_text().strip()
    if not classpath or any(not Path(entry).exists() for entry in classpath.split(os.pathsep)):
        raise AssertionError('actual compiled Test runtime classpath is incomplete')
    source = Path(__file__).with_name('ExportWatchdogFixture.java')
    subprocess.run(['javac', '-cp', classpath, '-d', str(classes), str(source)], check=True, timeout=30)
    fixture = root / 'fixture'
    subprocess.run(['java', '-cp', str(classes) + os.pathsep + classpath,
                    'ExportWatchdogFixture', str(fixture)], check=True, timeout=30)
    metadata = (fixture / 'metadata.txt').read_text().splitlines()
    if len(metadata) != 4:
        raise AssertionError('invalid exported metadata')
    fake_bin = root / 'bin'
    fake_bin.mkdir()
    (fake_bin / 'app_process').write_text(
        '#!/bin/sh\nprintf \'%s\\n\' "$CLASSPATH" "$@" >> "$CALLS"\n: > "$STOP_FILE"\n')
    (fake_bin / 'sleep').write_text('#!/bin/sh\nexec /bin/sleep 30\n')
    for executable in fake_bin.iterdir():
        executable.chmod(0o700)
    calls = root / 'calls.txt'
    stop = fixture / 'camera_daemon.disabled'
    for generation in ['old', 'new']:
        stop.unlink(missing_ok=True)
        process = subprocess.Popen(['/bin/sh', str(fixture / (generation + '.sh'))],
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   env={'PATH': str(fake_bin) + ':/usr/bin:/bin',
                                        'CALLS': str(calls), 'STOP_FILE': str(stop)},
                                   start_new_session=True)
        try:
            output, _ = process.communicate(timeout=10)
            if process.returncode != 0:
                raise AssertionError('dummy watchdog failed: ' + output.decode(errors='replace'))
        except subprocess.TimeoutExpired:
            raise AssertionError('dummy watchdog exceeded its 10-second limit')
        finally:
            # Reap any fixture-only log-poller/sleep children in its own group.
            stop_group(process)
        if (fixture / 'cam_watchdog.lock').exists() or (fixture / 'cam_watchdog.pid').exists():
            raise AssertionError('watchdog ownership files survived clean fixture shutdown')
    rows = calls.read_text().splitlines()
    if len(rows) != 14:
        raise AssertionError('expected exactly two dummy launch records')
    for index, start in enumerate([0, 7]):
        apk, native = metadata[index * 2:index * 2 + 2]
        if not rows[start].endswith(apk):
            raise AssertionError('wrong APK classpath')
        expected = '-Djava.library.path=' + native + ':/system/lib64:/vendor/lib64:/product/lib64:/odm/lib64'
        if rows[start + 1] != expected or rows[start + 6] != native:
            raise AssertionError('wrong native-library metadata')
    if metadata[0] in '\n'.join(rows[7:]) or metadata[1] in '\n'.join(rows[7:]):
        raise AssertionError('new generation retained old launch metadata')
    if not Path(metadata[0]).is_file():
        raise AssertionError('old APK control must remain present')
    print(json.dumps({'dummy_generations': 2, 'old_apk_retained': True,
                      'paired_metadata': 'PASS', 'ownership_cleanup': 'PASS',
                      'scope': 'real compiled generator, explicit Linux tool; no Android/camera/JNI acceptance'}))


if __name__ == '__main__':
    main()

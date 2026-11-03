#!/usr/bin/env python3
"""Check a built APK against source assets; run in the same isolated build environment."""
import hashlib
import json
import os
from pathlib import Path
import sys
import zipfile


def sha(data):
    return hashlib.sha256(data).hexdigest()


def check(root, apk):
    assets = root / 'app/src/main/assets'
    alias = assets / 'web/local/shared'
    canonical = assets / 'web/shared'
    if not alias.is_symlink() or os.readlink(alias) != '../shared' or not alias.samefile(canonical):
        raise AssertionError('the documented source alias must point at web/shared')
    with zipfile.ZipFile(apk) as package:
        names = package.namelist()
        if len(names) != len(set(names)):
            raise AssertionError('duplicate APK entry names')
        if any(name.startswith('assets/web/local/shared/') for name in names):
            raise AssertionError('the source compatibility alias was packaged again')
        preserved = {}
        ignored = []
        for directory, dirs, files in os.walk(assets, followlinks=False):
            for name in list(dirs):
                item = Path(directory) / name
                if item == alias:
                    dirs.remove(name)
                elif item.is_symlink():
                    raise AssertionError('unexpected source directory symlink: ' + str(item))
            for name in files:
                item = Path(directory) / name
                relative = item.relative_to(assets).as_posix()
                # AAPT's existing ignore pattern omits Git placeholders. This
                # exact, empty source marker is not a runtime asset.
                if relative == 'models/.gitkeep' and item.stat().st_size == 0:
                    ignored.append(relative)
                    continue
                entry = 'assets/' + relative
                if item.is_symlink() or not item.is_file():
                    raise AssertionError('unexpected source file type: ' + relative)
                source = item.read_bytes()
                if entry not in names or package.read(entry) != source:
                    raise AssertionError('missing or changed asset: ' + relative)
                preserved[relative] = {'bytes': len(source), 'sha256': sha(source)}
        if 'web/shared/performance.js' not in preserved:
            raise AssertionError('canonical performance script missing')
        return {'apk': apk.name, 'source_alias_preserved': True,
                'duplicate_apk_entry_names': 0, 'packaged_alias_entries': 0,
                'preserved_source_assets': preserved, 'aapt_ignored_empty_markers': ignored}


if __name__ == '__main__':
    repository = Path(__file__).resolve().parents[3]
    if len(sys.argv) != 2:
        raise SystemExit('usage: check.py path/to/app-arm64-v8a-debug.apk')
    print(json.dumps(check(repository, Path(sys.argv[1])), sort_keys=True))

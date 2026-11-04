#!/usr/bin/env python3
"""Replicates the downloadOpenCV digest gate from app/build.gradle.kts:
a downloaded zip must match the pinned SHA-256 before extraction; a mismatch
or an empty pin fails closed. Usage: check.py <zip> <pin>  (exit 0 = accept)"""
import hashlib, sys

def main() -> int:
    path, pin = sys.argv[1], sys.argv[2].strip().lower()
    h = hashlib.sha256(open(path, "rb").read()).hexdigest()
    if not pin or h != pin:
        print(f"REJECTED: got {h}, pin empty or mismatch")
        return 1
    print("ACCEPTED: digest matches pin")
    return 0

if __name__ == "__main__":
    sys.exit(main())

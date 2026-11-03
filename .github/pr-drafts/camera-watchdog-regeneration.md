## What does this PR do?

Replace the obsolete per-restart `pm path` source contract with generated-shell
coverage of the actual watchdog generator using a fresh APK/native-library
metadata pair, preserving portable mandatory JUnit tests. Keep POSIX execution
in an explicit maintained Linux tool, and preserve release-reset request guards.

## Why is this change needed?

The application refreshes camera launch metadata by regenerating its watchdog
after package replacement. The old test required an unimplemented lookup and
an unsafe assumption about native-directory layout. Mandatory tests verify the regenerated command uses the new supplied pair,
including a native directory outside the APK tree. The separate Linux probe
also retains the old APK on disk without imposing a shell on Windows tests.

## How to test

Run `./gradlew assembleDebug` and `./gradlew test`. Targeted generator, watchdog
ownership, updater and legacy identity checks are pending before submission.
See `.github/tdd/camera-watchdog-regeneration.md` for validation scope and the
unresolved broadcast/reset error paths. No production code changes or installed
vehicle update acceptance are claimed.

## Screenshots (if applicable)

Not applicable; this change updates regression coverage.

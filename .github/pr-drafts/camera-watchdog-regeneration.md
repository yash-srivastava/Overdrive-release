## What does this PR do?

Replace the obsolete per-restart `pm path` source contract with generated-shell
coverage of watchdog regeneration using a fresh APK/native-library metadata
pair. Preserve source checks for the release package-replacement reset request.

## Why is this change needed?

The application refreshes camera launch metadata by regenerating its watchdog
after package replacement. The old test required an unimplemented lookup and
an unsafe assumption about native-directory layout. The new behavior test
retains the old APK and verifies the regenerated command uses the new supplied
pair, including an authoritative native directory outside the APK tree.

## How to test

Run `./gradlew assembleDebug` and `./gradlew test`. Targeted generator, watchdog
ownership, updater and legacy identity checks are pending before submission.
See `.github/tdd/camera-watchdog-regeneration.md` for validation scope and the
unresolved broadcast/reset error paths. No production code changes or installed
vehicle update acceptance are claimed.

## Screenshots (if applicable)

Not applicable; this change updates regression coverage.

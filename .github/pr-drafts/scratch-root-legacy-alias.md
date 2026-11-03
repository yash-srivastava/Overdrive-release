## What does this PR do?

Reuse `ScratchRoot.LEGACY_DIR` for the public compile-time
`DaemonStorage.LEGACY_BASE` constant. Add legacy and relocated consumer controls.

## Why is this change needed?

The scratch-root completeness gate mistakes a second declaration of the legacy
sentinel for an unconverted direct path. Sharing the identical constant fixes
the actual duplicate declaration without exempting another file from scanning
or changing storage selection, migration, paths or command text.

## How to test

Run `./gradlew assembleDebug` and `./gradlew test` in the configured development
checkout. Whole-project results are pending before submission.

Targeted RED: 12 tests, the unchanged scanner reports only the duplicated
sentinel. Targeted GREEN: the same 12 tests pass, including eight existing
alternate-root controls. See `.github/tdd/scratch-root-legacy-alias.md` for exact
fixture qualifications and the separate, unmodified relocation-policy issue.

## Screenshots (if applicable)

Not applicable; this compile-time alias has no UI change.

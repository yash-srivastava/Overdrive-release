## What does this PR do?

Stage main assets through a producer-ordered Sync task and package the canonical
`web/shared` tree once. Keep the documented `web/local/shared -> ../shared`
source alias and existing daemon URL compatibility. Add APK byte-preservation
and static-route compatibility checkers.

## Why is this change needed?

The v57 packaging contract requires one copy, but a real current debug APK stores
114 shared files twice. Removing the source link would violate CONTRIBUTING;
excluding only its packaged duplicate satisfies both requirements. The asset
staging task must run after the FastCam asset producer and before asset merges.

## How to test

Run `./gradlew assembleDebug`, the APK/route checks in
`tools/tests/package-web-assets/README.md`, and `./gradlew test`.
Full Android plugin configuration, dependency ordering, incremental Sync,
actual rebuilt APK and route results are pending before submission.

See `.github/tdd/canonical-web-asset-packaging.md` for the baseline APK evidence,
source-excerpt limits and unchanged native/dependency setup qualifications.

## Screenshots (if applicable)

Not applicable; URL, asset contents and runtime behavior are intended unchanged.

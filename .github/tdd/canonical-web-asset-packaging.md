# Package one canonical shared web asset tree

## Validated baseline and intent

Commit `263e7df0` (v57) changed the battery asset contract from identical copies
to a single packaged performance script. The source compatibility symlink was
retained, and the older CONTRIBUTING clone guidance still requires it. Deleting
that link would break the documented source checkout contract.

A real debug APK built from fresh main plus a test-only camera branch contains
114 files under both `assets/web/shared/` and `assets/web/local/shared/`.
`performance.js` is stored twice, 148,504 bytes per copy, SHA-256
`ea7daf7829241ec31e05ed576e5d03a4dc1dcf7fc7fff084f67f17ef892af1b6`.
The APK has 1,996 distinct entry names (zero duplicate ZIP names); duplicated
content under different paths is the issue. Its actual `assembleDebug` passed.
The same run's new camera test compilation failed before tests ran, which does
not invalidate the separately completed APK observation and is not a test pass.

## Change and build ordering

`preparePackagedMainAssets` stages main assets with Gradle Sync, excluding only
`web/local/shared` and its descendants. It depends on `downloadFastCam`, the
only task that can produce a main-source asset, before copying. The task's
output is the main asset source for Android variants, so asset merges must
consume the task dependency rather than race a sibling `preBuild` action.
Hebrew generation writes resources, not assets. Variant overlays remain their
separate Android source sets. No HTTP, WebView, auth or camera code changes.

The source symlink remains `../shared`. Unit packaging contracts depend on the staging task and check the real staged
performance bytes and absence of the alias, in addition to source-link identity. Actual APK checks are supplied under
`tools/tests/package-web-assets`, including all non-alias source asset bytes.
The empty `models/.gitkeep` marker is already ignored by AAPT.

## Existing runtime compatibility

The daemon's current static branch strips query/fragment and maps
`local/shared/...` to `shared/...` before serving. WebView loads daemon HTTP URLs
and disables file access. Shared CSS imports therefore continue to resolve via
the existing alias mapping. Static Range requests currently return full `200`
responses; no new `206` behavior is claimed.

The source-excerpt route fixture compiles that unchanged branch, file serving,
MIME mapping and actual HttpResponse against canonical assets extracted from
the real APK. Catalog/model fallbacks are stubbed and authentication is outside
the fixture. Shared web assets currently contain no fonts; one actual native
font is copied to a fixture-only shared URL to exercise alias handling for that
file type. The original native fonts are covered by APK byte-preservation checks.

## Validation status and limits

Full Android plugin configuration, task dependency inspection, Sync stale-file
controls, rebuilt APK hashes and route checks are pending. Required
`./gradlew assembleDebug` and `./gradlew test` must be recorded before submission.
Target execution remains bounded, network-disabled, read-only source/tools and
scratch-only writes. Staged native/Maven qualifications from the matched public
baseline apply. No installed BYD-device or fleet acceptance is established.

# Canonical web asset packaging

Build normally, then inspect the resulting APK in the same isolated development
environment:

```sh
./gradlew assembleDebug
python3 tools/tests/package-web-assets/check.py app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
./gradlew test
```

The checker verifies every ordinary source asset remains byte-identical in the
APK and the source `web/local/shared -> ../shared` compatibility link remains
intact, while its duplicate packaged subtree is absent. The empty
`models/.gitkeep` placeholder is omitted by AAPT's existing ignore policy.
APK files from dependencies may add assets; they do not replace this check of
all main-source assets. Duplicate ZIP names are rejected independently.

The staging task depends on `downloadFastCam` before copying its generated
asset, and every Android variant consumes the task output as its main source.
Variant-specific source directories remain separate Android overlays.

The daemon already maps `/local/shared/...` onto `/shared/...`, including
cache-busting query strings; this change does not alter HTTP/WebView routing,
cache validators, static Range behavior or authentication.

For source-excerpt HTTP compatibility checks against the built canonical tree:

```sh
python3 tools/tests/package-web-assets/route_check.py app/build/outputs/apk/debug/app-arm64-v8a-debug.apk --json-jar /path/to/json-20231013.jar --scratch /path/to/disposable/scratch/asset-routes
```

This compiles the unchanged static route branch, file-serving method and MIME
mapping from `HttpServer.java`, plus the actual `HttpResponse.java`. Every
packaged shared file is requested through canonical and legacy aliases with
query/fragment controls and compared byte-for-byte; ETag, missing-path,
traversal and existing static Range behavior are checked. Static assets currently
return full `200` responses to Range requests; no `206` claim is made.
Catalog/model fallbacks are stubs and authentication is outside this fixture.

Web shared assets currently contain no packaged font files. A real native UI
font from the same APK is copied to a fixture-only shared URL for an additional
alias/MIME check; that URL is not a shipped application resource. All original
native font assets are independently covered by the APK byte-preservation check.

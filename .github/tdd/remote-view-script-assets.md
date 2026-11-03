# Remote-view script asset contracts

## Confirmed cause

Two source-contract tests expected the exact `remote-dev-view.js?v=10` string, while the shipped page already loads `?v=11`. The source assets, script dependency order, authentication, POST body and no-store behavior were not changed by this PR. Cache tokens may change without changing an asset's identity.

The replacement checks require one real local script reference, the intended path, an existing source asset, and auth-before-core-before-view order. Query-only version changes are accepted. Missing scripts, missing files, external references, fragments, duplicates, commented-out tags and `data-src` attributes cannot satisfy the required asset check. The existing auth/no-store/session-in-body assertions remain intact.

## RED and GREEN

In one network-disabled sandbox, the exact baseline `ade874fc` test class was compiled and its two affected methods executed: both failed. The modified class passes those same two methods plus two new helper-contract tests containing positive cache-query and negative reference mutations. The final rerun also rejects commented-out script tags and `data-src`. No production files changed.

Bounds: 1 CPU, 512 MiB RAM, 128 MiB scratch disk, 64 PIDs, 64 MiB file limit, 90 seconds. Actual Java test source runs against actual tracked HTML/JS. The asset-only compile fixture provides an unrelated WebSocket parser symbol that throws if called; none of the selected methods calls it. This result does not establish WebSocket parser or browser runtime behavior. Full Gradle compilation and tests are separate parent-coordinated contribution checks.

Focused check in a configured checkout:

```sh
./gradlew testDebugUnitTest --tests 'com.overdrive.app.server.RemoteDevViewAssetTest.sidebarStartsSharedVehicleStatusPolling' --tests 'com.overdrive.app.server.RemoteDevViewAssetTest.frameFallbackUsesAuthenticatedPostAndNoStoreWithoutPuttingSessionInUrl' --tests 'com.overdrive.app.server.RemoteDevViewAssetTest.scriptAsset*'
```

Required before PR publication:

```sh
./gradlew assembleDebug
./gradlew test
```

A separate existing localization assertion in this class is outside this fix. Report its status accurately if it still fails; do not count a focused run as a whole-suite pass.

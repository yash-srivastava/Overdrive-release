# Restore the public proximity-access runbook

## Confirmed cause

On public main `ade874fc24ba609c42ea2c93dc63e81d2c680a15`, four existing
documentation contracts fail because `docs/remote-access.md` is not tracked.
The other fifteen tests in those four classes pass. No production defect is
inferred from the absent file.

## Change

Add an implementation-derived operator guide covering Learning mode, independent
phone/fob triggers, timing defaults and migration, local confirmation, cloud
actuator continuation and revocation, outcomes and rail selection. Source links
identify the implementation behind these descriptions. Historical log identifiers
required by the existing contracts are explicitly labeled as unavailable public
artifacts, not new evidence. The documented historical timestamp is already
cited by `RemoteAccessSettings` and `KeyFobEventEvaluator` comments.

No application, build configuration or existing assertion changes are included.

## Red and green

The actual unchanged classes were compiled with JDK 21.0.12.1+1, JUnit 4.13.2,
Hamcrest Core 1.3 and the declared `org.json:json:20231013`, then run together
through `org.junit.runner.JUnitCore`:

```text
com.overdrive.app.server.RemoteAccessCloudFallbackAssetTest
com.overdrive.app.server.RemoteAccessKeyFobUnlockTriggerAssetTest
com.overdrive.app.server.RemoteAccessSnappyLocksAssetTest
com.overdrive.app.server.RemoteAccessSnappyProximityAssetTest
```

All target execution used a disposable, network-disabled OS sandbox with
read-only source/toolchain/fixture mounts, an explicitly populated environment,
scratch-only writable storage, 512 MiB memory, one CPU, 64 processes, 128 MiB
scratch and a 60-second wall limit.

- RED: 19 tests, four missing-runbook failures; sandbox exit 1, 2.311 s.
- Intermediate: 19 tests, one exact-phrase wrapping mismatch; no assertion changed.
- GREEN: 19 tests pass; sandbox exit 0, 2.350 s, no limit stop.

The final guide SHA-256 is
`9a8d7ae58bfea9955891340352010b6c6db44c53223d260085eaa727265ba24e`.
The green captured log SHA-256 is
`646c15f4e9ccf4b40870aa49e8cd509f8237fc553aee06c6cf147b300f3ba1c4`.
The red captured log SHA-256 is
`2e3c8a7c31a87a323d08429d8253b7ce176a3c4b10b3ab1618cc2aef802929ff`.

To rerun the focused checks in a configured checkout:

```sh
./gradlew :app:testDebugUnitTest \
  --tests com.overdrive.app.server.RemoteAccessCloudFallbackAssetTest \
  --tests com.overdrive.app.server.RemoteAccessKeyFobUnlockTriggerAssetTest \
  --tests com.overdrive.app.server.RemoteAccessSnappyLocksAssetTest \
  --tests com.overdrive.app.server.RemoteAccessSnappyProximityAssetTest
```

This Gradle command is a reproduction command; the targeted evidence above is
the direct JUnit execution. Required `./gradlew assembleDebug` and
`./gradlew test` results must be recorded separately before PR publication.
The focused green result does not establish hardware, installed-app or fleet
acceptance. No actual vehicle commands or external provider requests were run.

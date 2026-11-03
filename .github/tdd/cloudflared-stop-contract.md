# Current Cloudflared stop callback contract

## Confirmed cause

The failure searches for `private fun stopDaemonSilent`, which no longer exists. The current `stopDaemon` callback reports ERROR on failed stop and schedules an actual-status refresh; `CloudflaredController.stop` forwards that error while preserving the tunnel URL. The contract now inspects the current error callback and separately requires successful stop to clear the URL and report STOPPED. No production change is needed for this reproduced failure.

## Validation

- Exact `ade874fc` Java test: the affected method fails because its old function anchor is absent.
- Modified actual Java method: passes, retaining and strengthening URL/error/success assertions.
- Verbatim-extracted actual Kotlin controller and ViewModel stop methods: four immediate/delayed success/failure scenarios pass with deterministic URL/status/clock seams. Failed stops never report STOPPED or clear the URL; the existing delayed refresh is invoked. Both incorrect-callback mutations are rejected.
- JDK 21.0.12.1+1, Kotlin compiler 2.0.21, exact application stdlib 2.1.20. The first callback compilation omitted the compiler's existing coroutine dependency, so no callback behavior ran in that attempt; the corrected explicit compiler classpath succeeds.
- Isolation: network disabled; source/tools read-only; scratch-only writes; 1 CPU, 512 MiB RAM, 128 MiB disk, 64 PIDs, 64 MiB file bound, 90 second limit. Complete RED/GREEN plus behavior run exits zero in 11.191 seconds.

```sh
./gradlew testDebugUnitTest --tests com.overdrive.app.launcher.CloudflaredRecoveryContractTest
./gradlew assembleDebug
./gradlew test
```

The parent coordinates final canonical checks before PR publication. The standalone behavior fixture is `tools/tests/cloudflared-stop-callback/`; its deterministic seams do not establish Android LiveData timing, real ADB stop verification, actual delayed process status or installed-device/fleet behavior. Existing shell kill/marker, reconnect and boot-relaunch contract checks are unchanged.

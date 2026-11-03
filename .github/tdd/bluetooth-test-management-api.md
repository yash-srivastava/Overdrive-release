# Bluetooth callback monitor observation on modular JDKs

## Cause and invariant

The two callback race tests inspected `getThreadInfo` on the runtime bean implementation class. On JDK 21 that implementation lives in a non-exported package, so reflective invocation throws `IllegalAccessException`. The helper swallowed it and returned false before the intended locking assertions ran. Resolve methods through the exported `ThreadMXBean`, `ThreadInfo` and `LockInfo` types instead. Reflection remains only to avoid an Android compile-time dependency on JVM management classes; it uses no module access override. Inspection failures now report their cause instead of masquerading as an unlocked callback.

The tests still require the callback to be blocked on the exact shared monitor and preserve the close/read-replacement assertions. A new regression also rejects an unrelated monitor and an exited thread.

## RED and GREEN

- Original V58 plus new regression, commit `dd8447e`: 35 real callback/platform JUnit tests ran; the two existing race tests and new monitor regression failed (3 failures).
- Rebased to current main `ade874fc24ba609c42ea2c93dc63e81d2c680a15`, the helper fix passes all 35 tests on JDK 21.0.12.1+1; no module access overrides, errors, skips or runtime hardware/config calls.
- Sandbox: network disabled, source/tools read-only, scratch-only writes, 1 CPU, 512 MiB RAM, 64 PIDs, 128 MiB disk, 64 MiB file limit, 90 second limit. Actual production callback/layout/native-call Java and both actual test classes compiled against Android 36/JUnit 4.13.2. A fail-on-call `DiLink5Platform.isSelected()` compile seam prevents hardware/config selection; these tests pass explicit platform metadata.
- The first isolated compile attempt omitted the actual `BluetoothNativeCall` input and executed no tests. It is not counted as RED.

Focused reproduction in a configured checkout:

```sh
./gradlew testDebugUnitTest --tests com.overdrive.app.remoteaccess.BluetoothRssiCallbacksTest --tests com.overdrive.app.remoteaccess.BluetoothPlatformSupportTest
```

Required whole-project checks, separately coordinated before PR submission:

```sh
./gradlew assembleDebug
./gradlew test
```

Whole-project results must be reported separately from the focused check. No production source or Bluetooth behavior changes; no claim about physical radio, firmware or vehicle acceptance.

## What does this PR do?

Repair JVM monitor inspection in Bluetooth callback concurrency tests by resolving reflection through exported management types. Add positive/exact-monitor, unrelated-monitor and exited-thread coverage; report inspection errors explicitly.

## Why is this change needed?

JDK 21 rejects reflection on its internal bean implementation. The old catch-all hid that failure, causing two existing race tests to fail before checking callback state. Production callback code is unchanged.

## How to test

The isolated actual-source callback/platform suite reproduces the three expected RED failures and passes all 35 tests after the change. See `.github/tdd/bluetooth-test-management-api.md` for setup and limits.

Run `./gradlew assembleDebug` and `./gradlew test`. Final whole-project results are pending and must be added before publication; focused results do not replace these commands.

## Screenshots (if applicable)

Not applicable: tests only, no UI change.

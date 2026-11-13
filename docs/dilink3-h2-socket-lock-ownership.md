# H2 socket-lock ownership across daemon stores

## Summary

SOC/charging history, trip history, notifications, RoadSense, and the recordings index open embedded H2 stores with `FILE_LOCK=SOCKET`. Previously each store's initialization retries included an age-based cleanup of `<store>.lock.db` (and sometimes the associated trace file). The heuristic treated a lock file older than five minutes as abandoned.

That is unsafe for the **SOCKET** protocol: H2 records an owner socket in the lock file and does **not** need to periodically update its timestamp. A live daemon that has held a database for more than five minutes can therefore have an old `.lock.db` file. Deleting that file on a competing startup can permit a second process to establish its own lock, risking simultaneous access to the same H2 database.

H2's documented socket-lock algorithm probes the owner socket. If the owner is dead, H2 recovers the orphaned lock itself. If the owner is alive, the new opener receives a lock-contention exception. The application must not independently infer ownership from `lastModified()`.

H2 reference: https://h2database.github.io/html/advanced.html#file_locking_protocols

## Changes

- Keep the existing three-attempt initialization with a short, bounded, increasing delay on H2 lock-contention errors. This preserves brief daemon-handover recovery without removing the active owner's lock.
- Centralize lock-conflict recognition in the pure-Java `H2SocketLockRetry` helper, including the H2 database-already-open error code (`90020`) and legacy exception wording.
- Delete all five age-based lock/trace cleanup methods. H2 owns the socket lock file, even when it looks old.
- Close any JDBC connection acquired before a subsequent schema/setup failure, before retrying or aborting initialization. In the SOC store, clear the initialized flag on that failure too.
- Keep the recordings index's existing corruption-rebuild behavior, but do not unlink its `.lock.db` during that operation. The index is derived data, but the lock is still owned by H2.

No database schemas, file paths, SQL statements, runtime scheduling intervals, or public APIs are changed. This PR does not change H2's own lock recovery or the rules for determining database corruption.

## Verification guidance (not executed)

1. Run the JVM unit tests for `H2SocketLockRetryTest` and `H2SocketLockOwnershipContractTest` in the normal CI environment.
2. Start the daemon on a parked head unit. Let SOC/recordings/notifications/trip/RoadSense stores remain open for more than five minutes, then simulate a second daemon trying to open the same store. The second process should fail or wait for the bounded retries; the first owner's `.lock.db` must remain untouched.
3. Shut down the first daemon cleanly and start the new one. H2 should release its lock and the new instance should open normally.
4. Terminate a store-owning JVM abnormally, then restart it. Verify that H2's socket-probe recovers the orphan rather than requiring the app to delete a file manually.
5. Inject a schema-creation exception **after** `DriverManager.getConnection` succeeds; verify the failed initializer releases its JDBC handle and subsequent start does not self-contend.
6. Check SOC and trip histories, the recordings library, notifications, and RoadSense after restart. Confirm retention and queries are unchanged.

## Compatibility and limitations

- Target: Android 10 (API 29), BYD DiLink 3.0; logic is pure Java and standard Kotlin interoperability.
- A genuinely busy store can remain unavailable after the bounded retries instead of being forced open. This is intentional: two concurrent embedded writers are a greater risk than a delayed startup.
- H2's socket locking assumes that all processes accessing a database share the same machine/network stack, as in this head unit. Cross-host shared-filesystem deployments are not supported by the current design.
- Builds, automated tests, and device checks have **not** been executed while preparing this change.

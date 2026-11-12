# SafeKeep thermal and recorder timers on DiLink 3

## Problem and risk

SafeKeep has two short elapsed-time intervals in its uploader safety logic:

- `UploadSignals` refreshes its selected SoC thermal sysfs reading every
  30 seconds. This reading feeds the thermal upload guard (80 C pause with
  hysteresis) and the conservative throughput reduction from 70 C.
- `Governor` holds uploads for 10 seconds after recording lag, excessive
  queue depth, or dropped muxer entries, allowing the recorder to recover.

Previously these intervals subtracted `System.currentTimeMillis()` values.
That clock can jump on a head unit after boot, NTP/GPS synchronization, or
manual clock correction. A backward jump can leave a previously cool thermal
sample in use until the wall clock catches up; it can also prolong an upload
hold unnecessarily. A forward jump can expire the recorder recovery hold too
soon, or trigger an extra thermal sysfs read.

## Change

- Both intervals now use Android's `SystemClock.elapsedRealtime()`, which
  advances monotonically across sleep and is available on API 29.
- The small, pure-Java `SafeKeepTiming` helper implements the interval/hold
  boundaries with a `-1` sentinel for "no earlier sample/event". An unseen
  recorder problem does **not** impose a false 10-second hold immediately
  after Android boots.
- A first thermal sample is due immediately; subsequent reads become due at
  30 seconds or later. The recorded thermal timestamp is taken *after* the
  sysfs read to avoid scheduling the next read prematurely after slow I/O.
- If the elapsed-time source ever resets unexpectedly while the process
  survives, thermal sampling runs promptly and an existing recorder hold
  remains conservative rather than becoming a negative-age bypass.
- No uploader policy limits, temperature thresholds, recovery durations,
  recorder statistics, permission checks, threads, dependencies, API payloads,
  or persisted data formats change. The cached 2/5-second signal snapshots
  already use elapsed time and remain unchanged.

## Regression tests added (source only)

`SafeKeepTimingTest` covers first reads, the exact 30-second refresh boundary,
10-second recorder holds, repeat unhealthy samples and backward elapsed-time
edge cases. These cases are pure-Java and require no Android service mocks.

## Suggested verification (not performed)

1. On a parked development head unit with SafeKeep enabled, check the
   diagnostics/telemetry logs show a new thermal measurement after roughly
   30 seconds, including when system time is corrected backward.
2. During a controlled upload and recorder-load simulation, trigger a
   recorder-busy indication and confirm uploads pause, remain paused while
   overload continues, and resume after 10 seconds of healthy recorder state.
3. If a safely parked development image allows clock correction, shift the
   wall clock backward and forward during these checks. Neither interval
   should change; repeat without any clock correction to check normal behavior.
4. Confirm recorder/battery/thermal safeguards and all uploaded chunk totals
   still behave as before, and that no additional sysfs polling or upload
   workers are introduced.
5. Run `SafeKeepTimingTest` in the regular CI/Gradle suite separately.

The source patch was prepared without building, executing tests, collecting
vehicle logs, or running software on a head unit.

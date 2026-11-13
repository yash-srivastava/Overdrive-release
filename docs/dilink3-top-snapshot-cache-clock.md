# Process snapshot cache clock on DiLink 3 (Android 10)

## Scope

`TopSnapshotCollector` samples Android Toybox `top` on demand for the process
view in the performance dashboard. A short 750 ms cache lets adjacent HTTP
requests reuse one snapshot instead of launching another shell process.
This is a cache-only change; it does not change Toybox commands, collected
fields, output JSON structure, polling policy, or per-request process limits.

## Problem

The cache previously computed its age using `System.currentTimeMillis()`.
The head unit's wall clock may be corrected after startup or after a loss of
time synchronization. If it moves **backward**, `now - cachedAtMs` is negative
and still satisfies `<= 750`. Even when seconds or minutes pass, the same
process/CPU/memory snapshot may be served until the clock catches up to the
previous timestamp. A forward correction instead expires the cache early and
can trigger an unnecessary `top` invocation.

Additionally, the old code timestamped a cache entry **before** executing
`top`. If the command took longer than the cache window, immediate follow-up
requests spawned `top` again instead of reusing the finished snapshot.

## Change

- Use `SystemClock.elapsedRealtime()` for cache expiry, which is monotonic
  across wall-clock and timezone corrections on Android 10 / API 29.
- Record the cache completion time **after** `top` has returned and its
  output has been parsed. Requests within the next 750 ms reuse that result.
- Reject negative ages in the cache-window helper. A defensive regression
  check prevents backward timestamps from being interpreted as fresh.
- Continue reporting the real epoch timestamp in the JSON `timestamp` field;
  the response contract and the separately measured command duration remain
  unchanged.
- Retain the existing capture lock, process-count limit, and supported
  Toybox `-m` fallback behavior. No new threads or periodic polling are added.

## Parked-head-unit verification (not performed for this change)

1. With the vehicle safely parked, open the performance dashboard's process
   list and confirm that Toybox process rows still appear normally.
2. Refresh twice within 750 ms of a completed capture and confirm that the
   short-lived cache still prevents repeated `top` launches (for example by
   observing the existing snapshot diagnostics/logging in a development build).
3. Correct the system time backward while the view is open, if an isolated
   test setup permits it. Confirm that the process list refreshes again after
   the usual 750 ms elapsed-time window rather than freezing until wall time
   catches up. Also check a forward time correction.
4. Confirm that JSON `timestamp` is still Unix epoch milliseconds, while
   `durationMs` still reports the Toybox capture duration.
5. Review the two cache-window regression cases in
   `TopSnapshotCollectorTest` when the normal project tests are run separately.

No build, unit test, benchmark, or vehicle validation was run while preparing
this patch.

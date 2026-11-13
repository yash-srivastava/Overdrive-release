# DiLink 3 geocoder: clean up discarded queued lookups

## Problem

`GeocodingResolver` coalesces callbacks for the same geohash/locale under an
`inflight` map. Each queued lookup is the *leader* that eventually removes its
map entry and invokes callbacks. The executor has two workers and a bounded
1,024-task queue; overload intentionally drops the oldest queued lookup to
favor recent locations.

The previous executor used `DiscardOldestPolicy` and `submit(Callable)`.
Discarding an old `FutureTask` never ran its `finally` block, so its `inflight`
entry remained. All later requests for that geohash/locale piggybacked on the
abandoned lookup, which could never finish. Repeated saturation could retain
stale callback lists for the daemon's lifetime and prevent fresh place labels.
The executor's shutdown rejection was also silent.

## Change

- `GeocodeDiscardPolicy` keeps the bounded queue and oldest-first eviction,
  but informs dropped tasks so they clear the corresponding `inflight` entry.
- Lookup tasks use `execute(Droppable)` rather than `submit(Callable)`, so
  the rejection policy sees their cleanup hook instead of an opaque FutureTask.
- If the queue is refilled during the rejection handler, the newly rejected
  task also cleans its registration. Shutdown rejection likewise cleans up.
- Successful lookup callbacks still drain through the existing per-key
  `ConcurrentHashMap.compute` gate. Evicted work invokes no callbacks (the
  established best-effort contract), but callers are free to retry it.

This does **not** change Nominatim token limits or cooldowns, SafeLocation
privacy ordering, geocoding opt-in, cache keys/files, or network permissions.
It uses Java concurrency APIs available on Android 10 (API 29); there are no
BYD-specific SDK dependencies or migrations.

## Proposed verification (not executed)

1. Run the pure-Java `GeocodeDiscardPolicyTest`. Check that a saturated
   one-worker/one-slot executor removes the older queued task, cleans its
   registration, and executes the newest task when the worker is released.
2. Check that an executor already shut down rejects a new task and calls its
   cleanup hook without running the lookup.
3. On a development head unit while parked, generate more unique uncached
   location lookups than the 1,024 pending-task limit. Verify memory does not
   accumulate abandoned callback entries and a later request for a discarded
   geohash can become a new leader.
4. Check normal SafeLocation/cache/Android Geocoder/Nominatim resolution and
   callback delivery when the queue is not saturated. Confirm that online
   requests remain opt-in and continue obeying the existing rate limiter.

No Gradle build, automated tests, benchmark, or head-unit run was performed
while preparing this source patch.

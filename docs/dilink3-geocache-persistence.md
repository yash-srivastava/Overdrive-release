# Place-name cache persistence on DiLink 3 / Android 10

The daemon's `GeoCache` stores resolved place labels in a cross-UID JSON file.
The on-disk cache is loaded on demand, and writes are normally coalesced into
one flush after 30 seconds of inactivity. A successful flush should not be
required for camera recording or live vehicle functionality.

## Failure addressed

Previously, `put()` did not load the existing file before writing. If the first
operation after daemon startup was a cache insertion, the later flush could
replace the persisted list with only the labels resolved in that process.
A normal `get()` or explicit startup `ensureLoaded()` happened to avoid it,
but direct `put()` callers could not rely on that ordering.

Both a scheduled flush and `flushNow()` also set the dirty flag to `false`
*before* calling `saveToDisk()`. As that method logs and catches I/O failures,
a failed attempt cleared the only indication of pending changes. On a healthy
filesystem, an unrelated later `put()` could eventually save them; without
another write, changes could remain unsaved until daemon exit.

## Behavior after the change

- An initial `put()` loads existing entries before updating the in-memory map.
- The dirty flag clears only when `saveToDisk()` reports success (including
  a completed fallback direct write when a rename cannot be performed).
- Failed scheduled flushes retry on the existing single daemon executor after
  the same 30-second delay, without a tight I/O retry loop.
- Failed synchronous `flushNow()` calls retain the dirty flag and schedule a
  delayed retry. Callers still receive no success/failure result, so an urgent
  invalidation is **not guaranteed durable** if storage remains unavailable.
- No changes to JSON format, cache TTL, cap/eviction policy, geohash precision,
  location lookup networking, or foreground UI work.

## Manual verification (not performed)

Use a **parked** compatible head unit; only manipulate the cache in a development
or diagnostic environment, with a backup of the existing cache file.

1. With an existing `geocache.json`, cause a `put()` before any explicit cache
   lookup and flush; confirm that previously persisted labels are still present.
2. While a new label is pending, temporarily make the cache destination
   unwritable. Confirm the failure is logged and the pending update is retried
   approximately 30 seconds later, rather than requiring another `put()`.
3. Restore write access; confirm the next retry persists the current snapshot,
   and that a later clean flush does not rewrite the file needlessly.
4. Repeat with a `flushNow()` call during the storage failure; confirm that
   the failure does not discard the dirty state.
5. Check cache-hit behavior and place-name labels after a daemon restart.

No build, automated test, benchmark, or hardware verification has been run for
this change. Transient I/O recovery and filesystem permissions should be
confirmed on the target BYD DiLink 3.0 (Android 10, API 29) firmware.

# Recording index: bounded FileObserver dispatch

## Problem

`RecordingsIndexFileWatcher` previously called `RecordingsIndex.upsert()` and
`removeByPath()` directly from Android's `FileObserver.onEvent` callback.
An upsert can read an MP4 and parse its JSON sidecar from a slow FUSE-mounted
SD card before briefly writing to the H2 index. Under bursts of finalized
segments, sidecar writes or file cleanup, this synchronous I/O holds up the
FileObserver event-delivery thread. Kernel notifications can be lost while
callbacks lag, leaving the recording library stale until periodic reconcile.

There was also a separate completeness gap: atomic `.json` sidecars published
with `MOVED_TO` were not treated as updates, although other writers already
use temporary files and renames.

## Changes

- `onEvent` now only validates a basename and submits an MP4 path plus desired
  `REFRESH` or `REMOVE` operation. No `File.length()`, JSON parse or JDBC work
  happens on the callback thread.
- A daemon single-thread worker drains a **512-distinct-path** bounded map.
  Repeated notifications for an outstanding path collapse to its latest
  operation. A newer event arriving while that path is being processed is
  queued separately, preserving eventual last-event ordering.
- When more than 512 distinct paths are pending, dropped notifications trigger
  one background `RecordingsIndex.requestReconcile()` after the batch. A
  rejected worker schedule also asks for reconciliation, avoiding silent loss.
- `CREATE` alone still does not index unfinished MP4s. `CLOSE_WRITE` and
  `MOVED_TO` signal an MP4 ready for indexing; `DELETE` / `MOVED_FROM`
  request removal. Missing MP4s on a queued refresh are removed from the index.
- JSON `MODIFY`, `CLOSE_WRITE` and `MOVED_TO` refresh the sibling MP4. This
  picks up metadata written in-place or published with an atomic rename.
- `refresh()` retains queued work while switching watched storage roots and
  requests a background reconciliation to discover files already present on
  the newly selected mount.
- `stop()` closes the queue before unregistering observers. Late callbacks
  from the old observer set are ignored; shutdown does not interrupt an
  in-flight database operation.

No recording formats, database schema, HTTP API or retention policy changes.
The existing hourly reconcile remains a fallback for missing kernel events,
rejected scheduling and storage read errors.

## Compatibility and limitations

Android 10 / API 29 has the `FileObserver(File, int)` constructor; existing
legacy constructor selection is retained for older Android devices. The
single worker is daemon-only and performs no work when the index is idle.
A saturated queue can temporarily delay listing updates until reconciliation
finishes. Events already dequeued before `stop()` can finish one in-progress
operation; index shutdown coordinates with the database's existing close gate.
This is eventual consistency, not a transactional journal for filesystem
changes. `FileObserver` cannot guarantee notification delivery across FUSE
mounts, which is why reconciliation is intentionally retained.

## Suggested validation (not executed as part of this patch)

1. Run `RecordingIndexEventQueueTest` on the JVM. Verify burst coalescing,
   final event ordering, cap/overflow repair, late callback shutdown,
   executor rejection and failure recovery.
2. On a parked DiLink 3.0 unit, finalize recordings and atomically update
   JSON geo sidecars. Confirm the recordings API gains the MP4 and then the
   updated location without needing the hourly scan.
3. Generate a burst of file changes on an SD card. Inspect the daemon log for
   slow-index operations or reconciliation; verify the filesystem observer
   continues delivering events and memory usage stays bounded.
4. Switch between internal and SD storage while old events are pending.
   Verify previously present recordings are discovered and stop/restart does
   not re-submit old callbacks.
5. Unmount/reinsert storage during updates. Verify that a failed update does
   not stall subsequent events, and that reconciliation repairs the library.

No build, automated tests, benchmarks or device testing were performed while
preparing this change.

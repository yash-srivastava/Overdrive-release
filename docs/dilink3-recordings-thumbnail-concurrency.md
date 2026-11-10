# DiLink 3 recordings thumbnail workload

The recordings list generates thumbnails on `Dispatchers.IO`, preferring a
sidecar JPEG and falling back to `MediaMetadataRetriever` for older clips.
The shared IO dispatcher does not limit the number of simultaneous decoders
started by one list: rapidly binding multiple visible rows can run multiple
native frame extractions and bitmap allocations at the same time.

This change uses a **two-permit coroutine semaphore** per `RecordingAdapter`
to bound simultaneous thumbnail pipelines (including remote fallbacks). Jobs
waiting for a permit suspend without occupying a thread. Existing per-row
cancellation, 8 MiB thumbnail cache, decode priority and recycling behavior
remain in place. The semaphore is held during the remote retry delay, which
may slow loading of other thumbnails during network errors; this is an
intentional tradeoff to avoid unbounded concurrent work.

This affects the recordings list UI, not continuous video capture, camera
recording, vehicle control, or other apps on Android. The number of active
pipelines is bounded per adapter instance, not globally across all adapters.
The change is expected to reduce peak concurrent CPU/memory pressure while
scrolling, but no FPS or memory improvement has been measured.

## Manual verification (parked vehicle)

1. Open a large recordings library with both sidecar-backed and legacy clips.
2. Scroll quickly in both directions; verify thumbnails remain correct and
   recycled rows never show another recording's thumbnail.
3. Repeat while the daemon is slow or unreachable; verify responsiveness,
   cancellation, and retry handling.
4. Compare peak Java/native heap, GC pauses, frame time percentiles, and
   visible thumbnail latency with the previous implementation on DiLink 3.
5. Close/reopen the recordings list and verify no retained decoder work.

No build, automated test, benchmark, or device verification was performed.

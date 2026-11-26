# Bounded daemon recording thumbnail generation

Both `/thumb/<filename>` and `/thumb/id/<recordingId>` prefer existing JPEGs.
For cache misses, the daemon previously retained an unlimited number of unique
jobs in a single-thread executor. Bursts of requests could leave the decoder processing
old requests long after the viewer had left the page.

The shared admission gate now permits at most 17 outstanding distinct keys,
including running work; duplicates and excess requests are not enqueued. A
single decoder worker still serves accepted jobs in submission order and retires
after 30 seconds without work. Requests that do not get a slot receive the same
202/no-store/Retry-After response as pending jobs, so a later retry can enter.
Each admitted job rechecks the cache before decoding and releases its key and
slot even if generation fails. The executor also has a physically bounded queue.

Frames are still extracted with `getFrameAtTime` and scaled to 320x180. The full
frame is recycled as soon as it has been scaled. JPEG quality, lens correction,
sidecar and cached JPEGs and media data-source selection are unchanged.

## Verification plan (parked vehicle)

- Run `./gradlew assembleDebug` and `./gradlew test`, including
  `ThumbnailWorkQueueTest`.
- Compare cache-miss generation on legacy and current clips, including raw lens
  tags, non-16:9 sources, rotated metadata, short clips and removable storage.
- Burst more than 17 distinct uncached requests, repeat one key concurrently,
  and navigate away. Confirm no unbounded backlog and that later retries can
  generate previously rejected thumbnails. A stalled native decoder can still
  block accepted jobs; the queue cap is not a decoder timeout.
- Compare daemon CPU, peak Java/native heap, PSS, GC, generation latency and
  recording responsiveness using identical clips and cache state. Verify normal
  cached responses, JPEG dimensions, and no-store responses for in-flight clips.
- Confirm the worker retires when idle and restarts on the next cache miss.

Under sustained overload, finite client retry budgets can expire before admission;
reopening the page may be required. This is an intentional initial trade-off to
bound background work, not a guarantee of latency or fairness.

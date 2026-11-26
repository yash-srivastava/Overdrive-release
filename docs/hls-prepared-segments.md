# Request-local HLS segment preparation

The timeline daemon must know the exact fMP4 content length before sending the
HTTP response header. Previously `streamSegment` built a media/skim plan to get
that length, discarded it, then constructed the same plan again to stream the
body. Both calls walk the same sample metadata, build the same fragment boxes
and, when necessary, build the same AAC silence-repair run.

`Fmp4Writer.prepareMediaSegment` and `prepareSkimSegment` now provide an opaque
request-local `PreparedSegment`. Its `length()` and `writeTo()` use the same
plan and immutable MP4 index. `TimelineApiHandler` holds this object only for
the duration of the request. It does not cache complete segments or open files.

```
Before: plan -> length -> HTTP headers -> plan again -> stream samples
After:  plan -> length -> HTTP headers -> reuse plan -> stream samples
```

For N successfully streamed segment requests, plan construction drops from 2N
to N (50%). Only plan construction is reduced; media bytes, sample copy buffers,
storage I/O, playback quality, and network traffic are unchanged. The prepared
header and optional audio run now live until the request finishes, rather than
being discarded after length calculation. The existing streaming semaphore,
stale source size checks, bounded output, and stall watchdog remain in place.
The original length-only and writer entry points remain available for other
callers. No shared segment cache or persistent memory is introduced.

## Validation

`PreparedSegmentTest` covers exact content length, byte-identical media and
skim output, synthetic silent audio, and stale source rejection before any
segment bytes are written. For device validation, use a parked head unit and
play normal clips, video-only clips, mixed audio/video windows and 4x/8x skim
playlists. Compare segment bytes, lengths, playback continuity and daemon CPU
and allocation profiles before and after. Full Android build and device
validation are still required.

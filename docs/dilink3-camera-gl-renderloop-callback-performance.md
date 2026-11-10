# Camera GL render-loop callback reuse

## Background

`PanoramicCameraGpu` posts its render loop to the GL thread's `Handler`
when recording starts and again in `finally` after every render iteration.
Previously, each post evaluated `this::renderLoop`, a bound method reference
that can create a short-lived `Runnable` on each frame. At 30 frames per
second, that is potentially 1,800 callbacks allocated per minute.

## Change

Keep one final per-instance `Runnable` and post that same object at startup
and after subsequent iterations. This leaves the existing single-threaded
handler queue, frame-ready signaling, `running` checks, watchdog, and
exception/finally rescheduling logic unchanged. No changes are made to
camera buffers, GPU operations, readbacks, encoding, or timestamps.

This is a modest allocation-pressure optimization on the continuous camera GL
path, not a claim that the encoder or overall head-unit FPS will improve. A
JVM may optimize method-reference allocations itself, so the real benefit
must be measured on the target Android runtime.

## Manual verification (parked vehicle)

1. Start a panoramic recording and verify preview and recorded video retain
   the expected frame rate and frame timestamps.
2. Stop and restart the camera repeatedly; confirm rendering resumes each
   time and the watchdog does not report new stalls.
3. Compare allocation traces and GC activity on the camera GL thread before
   and after the change at the same resolution and frame rate.
4. Review dropped frames and encoder latency under sustained recording and
   while other dashboard features are active.

No builds, tests, benchmarks, or device verification were performed when
preparing this patch.

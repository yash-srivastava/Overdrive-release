# Skip no-op AI-lane GPU barriers (DiLink 3)

## Problem

The parked-surveillance AI lane holds `cameraTextureLock` while sampling the
camera's shared OES texture. A GPU completion barrier is essential **when the
lane has queued a draw**; otherwise the encoder thread could recycle the
camera's backing buffer before GPU sampling finishes (native driver crash).

Previously the AI lane ran that fence/wait on **every** notified tick,
including ticks skipped by the motion readback cadence with no pending
foveated request, and ticks when the motion worker was still busy. A GPU
fence/wait, or its `glFinish` fallback, can cause unnecessary synchronization
and lock contention even when the lane did not submit a camera sampling draw.

## Change

- Keep the existing `cameraTextureLock`, modulo/interval pacing, and GPU
  completion barrier; do not change recording frames, buffer ownership, or
  encoder timing.
- Have foveated servicing report whether it attempted a crop. It reports true
  even if the asynchronous crop returns no result or throws after it starts.
- Track attempted motion readback **before** calling the GPU readback routine,
  because a null result or exception may follow an already-queued draw.
- Run the existing fence/finish barrier whenever **either** readback or crop
  could have touched the shared OES texture. Only skip the barrier when
  neither operation was attempted.

The optimization is relevant to the legacy camera/AI lane, including DiLink 3.
The DiLink 4/5 paths are not changed, though they share this AI-lane class.

## Manual verification (not performed)

1. On a parked vehicle, arm surveillance and confirm motion detection and
   foveated/YOLO events still work (including first samples and empty crops).
2. Observe `AiLaneGl` GPU fence timing and camera/encoder frame times with and
   without the change on the same firmware and workload.
3. Run extended parked surveillance and verify no OES use-after-free crashes,
   dropped frames, stale frame content, or increased event latency.
4. Confirm the encoder thread remains responsive during AI worker congestion
   and during repeated surveillance arm/disarm.
5. Exercise the GLES2 fallback where available; actual crop/readback draws
   must still retain `glFinish` when GPU fences are unavailable.

No build, tests, benchmarks, or vehicle verification were performed.

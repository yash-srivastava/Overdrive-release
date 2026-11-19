# OEM DVR overlay: conditional CPU allocation and measurement plan

## Scope and reviewer feedback

Follow-up to [#808](https://github.com/yash-srivastava/Overdrive-release/pull/808).
The original patch skipped `glClear(GL_COLOR_BUFFER_BIT)` and deferred *all*
telemetry overlay setup until a frame needed it. The reviewer requested a car
measurement of GPU-time savings before dropping the clear and warned that
compiling shaders, loading icons and allocating bitmaps during recording could
cause frame stalls. A GL-thread call to the unsynchronized telemetry reconciler
also risked an unmatched polling start.

This revision **keeps the clear on every frame**, **compiles the overlay shader
and allocates its texture at normal EGL startup**, and only defers the software
1280×80 ARGB bitmap pair, icon decoding and fonts. Those CPU resources are
created on a lifecycle/config caller when burn-in becomes enabled, never inside
`drawPassthrough()`. The two bitmaps account for 819,200 bytes (~0.78 MiB),
excluding icon/font overhead. This is a modest memory optimization, not a
claimed FPS increase. Burn-in can still be enabled during recording without a
shader compilation or bitmap construction on the GL thread.

`OemOverlayLifecycle` serializes overlay creation, raster worker ownership and
collector-specific polling leases. A collector replacement releases the old
collector's hold before acquiring the new one; clearing the collector or
stopping the clip releases its hold. Its Android-independent behavior tests
exercise these transitions with concurrent callers and fake resources.

## Parked-car measurement procedure (not performed for this patch)

1. On the same DiLink hardware and OS, record the exact device, GPU, build,
   encoder mode/resolution/FPS, thermal state, and whether the pano pipeline is
   also active. The supplied diagnostic snapshot reports Android 10 (API 29),
   Qualcomm Trinket and `Adreno610v1`, but has no render/GPU timings.
2. With the car safely parked, capture baseline data from the upstream
   revision and candidate data from this patch. Repeat at least 10 cold DVR
   starts and three 5-minute recordings per configuration with burn-in disabled,
   enabled before start, and toggled during recording. Keep all other camera
   options fixed; alternate the baseline/candidate order to reduce thermal bias.
3. For **opt-in CPU time** logging, start the daemon with the Java system
   property `-Doverdrive.oem.dvr.timings=true` (pass this to the daemon JVM,
   not the app UI). Collect `OEM timing` log lines, which report milliseconds
   from EGL/overlay preparation through render-loop startup, and the mean and
   maximum CPU draw and `eglSwapBuffers` call duration per 300 output frames.
   Keep the diagnostic **off** for the normal recording comparison.
4. In parallel, obtain a Perfetto / GPU trace with supported Adreno GPU
   counters or an on-device GPU-time query. Measure GPU execution time,
   frame-time percentiles (p50/p95/p99), frame drops and memory usage.
   `eglSwapBuffers` call duration **is not GPU execution time**. The GPU
   clear optimization should only be reconsidered if that instrumentation
   demonstrates a repeatable GPU-time win on a DiLink device.
5. Compare startup distributions, frame-time percentiles, encode timestamps,
   dropped frames, shader-compile events, heap/PSS and polling-thread counts.
   Confirm `TelemetryPoller` has only one active scheduler even after rapid
   burn-in toggles, collector replacement, writer abort and repeated restart.
   Verify produced video visually with burn-in disabled, enabled at startup,
   enabled mid-clip, disabled again and after another DVR start.

## Validation boundary

The implementation and tests were inspected statically. No app build, unit
suite, GPU trace, recording on a car or hardware performance benchmark was run
when preparing this change. The presence of logging does not establish a
startup or per-frame performance improvement.

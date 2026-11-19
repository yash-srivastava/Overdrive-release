# YUV WebGL texture upload experiment (PR #824 follow-up)

`app/src/main/assets/web/shared/YUVCanvas.js` supports an experimental
`reusePlaneTextures: true` constructor option. The **default is false** until
there is evidence of a benefit on a DiLink 3 head unit running Chrome 58 WebView
with the Broadway decoder fallback. Normal behavior continues using
`texImage2D` for every frame; the option uses `texSubImage2D` when dimensions
are unchanged, with separate Y/U/V texture sizes and explicit allocation checks.

Both modes keep the per-frame `bufferData` calls for crop attributes. This
intentionally preserves GPU buffer orphaning: omitting these calls previously
produced 40–48 ms main-thread stalls approximately every nine frames when the
canvas was hidden or detached. Crop coordinate `Float32Array`s and the
animation vertex buffer are reused; the animation quad retains all four
corners. A failed WebGL texture allocation is not cached and is retried on the
next frame. In `customYUV444`, each chroma plane defaults to its own unpacked
width/height, not to the other plane's stride.

## Performance evidence — before enabling reuse by default

1. On a **real DiLink 3** device, record the Android, WebView and GPU versions,
   actual renderer type (`yuv420` / `customYUV444` / `yuv422`), whether Broadway
   fallback was used, input resolution, stride, and nominal frame rate.
2. Record **at least five comparable 60-second runs** per mode, alternating
   default/reuse to reduce warm-up and thermal effects, on the same stream.
   Include median, p95, p99 and maximum CPU frame time and total frame count.
3. Measure visible/composited and **hidden or detached** canvas cases. Track
   main-thread long tasks and the frequency/size of WebGL upload stalls;
   repeat with a change in decoder stride and resolution.
4. Repeat measurements with `gl.finish()` as a **separately labeled** GPU-sync
   experiment. Never compare synchronized times with unsynchronized times.
   Also verify crop geometry visually, including the animation and U/V padding.
5. Compare the baseline with the opt-in mode at 1280 × 960, 30 fps to the prior
   review's observations (0.13 vs 0.15 ms/frame unsynchronized; approximately
   4.5 ms/frame for both with `gl.finish`). Do not present those older results
   as measurements of this patch.

| Metric | Default (`false`) | Experimental (`true`) |
| --- | --- | --- |
| DiLink 3 / WebView 58 hardware and decoder | not measured | not measured |
| Visible: median / p95 / p99 CPU ms/frame | not measured | not measured |
| Hidden/detached: p95 / max and long tasks | not measured | not measured |
| `gl.finish()` median / p95 ms/frame | not measured | not measured |
| 1280 × 960 @ 30 fps frame count / dropped frames | not measured | not measured |
| Stride and resolution change / failed allocations | not measured | not measured |

A mock WebGL contract is provided at `tools/yuv-canvas-upload-check.cjs`
(`node tools/yuv-canvas-upload-check.cjs`). It covers both modes, independent
Y/U/V layouts, allocation retry, YUV422 uniforms and animation quad vertices.
It does **not** measure performance or detect GPU stalls and is not proof of
compatibility with any vehicle. This diagnostic is deliberately outside the
open-only `tools/tests` directory.

The uploaded device diagnostics describe a BYD AUTO Android 10 / API 29 Qualcomm
`trinket` platform. They provide **no** Chrome 58 WebView confirmation, no
DiLink 3 renderer trace, and no texture-upload timings. Do not use that archive
to fill the missing DiLink 3 benchmark fields.

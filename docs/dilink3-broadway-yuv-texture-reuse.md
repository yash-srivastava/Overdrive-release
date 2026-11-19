# Broadway WebGL YUV plane texture reuse (experimental)

The Broadway `Player` still uses `texImage2D` on every frame unless
`reusePlaneTextures: true` is passed to it. The live-view (`stream.js`) and
picture-in-picture (`pip.js`) Broadway fallbacks now pass that option when the
**page URL** contains `?reusePlaneTextures=1` (or `&reusePlaneTextures=1` after
other parameters). With the parameter absent or set to `0`, those callers keep
the original per-frame texture upload path. WebCodecs and JMuxer are unaffected.
Changing the parameter requires reloading the page/recreating the Player.

On the opt-in path, an initial allocation or change of decoder stride/rows
uploads with `texImage2D`; subsequent uploads of the same size use
`texSubImage2D`. A short decoder plane is rejected before uploading. WebGL
reports allocation errors such as `OUT_OF_MEMORY` through `getError`, rather
than throwing, so **only allocation/resize** checks that error and caches the
size after a clean result. The following frame retries an unsuccessful
allocation. This one-off error query can itself stall the GPU; it must be
included in the device-level measurements, not treated as free. `TypeError`s
are not suppressed. Non-four-byte luminance strides use unpack alignment 1.

## Needed evidence: DiLink 3, Chrome 58 WebView

1. On a parked DiLink 3 head unit, confirm the Chrome/WebView version and the
   Broadway decoder badge (not WebCodecs/JMuxer). Record the camera mode,
   stream quality, resolution, decoder stride and measured incoming FPS.
2. Open the same live-view stream with the page parameter absent (`off`),
   then with `reusePlaneTextures=1` (`on`); repeat for PiP if available. Keep
   the view, FPS, vehicle state and other running features the same.
3. In remote WebView DevTools, capture a warmed-up interval of at least 300
   decoded/drawn frames per run. Repeat each condition at least three times,
   alternating off/on. Report the median and p95 render/upload CPU time per
   frame, total frame interval, dropped frames, and any long GPU stalls.
   `performance.now()` around JS calls alone does not measure queued GPU work.
4. Repeat after a view/resolution change and after an interrupted/malformed
   frame, looking for blank canvases and retries. Include whether the driver
   reported GL allocation errors.
5. Report each run separately and aggregate the paired runs. Do not infer a
   benefit from a synthetic desktop run or a single small median difference.

**Status:** No DiLink 3 / Chrome 58 / Broadway on-device measurements are
available with this change. The maintainer reported desktop Chrome variants
within about 0.1–0.3 ms median/frame; that is not DiLink 3 evidence. No
runtime build, automated project test, or device benchmark was performed as
part of this patch.

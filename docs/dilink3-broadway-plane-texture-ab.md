# DiLink 3 Broadway plane-texture A/B protocol

This experimental path carries the optional WebGL plane-texture reuse change
from [PR #882](https://github.com/yash-srivastava/Overdrive-release/pull/882)
and the preceding [PR #875](https://github.com/yash-srivastava/Overdrive-release/pull/875).
It is **off by default**. No on-car improvement has been measured or claimed.
The legacy Broadway GL sequence remains unchanged with reuse off.

## Diagnostic switch, without changing the Live View route

In a diagnostic WebView session, enable the switch once using the JS console
(or a remote WebView inspector attached to the car app):

```js
sessionStorage.setItem('od.broadwayAbDiagnostic', '1');
location.reload();
```

Only then does the **A/B** control appear in the Live View camera **top bar**.
The **Cameras** header is untouched; ordinary users see no new control and
cannot accidentally force software decoding. Tapping the control cycles:

| Mode | Decoder | Plane uploads |
| --- | --- | --- |
| Auto | Standard WebCodecs → JMuxer → Broadway fallback | Legacy Broadway if used |
| Reuse off | Forced Broadway; backend also receives `decoder=broadway` | Original `texImage2D` per frame |
| Reuse on | Forced Broadway; backend also receives `decoder=broadway` | `texSubImage2D` for stable planes; `texImage2D` on allocation/resize and every 30 uploads |

The mode is stored under `od.broadwayAbMode` in `sessionStorage`; taps call
`location.reload()` on the **original** `/live-view` URL. Android's page router
matches the full path and cannot serve `/live-view?decoder=broadway`.
A missing diagnostic switch disables both overrides, even if the mode key
remains in the session. Leave diagnostics using:

```js
sessionStorage.removeItem('od.broadwayAbDiagnostic');
sessionStorage.removeItem('od.broadwayAbMode');
location.reload();
```

The toggle and the in-page summary have English and Hebrew translations.
The existing WebCodecs/JMuxer behavior and DiLink 5 Broadway fps safeguards
are unchanged outside diagnostic sessions. The mode does not persist beyond
the tab's `sessionStorage` lifetime.

## How to collect comparable numbers on a real vehicle

Use the same **DiLink 3 Chrome 58 WebView**, camera, resolution, bitrate, quality
preset, power/visibility conditions, and input stream in both forced Broadway
modes. Run at least three sessions per mode, each exceeding 300 stable decoded
frames. Verify the decoder badge shows **CPU**, not GPU/JMuxer or SOTA/WebCodecs,
and the backend's enable/view requests contain `decoder=broadway`.

`Player.js` records two timings per frame, only in forced diagnostic mode:

- **CPU call**: `performance.now()` elapsed around `drawNextOutputPicture()`.
  This measures issuing WebGL commands, **not** GPU completion.
- **Draw + GPU finish**: wall-clock elapsed from the same start through
  `gl.finish()`, which waits for queued GPU operations to complete. This
  **includes CPU call time and GPU synchronization**; it is not pure GPU time.
  `gl.finish()` is never called by the added code on normal playback paths.

For each 300-frame stable window, the page shows mean and p95 (milliseconds)
for the **normal** and **every30th** buckets; resize/startup frames are
separate. The console additionally prints `[Broadway A/B]` as JSON with count,
mean, median/p50, and p95 for both timing types. In release car builds console
output may be stripped; read the visible in-page summary instead. The
`every30th` bucket is a position-matched control in reuse-off mode, not a
texture-refresh event. A 30-frame allocation refresh retries drivers whose
allocation silently failed without blocking on `gl.getError()`.

Capture WebView version, device model, on-page mode and decoder badge, camera,
resolution/stride, received fps, elapsed session time, dropped/corrupt frames,
WebView CPU load, and GPU-inclusive mean/p95. Compare distributions; the
synchronization overhead can itself change timing and fps. **Do not compare
benchmarked runs against normal playback**. The source snapshot pins
`YUVCanvas.js?v=2`, `Player.js?v=2`, and `stream.js?v=index9` so the cache cannot
silently mix old and new decoder assets.

## Evidence and validation status

No real DiLink 3 A/B numbers were available with this code. Static review and
patch application alone cannot establish a speedup. A real head-unit run is
required before claiming reuse on is measurably faster, including the GPU work.

# DiLink 3 Broadway plane-texture A/B protocol

This is an **experimental, opt-in** texture upload path. No speedup is claimed.
The original Broadway WebGL calls are unchanged when reuse is off; reuse is
off by default. This document describes how to collect the missing on-device
evidence requested in [PR #875](https://github.com/yash-srivastava/Overdrive-release/pull/875).

## Choose the decoder and reuse mode on-car

Open **Live View** normally in the car app (`/live-view` has no query string).
Tap the small **A/B: Auto** button next to **Cameras** to cycle through:

| Button | URL parameters after reload | Decoder | Plane uploads |
| --- | --- | --- | --- |
| A/B: Auto | neither diagnostic parameter | Normal WebCodecs → JMuxer → Broadway fallback | Original `texImage2D` |
| A/B: Reuse off | `decoder=broadway` | Forced Broadway, even with working JMuxer | Original `texImage2D` each frame |
| A/B: Reuse on | `decoder=broadway&reusePlaneTextures=1` | Forced Broadway | `texSubImage2D` on stable planes; `texImage2D` at allocation/resize and every 30 uploads |

Each tap reloads the page and stops the current camera stream. Re-select the
**same camera** and quality preset after each reload. The query parameters work
on a desktop browser too. Returning to **Auto** removes both parameters; the
setting is not persisted, and the app's default entry point stays unchanged.

For the **off/on comparison**, use only **Reuse off** and **Reuse on**. Confirm
the browser console prints `[Stream] Initializing Broadway (CPU)` and the
decoder badge reads **CPU**, rather than **GPU** (JMuxer) or **SOTA** (WebCodecs).
For `/api/stream/enable` and camera selection, the Broadway backend hint must
also be present. This prevents a JMuxer run from being mistaken for a Broadway
measurement.

## Capture comparable numbers

Use a real **DiLink 3 head unit with Chrome 58 WebView**, not Chrome desktop.
Keep the camera, stream resolution, bit rate, fps, visibility, and connection
conditions the same in both runs. Take at least three sessions per mode, with
enough received frames for the 300-frame sample summary. Allow the first
startup/resize frame to be recorded separately.

When either forced Broadway mode is selected, `Player.js` measures the CPU time
around the renderer's `drawNextOutputPicture` call using `performance.now()`
(no `gl.getError()`, GPU fence, or readback). Every 300 steady-state frames it
logs `[Broadway A/B]` with the actual Broadway decoder mode, whether reuse was
enabled, the sample count, mean, median (p50), and p95 in milliseconds for:

- `normal`: the remaining steady-state upload/draw frames.
- `every30th`: frames 31, 61, 91, ... after each canvas allocation, which
  correspond to the forced-refresh cadence when reuse is enabled.
- `firstAfterResize`: a first-frame sample when the WebGL canvas is created
  or the decoded dimensions change.

These are **CPU-side JavaScript/WebGL call durations**, not elapsed GPU render
time. The first-frame sample measures draw/upload, excluding canvas
construction. In the baseline, `every30th` is only a *matching position*
bucket, not a reallocation event. A driver may fail a texture allocation
silently; the 30-upload refresh retries allocation without forcing a blocking
`getError()` readback.

Record the exact WebView version, device model, observed decoder badge, camera,
size/stride, actual fps, session duration, and the untouched logs for both
modes. Report distributions and frame counts, rather than a single favorable
frame. Do not infer better GPU performance from CPU samples alone; include
visual reliability and any dropped/corrupted frames.

The three separately versioned assets in `live-view.html` are
`YUVCanvas.js?v=2`, `Player.js?v=2`, and `stream.js?v=index9`. This prevents an
hour-cached old Broadway renderer from being silently paired with the new
stream controller during an A/B session.

## Evidence status

No DiLink 3 A/B measurements are included here. Static inspection and a clean
patch application do not establish a performance improvement. A maintainer can
decide whether to carry the opt-in after real on-car results are provided.

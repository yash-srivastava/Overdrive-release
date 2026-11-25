# Bound immersive vehicle rendering on the head unit

The immersive Vehicle page continuously paints its Three.js scene, even when
only the slow decorative rotation is running. The existing AVN limit is
approximately 30 FPS with a 1x CSS-pixel backing buffer. The native WebView
shares the head unit with the daemon and other system applications.

Use a 0.75x backing buffer and approximately 10 FPS for passive rendering in
the Android WebView. Keep the existing approximately 30 FPS cap during orbit
interaction, its one-second damping grace, finite GSAP animations and video.
Infinite decorative glow animations can use the passive cadence. Standalone
browsers keep their existing resolution and visible frame cadence. The Android
WebView already hides the 3D surround-view button; this change does not alter
camera resolution, video decoding, vehicle state polling or command handling.

| Head-unit scene | Previous | Proposed |
| --- | --- | --- |
| Backing-buffer pixel count | 100% | 56.25% |
| Passive draw cadence (nominal) | 30 FPS | 10 FPS |
| Passive pixels submitted per second (nominal) | 100% | 18.75% |
| Interactive pixels submitted per second (nominal) | 100% | 56.25% |

These are arithmetic workload estimates, not GPU utilization, power, memory or
whole-system benchmark results. Vsync, scene complexity, multisampling,
compositing, animation activity and driver behavior affect actual savings.
Expect softer model edges and slower, less smooth decorative rotation/pulses;
the DOM controls, text and state update cadence are unchanged.

The render callback is canceled and generation-invalidated on hidden-page or
lite-mode transitions. Returning to a visible immersive page starts one loop,
not multiple competing chains. Lite teardown also requests WebGL context loss
before replacing the canvas. Completed GLB loads dispose their Draco workers
and revoke the worker blob URL. Obsolete completed models and discarded model
meshes release geometry, materials and textures once per distinct object.
Failed or indefinitely stalled GLB loads are not made cancellable in this PR.

## Verification

The Node fixture `tools/test-vehicle-immersive-render-budget.js` checks the
cadence gates, hidden/reveal behavior, stale frame rejection and deduplicated
model resource disposal with mocked browser APIs. It is not a WebGL or device
benchmark. The Java asset contract retains its existing assertions and updates
the script cache-busting version.

On a parked head unit, compare the same immersive model before and after:

1. Measure idle and interactive render counts, WebView/daemon CPU, GPU load,
   memory and frame times for at least 60 seconds per condition.
2. Orbit and zoom; verify damping, short VFX, persistent glows, theme changes,
   resize, paint changes and status labels. Check acceptable visual sharpness.
3. Background and reveal the page repeatedly, and rapidly toggle lite/immersive.
   Verify no hidden rendering, blank canvas or duplicate callback chains.
4. Switch models repeatedly and inspect worker counts, heap and GL contexts.
   Check slow/failed downloads and retry behavior. Verify desktop/mobile
   rendering and the existing browser surround view remain unaffected.
5. Verify recording and live view while the immersive page is visible, using
   matched settings and a parked vehicle. Run the standard Android build and
   unit suite before merging.

No Android build, device measurements or full unit suite were run when
preparing this change.

# Avoid per-frame radial shader local-matrix updates in glass battery widgets

## Problem

`CellPainter.drawLive()` animates the battery liquid halo and surface highlight.
Both radial shaders were repositioned on every draw by calling
`Shader.setLocalMatrix()`. On Android's hardware-accelerated Canvas path, a
changing shader local matrix can force native shader state to be updated again
for each frame.

The project already documents the alternative in `GlassKit.kt`: keep radial
shaders in unit space and transform the Canvas while drawing.

## Change

- The liquid halo now draws a unit circle after translating/scaling the Canvas.
- The surface highlight keeps its existing offset centre, radius and visible
  oval bounds, but expresses the geometry in normalized coordinates under a
  Canvas transform.
- The radial shaders remain in their original unit coordinate system.
- The liquid depth and core shaders are unchanged; this patch does not alter
  their gradient geometry or the battery rendering lifecycle.

No texture formats, camera capture, encoder, EGL contexts, or vehicle controls
are changed.

## Verification

A source-contract test checks that the animated halo and surface use Canvas
transforms and no longer call `setLocalMatrix()` on those two shaders.

No Android build, automated test execution, frame-time benchmark, or physical
BYD Dolphin validation was performed. The expected benefit is reduced
per-frame native shader-matrix churn; actual GPU/RenderThread improvement must
be measured on the target head unit.

## Target hardware context

This optimization is intended for the BYD Dolphin DiLink 3 head unit with the Qualcomm Adreno 610 GPU running Android 10 (API 29). The diagnostic profile reports the Adreno 610 at 465 MHz; this clock reading is not a GPU-utilization measurement. No device-specific frame-time or power improvement is claimed without an on-vehicle benchmark.

Hardware profile label: Adreno 610 – DiLink 3.

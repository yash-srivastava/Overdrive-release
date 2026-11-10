# DiLink 3 camera preview: decode only the requested mosaic quadrant

The diagnostic camera-mapping preview crops a quadrant from a cached mosaic JPEG
on an HTTP worker thread. Previously it decoded the entire mosaic to an ARGB
bitmap, then created a second bitmap for the selected quadrant. A 2560×1920
mosaic alone occupies roughly 18.75 MiB as ARGB_8888, before the quadrant
bitmap, JPEG buffers and decoder overhead. This can create avoidable heap
pressure and garbage collection on Android 10 head units.

Use Android's `BitmapRegionDecoder` to decode the selected quadrant directly.
The quadrant mapping, rectangle boundaries, JPEG quality (82), null/error
handling, and resource cleanup remain unchanged in intent. No camera capture,
GL rendering, recording, or vehicle controls are modified. The optimization
applies to the diagnostic mosaic-preview endpoint, not the live recording loop.

## Manual verification (not executed)

- On a parked DiLink 3 unit, open the camera mapping dialog and request each
  quadrant. Confirm Front/Right/Rear/Left orientation and edges match baseline.
- Exercise odd-width/height and small valid mosaic JPEGs to verify crop bounds.
- Repeat rapid preview navigation and compare Java heap peaks, GC pauses and
  HTTP response latency before/after using Android profiling tools.
- Confirm malformed JPEGs return null without crashing the daemon.
- Check live recording and dashboard responsiveness while previews are requested.

No build, automated tests, benchmarks, or on-device checks were run.

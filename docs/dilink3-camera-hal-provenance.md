# DiLink 3 camera HAL provenance and conservative fallbacks

## Why this change

The shipped camera paths read from two different sources of truth. Before this
change, `GpuSurveillancePipeline` consulted `BmmCameraInfo.getCameraId()` for
the recorder while `CameraConfigResolver` reported the saved or profile-default
ID for the same pipeline. A DiLink 3 unit whose vendor HAL maps `pano_h` to
camera 0, but whose generic `ro.product.model` selects the legacy camera-1
profile, could therefore report camera 1 while recording from camera 0.

The central resolver now chooses an ID once: valid manual > valid frame-validated
probe > valid BMM panoramic tag > profile fallback. It does not open cameras or
scan ID ranges. Broken individual vendor tags no longer abort discovery of the
remaining tags. Only the legacy AVM HAL's 0..5 camera namespace is accepted;
out-of-range saved/returned IDs are not passed to a camera constructor.

`GET /api/surveillance/config` already exposes the resolved camera summary.
It now includes `panoCameraIdSource`, `panoSurfaceModeSource`, and
`panoGeometrySource`. A `profile-fallback-unverified` or `saved-unverified`
value is an assumption, **not** a measured camera capability. The legacy
strip geometry is split into four equal-width views, so an old saved width
that is not divisible by four is rejected as an invalid pair.

## What the device snapshot can and cannot prove

The supplied Dolphin snapshot reports Android 10/API 29 and a generic
`ro.product.model=BYD AUTO`. The separate `dumpsys media.camera` reports two
ordinary CameraService devices, but **does not identify BYD's private
`AVMCamera` panoramic camera ID, native sensor order, optical FOV, or supported
preview sizes**. The captured `vehicle.config.cam_sort`, `pano_cam`, and
`pano_l_cam` shell properties are empty. These values cannot safely be
invented, nor can a working camera's geometry be inferred from the SoC.

On some DiLink 3 firmwares `BmmCameraInfo` is entirely absent: in that case
the fallback stays explicitly labeled and the app preserves the proven legacy
profiles/manual mapping. It does **not** automatically reconfigure the
ImageReader or encoder using the first emitted frame. The producer can ignore
consumer geometry; automatically changing it can break recording, as documented
in `docs/DILINK3_CAMERA_GEOMETRY_HARDENING.md`.

Remaining values that are still policies or assumptions include profile strip
size (e.g., 5120x960), camera slice ordering, surface preview index, default
recording FPS, nominal vertical FOV estimates (115/95/115/95 degrees), and the
OEM dashcam ID's separate XOR fallback. BMM tags alone do not validate any of
these. This PR intentionally does not change safety-critical camera ownership,
OEM dashcam mapping, codecs, bitrate, storage cleanup, or camera startup order.

## Device acceptance checklist (not executed)

1. With cameras inactive, capture `BmmCameraInfo` tag results if available;
   record camera IDs, the exact firmware build, and profile/user overrides.
2. Check the resolved summary source labels and compare `panoCameraId` with
   the physical camera ID reported by the running daemon.
3. Verify four correct lens images in live preview, recorded mosaics, and AI
   input with and without an `AVMCamera` reopen / native 360-camera takeover.
4. On a device without BMM tags, verify explicit fallback labels, no extra
   HAL opens, and unmodified recorded geometry/FPS.
5. On an invalid saved id/geometry fixture, verify fallback and warning logs.

No Android build, JUnit suite, bench, or in-car test was run for this patch.

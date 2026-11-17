# BYD Dolphin camera service inventory (read-only)

The supplied 2026-11-16 ADB snapshot reports Android 10 (API 29), Qualcomm
`trinket` hardware, generic `ro.product.model=BYD AUTO`, and registered
`bmmcameraserver`, `bydcameramanager`, `media.camera`, and
`media.camera.proxy` services. These observations **do not** identify camera
IDs, physical roles, supported dimensions, or a Dolphin-specific camera layout.

The camera configuration diagnostics now include `cameraHardwareInventory`.
It reads Android runtime build fields and passively checks registration of
these four service names using `ServiceManager.checkService`. It does not
open cameras, infer a Dolphin camera ID, or change the recording backend.

Values `registered` and `not_registered` describe the service registry at
query time only. `unknown` means the hidden service API was unavailable or
blocked; it must not be treated as absence. A registered service does not
prove the camera can be opened or that its frames are valid.

The uploaded diagnostics are a snapshot of one device, not universal Dolphin
firmware specifications. Runtime values are intentionally queried again on
each diagnostics request. No device serial numbers or identifiers are exposed.

## Passive Android Camera2 enumeration

The runtime inventory now also reports `androidCamera2` with `status`,
`ids`, `reportedCount`, and `truncated` (maximum 32 displayed IDs).
`available` with an empty list means Camera2 returned no visible IDs;
`context_unavailable`, `service_unavailable`, or `unknown` mean enumeration
was not reliable. Camera2 IDs are **not** BYD AVM/APA HAL camera IDs and
are never used to select a panoramic source. This diagnostic does not open
cameras or infer their physical roles.

## Camera2 capability metadata (passive)

For each of at most 32 enumerated Android Camera2 IDs, the inventory now
requests `CameraCharacteristics` without opening a device. When reported,
`lensFacing`, `sensorOrientationDegrees`, the numeric Android `hardwareLevel`,
and supported **JPEG output** sizes are included. Each size is a width/height
pair; at most 32 are displayed, with total count and truncation flag.
Missing metadata is omitted rather than replaced with guessed values.
A per-ID `unknown` status means characteristics could not be read.

These are Android Camera2 JPEG output capabilities, **not** observed frame
sizes, FPS, AVM panorama geometry, or proof of recording support. They must
never be used as BYD proprietary camera IDs or physical AVM role mappings.
No camera is opened and no capture configuration is changed.

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

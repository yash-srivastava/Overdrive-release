# DiLink 3 manual camera selection and probe provenance

The camera settings HTTP endpoint previously wrote the currently resolved
profile width, height, and surface mode into `probed*` fields whenever a user
selected or cleared a manual camera ID. It also marked a manually selected
camera `probedAndValidated=true` without observing any camera frames.

A manual camera ID is **not** a hardware measurement. This change preserves the
manual ID but invalidates any prior camera's cached surface and dimensions by
writing `-1` sentinels. The config writer merges sections by key, so omitting
these keys would preserve stale values. The resolver treats negative values as
absent and falls back to profile defaults. Clearing a manual override also
invalidates these cached fields. Both paths set `probedAndValidated=false`.

These changes do **not** discover dimensions or validate a selected camera.
The profile fallback is an explicit assumption until the actual producer
provides a verified observation. The camera remains subject to the existing
native-camera ownership and safety constraints. No new camera is opened by the
settings endpoint.

## Review / validation

Review both `manualCameraId` and `clearManualCameraId` handlers and ensure
that the merged `camera` section no longer contains a positive `probedWidth`,
`probedHeight`, or `probedSurfaceMode` from a different camera. Confirm that
`manualOverride` and `probedAndValidated` are independent. Device validation
is required before claiming the selected ID works on a particular vehicle.

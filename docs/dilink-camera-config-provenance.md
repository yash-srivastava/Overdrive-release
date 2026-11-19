# Panoramic camera configuration provenance

`GET /api/surveillance/config` now includes three **diagnostic-only** fields
alongside the existing resolved values:

| Field | Describes |
| --- | --- |
| `panoCameraIdSource` | How `panoCameraId` was chosen by the config resolver |
| `panoSurfaceModeSource` | How the resolved `panoSurfaceMode` was chosen |
| `panoGeometrySource` | Where the resolved `panoWidth`/`panoHeight` pair came from |

The fields describe the **resolved configuration**, not necessarily the camera
finally opened. The DiLink 3 recorder may obtain a BmmCameraInfo hint at
initialization; it also re-resolves configuration on `start()`. A BMM tag is
not proof of working frames, lens order, frame geometry, or orientation.

## Labels

- `mode-profile` — fixed-mode geometry/selection (DiLink 5, Camera2 or DiLink 100).
- `profile-fallback-unverified` — no acceptable saved value, so the profile
  supplied it. An inferred Auto profile is still an assumption.
- `saved-unverified` — loaded from configuration. Neither the ID nor the
  saved preview index is independently verified just by being stored;
  dimensions may have been seeded from a profile.
- `saved-manual` — saved camera ID accompanied by `manualOverride=true`.
- `saved-validated-flag` — saved camera ID accompanied by
  `probedAndValidated=true`; this describes a persisted flag, not a new probe.
- `hal-bmm-tag` — DiLink 4's existing BmmCameraInfo tag selected the camera ID.
- `hal-tag-preview-index` — DiLink 4's existing tag determined preview index
  (pano_h → preview index 0, pano_l → preview index 0;
  `apa`/`byd_apa` → preview index 1).
- `unspecified` — a legacy caller used the older `ResolvedCameraConfig`
  constructor without supplying provenance.

## Compatibility limits

No selection rule or saved-data acceptance check changes:

- DiLink 3 retains Auto and explicit profile choices, unvalidated saved
  `probedCameraId`/`probedSurfaceMode` values and recorder-side HAL hints.
- DiLink 4 keeps its original BMM tag priority, preview-index mapping and
  fallback when the HAL does not return a selection. Existing values above
  the expected ranges are **not** newly rejected.
- Saved geometry keeps the legacy even-width/even-height rule. Widths not
  divisible by four are not newly discarded.
- No new HAL lookups, caching, re-probing, reflection exception handling or
  frame validation are introduced. Car/firmware evidence would be required
  before changing those policies (see #765 and PR #802).

These labels can help interpret reports but do not claim verified geometry
or that a recorded stream matches the selected ID.

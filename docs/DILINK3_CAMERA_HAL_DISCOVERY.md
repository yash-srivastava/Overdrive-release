# DiLink 3 panoramic source discovery

For the legacy DiLink 3 automatic panoramic source, the resolver now consults
`android.hardware.BmmCameraInfo.getCameraId(String)` using the native `pano_h`
and `pano_l` tags before accepting a model-inferred or saved camera ID.
Discovery only reads HAL metadata; it does not open any camera. An explicit
manual override remains authoritative.

If the HAL is missing, the tags are unavailable, or reflection fails, existing
profile/probe fallback behavior is preserved. APA tags are intentionally not
used on DiLink 3 because their preview-index contract is not verified there.

When the discovered ID differs from the persisted/profile ID, dimensions and
preview index are reset to profile defaults pending a real stream probe; the
resolver clears the previously persisted validation/fallback claims in its
runtime result. These geometry defaults are **not** hardware measurements.
If the HAL returns the same ID, this change does not validate persisted
geometry or role mappings. Future work should bind measured geometry to the
actual opened stream and invalidate probe records across firmware changes.

This patch does not alter DiLink 4, DiLink 5, DiLink 100, Camera2, camera
ownership/release logic, recording frame rates, or bitrate settings. It does
not claim that the HAL tag guarantees a working stream. Verify behavior on a
real DiLink 3 unit before relying on it in production.

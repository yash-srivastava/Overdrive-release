# DiLink 3 ACC-on camera handoff

The surveillance-to-drive transition relinquishes the AVMCamera handle so the
OEM camera application can claim the primary slot before OverDrive opens a
secondary consumer. The legacy path already starts `AvcHalWarmup` concurrently
with the existing 3 s grace and up-to-15 s owner polling, then coalesces the
warmup at the cold-open boundary. **That prewarm remains unchanged for every
`legacyOpenAttempt`, including BYD100F.** HAL settle is not bypassed.

## Release-safe scope and retry

The extra typed `IBYDCameraService`/`IBYDCameraUser` owner probe proposed in
PR #865 has been removed. These interface classes are shipped inside the app
and R8 renames them in release builds; the resulting app-owned `Stub` cannot
reliably resolve a remote BYD Binder user. Debug-only results cannot justify
new Binder polling threads in a release build. Existing
`BydCameraCoordinator.checkNativeAppActive()` behaviour is unchanged.

Only positively identified DiLink 3.0 firmware using the legacy AVMCamera path
uses `LegacyAccOpenRetry`. It makes one bounded initial open and at most one
retry after an ordinary vendor exception, provided the camera is still absent
and the reopen is still active. A vendor-open timeout is terminal: the camera
release restart is requested and the restart gate remains latched, with no
concurrent second open. If the handoff grace or poll throws **before** this
controller runs, the existing catch-block fallback still makes its one attempt;
if the controller ran, that catch-block cannot add a third attempt.

DiLink 4 (including default camera mode when the firmware identity says DiLink
4), DiLink 5, BYD100F and uncertain firmware continue through their existing
open/retry paths. Selection uses build product/device and firmware fingerprint,
not just the camera-mode setting. Where firmware evidence is absent or conflicts,
this optimization is disabled rather than guessing DiLink 3.

## Verification remaining

`Di3AccHandoffPolicyTest` checks DiLink 3 selection, DiLink 4 in default mode,
unknown firmware and BYD100F exclusions. `LegacyAccOpenRetryTest` drives the
production retry controller with injected open failures, hard timeouts and
before/after-helper catch eligibility. These tests are provided but were not
run here. No Android build, release APK, or car test has been performed.

The provided device diagnostics record a DiLink 3.0 Android 10 fingerprint, but
contain no timed ACC-on handoff trace or release-build first-frame sequence.
Before claiming a latency improvement, compare release APKs on the same car:
ACC-on timestamp, warmup start/completion, owner handoff, initial/retry open,
first decoded frame, and any native owner/yield/reacquire transition. Include
BYD100F AVMCamera and AvmSurfaceTexture, DiLink 4 default/selected, and a
stalled vendor-open case to check the no-overlap restart fence.

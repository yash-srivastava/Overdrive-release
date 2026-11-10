# Onboarding encoder-status polling

The onboarding overlay checks whether the camera encoder is running to avoid
competing decorative animations. The check uses asynchronous ADB status probes.

Previously, another probe was scheduled every three seconds regardless of
whether the previous one had completed. On a slow or unavailable ADB connection,
multiple requests could overlap and consume unnecessary CPU and shell resources.

The next probe now starts three seconds after the preceding callback completes.
Late callbacks from dismissed or restarted tours are discarded. Polling stops
when the overlay is dismissed, and cached state is reset at the next start.

This reduces redundant work during onboarding only. It is not a general BYD
firmware performance tweak and does not modify camera capture or vehicle APIs.

## Manual verification

1. Open onboarding while parked and confirm encoder-state UI still updates.
2. Delay ADB responses and confirm requests do not accumulate concurrently.
3. Dismiss and restart onboarding with a pending response; confirm stale
   callbacks cannot change the new overlay's state.
4. Confirm no periodic encoder status queries after dismissing onboarding.

No build, automated tests, benchmarks or device tests have been run.

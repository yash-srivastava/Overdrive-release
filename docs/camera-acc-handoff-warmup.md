# DiLink 3 ACC-on camera handoff: bounded owner polling and overlapped AVC settle

This change addresses the follow-up to #857 (commit `22e3c808ab5ff4ff1d405021b6724b1abddc8fbf`). The original goal is to avoid a *second* AVC four-second warmup wait after an ACC-on OEM camera handoff without compromising cold-open safety. No new timing advantage is claimed without device measurements.

## Scope and invariants

- **Only classic DiLink 3 ACC-on reopening** starts a background AVC warmup while the existing OEM ownership grace/poll is running. The authoritative `AvcHalWarmup.warmupBeforeColdOpen` at the vendor open still uses the same single-flight 15-second completed-settle cache and the same full four-second settle on a cache miss. No receipt exists to restamp that cache, including on retry.
- `DiLink100Platform.isByd100f()` excludes both BYD100F camera configurations, including AvmSurfaceTexture. DiLink 4, DiLink 5, and Camera2 keep their existing behaviour.
- The typed `IBYDCameraService`/`IBYDCameraUser` owner lookup is **only** called by this ACC-on polling path. No new R8 keep rules are added: the vendor stubs are already declared `compileOnly` in the Android build, and the runtime firmware supplies those interfaces. The existing reflection-only `queryCurrentCameraUser()`, frame-stall/yield/reacquire handling, contention valve and dead-slot recovery are not modified. A typed `LinkageError` or other call failure falls back to the existing reflection mechanism.
- No speculative probe runs before the mandatory three-second grace, and no one-use settle receipt is issued or consumed. This deliberately drops the two changes with no demonstrated normal-Binder speedup and possible 2–7 s slow-probe regressions.

## Bounded ownership polling

After the minimum 3 s grace, the classic DiLink 3 path polls on a daemon worker instead of invoking a vendor Binder from the camera reopen thread. The remaining `maxWaitMs - 3000` budget is measured with `elapsedRealtime`. The reopen thread checks only a volatile result and updates the GL heartbeat while waiting; if the Binder wedges, the camera moves to the existing fail-open path when the handoff deadline expires. No new worker can overlap the outstanding Binder transaction, even during another reopen. A completed worker is replaced on the next poll.

This is **a caller-side time limit**, not cancellation of a stuck vendor Binder call. A stuck worker remains daemon-owned until the Binder returns. A reboot/restart may still be required for a wedged vendor process. The post-owner 500 ms release grace is capped by the same deadline on this Di3 path; other modes retain their original polling deadline/behaviour.

The ACC-on legacy vendor-open hard timeout and process-restart fence remain. The Di3 open/retry driver performs exactly one retry after an ordinary exception, only if no partial camera handle remains and the reopen is still required. A timed-out vendor-open worker is never retried concurrently. BYD100F retains the existing open/retry call path.

## Verification and limits

The Android-free `BoundedAccOwnerProbeTest` executes worker completion, stale in-flight rejection, a blocked probe, and `LinkageError` recovery. `LegacyAccOpenRetryTest` executes the real production open/retry controller using injected vendor-open behaviours, including success, one retry, timeout fencing and a rejected retry. Source-level contracts continue to guard the cold-open `beforeLaunch.state != AvcProbeState.RUNNING` expression and the existing lifecycle fences.

**Not yet established:** a cold open acquiring frames in ≈4 s on actual DiLink 3 hardware, or stall/yield → owner clear → reacquire on DiLink 3 / BYD100F. The attached device diagnostic identifies a DiLink 3.0 Android 10 build but does not contain the required ACC-on transition timing and continuous-frame logs. Do not report these as tested.

For field validation, capture timestamps for the ACC-on transition, `Started AVC HAL warmup alongside ACC camera handoff`, `HAL warmup complete`, `Native app claimed camera (ACC-only bounded poll)`, `Camera reopened successfully`, first decoded frame, and each native app release/reacquire edge. Compare with a baseline at ordinary and artificially delayed owner Binder latency. Repeat on BYD100F with AVMCamera and AvmSurfaceTexture, and on DiLink 4 in default mode. Confirm the yield/reacquire and contention/dead-slot recovery are unchanged and that no duplicate vendor open occurs on a blocked Binder call.

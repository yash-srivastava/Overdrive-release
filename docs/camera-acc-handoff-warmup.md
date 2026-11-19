# Legacy AVC warmup during ACC-on camera handoff

The legacy DiLink 3 path prewarms `com.byd.avc` while the OEM camera ownership
handoff runs. This does **not** replace the four-second HAL settle on a first
cold open, an AVC restart, or an unproven handoff. DiLink 4/5 bypass the legacy
warmup, and BYD100F/DiLink 100 continues using its original cold-open rules.

## Where time can actually be saved

ACC ON → OEM ownership handoff — four-second AVC settle runs in parallel.

The common case takes at most 15 seconds to await native camera ownership;
when the overlapping settle finishes after about four seconds, the ordinary
15-second cache already covers the subsequent open. **A receipt does not
improve that case.** The only duplicate settle the receipt can avoid is a
handoff delayed more than 15 seconds *after* the prewarm completes (for example
when the OEM ownership Binder query stalls). That is a narrow recovery of a
rare additional four-second wait, not a normal 4-second ACC-on speedup.

For ordinary registered-service handoffs, the first native-owner query now
runs on a separate daemon thread **during**, rather than after, the mandatory
three-second ownership grace. The regular polling loop never starts a second
Binder query before that probe finishes. A stalled query is bounded by the
original overall handoff deadline.
An owner observed early still waits out that three-second minimum **and** the
existing 500 ms release grace, but the first Binder query's latency need not
be added *after* the minimum. The overall 15-second polling deadline still
runs from the start of the wait. No-service handoffs retain their full 15-second
budget. DiLink 4/5 retain their original ordering, and no AVC launch path is
added by this adjustment.

## Single-use completed-settle proof

Only the dedicated ACC handoff **background prewarm** may issue a receipt, after
finishing the full settle and confirming a `RUNNING` PID on its normal post-
settle probe. A full settle by the subsequent camera open cannot issue a receipt.
The receipt is consumed or discarded on the *first* open boundary; it cannot
be used on retry or on a later restart. The ordinary 15-second cache is checked
first at no additional cost.

After the cache expires, the open uses its **existing pre-launch `pidof` probe**
to check the prewarm's same PID. It never adds a separate receipt probe. The
receipt can skip the duplicate settle only when the PID is still running, it
matches the post-settle PID, the completion timestamp is unchanged, no parked
recovery request intervened, and the settle finished no more than 30 seconds
ago (monotonic clock). Failure of *any* condition takes the original cold-open
branch. In particular an `UNKNOWN` pidof retains its intentional cold-open
`am start` decision and full settle. Parked recovery relaunches and escalation
retain their existing behavior.

A vendor-open failure retires the handoff receipt but preserves the ordinary
15-second completed-settle cache: the vendor open failure alone does not
restart AVC. A retry reuses that valid cache when possible; after expiry or
an AVC recovery it takes the original cold-open probe and full settle. The existing
single-flight reopen gate stays latched if the vendor Binder open times out;
no second open is permitted to overlap the wedged worker.

## Verification and measurement

Compare timestamps for `Started AVC HAL warmup alongside camera handoff`,
`HAL warmup complete`, `Native app claimed camera during minimum wait`,
`Reusing completed 4 s AVC settle for the same delayed ACC camera handoff`,
and `Camera reopened successfully` across ACC ON transitions. Record both the
native-owner check duration and the time from prewarm completion to open; only
report savings attributable to the early probe or to a genuine expired-cache
handoff. Never claim the receipt saves time during an ordinary handoff.

Exercise ACC ON with registered and missing coordinators, a stalled OEM owner
query, cold boot/restart, deliberately changed AVC PID, an inconclusive/timeout
`pidof`, a parked frame-dead recovery, an AVMCamera open failure then retry,
and a vendor-open hard timeout. Verify that no unintended OEM 360 UI appears
and that no retry spawns an overlapping reopen. Check BYD100F where available.

The Android-free `AvcHandoffSettlePolicyTest` tests actual receipt transitions
and cold-open fallback decisions. `AvcColdOpenWarmupContractTest` and
`Di3BootAnimationSafetyContractTest` retain source-layout regression guards;
these source checks are not substitutes for JVM or device execution.

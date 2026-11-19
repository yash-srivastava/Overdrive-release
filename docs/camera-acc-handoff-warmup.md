# Legacy AVC warmup during ACC-on camera handoff

The legacy DiLink 3 path starts its AVC warmup alongside the OEM camera
ownership handoff. **The initial cold-open still waits four seconds** after
its AVC launch decision before opening `AVMCamera`. An AVC PID, even if
observed twice, is not proof that its camera session has settled.

## Same-handoff receipt

A handoff-specific receipt is issued **only after** the normal four-second
settle and a post-settle `RUNNING` probe. The later open boundary may consume
that receipt once, for at most 30 seconds after completion, if AVC still has
the same PID and no intervening warmup or recovery request is known. This
prevents a second four-second wait when other handoff cleanup takes longer
than the normal 15-second warmup reuse cache. A matching PID never creates
a receipt or bypasses an initial settle.

If the receipt is expired, AVC has changed, or the follow-up probe is
inconclusive, the ordinary four-second warmup is retained. An inconclusive
receipt check cannot itself trigger a new foreground AVC launch. The
existing explicitly requested parked recovery, fail-open handling and
launch-failure escalation remain in place.

This applies only to the ACC reopen that created the receipt. Ordinary
cold starts and subsequent restarts still use their original settle
boundary; BYD100F/DiLink 100 ignores receipts entirely, and DiLink 4/5
continue to bypass this legacy code.

## Verification guidance

On a DiLink 3 device, compare timestamps for `Started AVC HAL warmup alongside
camera handoff`, `HAL warmup complete`, `Reusing completed 4 s AVC settle for
the same ACC camera handoff`, and `Camera reopened successfully`. Confirm
that the receipt is used only if the handoff extends beyond the normal
15-second reuse window and that cold starts still wait four seconds.

Check ACC-on from surveillance, cold boot/reboot, deliberate AVC restart,
unknown/inconclusive `pidof`, a parked frame-dead recovery request, and
BYD100F if available. Observe the OEM 360 UI; the receipt path must not
bring it to the foreground. The existing
`AvcColdOpenWarmupContractTest` asserts the key source-level invariants.

No reduction is claimed for the usual parked surveillance -> ACC-on path,
where the initial four-second wait already overlaps the 15-second
camera-ownership handoff. This is a narrow duplicate-wait avoidance for
delayed handoffs, not a general bypass of HAL settling.

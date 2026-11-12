# Nominatim request gating with a negative nanoTime origin

## Problem

OverDrive's reverse geocoding routes multiple callers through a daemon-wide
Nominatim limiter, with a one-request-per-second process-local gate and a
separate persisted cooldown after network errors or server throttling.

The token gate used `nextTokenAtNs = 0` as the initial state, tested
`System.nanoTime() < nextTokenAtNs` to decide whether to throttle, and set the
next deadline by adding one second to the current value.

Java specifies that `System.nanoTime()` uses an **arbitrary origin**. Although
elapsed differences are meaningful, the absolute value can be negative. If
that happens, the initial `now < 0` comparison rejects even the first request.
The non-consuming `isThrottled()` probe makes the same mistake, so queued
geocoding/backfill work can incorrectly classify a miss as throttled. A
long-lived clock origin below zero could delay online geocoding until the
absolute reading eventually crosses zero.

An absolute `now + 1 second` deadline also fails when the signed `long`
reading crosses `Long.MAX_VALUE` and wraps to negative. Short elapsed-time
subtraction is the appropriate comparison for a monotonic clock.

## Changes

- Keep a package-private, pure-Java `NominatimTokenGate` with a boolean
  `hasGranted` state, a last-granted `nanoTime` reading, and the existing
  one-second interval.
- Grant the first token immediately regardless of the sign of the clock.
- Enforce subsequent grants with the signed elapsed difference
  `nowNs - lastGrantNs`. For a one-second interval, this is safe across the
  `nanoTime()` signed wraparound, provided elapsed time remains well below the
  292-year signed difference limit.
- Make non-consuming probes use the same gate as actual grants, so a read
  does not move the deadline. The class-level synchronization in
  `NominatimRateLimiter` continues to serialize grants and probes.
- Leave the persisted Nominatim error cooldown, backoff schedule, success
  reset, network requests, and geocoding privacy/online opt-in unchanged.

## Compatibility

This uses Java primitives and `System.nanoTime()` already present in the
application. No new Android APIs, permissions, native libraries, data files,
configuration fields or background threads are introduced. It is compatible
with the existing Android 10 (API 29) DiLink 3.0 code paths.

## Proposed verification (not executed while preparing the patch)

1. Run `NominatimTokenGateTest` and confirm the negative, zero and positive
   origin cases, 999,999,999 ns / 1,000,000,000 ns boundary, non-consuming
   probes, signed wraparound, and unexpected negative elapsed age.
2. In an isolated development environment, enable online geocoding with a
   permitted, rate-limited endpoint. On a cold daemon start without a
   persisted cooldown, confirm the first valid lookup can proceed promptly.
3. Request multiple distinct uncached coordinates inside one second. Confirm
   that only the first lookup is granted, and a later one can proceed once
   one second elapses. Verify the backfill throttle probe agrees.
4. Trigger a simulated HTTP failure and confirm the existing persisted
   cooldown still blocks requests and survives daemon restart.
5. Perform any head-unit validation while parked; avoid repeated external
   Nominatim requests beyond its usage policy.

No compilation, unit-test execution, benchmarks or vehicle testing was
performed for this change.

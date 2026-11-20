# DiLink 3 ACC-on camera reopen: retry safety

When surveillance hands back the camera at ACC ON, the legacy AVMCamera path
releases the primary slot, starts the existing `AvcHalWarmup` concurrently with
the OEM handoff, waits the original 3-second grace and up-to-15-second owner
poll, and opens as a secondary consumer. The warmup and HAL settle are unchanged,
including for BYD100F. No new Binder owner probe is introduced.

The existing `reopenCamera` already attempts one open, retries once after an
exception ("Retry camera open..."), and fences a timed-out worker until the
requested process restart. **This change does not claim a faster handoff** or a
reduced open count for ordinary DiLink 3 failures. It makes the retry budget
explicit for positively identified DiLink 3 using the **existing**
`RecordingEngine.isDiLink3HeadUnit` rule, with no new firmware parser. A failure
in the grace/poll phase retains the existing outer catch-block fallback; once
the controller starts, the outer catch cannot add a later reopen after callback
setup. A timeout stays terminal. Synthetic `InterruptedException` from a
cancelled warmup or worker epoch check exits the controller without marking the
ACC-on thread interrupted. A genuine interrupt while an open is in flight is
already handled as a terminal restart inside `openLegacyCameraWithHardTimeout`.

DiLink 4 (including Default camera mode), DiLink 5, BYD100F, and unrecognised
head units retain the unmodified open/retry path. The original non-DiLink 3
process-death cancellation comment remains in place.

## Evidence and remaining validation

The supplied November 16, 2026 device diagnostics identify a BYD DiLink 3.0
Android 10 build, but they do not include a timed ACC-on camera handoff log.
The maintainer's PR #878 comparison reported the same opens and timings on the
then-current upstream and v5 (DiLink 3 failure 4372 vs 4359 ms). This patch
therefore does not establish a measurable improvement in recording latency.

Pure-unit cases cover scope, exception and timeout budgets, cancellation without
spurious interrupt, and catch eligibility; they have **not been executed**.
Required device validation: capture release-build ACC-on traces for each path,
including warmup cancellation, callback-setup errors, normal vendor failure,
terminal blocked open, DiLink 4 Default mode, and BYD100F prewarm. Compare
open counts, gate/restart behavior, first decoded frame and time-to-recording
against the same upstream revision. No build or car test was run here.

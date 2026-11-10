# Performance sampler pacing on resource-constrained BYD head units

The performance dashboard is sampled only while clients are connected. Each
sample reads multiple `/proc` and `/sys` files, including thermal and GPU data.
On slower head units, a sample can take longer than the configured two-second
interval. A fixed-rate scheduler then starts the next sample immediately to
catch up, causing continuous sampling I/O while the performance page is open.

Use `scheduleWithFixedDelay` instead of `scheduleAtFixedRate`. This preserves
an initial immediate sample and a two-second *minimum idle gap* between the
end of one sweep and the start of the next. It does not change metric
collection, daemon state, history size, or camera behavior. Dashboard refresh
cadence can be slower if sampling itself takes longer than two seconds; this
is an intentional tradeoff to reduce competing background work.

This is platform-neutral Java scheduling behavior and does not depend on a
DiLink 5-only API. It is intended to help constrained DiLink 3 / Android 10
head units, but no measured frame-time improvement is claimed.

## Suggested parked-vehicle verification

1. Open the performance dashboard and confirm metrics update normally.
2. Under heavy system load, compare sampler CPU time, sample spacing and
   foreground UI frame times before and after this change.
3. Confirm the sampler stops when its final client disconnects and that
   the history still retains the latest 60 samples.
4. Verify on the target DiLink 3 firmware 2606 device, not only an emulator.

No builds, tests, benchmarks, or on-device verification were run.

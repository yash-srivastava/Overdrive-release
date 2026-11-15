# Avoid splitting the process stat line during CPU sampling

`PerformanceMonitor.collectCpuMetrics()` reads `/proc/<pid>/stat` on every
performance-dashboard sample. The old implementation split the entire line on
whitespace, allocating an array and token strings even though it only uses the
`utime` (field 14) and `stime` (field 15) counters.

The process name (`comm`, field 2) is parenthesized but may contain whitespace
and closing parentheses. Splitting the full line can therefore also shift the
field indexes and report incorrect app CPU usage for such process names.

The sampler now locates the final `)` and scans only far enough to parse fields
14 and 15. It avoids the full-line regex split and correctly anchors Linux field
numbers after `comm`. Missing or malformed counters continue to skip the app
CPU update for that sample rather than publishing a fabricated value. System
CPU sampling, counter deltas, normalization, and dashboard cadence are unchanged.

## Static validation cases

The field reader should return the same values for ordinary process names and
for names containing spaces or `)`; it should reject truncated lines and
non-numeric target fields. This change has not been benchmarked on a vehicle.
No build or project tests were run while preparing this patch.

## Suggested device verification

1. Open the performance dashboard and confirm app CPU usage continues updating.
2. Compare allocation traces for the `PerfMonitor` thread before and after the
   change while sampling at the same interval.
3. Confirm a malformed or unavailable `/proc/<pid>/stat` line does not stop
   system CPU, memory, GPU, or temperature collection.

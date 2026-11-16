# Reduce allocations while collecting memory metrics

`PerformanceMonitor` samples `/proc/meminfo` every two seconds while the
performance dashboard is active. The dashboard calculations use only
`MemTotal` and `MemAvailable`, but the previous implementation applied a
regular-expression split and parsed the numeric field on every line, including
fields that were discarded.

The sampler now checks only the two required labels and parses their numeric
kB values directly. This avoids per-line token arrays and unnecessary numeric
parsing while preserving the existing total/used/percentage formulas and the
live `/proc/meminfo` read on each sample. The reader is also closed with
try-with-resources.

This is a targeted allocation reduction; no measured latency or battery-life
gain is claimed. No build, project tests, benchmark, or on-device verification
was run.

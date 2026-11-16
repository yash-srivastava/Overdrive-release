# Share thermal-zone discovery between CPU and GPU metrics

The performance sampler resolves CPU and GPU temperature sources independently.
Both lookups previously opened and parsed the same numbered
`/sys/class/thermal/thermal_zoneN/type` files on their first sample, even
though the thermal-zone topology is stable for the daemon lifetime.

The sampler now builds one small, fixed-size index of zone type strings and
reuses it for both lookups. Missing or unreadable zone entries are cached too,
so a sparse sysfs layout does not cause repeated failed opens during discovery.
CPU/GPU matching order, temperature-file validation, direct-path fallbacks,
and the existing per-sensor resolved-path caches are unchanged.

This removes the second pass of up to 30 type-file reads during initial metric
discovery. It does not change the two-second sampling cadence or claim a
measured steady-state frame-time improvement.

## Static review

- Both temperature resolvers consume the same cached type index.
- The index is initialized lazily and then reused by the single-threaded
  performance sampler.
- A missing type file remains a null entry and is not retried.
- Runtime temperature values continue to be read live from the selected
  `/temp` file.

No build, project tests, benchmark, or on-device verification was run.

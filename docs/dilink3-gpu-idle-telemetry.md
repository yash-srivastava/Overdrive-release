# Preserve real GPU idle readings in the performance sampler

The on-demand performance monitor samples kernel GPU telemetry while clients
view the performance dashboard. On BYD DiLink 3 / Android 10 head units, a
valid `0%` reading is possible even when the GPU remains at a nonzero idle
clock. Frequency is **not** equivalent to GPU utilization.

## Changes

- Treat `devfreq` load and KGSL busy-counter readings as valid when they are
  exactly `0%`. A valid reading keeps its `devfreq_load` or `busy_counter`
  provenance instead of being overwritten by a frequency-ratio estimate.
- Only read a later GPU usage source when no earlier source produced a valid
  reading. This avoids unnecessary sysfs reads when the GPU is idle.
- Cache the absence of the canonical KGSL `gpu_busy` path for this daemon
  lifetime. On kernels without that path, the sampler no longer performs the
  same file-existence check every sampling interval. An existing but currently
  unreadable or malformed file remains eligible for a later retry.
- Parse the leading integer of a sysfs value without a regular-expression
  replacement on every read. For example, `45@1000` yields `45`, rather than
  the incorrect `451000`. A leading negative sign is treated as invalid.

The sampler's on-demand lifecycle, two-second fixed-delay schedule, recorded
history, and downstream JSON keys remain unchanged. The frequency-ratio
estimate is still used when no real load/busy-counter reading is available.

## Suggested manual verification (parked vehicle)

1. Open the performance dashboard and confirm its GPU reading updates and
   that the reported usage source is `devfreq_load`, `busy_counter`, or the
   existing frequency-ratio fallback as appropriate for that head unit.
2. At GPU idle, check that a measured `0%` remains `0%` with its measured
   source even if the GPU's clock is nonzero. Confirm that valid nonzero
   samples continue to display normally.
3. On a device lacking `/sys/class/kgsl/kgsl-3d0/gpu_busy`, observe the
   sampler during repeated intervals and confirm it continues to use a
   fallback source without re-probing that absent path.
4. Compare sampler CPU use and sysfs reads while the performance dashboard
   is open, then disconnect the client and confirm sampling stops as before.
5. Verify on a DiLink 3 / Android 10 head unit; emulator behavior alone does
   not establish hardware support or a real-world performance gain.

No builds, automated tests, benchmarks, or on-device checks were run for this
change. Performance benefits and specific kernel behavior require measurement
on the target hardware.

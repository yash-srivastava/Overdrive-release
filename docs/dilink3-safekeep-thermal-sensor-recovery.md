# SafeKeep thermal sensor discovery recovery (DiLink 3.0)

## Problem

`Thermal.readCelsius()` originally scanned `/sys/class/thermal` once per daemon lifetime.
A transient unreadable/missing directory or temporarily invalid `temp` reading during
startup committed an empty result permanently. If the chosen zone later disappeared or
became unreadable, the caller kept receiving `Float.NaN` without looking for a working
alternative. SafeKeep treats an unavailable reading as unknown, so upload throttling at
70 °C and the 80 °C high-temperature guard cannot react to that reading.

## Change

- Preserve a cached single-zone temperature file on the normal path (one file read per
  sampling call; no periodic full directory scan when it is healthy).
- On startup with no usable sensor, return `NaN` until a bounded rediscovery window
  elapses. Try scanning again after 60 seconds, *when next sampled*, rather than
  permanently caching the unavailable result.
- If the selected temperature file later reads as invalid, retry discovery at most
  once per 60-second interval. This allows a different usable CPU/SoC zone to be selected.
- Preserve existing preferred-zone matching, fallback-zone selection, millidegree
  parsing, 0–150 °C plausibility limits and the public `readCelsius()` API.
- Use Android's monotonic elapsed clock for recovery intervals. No background thread,
  filesystem watcher, new permission, persistence change or JSON schema change.

This is **recovery from missing telemetry**, not a new hardware-temperature safety
policy. When there truly is no readable sensor, the result remains `NaN` and existing
guard behavior is unchanged. On Android 10 / BYD DiLink 3.0, the effective time before a
retry can be longer than 60 seconds because SafeKeep samples periodically.

## Suggested verification (not performed)

1. Review and run `ThermalTest` on a development JVM. It covers startup with no
   thermal zone, recovery when one is later present, failure of a selected zone with
   another still available, no rescan when the chosen zone is healthy, and injected
   monotonic-clock reset handling.
2. On a parked development head unit, confirm a stable `/sys/class/thermal/thermal_zone*/temp`
   file continues to provide Celsius readings at the existing SafeKeep sampling cadence.
3. In an isolated test harness, simulate startup with no zones and then make a usable
   zone available; the next read at/after the retry interval should recover.
4. Simulate an unreadable cached zone alongside a healthy alternative; verify the
   reader returns `NaN` until the bounded retry, then uses the alternative.
5. Confirm the existing upload thermal threshold logic still consumes that reading
   and performs the unchanged 70 °C rate reduction / 80 °C upload stop. Do not
   intentionally heat a live vehicle to test this; use mocked signals or logs.

No build, test, performance benchmark or vehicle verification was run while preparing
this patch. The test cases are source only.

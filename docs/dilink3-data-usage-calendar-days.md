# Network data-usage local-day windows on DiLink 3

## What changed

The performance dashboard's network data-usage graph persists cumulative
interface/UID traffic as daily H2 rows with `yyyy-MM-dd` local-date keys. The
sampler writes a row every two minutes while the feature is enabled; the HTTP
endpoint reads a configurable number of recent days.

Two calendar-boundary problems existed:

1. `getUsage(days)` selected its oldest day with `now - (days-1)*86400000`.
   During daylight-saving changes, a "day" may last 23 or 25 hours. For example,
   on **2026-03-30 00:30** in `Europe/Zurich`, subtracting 24 hours lands on
   March 28 at 23:30, even though the previous *calendar* day is March 29.
   Around the autumn change, subtracting 24 hours late on October 25 can still
   land on October 25, excluding October 24 from a two-day query.
2. A `ThreadLocal<SimpleDateFormat>` captured the default timezone when each
   sampler or HTTP thread first used it. After a timezone correction, an old
   thread could keep assigning or querying records under the previous timezone
   until the daemon restarted.

## Implementation

- `DataUsageDayKeys` keeps the existing thread-local date formatter but applies
  the **current** timezone on every formatting call. The sampler still writes
  exactly the same ISO-style `yyyy-MM-dd` strings to the existing H2 schema.
- For the inclusive lookback start date, a `Calendar` in the same timezone now
  subtracts **calendar days**, avoiding assumptions about day length.
- The source is pure Java using `java.util`, available on Android 10 / API 29.
  There are no new dependencies, threads, timers, permissions, queries or data
  migrations. Counter sampling, byte totals, SQL ordering and the JSON response
  shape are unchanged.
- Existing rows are not re-bucketed: if the system timezone was wrong when a
  row was recorded, historical keys still reflect that earlier configuration.
  This fix affects subsequent writes and the correct range boundary for reads.

## Regression cases added (not executed here)

`DataUsageDayKeysTest` covers the two-day window after the Zurich spring change,
late on the 25-hour Zurich autumn day, the one-day boundary, and repeated
formatting across multiple timezones on the same thread.

## Parked-head-unit verification (not performed)

1. With the vehicle parked, enable the network usage graph and confirm that
   traffic counters continue to populate the same UI fields and daily totals.
2. On a development image or isolated test environment, inspect a two-day query
   around the March and October clock changes. Confirm that exactly the
   expected *local calendar dates* define the lookback boundary.
3. In a controlled environment, change the device timezone without restarting
   the daemon. Confirm that future samples use the updated local date, while
   the graph endpoint queries using that same timezone.
4. Confirm data persisted before the change remains accessible and that no DB
   schema migration is attempted. Cross-timezone history can retain older
   local-date keys by design.
5. Run the new Java regression cases separately during normal CI verification.

No build, unit tests, benchmarks, or head-unit/device checks were run while
preparing this source patch.

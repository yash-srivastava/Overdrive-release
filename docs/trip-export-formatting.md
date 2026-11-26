# GPX and KML export allocation reduction

SafeKeep staging and the trip export API use `TripExporter` to turn compressed
telemetry journals into GPX and KML documents. These are on-demand operations;
there is no change to the telemetry collection cadence or background scheduling.

GPX now creates one UTC `SimpleDateFormat`, one reusable `Date`, and one
`Formatter` targeting its output builder per export. The prior code created a
UTC formatter for metadata and every accepted point and a separate numeric
formatter and output string for every coordinate, altitude and speed. Decimal
precision, US locale, UTC timestamps and optional fields remain the same.

KML writes coordinates directly into its document builder and removes the final
separator by shortening that builder by one character. It no longer materializes
and trims a separate coordinate string before concatenating the final document.
The existing 200,000-point limit, skipped invalid points, partial gzip recovery,
CSV path and removable storage routing are unchanged.

For 200,000 accepted GPX points, the number of UTC formatter constructions falls
from 200,001 to one. If every point has altitude and speed, point-level numeric
formatter constructions fall from 800,000 to one. For KML, a 28-character average
coordinate tuple including its separator yields roughly 5.6 million characters
of intermediate coordinate text that need not be materialized separately. Exact
memory savings depend on JVM string representation and builder growth. Both
formats still produce an entire output string; this is not streaming export.

Validate by comparing GPX and KML output against the previous version on the same
finished trip, including empty journals, missing altitude/speed, negative
coordinates and UTC timestamps. Profile daemon heap/GC and elapsed export time
on a parked head unit; no device-wide savings are implied by the counts above.

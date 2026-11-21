# Reject unusable GPS coordinates before place lookups

OverDrive uses GPS fixes to write recording sidecars, label clips with place names,
and look up/cache geohash-based addresses. A finite coordinate within the WGS84
latitude/longitude limits is needed before those operations. The existing
`(0, 0)` no-fix sentinel remains excluded, although it is a geographic point.

## Problem

`GeoSnapshot.hasFix()` previously rejected only `NaN`. A snapshot containing
infinity, an out-of-range coordinate, or the `(0, 0)` sentinel could therefore
be treated as a valid fix and passed into sidecar or geocoder code. The backfill
sweep rejected `NaN` and `(0, 0)` but still accepted infinity and out-of-range
values. `Geohash.encode()` does not check its input bounds, so invalid coordinates
could also alias a real cache cell instead of being rejected.

## Behavior

A shared predicate now checks both values are finite, latitude is between -90
and 90 degrees, longitude between -180 and 180 degrees, and the pair is not
`(0, 0)`. It is used by recording snapshots, backfill candidates, cache
coordinate lookups/writes/invalidation, and synchronous/asynchronous place
resolution. Invalid inputs are ignored without cache writes or online geocoding;
existing valid fixes and direct `getByHash` callers are unchanged.

## Suggested manual verification (not performed)

On a parked development device, confirm normal GPS-tagged clips still receive
place names. Inject an out-of-range or `(0, 0)` fix into a development sidecar and confirm
it is not backfilled or sent to an online geocoder. For non-finite inputs, use a
development-only caller (JSON cannot represent `NaN` or infinity). Restore a
real fix and confirm place names and cache hits resume. No build, tests or
device verification were performed for this change.

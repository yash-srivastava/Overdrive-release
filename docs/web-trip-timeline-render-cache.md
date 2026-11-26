# Trip timeline rendering during scrubbing

The trip-detail speed/pedal/SoC chart previously reset its canvas and rebuilt
all sample paths on each slider, mouse or touch update. A chart hover also
called the slider HUD update, which rendered the same chart a second time.

The chart now stores one detached Canvas2D image of the unchanged axes, labels
and sample paths. A scrub restores those pixels at native backing-store size
and draws the existing marker and tooltip. The slider still updates its text,
recorded SoC and map marker. Mouse and touch hover draw the chart only once.
The maximum speed scan no longer creates a temporary array or spreads the
entire trip into a function call.

```text
Before: each hover -> scan timestamps -> full chart + tooltip -> HUD/map
                                       -> full chart + tooltip again
After:  each hover -> binary search* -> cached chart + tooltip -> HUD/map
        first render / invalidation -> full chart -> detached image
*Unordered or nonnumeric legacy wall times retain the original linear scan.
```

For N samples with recorded SoC, one full chart render walks N samples for
maximum speed and N each for brake, acceleration, speed and SoC paths.
On a warm cache these five sample passes are omitted on each highlight;
chart hover also omits a second full render. With 10,000 samples, 60 slider
updates avoid 3,000,000 sample visits in the base-chart drawing code. This
is an operation count, not a measured CPU or GPU speedup. Cached rendering
still copies the chart-sized image and draws the marker and tooltip, and
map/HUD updates still run. Full-detail rendering and pedal summary calculations
are still linear. For ordered timestamps, hover lookup is logarithmic and
retains the earliest row for equal-distance or duplicate timestamp ties.

The single cache is invalidated by changes in the sample array, canvas,
geometry, DPR, palette or localized minute suffix. It is bypassed during font
loading and cleared when the trip detail canvas is reset or a new trip loads.
The maximum extra backing store is 1,048,576 pixels (4 MiB nominal RGBA;
actual graphics memory can be higher). Larger canvases or failed image copies
use the full redraw path. The cache relies on the existing immutable fetched
telemetry array convention; no sample downsampling is introduced. The image
copy may be more expensive than drawing short charts on some GPU drivers.

## Verification

Run `node app/src/test/js/trips_timeline_render_cache_harness.js`, the existing
`trips_soc_track_harness.js` and `trips_gps_harness.js`, then the Android build
and unit suite. On a parked head unit, compare a short and a long trip, drag
both the slider and chart, and verify the tooltip, pedal percentages, route
marker, duplicate timestamps and SoC gap handling. Change the theme, language
and screen size; close and reopen trip details. Profile CPU frame time,
allocations, GPU memory and missed frames with identical telemetry. The local
mocked Canvas2D tests count operations and check state, not raster fidelity or
vendor GPU performance.

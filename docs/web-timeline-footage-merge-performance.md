# Web timeline footage-run classification

The web recordings timeline builds a model of available footage by merging
intervals that overlap or are separated by at most 1500 ms. Previously it then
sorted the available driving clips again and scanned them from the beginning
for every merged run. Fragmented days (including days with storage gaps) can
make this second pass quadratic in the number of recordings.

Each valid available clip belongs to exactly one merged run. The timeline now
carries a driving flag (`normal` or `oemDashcam`) through the existing merge:
when a clip joins a run, its flag is ORed into that run's flag. A single sort
and linear merge produce both the same boundaries and the same `Uint8Array`
classification. The unused merged trip-interval calculation is also removed;
raw trip intervals still feed gap explanations. Unavailable clips remain
excluded from driving classification. The fallback duration for incomplete
metadata, seam handling, event markers, gap explanations and playback logic
are unchanged. The strip asset URL version is bumped for cached clients.

## Work reduction

For R isolated driving clips (one clip per run), the old classification scans
1 + 2 + ... + R drive candidates, in addition to constructing and sorting a
second interval list. The new merge visits R available clips, with no extra
classification search. At R = 2000 this is 2,001,000 old candidate visits vs
2000 merge visits. This compares algorithmic work, not wall time: the initial
sort, other model construction, rendering, network and decoding still cost
CPU. Continuous recordings that merge into a single run see smaller gains.

## Verification

Run from the repository root:

- `node --check app/src/main/assets/web/shared/timeline-strip.js`
- `node app/src/test/js/timeline_strip_run_classification_harness.js`
- `node app/src/test/js/timeline_strip_aria_range_harness.js`
- `node app/src/test/js/timeline_request_timeout_harness.js`
- `node app/src/test/js/timeline_scrub_preview_harness.js`
- `./gradlew assembleDebug` and `./gradlew test`

On a parked head unit and in an external browser, open a fragmented day with
normal, OEM dashcam, sentry and unavailable footage. Check the driving labels,
zooming, gap cards, scrubbing, live refresh and playback. Repeat with continuous
footage and incomplete recording metadata. Profile timeline model construction
on identical payloads before and after; check main-thread long tasks and memory
allocation. No device-level speedup is asserted without measurements.

# WebCodecs live-stream NAL staging

`SotaPlayer` processes the Annex-B NAL units in a WebSocket message synchronously.
The splitter now returns `Uint8Array.subarray()` views instead of copying each
NAL's encoded bytes. The WebCodecs `EncodedVideoChunk` constructor still receives
the same byte range and copies it (no transfer list is supplied; see
[WebCodecs constructors](https://www.w3.org/TR/webcodecs/#encodedvideochunk-interface)).
IDR super-frame assembly, SPS-driven decoder configuration and watchdog behavior
are unchanged. This is not end-to-end zero-copy decoding.

SPS/PPS are the only units retained across messages. They are copied when first
received or changed, so a tiny parameter set cannot keep a large keyframe message
alive. Identical parameter sets reuse the owned copy. Views must not be retained
by future asynchronous consumers; copy at an ownership boundary if that changes.
No new API is required on legacy WebViews, which still use their existing decoder.
The live-view and immersive script URLs are versioned together.

## Workload

A synthetic minute of 1,800 messages (30/s), including 60 IDRs bundled with SPS
and PPS, each video NAL containing 16 KiB of payload, produced the following
instrumented JavaScript `slice()` counts:

| | Original | Views |
|---|---:|---:|
| Slice calls | 1,920 | 2 |
| Copied bytes | 29,499,420 | 16 |
| Submitted chunks | 1,800 | 1,800 |

This measures cumulative temporary encoded-payload copies, not retained memory,
decoder memory, or wall-clock performance. At a sustained 4 Mbit/s, removing one
copy of the incoming video payload would avoid roughly 1.8 GB of JS copying per
hour, excluding parameter sets, framing and existing uncopied inputs. Actual
stream structure and bitrate determine savings; the scan, view objects, IDR
assembly and WebCodecs' copy still incur costs.

## Validation

Run `node tools/test-sota-nal-views.js`. To compare against the original parser
and chunk/configuration output, pass a path to the baseline `SotaPlayer.js` as an
optional argument. The harness uses a mocked WebCodecs constructor and decoder;
it exercises mixed start codes, offset views, owned SPS/PPS, repeated and changed
parameters, IDR gating and copy counts. The optional baseline run compares 5,000
deterministic packets, including short and malformed inputs, without changing
existing parser semantics.

Run the normal Android build and tests, including `SotaPlayerRecoveryAssetTest`.
On a parked head unit and a WebCodecs-capable remote browser, check startup,
quality switches, reconnects, frame delivery and fallback to the legacy decoder.
Profile allocation rate, CPU and frame latency during a sustained live stream;
no actual hardware decoder or head-unit benchmark was performed here.

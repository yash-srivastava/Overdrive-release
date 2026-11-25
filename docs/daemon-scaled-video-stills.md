# Smaller daemon video stills

On cache misses, the recordings thumbnail API and timeline scrub-frame service
previously extracted a full-resolution `MediaMetadataRetriever` bitmap and then
resized it. Both now request a smaller frame with `getScaledFrameAtTime` (API 27;
the app's minimum SDK is 28). If the scaled request returns null or throws a
runtime exception, extraction retries with `getFrameAtTime` at the same time
and with the same sync-frame selection. The library still retries at zero if
extraction at one second returns null.

The recordings API still produces a 320x180 JPEG at its existing quality and
applies the existing lens correction after resizing. Timeline frames request
bounds derived from video dimensions and display rotation, then retain the
existing width-based final resize, using the returned bitmap's aspect ratio.
Missing or malformed geometry, an upscale request, or an unusually tall
speculative size selects the original extraction path, as does a scaled result
more than one pixel narrower than the requested timeline width. Aspect rounding can
change the final height slightly; a non-16:9 library thumbnail may also have
less detail after the existing stretch to 320x180. Native vendor scaling and
pixel-format choices can change image quality. Existing cached stills are not
invalidated, so cached and newly generated images may differ slightly.

For a 1920x1080 source and 320x180 output, assuming ARGB_8888:

| Returned frame bitmap | Bytes | MiB |
| --- | ---: | ---: |
| Full-size | 8,294,400 | 7.91 |
| Scaled | 230,400 | 0.22 |

That is 36x fewer pixels (97.22% fewer bytes) in the returned bitmap on the
successful scaled path. The native decoder may still decode full-resolution
internally; these are not measured total heap, decoder workload or latency
savings. The final same-size bitmap can be used directly without a redundant
copy. The existing queues, cache policy, data-source handling, decoder release,
recording and playback paths are unchanged. The fallback can add latency on
vendor retrievers that reject scaled extraction.

## Validation plan

- Run `./gradlew assembleDebug` and `./gradlew test`.
- On a parked device, browse uncached recordings and scrub timeline previews
  from 1080p/4K, rotated, non-16:9, raw/dewarped and legacy clips (internal and
  removable storage). Compare still dimensions, selected frames, strip/lens
  rendering and image quality against the baseline.
- Repeat with a decoder that returns null or rejects scaled extraction, and
  verify the full-frame fallback and cleanup. Exercise corrupt clips too.
- Profile cold thumbnail generation and scrubbing: Java/native bitmap memory,
  peak process RSS, GC, latency and concurrent recording responsiveness. Repeat
  with warm caches as a control. Do not interpret the bitmap size ratio as a
  measured system-wide speedup.

Android build, unit suite and device profiling are not verified here.

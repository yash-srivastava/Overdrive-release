# Trip telemetry: bounded compressed journal reads

## Problem

`TelemetryStore.readFromFile()` formerly loaded the entire compressed journal
into a `ByteArrayOutputStream`, then copied it with `toByteArray()` before
passing it to `GzipJsonLines`. The previous incremental JSON-lines decoder
avoided staging expanded JSON, but the compressed staging still scaled with
journal size. A stable N-byte journal required two N-byte arrays during the
copy, plus the read buffer; the decoder then retained one N-byte array.

## Design

- Open through `RemovableStorageFs.openInput()` as before, and capture the
  size of the opened descriptor (the storage layer already uses this API).
- Feed the existing incremental gzip/UTF-8 line decoder through an 8 KiB
  length-limited input buffer. Report remaining bytes to the gzip reader so
  concatenated members work even if the underlying stream's `available()`
  is unhelpful. Do not read past the captured length, even when the recorder
  appends a member while the file is open.
- Closing an individual gzip reader releases its inflater, not the caller's
  file descriptor. `TelemetryStore` closes the descriptor on every exit.
- Keep the original line boundaries, malformed-JSON handling, concatenated
  member handling, final line without newline and damaged gzip tail recovery.
  A source I/O exception is rethrown (including one encountered while gzip
  probes the next member), so the caller logs the error and returns an empty
  list rather than presenting an apparently complete partial result.

```text
Before: file -> N-byte compressed buffer -> N-byte copy -> gzip -> samples
After:  file -> 8 KiB bounded buffer                   -> gzip -> samples
```

This removes two size-dependent compressed array allocations and the full
snapshot copy. For a stable 10 MiB file, the two compressed arrays alone
previously occupied about 20 MiB at the copy; the new compressed input buffer
is 8 KiB. The gzip inflater, expanded chunk buffer, longest pending line,
parsed samples and eventual API JSON output still consume memory. This is not
an estimate of total process heap or a measured device speedup.

## Semantics and limitations

The reader captures the descriptor length at open rather than reading until
whatever EOF it encounters later. A subsequent request sees appended members.
It is a length boundary, not a transactional snapshot of bytes rewritten in
place; early physical EOF still allows recovery of the inflated prefix. The
open descriptor remains held during parsing, unlike the earlier two-phase
read. It may affect SD-card migration or access latency on some hardware. The
change relies on regular, size-reporting journal descriptors; the existing
removable-storage self-test checks descriptor sizes. Verify on supported
internal storage and routed removable storage, especially during live append,
SD removal and recovery. Journal writes, format, sampling and trip scoring
are unchanged.

## Verification

Run `./gradlew :app:testDebugUnitTest --tests 'com.overdrive.app.trips.GzipJsonLinesTest'`,
`./gradlew assembleDebug` and `./gradlew test`. On a parked head
unit, compare trip detail, route, export/analytics and damaged journal recovery
against the old build, including storage routing and live journal append.
Measure daemon heap peaks, GC and wall time with the same long journals.

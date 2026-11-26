# Bounded native trip export downloads

The in-app WebView saves GPX, KML and CSV trip exports through
`AndroidBridge.downloadToFile`, rather than a browser download. Previously the
bridge accumulated the complete HTTP response in a `ByteArrayOutputStream`,
then copied it with `toByteArray()` before writing to storage.

The bridge now copies directly from the HTTP input stream to a unique temporary
file in the destination directory, using a 16 KiB buffer. It still rejects a
known Content-Length above 32 MiB before reading, and checks the actual bytes
against the same cap before every write (including unknown-length responses).
After EOF it syncs and closes the file, then renames it to the sanitized final
name in `Download/OverDrive` (atomic move, with same-directory rename when
atomic move is unsupported). A failed copy or rename does not delete an
existing completed export. Temporary-file cleanup is attempted in `finally`.

At a 32 MiB response size, the old accumulator and its final copy held at least
64 MiB of byte arrays at the point of copying. The new writer's own payload
buffer is 16 KiB, independent of response size. This is an allocation estimate,
not total UI-process memory: HTTP buffers, filesystem cache and any daemon-side
export generation are separate.

Trade-offs: oversized responses with unknown length can now write up to the cap
to a temporary file before being rejected. The bridge remains synchronous and
continues to use the original connection, timeout, redirect and URL checks.
Replacement relies on same-directory rename; if the filesystem refuses it,
the download fails instead of deleting the previous file and retrying.
Partial data can be present in the hidden temporary file during transfer.
If the UI process is killed before `finally`, a later native export attempts
to remove regular `.od-export-*.part` files in that same directory whose
last-modified time is at least 24 hours old. Completed exports, unrelated
names, directories, symbolic links and younger or future-dated parts are
left alone. Creation/registration and the sweep share a short in-process lock;
active parts are excluded even if old, and streaming does not hold the lock.

This is lazy, best-effort recovery, not a startup task or background timer.
Fresh abandoned parts remain until an eligible later export; storage errors
can leave parts behind for a subsequent attempt. The age check is a conservative
wall-clock heuristic, not a cross-process lock; it does not guarantee protection
for an independent writer using this reserved namespace with an old timestamp.
No change is made to export formats, authentication, recording or telemetry
collection.

Regression coverage: `BridgeDownloadFileTest` exercises short reads, the exact
limit, oversized streams, I/O failure, rename failure, overlapping writes and empty downloads, plus stale-part
recovery, preservation of fresh/unrelated files and symbolic links, and
protection of an artificially aged active part during an overlapping save.
Validate actual exports and repeated saves on a parked head unit; compare
bytes and profile UI-process allocations using large trips.

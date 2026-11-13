# HTTP request framing and resource limits (DiLink 3 / Android 10)

## Motivation

The camera daemon's HTTP listener binds to `0.0.0.0:8080` and serves both
local dashboards and tunnel-mediated requests. The prior code applied a 16 MiB
(default) request **body** limit, but read request lines and HTTP headers with
`BufferedReader.readLine()`, which allocates until a delimiter arrives. This
happens **before authentication**. A very long request target or header could
therefore consume substantial heap on one of the daemon's 32 HTTP workers.
A peer sending bytes intermittently could also hold a worker despite the
socket's 15-second *per-read* timeout.

The request parser also silently chose the last `Content-Length` header,
ignored `Transfer-Encoding`, and counted body bytes by re-encoding character
chunks. Chunk boundaries can split Unicode characters, making that last count
inaccurate. A premature EOF was treated as a short, dispatchable request body.

## Design

`HttpRequestHead` is a pure-Java, byte-oriented ingress guard. It reads directly
from a single `BufferedInputStream` that is then reused by the body decoder.
There is no conflicting `BufferedReader` read-ahead across the head/body boundary.

- At most **8,192 bytes per request line** (including HTTP paths with query
  tokens); up to two request lines only for the existing, *trusted* PROXY v1
  preamble followed by HTTP.
- At most **16,384 bytes per individual header line**, **100 header fields**,
  and **32,768 bytes for the entire request head** including its terminators.
  These limits apply before authentication or endpoint dispatch.
- A **15-second total elapsed-time budget** for request-head parsing, in addition
  to the existing 15-second socket read timeout. This bounds trickle uploads
  without altering the timeout for a successfully parsed request body.
- Reject malformed header field names, embedded control characters, missing
  header terminators, duplicate `Content-Length` (including identical values),
  comma-separated or invalid lengths, and all `Transfer-Encoding` values.
  This server does not implement HTTP/1 chunked request decoding; rejecting it
  is safer than silently treating a chunked body as empty.
- Decode bodies from a **length-limited byte stream** so UTF-8 characters can
  cross decoder-buffer boundaries without corrupting the declared byte count.
  Reject EOF before the declared number of bytes arrives.
- Preserve the existing per-endpoint request-body size policy: 16 MiB default,
  32 MiB backup import, 72 MiB audio library, and 64 KiB for the existing
  smaller endpoints. Zero-length bodies remain `null` for API compatibility.
- Preserve ordinary CRLF and LF-only requests, the PROXY v1 trust check,
  WebSocket upgrade parsing, and the existing `Connection: close` semantics.

Invalid heads receive 400 (malformed/ambiguous framing), 408 (header deadline),
414 (long request line), or 431 (too many/too large headers). Oversize
`Content-Length` still receives 413. Error messages contain no attacker input.

## Compatibility and trade-offs

- A browser, app, or tunnel agent sending more than 32 KiB of headers or more
  than 100 fields will now fail rather than force unbounded buffering. JWT
  cookies and signed WebSocket protocol headers should be checked on device.
- `Transfer-Encoding: chunked` requests were **not implemented previously**;
  they now fail explicitly instead of being interpreted as absent bodies.
  An upstream proxy should forward a known `Content-Length` for JSON uploads.
- The body remains a Java `String` for compatibility with the existing API
  handlers, so large audio/backup payloads still require substantial transient
  heap. Streaming large uploads directly to a temporary file would be a
  separate, broader performance change.
- A 15-second parsing deadline is enforced on reads; the socket's blocking read
  timeout remains the outer bound of an individual blocked read. This is not
  a substitute for server-wide rate limiting, a TLS terminator, or per-client
  resource budgets.
- Android 10 (API 29) supports the standard Java I/O used here. No new Gradle
  dependencies, Android permissions, DB migrations, or configuration keys.

## Suggested verification (not executed)

1. Run `HttpRequestHeadTest` and the existing HTTP/proxy/header/body tests
   in an Android-capable development environment; check ordinary CRLF and LF
   requests, PROXY-prefixed traffic, origin/tunnel auth, and WebSocket upgrades.
2. With the vehicle parked, load the local dashboard and authenticated tunnel
   dashboard, browse recordings, and open a live WebSocket video session.
3. Submit test JSON containing multi-byte Unicode at decoder boundaries;
   confirm the request is parsed exactly once and does not hang or truncate.
4. Use a test client to submit a 9 KiB target, a 17 KiB header line, more than
   100 headers, duplicate `Content-Length`, and `Transfer-Encoding: chunked`.
   Confirm a prompt 4xx response and no daemon crash.
5. Submit an incomplete body, a slow-trickled request head, and a valid small
   body afterward. Confirm the server rejects incomplete/malicious requests and
   remains responsive to the next client.
6. Verify backup import and audio-library uploads at their documented existing
   size limits, observing memory and CPU on the target BYD DiLink 3 head unit.

**Status:** static patch preparation only. Neither build nor automated tests,
network probes, benchmarks, or on-device validation were performed.

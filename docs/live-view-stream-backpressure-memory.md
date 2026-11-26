# Legacy live-view WebSocket: bounded backlog and keyframe recovery

Only the legacy port-8887 application send queue is changed. The primary HTTP
`/ws` path retains its existing count, one-second age limit and GOP-skip policy;
no additional byte accounting or single-packet limit is introduced there.

The 8887 sender checks `WebSocketImpl.outQueue` before each application send
(including parameter-set replays). The original proposed high-water thresholds
are 16 pending buffers and 4 MiB plus a 10-byte WebSocket header allowance.
On overflow, only that connection stops receiving new packets. Its existing
queue is left intact so the library can finish writing any partial WebSocket
frame. While waiting for an IDR, it receives neither dependent pictures nor
standalone SPS/PPS replays. When a packet marked `BUFFER_FLAG_KEY_FRAME` arrives
and there is room for both cached SPS/PPS and the keyframe, the server enqueues
them in that order under the application send lock, then resumes normal sends.
There is no congestion-triggered disconnect or additional forced encoder IDR.

The two recovery messages must fit together (including both header allowances).
An oversized recovery pair, missing parameter sets, or insufficient queue space
leaves the viewer waiting for a subsequent keyframe. The original encoder packet
size ceiling is unchanged. Control traffic and the independent library writer
are outside the application lock, so this is a best-effort high-water admission
check, not a hard bound on process memory or end-to-end latency. Old packets
already queued or in TCP buffers still have to drain. A continuously slow link
may skip multiple GOPs; the chosen thresholds are not device-tuned.

## Verification plan (not yet performed)

- Run `./gradlew assembleDebug` and `./gradlew test`, including
  `DirectStreamBacklogPolicyTest`.
- On a parked head unit with an authenticated legacy 8887 viewer, select MAX
  (1280x960, 30 fps, 5 Mbps nominal). Keep another unthrottled viewer connected.
- Capture timestamped `WSStreamServer` logs, then throttle only the legacy
  viewer below the stream bitrate; include a short stall, a sustained stall,
  and subsequent removal of throttling. Record the actual rate and timestamps.
  If console logging is enabled, `adb logcat -v threadtime -s WSStreamServer:V`
  can capture the tag; the daemon's `wsstreamserver.log` is another source.
- Look for `WS: legacy viewer waiting for IDR` and `WS: legacy viewer resumed at IDR`
  on the same connection. Confirm no congestion-triggered reconnect, no dependent
  pictures are admitted during the gap, decoding recovers at a keyframe, and the
  healthy viewer continues normally. Capture the client-side connection/decoder
  events as well: server enqueue logs alone do not establish successful decode.
- Repeat with a fast viewer only and with the primary `/ws` path. Record device
  model, build revision, preset, throttling configuration, recovery duration and
  any observed disconnects; redact tokens and private addresses before sharing.

No device logs, build/test results, memory savings or recovery-time measurements
are available yet. Device evidence is still needed before claiming success on a
throttled link.

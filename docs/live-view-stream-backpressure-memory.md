# Live-view streaming: bound video backlogs by memory

The primary HTTP `/ws` path previously limited each viewer to 60 messages and
a one-second oldest-message age. A burst of large encoded pictures could still
retain many MiB of heap before either condition triggered. The legacy direct
WebSocket path (8887) also handed packets to an unbounded library send queue.

## Admission and recovery

- `/ws`: a per-viewer 4 MiB budget counts video backing array capacity,
  including bucket rounding, before copying. The existing 60-message and
  one-second limits remain. A budget breach clears the backlog and waits for
  a fitting IDR, retaining/resending codec config as before. Individual video
  packets exceeding the budget are rejected without allocating a payload.
- Port 8887: check the library's pending buffer capacities and count before
  application sends, including parameter-set replays. The admission thresholds
  are 4 MiB plus a maximum WebSocket header, and 16 buffers. If admitting
  another message would exceed either threshold, stop sending to that viewer
  and asynchronously close its connection; reconnect is required for a clean
  reference chain. Other viewers are not disconnected.

These limits cover application video backlog, not the whole process or
end-to-end transport buffers. The HTTP sender may hold one additional in-flight
video packet; its idle pool retains at most 1016 KiB, and codec configuration
and socket buffers are additional. The legacy library's independent writer
and control frames make its high-water check best-effort.

The HTTP queue reserves capacity before publication and subtracts it when a
frame is claimed by the sender or discarded. It never zeroes the accounting
counter during a reset; a concurrent consumer may still be accounting for
a frame it already owns. This can conservatively reject a packet but cannot
undercount queued video capacity.

## Verification plan

- Run the Gradle unit suite and debug build.
- Compare a fast LAN viewer, a bandwidth-throttled remote viewer, and multiple
  concurrent viewers at the same quality. Observe daemon RSS/PSS, Java heap,
  GC activity, CPU, delivered FPS and live-view latency.
- Exercise the 8887 legacy route with a stalled client; check disconnect and
  reconnect, and verify the normal viewer remains responsive.
- Change quality during congestion; confirm encoder retirement and SPS/PPS
  followed by IDR still recover on `/ws`.
- Compare recording and surveillance behavior at the same settings.

Expect earlier GOP skips or legacy reconnects on slow links, especially during
large keyframes. The numerical budgets are engineering defaults, not measured
optimal values. No recording quality, camera cadence or detection settings
are changed. Actual device-wide gains require measurement on a head unit.

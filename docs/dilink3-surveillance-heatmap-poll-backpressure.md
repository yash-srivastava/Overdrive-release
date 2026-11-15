# Prevent overlapping surveillance heatmap polls

The motion heatmap overlay polls `/api/surveillance/heatmap` every 333 ms.
The previous `setInterval` callback launched an asynchronous fetch without
waiting for the preceding fetch to finish. If the daemon or device took longer
than 333 ms to answer, requests could overlap and complete out of order. That
created avoidable network, JSON parsing, and canvas work, and an older response
could repaint over newer data.

The poller now schedules its next timeout only after the current request has
settled. This keeps at most one heatmap request in flight and preserves a
minimum 333 ms idle gap between completed polls. The effective update rate is
therefore capped at 3 FPS and naturally falls when the endpoint is slow.

A generation counter invalidates pending work when the overlay stops or
restarts. A response from an earlier session cannot draw onto a removed canvas
or schedule another poll. The existing start/stop API and visibility handling
remain unchanged.

## Suggested verification

1. Enable the motion heatmap and inspect the network panel; requests should not
   overlap even when the endpoint is throttled beyond 333 ms.
2. Confirm the overlay continues updating at up to 3 FPS under normal response
   times and slows down when responses are delayed.
3. Hide the page or disable the heatmap while a request is pending. Confirm the
   old response does not repaint and no further polls are scheduled.
4. Re-enable the heatmap and confirm a fresh polling session starts normally.

No project build, automated tests, device checks, or measured performance
benchmarks were run for this change.

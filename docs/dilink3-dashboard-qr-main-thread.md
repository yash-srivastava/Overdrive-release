# Dashboard QR encoding off the UI thread

The dashboard remote-access panel displays a QR code for the selected tunnel.
Previously, `DashboardFragment.renderQr()` called `QrCodeGenerator.generate(url,
400)` synchronously, so expanding the section or selecting a tunnel could
block Android's main thread while a bitmap was generated.

The QR bitmap is now generated on a lazily created, single-thread worker.
Only the UI thread updates views. Duplicate URL notifications while encoding
are coalesced, and a generation token discards stale results after selecting a
different tunnel, collapsing the panel, or destroying the view. Unused stale
bitmaps are recycled; the worker is shut down when the dashboard view ends.

The old QR is hidden as soon as the user selects a different tunnel so it cannot
be mistaken for the new connection. The UI displays its existing placeholder
until the new bitmap is ready. The foreground QR image and its URL continue to
represent the same tunnel. Encoding failure retains the placeholder.

## Scope

This is an Android UI-process optimization for the dashboard, compatible with
Android 10 (API 29). No daemon, camera, vehicle-control, or DiLink 5-specific
logic is changed. The improvement is primarily removing a potential UI stall
when opening or switching the remote-access QR panel. It cannot guarantee a
system-wide frame-rate increase.

## Manual verification on a parked DiLink 3 vehicle

1. Expand the remote-access panel with an active tunnel; verify the QR code
   appears and can be scanned to open the displayed URL.
2. Switch tunnels rapidly while QR generation is pending. Verify a stale QR
   never reappears and the URL and bitmap always correspond.
3. Collapse the panel during generation, then expand it again. Confirm that
   results from the old request do not override the newer one.
4. Rotate or leave the dashboard during generation; confirm no stale UI
   updates, exceptions, or extra QR worker threads remain.
5. Compare dashboard main-thread frame-time spikes (especially p95/p99),
   jank counts, and time-to-visible-QR before and after this change.

No app build, tests, benchmarks, or on-device checks have been executed.

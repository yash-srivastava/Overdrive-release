# Consumed Escape in the remote viewer

This regression tool loads the actual remote-view HTML, application shell, core,
search palette and viewer scripts in Chromium. HTTP and WebSocket collaborators
are local dummy fixtures. It never contacts a vehicle, daemon or account.

Use an existing Node.js installation, `playwright-core` and Chromium:

```sh
node tools/tests/viewer-consumed-escape/run.mjs \
  --playwright-module /path/to/playwright-core/index.mjs \
  --browser /path/to/chromium
```

No dependency is downloaded by this tool. Run it in an isolated, network-disabled
environment with the checkout and tools read-only and bounded scratch resources.
`--font /path/to/a/local/Hebrew-capable.ttf` supplies an optional fixture-only font.
`--screenshot` emits one bounded PNG as a `SCREENSHOT` base64 line; otherwise only
case summaries and structured `RESULT` JSON are emitted.

The six cases cover English, French and Hebrew at 360 and 1280 pixels. A real
search-input Escape must close search while retaining CSS fallback fullscreen;
the next viewer-focused Escape must exit fullscreen without sending remote Back.
Already consumed events and the search field's legacy `keyCode: 27` handling stay
local. Explicit remote keyboard capture still sends text and mapped keys exactly
once: its consumed Escape sends remote Back while retaining fullscreen. Ctrl+K,
slash, local search typing and stopped sessions must not leak remote input.

The fallback is selected by disabling only the viewer's native fullscreen request
methods. The fullscreen button is pre-focused with `preventScroll` before an
actual pointer click. An initial Hebrew wide-screen control after textarea capture
delivered pointerdown without a complete button click; its trace is preserved in
the TDD record. This separate pointer/focus observation needs validation and is
not changed by this fix. Browser-managed native fullscreen Escape can exit fullscreen independently
of JavaScript cancellation and is outside this test's contract. This is a browser
source fixture, not a vehicle hardware or installed-app acceptance test. Run the
required `./gradlew assembleDebug` and `./gradlew test` before submitting a PR.

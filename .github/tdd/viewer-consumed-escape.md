# Respect consumed Escape events in the remote viewer

## Scope and cause

Baseline: `6899b901a5d4bcc5adc09ad45c7ad7a227359b14`.
The search input prevents the default action of Escape and closes its palette.
The event then bubbles into the remote viewer's document listener, which handles
fullscreen before checking whether an earlier handler consumed the event.
Consequently, dismissing search also exits CSS fallback fullscreen.

The same event ordering affects explicit remote keyboard capture: its target
handler sends remote Back and consumes Escape, then the document listener also
exits fullscreen. Consumed capture Escape should send exactly one Back; a separate
unconsumed viewer Escape should exit fullscreen without sending remote Back.

The fix will respect `event.defaultPrevented` at the document listener entry.
Only the dedicated HTML script version token changes to refresh cached clients.
Native fullscreen palette placement belongs to a separate PR. Browser-managed
native fullscreen Escape is outside this fallback-specific JavaScript contract.

## Regression tool

`tools/tests/viewer-consumed-escape/run.mjs` loads the actual shipped HTML, shell,
core, search and viewer scripts in Chromium with dummy HTTP/WebSocket collaborators.
English, French and Hebrew run at 360 and 1280 pixels. Controls cover search
Ctrl+K/slash, local typing/arrows, consumed Escape, legacy keyCode Escape, direct
remote text/mapped keys, viewer-only Escape, stopped sessions, language direction,
missing assets, browser errors, external requests and unintended writes.

## RED

The unchanged production baseline fails exactly 18 of 168 checks: each of the six
cases exits fullscreen after the palette, a separate local handler, or explicit
remote capture has consumed Escape. The other 150 checks pass. All six cases reach
their intended assertions; no setup failures or diagnostic recovery occur.
The bounded offline sandbox completes with exit 1 in 44.025 seconds.

The accepted runner SHA256 is
`2ba5f8c860e89c5f23a46edf26de2eaa3369ac70433f9d967e061937dbd0b5f2`.
Parent-retained `fullscreen-escape/stage4.log` and `stage4-result.json` record the
exact command, source/fixture hashes, checks and resource bounds: 2 CPUs, 2 GiB
memory, 512 MiB scratch, 256 processes and 180 seconds, network disabled and
read-only source/tools. Only dummy capability/session/input fixtures are used.

Initial runs are preserved separately: stage1 asserted focus before search's
deferred focus callback; stages2/3 completed five cases but the Hebrew 1280-pixel
pointer control delivered button pointerdown without button pointerup/click.
A diagnostic DOM click then activated fullscreen, so no fullscreen-handler fix
was inferred from that incomplete pointer sequence. The accepted tool pre-focuses
the button with `preventScroll` before a real pointer click; all six pointer
controls then complete. The original pointer/focus observation remains outside
this Escape fix and needs separate validation; it is not declared fixture-only.

## GREEN and submission gates

GREEN is pending. Independent review and the actual `./gradlew assembleDebug` and
`./gradlew test` runs remain required before submission. This browser source
fixture does not establish installed-app, BYD hardware or fleet acceptance.

# Search palette in native fullscreen

Journeys were derived from the reproduced upstream UI defect: opening search
from the remote viewer's native fullscreen creates a body-level dialog that the
browser cannot paint or focus outside the fullscreen top layer.

## RED checkpoint

Base: `6899b901a5d4bcc5adc09ad45c7ad7a227359b14`.
Production `search.js` was unchanged (`922e352eb030eb631922386bae4fa7a2dfaff6b2`).
The new maintained browser tool ran in a network-disabled, read-only sandbox:

```sh
/opt/node/bin/node /src/tools/tests/search-native-fullscreen/run.mjs \
  --root /src --playwright /opt/playwright-core/index.mjs \
  --chromium /opt/chromium/chrome --font /case/DejaVuSans.ttf \
  --source 6899b901a5d4bcc5adc09ad45c7ad7a227359b14 --screenshot
```

Result: **0/9 passed, exit 1**, 13.833 seconds, no resource-limit stop.
All six English/French/Hebrew × 360/1280 cases failed the same oracle:
`open=true, focused=false, painted=false, host=BODY, fullscreen=viewerCard`.
The prefixed API alias reproduces the same defect; replaced-canvas exit times
out and rejected exit leaves an invisible active palette on the old source.
The input hit-test and focus assertions establish the rendering defect; CSS
visibility alone would have missed it. Scratch evidence was captured outside
the target container for independent review.

## GREEN checkpoint

Pending implementation and validation. No passing result is claimed yet.

## Boundaries

The test loads actual shipped HTML/scripts/translations with dummy intercepted
HTTP and WebSocket responses. No live daemon, device, identity or vehicle is
used. The prefixed test uses Chromium with WebKit aliases, not WebKit itself.
Browser Escape can be reserved by the user agent. The separate CSS-fallback
Escape defect and vendor hardware acceptance are outside this fix.

[Fullscreen API](https://fullscreen.spec.whatwg.org/) defines the fullscreen
element in the top layer and asynchronous fullscreen change events.

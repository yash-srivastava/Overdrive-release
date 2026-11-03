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

The same overlay is now mounted in the active supported HTML fullscreen
container, returning to `document.body` on exit/close. Unsupported fullscreen
hosts must exit before it becomes visible. Open request generations and pending
exit identity prevent canceled or stale callbacks from acting on another open;
deferred focus is canceled on close. Repeated `open()` retains the first caller.

The initial nine cases were expanded to fourteen with thrown/missing exits,
void-returning pending completion/cancellation and stale-rejection controls.
The full tool passed **14/14, exit 0**, 20.942 seconds, no resource-limit stop:

```sh
/opt/node/bin/node /src/tools/tests/search-native-fullscreen/run.mjs \
  --root /src --playwright /opt/playwright-core/index.mjs \
  --chromium /opt/chromium/chrome --font /case/DejaVuSans.ttf \
  --acorn /case/acorn.mjs --source 8f8e7e3bdabd6616b64143d2e551a4d75571bd76
```

The source label is the RED checkpoint plus the then-uncommitted fix. The tested
production `search.js` SHA-256 is
`ca7d6e5052c472661cc1238104df603b955d10516ddbb86a140537751c39b4e5`;
the tested runner SHA-256 is
`05a82567d92750af7ca0e65bfd8a3e292826019c9960dc7960f0e3a817606a15`.
The GREEN commit preserves these exact bytes. The optional Acorn parse passed
with `ecmaVersion: 5`. No JavaScript dependency was installed or downloaded.

| Guarantee | Focused browser case | Result |
| --- | --- | --- |
| Input is painted at its center and owns focus in fullscreen | Native EN/FR/HE × 360/1280 | PASS |
| Entry, exit and nested owner changes preserve query, selection and usable focus | Native matrix | PASS |
| Language change updates search while retaining query and session | Native matrix | PASS |
| Closed/repeated/rapid open-close does not steal focus or lose original caller | Native matrix | PASS |
| Local search keys and transitions produce no remote input; intentional keyboard and pointer controls do | Native matrix | PASS |
| Dummy session/socket identity survives all tested search transitions | All 14 cases | PASS |
| Standard and prefixed fullscreen element/change paths work | Native matrix; prefixed alias/event | PASS |
| Replaced canvas fullscreen exits before showing a painted, focused palette | Canvas normal; pending completion | PASS |
| Rejected, thrown or missing exits expose no invisible active palette | Canvas reject/throw/missing | PASS |
| Canceled exit cannot reopen search; stale rejection cannot close a new request | Canvas pending cancel/stale reject | PASS |
| No page/console errors, missing assets, external requests or media access | All 14 cases | PASS |

On this standalone branch, the native Escape observation closes the palette and
exits fullscreen in all six matrix cases, with no remote input writes. The
viewer Escape handler is unchanged here; its separate fix is validated in its
own PR. No claim is made that the browser's fullscreen Escape is overridden.

The intermediate runs are preserved: one stopped before browser launch because
the daemon's `/usr` view lacked Acorn (the existing parser was copied unchanged
into this case's read-only fixture); a second caught a genuine missing focus
condition after leaving a replaced fullscreen host and an incorrect nested-stack
wait in the helper. Both were corrected before the final GREEN run.

Retained evidence hashes:

- RED result: `c737b318b301be6271b6cf570e5e3ee8f3afbc977879c96c5ae5273cf29a1a66`.
- GREEN result: `12dc5e32e058cf8017cea4499bb0be9f1a5562eca910295b86f28b297c899976`.
- GREEN log: `a9b8e2ed7f3c4bb963396aabec2fefb1c3015c8221c90f626ee05dac327f1f38`.

Whole-file line/branch coverage was not collected; the table records behavioral
coverage of this change. Full `./gradlew assembleDebug` and `./gradlew test`
remain the coordinator's submission gates against the final committed source.
Those results must be recorded in the PR before submission; this focused
browser result does not substitute for them.

## Boundaries

The test loads actual shipped HTML/scripts/translations with dummy intercepted
HTTP and WebSocket responses. No live daemon, device, identity or vehicle is
used. The prefixed test uses Chromium with WebKit aliases, not WebKit itself.
Browser Escape can be reserved by the user agent. The separate CSS-fallback
Escape defect and vendor hardware acceptance are outside this fix.

[Fullscreen API](https://fullscreen.spec.whatwg.org/) defines the fullscreen
element in the top layer and asynchronous fullscreen change events.

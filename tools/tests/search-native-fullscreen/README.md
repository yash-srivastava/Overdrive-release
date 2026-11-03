# Search in native fullscreen

This focused Playwright regression tool loads the shipped remote-viewer HTML,
shell, core, search and translations. It checks actual input hit testing and
focus in native fullscreen, transitions, locale/query/selection preservation,
original focus restoration, standard and prefixed fullscreen APIs/events,
replaced-element fallback and rejection, and dummy stream/control ownership.
English, French and Hebrew run at 360 and 1280 CSS pixels. The prefixed API test
uses aliases around Chromium's actual native fullscreen; it is not a WebKit run.

Use an existing Node.js 20+ and Playwright installation with Chromium. No package
installation or download is part of this tool. Run **inside a disposable OS
sandbox** with no network, a read-only checkout/toolchain, scratch-only writes,
an empty allowlisted environment and explicit memory/CPU/PID/disk/wall limits:

```sh
node tools/tests/search-native-fullscreen/run.mjs \
  --root /src --playwright /opt/playwright-core/index.mjs \
  --chromium /opt/chromium/chrome
```

Optional `--font /case/DejaVuSans.ttf` supplies an existing local font;
`--source <revision>` labels the source, and `--screenshot` emits one bounded
failure screenshot as a base64 `SCREENSHOT` line. `CASE` and `RESULT` lines report
outcomes; any failure produces a nonzero exit. All HTTP requests are intercepted
locally and external requests fail. Sessions, credentials and WebSockets are
synthetic; no daemon, device, account, microphone or vehicle is contacted.

The browser may reserve Escape to exit native fullscreen before JavaScript sees
it. This tool does not promise to override browser Escape or cover the separate
CSS-fallback viewer Escape defect. This is browser compatibility evidence, not
Android/vendor WebView or vehicle hardware acceptance. `./gradlew assembleDebug`
and `./gradlew test` remain required by CONTRIBUTING.md.

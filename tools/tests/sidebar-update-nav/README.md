# Sidebar “Check for Updates” visibility

Playwright regression for mobile **More** (עוד): open menu → **Network** or
**Notifications** → **Devices** tab → reopen **More** (three cycles). Same
390×844 viewport; **baseline** serves `main:app-shell.js` via `git show`,
**fixed** serves the branch asset.

Assertions are strict: `#navUpdateLink` must be **fully inside** the sidebar
scrollport (padding), and `elementFromPoint` at the link centre must hit the
link — not merely `hidden === false` or partial intersection.

Also covers Hebrew RTL, desktop 1280 (no phone sheet; update action clickable
after normal desktop scroll), and delayed `update-flow.js` inject while the menu
stays open.

```bash
cd tools/tests/sidebar-update-nav
npm install playwright@1.65.0 --no-save   # once
cd ../../..
CHROMIUM_EXECUTABLE=/usr/local/bin/google-chrome node tools/tests/sidebar-update-nav/run.mjs
```

Requires Chromium/Chrome; does **not** need Android SDK.

**Limitations:** no in-car WebView (`data-android-embed`); Gradle asset tests
need `ANDROID_HOME`.

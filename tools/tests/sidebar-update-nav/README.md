# Sidebar “Check for Updates” visibility

Playwright regression for the mobile **More** (עוד) menu: after navigating to
Network / Notifications (connected devices / browsers), reopening the sidebar
must keep `#navUpdateLink` in the scrollable viewport.

```bash
cd tools/tests/sidebar-update-nav
npm install playwright@1.65.0 --no-save   # once
cd ../../..
CHROMIUM_EXECUTABLE=/usr/local/bin/google-chrome node tools/tests/sidebar-update-nav/run.mjs
```

Requires Chromium/Chrome; does **not** need Android SDK or a device.

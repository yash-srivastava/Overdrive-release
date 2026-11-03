# Remote-access readable tracking

The public accessibility contract forbids negative letter spacing. Fresh upstream main ade874fc still has three negative declarations in remote-access.css: headings (-.025em), radar state (-.04em), and metric values (-.04em). The original canonical baseline records the failing DesignSystemAssetTest method. The fix changes exactly those three declarations to `letter-spacing: normal`; selectors, sizing, colors, state logic and the new Hebrew/RTL wiring are retained.

## Focused validation

- Unchanged DesignSystemAssetTest passes all seven methods against the corrected assets.
- Browser fixture: 18 configurations (360/768/1280px widths, English/French/Hebrew, normal/doubled computed text size), each before and after: 36 isolated page renders. It executes the real core.js i18n module with real catalogs; unrelated page scripts and device API work are blocked. Advanced mode is enabled so every affected heading/state/metric target has positive visible geometry.
- Every corrected target has normal/nonnegative computed spacing, visible nonempty text, and no added target clipping or document horizontal overflow versus the matched original CSS in the sampled matrix. Root-catalog lang and Hebrew RTL direction are checked.
- Before/after Hebrew screenshots at 768px were visually inspected: glyphs are readable, headings and three displayed metrics remain visible, layout is preserved. This is not a subjective universal typography or device-font guarantee.

The first stage6 matrix left advanced metrics hidden and exposed missing Hebrew fonts in the sandbox (tofu boxes). Those outputs were retained with their limitations and are not accepted as Hebrew readability or metric validation. Stage9 is the corrected result: advanced targets must be visible; Hebrew uses an already-installed DejaVuSans.ttf copied read-only into the fixture, with recorded SHA-256 and FontFaceSet readiness checks. No font was downloaded or added to the product. English/French retain the environment's existing fonts. The doubled text-size check is an explicit fixture scaling operation, not Android system-font acceptance.

Stage9 exits0 in35.368s, under network-disabled, read-only source/tool isolation with 2CPUs/2GiB memory/256MiB scratch/256PIDs/150s limits. Source/test/font hashes and the two advanced PNG hashes are retained in the parent-owned evidence. The fixture routes only local source assets and uses dummy RSSI values; it does not operate proximity, Bluetooth, locks or vehicle APIs.

Run `node tools/tests/remote-access-tracking/run.mjs` with an existing Playwright installation (PLAYWRIGHT_MODULE and CHROMIUM_EXECUTABLE can select local tools). TRACKING_BASELINE_CSS optionally enables the exact three-declaration before/after comparison; TRACKING_FIXTURE_FONT supplies a local Hebrew-capable fixture font. Then run `./gradlew assembleDebug` and `./gradlew test` as required by CONTRIBUTING. Full wrapper results remain a parent publication gate. Physical Android/BYD font, firmware and fleet acceptance are not established.

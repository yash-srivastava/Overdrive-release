## What does this PR do?

Use normal letter spacing for remote-access headings, proximity state and signal metrics. Preserve the existing layout and Hebrew/RTL changes, and add a before/after typography regression fixture.

## Why is this change needed?

Three negative tracking declarations violate the repository's existing accessibility contract and compress already small on-screen text. The change restores the normal spacing of the selected font.

## How to test

DesignSystemAssetTest passes all 7 methods. The recorded Chromium fixture passes 18 viewport/locale/text-scale configurations before and after (36 renders), with all affected targets visible, normal spacing and no added observed clipping or horizontal overflow. It uses actual shipped HTML/CSS and core i18n/catalogs, dummy state, blocked device scripts, and a recorded local Hebrew-capable fixture font. Run `node tools/tests/remote-access-tracking/run.mjs` with existing local Playwright/Chromium and optional baseline/font paths. Run `./gradlew assembleDebug` and `./gradlew test` before submission; canonical results remain pending parent validation. See `.github/tdd/remote-access-tracking.md` for setup and font/device limits.

## Screenshots (if applicable)

Before/after 768px Hebrew screenshots were retained and visually reviewed in the local validation evidence. They show readable glyphs, all three signal metrics and preserved layout. They do not establish actual Android/BYD system-font acceptance.

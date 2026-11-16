# Charging location tariffs (multi)

Headless Chromium flow: add three location tariffs on the Settings tab (Hebrew RTL), reload, confirm all persist.

```bash
npm install --prefix tools/tests/charging-tariffs-multi playwright@1.49.0
npx --prefix tools/tests/charging-tariffs-multi playwright install chromium
PLAYWRIGHT_MODULE="$(pwd)/tools/tests/charging-tariffs-multi/node_modules/playwright/index.mjs" \
  node tools/tests/charging-tariffs-multi/run.mjs
```
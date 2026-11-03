## What does this PR do?

Repair stale GenAI source contracts after localization and generic diagnostic-mode streaming. Keep the existing safety assertions, use actual translation call sites and asset identity, and add behavior checks against the shipped GenAI and i18n code with English, French and Hebrew catalogs.

## Why is this change needed?

The public V58 tests require English text to appear directly in JavaScript and hard-code an older cache token. Those assertions fail although the rendered text is supplied by the locale catalog. Diagnostics now follows the generic stream instead of a separate conditional; the behavior fixture checks the forwarded mode and user message.

## How to test

Run `node tools/tests/genai-localized-contracts/run.cjs` (63 focused assertions) and GenAiAssetContractTest (5 methods). Both pass in the recorded isolated setup. Run `./gradlew assembleDebug` and `./gradlew test` from the configured checkout before submission; full contribution results will be added after parent validation. See `.github/tdd/genai-localized-contracts.md` for the original failures and fixture limits.

## Screenshots (if applicable)

Not applicable: production UI and layout are unchanged.

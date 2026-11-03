## What does this PR do?

Repair the Communicate resource-lifecycle contract to recognize localized timeout/inactivity reasons, preserving the English fallbacks and all teardown requirements. Add a behavior fixture against the complete shipped script.

## Why is this change needed?

The old test requires direct English stopTalk arguments and fails after the existing translation helper was introduced. The application already cleans up correctly in the focused scenarios; this change corrects the test rather than changing microphone behavior.

## How to test

Run RemoteCommunicationAssetContractTest (10 methods) and `node tools/tests/communicate-localized-lifecycle/run.cjs` (134 assertions). The recorded isolated run passes both; the original class reproduces its one failure. Run `./gradlew assembleDebug` and `./gradlew test` before submission; full contribution results remain pending parent validation. See `.github/tdd/communicate-localized-lifecycle.md` for coverage and limits.

## Screenshots (if applicable)

Not applicable: production UI and layout are unchanged.

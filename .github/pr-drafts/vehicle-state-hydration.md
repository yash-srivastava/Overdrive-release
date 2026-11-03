## What does this PR do?

Replace the stale prohibition of translated initial vehicle-status labels with a contract on the locale callback that restores actual live state. Add behavior checks using the real vehicle script and i18n implementation.

## Why is this change needed?

The existing callback already rerenders lock and cloud state after hydration. The old test rejects the translated HTML placeholders themselves and fails before checking that behavior.

## How to test

Run VehicleControlAvailabilityAssetTest (10 methods) and `node tools/tests/vehicle-state-hydration/run.cjs` (447 assertions). Both pass in the recorded focused isolated run after reproducing the original one-method failure. Run `./gradlew assembleDebug` and `./gradlew test` before submission; canonical results remain pending parent validation. See `.github/tdd/vehicle-state-hydration.md` for the state matrix and source-fixture limits.

## Screenshots (if applicable)

Not applicable: production UI and layout are unchanged.

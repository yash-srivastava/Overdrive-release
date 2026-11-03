## What does this PR do?

Repair one stale remote-view clearing assertion and add a behavior fixture covering localized notices, frame clearing and late image decoding.

## Why is this change needed?

The page already wraps its resume notice in the translation helper. The old direct-English source assertion fails even though the real callback still clears the frame. Strict JWT, physical PIN and lock/canvas assertions remain.

## How to test

Run `node tools/tests/remote-view-localized-clear/run.cjs`: 46 recorded assertions pass. The selected source-contract method also passes after reproducing its original failure. The complete source-contract class has independent cache-token failures addressed separately. Run `./gradlew assembleDebug` and `./gradlew test` before submission; canonical results remain pending parent validation. See `.github/tdd/remote-view-localized-clear.md` for focused-wrapper and device limitations.

## Screenshots (if applicable)

Not applicable: production UI and layout are unchanged.

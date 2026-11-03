## What does this PR do?

Repair the Cloudflared recovery test's removed `stopDaemonSilent` source anchor. Check the current stop callback's ERROR/refresh behavior, preserve failed-stop URL retention, and explicitly guard successful STOPPED/URL clearing. Add a deterministic fixture executing the actual Kotlin stop methods.

## Why is this change needed?

The old test fails before reaching its behavior checks because that helper no longer exists. Current production callbacks already distinguish failed and successful stops. This PR changes tests and validation tooling only.

## How to test

The exact baseline's affected method fails; the updated method passes. Four immediate/delayed callback scenarios pass and two incorrect callback mutations are rejected using verbatim source excerpts. See `.github/tdd/cloudflared-stop-contract.md` and the fixture README for commands and limitations.

Run `./gradlew assembleDebug` and `./gradlew test`. Final canonical results are pending and must be added before publication; focused source-excerpt results do not replace these commands or device acceptance.

## Screenshots (if applicable)

Not applicable: no production UI change.

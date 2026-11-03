## What does this PR do?

Validate the local remote-view scripts by asset path, source-file existence, uniqueness and dependency order, allowing cache-query updates. Add negative checks for missing/external/duplicate/commented references and data attributes.

## Why is this change needed?

Two tests pin the obsolete `?v=10` string while the page already ships `?v=11`. They fail even though the expected script and authenticated no-store frame flow are still present. Authentication and frame transport assertions are preserved; production code is unchanged.

## How to test

The exact baseline's two affected methods fail; both modified methods and two new mutation tests pass against the tracked assets. See `.github/tdd/remote-view-script-assets.md` for commands and fixture limits.

Run `./gradlew assembleDebug` and `./gradlew test`. Final canonical results remain pending and must be added before publication. Other pre-existing baseline failures are separate work.

## Screenshots (if applicable)

Not applicable: test contracts only, no UI change.

# GenAI localization contract repair

The unchanged public V58 GenAiAssetContractTest has two failing methods because it looks for English UI literals directly in genai.js. The production script now resolves those messages through BYD.i18n. After correcting the first literals, the same method also exposed an obsolete diagnostic-mode special-case and genai.js?v11 assertion (actual asset is v14). The mode now travels through the generic streaming path; source attribution uses its translated map entry.

Only test code changes. Existing authentication, session-only storage, opt-in presentation, DOM text rendering and manual-automation checks remain. The contract asserts the actual translation calls/map entry and shipped script identity rather than English literals/cache revisions.

## Recorded checks

- Unmodified V58: actual JUnit class runs five methods, two fail at its original assertions.
- Updated class on ade874fc: five JUnit methods pass.
- `node tools/tests/genai-localized-contracts/run.cjs`: 63 assertions pass. The harness executes the complete shipped GenAI script and actual core.js i18n module, loading real en/fr/he catalogs including English fallback. It checks saved-manual safety copy, confirmation/context labels, community zero/one/many results, missing-key behavior, locale changes, and forwarding automation_diagnostics to the generic stream with the original user message.
- An earlier fake-translator attempt failed on a missing French key; that fixture was corrected to use the actual i18n implementation. It is not a production defect or accepted GREEN evidence.

All execution used bounded, network-disabled isolation: JDK21/Node, 1 CPU, 512MiB memory, 128MiB scratch, 64 PIDs, 90s wall-clock limit, read-only source/tools. No provider/account/vehicle was contacted. Parent-retained stage1/stage8 logs and SHA-256 source inputs distinguish baseline RED from final passing focused checks. This is source/UI-fixture validation, not installed-device or fleet acceptance. Full wrapper build/test checks remain a parent publication gate.

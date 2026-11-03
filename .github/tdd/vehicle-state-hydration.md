# Vehicle live-state labels after locale hydration

The baseline contract rejects data-i18n attributes on lock/cloud labels. Those attributes now provide translated initial copy, and the existing onChange callback restores live state after hydration. Prohibiting the attributes contradicts the implemented lifecycle; no production state bug was reproduced.

The repaired source contract checks both translated initial labels and all three state renderers inside the locale-change callback registered before boot. Existing partial-door, cloud availability, pending-mutation and other assertions remain unchanged.

Stage5 reproduces the exact baseline ten-method class with one failure, then the repaired ten-method class passes. `node tools/tests/vehicle-state-hydration/run.cjs` passes 447 behavior assertions. It executes the complete vehicle-control script and actual core.js i18n module/catalogs with fake DOM elements retaining their data-i18n attributes. Unrelated model/network/control boot collaborators are stubbed; the real VC.init locale registration and state renderers execute. English/French/Hebrew initialization, English/Hebrew locale switches, all unknown/locked/unlocked and vehicle/driver-door scopes, four cloud states, button highlights, indicator attributes and missing-key English fallbacks are checked. A callback exception is a fixture failure. The fixture never calls an actual vehicle API.

Execution is bounded and network-disabled: read-only source/tools, 1CPU/512MiB memory/128MiB scratch/64PIDs/90seconds. Exact source hashes/logs are retained in stage5. Full CONTRIBUTING commands remain a parent publication gate; these fixtures do not establish Android/vehicle/fleet acceptance.

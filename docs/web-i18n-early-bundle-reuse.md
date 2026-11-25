# Reuse the early APK translation catalog in WebView initialization

Non-English WebView pages translate the HTML during `theme.js` bootstrap so
first paint does not flash English. `core.js` adopts that parsed catalog and
loads the English fallback, but `BYD.i18n.init()` previously asked the native
bridge for the active locale again and parsed the entire JSON again. The native
bridge already caches APK strings; this optimization removes the redundant
bridge transfer and JavaScript parse, not an APK disk read on a warm cache.

```text
Before: theme -> bridge(active) -> parse -> core early hydration
        core  -> bridge(en)     -> parse -> English fallback
        init  -> bridge(active) -> parse -> full hydration + notify
After:  theme -> bridge(active) -> parse -> core early hydration
        core  -> bridge(en)     -> parse -> English fallback
        init  -> reuse parsed APK catalog -> full hydration + notify
```

`theme.js` labels an early catalog only when the bridge returned parseable,
truthy JSON. `core.js` also records its own successful synchronous APK read on
pages without a matching early catalog. The handoff is document-local,
single-use, and matched against the resolved locale; any different requested
locale discards it. Browser localStorage catalogs (including those used after
an in-app bridge failure) and older theme scripts without the marker still
follow the existing refresh path. English fallback readiness, locale revision
guards, full hydration, subscriber notification, RTL handling and external
browser revalidation remain intact. No additional native cache is introduced.

With the source catalogs at the baseline revision, an Arabic startup with both
bundled reads succeeding goes from 2 x 551,671 + 433,350 = 1,536,692 UTF-8 source
bytes parsed to 551,671 + 433,350 = 985,021 (about 35.9% less input), and from
three catalog bridge calls to two. The active catalog alone is parsed once
rather than twice. Hebrew saves 557,124 source bytes, and French saves the size
of its active catalog on each qualifying page initialization. Actual Java/JS
string representation, bridge copying, object allocation, CPU time and memory
pressure depend on the WebView implementation. These are workload counts, not
measurements of navigation latency or total system performance. English-only
startup, external browsers and failing native bridges do not get this fast path.

The existing cache URLs are intentionally unchanged to avoid editing every page
and overlapping independent changes. Current extracted assets become available
on cache revalidation/expiry (existing static max-age is one hour). Mixed old
and new theme/core versions safely use the original refresh path when the marker
is absent or ignored. Reload/clear the web asset cache for immediate A/B testing.

## Verification

- `node --check app/src/main/assets/web/shared/theme.js`
- `node --check app/src/main/assets/web/shared/core.js`
- `node app/src/test/js/i18n_early_bundle_reuse_harness.js`
- `node app/src/test/js/theme_bootstrap_sync_harness.js`
- `node app/src/test/js/lang_picker_lifecycle_harness.js`
- `node app/src/test/js/core_personalized_range_request_harness.js`
- `./gradlew assembleDebug` and `./gradlew test` (maintainer/CI).

On a parked device, compare identical non-English WebView page navigations with
warm and cold caches. Confirm early translated labels, fallback labels missing
from the active locale, direction changes, language switches during startup,
and recovery when the native bridge is unavailable. Repeat in an external
browser with a deliberately stale localStorage catalog. Profile bridge calls,
JavaScript parsing and allocation, and first-paint/page-ready timing on the same
hardware; no device performance numbers are claimed here.

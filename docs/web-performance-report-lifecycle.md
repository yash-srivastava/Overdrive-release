# Load Performance reports only in the owning subview

The Performance page previously fetched SOC history, battery health, SOH and
network data usage on initialization and every minute while any subview was
visible. Battery health also requests HV-cell readings. The full SOC and
battery reports query historical data in `SocHistoryDatabase` and serialize
JSON in the daemon; the WebView then parses and renders those reports even
when the corresponding cards are hidden.

The Battery subview now owns the SOC, battery-health (including HV cells) and
SOH timers. Data owns the data-usage timer. System, Live and Reset have no
scheduled ancillary report requests. On entry, return from a hidden page or
reconnect, the visible owner refreshes immediately and then at the existing
60-second cadence. Switching away or hiding the page clears its timers.
Already-started requests can finish; a battery-health request finishing off
its owning tab no longer launches its HV-cell follow-up. Explicit user
operations (including a reset-triggered SOH refresh) remain unchanged.

Typical successful report requests per minute in a stable visible subview,
excluding realtime metrics, heartbeats, top snapshots and user actions:

| Subview | Before | After |
| --- | ---: | ---: |
| Battery | 5 | 4 |
| Data | 5 | 1 |
| System / Live / Reset | 5 | 0 |

These are request counts, not measured CPU, memory or power savings. The
reduction also applies to the initial set of reports, once the active tab is
known. First entry to an unvisited tab now waits for its own report rather
than relying on eager preloading. The three Battery report requests may run
concurrently at entry instead of the original sequential startup chain;
rapid tab changes can trigger additional refreshes, and existing in-flight
requests are not cancelled or deduplicated. The daemon's history collection,
realtime monitor sampling, recording and vehicle controls are not changed.

The `#soh-fix` notification link selects the Battery tab using the existing
tab click handler and scrolls after a successful SOH response. Leaving the
Battery tab before the response cancels the pending scroll. The script asset
version is bumped so the new lifecycle takes effect after deployment.

## Verification

- `node --check app/src/main/assets/web/shared/performance.js`
- `node app/src/test/js/performance_report_lifecycle_harness.js`
- `node app/src/test/js/performance_data_usage_request_harness.js`

On a parked device, open each subview and observe requests for at least two
minutes. Switch tabs, hide/show the page, reconnect, change report ranges and
open a SOH notification from both a fresh page and an existing non-Battery
tab. Verify reports populate, the Data toggle and disabled hint are accurate,
SOH links work, and System/Live monitoring and history collection continue.
Compare daemon CPU, database query time and WebView allocations with the
same history size and subview before and after. Device results are not yet
available.

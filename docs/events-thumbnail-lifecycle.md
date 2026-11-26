# Recording-list thumbnail lifetime

The Events List renders a page of recording cards, fetches each thumbnail via
its existing authenticated fetch path, and retries HTTP 202 responses with
exponential backoff (up to nine requests, including the first attempt). A list
can be replaced before these operations finish, for example while paging,
changing filters or entering selection mode.

Previously, retry callbacks retained detached card nodes and kept requesting
thumbnails for a list that was no longer displayed. Successful blob URLs were
never revoked on list replacement, so their underlying blobs could remain live
for the lifetime of the document.

Every list replacement now invalidates the associated thumbnail batch, clears
its pending retry timers, aborts its requests when AbortController exists and
revokes its object URLs. This includes spinner, empty, unavailable and failure
states. Asynchronous callbacks check batch identity and card membership before
starting another request, reading a response body or installing an image.
Chrome 58 has no AbortController; it still gets timer cancellation and stale
result rejection. A request that has already reached the daemon may still
finish there even on newer browsers.

Object URLs remain valid while their images belong to the displayed list, so
browser image actions continue to work. Failed image loads release their own
URL immediately and restore the existing no-preview fallback. The existing
retry schedule, authenticated fetch path, thumbnail source and recording data
are unchanged. This is not a new cache: reopening a page may fetch its
thumbnails again. Hidden or back-forward-cached documents with an unchanged
list keep their current batch; this change is specifically about replaced
lists, not page visibility or global request throttling.

## Illustrative workload

With a 12-card page, if every initial request returns HTTP 202 and the page is
replaced before the first retry, up to 96 subsequent requests (12 × 8) for that
obsolete page are avoided. For ten such pages, up to 960 obsolete requests are
avoided. Already-started server jobs are not cancelled by this guarantee.

If 100 consecutive 12-card pages successfully load their thumbnails, the old
code can leave 1,200 object URLs registered until the document is destroyed.
The new code retains at most 12 for the currently displayed page (plus one if
there is an in-flight placeholder). With illustrative 40 KiB JPEG blobs, this
corresponds to about 46.9 MiB versus 480 KiB of referenced encoded data, before
browser/decoder overhead. These are scenario estimates, not measured process
memory or network/CPU reductions. Decoded images, HTTP caches and in-flight
responses have separate lifetimes.

## Verification

Run `node app/src/test/js/events_thumbnail_lifecycle_harness.js` to check
current and stale 202 responses, cancellation with and without AbortController,
late response/body/image events, backoff, failure handling and 1,200 object URL
lifecycles across 100 simulated pages. Run the existing Events, storage-index
and SafeKeep harnesses, `./gradlew assembleDebug` and `./gradlew test`.

On a parked head unit, rapidly page/filter a large recording library while
thumbnails are generating; enter/exit selection mode and exercise index-down
and network failure recovery. Confirm visible cards still load and retry as
before, and that obsolete cards stop issuing retry requests. Compare network
requests and WebView/daemon memory over a long browsing session, on both an
older WebView without AbortController and a newer browser. Device behavior and
actual memory reclamation are not established by mocked lifecycle checks.

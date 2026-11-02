# Honor SafeKeep cancellation before link commit

An in-flight sign-in poll could store credentials and create a destination after
cancellation had already returned success. A short manager state lock now gives
cancellation and the final commit one admission decision: cancellation that wins
revokes the session before storage; an already claimed or completed commit returns
HTTP 409 instead of reporting cancellation success. Public link states remain
compatible, and accepted commits may finish.

HTTP, storage/configuration and lifecycle work remain outside the state lock.
Shutdown closes registration and revokes pending sessions without waiting for an
accepted commit, avoiding lifecycle lock inversion. Delayed registration, stale
retry/error callbacks, expiry, duplicate paste and commit failure paths follow the
same authority rules. The UI checks both cancellation responses and recovers paste
sign-in through status polling when a finish response is lost.

Validation: original RED checkpoint `ba9f850a4be7ce3f56caf7b31d1f621e4ebfb40f`
recorded 3 tests / 1 intended failure. The reviewed GREEN run passed **19 JUnit
lifecycle tests + 6 actual-JavaScript UI checks** inside the isolated, network-disabled
lab; `git diff --check` also passed. Tests compile actual `LinkManager`/`OAuthApps`
and use real OkHttp 4.12.0 with dummy collaborators. No real provider/account,
device or transfer is used.

Limits: available JSON 20240303 substitutes for declared JSON 20231013. The API409
bridge is source-reviewed, not runtime-tested by the standalone harness. These
checks do not establish full Android-build acceptance or real config persistence.
An accepted commit can finish after a cancellation attempt; the API/UI explicitly
report that cancellation was too late.

See [RED/GREEN evidence](../tdd/10-link-cancellation.md) for the contract, sandbox
bounds, source hashes and raw-log fingerprints.

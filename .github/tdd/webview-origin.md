# WebView daemon-origin admission: RED/GREEN evidence

Reviewed baseline: `2bba1bf55adc8c4004f7df4707b9c936356dcd2f` (V58).
Test commit: `403eb958ece7faf76488785bbb9ef3d573ed0c93`.
Implementation commit: `d61a9d95d0423128248afb70f1ae1c127c95d3bf`.

A loopback-looking URL with user information can target a different parsed host. Raw substring matching in native interception admitted the session header, while raw prefix matching in navigation retained the foreign document in the bridged WebView. Native events/widget routing also ran before an origin check.

The shared parsed predicate now requires HTTP, no user information, exactly localhost or 127.0.0.1, and exactly `CameraDaemon.HTTP_PORT`. It accepts a legitimate empty root path. Interception and navigation use this predicate, including the guards before native events/widget handling. Other destinations retain the existing ACTION_VIEW route.

## Executed checks

| Check | RED | GREEN |
| --- | --- | --- |
| Six committed JUnit origin-boundary tests, using a verbatim production-helper excerpt | Two expected failures: bare root rejected; user information accepted on the daemon host | Six pass |
| Four existing WebView embed asset contract tests | Four pass | Four pass |
| Real citation taps with original target/rel attributes on Android10/API29 WebView74.0.3729.185 | Credentialed foreign citation loads a foreign DOM and the owned collector receives the fixed dummy session | Legitimate internal navigation succeeds; ordinary and credentialed external links dispatch externally; no foreign UI request |
| Independent native header callback probes | User-info and query-marker URLs incorrectly receive a session | Both rejected; no foreign dummy session |
| Independent native navigation callback probes | Foreign events reach the harmless native-handler stub; foreign widget and wrong-port routing bypass the external route | Foreign events/widget/wrong-port URLs dispatch externally; legitimate empty-root URL remains internal |
| HTTP response and asset-routing controls | Pass | Pass |

The response controls verify status206, MIME `video/mp4`, three response bytes and `Content-Range: bytes 0-2/8`. Asset controls verify the native branch decisions for versioned pass-through, unversioned interception and requests with Range. They do not claim real recording playback or browser cache hits. The existing CDN classifier is exercised against an owned loopback collector without an injected session; no external CDN/proxy connection is attempted.

The Android fixture executes three real citation-tap controls, eight native interception probes and four native navigation probes. The original source blocks are extracted verbatim and hash-recorded. Outer UI collaborators and ACTION_VIEW launch are harmless stubs, and AuthManager returns only `DUMMY_V58_LABEL_NOT_A_CREDENTIAL`. The globally registered bridge exposes a page-ready log callback only; no privileged bridge action is executed.

All target code executes in a disposable OS-enforced sandbox with no external network, an explicit empty-environment allowlist, read-only source/tools/fixtures, and bounded scratch, CPU, memory, PID, file, output and wall-clock limits. The Android run uses only its task-owned ADB server and disposable AVD. Its loopback collectors have distinct IP addresses to separate native header injection from browser cookie-domain behavior.

Definitive RED: unit exit1, 10 tests/two expected failures; Android policy exit1 after52.600 seconds. GREEN: unit exit0, 10 tests passed; Android policy exit0 after53.220 seconds. An earlier fixture-only PID startup race occurred before assertions and was corrected with bounded process polling. Full raw logs and their hashes are retained separately from the repository. `git diff --check` passes.

These are source-excerpt and helper checks, not a full production/native APK build or an installation of the production application. Supported BYD firmware/WebView acceptance, real credential handling, providers, vehicles, recording durability and fleet regression safety remain unverified by these checks.

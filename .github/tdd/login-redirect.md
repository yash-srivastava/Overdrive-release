# Login redirect regression: TDD evidence

Before the fix, the login page accepted a query-string destination, decoded it a
second time, and assigned it to `window.location.href` after successful authentication.
The new regression executes every inline script from the actual
`app/src/main/assets/web/local/login.html`; it does not copy its redirect logic.
Only the DOM, navigation, timers, storage, localization, and dummy auth responses
are supplied by the Node VM harness.

## User journeys and acceptance criteria

After a successful login, a user returns to a valid HTTP(S) destination on the
same origin, retaining its escaped path, query, and fragment. Invalid or unsafe
destinations fall back to `/` without preventing login. A rejected or failed
authentication request never navigates or stores a session.

| Guarantee | Regression cases | RED result |
| --- | --- | --- |
| Missing or empty destinations use `/` | Missing and empty redirect | Missing passes; empty fails |
| Executable and foreign destinations use `/` | JavaScript, mixed-case and obfuscated schemes, data URLs, foreign origins, changed scheme or port, and userinfo | Fails |
| Backslashes and raw control characters use `/` | Authority and path backslashes, null, newline, tab, carriage return, and DEL | Fails |
| Malformed escapes do not break page initialization | Invalid, incomplete, lone percent, and invalid UTF-8 escapes | Fails with `URI malformed` from the production script |
| Encoded scheme and authority text remains harmless local path data | Encoded JavaScript scheme and authority | Fails |
| Same-origin navigation remains compatible | Root, internal query/fragment, absolute URL, and explicit default port | Passes |
| A normalized pathname starting with `//` remains local | Same-origin absolute and dot-segment paths | Passes; protects against unsafe pathname reconstruction in a future fix |
| Existing percent escapes are preserved | Escaped path/query/fragment, literal percent signs, query data, and path slashes | Fails |
| Rejected or offline login neither redirects nor stores a session | Dummy rejection and network failure | Passes |

## Executed RED validation

Production baseline: `2bba1bf55adc8c4004f7df4707b9c936356dcd2f`.

Portable command (Node built-ins only):

```sh
node app/src/test/js/login_redirect_harness.js
```

For this security task the command was executed only through the existing
`v58-remediation/lab/sandbox.py`. The runner invoked
`/opt/node/bin/node /src/app/src/test/js/login_redirect_harness.js`, with the case-14
worktree mounted read-only, the `fix-14-login/scratch` fixture, no network,
256 MiB memory, a 64 MiB scratch filesystem, and a 30-second timeout. The retained
log includes the complete runner command and isolation settings.

Observed exit status: **1**, with **9/41 passing and 32 failing**. The retained
case artifact is `fix-14-login/scratch/login-redirect-red.log`. Representative
output:

```text
FAIL JavaScript URL falls back to the local root after successful login
+ 'javascript:document.documentElement.dataset.loginMarker=1;void 0'
- 'https://device.invalid/'
FAIL malformed percent escape falls back to the local root after successful login
Actual message: "URI malformed"
PASS absolute URL on the same origin is preserved after successful login
PASS local path normalizing to two leading slashes is preserved after successful login
PASS rejected login neither redirects nor stores a session
PASS network-error login neither redirects nor stores a session
9/41 login redirect tests passed
```

The ordinary auth-status and token POST flows execute before destination
assertions; successful cases also check dummy cookie and local-storage session
writes. The failing cases therefore demonstrate the intended production defect,
rather than a missing helper, dependency, or unrelated build failure.

### Extended RED before implementation

Review added a relative-path compatibility case and a `blob:` URL whose parsed
origin matches the page, so that the HTTP(S) requirement is tested independently
of the origin check. It also corrected the two encoded-prefix expectations:
`javascript%3Avoid(0)` and `%2F%2Fforeign.invalid/landing` should remain harmless
local relative paths, without another decode. The original code still fails
these cases because it turns them into an executable or foreign destination.

The same isolated command against unchanged production source exits **1**, with
**10/43 passing and 33 failing**. The extended capture is
`fix-14-login/scratch/login-redirect-red-extended.log`; the original RED capture
and checkpoint are preserved.

## Implementation and GREEN validation

`getLoginRedirect()` now resolves the query-decoded destination with the browser
URL parser and accepts only HTTP(S) URLs on the exact current origin, including
the effective port, with no parsed username or password. Backslashes, raw control
characters, empty values, malformed escapes, and rejected URLs fall back to `/`.
Percent escapes are validated without using the decoded result for navigation.
Returning the validated absolute URL preserves its authority even when path
normalization produces a pathname starting with `//`.

The same sandboxed Node command now exits **0** with **43/43 passing**. The
retained capture is `fix-14-login/scratch/login-redirect-green.log`.

A separate Chromium fixture serves the actual fixed source assets with dummy
auth-status and token endpoints on isolated loopback. It clicks the real login
button, checks the resulting browser URL, cookie and local-storage writes, and
blocks and records foreign requests. It also checks rejected and offline login.

```text
43/43 login redirect tests passed
19/19 browser login redirect tests passed
```

The browser command was `/opt/node/bin/node /case/login-browser-smoke.mjs`, run
through the same sandbox with no network, read-only source and fixture mounts,
1 GiB memory, a 256 MiB scratch filesystem, and a 60-second timeout. Chromium
`156.0.8078.4` passed **19/19 cases**, with no executed DOM marker, external
requests, or page errors. The capture is
`fix-14-login/scratch/login-browser-green.log`; its first two attempts preserve
fixture-only failures (module resolution, then browser URL serialization),
followed by the complete passing run. No production change was needed for those
fixture corrections. The fixture is retained separately as
`fix-14-login/scratch/login-browser-smoke.mjs`; original audit fixtures are unchanged.

Validated `login.html` SHA-256:
`3e693c63736da9131af3c478e476268b15b34cc88e40d1707397930c7b1be2fc`.

The original RED checkpoint is `8799768831c83e9e793c714e049aed732d6549bb`, and
the extended RED checkpoint is `97764d73d6373de74cdb3002cd0100924927088c`.
Preserve the RED/GREEN summary in the PR body if commits are squash-merged.

## Scope and limitations

The VM records resolved browser-style destination URLs; it does not execute
JavaScript URLs. The Chromium run validates source assets with dummy auth, not
native WebView behavior or real accounts. Independent browser evidence for the
original source and released assets remains in the case-14 validation artifacts.
No numeric coverage measurement, full Android build, or fixed APK execution was
performed; the released APK has not been rebuilt by this change.

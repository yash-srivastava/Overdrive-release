# Login redirect regression: RED checkpoint

The login page currently accepts a query-string destination, decodes it a second
time, and assigns it to `window.location.href` after successful authentication.
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
| Encoded executable schemes and authorities are not decoded again | Encoded JavaScript scheme and foreign authority | Fails |
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

## Scope and follow-up

This is a test-only RED checkpoint. Production code is unchanged. GREEN and the
production fix are pending the coordinated remediation release gate.

The VM records resolved browser-style destination URLs; it does not execute
JavaScript URLs or prove native WebView behavior. Independent browser evidence
for the original source and released assets remains in the case-14 validation
artifacts. No numeric coverage measurement or full Android build was run for
this test-only checkpoint.

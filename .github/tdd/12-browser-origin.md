# Loopback browser-origin authorization evidence

## Intended behavior

A foreign browser page must not acquire session authority merely because its
request reaches a loopback listener. Native requests without `Origin` retain the
existing local fallback. Valid JWTs retain their current primary authentication
behavior, including authenticated external webapps. Tunnel fingerprints,
JWT-only APIs, public/signed-asset routes and existing GenAI origin checks keep
their current behavior. HTTP and WebSocket ingress must apply the same fallback
policy.

## RED checkpoint

Production baseline: `2bba1bf55adc8c4004f7df4707b9c936356dcd2f`.

The standalone harness compiles original HTTP/authentication source and runs
49 assertions. After correcting two fixture expectations for the pre-existing
HotSpot IPv6 peer-classification denial, the final RED run compiled and executed:
**28 passed, 21 failed for the intended browser-origin authorization behavior**.
The earlier run is retained separately and is not the accepted checkpoint.

The accepted failures include foreign/opaque/malformed origins, a different
listener port, untrusted `Host`, duplicate `Origin`, an invalid JWT with foreign
origin, and foreign WebSocket admission. Native and same-loopback browser
requests, valid bearer/cookie validation outcomes, public assets, signed
thumbnails, tunnel rejection, JWT-only APIs and GenAI controls pass.

Isolated validation command (repository mounted at `/src`):

```sh
python3 <lab>/sandbox.py --src <worktree> \
  --fixture <worktree>/tools/tests/browser-origin \
  --timeout 60 --memory 768m --disk 128m --pids 64 --cpus 1 \
  --output-limit 65536 -- \
  /bin/sh /src/tools/tests/browser-origin/run.sh \
  /opt/jdk21 /lab/deps/json-20240303.jar /scratch/browser-origin
```

Recorded result: exit1, no timeout/output stop, 2.571 seconds. Final raw-log
SHA256: `576de896e0baef7356a9c2d805ca8c91be49eacb77a9d5786282ceb75ec3e458`.
No external network, real credentials, provider traffic or vehicle actions.

## Coverage and limitations

This is behavioral regression coverage for the original header parser and auth
decisions, with fixed dummy auth/storage/hardware collaborators. It is not a
whole-application coverage measurement. No JaCoCo percentage is claimed.
Project JSON20231013 is unavailable locally; JSON20240303 is a documented fixture
dependency. Actual browser, installed-app and supported firmware acceptance
require separate evidence. Requests with no `Origin` remain compatible with the
existing native fallback; this change does not identify every browser request.

## Implementation and GREEN evidence

HTTP and WebSocket ingress pass the actual Origin header and accepted listener
port to the shared middleware. Duplicate Origin headers become an opaque value.
Only Tier2 local fallback is restricted; valid JWT/public/signed asset precedence
is unchanged. Supplied origins must identify the HTTP loopback listener, without
credentials, query, fragment or non-root path. Native absent-Origin behavior and
the existing peer-address classifier remain unchanged.

The exact same 49 assertions compiled and ran against the changed source:
**49 passed, 0 failed**, exit0, no stop, 2.550s. Existing
`HttpServerProxyProtocolTest` and `GenAiRequestSecurityTest` also compiled and
passed: **6 JUnit tests**, exit0, no stop, 3.469s. The parent retained these
results after the author agent was interrupted by the service filter; the
interrupted runtime assignment was not retried.

Fixed-source Android browser controls were prepared but not executed. The
pre-change API29/WebView74 browser observation remains baseline evidence only;
no post-fix browser, installed APK or fleet acceptance is claimed. Final source
and retained fixture hashes must pass independent review before PR publication.


Retained GREEN log SHA256: `ab5306f587239b0e582473e286d4757f7ae17bb1b27e285b07d9b87c957ca946`.

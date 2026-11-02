# Loopback browser-origin regression

Run with an existing JDK, a provisioned `org.json` JAR, and a disposable writable
directory:

```sh
sh tools/tests/browser-origin/run.sh /path/to/jdk /path/to/json.jar /path/to/scratch
```

Run untrusted project tests inside an isolated environment: no external network,
read-only repository/tools, an empty allowlisted environment and bounded scratch,
CPU, memory, process count, file size and wall time. The validation used JDK21
and the already provisioned JSON20240303 fixture dependency; it does not fetch
dependencies. That JSON version differs from the project's test JSON20231013.

`prepare.py` retains `HttpServer.handleClient` and eight helper methods verbatim,
then copies the complete current `AuthMiddleware`, `HttpResponse` and
`GenAiRequestSecurity`. It prints hashes for every retained block. Socket I/O is
an in-memory request/response. The router returns a fixed dummy sentinel,
authentication uses fixed dummy validator outcomes, and WebSocket endpoints
record admission without opening streams or invoking hardware.

The tests exercise HTTP header plumbing, loopback fallback, valid/invalid JWT
results, public/signed-asset routes, tunnel fingerprints, JWT-only/GenAI gates
and WebSocket admission parity. They do not establish cryptographic validity,
actual browser policy, production routing, vehicle acceptance or fleet behavior.
The existing API29/WebView74 baseline is separate evidence.

The current peer classifier examines `SocketAddress.toString()`. HotSpot's IPv6
format is denied by that pre-existing classifier; one control records and
preserves it. IPv6 `Origin` parsing is exercised using an admitted IPv4 peer.
This regression intentionally avoids changing peer-classification semantics.

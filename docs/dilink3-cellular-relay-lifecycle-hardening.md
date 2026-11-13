# Cellular hotspot relay: credential isolation and bounded lifecycle

## Motivation

The client-facing HTTP CONNECT / plain HTTP / SOCKS5 relay is bound to the
hotspot gateway and uses a cellular-bound outbound socket. Clients authenticate
with the per-install hotspot proxy token. The old plain HTTP path forwarded
its complete proxy request header to the destination, including the
`Proxy-Authorization: Bearer ...` header. That credential belongs to the
**proxy**, not the origin; an HTTP site must never receive it.

There were also resource-lifecycle risks under connection bursts and hotspot
shutdown:

- The advertised 64-tunnel cap was checked on the accept thread, but only
  incremented in the worker. Several accepted clients could pass the check
  concurrently while workers had not yet incremented the count.
- `stop()` closed only the listening socket and released the Android network
  callback. Already established client and cellular sockets could continue
  relaying until the normal 60-second I/O timeout, even after hotspot sharing
  was disabled.
- Early failure after requesting a cellular network (for example an absent
  AP address or a bind problem) could strand the network request.

## Behavior of this change

1. Plain HTTP proxy requests strip `Proxy-Authorization` and
   `Proxy-Connection` before forwarding to the remote HTTP server. Original
   origin headers such as `Authorization` are retained. Malformed or folded
   headers are rejected instead of risking forwarding proxy credentials.
   HTTP CONNECT and SOCKS5 handshakes are still consumed locally, not
   forwarded to the destination.
2. A small lock-free admission gate reserves one of 64 slots **before** work
   is dispatched, so burst traffic cannot oversubscribe the cached thread
   pool. Slots are released in all worker and dispatch-failure paths.
3. Every accepted client socket and each outgoing cellular socket (including
   an in-progress connect) is registered while the relay session is live.
   `stop()` invalidates the session generation and closes those sockets,
   waking blocked reads and preventing further traffic on a stopped session.
   Stale callback notifications and accept threads from a previous session
   cannot publish a cellular network into a restarted relay.
4. Startup failures close the listener and unregister any partially issued
   Android `ConnectivityManager.requestNetwork` callback. AP gateway detection
   happens before requesting cellular access.

The existing hotspot opt-in, bind-address restrictions, per-install token,
SOCKS5 authentication, destination address restrictions, byte counters,
connection timeout and upstream protocol are not changed. No new service,
permission, dependency or background polling is introduced. Java/Kotlin APIs
used are available on Android 10 / API 29.

## Why a hard stop matters

The head unit can turn tethering off or switch the hotspot configuration while
active clients have open tunnels. Closing both ends prevents continued cellular
traffic, delayed resource release, or usage counters changing from stale
sessions. In-progress DNS resolution still depends on the system resolver, but
the session generation prevents a late result from creating a new tunnel.

## Proposed verification (not performed during patch preparation)

- Run `CellularRelayHardeningTest` as a JVM unit test. Verify the proxy bearer
  token never appears in the sanitized origin request, ordinary origin
  Authorization is retained, malformed headers fail closed, and concurrent
  admissions never exceed 64 slots.
- On an isolated local test network and **parked** Android 10 head unit,
  configure a client for the HTTP proxy and request a plain HTTP resource from
  a controlled test server. Confirm the proxy authorizes the client, but the
  origin does not receive either `Proxy-Authorization` or `Proxy-Connection`.
  Repeat with an HTTP CONNECT and SOCKS5 client.
- Open many concurrent slow clients. Confirm that the server accepts at most
  64 active/queued sessions, promptly closes excess connections, and does not
  create an unbounded number of worker threads.
- Start several established tunnels, turn hotspot proxying off, and verify
  all those sockets close promptly and no further relay data is counted.
  Turn it back on and verify new connections work with the configured token.
- Simulate missing AP gateway or failed network registration in a controlled
  environment. Confirm a failed start leaves no listening port or active
  cellular network callback.

The patch has only been checked for diff integrity and applicability. It has
not been compiled, tested, benchmarked or exercised on vehicle hardware.

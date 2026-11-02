# LinkManager cancellation regression

This standalone JUnit 4 suite compiles the repository's actual `LinkManager.java`
and `OAuthApps.java`. No copy of their lifecycle logic is maintained here. Small
test collaborators replace the daemon, configuration storage, logger, scratch
path, and HTTP client factory. They live outside `app/src/test` so their production
class names cannot collide with an Android Gradle test classpath.

The real OkHttp client's application interceptor supplies fixed dummy responses;
it never calls `chain.proceed`. No provider, account, real token, device or socket
is used. The tests observe calls to in-memory storage collaborators, not real
configuration persistence or file transfers.

Cancellation is an admission decision: if cancellation wins before commit is
claimed, no callback may write. If the commit claim wins, `cancel` returns false
and the API reports HTTP 409; an already accepted commit can finish. This does
not promise to stop storage after every cancellation attempt. `cancelAll` closes
registration and revokes pending sessions without waiting for accepted commits.

The original three tests cover normal completion, cancellation before a callback
starts, and cancellation while a successful response body is paused. They retain
the original polling delays. Additional tests cover both race winners, shutdown,
delayed registration, expiry, retry responses, relink/paste/Nextcloud paths, and
storage failures. They advance scheduler delays to 1 ms while preserving real
JDK futures and bounded latch ordering. No private method or replacement
cancellation decision is used. The suite also executes the actual JavaScript
asset through public UI entrypoints with a dummy DOM and fetch responses,
checking confirmed cancellation, commit conflicts and late responses.

Run in an isolated, network-disabled environment with the repository mounted
read-only, an existing JDK and Node.js, and an explicit classpath of local jars:

```sh
sh tools/tests/link-manager-cancellation/run.sh "$DEPENDENCY_CLASSPATH" /scratch/build
```

Match the project dependencies when assembling the classpath: OkHttp 4.12.0,
Okio JVM 3.6.0, Kotlin standard library 2.0.21, JUnit 4.13.2, Hamcrest Core 1.3,
JetBrains annotations 13.0, and JSON 20231013. The runner does not resolve or fetch
dependencies and writes compilation outputs only under the supplied build root.
Allow about 25 seconds for the suite. A known dependency substitution in a
particular validation run must be recorded with its result.

The staged RED checkpoint is documented in
[`.github/tdd/10-link-cancellation.md`](../../../.github/tdd/10-link-cancellation.md).

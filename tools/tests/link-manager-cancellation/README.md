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

The three tests cover normal completion, cancellation before a callback starts,
and cancellation while a successful poll response body is paused. The last test
resumes the response only after `cancel(id)` returns, and requires no storage
calls. All tests use a real `ScheduledThreadPoolExecutor`, preserve the delays
requested by `LinkManager`, and coordinate ordering with bounded latches. They do
not invoke private methods or replace cancellation decisions.

Run in an isolated, network-disabled environment with the repository mounted
read-only, an existing JDK, and an explicit classpath of locally available jars:

```sh
sh tools/tests/link-manager-cancellation/run.sh "$DEPENDENCY_CLASSPATH" /scratch/build
```

Match the project dependencies when assembling the classpath: OkHttp 4.12.0,
Okio JVM 3.6.0, Kotlin standard library 2.0.21, JUnit 4.13.2, Hamcrest Core 1.3,
JetBrains annotations 13.0, and JSON 20231013. The runner does not resolve or fetch
dependencies and writes compilation outputs only under the supplied build root.
Allow about 20 seconds for the three tests. A known dependency substitution in a
particular validation run must be recorded with its result.

The staged RED checkpoint is documented in
[`.github/tdd/10-link-cancellation.md`](../../../.github/tdd/10-link-cancellation.md).

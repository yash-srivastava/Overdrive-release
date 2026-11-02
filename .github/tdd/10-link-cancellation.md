# Case 10: LinkManager cancellation — test-only RED checkpoint

Production behavior is unchanged at this checkpoint. The regression deliberately
fails until a separately authorized production change enforces cancellation.

## Contract and test design

For an ordinary new-destination Google Drive link, cancellation that returns while
the successful poll response is still paused must prevent later destination and
credential storage calls. The test resumes the dummy response only after
`cancel(id)` returns and status reports `expired`.

The standalone JUnit suite compiles the repository's original `LinkManager.java`
and `OAuthApps.java` directly. It executes the real `start`, scheduled `poll`, and
`cancel` paths; `created` is reached naturally. No private method is invoked and no
copy of cancellation or persistence decisions is used as proof. The collaborators
replace the engine/configuration, storage, logger, scratch path and HTTP factory.
A real OkHttp application interceptor returns fixed dummy responses and never
proceeds to network transport. A real JDK scheduled executor preserves the
original 5-second delays and `cancel(false)` semantics. Bounded latches control
response ordering. Cancellation itself has a bounded wait so a later fix that
holds a lock over network I/O fails cleanly rather than hanging the suite.

The collaborators observe calls only. This checkpoint does not test encrypted
storage, real configuration persistence, provider accounts, devices or transfers.

## Validation

Baseline source: `2bba1bf55adc8c4004f7df4707b9c936356dcd2f`.
`LinkManager.java` SHA-256:
`205e0e2d537bc05a3b2691786646e6b62059f89094c3b3763633d00a72d27f33`.

| Test | Result |
| --- | --- |
| Normal completion stores credentials and destination | PASS |
| Cancellation before callback prevents polling/storage, including after its original deadline | PASS |
| Cancellation during the response prevents storage | Intended RED: both storage collaborators were called |

The isolated lab mounted the worktree and fixture read-only, disabled networking,
cleared the environment to an explicit allowlist, dropped capabilities, and
restricted writes to scratch tmpfs. Limits: 75 seconds wall time, 768 MiB memory,
1 CPU, 64 processes, 128 MiB scratch, 16 MiB per file, and 32 KiB target output.
The corrected run exited 1 with no limit stop after 18.799 seconds. The container
and all target processes stopped before evidence capture completed.

Classpath: OkHttp 4.12.0, Okio JVM 3.6.0, project Kotlin stdlib 2.0.21, JUnit
4.13.2, Hamcrest Core 1.3, and JetBrains annotations 13.0. **Recorded limitation:**
the already available real JSON 20240303 jar substitutes for the declared test
JSON 20231013 jar. No dependency resolution or download ran inside the test.

Reproduction in the existing isolated lab, with these jars already provisioned
read-only (`$CLASSPATH` names only sandbox-local dependency paths):

```sh
python3 "$LAB/sandbox.py" \
  --src "$WORKTREE" \
  --fixture "$WORKTREE/tools/tests/link-manager-cancellation" \
  --timeout 75 --memory 768m --cpus 1 --pids 64 --disk 128m \
  --file-limit 16777216 --output-limit 32768 \
  -- /bin/sh /src/tools/tests/link-manager-cancellation/run.sh \
  "$CLASSPATH" /scratch/build
```

The complete parent-captured raw log, `checkpoint-red-runtime.raw.log`, is 4,056
bytes; SHA-256:
`2a25e5e8c54f6e1ab17696aa59e876cd9c6bb2da9605e831627a432eed183607`.
The external run metadata records the exact command, source/test/dependency
hashes, safe environment, sandbox configuration and dependency limitation.

An initial setup attempt invoked the standalone script through the `/case`
bind alias, which made its repository-relative path resolve to `/`. Compilation
stopped with file-not-found (exit 2); this was **not** the runtime RED. Its complete
log is preserved separately as `checkpoint-red.raw.log`. The corrected invocation
uses the source path shown above; no production file was changed to address it.

Complete JUnit output from the corrected run:

```text
JUnit version 4.13.2
.aNormalCompletionPersistsDestination INTERCEPT dummy start response; transportUnused=true
aNormalCompletionPersistsDestination SCHEDULE initialMs=5000 delayMs=5000
aNormalCompletionPersistsDestination START_RETURNED pending
aNormalCompletionPersistsDestination CALLBACK_BEGIN
aNormalCompletionPersistsDestination INTERCEPT dummy poll response; transportUnused=true
aNormalCompletionPersistsDestination STORAGE secrets.put cancelReturned=false
aNormalCompletionPersistsDestination STORAGE admin.addDestination cancelReturned=false
aNormalCompletionPersistsDestination CALLBACK_END
aNormalCompletionPersistsDestination CONTROL_PASS normal completion
.bCancelBeforeCallbackPreventsStorage INTERCEPT dummy start response; transportUnused=true
bCancelBeforeCallbackPreventsStorage SCHEDULE initialMs=5000 delayMs=5000
bCancelBeforeCallbackPreventsStorage START_RETURNED pending
bCancelBeforeCallbackPreventsStorage CANCEL_CALL
bCancelBeforeCallbackPreventsStorage CANCEL_RETURNED
bCancelBeforeCallbackPreventsStorage CONTROL_PASS cancellation before callback
.cCancelDuringResponsePreventsStorage INTERCEPT dummy start response; transportUnused=true
cCancelDuringResponsePreventsStorage SCHEDULE initialMs=5000 delayMs=5000
cCancelDuringResponsePreventsStorage START_RETURNED pending
cCancelDuringResponsePreventsStorage CALLBACK_BEGIN
cCancelDuringResponsePreventsStorage INTERCEPT dummy poll response; transportUnused=true
cCancelDuringResponsePreventsStorage POLL_BODY_PAUSED
cCancelDuringResponsePreventsStorage CANCEL_CALL
cCancelDuringResponsePreventsStorage CANCEL_RETURNED
cCancelDuringResponsePreventsStorage STATUS_AFTER_CANCEL expired; resume response
cCancelDuringResponsePreventsStorage POLL_BODY_RESUMED cancelReturned=true
cCancelDuringResponsePreventsStorage STORAGE secrets.put cancelReturned=true
cCancelDuringResponsePreventsStorage STORAGE admin.addDestination cancelReturned=true
cCancelDuringResponsePreventsStorage CALLBACK_END
E
Time: 15.978
There was 1 failure:
1) cCancelDuringResponsePreventsStorage(com.overdrive.app.safekeep.LinkManagerCancellationTest)
java.lang.AssertionError: cancel(id) returned before the response resumed; storage must remain untouched expected:<[]> but was:<[secrets.put, admin.addDestination]>
	at org.junit.Assert.fail(Assert.java:89)
	at org.junit.Assert.failNotEquals(Assert.java:835)
	at org.junit.Assert.assertEquals(Assert.java:120)
	at com.overdrive.app.safekeep.LinkManagerCancellationTest.cCancelDuringResponsePreventsStorage(LinkManagerCancellationTest.java:150)

FAILURES!!!
Tests run: 3,  Failures: 1
```

## Design questions before a production change

A cancelled future alone does not invalidate the session object retained by a
running poll. Any later change needs a cancellation/commit decision that also
covers the shared `created` path, without holding a lock over HTTP operations.
A cancellation flag checked only once before storage leaves a check/write race
if a stronger guarantee is required for cancellation concurrent with commit.
This suite currently establishes the response-before-commit ordering only.

Review lock order before serializing commit with cancellation. `SafeKeep.stop`
and `converge` hold the lifecycle monitor while `Engine.stop` calls
`links.cancelAll`. Conversely, `created` invokes `SafeKeepAdmin.addDestination`,
whose mutation can call `SafeKeep.converge` after releasing its config/write
locks. Holding a new session/commit lock across that call while `cancelAll`
waits for the same lock under the lifecycle monitor could invert lock order.
`SafeKeepSecrets.put` is also synchronized and performs configuration I/O.
Define commit/cancel ordering and these nested-lock relationships before adding
a broad lock. Consider `cancelAll`, paste completion, relink and scheduling races
when reviewing the shared path; those flows are not claimed as runtime-tested by
this three-test fixture.

No production fix, push or pull request is included in this checkpoint.

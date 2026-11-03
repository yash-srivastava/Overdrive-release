# Cloudflared stop callback behavior fixture

This local fixture extracts `CloudflaredController.stop` and `DaemonsViewModel.stopDaemon` verbatim from the checkout and compiles them against deterministic callback, URL-state, status and clock seams. It does not create Android contexts, launch ADB, kill processes or contact a tunnel. Input source hashes are printed.

It verifies immediate and delayed stop success/failure (four scenarios): pending/log callbacks remain stopping; a verified success clears the URL and reports stopped; an error forwards the reason, reports ERROR, retains the URL and schedules the existing 1,000 ms refresh. The clock is advanced explicitly and only the refresh invocation is checked; actual platform status probing is outside the fixture. Two deliberately incorrect controller variants (clear URL on failure, report STOPPED on failure) must be rejected by the same oracle.

Run within the project's isolated validation environment using its already provisioned toolchain. Pass explicit classpaths, without wildcard runtime selection:

```sh
python3 tools/tests/cloudflared-stop-callback/run.py \
  --source "$CHECKOUT" --work "$CASE_SCRATCH/callbacks" \
  --java-home "$JAVA_TOOLCHAIN" \
  --compiler-classpath "$KOTLIN_COMPILER_CP" --stdlib "$KOTLIN_RUNTIME_CP"
```

The recorded run uses JDK 21.0.12.1+1, Kotlin compiler 2.0.21 with its reflect/daemon, Trove 1.0.20200330, compiler coroutines 1.6.4 and annotations 13.0 dependencies, and exact application Kotlin stdlib 2.1.20. All were available locally; nothing is fetched. `--work` must not already exist. Template adapters are deliberately local to this fixture and are not production code.

This source-excerpt result complements the actual app JUnit contract and whole-project Gradle checks; it does not establish installed Android app, LiveData scheduling, ADB transport or fleet acceptance.

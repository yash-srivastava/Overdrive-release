# OpenH264 binary-input integrity regression

This standalone Python/Gradle harness executes the production
`downloadOpenH264` task extracted verbatim from `app/build.gradle.kts`.
It records the source and task hashes and fails visibly if the extraction
adapter no longer matches the task layout. The harness does not reimplement
the download, decompression, cache check or publication decisions.

Use an existing local Gradle 8.13 installation and a disposable OS sandbox.
The source and tools must be read-only, the environment must be an explicit
allowlist, writes must be confined to scratch, and external networking must
be disabled. The only server is a task-local loopback proxy. It never forwards
requests or opens CONNECT tunnels. `--offline` prevents Gradle dependency
resolution; it does not disable networking performed by the task itself.

Inside that sandbox, run:

```sh
python3 /src/tools/tests/openh264-integrity/run.py \
  --source /src --scratch /scratch \
  --gradle /tools/gradle-8.13/bin/gradle \
  --trusted-library /fixtures/verified-openh264-2.6.0-arm64.so
```

Adjust only the tool/fixture mount paths. Use a fresh scratch directory for
each invocation. The positive-control file is optional, but omitting it skips
that test. Its SHA-256 must be
`4d9bc54d2d38e53eb7bd551ec61acb8ad8320d8b957bda751cfdbdcbfabc3b07`,
the independently verified unstripped Cisco 2.6.0 Android ARM64 binary. The
test copies and hashes this file; it never loads or executes it. Do not supply
credentials, signing material, production accounts or private configuration.

The regression checks:

- Two unrelated, inert local bzip2 archives must not become the native input.
- A corrupt existing native-input file must not satisfy the task.
- A verified existing library must permit an unchanged offline build input.
- An invalid archive must fail the task without publishing a library.

Headers are preseeded with unused inert files so this binary-input test never
contacts GitHub. Task extraction and plain Gradle execution intentionally omit
the Android plugin, native compiler and application packaging. Separate
baseline packaging evidence establishes the input's build graph connection.

An HTTPS download cannot complete through this fixture's proxy. A future
HTTPS-only task can therefore satisfy the fresh-download rejection controls
without demonstrating checksum handling over successful TLS. Add a dedicated
trusted local TLS fixture when implementing that behavior; do not describe
this test alone as full transport or digest-validation coverage.

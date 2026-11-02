# OpenH264 binary-input integrity regression

This Python/Gradle harness first configures the complete production Android
build script with the repository's actual Android and Kotlin plugins. Only
after that succeeds does it execute the production `downloadOpenH264` task
extracted verbatim, including its imports, from `app/build.gradle.kts`.
It records the source and task hashes and fails visibly if the extraction
adapter no longer matches the task layout. The harness does not reimplement
the download, decompression, cache check or publication decisions.

Use an existing local Gradle 8.13 installation and a disposable OS sandbox.
The source and tools must be read-only, the environment must be an explicit
allowlist, writes must be confined to scratch, and external networking must
be disabled. A task-local loopback proxy serves HTTP controls and forwards only
the exact Cisco host's CONNECT requests to the fixture-owned loopback TLS
listener. It never resolves or connects to an external host. `--offline` prevents Gradle dependency
resolution; it does not disable networking performed by the task itself.

Inside that sandbox, run:

```sh
python3 /src/tools/tests/openh264-integrity/run.py \
  --source /src --scratch /scratch \
  --gradle /tools/gradle-8.13/bin/gradle \
  --android-sdk /tools/android-sdk --module-cache /fixtures/gradle-cache \
  --trusted-library /fixtures/verified-openh264-2.6.0-arm64.so \
  --trusted-archive /fixtures/verified-openh264-2.6.0-arm64.so.bz2
```

Adjust only the tool/fixture mount paths. Use a fresh scratch directory for
each invocation. The positive-control file is optional, but omitting it skips
that test. Its SHA-256 must be
`4d9bc54d2d38e53eb7bd551ec61acb8ad8320d8b957bda751cfdbdcbfabc3b07`,
the independently verified unstripped Cisco 2.6.0 Android ARM64 binary. The
test copies and hashes this file; it never loads or executes it. Do not supply
credentials, signing material, production accounts or private configuration.

The compressed positive-control input must have SHA-256
`c702d68c9c8db492a43c1d73a497cea5f31ae5d23e330dcb13bd28cab1dbbf2a`.
Omitting `--trusted-archive` skips the four additional TLS cases. The harness
uses existing local OpenSSL and JDK keytool to create scratch-only ephemeral
keys and a CA truststore. Java uses ordinary certificate and hostname checks;
no insecure production property, trust manager or hostname-verifier override
is introduced.

The regression checks:

- Two unrelated, inert local bzip2 archives must not become the native input.
- A corrupt existing native-input file must not satisfy the task.
- A verified existing library must permit an unchanged offline build input.
- An invalid archive must fail the task without publishing a library.
- A verified archive must download over real TLS and publish the expected bytes.
- A same-size archive with the wrong digest must fail after successful TLS.
- A truncated TLS response must fail without publishing a library.
- An HTTPS redirect to HTTP must fail without following the redirect.
- A corrupt cache with the correct size must fail its digest check before networking.
- Download staging must be removed after success and failure.

The mandatory first phase uses `configure-android-project.py` to copy eight
allowlisted public configuration inputs and run `:app:help` with the actual
repository plugins, completely offline. It copies no local properties, OAuth
configuration or signing material. The complete app script remains unchanged.
This catches plugin-generated Kotlin DSL names that can change script name
resolution; the extracted-task fixture alone cannot establish compatibility.

In the subsequent task-only phase, headers are preseeded with unused inert
files so this binary-input test never contacts GitHub. That phase omits the
Android plugin, native compiler and packaging. Neither phase compiles the app
or executes native tasks. Separate baseline packaging evidence establishes
the input's build graph connection.

The positive TLS control and same-size wrong-digest control distinguish digest
validation from a transport-only rejection. The fixture's certificate is valid
for `ciscobinary.openh264.org`; only its ephemeral CA is added to the test JVM's
truststore. That truststore and its dummy password are scoped to the fixture.
No production TLS configuration is changed.

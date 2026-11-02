# OpenH264 binary-input integrity

This change derives its guarantees from the V58 security audit
and bounded baseline validation, with source commit
`2bba1bf55adc8c4004f7df4707b9c936356dcd2f`. No separate plan document was used.
The preserved RED checkpoint is `883d0cdeabe86b02eb52655ff090ce7d2102a878`.
It contains tests and this report before the production change.

As a builder, I need the native codec input to be verified before it can satisfy
the download task. A valid cached copy should continue to work offline, and a
failed or invalid download must stop the build.

## Executed regression

The standalone harness executes the production `downloadOpenH264` task,
extracted verbatim into a minimal Gradle project. At the reviewed baseline,
the original task occupied lines 23–70 of `app/build.gradle.kts`.
It logs the source and task digests. It does not copy the decision logic into
Python or use source-text assertions as the behavioral proof.

The OS sandbox has no external network. A loopback proxy supplies deterministic
inert data and forwards only the exact Cisco host's CONNECT requests to a
fixture-owned loopback TLS listener. Source and
toolchains are read-only, the environment is an explicit allowlist, and only
scratch is writable. Bounds are two CPUs, 2 GiB memory, 192 processes, 1 GiB
scratch, a 256 MiB file limit, 480 seconds and 120,000 output bytes.

The inner command actually executed was:

```sh
/usr/bin/python3 /src/tools/tests/openh264-integrity/run.py \
  --source /src --scratch /scratch \
  --gradle /gradle-wrapper/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle \
  --trusted-library /case/trusted-library.so \
  --trusted-archive /case/trusted-archive.bz2
```

The task uses Gradle 8.13 with `--offline --no-daemon --max-workers=1`; it has no
third-party Gradle plugin dependency. No dependency was fetched. Unused inert
header files prevent unrelated GitHub requests.

## RED evidence

| Guarantee | Test | Baseline result |
| --- | --- | --- |
| Unverified network bytes cannot become the native input | `test_01_rejects_unknown_download` | Intended RED: original task returns zero and publishes the dummy bytes |
| A different unverified archive is also rejected | `test_02_rejects_second_unknown_download` | Intended RED: second unrelated dummy archive is also published |
| A corrupt existing library cannot satisfy the task | `test_03_rejects_corrupt_cached_library` | Intended RED: original task succeeds with zero requests and retains the corrupt input |
| A verified existing library remains unchanged and works offline | `test_04_preserves_verified_existing_library` | PASS: zero requests; exact bytes preserved |
| Decompression failure fails the task without publishing a library | `test_05_rejects_invalid_archive` | Intended RED: original task prints `Invalid bz2 file` and reports build success |

All five baseline production task invocations execute. The suite exits 1 with four
assertion failures and one passing positive control. The failures are the
intended integrity and fail-closed violations, not missing dependencies,
configuration errors or compilation failures.

The positive-control input is the independently verified, unstripped Cisco
2.6.0 Android ARM64 library, SHA-256
`4d9bc54d2d38e53eb7bd551ec61acb8ad8320d8b957bda751cfdbdcbfabc3b07`.
Its bytes are copied and hashed only; no native library is loaded or executed.
The file is supplied externally and is not committed to the repository.

The original five-case run completed in 34.821 seconds including container
cleanup. Its retained raw-log SHA-256 is
`46f49a95d657ab60c575a95b8f0b5a844a900a8431be4823d9aeaa39cbbf4389`.

## Correction and expanded validation

The task keeps OpenH264 2.6.0 and the same Cisco ARM64 binary. It uses HTTPS
without redirects, checks the compressed input and unpacked library against
trusted SHA-256 pins and exact sizes, and checks existing cache files before
allowing them to satisfy the task. Download bytes are bounded. Temporary files
are unique, remain on the destination filesystem, and are removed after success
or failure. The final library is published with `ATOMIC_MOVE` only after both
verification steps; unsupported atomic movement fails the task. Network,
integrity, decompression, publication and cleanup failures propagate to Gradle.

The uncompressed pin above came from Cisco's official HTTPS binary without
redirects. The compressed input's SHA-256 is
`c702d68c9c8db492a43c1d73a497cea5f31ae5d23e330dcb13bd28cab1dbbf2a`.
Separately, NDK 26.1 `llvm-strip --strip-unneeded` on the uncompressed binary
produces SHA-256
`933977df6c595f7ca95d56e5189e52ea9b19ff75d8d7421292a43e97e1d480f2`,
matching the library in the signature-verified V58 APK. These are static byte
comparisons; no native library was executed.

The expanded suite also checks:

| Guarantee | Test |
| --- | --- |
| A verified archive downloads over real TLS and publishes the expected library | `test_06_accepts_verified_download_over_real_tls` |
| A same-size, wrong-digest archive fails after a successful TLS response | `test_07_rejects_same_size_wrong_digest_over_real_tls` |
| A partial TLS body fails without a published library | `test_08_rejects_truncated_tls_response` |
| An HTTPS-to-HTTP redirect fails without being followed | `test_09_rejects_redirect_without_following_it` |
| A same-size corrupt cache fails its digest check before networking | `test_10_rejects_same_size_wrong_digest_in_cache` |

The harness creates an ephemeral CA and a certificate valid for
`ciscobinary.openh264.org`, then gives the test JVM a normal CA truststore.
Certificate and hostname validation remain enabled. No insecure production
override, custom trust manager or hostname verifier is introduced. It also
asserts staging cleanup after each successful or failed invocation.

GREEN result: all 10 tests pass, with no skips. The isolated run exits 0, has no resource-limit stop, and completes in 58.422 seconds including cleanup. The raw log contains both successful TLS acceptance of the verified archive and explicit SHA-256 rejection after successful TLS. Its SHA-256 is `52c360676158131da3c203a60d2a65417aadcbaeaf0c524822a2526843d182de`.

## Coverage and limits

This checkpoint covers the original task's fresh-input, existing-cache and
decompression-error behavior. No numerical coverage measurement is claimed.
No application APK is built or installed by this regression, and no release
compromise or native-code execution is demonstrated. Separate baseline evidence
establishes the original task's packaging connection; that packaging test was
not repeated here.

The successful TLS and wrong-digest controls distinguish digest enforcement
from transport failure. Header retrieval behavior is unchanged and outside
this binary-input correction. This is not a hermetic-build guarantee against
a malicious actor who can continually rewrite local build files. No complete
Android application build or fleet acceptance is claimed by this task fixture.

A corrupt preexisting cache is rejected rather than silently repaired. Remove
the rejected `libopenh264.so` and rebuild to acquire and verify a fresh copy.

Preserve this RED evidence if the test and future fix checkpoints are squashed.
No push, merge, deployment or release publication is part of these checkpoints.

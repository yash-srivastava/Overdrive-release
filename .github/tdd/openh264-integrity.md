# OpenH264 binary-input integrity: RED checkpoint

This test-only checkpoint derives its guarantees from the V58 security audit
and bounded baseline validation, with source commit
`2bba1bf55adc8c4004f7df4707b9c936356dcd2f`. No separate plan document was used.
Production code is unchanged. Implementation and GREEN validation remain gated
on completion of the outstanding audit validation work.

As a builder, I need the native codec input to be verified before it can satisfy
the download task. A valid cached copy should continue to work offline, and a
failed or invalid download must stop the build.

## Executed regression

The standalone harness executes the original `downloadOpenH264` task, extracted
verbatim from lines 23–70 of `app/build.gradle.kts`, in a minimal Gradle project.
It logs the source and task digests. It does not copy the decision logic into
Python or use source-text assertions as the behavioral proof.

The OS sandbox has no external network; a loopback proxy supplies deterministic
inert data, never forwards requests, and rejects CONNECT tunnels. Source and
toolchains are read-only, the environment is an explicit allowlist, and only
scratch is writable. Bounds are two CPUs, 2 GiB memory, 192 processes, 1 GiB
scratch, a 256 MiB file limit, 480 seconds and 120,000 output bytes.

The inner command actually executed was:

```sh
/usr/bin/python3 /src/tools/tests/openh264-integrity/run.py \
  --source /src --scratch /scratch \
  --gradle /gradle-wrapper/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle \
  --trusted-library /case/trusted-library.so
```

The task uses Gradle 8.13 with `--offline --no-daemon --max-workers=1`; it has no
third-party Gradle plugin dependency. No dependency was fetched. Unused inert
header files prevent unrelated GitHub requests.

## Guarantees and baseline results

| Guarantee | Test | Baseline result |
| --- | --- | --- |
| Unverified network bytes cannot become the native input | `test_01_rejects_unknown_download` | Intended RED: original task returns zero and publishes the dummy bytes |
| A different unverified archive is also rejected | `test_02_rejects_second_unknown_download` | Intended RED: second unrelated dummy archive is also published |
| A corrupt existing library cannot satisfy the task | `test_03_rejects_corrupt_cached_library` | Intended RED: original task succeeds with zero requests and retains the corrupt input |
| A verified existing library remains unchanged and works offline | `test_04_preserves_verified_existing_library` | PASS: zero requests; exact bytes preserved |
| Decompression failure fails the task without publishing a library | `test_05_rejects_invalid_archive` | Intended RED: original task prints `Invalid bz2 file` and reports build success |

All five production task invocations execute. The suite exits 1 with four
assertion failures and one passing positive control. The failures are the
intended integrity and fail-closed violations, not missing dependencies,
configuration errors or compilation failures.

The positive-control input is the independently verified, unstripped Cisco
2.6.0 Android ARM64 library, SHA-256
`4d9bc54d2d38e53eb7bd551ec61acb8ad8320d8b957bda751cfdbdcbfabc3b07`.
Its bytes are copied and hashed only; no native library is loaded or executed.
The file is supplied externally and is not committed to the repository.

## Coverage and limits

This checkpoint covers the original task's fresh-input, existing-cache and
decompression-error behavior. No numerical coverage measurement is claimed.
No application APK is built or installed by this regression, and no release
compromise or native-code execution is demonstrated. Separate baseline evidence
establishes the original task's packaging connection; that packaging test was
not repeated here.

The proxy cannot complete HTTPS. A future HTTPS-only implementation can reject
the fresh-input controls because transport is unavailable. These tests alone
would therefore not prove digest validation over successful TLS. Add a trusted
local TLS fixture and legitimate fresh-download controls during the future
implementation stage, together with atomic-publication and cache-validation
checks. Do not treat this RED checkpoint as post-fix or fleet acceptance.

Preserve this RED evidence if the test and future fix checkpoints are squashed.
No production fix, GREEN checkpoint, push or pull request is part of this commit.

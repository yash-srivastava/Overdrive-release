# HTTP body initial capacity

## Defect and correction

The unchanged V58 handler reserved the complete untrusted Content-Length in its
StringBuilder before any body bytes or authentication decision. Retained,
independently reviewed baseline execution showed eager reservation for absent
1 MiB and 2 MiB bodies. No Android outage or fleet impact was demonstrated.

Reserve the existing CHUNK capacity instead. This is min(Content-Length, 4096)
inside the positive-length branch, so the initial accumulator capacity is bounded
by 4096 characters. Normal append growth follows received characters. Body caps,
UTF-8 accounting, routing and authentication ordering are unchanged.

## Evidence and test limitation

The earlier resource-check preparation was interrupted by agent-service filtering.
Its log also had an unrelated empty-body fixture error; it is not presented as a
clean automated RED checkpoint. That resource experiment was not retried. The
independent original baseline remains the pre-change allocation evidence.

A separate functional compatibility harness compiles the actual handler and its
helpers verbatim, plus original middleware/response/security classes. Dummy
socket/router/auth/hardware collaborators are isolated. Empty bodies preserve the
existing null router argument. The harness performs no allocation measurements,
load test, concurrent requests or availability experiment.

Actual isolated command:

```sh
python3 <lab>/sandbox.py --src <worktree> \
  --fixture <worktree>/tools/tests/http-body-compatibility \
  --timeout 60 --memory 768m --disk 128m --pids 64 --cpus 1 \
  --output-limit 65536 -- \
  /bin/sh /src/tools/tests/http-body-compatibility/run.sh \
  /opt/jdk21 /lab/deps/json-20240303.jar /scratch/body-compatibility
```

Result: 10/10 functional checks pass, exit 0, no timeout/output stop, 2.570s.
Small ASCII, multibyte UTF-8, 64 KiB chunked body, empty/null behavior,
unauthenticated routing denial and five endpoint body caps pass.
Raw-log SHA256: `5d51ff08d949dc3c542c883855a52041e4a35b2b816eb72a2f270e22e07c0ce7`.

The initial-capacity guarantee follows directly from the bounded constructor
argument and is not a new measured Android allocation result. Whole-app coverage,
full Android builds, vehicle firmware and fleet acceptance are not established.
The fixture uses existing JSON20240303 because project test JSON20231013 is not
available locally; no dependencies are fetched by the test.

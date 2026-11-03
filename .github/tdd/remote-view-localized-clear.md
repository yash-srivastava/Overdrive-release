# Remote developer view localized clearing contract

The selected baseline source-contract method fails because the visibility callback now passes t('remote_dev.resume_tab', English fallback) to clearFrame instead of a direct English literal. Its strict-JWT, physical-PIN, lock-metadata and canvas-clear assertions are retained. Only this stale assertion changes; the independent cache-buster repair belongs to another PR.

Recorded stage4 reproduces the original selected method failure, then passes the repaired method. The method and original read helper are copied verbatim into a dependency-free JUnit wrapper for this focused execution; this is not a claim that the entire RemoteDevViewAssetTest class passed (it still has unrelated cache-token failures). Full canonical class coverage remains a parent gate.

`node tools/tests/remote-view-localized-clear/run.cjs` executes the complete shipped script with dummy DOM, authentication, HTTP, WebSocket and image-decoder collaborators. Its 46 assertions pass for translated labels and English fallback. It checks initial no-session state, explicit POST start, capability in the subprotocol rather than URL, displayed-frame clearing on LOCKED metadata, stream close/handler detach on hidden tab, translated resume notice, rejection/revocation of a decode completing after hiding, and stream reopening with the same dummy capability on return. There is no real developer-view service, device or privileged action.

Execution: network disabled, read-only source/tools, 1CPU, 512MiB memory, 128MiB scratch, 64PIDs, 90s wall-clock limit. Exact script/test hashes are retained in stage4-result.json. This is a source/UI-fixture check, not installed-device or fleet acceptance. Run both required CONTRIBUTING wrapper commands before submission.

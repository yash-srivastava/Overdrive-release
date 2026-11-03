# Communicate localized lifecycle contract

The public baseline's ten-method RemoteCommunicationAssetContractTest fails its resource teardown method because timeout/inactivity stop reasons now call the existing tt(key, English-fallback) helper. The original test requires the older direct English argument. Production timer and stopTalk resource ownership are unchanged.

The repaired source contract requires the translated call and its English fallback, retaining navigation, timing constants, microphone, socket, graph/context/track cleanup and polling assertions. A complete-script Node fixture exercises both translation results and missing-key fallback at the 30-second session limit and 3-second audio inactivity limit. It also invokes actual visibilitychange/pagehide/beforeunload callbacks. Every stop must close the socket, disconnect three graph nodes, close AudioContext, stop both fake tracks, clear the ticker, invalidate the generation and clear owned references. Active-time and no-longer-live controls retain resources appropriately.

Recorded stage3: original exact baseline test class fails one of ten methods; repaired class passes ten methods; Node passes 134 behavior assertions. The original class is taken from pinned main ade874fc; production source SHA values match the final focused run. Execution is network-disabled, read-only source/tools, 1CPU/512MiB/128MiB scratch/64PIDs/90seconds. Dummy audio resources only, no actual microphone/network/vehicle. English PR draft documents full wrapper checks as still pending parent validation.

Run `node tools/tests/communicate-localized-lifecycle/run.cjs` and the JUnit class, then the CONTRIBUTING commands `./gradlew assembleDebug` and `./gradlew test`. Source-fixture checks do not establish installed-device acceptance.

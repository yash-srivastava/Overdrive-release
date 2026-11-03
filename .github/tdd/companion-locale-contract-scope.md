# Separate app and companion locale ownership

## Confirmed cause and boundary

Public main `ade874fc24ba609c42ea2c93dc63e81d2c680a15` includes `:app` and
`:dilink-probe`, not a launcher module. Its Czech/Hebrew app tests read
unversioned `launcher/` paths and fail before completing their app assertions.
No companion source revision was supplied or fabricated.

Keep every app assertion, add app locale/RTL manifest checks and retain every
original launcher condition in the explicit standalone
`tools/tests/companion-launcher-locales/LauncherLocaleContract.java` command.
The command requires actual tracked inputs in a clean Git checkout and records
its revision. Missing arguments, checkout or inputs fail; no check is silently
skipped. CONTRIBUTING and the tool README document release ownership and state
that public app tests alone cannot establish companion acceptance.

## Focused observations

The actual Czech/Hebrew JUnit classes were compiled using JDK 21.0.12.1+1,
JUnit 4.13.2, Hamcrest Core 1.3 and exact JSON 20231013. A fixture compiled the
exact supported-locale constants and four pure resolution methods extracted
from `LocaleManager`; it did not replace or model their logic. This is a
**source-excerpt test**, not actual Android locale persistence acceptance.

- RED: five tests, three missing-companion failures; exit 1, 2.572 s.
- After the scope correction: five tests, four pass and one **remaining failure**
  at the retained app `Sync::class` assertion; exit 1, 4.120 s including CLI checks.
- The explicit companion tool compiles. Three negative CLI controls (missing
  argument, unavailable module, public app root instead of a launcher module)
  fail clearly as intended. No positive companion acceptance is claimed.

The remaining app failure is real and was previously hidden by the missing
companion reads: the app's Hebrew alias task uses `Copy::class`, whereas the
existing test expects `Sync::class`. It is left intact for an independently
validated resource-generation correction. **This branch is not a fully green
test result by itself.**

Both runs used a network-disabled disposable OS sandbox with source/toolchain
read-only, scratch-only writes, explicit environment, 512 MiB memory, one CPU,
64 processes, 128 MiB scratch and a 60-second wall limit. No real app state or
companion sources were created.

Captured RED log SHA-256:
`f5138f6d437993f266258067e53cefb3e8db7cf7332b5cf0919bba2039d51a54`.
Scoped observation/CLI log SHA-256:
`44d61307f61cdd54916fc29d4da04f81ca8a724825a49c9e232fed639d06fbc1`.
Full LocaleManager source SHA-256:
`56ecf238faf7cfdc18220cc987af07f3dc918f3e125b409baae8f7bf19029551`.
Compiled pure excerpt SHA-256:
`07ba5e93afa31a65aecb87e6851f67f2557118316c7f0816ad6a40af28d824f6`.

## Required follow-up checks

Run the actual full app checks before publication:

```sh
./gradlew assembleDebug
./gradlew test
```

The parent validation must compile actual `LocaleManager`, not this focused
excerpt. Record the remaining independent resource-sync failure accurately.
For a release containing the companion, also run its explicit command against
the actual clean versioned module and its own build/device checks. Those
inputs remain unavailable here, so companion acceptance remains unvalidated.

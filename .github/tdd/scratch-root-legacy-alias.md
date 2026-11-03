# Reuse the canonical legacy scratch-root constant

## Validated cause

The completeness gate reports exactly `DaemonStorage.LEGACY_BASE` because the
literal `/data/local/tmp` is declared twice. This declaration is a relocation
sentinel and the default base; it is not itself a file access. `ScratchRoot`
already owns the identical public compile-time string constant. The fix uses
that constant directly, without broadening the scanner allowlist.

## Change and compatibility

`LEGACY_BASE = ScratchRoot.LEGACY_DIR` remains a Java compile-time constant with
the exact same value. It does not initialize `ScratchRoot`, change relocation
selection, run migrations, or alter paths or shell commands. Added tests pin
both existing relocation states and verify legacy identity, relocated path
components, sibling-prefix and unrelated-path controls. Reflection in these
tests only pins the existing private cached decision; it does not bypass the
production storage policy or require JVM module overrides.

## Validation

A bounded, network-disabled JDK 21 sandbox compiled the production classes
verbatim with the Android SDK 36 compile stubs, exact declared JSON 20231013,
JUnit 4.13.2 and Hamcrest 1.3. Two fixture-only external helpers return an empty
configuration and a non-DiLink-5 hardware signal; tests pin the cached state, so
neither helper is used to establish platform behavior.

RED on public V58 `2bba1bf`: 12 tests, exactly one failure in the unchanged
completeness scanner, naming the duplicated sentinel declaration. GREEN after
the one-line alias: the same 12 tests pass. The unchanged eight alternate-mode
checks and three added consumer checks accompany the full scanner. Resources:
512 MiB, one CPU, 64 processes, 128 MiB scratch, 90-second wall limit; no limit
stop. Parent-retained logs: baseline-runtime stages 1 and 2.

Required whole-project checks are pending and must be recorded before PR
submission. No compatible vehicle or firmware acceptance is claimed.

## Separate policy issue

`DaemonStorage` still has an automatic hardware/config relocation policy while
`ScratchRoot` uses explicit selection. This change deliberately preserves both
existing policies; consolidating them requires a separately validated migration
and compatibility plan. A passing scanner alone does not establish that those
policies agree on every head unit.

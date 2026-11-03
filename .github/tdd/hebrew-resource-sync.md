# Remove stale generated Hebrew alias resources

## Confirmed cause

The app declares `src/main/res/values-he` as the source of a generated
`values-iw` overlay. `sourceSets.main.res` includes the generated root, and the
pre-build hooks depend on the alias task. The task's destination is the dedicated
`build/generated/res/iwAlias/values-iw` child; no other writer was found.

Public main `ade874fc24ba609c42ea2c93dc63e81d2c680a15` registers this task as
`Copy`, which does not remove a generated file after its Hebrew input is removed
or renamed. This can retain obsolete translations or duplicate resource names
in an incremental build. The existing app Hebrew contract expects `Sync`, but
missing companion source reads previously hid that assertion failure.

## Minimal correction

Register `syncHebrewIwResources` as `Sync` instead of `Copy`. Keep the task name,
source, dedicated destination, source-set wiring and dependencies unchanged.
Synchronization affects only `values-iw`, not sibling generated output. No
application runtime code or translation content changes.

## Behavioral red and green

A disposable Gradle 8.13 project used the **exact alias task and configureEach
fragment extracted from the real app script**, with all nine actual Hebrew XML
files. Each independent sandbox run started with fresh project/cache storage.
The fixture compared every output filename and SHA-256 with its input after:

1. Initial generation.
2. A content edit to an existing XML file.
3. Removal of an existing XML file.
4. Rename of another existing XML file.

It also parsed the output XML to detect duplicated resource names and checked
that a sibling generated-output marker outside `values-iw` survived.

RED (`Copy`): initial/edit controls pass. Removal retains
`dashboard_modern_resources.xml`; rename also retains
`recording_modern_strings.xml`, causing **31 duplicate string resource names**.
The original nine input files have zero duplicate names. The final mirror
assertion fails as intended; sandbox exit 1, 31.438 s, no limit stop.

GREEN (`Sync`), with the identical fixture: all four file/hash comparisons pass,
zero stale files or duplicate resource names, sibling ownership preserved;
sandbox exit 0, 28.673 s, no limit stop.

Both used the declared Gradle 8.13 distribution and JDK 21.0.12.1+1, with
`--offline --no-daemon --console=plain --max-workers=1`. Execution was confined to
a network-disabled OS sandbox, read-only source/toolchain/fixture mounts,
explicit environment, scratch-only writes, 2 GiB memory, two CPUs, 256 processes,
1 GiB scratch and a 600-second wall limit.

Fixture SHA-256 (identical red/green):
`73cb3027417414fc014d826bf9acf40b2a787c7bf6c1e29e630ab5c148d57d8f`.
RED log SHA-256:
`6dfc468edddecfb75d196ae81aacb6ca7fd0b8eff192a2f58846d5a88ea4bb00`.
GREEN log SHA-256:
`7fe29d97040d15972792f5c12b9045116f6aef2a300cde695c5a067e876f9c73`.
Original app script SHA-256:
`11695a5bce48056bd642d0ba2f87a4c5c5b07fa1541119236cf66e03cc35c24a`.
Corrected app script SHA-256:
`6f78d484c3a7efbdb934a311adc1b8d426626b1b578296dd6fa44242de716ed3`.

## Actual plugin and variant acceptance still required

The fragment check proves incremental file behavior. It does **not** replace
compiling the complete app Kotlin DSL with the real Android plugin. Before PR
publication run and record the actual configured checkout commands:

```sh
./gradlew :app:help
./gradlew :app:mergeDebugResources :app:mergeReleaseResources :app:mergeBraveheartResources
./gradlew assembleDebug
./gradlew test
```

The alias uses `sourceSets.main`, shared by debug/release/braveheart. Actual
plugin/variant and canonical build/test results are pending parent validation.
The independently scoped companion-locale correction is required to reach all
app assertions; this one-line fix alone does not supply absent companion files
or establish a fully green project. No Android device or vehicle behavior is
claimed by a build-resource synchronization test.

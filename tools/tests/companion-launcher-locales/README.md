# Companion launcher locale contracts

The public Gradle build includes `:app` and `:dilink-probe`. It does not include
or distribute the companion launcher module. App tests cover the app's own
locale resolver, native resources, web/server catalogs, RTL configuration and
resource aliases. They cannot certify a separate application's resources.

This tool retains every launcher assertion previously embedded in
`CzechLocaleSupportTest` and `HebrewLocaleSupportTest`. Nothing is conditionally
skipped. Run it against a **real, clean, versioned companion module** when
validating a release containing that companion:

```sh
java tools/tests/companion-launcher-locales/LauncherLocaleContract.java \
  /path/to/companion-checkout/launcher
```

Use JDK 17 or newer and Git. Pass the module containing `build.gradle.kts`,
`src/main/java/com/overdrive/launcher/i18n/LocaleController.kt` and the actual
resource directories; if the companion repository root is the module, pass
that root. The tool checks required inputs are tracked, requires a clean
module, prints the exact Git revision and fails on missing inputs or a failed
assertion. It uses no network service and never creates companion files.

The three contract groups cover Czech selection/label, Hebrew English-key
parity/labels/placeholders, and canonical Hebrew/legacy `iw` alias/picker/build
wiring. These source contracts do not replace the companion's own build,
resource-generation and device tests.

No companion source revision is bundled in this public project. Until a real
checkout passes this command, **companion acceptance remains unvalidated** even
when `./gradlew test` passes for the public app. A combined app/launcher release
owner must record the app revision, companion revision, this command's result
and the companion's own checks. Do not create a dummy launcher module or report
a missing-checkout diagnostic as a passing companion run.

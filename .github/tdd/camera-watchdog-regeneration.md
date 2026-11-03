# Camera metadata refresh follows watchdog regeneration

The old source-text contract demanded a `pm path` lookup before every daemon
restart and derived a native-library path from the APK directory. Production
instead regenerates its watchdog after package replacement. Neither a dynamic
lookup nor that native-path derivation exists in the current implementation.

The release `MY_PACKAGE_REPLACED` receiver launches a new MainActivity with
`EXTRA_POST_UPDATE`. Startup requests `UpdateLifecycle.hardResetDaemons` before
its completion callback initializes daemon startup. The reset requests a camera
disable sentinel, script removal and process-family teardown. Fresh launchers
read `ApplicationInfo.sourceDir`; their callers supply the matching
`ApplicationInfo.nativeLibraryDir`. Debug installs intentionally leave relaunch
to the IDE. The application must not guess native paths from APK locations.

The mandatory JUnit contract compares two actual generator outputs using
separate APK/native-directory pairs, requires the supplied metadata and vendor
library paths, and rejects old metadata in the regenerated script. Its four
methods also retain BMM classpath and lifecycle guards. It invokes no platform
shell, chmod, or subprocess and contains no OS-dependent skip. Windows behavior
is source-reviewed for portability; an actual Windows runner is not available.

The actual POSIX execution scenario is maintained explicitly under
`tools/tests/camera-watchdog-regeneration`. Its Java exporter invokes the real
compiled generator using the actual Gradle Test runtime classpath, pins/restores
ScratchRoot in its own JVM, and remaps both known production roots into the
assigned disposable directory. A separate Linux tool runs both generations with
a dummy `app_process`, retaining the old APK on disk, and checks paired metadata,
fixed timeouts, isolated process-group cleanup and ownership-file removal. This
tool is optional and explicit; default JUnit tests never execute it.

The earlier exact commit `4407e5e0` did execute the same source generator's shell
scenario successfully under Linux during canonical tests, but its mandatory
`/bin/sh` dependency was rejected during independent portability review before
publication. The new explicit tool must be executed again on the final source;
that previous observation is not claimed as a new portable or tool result.

This establishes generated-command behavior and source lifecycle wiring. It is
not an installed-device update test and does not prove teardown succeeds in all
error cases: hard-reset errors complete with a warning, MainActivity has a
30-second callback fallback, and launcher cleanup errors can still proceed to
script writing. Failed broadcast/reset, external concurrent installs, actual
BYD camera/native compatibility and fleet behavior remain needs-validation.
No runtime package-manager query, new JNI load or production logic change is
included.

Validation status: original canonical baseline fails the old source-text test.
Targeted actual-generator checks and required canonical wrapper commands are
pending; results will be recorded before PR publication.

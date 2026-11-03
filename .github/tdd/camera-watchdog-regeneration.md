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

The corrected contract exercises the actual generated shell script twice with
different APK/native-directory pairs, retaining the old APK on disk. Only a
dummy `app_process` runs; it records its classpath/arguments and writes the
camera-disable marker. The two generations must use their supplied pair and
retain the full system/vendor library search paths, and both must remove their
watchdog ownership state on exit. Separate source contracts cover the release
receiver/reset request and the use of ApplicationInfo metadata. Existing
ownership, backoff and legacy/alternate mode tests remain in place.

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

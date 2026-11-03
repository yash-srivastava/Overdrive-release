# Explicit Linux watchdog regeneration probe

The default `./gradlew test` suite is portable: its camera metadata tests invoke
the real generator and compare command metadata without running a shell or
skipping tests based on the host OS.

This separate tool exercises actual generated POSIX scripts. Run it only in a
bounded Linux sandbox with no external network, read-only source/toolchain and
scratch-only writes. It requires the existing JDK, Python 3.8+ and `/bin/sh` and
`/bin/sleep`. Unsupported hosts fail explicitly; they do not skip JUnit checks.
Use dummy fixtures only, never a car, real app_process, credentials or ADB.

From the configured disposable project copy:

```sh
./gradlew :app:testDebugUnitTest \
  --tests com.overdrive.app.launcher.CameraWatchdogApkPathAssetTest \
  --init-script tools/tests/camera-watchdog-regeneration/runtime-classpath.init.gradle \
  -PwatchdogProbeClasspathFile=/scratch/watchdog-runtime-classpath.txt
python3 tools/tests/camera-watchdog-regeneration/run-posix.py \
  --classpath-file /scratch/watchdog-runtime-classpath.txt \
  --scratch /scratch/watchdog-posix-probe
```

The same explicit init script may accompany the full unfiltered `test` command;
it only exports the actual debug Test runtime classpath as a declared task
output. It changes no production source or assertions and performs no platform
execution. Configuration-cache compatibility is not established.

The Java exporter compiles against that actual classpath and calls
`DaemonLauncher.Companion.buildCamDaemonWatchdogScript`; it does not parse or
duplicate the generator. It pins and restores the scratch-root model in its own
JVM, remaps both legacy and alternate production root strings, and exports two
scripts with distinct supplied APK/native-directory pairs. The old dummy APK
remains present throughout the new generation's execution.

Each script runs in its own Linux process group with fake `app_process` and
`sleep` programs, a ten-second limit and cleanup of only that owned group. The
probe verifies two launch records, unchanged system/vendor search paths, no old
metadata in the new launch, and removal of watchdog ownership files. No Android
camera, JNI, package installation, bridge action or vehicle acceptance is proved.

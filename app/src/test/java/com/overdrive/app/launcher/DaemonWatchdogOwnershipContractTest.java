package com.overdrive.app.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Assume;
import org.junit.Test;

/** Regression coverage for process-safe camera and ACC watchdog ownership. */
public class DaemonWatchdogOwnershipContractTest {

    @Test
    public void cameraWatchdogAcquiresAtomicallyBeforeEnteringLoop()
            throws Exception {
        String script = join(DaemonLauncher.Companion
                .buildCamDaemonWatchdogScript(
                        "/data/app/base.apk",
                        "/data/app/lib",
                        "/sdcard/Android/data/com.overdrive.app/files",
                        ""));

        assertOwnershipProtocol(script, "/data/local/tmp/start_cam_daemon.sh",
                "/data/local/tmp/cam_watchdog.pid",
                "/data/local/tmp/cam_watchdog.lock");
        assertTrue(script.indexOf("if ! acquire_watchdog_lock; then")
                < script.indexOf("while true; do"));
        assertShellSyntax(script);
    }

    @Test
    public void concurrentLaunchCannotStealLockBeforeOwnerPublishesPid()
            throws Exception {
        Path shell = Paths.get("/bin/sh");
        Assume.assumeTrue(Files.isExecutable(shell));

        Path root = Files.createTempDirectory("overdrive-watchdog-race-");
        try {
            Path scriptPath = root.resolve("start_cam_daemon.sh");
            Path pidFile = root.resolve("cam_watchdog.pid");
            Path lockDir = root.resolve("cam_watchdog.lock");
            Path owners = root.resolve("owners.txt");
            Path release = root.resolve("release");

            String generated = join(DaemonLauncher.Companion
                    .buildCamDaemonWatchdogScript(
                            "/data/app/base.apk",
                            "/data/app/lib",
                            "/sdcard/Android/data/com.overdrive.app/files",
                            ""));
            String acquisitionEnd = "if ! acquire_watchdog_lock; then\n"
                    + "  exit 0\n"
                    + "fi";
            int end = generated.indexOf(acquisitionEnd);
            assertTrue(end >= 0);
            String protocol = generated.substring(
                    0, end + acquisitionEnd.length())
                    .replace("/data/local/tmp/start_cam_daemon.sh",
                            scriptPath.toString())
                    .replace("/data/local/tmp/cam_watchdog.pid",
                            pidFile.toString())
                    .replace("/data/local/tmp/cam_watchdog.lock",
                            lockDir.toString())
                    // Make both contenders reach the atomic directory
                    // acquisition together.
                    .replace(
                            "if mkdir \"$WATCHDOG_LOCK_DIR\" 2>/dev/null; then",
                            "sleep 1\n"
                                    + "  if mkdir \"$WATCHDOG_LOCK_DIR\" "
                                    + "2>/dev/null; then")
                    + "\necho $$ >> \"" + owners + "\"\n"
                    // Hold the lock until the other contender has tried and exited, so a
                    // late start on a loaded machine cannot take a lock already released.
                    + "i=0\n"
                    + "while [ ! -e \"" + release + "\" ] && [ $i -lt 30 ]; do\n"
                    + "  sleep 1\n"
                    + "  i=$((i + 1))\n"
                    + "done\n";
            // Another test's scratch-root override would leave the real paths in place.
            for (Path expected : new Path[] {scriptPath, pidFile, lockDir}) {
                assertTrue("protocol does not use " + expected + ":\n" + protocol,
                        protocol.contains(expected.toString()));
            }
            Files.write(scriptPath,
                    protocol.getBytes(StandardCharsets.UTF_8));

            Process first = new ProcessBuilder(
                    shell.toString(), scriptPath.toString())
                    .redirectErrorStream(true)
                    .start();
            Process second = new ProcessBuilder(
                    shell.toString(), scriptPath.toString())
                    .redirectErrorStream(true)
                    .start();
            // The contender that loses the lock exits first; only then may the owner finish.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (first.isAlive() && second.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            Files.createFile(release);
            byte[] firstOutput = first.getInputStream().readAllBytes();
            byte[] secondOutput = second.getInputStream().readAllBytes();
            assertEquals(new String(firstOutput, StandardCharsets.UTF_8),
                    0, first.waitFor());
            assertEquals(new String(secondOutput, StandardCharsets.UTF_8),
                    0, second.waitFor());

            List<String> ownerLines = Files.exists(owners)
                    ? Files.readAllLines(owners) : java.util.Collections.emptyList();
            assertEquals("owners=" + ownerLines
                            + " first=[" + new String(firstOutput, StandardCharsets.UTF_8)
                            + "] second=[" + new String(secondOutput, StandardCharsets.UTF_8) + "]",
                    1, ownerLines.size());
            assertFalse(Files.exists(pidFile));
            assertFalse(Files.exists(lockDir));
        } finally {
            if (Files.exists(root)) {
                try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                    paths.sorted(Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (Exception ignored) {
                                    // Best-effort test cleanup.
                                }
                            });
                }
            }
        }
    }

    @Test
    public void uiDeploymentPreservesWatchdogScriptsByteForByte()
            throws Exception {
        Path shell = Paths.get("/bin/sh");
        Assume.assumeTrue(Files.isExecutable(shell));

        Path root = Files.createTempDirectory("overdrive-watchdog-write-");
        try {
            List<String> camera = DaemonLauncher.Companion
                    .buildCamDaemonWatchdogScript(
                            "/data/app/base.apk",
                            "/data/app/lib",
                            "/sdcard/Android/data/com.overdrive.app/files",
                            "");
            List<String> acc = DaemonLauncher.Companion
                    .buildAccSentryWatchdogScript(
                            "/data/app/base.apk", "");

            assertWriteRoundTrip(
                    shell, root.resolve("start_cam_daemon.sh"), camera);
            assertWriteRoundTrip(
                    shell, root.resolve("start_acc_sentry.sh"), acc);
        } finally {
            deleteTree(root);
        }
    }

    @Test
    public void deploymentPathsClearStaleOwnershipAfterStoppingOldWrapper()
            throws Exception {
        String launcher = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/launcher/DaemonLauncher.kt");
        String cameraLaunch = section(
                launcher,
                "private fun launchCameraDaemonInternal(",
                "private fun writeCamDaemonScript(");
        assertOrdered(cameraLaunch,
                "psAwkKillLine(\"cam_daemon\")",
                "sleep 1",
                "$CAMERA_WATCHDOG_PID_FILE",
                "rm -rf $CAMERA_WATCHDOG_LOCK_PATH");

        String accLaunch = section(
                launcher,
                "private fun launchAccSentryDaemonInternal(",
                "private fun writeWatchdogScript(");
        assertOrdered(accLaunch,
                "psAwkKillLine(\"acc_sentry\")",
                "sleep 1",
                "$ACC_SENTRY_WATCHDOG_PID_FILE ");
        assertTrue(accLaunch.contains(
                "rm -rf $ACC_SENTRY_WATCHDOG_LOCK_PATH"));

        String telegram = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/telegram/"
                        + "DaemonCommandHandler.java");
        String cameraTelegram = section(
                telegram,
                "private boolean startCameraDaemonWithWatchdog(",
                "private boolean startAccSentryDaemonWithWatchdog(");
        assertOrdered(cameraTelegram,
                "grep -F 'cam_daemon'",
                "sleep 1",
                "/data/local/tmp/cam_watchdog.lock");
        assertTrue(cameraTelegram.contains(
                "rm -rf /data/local/tmp/cam_watchdog.lock"));

        String accTelegram = section(
                telegram,
                "private boolean startAccSentryDaemonWithWatchdog(",
                "private String getReservedZrokUrl(");
        assertOrdered(accTelegram,
                "grep -F 'acc_sentry'",
                "sleep 1",
                "/data/local/tmp/acc_sentry_watchdog.lock");
        assertTrue(accTelegram.contains(
                "rm -rf /data/local/tmp/acc_sentry_watchdog.lock"));

        String cameraController = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/daemon/"
                        + "CameraDaemonController.kt");
        assertTrue(cameraController.contains(
                "rm -rf /data/local/tmp/cam_watchdog.lock"));

        String accController = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/daemon/"
                        + "AccSentryDaemonController.kt");
        assertTrue(accController.contains(
                "rm -rf /data/local/tmp/acc_sentry_watchdog.lock"));
    }

    @Test
    public void accWatchdogOwnsLockBeforeMutatingLogOrDeviceConfig()
            throws Exception {
        String script = join(DaemonLauncher.Companion
                .buildAccSentryWatchdogScript(
                        "/data/app/base.apk", "", true));

        assertOwnershipProtocol(script, "/data/local/tmp/start_acc_sentry.sh",
                "/data/local/tmp/acc_sentry_watchdog.pid",
                "/data/local/tmp/acc_sentry_watchdog.lock");
        int acquired = script.indexOf("if ! acquire_watchdog_lock; then");
        assertTrue(acquired < script.indexOf("/system/bin/device_config put"));
        assertTrue(acquired < script.indexOf("=== WATCHDOG STARTED ==="));
        assertShellSyntax(script);
    }

    @Test
    public void nonDi5AccWatchdogHasNoHealthPollerOrRecurringWakeup()
            throws Exception {
        String script = join(DaemonLauncher.Companion
                .buildAccSentryWatchdogScript(
                        "/data/app/base.apk", "", false));

        assertFalse(script.contains("HEALTH_FILE="));
        assertFalse(script.contains("HEALTH_ARMED="));
        assertFalse(script.contains("HEALTH_WATCH_PID"));

        String launcher = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/launcher/DaemonLauncher.kt");
        String writer = section(
                launcher,
                "private fun writeWatchdogScript(",
                "private fun launchWatchdogScript(");
        assertTrue(writer.contains(
                "DiLink5Platform.isSelected()"));
        String telegram = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/telegram/"
                        + "DaemonCommandHandler.java");
        assertTrue(telegram.contains(
                ".DiLink5Platform.isSelected())"));
        assertShellSyntax(script);
    }

    @Test
    public void vehicleModeRedeployPreservesStopAndParkedGates()
            throws Exception {
        String cleanup = DaemonLauncher.Companion
                .buildAccSentryModeSyncCleanupScript();

        assertOrdered(
                cleanup,
                "[ -f \"/data/local/tmp/acc_sentry_daemon.disabled\" ]",
                "MODE_SYNC_SKIPPED_DISABLED",
                "[ -f \"/data/local/tmp/overdrive_parked_shutdown\" ]",
                "MODE_SYNC_SKIPPED_PARKED",
                "rm -f \"/data/local/tmp/start_acc_sentry.sh\"",
                "grep -F 'acc_sentry'",
                "sleep 1",
                "\"/data/local/tmp/acc_sentry_watchdog.pid\"",
                "\"/data/local/tmp/acc_sentry_health\"",
                "rm -rf \"/data/local/tmp/acc_sentry_watchdog.lock\"",
                "MODE_SYNC_REDEPLOY_READY");
        assertFalse(cleanup.contains(
                "rm -f \"/data/local/tmp/acc_sentry_daemon.disabled\""));
        assertShellSyntax(cleanup);

        String launcher = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/launcher/DaemonLauncher.kt");
        String redeploy = section(
                launcher,
                "fun redeployAccSentryDaemonForVehicleMode(",
                "private fun launchAccSentryDaemonInternal(");
        assertTrue(redeploy.contains(
                "buildAccSentryModeSyncCleanupScript()"));
        assertTrue(redeploy.contains(
                "enableDi5HealthSupervision"));
        assertTrue(redeploy.contains(
                "allowBareFallback = false"));
        assertFalse(redeploy.contains(
                "DiLink5Platform.isSelected()"));
    }

    @Test
    public void accWatchdogRestartsOnlyAnArmedStaleDi5Heartbeat()
            throws Exception {
        String script = join(DaemonLauncher.Companion
                .buildAccSentryWatchdogScript(
                        "/data/app/base.apk", "", true));

        int daemonPid = script.indexOf("DAEMON_PID=$!");
        int healthStart = script.indexOf("HEALTH_ARMED=0", daemonPid);
        int matchingPid = script.indexOf(
                "[ \"$HEALTH_MARKER_PID\" = \"$DAEMON_PID\" ]",
                healthStart);
        int arm = script.indexOf("HEALTH_ARMED=1", matchingPid);
        int staleGate = script.indexOf(
                "if [ $HEALTH_ARMED -eq 1 ]; then", arm);
        int staleAge = script.indexOf(
                "HEALTH_AGE=$((NOW_SEC - HEALTH_LAST_GOOD))",
                staleGate);
        int kill = script.indexOf("kill -9 $DAEMON_PID", staleAge);
        int wait = script.indexOf("wait $DAEMON_PID", kill);
        int stopHealth = script.indexOf(
                "kill $HEALTH_WATCH_PID", wait);

        assertTrue(daemonPid >= 0);
        assertTrue(healthStart > daemonPid);
        assertTrue(matchingPid > healthStart);
        assertTrue(arm > matchingPid);
        assertTrue(staleGate > arm);
        assertTrue(staleAge > staleGate);
        assertTrue(kill > staleAge);
        assertTrue(wait > kill);
        assertTrue(stopHealth > wait);

        // A non-Di5 child never publishes a matching marker, so this remains
        // zero and the kill branch is unreachable.
        assertTrue(script.contains("HEALTH_ARMED=0"));
        assertTrue(script.contains(
                "if [ $HEALTH_CHECK_GAP -gt $HEALTH_SUSPEND_GAP_SEC ]; then"));
        assertTrue(script.contains(
                "HEALTH_LAST_MARKER=$HEALTH_MARKER_SEC"));
        assertTrue(script.contains(
                "elif [ \"$HEALTH_MARKER_SEC\""
                        + " != \"$HEALTH_LAST_MARKER\" ]; then"));
        assertTrue(script.contains(
                "granting fresh grace window"));
        assertShellSyntax(script);
    }

    @Test
    public void accHealthSupervisorIsDormantWithoutMarkerAndKillsWhenStale()
            throws Exception {
        Path shell = Paths.get("/bin/sh");
        Assume.assumeTrue(Files.isExecutable(shell));

        Path root = Files.createTempDirectory("overdrive-acc-health-");
        try {
            runAccHealthScenario(
                    shell, root.resolve("no-marker"), false);
            runAccHealthScenario(
                    shell, root.resolve("stale-marker"), true);
        } finally {
            deleteTree(root);
        }
    }

    private static void runAccHealthScenario(
            Path shell, Path prefix, boolean publishMarker) throws Exception {
        Path systemAwk = Paths.get("/usr/bin/awk");
        Assume.assumeTrue(Files.isExecutable(systemAwk));
        Path scriptPath = Paths.get(prefix + ".sh");
        Path logPath = Paths.get(prefix + ".log");
        Path markerPath = Paths.get(prefix + ".health");
        Path fakeBin = Files.createDirectories(
                Paths.get(prefix + ".bin"));
        Path fakeAwk = fakeBin.resolve("awk");
        Files.write(fakeAwk, (
                "#!/bin/sh\n"
                        + "if [ \"$2\" = \"/proc/uptime\" ]; then\n"
                        + "  date +%s\n"
                        + "else\n"
                        + "  exec \"" + systemAwk + "\" \"$@\"\n"
                        + "fi\n").getBytes(StandardCharsets.UTF_8));
        assertTrue(fakeAwk.toFile().setExecutable(true));

        StringBuilder script = new StringBuilder();
        script.append("#!/bin/sh\n")
                .append("PATH=\"").append(fakeBin)
                .append(":$PATH\"; export PATH\n")
                .append("LOG_FILE=\"").append(logPath).append("\"\n")
                .append("HEALTH_FILE=\"").append(markerPath).append("\"\n")
                .append("HEALTH_POLL_SEC=1\n")
                .append("HEALTH_STALE_SEC=1\n")
                .append("HEALTH_SUSPEND_GAP_SEC=30\n")
                .append(": > \"$LOG_FILE\"\n")
                .append("sleep 30 &\n")
                .append("DAEMON_PID=$!\n");
        if (publishMarker) {
            script.append(
                    "NOW_SEC=$(awk '{print int($1)}' /proc/uptime)\n")
                    .append("echo \"$DAEMON_PID $NOW_SEC\" > \"$HEALTH_FILE\"\n");
        }
        script.append(String.join("\n",
                DaemonLauncher.Companion.accSentryHealthCoprocessLines(
                        "DAEMON_PID", "HEALTH_WATCH_PID")))
                .append('\n');
        if (publishMarker) {
            script.append("TRIES=0\n")
                    .append("while ! grep -F 'heartbeat stale' \"$LOG_FILE\" >/dev/null 2>&1; do\n")
                    .append("  TRIES=$((TRIES + 1))\n")
                    .append("  if [ $TRIES -ge 10 ]; then\n")
                    .append("    kill $DAEMON_PID $HEALTH_WATCH_PID 2>/dev/null\n")
                    .append("    exit 41\n")
                    .append("  fi\n")
                    .append("  sleep 1\n")
                    .append("done\n")
                    .append("wait $DAEMON_PID 2>/dev/null || true\n")
                    .append("kill -0 $DAEMON_PID 2>/dev/null && exit 42\n")
                    .append("wait $HEALTH_WATCH_PID 2>/dev/null || true\n");
        } else {
            script.append("sleep 3\n")
                    .append("kill -0 $DAEMON_PID 2>/dev/null || exit 43\n")
                    .append("kill $DAEMON_PID $HEALTH_WATCH_PID 2>/dev/null\n")
                    .append("wait $DAEMON_PID 2>/dev/null || true\n")
                    .append("wait $HEALTH_WATCH_PID 2>/dev/null || true\n");
        }
        Files.write(
                scriptPath,
                script.toString().getBytes(StandardCharsets.UTF_8));

        Process process = new ProcessBuilder(
                shell.toString(), scriptPath.toString())
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(20, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        byte[] output = process.getInputStream().readAllBytes();
        assertTrue("health supervisor scenario timed out: "
                        + new String(output, StandardCharsets.UTF_8),
                finished);
        assertEquals(
                new String(output, StandardCharsets.UTF_8),
                0,
                process.exitValue());
    }

    private static void assertOwnershipProtocol(
            String script, String scriptPath, String pidFile, String lockFile) {
        assertTrue(script.contains("WATCHDOG_SCRIPT=\"" + scriptPath + "\""));
        assertTrue(script.contains("WATCHDOG_PID_FILE=\"" + pidFile + "\""));
        assertTrue(script.contains("WATCHDOG_LOCK_DIR=\"" + lockFile + "\""));
        assertTrue(script.contains(
                "WATCHDOG_LOCK_OWNER=\"$WATCHDOG_LOCK_DIR/pid\""));

        int atomicMkdir = script.indexOf(
                "if mkdir \"$WATCHDOG_LOCK_DIR\" 2>/dev/null; then");
        int ownerPidWrite =
                script.indexOf("echo $$ > \"$WATCHDOG_LOCK_OWNER\"");
        int publicPidWrite =
                script.indexOf("echo $$ > \"$WATCHDOG_PID_FILE\"");
        assertTrue(atomicMkdir >= 0);
        assertTrue(ownerPidWrite > atomicMkdir);
        assertTrue(publicPidWrite > ownerPidWrite);
        assertFalse(script.contains("if ln "));

        assertTrue(script.contains(
                "[ -r \"/proc/$CANDIDATE_PID/cmdline\" ] || return 1"));
        assertTrue(script.contains(
                "grep -F \"$WATCHDOG_SCRIPT\" >/dev/null 2>&1"));
        int identityCheck =
                script.indexOf("if watchdog_pid_matches \"$OLD_WPID\"; then");
        assertTrue(identityCheck >= 0);
        String acquisition = script.substring(
                script.indexOf("acquire_watchdog_lock() {"),
                script.indexOf("cleanup_watchdog_lock() {"));
        assertFalse(acquisition.contains(
                "rm -rf \"$WATCHDOG_LOCK_DIR\""));
        assertTrue(script.contains("trap 'cleanup_watchdog_lock' EXIT"));
        assertTrue(script.contains(
                "if [ \"$CURRENT_WPID\" = \"$$\" ]; then"));
        assertTrue(script.contains(
                "if [ \"$LEGACY_WPID\" = \"$$\" ]; then"));
        assertTrue(script.contains(
                "rmdir \"$WATCHDOG_LOCK_DIR\" 2>/dev/null"));

        // Ownership must come exclusively from atomic mkdir, not a
        // check-then-write PID file or Android-blocked hard link.
        assertFalse(script.contains(
                "if [ -f \"$WATCHDOG_PID_FILE\" ]; then"));
    }

    private static void assertWriteRoundTrip(
            Path shell, Path scriptPath, List<String> lines) throws Exception {
        String expected = String.join("\n", lines) + "\n";
        assertTrue(expected.contains("\\000"));

        String writeCommand = DaemonLauncher.Companion
                .buildWatchdogScriptWriteCommand(
                        scriptPath.toString(), lines);
        Process writer = new ProcessBuilder(
                shell.toString(), "-c", writeCommand)
                .redirectErrorStream(true)
                .start();
        byte[] writerOutput = writer.getInputStream().readAllBytes();
        assertEquals(
                new String(writerOutput, StandardCharsets.UTF_8),
                0,
                writer.waitFor());

        byte[] actualBytes = Files.readAllBytes(scriptPath);
        for (byte value : actualBytes) {
            assertTrue("deployed watchdog contains a binary NUL", value != 0);
        }
        assertEquals(expected,
                new String(actualBytes, StandardCharsets.UTF_8));

        Process syntax = new ProcessBuilder(
                shell.toString(), "-n", scriptPath.toString())
                .redirectErrorStream(true)
                .start();
        byte[] syntaxOutput = syntax.getInputStream().readAllBytes();
        assertEquals(
                new String(syntaxOutput, StandardCharsets.UTF_8),
                0,
                syntax.waitFor());
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                            // Best-effort test cleanup.
                        }
                    });
        }
    }

    private static String join(List<String> lines) {
        return String.join("\n", lines);
    }

    private static String section(
            String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertTrue("missing section start: " + startMarker, start >= 0);
        assertTrue("missing section end: " + endMarker, end > start);
        return source.substring(start, end);
    }

    private static void assertOrdered(String source, String... fragments) {
        int previous = -1;
        for (String fragment : fragments) {
            int current = source.indexOf(fragment, previous + 1);
            assertTrue("missing/out-of-order fragment: " + fragment,
                    current > previous);
            previous = current;
        }
    }

    private static String readRepositoryFile(String relativePath)
            throws Exception {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(
                        Files.readAllBytes(candidate),
                        StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(
                    relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(
                        Files.readAllBytes(fromModule),
                        StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError(
                "Could not locate repository file: " + relativePath);
    }

    private static void assertShellSyntax(String script) throws Exception {
        Path shell = Paths.get("/bin/sh");
        Assume.assumeTrue(Files.isExecutable(shell));
        Path file = Files.createTempFile("overdrive-watchdog-", ".sh");
        try {
            Files.write(file, script.getBytes(StandardCharsets.UTF_8));
            Process process = new ProcessBuilder(
                    shell.toString(), "-n", file.toString())
                    .redirectErrorStream(true)
                    .start();
            byte[] output = process.getInputStream().readAllBytes();
            int exit = process.waitFor();
            assertTrue("shell syntax error: "
                            + new String(output, StandardCharsets.UTF_8),
                    exit == 0);
        } finally {
            Files.deleteIfExists(file);
        }
    }
}

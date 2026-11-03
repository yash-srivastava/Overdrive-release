package com.overdrive.app.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** Camera APK and native-library metadata are refreshed by post-update regeneration. */
public class CameraWatchdogApkPathAssetTest {

    @Test
    public void regeneratedWatchdogUsesTheNewApkAndItsSuppliedNativeDirectory()
            throws Exception {
        Path root = Files.createTempDirectory("camera-package-refresh-");
        try {
            Path calls = root.resolve("calls");
            Path fakeBin = root.resolve("bin");
            Files.createDirectories(fakeBin);
            Path appProcess = fakeBin.resolve("app_process");
            Files.write(appProcess, (
                    "#!/bin/sh\n"
                    + "printf '%s\\n' \"$CLASSPATH\" \"$@\" >> \"$CALLS\"\n"
                    + ": > \"$STOP_FILE\"\n").getBytes(StandardCharsets.UTF_8));
            assertTrue(appProcess.toFile().setExecutable(true));
            Path sleep = fakeBin.resolve("sleep");
            Files.write(sleep, "#!/bin/sh\nexec /bin/sleep 30\n".getBytes(StandardCharsets.UTF_8));
            assertTrue(sleep.toFile().setExecutable(true));

            // Keep both generations on disk: old APK existence must not affect
            // which pair the regenerated script receives from ApplicationInfo.
            Path oldApk = root.resolve("old/base.apk");
            Path newApk = root.resolve("new/base.apk");
            Files.createDirectories(oldApk.getParent());
            Files.createDirectories(newApk.getParent());
            Files.createFile(oldApk);
            Files.createFile(newApk);
            String oldLib = root.resolve("old-native/vendor-selected").toString();
            String newLib = root.resolve("new-native/authoritative-location").toString();
            runOneGeneration(root, fakeBin, calls, oldApk.toString(), oldLib);
            runOneGeneration(root, fakeBin, calls, newApk.toString(), newLib);

            List<String> launches = Files.readAllLines(calls);
            assertEquals(14, launches.size());
            assertTrue(launches.get(0).endsWith(oldApk.toString()));
            assertEquals("-Djava.library.path=" + oldLib
                    + ":/system/lib64:/vendor/lib64:/product/lib64:/odm/lib64", launches.get(1));
            assertEquals(oldLib, launches.get(6));
            assertTrue(launches.get(7).endsWith(newApk.toString()));
            assertEquals("-Djava.library.path=" + newLib
                    + ":/system/lib64:/vendor/lib64:/product/lib64:/odm/lib64", launches.get(8));
            assertEquals(newLib, launches.get(13));
            assertFalse(launches.subList(7, 14).toString().contains(oldApk.toString()));
            assertFalse(launches.subList(7, 14).toString().contains(oldLib));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    public void cameraClasspathRetainsTheLegacyAndAlternateJarPolicy() throws Exception {
        java.lang.reflect.Method pin = com.overdrive.app.util.ScratchRoot.class
                .getDeclaredMethod("setResolvedDirForTest", String.class);
        pin.setAccessible(true);
        try {
            pin.invoke(null, com.overdrive.app.util.ScratchRoot.LEGACY_DIR);
            String legacy = String.join("\n", DaemonLauncher.Companion
                    .buildCamDaemonWatchdogScript("/installed/base.apk", "/native", "/output", ""));
            assertTrue(legacy.contains(
                    "CLASSPATH=/system/framework/bmmcamera.jar:/installed/base.apk app_process"));
            pin.invoke(null, com.overdrive.app.util.ScratchRoot.ALT_DIR);
            String alternate = String.join("\n", DaemonLauncher.Companion
                    .buildCamDaemonWatchdogScript("/installed/base.apk", "/native", "/output", ""));
            String jar = new java.io.File("/system/framework/bmmcamera.jar").isFile()
                    ? "/system/framework/bmmcamera.jar:" : "";
            assertTrue(alternate.contains(
                    "CLASSPATH=" + jar + "/installed/base.apk app_process"));
        } finally {
            pin.invoke(null, new Object[] {null});
        }
    }

    @Test
    public void packageReplacementRequestsPostUpdateResetBeforeStartup() throws Exception {
        String receiver = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/receiver/BootReceiver.kt");
        String replaced = section(receiver, "Intent.ACTION_MY_PACKAGE_REPLACED -> {",
                "// Screen/user events");
        assertTrue(replaced.contains("UpdateLifecycle.EXTRA_POST_UPDATE"));
        assertTrue(replaced.contains("context.startActivity(launchIntent)"));
        // Debug deployments remain IDE-owned, as documented by the receiver.
        assertTrue(replaced.contains("BuildConfig.DEBUG"));

        String activity = readRepositoryFile("app/src/main/java/com/overdrive/app/ui/MainActivity.kt");
        String startup = section(activity, "private fun runDaemonStartup(",
                "private fun showPostUpdateToasts(");
        String resetCallback = section(startup,
                "UpdateLifecycle.hardResetDaemons(this) {", "} else {");
        assertTrue(resetCallback.contains("startDaemons.run()"));
        assertTrue(startup.contains("if (isPostUpdate)"));

        String reset = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/updater/UpdateLifecycle.java");
        assertTrue(reset.contains("camera_daemon.disabled"));
        assertTrue(reset.contains("rm -f /data/local/tmp/start_cam_daemon.sh"));
        assertTrue(reset.contains("psAwkKillLine(\"cam_daemon\")"));
    }

    @Test
    public void regenerationReadsApplicationInfoInsteadOfDerivingNativePaths() throws Exception {
        String launcher = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/launcher/DaemonLauncher.kt");
        String internal = section(launcher, "private fun launchCameraDaemonInternal(",
                "private fun writeCamDaemonScript(");
        assertTrue(internal.contains("val apkPath = context.applicationInfo.sourceDir"));
        assertTrue(internal.contains(
                "writeCamDaemonScript(apkPath, proxyArgs, outputDir, nativeLibDir, scriptPath, callback)"));
        String controller = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/daemon/CameraDaemonController.kt");
        assertTrue(controller.contains("context.applicationInfo.nativeLibraryDir"));
        String generator = section(launcher, "fun buildCamDaemonWatchdogScript(",
                "interface LaunchCallback");
        assertFalse(generator.contains("/lib/arm64"));
    }

    private static void runOneGeneration(Path root, Path fakeBin, Path calls,
                                         String apk, String nativeDir) throws Exception {
        Path stop = root.resolve("camera_daemon.disabled");
        Files.deleteIfExists(stop);
        Path script = root.resolve("start_cam_daemon.sh");
        String generated = String.join("\n", DaemonLauncher.Companion
                .buildCamDaemonWatchdogScript(apk, nativeDir, "/output", ""))
                .replace("/data/local/tmp", root.toString());
        Files.write(script, (generated + "\n").getBytes(StandardCharsets.UTF_8));
        ProcessBuilder builder = new ProcessBuilder("/bin/sh", script.toString())
                .redirectErrorStream(true);
        builder.environment().put("PATH", fakeBin + ":/usr/bin:/bin");
        builder.environment().put("CALLS", calls.toString());
        builder.environment().put("STOP_FILE", stop.toString());
        Process process = builder.start();
        boolean done = process.waitFor(10, TimeUnit.SECONDS);
        if (!done) {
            process.destroyForcibly();
        }
        assertTrue("generated watchdog did not stop after dummy daemon marker", done);
        assertEquals(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                0, process.exitValue());
        assertFalse(Files.exists(root.resolve("cam_watchdog.lock")));
        assertFalse(Files.exists(root.resolve("cam_watchdog.pid")));
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue("missing start: " + start, from >= 0);
        assertTrue("missing end: " + end, to > from);
        return source.substring(from, to);
    }

    private static void deleteTree(Path root) throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}

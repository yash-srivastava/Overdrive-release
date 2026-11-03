package com.overdrive.app.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.Test;

/** Camera APK and native-library metadata are refreshed by post-update regeneration. */
public class CameraWatchdogApkPathAssetTest {

    @Test
    public void regeneratedWatchdogUsesTheNewApkAndItsSuppliedNativeDirectory()
            throws Exception {
        java.lang.reflect.Method pin = com.overdrive.app.util.ScratchRoot.class
                .getDeclaredMethod("setResolvedDirForTest", String.class);
        pin.setAccessible(true);
        try {
            pin.invoke(null, com.overdrive.app.util.ScratchRoot.LEGACY_DIR);
            String oldApk = "/installed/old/base.apk";
            String newApk = "/installed/new/base.apk";
            String oldLib = "/old-native/vendor-selected";
            String newLib = "/new-native/authoritative-location";
            List<String> oldScript = DaemonLauncher.Companion
                    .buildCamDaemonWatchdogScript(oldApk, oldLib, "/output", "");
            List<String> newScript = DaemonLauncher.Companion
                    .buildCamDaemonWatchdogScript(newApk, newLib, "/output", "");
            assertLaunchMetadata(oldScript, oldApk, oldLib);
            assertLaunchMetadata(newScript, newApk, newLib);
            String regenerated = String.join("\n", newScript);
            assertFalse(regenerated.contains(oldApk));
            assertFalse(regenerated.contains(oldLib));
        } finally {
            pin.invoke(null, new Object[] {null});
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

    private static void assertLaunchMetadata(List<String> script, String apk, String nativeDir) {
        String launch = null;
        int commands = 0;
        for (String line : script) {
            if (line.trim().startsWith("CLASSPATH=") && line.contains("app_process")) {
                launch = line;
                commands++;
            }
        }
        assertEquals("Exactly one camera launch command is generated", 1, commands);
        assertTrue(launch.contains("CLASSPATH=/system/framework/bmmcamera.jar:" + apk + " app_process "));
        assertTrue(launch.contains("-Djava.library.path=" + nativeDir
                + ":/system/lib64:/vendor/lib64:/product/lib64:/odm/lib64 "));
        assertTrue(launch.contains("com.overdrive.app.daemon.CameraDaemon /output " + nativeDir + " >> "));
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue("missing start: " + start, from >= 0);
        assertTrue("missing end: " + end, to > from);
        return source.substring(from, to);
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

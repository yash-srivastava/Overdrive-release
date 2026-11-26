package com.overdrive.app.daemon;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import com.overdrive.app.byd.BydDeviceHelper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class AccSentryDiLink5PanelContractTest {

    public static final class StartupDevice {
        boolean enabled;
        int writeResult;
        int writes;

        StartupDevice(boolean enabled, int writeResult) {
            this.enabled = enabled;
            this.writeResult = writeResult;
        }

        public boolean getStartupAppEnable(String packageName) {
            return enabled;
        }

        public int setStartupAppEnable(String packageName, boolean value) {
            writes++;
            if (writeResult >= 0) enabled = value;
            return writeResult;
        }
    }

    @Test
    public void dilink5UsesVerifiedDarkeningAndRetriesFailures()
            throws Exception {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/daemon/AccSentryDaemon.java");

        assertTrue(source.contains(
                "isDilink4CameraMode() || isDilink5CameraMode()"));
        assertTrue(source.contains(
                "DILINK5_FORCE_REPUBLISH_TICKS = 2"));
        assertTrue(source.contains("\"~10s\""));
        assertTrue(source.contains(
                "shouldApplyHardwarePeripheralPower(state)"));
        // R25 rail gate: Di5 rail writes are authorized by a field-verified
        // capability registry, never by a hardcoded model string. The registry
        // ships EMPTY so unknown Di5 firmware fails closed, and the synchronous
        // gate is a pure registry lookup against a background-probed signature
        // (null until observed → closed). The keep-awake user toggle remains a
        // separate mandatory condition at the feature gate.
        assertFalse(source.contains("getSelectedVehicleModelId"));
        assertFalse(source.contains("\"sealion7\""));
        assertTrue(source.contains(
                "VERIFIED_DILINK5_RAIL_SIGNATURES = {}"));
        assertTrue(source.contains(
                "isVerifiedDilink5RailCapability()"));
        assertTrue(source.contains(
                "if (observed == null) return false;"));
        assertTrue(source.contains(
                "state.keepAwakeEnabled() && isVehicleRailControlSupported()"));
        assertTrue(source.contains(
                "DI5 voltage-driven MCU monitor disabled"));
        assertTrue(source.contains(
                "DI5 keep-alive is limited to Android CPU/network holds"));
        assertTrue(source.contains(
                "DI5 vehicle-rail keep-alive enabled"));
        assertTrue(source.contains(
                "rail control fails closed"));

        int adbRecovery = source.indexOf(
                "private static void recoverDilink5AdbIfNeeded");
        int adbPortCheck = source.indexOf(
                "isLoopbackPortOpen(ADB_TCP_PORT)", adbRecovery);
        int adbBackoff = source.indexOf(
                "DILINK5_ADB_RECOVERY_RETRY_MS", adbPortCheck);
        int adbObservation = source.indexOf(
                "waiting for coordinated app-process recovery", adbBackoff);
        assertTrue(adbRecovery >= 0);
        assertTrue(adbPortCheck > adbRecovery);
        assertTrue(adbBackoff > adbPortCheck);
        assertTrue(adbObservation > adbBackoff);
        String adbRecoveryBody = source.substring(adbRecovery,
                source.indexOf("private static boolean isLoopbackPortOpen", adbRecovery));
        assertFalse(adbRecoveryBody.contains("settings put global adb_enabled"));
        assertFalse(adbRecoveryBody.contains("settings put global adb_wifi_enabled"));
        assertFalse(adbRecoveryBody.contains("settings put global adb_allowed_connection_time"));
        assertFalse(source.contains(
                "settings put global development_settings_enabled"));
        assertFalse(source.contains("persist.sys.usb.config"));

        int backlightMethod = source.indexOf(
                "private static boolean setBacklightState(\n"
                        + "            boolean on,\n"
                        + "            ShellOwnership ownership,\n"
                        + "            boolean allowDeviceSleep)");
        int backlightMethodEnd = source.indexOf(
                "/**\n     * Enforces strict power management state.",
                backlightMethod);
        String backlightBody =
                source.substring(backlightMethod, backlightMethodEnd);
        int diLink5Fallback =
                backlightBody.indexOf("if (isDilink5CameraMode()) {");
        int legacyFallback =
                backlightBody.indexOf("// Legacy fallback: Settings brightness");
        int verifiedReturn = backlightBody.indexOf(
                "return com.overdrive.app.power.StealthPanel",
                diLink5Fallback);
        int legacySleep =
                backlightBody.indexOf("\"input keyevent 223\"", legacyFallback);
        assertTrue(backlightMethod >= 0);
        assertTrue(backlightMethodEnd > backlightMethod);
        assertTrue(diLink5Fallback >= 0);
        assertTrue(verifiedReturn > diLink5Fallback);
        assertTrue(legacyFallback > verifiedReturn);
        assertFalse(backlightBody.substring(
                diLink5Fallback, legacyFallback).contains("screen_brightness"));
        assertTrue(legacySleep > legacyFallback);

        int smartSleep = source.indexOf(
                "private static void enforceSmartSleep()");
        int diLink4Probe = source.indexOf(
                "boolean dilink4 = isDilink4CameraMode();", smartSleep);
        int diLink5SmartSleep =
                source.indexOf(
                        "if (!dilink4 && isDilink5CameraMode()) {",
                        diLink4Probe);
        int checkedResult = source.indexOf(
                "boolean darkened =", diLink5SmartSleep);
        int retry = source.indexOf(
                "requestPanelForLatestTransition();", checkedResult);
        int diLink4SmartSleep =
                source.indexOf("if (dilink4) {", retry);
        assertTrue(smartSleep >= 0);
        assertTrue(diLink4Probe > smartSleep);
        assertTrue(diLink5SmartSleep > diLink4Probe);
        assertTrue(checkedResult > diLink5SmartSleep);
        assertTrue(retry > checkedResult);
        assertTrue(diLink4SmartSleep > retry);
    }

    @Test
    public void autostartRequiresAuthoritativeReadback() {
        StartupDevice accepted = new StartupDevice(false, 1);
        assertTrue(BydDeviceHelper.verifyStartupAppAccess(
                accepted, "com.overdrive.app", 1, 0L));
        assertTrue(accepted.enabled);

        StartupDevice rejected = new StartupDevice(false, -1);
        assertFalse(BydDeviceHelper.verifyStartupAppAccess(
                rejected, "com.overdrive.app", 2, 0L));
        assertFalse(rejected.enabled);
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
}

package com.overdrive.app.power;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Guards the safety/lifecycle contract of the opt-in DiLink 5 parked
 * keep-alive lease: default off, gates untouched, lease-only write path,
 * marker-scoped hygiene, process-exit releases, and the UI/API wiring.
 */
public class Di5ParkedKeepAliveContractTest {

    @Test
    public void configIsOptInAndFailsClosed() throws IOException {
        String config = read(
                "app/src/main/java/com/overdrive/app/config/UnifiedConfigManager.kt");
        assertTrue(config.contains(
                "surveillance.put(\"di5ParkedKeepAlive\", false)"));
        assertTrue(config.contains(
                "Di5ParkedKeepAliveSettings.ensureLinkConfig(surveillance)"));
        assertTrue(config.contains(
                "surveillance.put(\"di5ParkedKeepAliveMcuHold\", true)"));
        assertTrue(config.contains(
                "surveillance.put(\"di5ParkedKeepAliveMcuPowerHold\", true)"));
        assertTrue(config.contains(
                "surveillance.put(\"di5ParkedKeepAliveCameraHeartbeat\", false)"));
        assertTrue(config.contains(
                "surveillance.put(\"di5ParkedKeepAliveApHold\", false)"));
        assertTrue(config.contains(
                "surveillance.put(\"di5ParkedKeepAliveApHoldPreflightPassed\", false)"));
        assertTrue(config.contains(
                "surveillance.put(\"di5ParkedKeepAliveCutoffVoltage\", 11.8)"));
        assertTrue(config.contains(
                "fun isDi5ParkedKeepAliveEnabled(): Boolean"));
        assertTrue(config.contains(
                "optBoolean(\"di5ParkedKeepAlive\", false)"));
    }

    @Test
    public void legacyDi5GatesAreUntouchedAndLeasePathIsTheOnlyDi5Writer()
            throws IOException {
        String mcu = read("app/src/main/java/com/overdrive/app/power/McuPowerHal.java");
        String accSentry = read(
                "app/src/main/java/com/overdrive/app/daemon/AccSentryDaemon.java");

        // Every legacy entry point still fails closed on DiLink 5.
        assertTrue(count(mcu, "if (isDilink5Mode()) return false;") >= 4);
        // The fail-closed rail registry is still empty.
        assertTrue(accSentry.contains("VERIFIED_DILINK5_RAIL_SIGNATURES = {}"));
        assertTrue(accSentry.contains(
                "if (isDilink5CameraMode() && !isVerifiedDilink5RailCapability()) return false;"));

        // Lease path: token-gated, assert requires a current parked generation,
        // release never does; the MCU-sleep event is never written by the lease.
        assertTrue(mcu.contains("public static int[] writeSentryFlagsForLease("));
        assertTrue(mcu.contains("private static boolean leaseAuthorized(Di5LeaseToken token, boolean assertSide)"));
        assertTrue(mcu.contains("if (token == null) return false;\n"
                + "        return !assertSide || token.isCurrent();"));
        int leaseStart = mcu.indexOf("// ── DiLink 5 parked keep-alive lease");
        int internals = mcu.indexOf("// ── Internals");
        assertTrue(leaseStart > 0 && internals > leaseStart);
        String leaseSection = mcu.substring(leaseStart, internals);
        // EVENT_MCU_SLEEP_WAKE is written ONLY by the dedicated power-hold
        // method (1 = hold/wake, 0 = release-if-asserted), never as a bare
        // "request MCU sleep".
        assertTrue(leaseSection.contains(
                "public static int writeMcuPowerHoldForLease(Di5LeaseToken token, boolean hold)"));
        assertTrue(leaseSection.contains("int value = hold ? 1 : 0;"));
        assertEquals(1, count(leaseSection, "EVENT_MCU_SLEEP_WAKE,"));
        assertFalse(leaseSection.contains("requestMcuSleep("));
        assertTrue(leaseSection.contains("int enter = assertFlags ? 1 : 0;"));
        assertTrue(leaseSection.contains("int state = assertFlags ? 1 : 2;"));
        // Not tied to the camera-mode selection (two DiLink 5 head-unit
        // flavours): the lease section never consults the mode predicates.
        assertFalse(leaseSection.contains("isDilink5Mode()"));
        assertFalse(leaseSection.contains("isDilink4CameraMode()"));
        assertFalse(leaseSection.contains("DiLink5Platform"));
        // The lease resolves its own special-device handle (both FQNs) so it
        // never widens what the fleet-wide writers resolve.
        assertTrue(leaseSection.contains("private static Object resolveLeaseSpecialDevice()"));
        assertTrue(leaseSection.contains("for (String fqn : SPECIAL_CLASS_CANDIDATES)"));
        assertTrue(leaseSection.contains("cachedLeaseSpecialDevice = device;"));
        assertFalse(leaseSection.contains("cachedSpecialDevice = "));
        int writeStart = leaseSection.indexOf("public static int[] writeSentryFlagsForLease(");
        int writeEnd = leaseSection.indexOf("public static int readMcuStatusForLease(");
        String writeBody = leaseSection.substring(writeStart, writeEnd);
        assertTrue(writeBody.contains("resolveLeaseSpecialDevice()"));
        assertFalse(writeBody.contains("resolveSpecialDevice()"));
        assertTrue(mcu.contains("cachedLeaseSpecialDevice = null;"));
    }

    @Test
    public void leaseControllerContract() throws IOException {
        String hold = read(
                "app/src/main/java/com/overdrive/app/power/Di5ParkedPowerHold.java");
        // Marker before the first HAL write; fail closed when it cannot be written.
        assertTrue(hold.indexOf("marker.record(generation, toRecord, now, hardware.pid())")
                < hold.indexOf("assertMcuLocked(generation, now, s);"));
        assertTrue(hold.contains("refusing to write HAL flags (fail closed)"));
        // No flag writes into a sleeping MCU; bounded wake polling.
        assertTrue(hold.contains("status == MCU_STATUS_ACTIVE || status == MCU_STATUS_ACTIVE_ALT"));
        assertTrue(hold.contains("WAKE_POLL_MAX_MS = 10_000L"));
        assertTrue(hold.contains("flags NOT written; will retry"));
        // OEM-parity MCU power hold: written UNCONDITIONALLY at assert time
        // (before the status read / wake poll — the 1 IS the wake request);
        // the release-side 0 only when asserted or marker-recorded.
        int assertStart = hold.indexOf("private void assertMcuLocked(");
        assertTrue(assertStart > 0);
        int holdWrite = hold.indexOf(
                "int holdRc = safeWriteMcuPowerHold(generation, true);", assertStart);
        assertTrue(holdWrite > 0);
        // The main-flow MCU status read AND the wake attempt both come after
        // the hold write (the earlier safeMcuStatus in the method is the
        // between-cadence liveness probe, which precedes lastMcuAssertAtMs).
        int statusRead = hold.indexOf("mcuStatusBefore = status;", holdWrite);
        int wakeCall = hold.indexOf("hardware.wakeUpMcu(generation);", holdWrite);
        assertTrue(statusRead > holdWrite && wakeCall > statusRead);
        assertTrue(hold.contains("private void releaseMcuPowerLocked("));
        assertTrue(hold.contains("mcuPowerHoldAsserted || recMcuPower, apHeld || recAp"));
        assertTrue(hold.contains(
                "if (!s.isMcuPowerHoldEffective() && mcuPowerHoldAsserted) {"));
        // Cutoff latches until ACC ON or confirmed charging; never resumes on rebound.
        assertTrue(hold.contains("voltageGuard.releaseLatch(\"ACC ON / lease end\")"));
        assertTrue(hold.contains("external charging confirmed"));
        assertFalse(hold.contains("releaseLatch(\"voltage recovered\")"));
        // Own token, never another app's; release strips only our token.
        assertTrue(hold.contains("AP_HOLD_TOKEN = \"ovdrv\""));
        assertFalse(hold.contains("\"vandp\""));
        // Release order: heartbeat, then sentry flags, then AP.
        String release = hold.substring(hold.indexOf("private void releaseLeversLocked("));
        assertTrue(release.indexOf("releaseCameraLocked(\"release\")")
                < release.indexOf("releaseMcuLocked(\"release\")"));
        assertTrue(release.indexOf("releaseMcuLocked(\"release\")")
                < release.indexOf("releaseApLocked(\"release\")"));
        // Cross-process status file the API reads.
        assertTrue(hold.contains(
                "DEFAULT_STATUS_PATH =\n            com.overdrive.app.util.ScratchRoot"
                        + ".path(\"overdrive_di5_keepalive.status\")"));
    }

    @Test
    public void daemonWiringIsGenerationFencedAndNotTiedToCameraMode() throws IOException {
        String accSentry = read(
                "app/src/main/java/com/overdrive/app/daemon/AccSentryDaemon.java");
        // Requested on every platform with the keep-alive loop's own verdict —
        // DiLink 5 head units exist with and without the QCarCam camera stack,
        // so the lease must never follow the camera-mode selection.
        assertTrue(accSentry.contains(
                "keepAliveReconciler.request(state.generation, keepAwake);\n"
                        + "        // Not keyed to the camera-mode selection (see installDi5ParkedKeepAlive);\n"
                        + "        // the lease is inert while the user's master toggle is OFF.\n"
                        + "        di5KeepAliveReconciler.request(state.generation, keepAwake);"));
        assertFalse(accSentry.contains(
                "if (isDilink5CameraMode()) {\n"
                        + "            di5KeepAliveReconciler.request("));
        // Ticked from the shared 10 s loop OUTSIDE the DiLink 5-only block: after
        // that block's ADB recovery and before the network maintenance step.
        int loopStart = accSentry.indexOf(
                "while (running && isKeepAliveCommitCurrent(transitionGeneration))");
        assertTrue(loopStart > 0);
        int adb = accSentry.indexOf("recoverDilink5AdbIfNeeded(transitionGeneration);", loopStart);
        int tickCall = accSentry.indexOf("di5KeepAliveTick(transitionGeneration);", loopStart);
        int network = accSentry.indexOf("// 1. Maintain Network Interface Stability", loopStart);
        assertTrue(adb > 0 && tickCall > adb && network > tickCall);
        // The DI5-only block closes before the tick (two closing braces between).
        String between = accSentry.substring(adb, tickCall);
        assertTrue(count(between, "}") >= 2);
        // Installed at start-up with marker-scoped hygiene, after the parked-marker
        // seed, on every platform (no camera-mode early return).
        assertTrue(accSentry.indexOf("seedSentryEntryFromParkedMarker();")
                < accSentry.indexOf("installDi5ParkedKeepAlive();"));
        int installStart = accSentry.indexOf("private static void installDi5ParkedKeepAlive()");
        int installEnd = accSentry.indexOf("private static void di5KeepAliveTick(", installStart);
        assertTrue(installStart > 0 && installEnd > installStart);
        String installBody = accSentry.substring(installStart, installEnd);
        assertFalse(installBody.contains("isDilink5CameraMode()"));
        assertFalse(installBody.contains("DiLink5Platform"));
        // Hygiene's ACC probe is lazy (only runs when an ownership marker exists).
        assertTrue(accSentry.contains("hold.onDaemonStart(() ->"));
        assertTrue(accSentry.contains(
                "!com.overdrive.app.monitor.AccMonitor.probeAccState(appContext));"));
        // Released synchronously on daemon shutdown before the reconcilers tear down.
        int shutdown = accSentry.indexOf("log(\"=== DAEMON SHUTDOWN INITIATED ===\");");
        assertTrue(shutdown > 0);
        int release = accSentry.indexOf(
                ".releaseForProcessExit(\"daemon shutdown\", inSentryMode);",
                shutdown);
        int transition = accSentry.indexOf("beginShutdownAccOnTransition();", shutdown);
        assertTrue(release > 0 && release < transition);
        // Token minted per generation against the sentry fence.
        assertTrue(accSentry.contains(
                "generation, () -> isSentryTransitionCurrent(generation, true));"));
        // Context handed to McuPowerHal without dropping its cached devices per tick.
        assertTrue(accSentry.contains("McuPowerHal.ensureAppContext("));
        assertFalse(accSentry.contains("McuPowerHal.setAppContext(\n"
                + "                    new PermissionBypassContext(appContext));"));
        // ADB status memory is an isolated item, never part of the aggregate verdict.
        assertTrue(accSentry.contains("result.put(\"adbStatusMemory\", adbStatusMemory);"));
        assertFalse(accSentry.contains("&& adbStatusMemory"));
    }

    @Test
    public void socCutoffReleasesLeaseBeforeSleepAndKeepsExistingOrder()
            throws IOException {
        String soc = read("app/src/main/java/com/overdrive/app/power/SocCutoffMonitor.java");
        int stop = soc.indexOf("BatteryVoltageMonitorV2.stopMonitorForShutdown()");
        int lease = soc.indexOf("Di5ParkedPowerHold.releaseForProcessExit(\"SoC cutoff\")");
        int panel = soc.indexOf("boolean stealthPanelPlatform = false");
        int sleep = soc.indexOf("powerManagerGoToSleep();");
        assertTrue(stop > 0 && lease > stop && panel > lease && sleep > panel);
    }

    @Test
    public void panoramaWorkModeSetterIdIsTheCatalogValue() throws IOException {
        String helper = read(
                "app/src/main/java/com/overdrive/app/camera/BydApaViewpointHelper.java");
        String owner = read(
                "app/src/main/java/com/overdrive/app/power/PanoramaWorkModeOwner.java");
        assertTrue(helper.contains("1306529812,   // PANORAMA_WORK_MODE_SET"));
        assertFalse(helper.contains("1329598484,   // PANORAMA_WORK_MODE_SET"));
        assertTrue(owner.contains("PANORAMA_WORK_MODE_SET = 1306529812"));
        assertTrue(owner.contains("PANORAMA_WORK_MODE = 1329598484"));
        assertTrue(owner.contains("DEVICE_PANORAMA = 1031"));
    }

    @Test
    public void apiExposesMasterSwitchOnlyWithoutCameraModeGate() throws IOException {
        String api = read(
                "app/src/main/java/com/overdrive/app/server/SurveillanceApiHandler.java");
        String vehicle = read(
                "app/src/main/java/com/overdrive/app/server/VehicleControlApiHandler.java");
        assertTrue(api.contains("config.put(\"di5ParkedKeepAlive\","));
        // No capability flag: the row must not follow the camera-mode selection
        // (two DiLink 5 head-unit flavours) nor the di5CloudKeepAlive experiment.
        assertFalse(api.contains("di5ParkedKeepAliveSupported"));
        assertFalse(api.contains("config.put(\"di5ParkedKeepAliveMcuHold\""));
        assertFalse(api.contains("config.put(\"di5ParkedKeepAliveApHold\""));
        // POST persists without a platform check.
        int post = api.indexOf("if (configJson.has(\"di5ParkedKeepAlive\"))");
        assertTrue(post > 0);
        int postEnd = api.indexOf("if (configJson.has(\"lowSocCutoffPercent\"))", post);
        assertTrue(postEnd > post);
        String postBlock = api.substring(post, postEnd);
        assertFalse(postBlock.contains("DiLink5Platform"));
        assertFalse(postBlock.contains("only in DiLink 5 mode"));
        assertTrue(postBlock.contains("Failed to save DI5 parked keep-alive setting"));
        // The cloud experiment keeps its own rollout + DI5-mode gates; the
        // local parked master remains independent of both.
        assertTrue(api.contains(
                "boolean di5CloudKeepAlivePollerEnabled ="));
        assertTrue(api.contains(
                "boolean di5CloudKeepAliveSupported =\n"
                        + "                di5CloudKeepAlivePollerEnabled\n"
                        + "                        && com.overdrive.app.camera.dilink5.DiLink5Platform\n"
                        + "                                .isSelected();"));
        // Status endpoint reports context, not a capability verdict.
        assertTrue(vehicle.contains(
                "cleanPath.equals(\"/api/vehicle/di5-keepalive\") && method.equals(\"GET\")"));
        assertTrue(vehicle.contains("Di5ParkedPowerHold.DEFAULT_STATUS_PATH"));
        int status = vehicle.indexOf("private static void handleDi5KeepAliveStatus(");
        int statusEnd = vehicle.indexOf("public static boolean handle(", status);
        String statusBody = vehicle.substring(status, statusEnd);
        assertFalse(statusBody.contains("\"supported\""));
        assertTrue(statusBody.contains("response.put(\"cameraMode\","));
        assertTrue(statusBody.contains("response.put(\"dilink5CameraHardware\","));
    }

    @Test
    public void surveillanceUiMatchesImmediateToggleContractWithConfirmation()
            throws IOException {
        String html = read("app/src/main/assets/web/local/surveillance.html");
        String script = read("app/src/main/assets/web/shared/surveillance.js");
        String english = read("app/src/main/assets/web/i18n/en.json");
        String hebrew = read("app/src/main/assets/web/i18n/he.json");

        // Always visible (no display:none on the row): not tied to the camera-mode
        // selection nor to the DI5 cloud row. Hydration lock on the input stays.
        assertTrue(html.contains("id=\"survDi5ParkedKeepAliveRow\">"));
        assertFalse(html.contains(
                "id=\"survDi5ParkedKeepAliveRow\" style=\"display:none;\""));
        assertTrue(html.contains(
                "id=\"survDi5ParkedKeepAlive\" disabled "
                        + "onchange=\"SurvSettings.toggleDi5ParkedKeepAlive()\""));
        // The cloud row keeps its own capability gate; the two are independent.
        assertTrue(html.contains(
                "id=\"survDi5CloudKeepAliveRow\" style=\"display:none;\""));
        assertFalse(script.contains("di5ParkedKeepAliveSupported"));
        assertTrue(script.contains("toggleDi5ParkedKeepAlive()"));
        assertTrue(script.contains("_nextImmediateWrite('di5ParkedKeepAlive')"));
        assertTrue(script.contains("{ di5ParkedKeepAlive: on }"));
        assertTrue(script.contains("applyDi5ParkedKeepAliveUI()"));
        // Turning ON asks for consent; a missing dialog fails safe (not enabled).
        assertTrue(script.contains("surveillance.di5_parked_keepalive_confirm_title"));
        assertTrue(script.contains(
                "surveillance.di5_parked_keepalive_confirm_unavailable"));
        // Dimmed with the other post-OFF controls in onOnly mode.
        assertTrue(count(script, "'survDi5ParkedKeepAlive'") >= 2);
        for (String key : new String[]{
                "\"di5_parked_keepalive\"",
                "\"di5_parked_keepalive_desc\"",
                "\"di5_parked_keepalive_confirm_title\"",
                "\"di5_parked_keepalive_confirm_body\"",
                "\"di5_parked_keepalive_saved_on\"",
                "\"di5_parked_keepalive_saved_off\""}) {
            assertTrue("en.json missing " + key, english.contains(key));
            assertTrue("he.json missing " + key, hebrew.contains(key));
        }
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            n++;
            idx += needle.length();
        }
        return n;
    }

    private static String read(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            Path direct = current.resolve(relativePath);
            if (Files.isRegularFile(direct)) {
                return new String(
                        Files.readAllBytes(direct),
                        StandardCharsets.UTF_8);
            }
            Path fromModule =
                    current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(
                        Files.readAllBytes(fromModule),
                        StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate " + relativePath);
    }
}

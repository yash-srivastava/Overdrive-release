package com.overdrive.app.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/** Pins the Automations safety tab, API persistence, and shared guard wiring. */
public class AutomationSafetySettingsAssetTest {

    @Test
    public void safetyTabControlsEveryConfigurableDrivingGuard() throws IOException {
        String html = readRepositoryFile(
                "app/src/main/assets/web/local/automations.html");
        String script = readRepositoryFile(
                "app/src/main/assets/web/shared/automations.js");
        String styles = readRepositoryFile(
                "app/src/main/assets/web/shared/styles.css");
        String english = readRepositoryFile(
                "app/src/main/assets/web/i18n/en.json");
        String api = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/AutomationApiHandler.java");
        String guard = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/routing/DrivingSafetyGuard.java");
        String gearMonitor = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/monitor/GearMonitor.java");
        String positionsApi = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/PositionsApiHandler.java");
        String positionsScript = readRepositoryFile(
                "app/src/main/assets/web/shared/seat-positions.js");
        String collector = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/BydDataCollector.java");
        String actuator = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/services/VehicleActuatorService.java");
        String cloudClient = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/cloud/BydCloudClient.java");
        String seatDebug = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/SeatDebugApiHandler.java");

        assertTrue(html.contains("{ id: 'safety'"));
        assertTrue(html.contains("id=\"safetySettingsControls\" hidden"));
        assertTrue(html.contains("id=\"safetySettingsStatus\""));
        assertTrue(html.contains("id=\"safetySettingsStatusIcon\""));
        for (String key : new String[] {
                "doorLocks", "trunk", "mirrorFold", "positioning",
                "headlightOff", "displayBrightness", "displayPower", "screenMedia"
        }) {
            assertTrue(html.contains("data-safety-key=\"" + key + "\" checked"));
            assertTrue(guard.contains("\"" + key + "\""));
        }
        assertTrue(guard.contains("if (!gm.isRunning()) return GearReading.UNKNOWN;"));
        assertFalse(guard.contains("d.gearMode == GearMonitor.GEAR_P"));
        assertFalse(guard.contains("d.speedKmh"));
        assertTrue(guard.contains(
                "BydDataCollector.getInstance().readCurrentSpeedKmh();"));
        assertTrue(guard.contains(
                "SystemClock.elapsedRealtime() - gm.getLastUpdateTime()"));
        // The guard's freshness clock must come from the same published observation as the gear.
        assertTrue(gearMonitor.contains("private volatile GearSample observation"));
        assertTrue(gearMonitor.contains("public final int gear;"));
        assertTrue(gearMonitor.contains("public final long observedAtElapsedMs;"));
        assertTrue(Pattern.compile("gearObservedAtElapsedRealtimeMs\\s*=\\s*snap\\.gearReadElapsedRealtimeMs;")
                .matcher(gearMonitor).find());
        assertTrue(Pattern.compile("observation\\s*=\\s*new GearSample\\(gear,\\s*"
                        + "gearObservedAtElapsedRealtimeMs,\\s*CACHED_GEAR_MAX_AGE_MS\\)")
                .matcher(gearMonitor).find());
        assertTrue(Pattern.compile("long getLastUpdateTime\\(\\)\\s*\\{\\s*return observation\\.observedAtElapsedMs;")
                .matcher(gearMonitor).find());
        assertTrue(script.contains("saveSafetySetting(el)"));
        assertTrue(script.contains("data.success !== true"));
        assertTrue(script.contains("controls.hidden = true"));
        assertTrue(script.contains("Promise.race(["));
        assertTrue(script.contains("safety settings request timed out"));
        assertTrue(script.contains("automation.safety_unavailable"));
        assertTrue(script.contains("typeof BYD.i18n.t === 'function'"));
        assertTrue(script.contains("typeof safety[toggleKey] !== 'boolean'"));
        assertTrue(script.contains("toggles[i].checked = safety[toggleKey]"));
        assertTrue(script.contains("await this.loadSettings()"));
        assertTrue(script.contains("await BYD.utils.confirmDialog({"));
        assertTrue(script.contains("title: BYD.i18n.t('common.warning')"));
        assertTrue(script.contains("confirmLabel: BYD.i18n.t('common.disable')"));
        assertTrue(script.contains("cancelLabel: BYD.i18n.t('common.cancel')"));
        assertTrue(script.contains("statusIcon.hidden = false"));
        assertTrue(script.contains(": window.confirm(message);"));
        assertTrue(script.contains("automation.safety_disable_confirm"));
        assertTrue(script.contains(
                "for (let i = 0; i < toggles.length; i++) toggles[i].disabled = true"));
        assertTrue(script.contains(
                "for (let i = 0; i < toggles.length; i++) toggles[i].disabled = false"));
        assertTrue(script.contains("JSON.stringify({ drivingSafety: drivingSafety })"));
        assertTrue(styles.contains(
                ".info-box-note[hidden],\n"
                        + ".info-box-warning[hidden] {\n"
                        + "    display: none;"));
        assertTrue(api.contains("resp.put(\"drivingSafety\", DrivingSafetyGuard.getGuardSettings())"));
        assertTrue(api.contains("Unknown drivingSafety setting: "));
        assertTrue(api.contains("\"drivingSafety\", safetyUpdate"));
        assertTrue(english.contains("\"safety_title\": \"Driving safety guards\""));
        assertTrue(english.contains(
                "matching vehicle actions started through OverDrive's Automations, Action Groups, Key Mapping, Quick Controls, MQTT, Vehicle Controls, and saved seat positions"));
        assertTrue(english.contains(
                "Advanced raw shell, CAN, and diagnostic actions are outside these guards"));
        assertTrue(positionsApi.contains("target.put(\"positioningBlocked\""));
        assertTrue(positionsApi.contains("putGateState(cur)"));
        assertTrue(positionsApi.contains("BodyworkSeatProbe.applyFull(ctx, overrides)"));
        assertFalse(positionsApi.contains("param(q, body, \"force\")"));
        assertFalse(seatDebug.contains("q.get(\"force\")"));
        String bodyworkProbe = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/BodyworkSeatProbe.java");
        assertFalse(bodyworkProbe.contains("&& !force"));
        assertFalse(bodyworkProbe.contains("boolean force"));
        assertTrue(bodyworkProbe.contains(
                "movement gate became active while preparing the position"));
        assertTrue(bodyworkProbe.contains(
                "movement gate became active before the seat batch"));
        assertTrue(bodyworkProbe.contains(
                "movement gate became active at batch boundary"));
        assertTrue(collector.contains(
                "drivingSafetyBlocked(DrivingSafetyGuard.GUARD_DISPLAY_BRIGHTNESS)"));
        assertTrue(collector.contains(
                "drivingSafetyBlocked(DrivingSafetyGuard.GUARD_DISPLAY_POWER)"));
        assertTrue(collector.contains(
                "drivingSafetyBlocked(DrivingSafetyGuard.GUARD_MIRROR_FOLD)"));
        assertTrue(collector.contains(
                "drivingSafetyBlocked(DrivingSafetyGuard.GUARD_TRUNK)"));
        assertTrue(collector.contains(
                "drivingSafetyBlocked(DrivingSafetyGuard.GUARD_POSITIONING)"));
        assertTrue(collector.contains(
                "drivingSafetyBlocked(DrivingSafetyGuard.GUARD_HEADLIGHT_OFF)"));
        assertTrue(collector.contains(
                "setMirrorsFoldedOnSettingDevice(int value, boolean folded)"));
        assertTrue(collector.contains(
                "if (VehicleActuatorBridge.isDiLink5RequestExpired()) return false;\n"
                        + "            m.invoke(dev, level);"));
        assertTrue(collector.contains(
                "if (drivingSafetyBlocked(DrivingSafetyGuard.GUARD_DISPLAY_BRIGHTNESS)) return false;\n"
                        + "            Process p = new ProcessBuilder(\"sh\", \"-c\", "
                        + "com.overdrive.app.util.ScratchRoot.remap(script))"));
        assertTrue(collector.contains(
                "if (VehicleActuatorBridge.isDiLink5RequestExpired()) return false;\n"
                        + "                m.invoke(settingDevice);"));
        assertTrue(collector.contains(
                "if (drivingSafetyBlocked(DrivingSafetyGuard.GUARD_DISPLAY_POWER)) return false;\n"
                        + "            Process p = new ProcessBuilder(\"sh\", \"-c\", "
                        + "com.overdrive.app.util.ScratchRoot.remap(script))"));
        assertTrue(collector.contains(
                "if (VehicleActuatorBridge.isDiLink5RequestExpired()) return false;\n"
                        + "                Object r = m.invoke(bodyworkDevice, val);"));
        assertTrue(actuator.contains("isAppProcessActionBlocked(String guardKey)"));
        assertTrue(actuator.contains(
                "isAppProcessActionBlocked(\n"
                        + "                    DrivingSafetyGuard.GUARD_MIRROR_FOLD)"));
        assertTrue(actuator.contains(
                "isAppProcessActionBlocked(\n"
                        + "                DrivingSafetyGuard.GUARD_DISPLAY_BRIGHTNESS)"));
        assertTrue(actuator.contains(
                "isAppProcessActionBlocked(\n"
                        + "                DrivingSafetyGuard.GUARD_DISPLAY_POWER)"));
        assertTrue(cloudClient.contains(
                "String guardKey = drivingSafetyGuardForRemoteCommand(commandType);"));
        int guardCheck = cloudClient.indexOf(
                "String guardKey = drivingSafetyGuardForRemoteCommand(commandType);");
        Matcher remoteDispatch = Pattern.compile(
                "JSONObject\\s+response\\s*=\\s*transport\\.postSecure\\s*\\("
                        + "\\s*\"/control/remoteControl\"\\s*,\\s*env\\.outer"
                        + "\\s*,\\s*0L\\s*,\\s*authority\\s*\\)")
                .matcher(cloudClient);
        assertTrue("The guarded dispatch must carry its cancellation authority",
                remoteDispatch.find());
        assertTrue(guardCheck < remoteDispatch.start());
        assertTrue(cloudClient.contains(
                "if (Thread.currentThread().isInterrupted()) {\n"
                        + "            throw new IOException(\"remote command cancelled\");\n"
                        + "        }\n"
                        + "        JSONObject response = transport.postSecure"));
        assertTrue(cloudClient.contains(
                "return DrivingSafetyGuard.GUARD_DOOR_LOCKS;"));
        assertTrue(cloudClient.contains(
                "return DrivingSafetyGuard.GUARD_TRUNK;"));
        assertTrue(positionsScript.contains("!this.positioningBlocked"));
        assertTrue(positionsScript.contains("typeof j.positioningBlocked === 'boolean'"));
        assertTrue(positionsScript.contains("if (!this.acc || this.positioningBlocked) return;"));
        assertFalse(positionsScript.contains(
                "if (!this.acc || this.movementBlocked) return;"));
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}

package com.overdrive.app.monitor;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Prevents outbound charging surfaces from bypassing the resolved measured-kW publication. */
public class ChargingPowerDownstreamContractTest {

    @Test
    public void dedicatedPowerIsThePrimaryResolvedMeasuredSource() throws Exception {
        String collector = read("app/src/main/java/com/overdrive/app/byd/BydDataCollector.java");
        String monitor = read("app/src/main/java/com/overdrive/app/monitor/VehicleDataMonitor.java");

        assertBefore(collector,
                "callGetter(device, \"getChargingPower\")",
                "callGetter(device, \"getChargePower\")");
        assertBefore(monitor,
                "if (!Double.isNaN(devKw))",
                "} else if (!Double.isNaN(clusterKw))");
        assertTrue(monitor.contains(
                "devKw, \"chargingDevice\", vd.chargingPowerAtMs,\n"
                        + "                        ChargingStateData.PowerQuality.MEASURED, 1.0"));
        assertTrue(monitor.contains(
                "data.powerSource = powerSource;"));
    }

    @Test
    public void outboundFeedsUseResolvedChargingState() throws Exception {
        String mqtt = read("app/src/main/java/com/overdrive/app/mqtt/MqttConnectionManager.java");
        assertTrue(mqtt.contains("vehicleDataMonitor.getChargingSnapshot()"));
        assertTrue(mqtt.contains("chargingSnapshot.getVehicleData()"));
        assertTrue(mqtt.contains("chargingSnapshot.getChargingState()"));
        assertFalse(mqtt.contains("vehicleDataMonitor.getChargingState()"));
        String mqttPower = between(mqtt, "// charge_power", "// is_parked");
        assertTrue(mqttPower.contains("chargingState.chargingPowerKW"));
        assertTrue(mqttPower.contains("!chargingState.isEstimated"));
        assertFalse(mqttPower.contains("vd.chargingPowerKw"));
        assertTrue(mqtt.contains("getOpenChargingSessionTypeVerdict()"));
        assertTrue(mqtt.contains("ChargingTypeClassifier.classifyLive("));
        assertTrue(mqtt.contains("ChargingTypeClassifier.toBinaryFlag("));
        assertTrue(mqtt.contains("payload.put(\"is_dcfc\", JSONObject.NULL)"));
        String mqttCharging = between(mqtt, "// is_charging", "// is_dcfc");
        assertTrue(mqttCharging.contains("!chargingState.isEstimated"));
        assertTrue(mqtt.contains("getOpenChargingSessionEnergy()"));
        assertTrue(mqtt.contains("\"charging_capacity_incomplete\""));
        assertTrue(mqtt.contains("\"charging_capacity_estimated\""));
        assertTrue(mqtt.contains("\"charging_capacity_source\""));

        String abrp = read("app/src/main/java/com/overdrive/app/abrp/AbrpTelemetryService.java");
        String abrpPower = between(
                abrp, "static double selectTelemetryPower", "public JSONObject collectTelemetry");
        assertTrue(abrpPower.contains("!chargingState.isEstimated"));
        assertTrue(abrpPower.contains("return -chargingState.chargingPowerKW"));
        assertBefore(abrpPower,
                "return -chargingState.chargingPowerKW",
                "if (canPublishEnginePower");
        assertTrue(abrp.contains("getOpenChargingSessionTypeVerdict()"));
        assertTrue(abrp.contains("ChargingTypeClassifier.classifyLive("));
        assertTrue(abrp.contains("ChargingTypeClassifier.toBinaryFlag("));

        String chargingApi = read(
                "app/src/main/java/com/overdrive/app/charging/ChargingApiHandler.java");
        assertTrue(chargingApi.contains("normalizePowerPublication("));
        assertTrue(chargingApi.contains("state.chargingPowerKW"));
        assertTrue(chargingApi.contains("state.powerSource"));
        assertTrue(chargingApi.contains("sessionEnergyIncomplete"));
        assertTrue(chargingApi.contains("sessionEnergyEstimated"));
        assertTrue(chargingApi.contains("sessionEnergySource"));

        String launcher = read(
                "app/src/main/java/com/overdrive/app/server/LauncherApiHandler.java");
        assertTrue(launcher.contains(
                "VehicleDataMonitor.getInstance().getChargingState()"));
        assertTrue(launcher.contains("kw = cs.chargingPowerKW"));
        assertTrue(launcher.contains("if (cs.isEstimated)"));
        assertTrue(launcher.contains(
                "j.put(\"energyIncomplete\""));
        assertTrue(launcher.contains(
                "j.put(\"energyEstimated\""));
        assertTrue(launcher.contains(
                "j.put(\"energySource\""));
        assertTrue(launcher.contains(
                "j.put(\"dc\", s.has(\"isDc\")"));

        String ipc = read(
                "app/src/main/java/com/overdrive/app/server/SurveillanceIpcServer.java");
        assertTrue(ipc.contains("monitor.getChargingState()"));
        assertTrue(ipc.contains("json.put(\"chargingPowerKW\", data.chargingPowerKW)"));
        assertTrue(ipc.contains("json.put(\"isEstimated\", data.isEstimated)"));

        String notifier = read(
                "app/src/main/java/com/overdrive/app/notifications/ChargingEventNotifier.java");
        assertTrue(notifier.contains(
                "VehicleDataMonitor.getInstance().getChargingState()"));
        assertTrue(notifier.contains("!cs.isEstimated"));

        String database = read(
                "app/src/main/java/com/overdrive/app/monitor/SocHistoryDatabase.java");
        String report = between(database, "public synchronized JSONObject getFullReport",
                "public synchronized void setSohEstimator");
        assertTrue(report.contains("ChargingStateData chargingData = monitor.getChargingState()"));
        assertTrue(report.contains("chargingData.chargingPowerKW"));
        assertTrue(report.contains("chargingData != null && chargingData.isEstimated"));
        assertTrue(report.contains("!livePowerEstimated && isFinite(livePower)"));
        assertTrue(report.contains("livePower > 0 && livePower <= 500"));
        assertTrue(report.contains("livePoint.put(\"power\", JSONObject.NULL)"));
        assertTrue(report.contains("livePoint.put(\"powerEstimated\", livePowerEstimated)"));
        assertTrue(database.contains(
                "public synchronized OpenChargingSessionEnergy getOpenChargingSessionEnergy()"));
        assertTrue(database.contains(
                "o.put(\"energyIncomplete\", liveEnergy.incomplete)"));
        assertTrue(database.contains(
                "o.put(\"energyEstimated\", liveEnergy.estimated)"));
        assertTrue(database.contains(
                "out.put(\"energyEstimated\""));

        String insight = read(
                "app/src/main/java/com/overdrive/app/ui/dashboard/DashboardInsight.kt");
        assertTrue(insight.contains(
                "json.optBoolean(\"energyIncomplete\", false)"));
        assertTrue(insight.contains(
                "json.has(\"energyEstimated\")"));
        assertTrue(insight.contains(
                "(if (energyApproximate) \"~\" else \"\")"));
    }

    @Test
    public void historyAndEnergyAccountingUseMeasuredResolvedSamples() throws Exception {
        String sessions = read(
                "app/src/main/java/com/overdrive/app/charging/ChargingSessionManager.java");
        String sample = between(
                sessions, "private void sampleOnce", "public synchronized void onConfigChanged");
        assertTrue(sample.contains("double power = cs != null ? cs.chargingPowerKW"));
        assertTrue(sample.contains("cs.isEstimated"));
        assertTrue(sample.contains("!Double.isFinite(power)"));
        assertTrue(sample.contains("power > 500.0"));

        String database = read(
                "app/src/main/java/com/overdrive/app/monitor/SocHistoryDatabase.java");
        assertTrue(database.contains(
                "chargingData != null && !chargingData.isEstimated"));
        assertTrue(database.contains("chargingData.chargingPowerKW"));
        assertTrue(database.contains("SELECT AVG(power_kw)"));
        assertTrue(database.contains("isValidChargingSamplePower(powerKw)"));
    }

    private static void assertBefore(String text, String first, String second) {
        int firstAt = text.indexOf(first);
        int secondAt = text.indexOf(second);
        assertTrue("Missing first contract: " + first, firstAt >= 0);
        assertTrue("Missing second contract: " + second, secondAt >= 0);
        assertTrue(first + " must precede " + second, firstAt < secondAt);
    }

    private static String between(String text, String start, String end) {
        int startAt = text.indexOf(start);
        int endAt = text.indexOf(end, startAt + Math.max(0, start.length()));
        assertTrue("Missing block start: " + start, startAt >= 0);
        assertTrue("Missing block end: " + end, endAt > startAt);
        return text.substring(startAt, endAt);
    }

    private static String read(String relativePath) throws Exception {
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

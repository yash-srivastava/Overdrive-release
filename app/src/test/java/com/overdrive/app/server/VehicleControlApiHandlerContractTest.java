package com.overdrive.app.server;

import com.overdrive.app.byd.BydVehicleData;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Contract checks for physical-control input validation that cannot use a live vehicle SDK. */
public class VehicleControlApiHandlerContractTest {

    @Test
    public void lightStateOmitsUnknownDiLink5ValuesButKeepsLegacyShape()
            throws Exception {
        BydVehicleData data = new BydVehicleData.Builder()
                .lowBeam(false)
                .highBeam(true)
                .hazard(true)
                .dayTimeLight(true)
                .lightKnownMask(BydVehicleData.LIGHT_KNOWN_LOW_BEAM)
                .build();

        org.json.JSONObject diLink5 = new org.json.JSONObject();
        VehicleControlApiHandler.putLightState(diLink5, data, true);
        assertTrue(diLink5.has("lowBeam"));
        assertFalse(diLink5.getBoolean("lowBeam"));
        assertFalse(diLink5.has("highBeam"));
        assertFalse(diLink5.has("hazard"));
        assertFalse(diLink5.has("dayTimeLight"));

        org.json.JSONObject legacy = new org.json.JSONObject();
        VehicleControlApiHandler.putLightState(legacy, data, false);
        assertTrue(legacy.has("lowBeam"));
        assertTrue(legacy.has("highBeam"));
        assertTrue(legacy.has("hazard"));
        assertTrue(legacy.has("dayTimeLight"));
    }

    @Test
    public void physicalControlEndpointsUseStrictBooleanAndSeatStateValidation() throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/VehicleControlApiHandler.java");

        assertTrue(source.contains("private static Boolean jsonBoolean"));
        assertTrue(source.contains("enabled must be boolean"));
        assertTrue(source.contains("seat climate state must include all four levels (0-2)"));
        assertTrue(source.contains("hasFreshCompleteSeatState"));
        assertTrue(source.contains("snap.seatClimateAtMs"));
        assertTrue(source.contains("boolean localSeatStateFresh = hasFreshCompleteSeatState(snap);"));
        assertTrue(source.contains("seatStateWithTarget(snap.seatHeat, snap.seatCool,"));
        assertTrue(source.contains(
                "v == BydVehicleData.UNAVAILABLE ? JSONObject.NULL : v"));
        assertTrue(source.contains("cloudSeatState[0], cloudSeatState[1]"));
        assertTrue(source.contains("cloudSeatState[2], cloudSeatState[3]"));
        assertTrue(source.contains("cloudSeatState[2], cloudSeatState[3], true,"));
        assertTrue(source.contains("localSeatStateFresh ? snap.seatClimateAtMs : 0L"));
        assertFalse(source.contains("complete current seat state is required for cloud fallback"));
        assertTrue(source.contains("new VehicleCommandRouter.SeatHeatCommand(position, level,"));
        assertTrue(source.contains("new VehicleCommandRouter.SeatVentCommand(position, level,"));
        assertTrue(source.contains("laneAssist mode must be an integer from 0 to 3"));
        assertTrue(source.contains("childPresenceDetection value must be 1, 2, or 3"));
        assertTrue(source.contains(
                "if (data.childPresenceDetection >= 1"));
        assertTrue(source.contains(
                "&& data.childPresenceDetection <= 3)"));
        assertTrue(source.contains("new VehicleCommandRouter.BatteryHeatCommand(enabled.booleanValue())"));
        assertFalse(source.contains("req.optBoolean(\"enable\", true)"));
        assertFalse(source.contains("new JSONObject(body).optBoolean(\"enabled\", false)"));

        String collector = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/BydDataCollector.java");
        assertTrue(collector.contains(
                "java.util.Arrays.fill(seatHeat, BydVehicleData.UNAVAILABLE)"));
        assertTrue(collector.contains(
                "java.util.Arrays.fill(seatCool, BydVehicleData.UNAVAILABLE)"));
        assertFalse(collector.contains(
                "getMethod(\"resetSeatParams\", int.class, int.class)"));
        assertTrue(collector.contains(
                "isAccStateFreshForSafety()"));
        assertTrue(collector.contains(
                "VehicleActuatorBridge.currentDiLink5RequestAccOn()"));
        assertTrue(collector.contains(
                "BydFeatureIds.SETTING_LF_MEMORY_LOCATION_WAKE_SET, position"));
        assertTrue(collector.contains(
                "BydDeviceHelper.sendSetCommandIntegerResult("));
        assertTrue(collector.contains("result instanceof Integer"));
        assertTrue(collector.contains(
                "getDriverSeatVentilating2aaConfig"));
        assertTrue(collector.contains(
                "getPassengerSeatVentilating2aaConfig"));
        assertTrue(source.contains("ventilatedSupportedBySeat"));
        assertTrue(collector.contains(
                "current.seatVentilationSupport[position - 1]"));

        String mqttCatalog = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/mqtt/VehicleControlCatalog.java");
        assertTrue(mqttCatalog.contains(
                "snap.childPresenceDetection < 1"));
        assertTrue(mqttCatalog.contains(
                "snap.childPresenceDetection > 3"));

        String vehicleControl = readRepositoryFile(
                "app/src/main/assets/web/shared/vehicle-control.js");
        assertTrue(vehicleControl.contains(
                "var seatSupport = data.seats.ventilatedSupportedBySeat"));
        assertTrue(vehicleControl.contains("seatSupport[ci] === false"));

        String vehicleData = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/BydVehicleData.java");
        assertTrue(vehicleData.contains(
                "sh.put(s == UNAVAILABLE ? JSONObject.NULL : (Object) s)"));
        assertTrue(vehicleData.contains(
                "sc.put(s == UNAVAILABLE ? JSONObject.NULL : (Object) s)"));

        String mqtt = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/mqtt/MqttConnectionManager.java");
        assertTrue(mqtt.contains(
                "a.put(s == BydVehicleData.UNAVAILABLE"));
    }

    @Test
    public void actuatorValidationUsesStrictTypesAndPreflightScheduleChecks() throws Exception {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/VehicleControlApiHandler.java");

        assertTrue(source.contains("Integer areaValue = jsonInteger(req, \"area\")"));
        assertTrue(source.contains("Integer targetValue = jsonInteger(req, \"targetPercent\")"));
        assertTrue(source.contains("Integer commandValue = jsonInteger(req, \"command\")"));
        assertFalse(source.contains("req.getInt(\"area\")"));
        assertFalse(source.contains("req.getInt(\"targetPercent\")"));
        assertFalse(source.contains("req.getInt(\"command\")"));
        assertTrue(source.contains("Double suppliedTemp = jsonNumber(req, \"temp\")"));
        assertTrue(source.contains("zone must be an integer from 0 to 2"));
        assertTrue(source.contains("fan must be an integer from 1 to 7"));
        assertTrue(source.contains("autoOffMinutes must be an integer from 0 to "));
        assertTrue(source.contains("enabled must be boolean"));
        assertTrue(source.contains("isValidChargingTime(start, false)"));
        assertTrue(source.contains("isValidChargeWay(way)"));
        assertTrue(source.contains("Integer requestedPercent = hasPercent ? jsonInteger(req, \"percent\") : null"));
        assertTrue(source.contains("setChargeCapPercentAndEnabledWithResult(percent, enabled)"));
        assertTrue(source.contains("\"/api/vehicle/ac-charge-current-limit\""));
        assertTrue(source.contains("state must be an integer from 1 to 5"));
        assertTrue(source.contains("new VehicleCommandRouter.AcChargeCurrentLimitCommand("));
        assertTrue(source.contains("collector.getAcChargingCurrentLimitStatus()"));
        assertTrue(source.contains("response.put(\"available\", status.available)"));
        assertTrue(source.contains("status.supported != null"));
        assertTrue(source.contains("Messages.get(\"vehicle_data_unavailable\")"));
        assertTrue(source.contains("commandSucceeded && !readBackAvailable"));
        String collector = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/BydDataCollector.java");
        String bridge = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/VehicleActuatorBridge.java");
        String actuator = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/services/VehicleActuatorService.java");
        assertTrue(collector.contains(
                "VehicleActuatorBridge.dispatchAcChargeCurrentLimit(state)"));
        assertTrue(collector.contains("public synchronized boolean setAcChargingCurrentLimitState"));
        assertTrue(collector.contains("boolean confirmed = readBack == state"));
        assertTrue(bridge.contains("--es action ac_charge_current_limit"));
        assertTrue(bridge.contains("--es state \" + state"));
        assertTrue(actuator.contains("\"ac_charge_current_limit\".equals(action)"));
        assertTrue(actuator.contains("setAcChargeCurrentLimit(state)"));
        assertTrue(actuator.contains(
                "BydDeviceHelper.withBydPermissionBypass(getApplicationContext())"));
        assertTrue(actuator.contains(
                "BydFeatureIds.SETTING_AC_CHARGING_CURRENT_LIMIT_STATUS_SET"));
        assertTrue(actuator.contains(
                "BydFeatureIds.SETTING_AC_CHARGING_CURRENT_LIMIT_STATUS"));
        assertTrue(actuator.contains(
                "resolveAcChargingCurrentLimitSupport("));
        assertTrue(actuator.contains(
                "AC charge current limit read side did not prove capability"));
        assertTrue(source.contains("boolean confirmed = readBack == state.intValue()"));
        assertTrue(source.contains("routed.put(\"success\", confirmed)"));
        assertTrue(source.contains("routed.put(\"commandSuccess\", confirmed)"));
        assertTrue(source.contains("if (hasEnabled && hasPercent)"));
        assertTrue(source.contains("resp.put(\"partialApplied\", combinedResult.partiallyApplied("));
        assertTrue(source.contains("boolean verified = Boolean.TRUE.equals(supported)"));
        assertTrue(source.contains("resp.put(\"requestedEnabled\", enabled)"));
        assertTrue(source.contains("resp.put(\"requestedStartChargeTime\", start)"));
        assertTrue(source.contains("\"/api/vehicle/climate-schedule\""));
        assertTrue(source.contains("new VehicleCommandRouter.ClimateScheduleCommand("));
        assertTrue(source.contains("bookingTime must be an epoch-second time"));
        assertTrue(source.contains("handleGetClimateSchedule"));
        assertTrue(source.contains("emptyBookingsMayBeStale"));
        assertTrue(source.contains("bookingIdsAsDecimalStrings(bookings)"));
        assertTrue(source.contains("resp.put(\"bookingId\", String.valueOf(bookingId.longValue()))"));
        assertTrue(source.contains(
                "resp.put(\"requestedBookingId\", String.valueOf(bookingId.longValue()))"));
        assertTrue(source.contains("new VehicleCommandRouter.VentAllWindowsCommand()"));
        assertTrue(source.contains("ambientColour zone must be front, rear, or both"));
        assertTrue(source.contains("volume_step value must be -1 or 1"));
        assertTrue(source.contains("value must be an integer"));
        assertTrue(source.contains("String volumeChannel = null;"));
        assertTrue(source.contains("volumeChannel = optionalVolumeChannel(req);"));
        assertTrue(source.contains("volume channel must be media, navigation, voice, phone, call, system, alarm, or ring"));
        assertTrue(source.contains("String zone = optionalAmbientZone(req)"));
        assertTrue(source.contains("climate.put(\"remoteClimateActive\", remoteClimateActive.booleanValue())"));
        assertTrue(source.contains("(diLink5 || vehiclePoweredOn) && data.acStartState == 1"));
        assertTrue(source.contains(
                "if (data.tempUnit != BydVehicleData.UNAVAILABLE)"));
        assertTrue(source.contains(
                "data.acSetpointDriver != BydVehicleData.UNAVAILABLE\n"
                        + "                && (diLink5 || vehiclePoweredOn)"));
        assertTrue(source.contains(
                "data.acSetpointPassenger != BydVehicleData.UNAVAILABLE\n"
                        + "                && (diLink5 || vehiclePoweredOn)"));
        assertTrue(source.contains("data.tyrePressureState[i] != BydVehicleData.UNAVAILABLE"));
        assertTrue(source.contains("tyres.put(\"available\", anyTyrePressure)"));

        assertTrue((Boolean) invokePrivate("isValidChargingTime",
                new Class<?>[] {String.class, boolean.class}, "00:00", false));
        assertTrue((Boolean) invokePrivate("isValidChargingTime",
                new Class<?>[] {String.class, boolean.class}, "full", true));
        assertFalse((Boolean) invokePrivate("isValidChargingTime",
                new Class<?>[] {String.class, boolean.class}, "24:00", false));
        assertFalse((Boolean) invokePrivate("isValidChargingTime",
                new Class<?>[] {String.class, boolean.class}, "12:60", false));
        assertTrue((Boolean) invokePrivate("isValidChargeWay",
                new Class<?>[] {String.class}, "0,2,6"));
        assertFalse((Boolean) invokePrivate("isValidChargeWay",
                new Class<?>[] {String.class}, "0,0"));
        assertFalse((Boolean) invokePrivate("isValidChargeWay",
                new Class<?>[] {String.class}, "0,7"));
        assertTrue((Boolean) invokePrivate("isValidAmbientZone",
                new Class<?>[] {String.class}, "front"));
        assertTrue((Boolean) invokePrivate("isValidAmbientZone",
                new Class<?>[] {String.class}, "both"));
        assertFalse((Boolean) invokePrivate("isValidAmbientZone",
                new Class<?>[] {String.class}, "all"));
    }

    @Test
    public void strictNumericHelpersDoNotCoerceInvalidActuatorValues() throws Exception {
        org.json.JSONObject request = new org.json.JSONObject()
                .put("integer", 3.5)
                .put("number", "22")
                .put("boolean", "true");

        assertNull(invokePrivate("jsonInteger", new Class<?>[] {
                org.json.JSONObject.class, String.class}, request, "integer"));
        assertNull(invokePrivate("jsonNumber", new Class<?>[] {
                org.json.JSONObject.class, String.class}, request, "number"));
        assertNull(invokePrivate("jsonBoolean", new Class<?>[] {
                org.json.JSONObject.class, String.class}, request, "boolean"));
        request.put("integer", 3);
        assertEquals(Integer.valueOf(3), invokePrivate("jsonInteger", new Class<?>[] {
                org.json.JSONObject.class, String.class}, request, "integer"));
        org.json.JSONObject bookingRequest = new org.json.JSONObject(
                "{\"bookingId\":1216038691305533440}");
        assertEquals(Long.valueOf(1_216_038_691_305_533_440L),
                invokePrivate("jsonLong", new Class<?>[] {
                        org.json.JSONObject.class, String.class}, bookingRequest, "bookingId"));
        org.json.JSONObject bookingIdText = new org.json.JSONObject(
                "{\"bookingId\":\"1216038691305533440\"}");
        assertEquals(Long.valueOf(1_216_038_691_305_533_440L),
                invokePrivate("jsonLong", new Class<?>[] {
                        org.json.JSONObject.class, String.class}, bookingIdText, "bookingId"));
        bookingIdText.put("bookingId", "1216038691305533440.0");
        assertNull(invokePrivate("jsonLong", new Class<?>[] {
                org.json.JSONObject.class, String.class}, bookingIdText, "bookingId"));
        org.json.JSONObject bookingList = new org.json.JSONObject()
                .put("listInfo", new org.json.JSONArray().put(new org.json.JSONObject()
                        .put("bookingId", 1_216_038_691_305_533_440L)));
        org.json.JSONObject losslessBookingList = (org.json.JSONObject) invokePrivate(
                "bookingIdsAsDecimalStrings", new Class<?>[] {org.json.JSONObject.class},
                bookingList);
        Object returnedBookingId = losslessBookingList.getJSONArray("listInfo")
                .getJSONObject(0).get("bookingId");
        assertTrue(returnedBookingId instanceof String);
        assertEquals("1216038691305533440", returnedBookingId);
        request.put("zone", "all");
        assertNull(invokePrivate("optionalAmbientZone", new Class<?>[] {
                org.json.JSONObject.class}, request));
        request.remove("zone");
        assertEquals("both", invokePrivate("optionalAmbientZone", new Class<?>[] {
                org.json.JSONObject.class}, request));
        assertTrue((Boolean) invokePrivate("isValidRemoteClimateDuration",
                new Class<?>[] {int.class}, 20));
        assertFalse((Boolean) invokePrivate("isValidRemoteClimateDuration",
                new Class<?>[] {int.class}, 12));
        assertEquals(22, invokePrivate(
                "climateSetpointToCelsius",
                new Class<?>[] {int.class}, 72));
        assertEquals(22, invokePrivate(
                "climateSetpointToCelsius",
                new Class<?>[] {int.class}, 22));
    }

    @Test
    public void seatFallbackSeedsFromCollectorOnlyWhenFreshAndVolumeChannelsAreStrict()
            throws Exception {
        org.json.JSONObject request = new org.json.JSONObject()
                .put("driverHeat", 2)
                .put("driverVent", 0)
                .put("passengerHeat", 1)
                .put("passengerVent", 0);
        assertEquals(Integer.valueOf(2), invokePrivate("optionalSeatLevel", new Class<?>[] {
                org.json.JSONObject.class, String.class}, request, "driverHeat"));
        request.put("driverHeat", 2.5);
        assertNull(invokePrivate("optionalSeatLevel", new Class<?>[] {
                org.json.JSONObject.class, String.class}, request, "driverHeat"));

        assertTrue((Boolean) invokePrivate("isValidVolumeChannel",
                new Class<?>[] {String.class}, "media"));
        assertTrue((Boolean) invokePrivate("isValidVolumeChannel",
                new Class<?>[] {String.class}, "navigation"));
        assertTrue((Boolean) invokePrivate("isValidVolumeChannel",
                new Class<?>[] {String.class}, "call"));
        assertFalse((Boolean) invokePrivate("isValidVolumeChannel",
                new Class<?>[] {String.class}, "unknown"));
        assertFalse((Boolean) invokePrivate("isValidVolumeChannel",
                new Class<?>[] {String.class}, "MEDIA"));

        org.json.JSONObject volumeRequest = new org.json.JSONObject();
        assertEquals("media", invokePrivate("optionalVolumeChannel", new Class<?>[] {
                org.json.JSONObject.class}, volumeRequest));
        volumeRequest.put("channel", "phone");
        assertEquals("phone", invokePrivate("optionalVolumeChannel", new Class<?>[] {
                org.json.JSONObject.class}, volumeRequest));
        volumeRequest.put("channel", 3);
        assertNull(invokePrivate("optionalVolumeChannel", new Class<?>[] {
                org.json.JSONObject.class}, volumeRequest));
    }

    @Test
    public void drivingGateTargetsOnlySafetyRelevantDisplays() throws Exception {
        Class<?>[] argument = { String.class };
        assertEquals("displayBrightness", invokePrivate(
                "drivingSafetyGuardForDisplayTarget", argument, "brightness"));
        assertEquals("displayBrightness", invokePrivate(
                "drivingSafetyGuardForDisplayTarget", argument, "cluster_brightness"));
        assertEquals("displayBrightness", invokePrivate(
                "drivingSafetyGuardForDisplayTarget", argument, "hud_brightness"));
        assertEquals("displayPower", invokePrivate(
                "drivingSafetyGuardForDisplayTarget", argument, "hud_power"));
        assertEquals("displayPower", invokePrivate(
                "drivingSafetyGuardForDisplayTarget", argument, "screen_power"));
        for (String target : new String[] {
                "volume", "volume_step", "media_key", "ambient_brightness", "ambient_power", null
        }) {
            assertNull(invokePrivate(
                    "drivingSafetyGuardForDisplayTarget", argument, target));
        }

        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/VehicleControlApiHandler.java");
        assertTrue(source.contains("displaySafetyGuard != null\n"
                + "                    && !(isHudPower && value > 0)\n"
                + "                    && DrivingSafetyGuard.isActionBlocked(displaySafetyGuard)"));
        assertTrue(source.contains("DrivingSafetyGuard.GUARD_SCREEN_MEDIA"));
        assertTrue(source.contains("Messages.get(\"vehicle_control.blocked_driving\")"));
        assertFalse(source.contains("boolean screenTarget = !isVolume"));

        String guard = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/byd/routing/DrivingSafetyGuard.java");
        String service = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/services/VehicleActuatorService.java");
        String activity = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/VideoPlaybackActivity.java");
        String manifest = readRepositoryFile("app/src/main/AndroidManifest.xml");
        assertTrue(source.contains("\"/api/vehicle/driving-safety/\""));
        assertTrue(guard.contains("isActionBlockedViaDaemon(String key)"));
        assertTrue(service.contains("submitGuardedActuation("));
        assertTrue(service.contains("DrivingSafetyGuard.GUARD_MIRROR_FOLD"));
        assertTrue(service.contains("DrivingSafetyGuard.GUARD_DISPLAY_BRIGHTNESS"));
        assertTrue(service.contains("DrivingSafetyGuard.GUARD_DISPLAY_POWER"));
        assertTrue(service.contains("DrivingSafetyGuard.isActionBlockedViaDaemon(guardKey)"));
        assertTrue(activity.contains("requestSafeStart(Intent intent)"));
        assertTrue(activity.contains("startSafetyMonitor()"));
        assertTrue(activity.contains("blocked fullscreen media before prepared playback"));
        assertTrue(activity.contains("generation != safetyRequestGeneration"));
        assertTrue(activity.contains(
                "DrivingSafetyGuard.isActionBlockedViaDaemon(\n"
                        + "                                DrivingSafetyGuard.GUARD_SCREEN_MEDIA)"));
        assertTrue(activity.contains("if (executor == null || executor.isShutdown())"));
        assertTrue(activity.contains("catch (RuntimeException unavailable)"));
        assertTrue(activity.contains(
                "DrivingSafetyGuard.isActionBlockedViaDaemon("));
        int activityStart = manifest.indexOf(
                "android:name=\"com.overdrive.app.ui.VideoPlaybackActivity\"");
        int activityEnd = manifest.indexOf("/>", activityStart);
        assertTrue(activityStart >= 0 && activityEnd > activityStart);
        assertTrue(manifest.substring(activityStart, activityEnd).contains(
                "android:permission=\"android.permission.DUMP\""));
    }

    @Test
    public void seatCloudFallbackOverlaysOnlyItsRequestedField() throws Exception {
        int[] heat = { 1, 2 };
        int[] cool = { 2, 1 };

        assertArrayEquals(new int[] { 2, 2, 2, 1 }, (int[]) invokePrivate(
                "seatStateWithTarget",
                new Class<?>[] { int[].class, int[].class, boolean.class, int.class, int.class },
                heat, cool, false, 1, 2));
        assertArrayEquals(new int[] { 1, 2, 2, 0 }, (int[]) invokePrivate(
                "seatStateWithTarget",
                new Class<?>[] { int[].class, int[].class, boolean.class, int.class, int.class },
                heat, cool, true, 2, 0));
    }

    @Test
    public void bydDoorLockValuesAreMappedOnlyForDiLink5Telemetry() throws Exception {
        assertEquals(1, invokePrivate(
                "cloudLockToApi", new Class<?>[] {int.class}, 2));
        assertEquals(2, invokePrivate(
                "cloudLockToApi", new Class<?>[] {int.class}, 1));
        assertEquals(-1, invokePrivate(
                "cloudLockToApi", new Class<?>[] {int.class}, 0));
        assertEquals(2, invokePrivate(
                "localLockToApi", new Class<?>[] {int.class, boolean.class}, 2, false));
        assertEquals(1, invokePrivate(
                "localLockToApi", new Class<?>[] {int.class, boolean.class}, 2, true));

        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/VehicleControlApiHandler.java");
        assertTrue(source.contains(
                "doors.put(\"rf\", localLockToApi(data.doorLockStatus[0], diLink5))"));
        assertTrue(source.contains(
                "localLockToApi(data.doorLockStatus[6], diLink5)"));
        assertTrue(source.contains(
                "if (mappedOverall == 1 || mappedOverall == 2)"));

        String launcher = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/LauncherApiHandler.java");
        assertTrue(launcher.contains("VehicleControlApiHandler.localLockToApi("));
        assertTrue(launcher.contains("if (overall == 1) locked = Boolean.TRUE"));
        assertTrue(launcher.contains("else if (overall == 2) locked = Boolean.FALSE"));
    }

    @Test
    public void windowStatusUsesPreciseLocalDataBeforeCoarseFallbacks() {
        assertEquals(0, VehicleControlApiHandler.resolveWindowOpenState(0, 1, 2));
        assertEquals(1, VehicleControlApiHandler.resolveWindowOpenState(37, 0, 1));
        assertEquals(1, VehicleControlApiHandler.resolveWindowOpenState(-1, 0, 2));
        assertEquals(1, VehicleControlApiHandler.resolveWindowOpenState(-1, 1, 1));
        assertEquals(0, VehicleControlApiHandler.resolveWindowOpenState(-1, -1, 1));
        assertEquals(1, VehicleControlApiHandler.resolveWindowOpenState(-1, -1, 2));
        assertEquals(0, VehicleControlApiHandler.resolveWindowOpenState(-1, 0, -1));
        assertEquals(-1, VehicleControlApiHandler.resolveWindowOpenState(-1, 255, 255));
    }

    @Test
    public void diLink5ProjectionCannotFallThroughToLegacyCast()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/"
                        + "VehicleControlApiHandler.java");
        String ui = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/"
                        + "ProjectionFragment.kt");

        assertTrue(source.contains(
                "hasDiLink5CameraHardware()"));
        // Cast routing follows the CLUSTER TRANSPORT (DI5 firmware / config /
        // dilink5 mode); the "switch to DiLink 5 mode" nag only applies when
        // the transport ALSO stayed legacy — otherwise DiLink 5.0 units
        // without the QCarCam library could never cast.
        assertTrue(source.contains(
                "com.overdrive.app.camera.dilink5.ClusterTransport.isDiLink5();"));
        assertTrue(source.contains(
                "boolean modeRequired =\n"
                        + "                    installed && diLink5Hardware"
                        + " && !diLink5Mode && !diLink5;"));
        assertTrue(source.contains(
                "reason = \"dilink5_mode_required\";"));
        assertTrue(source.contains(
                "response.put(\"vehicleMode\""));
        assertTrue(source.contains(
                "response.put(\"dilink5Hardware\""));
        assertTrue(source.contains(
                "response.put(\"clusterTransport\""));
        // The mode-required veto must keep vetoing the legacy fall-through.
        assertTrue(source.contains("if (installed && !modeRequired)"));
        assertTrue(ui.contains(
                "\"dilink5_mode_required\""));
        assertTrue(ui.contains(
                "projection_dilink5_mode_required"));
        assertTrue(ui.contains(
                "!casting && !diLink5ModeRequired"));
    }

    /**
     * The Projection screen's transport selector rides a dedicated endpoint
     * that (a) validates against the ClusterTransport vocabulary, (b) always
     * persists, and (c) live-applies ONLY while no cast / cluster map / legacy
     * projection is active — flipping the routing under a live session would
     * split its start and stop across two different transports.
     */
    @Test
    public void clusterTransportOverrideIsValidatedAndIdleGated()
            throws IOException {
        String source = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/server/"
                        + "VehicleControlApiHandler.java");
        assertTrue(source.contains(
                "cleanPath.equals(\"/api/vehicle/cluster-transport\")"));
        int handler = source.indexOf(
                "private static void handleClusterTransport(");
        assertTrue(handler >= 0);
        String body = source.substring(handler,
                source.indexOf("private static void handleClusterStop(", handler));
        assertTrue(body.contains(".normalizeConfigured(req.optString(\"transport\", \"\"))"));
        assertTrue(body.contains("transport must be auto, legacy or dilink5"));
        assertTrue(body.contains("ClusterCast.isActive()"));
        assertTrue(body.contains("ClusterMapProjector.isActive()"));
        assertTrue(body.contains("isProjectionIdleStatic()"));
        assertTrue(body.contains("response.put(\"restartRequired\", busy)"));
        assertTrue(body.contains("response.put(\"applied\", !busy)"));
        // The persist must precede the live-apply decision so a busy cluster
        // still records the choice for the next restart.
        int persist = body.indexOf("UnifiedConfigManager.updateValues(");
        int busyCheck = body.indexOf("ClusterCast.isActive()");
        assertTrue(persist >= 0 && busyCheck > persist);

        String ui = readRepositoryFile(
                "app/src/main/java/com/overdrive/app/ui/fragment/"
                        + "ProjectionFragment.kt");
        assertTrue(ui.contains("/api/vehicle/cluster-transport"));
        assertTrue(ui.contains("projectionTransportGroup"));
        assertTrue(ui.contains("transportWriteInFlight"));
        assertTrue(ui.contains("projection_transport_restart_required"));
    }

    private static Object invokePrivate(String name, Class<?>[] parameterTypes, Object... args)
            throws Exception {
        Method method = VehicleControlApiHandler.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(null, args);
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

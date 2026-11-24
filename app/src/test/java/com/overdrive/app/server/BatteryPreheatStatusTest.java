package com.overdrive.app.server;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.byd.cloud.VehicleCloudSnapshot;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BatteryPreheatStatusTest {
    private static JSONObject read(BydVehicleData data, VehicleCloudSnapshot cloud, long now)
            throws Exception {
        JSONObject result = new JSONObject();
        VehicleControlApiHandler.putBatteryHeatStatus(result, data, cloud, now);
        return result;
    }

    @Test public void localFeedbackSurvivesBridgeWithItsOriginalAgeAndDistinguishesCompletion()
            throws Exception {
        long observed = System.currentTimeMillis() - 1000L;
        VehicleCloudSnapshot cloud = VehicleCloudSnapshot.fromVehicleInfo(
                new JSONObject().put("batteryHeatState", 1).put("time", observed)).build();
        String[] expected = {"off", "on", "completed"};
        for (int raw = 0; raw < expected.length; raw++) {
            BydVehicleData local = new BydVehicleData.Builder().batteryPreheat(raw, observed)
                    .build().toBuilder().speedKmh(0).build();
            BydVehicleData bridged = BydVehicleData.fromJson(local.toBridgeJson());
            JSONObject result = read(bridged, cloud, observed + 1000L);
            assertEquals(expected[raw], result.getString("batteryHeatStatus"));
            assertEquals("local", result.getString("batteryHeatSource"));
            assertEquals(observed, result.getLong("batteryHeatObservedAtMs"));
            assertEquals(raw == 1, result.getBoolean("batteryHeat"));
        }
    }

    @Test public void staleOrUnavailableLocalReadingFallsBackToCloudWireDomain() throws Exception {
        long now = System.currentTimeMillis() - 100L;
        VehicleCloudSnapshot cloud = VehicleCloudSnapshot.fromVehicleInfo(new JSONObject()
                .put("time", now).put("statusNow", new JSONObject().put("batteryHeatState", 2))).build();
        BydVehicleData stale = new BydVehicleData.Builder()
                .batteryPreheat(0, now - 2 * BydDataCollector.POLL_INTERVAL_PARKED_MS).build();
        for (BydVehicleData local : new BydVehicleData[]{stale, null,
                new BydVehicleData.Builder().batteryPreheat(65535, now).build()}) {
            JSONObject result = read(local, cloud, now);
            assertEquals("on", result.getString("batteryHeatStatus"));
            assertTrue(result.getBoolean("batteryHeat"));
            assertEquals("cloud", result.getString("batteryHeatSource"));
            assertEquals(now, result.getLong("batteryHeatObservedAtMs"));
        }
    }

    @Test public void staleCloudObservationOrAnOlderBridgeWithoutAgeIsUnknown() throws Exception {
        long now = System.currentTimeMillis();
        JSONObject vi = new JSONObject().put("time", now);
        JSONObject hvac = new JSONObject().put("batteryHeatState", 1)
                .put("time", now - VehicleCloudSnapshot.TELEMETRY_MAX_AGE_MS);
        BydVehicleData old = BydVehicleData.fromJson(new JSONObject().put("charging",
                new JSONObject().put("batteryPreheatState", 2)));
        JSONObject result = read(old, VehicleCloudSnapshot.fromVehicleInfo(vi, hvac).build(), now);
        assertEquals("unknown", result.getString("batteryHeatStatus"));
        assertEquals("unknown", result.getString("batteryHeatSource"));
        assertEquals(0L, result.getLong("batteryHeatObservedAtMs"));
        assertFalse(result.has("batteryHeat"));
    }

    @Test public void freshestCloudPayloadWinsInEitherOrder() throws Exception {
        long now = System.currentTimeMillis() - 100L;
        JSONObject older = new JSONObject().put("time", now - 600_000L).put("batteryHeatState", 1);
        JSONObject newer = new JSONObject().put("time", now)
                .put("statusNow", new JSONObject().put("batteryHeatState", 0));
        for (VehicleCloudSnapshot cloud : new VehicleCloudSnapshot[]{
                VehicleCloudSnapshot.fromVehicleInfo(older, newer).build(),
                VehicleCloudSnapshot.fromVehicleInfo(newer, older).build()}) {
            JSONObject result = read(null, cloud, now);
            assertEquals("off", result.getString("batteryHeatStatus"));
            assertEquals(now, result.getLong("batteryHeatObservedAtMs"));
        }
    }

    @Test public void oldVehicleTimeOrUndatedHvacCannotConfirmNewHeating() throws Exception {
        long now = System.currentTimeMillis();
        JSONObject cachedHvac = new JSONObject().put("batteryHeatState", 1);
        JSONObject vehicle = new JSONObject().put("time", now - VehicleCloudSnapshot.TELEMETRY_MAX_AGE_MS)
                .put("batteryHeatState", 1);
        JSONObject result = read(null, VehicleCloudSnapshot.fromVehicleInfo(vehicle, cachedHvac).build(), now);
        assertEquals("unknown", result.getString("batteryHeatStatus"));
        vehicle.put("time", -1L);
        result = read(null, VehicleCloudSnapshot.fromVehicleInfo(vehicle, cachedHvac).build(), now);
        assertEquals(0L, result.getLong("batteryHeatObservedAtMs"));
    }
}

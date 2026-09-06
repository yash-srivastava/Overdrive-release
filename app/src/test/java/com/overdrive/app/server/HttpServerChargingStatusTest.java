package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.charging.ChargingApiHandler;
import com.overdrive.app.monitor.ChargingStateData;

import org.json.JSONObject;
import org.junit.Test;

public class HttpServerChargingStatusTest {

    @Test
    public void statusTreatsPhysicalGunOutAsUnpluggedDespiteStaleFinishedState() {
        assertFalse(HttpServer.resolveChargingPlugged(
                false, ChargingStateData.ChargingStatus.FINISHED, 1, false));
    }

    @Test
    public void statusTreatsV2lAsUnpluggedAndRetainsNormalFallbacks() {
        assertFalse(HttpServer.resolveChargingPlugged(
                true, ChargingStateData.ChargingStatus.CHARGING, 2, true));
        assertFalse(HttpServer.resolveChargingPlugged(
                false, ChargingStateData.ChargingStatus.READY,
                BydVehicleData.UNAVAILABLE, false));
        assertTrue(HttpServer.resolveChargingPlugged(
                false, ChargingStateData.ChargingStatus.FINISHED,
                BydVehicleData.UNAVAILABLE, false));
    }

    @Test
    public void authoritativeDisconnectOrExportClearsRacedChargingVerdict() {
        assertFalse(HttpServer.resolveChargingActive(
                true, false,
                ChargingStateData.ChargingStatus.FINISHED,
                1, false));
        assertFalse(HttpServer.resolveChargingActive(
                true, false,
                ChargingStateData.ChargingStatus.CHARGING,
                5, true));
        assertFalse(HttpServer.resolveChargingFull(
                ChargingStateData.ChargingStatus.FINISHED,
                false, 1, false));
        assertFalse(HttpServer.resolveChargingFull(
                ChargingStateData.ChargingStatus.FINISHED, false, 5, true));
    }

    @Test
    public void dischargingOverridesRacedDetectorAndTaperPositives() {
        assertFalse(HttpServer.resolveChargingActive(
                true, true,
                ChargingStateData.ChargingStatus.DISCHARGING,
                2, false));
        assertFalse(HttpServer.resolveChargingFull(
                ChargingStateData.ChargingStatus.DISCHARGING,
                false, 2, false));
    }

    @Test
    public void taperRemainsActiveButIsNotPresentedAsFull() {
        assertTrue(HttpServer.resolveChargingActive(
                false, true,
                ChargingStateData.ChargingStatus.FINISHED,
                2, false));
        assertFalse(HttpServer.resolveChargingFull(
                ChargingStateData.ChargingStatus.FINISHED, true, 2, false));
    }

    @Test
    public void racedFusedPositiveCannotOverrideFinishedWithoutLiveTaper() {
        assertFalse(HttpServer.resolveChargingActive(
                true, false,
                ChargingStateData.ChargingStatus.FINISHED,
                2, false));
    }

    @Test
    public void missingVehicleStateStillEmitsACompleteClearingBlock() {
        JSONObject idle = HttpServer.buildIdleChargingStatus();
        assertFalse(idle.optBoolean("charging", true));
        assertFalse(idle.optBoolean("plugged", true));
        assertFalse(idle.optBoolean("full", true));
        assertFalse(idle.optBoolean("fault", true));
        assertTrue(idle.has("powerKw"));
        assertEquals(0.0, idle.optDouble("chargingPowerKW", -1), 0.0);
        assertEquals(0.0, idle.optDouble("powerKw", -1), 0.0);
        assertEquals("none", idle.optString("powerSource"));
        assertEquals(0L, idle.optLong("powerObservedAtMs", -1L));
        assertEquals("UNKNOWN", idle.optString("powerQuality"));
    }

    @Test
    public void statusPublicationHelperCannotLeakPositivePowerAfterStop() {
        ChargingStateData state = new ChargingStateData(
                ChargingStateData.CHARGING_BATTERY_STATE_CHARGING);
        state.updateChargingPower(
                3.0, "chargingDevice", 999L,
                ChargingStateData.PowerQuality.MEASURED, 1.0);

        ChargingApiHandler.PowerPublication publication =
                ChargingApiHandler.normalizePowerPublication(false, state);
        assertEquals(0.0, publication.powerKw, 0.0);
        assertEquals("none", publication.source);
        assertEquals(0L, publication.observedAtMs);
    }
}

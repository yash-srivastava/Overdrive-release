package com.overdrive.app.charging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.byd.BydVehicleData;
import org.junit.Test;

public class ChargingCompletionEstimateTest {

    private static final long NOW = 1_700_000_000_000L;

    @Test
    public void vehicleCountdownWinsOverCalculatedFallback() {
        ChargingCompletionEstimate eta = ChargingCompletionEstimate.resolve(
                true, false, 2, 7, 100, 50, 60, 100, 7, false, NOW);

        assertTrue(eta.isAvailable());
        assertEquals(127, eta.minutes);
        assertEquals(ChargingCompletionEstimate.SOURCE_VEHICLE, eta.source);
        assertEquals(100, eta.targetPercent);
        assertEquals(NOW + 127 * 60_000L, eta.completionEpochMs);
    }

    @Test
    public void measuredPowerCalculatesFallbackWhenVehicleValueIsMissing() {
        ChargingCompletionEstimate eta = ChargingCompletionEstimate.resolve(
                true, false,
                BydVehicleData.UNAVAILABLE, BydVehicleData.UNAVAILABLE,
                100, 50, 60, 90, 6, false, NOW);

        assertTrue(eta.isAvailable());
        assertEquals(270, eta.minutes);
        assertEquals(ChargingCompletionEstimate.SOURCE_CALCULATED, eta.source);
    }

    @Test
    public void estimatedPowerNeverProducesCompletionTime() {
        ChargingCompletionEstimate eta = ChargingCompletionEstimate.resolve(
                true, false,
                BydVehicleData.UNAVAILABLE, BydVehicleData.UNAVAILABLE,
                100, 50, 60, 100, 7, true, NOW);

        assertFalse(eta.isAvailable());
    }

    @Test
    public void inactiveAndCompleteStatesDoNotExposeCountdown() {
        assertFalse(ChargingCompletionEstimate.resolve(
                false, false, 1, 0, 100, 50, 60, 100, 7, false, NOW).isAvailable());
        assertFalse(ChargingCompletionEstimate.resolve(
                true, true, 1, 0, 100, 100, 60, 100, 7, false, NOW).isAvailable());
    }

    @Test
    public void calculatedFallbackRespectsVehicleChargeCap() {
        ChargingCompletionEstimate eta = ChargingCompletionEstimate.resolve(
                true, false,
                BydVehicleData.UNAVAILABLE, BydVehicleData.UNAVAILABLE,
                80, 50, 60, 100, 6, false, NOW);

        assertEquals(180, eta.minutes);
        assertEquals(80, eta.targetPercent);
        assertEquals(ChargingCompletionEstimate.SOURCE_CALCULATED, eta.source);
    }
}

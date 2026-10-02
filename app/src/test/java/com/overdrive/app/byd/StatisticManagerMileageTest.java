package com.overdrive.app.byd;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The manager tier ({@code BYDAutoManager.getInt}) returns the HAL's not-available
 * encodings as ordinary numbers, so the EV/HEV mileage fallback must reject them
 * before they can become a published odometer value.
 */
public class StatisticManagerMileageTest {

    @Test
    public void realLifetimeMileageIsAccepted() {
        // Values read live from a Sealion 6 DM-i (Di 3.0): EV 8373, HEV 2141 km.
        assertTrue(BydDataCollector.isUsableManagerMileage(8373, false));
        assertTrue(BydDataCollector.isUsableManagerMileage(2141, false));
        assertTrue(BydDataCollector.isUsableManagerMileage(1, false));
        assertTrue(BydDataCollector.isUsableManagerMileage(2_000_000, false));
    }

    @Test
    public void halSentinelsAreRejected() {
        int[] sentinels = {
                BydFeatureIds.BMS_UNAVAILABLE,   // -10011
                BydFeatureIds.INVALID_VALUE,     // -2147482645
                BydFeatureIds.INVALID_VALUE_2,   // -2147482648
                Integer.MIN_VALUE,               // "manager gave us nothing"
                65535,
                -1,
        };
        for (int s : sentinels) {
            assertFalse("sentinel " + s + " must not become mileage",
                    BydDataCollector.isUsableManagerMileage(s, false));
            assertFalse("sentinel " + s + " must not become mileage (DiLink 5)",
                    BydDataCollector.isUsableManagerMileage(s, true));
        }
    }

    @Test
    public void zeroOnlyCountsOnDiLink5() {
        // Legacy trims report 0 for an unpopulated register; DiLink 5 can legitimately
        // report 0 (e.g. no HEV distance yet), matching isUsableMileage.
        assertFalse(BydDataCollector.isUsableManagerMileage(0, false));
        assertTrue(BydDataCollector.isUsableManagerMileage(0, true));
    }

    @Test
    public void implausiblyLargeValuesAreRejected() {
        assertFalse(BydDataCollector.isUsableManagerMileage(2_000_001, false));
        assertFalse(BydDataCollector.isUsableManagerMileage(Integer.MAX_VALUE, true));
    }
}

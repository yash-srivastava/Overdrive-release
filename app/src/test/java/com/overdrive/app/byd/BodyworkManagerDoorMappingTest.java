package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.byd.bodywork.BodyworkConstants;

import org.junit.Test;

public class BodyworkManagerDoorMappingTest {

    @Test
    public void frontManagerIdsCrossOnlyWhenLearnedCrossed() {
        assertEquals(
                BydFeatureIds.BODYWORK_DOOR_RF,
                BydDataCollector.doorFeatureForArea(1, true));
        assertEquals(
                BydFeatureIds.BODYWORK_DOOR_LF,
                BydDataCollector.doorFeatureForArea(2, true));

        assertEquals(
                BydFeatureIds.BODYWORK_DOOR_LF,
                BydDataCollector.doorFeatureForArea(1, false));
        assertEquals(
                BydFeatureIds.BODYWORK_DOOR_RF,
                BydDataCollector.doorFeatureForArea(2, false));
    }

    @Test
    public void rearAndLidManagerIdsRemainPhysical() {
        assertEquals(
                BydFeatureIds.BODYWORK_DOOR_LR,
                BydDataCollector.doorFeatureForArea(3, true));
        assertEquals(
                BydFeatureIds.BODYWORK_DOOR_RR,
                BydDataCollector.doorFeatureForArea(4, false));
        assertEquals(
                BydFeatureIds.BODYWORK_HOOD,
                BydDataCollector.doorFeatureForArea(5, true));
        assertEquals(
                BydFeatureIds.BODYWORK_TRUNK,
                BydDataCollector.doorFeatureForArea(6, true));
        assertEquals(
                BydFeatureIds.BODYWORK_FUEL_CAP,
                BydDataCollector.doorFeatureForArea(7, true));
    }

    @Test
    public void onlyRealOpenClosedValuesBypassLegacyFallback() {
        assertTrue(BydDataCollector.isValidDoorOpenState(BodyworkConstants.STATE_CLOSED));
        assertTrue(BydDataCollector.isValidDoorOpenState(BodyworkConstants.STATE_OPEN));

        int[] unavailable = {
                -1,
                2,
                255,
                65535,
                -10011,
                -2147482645,
                Integer.MIN_VALUE
        };
        for (int value : unavailable) {
            assertFalse("accepted unavailable door state " + value,
                    BydDataCollector.isValidDoorOpenState(value));
        }
    }
}

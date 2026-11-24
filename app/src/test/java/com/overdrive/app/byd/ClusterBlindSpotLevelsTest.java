package com.overdrive.app.byd;

import org.junit.Test;

import static com.overdrive.app.byd.BydDataCollector.*;
import static com.overdrive.app.byd.BydVehicleData.UNAVAILABLE;
import static org.junit.Assert.assertEquals;

public class ClusterBlindSpotLevelsTest {
    @Test public void aggregateKeepsMissingDirectionUnknown() {
        assertEquals(BS_AGGREGATE_KNOWN_BIT | BS_ANY_BIT,
                packClusterBlindSpotLevels(UNAVAILABLE, UNAVAILABLE, () -> 2));
        assertEquals(BS_LEFT_KNOWN_BIT | BS_AGGREGATE_KNOWN_BIT | BS_ANY_BIT,
                packClusterBlindSpotLevels(0, UNAVAILABLE, () -> 2));
        assertEquals(BS_RIGHT_KNOWN_BIT | BS_AGGREGATE_KNOWN_BIT | BS_ANY_BIT,
                packClusterBlindSpotLevels(UNAVAILABLE, 0, () -> 2));
        for (int idle : new int[]{0, 1}) {
            assertEquals(BS_RIGHT_KNOWN_BIT | BS_AGGREGATE_KNOWN_BIT,
                    packClusterBlindSpotLevels(UNAVAILABLE, 0, () -> idle));
        }
        for (int unknown : new int[]{UNAVAILABLE, 3, 255}) {
            assertEquals(-1, packClusterBlindSpotLevels(UNAVAILABLE, UNAVAILABLE, () -> unknown));
            assertEquals(BS_LEFT_KNOWN_BIT, packClusterBlindSpotLevels(0, UNAVAILABLE, () -> unknown));
        }
    }

    @Test public void conclusiveSideReadingsAvoidAnotherHalRead() {
        java.util.function.IntSupplier neverRead = () -> { throw new AssertionError("unexpected aggregate read"); };
        int bothKnown = BS_LEFT_KNOWN_BIT | BS_RIGHT_KNOWN_BIT | BS_SIDES_KNOWN_BIT;
        assertEquals(bothKnown, packClusterBlindSpotLevels(0, 0, neverRead));
        assertEquals(bothKnown | BS_LEFT_BIT | BS_RIGHT_BIT, packClusterBlindSpotLevels(1, 1, neverRead));
        assertEquals(BS_LEFT_KNOWN_BIT | BS_LEFT_BIT, packClusterBlindSpotLevels(1, UNAVAILABLE, neverRead));
        assertEquals(BS_RIGHT_KNOWN_BIT | BS_RIGHT_BIT, packClusterBlindSpotLevels(UNAVAILABLE, 1, neverRead));
    }
}

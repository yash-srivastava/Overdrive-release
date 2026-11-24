package com.overdrive.app.geo;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure-Java cases for GPS validity shared by sidecars, the cache and geocoding. */
public class GeoCoordinatesTest {

    @Test
    public void acceptsRealCoordinatesAndWorldBoundaries() {
        assertTrue(GeoCoordinates.isUsable(47.3769, 8.5417));
        assertTrue(GeoCoordinates.isUsable(-90.0, -180.0));
        assertTrue(GeoCoordinates.isUsable(90.0, 180.0));
        assertTrue(GeoCoordinates.isUsable(0.0, 0.0001));
    }

    @Test
    public void rejectsNonFiniteAndOutOfRangeCoordinates() {
        assertFalse(GeoCoordinates.isUsable(Double.NaN, 8.0));
        assertFalse(GeoCoordinates.isUsable(47.0, Double.NaN));
        assertFalse(GeoCoordinates.isUsable(Double.POSITIVE_INFINITY, 8.0));
        assertFalse(GeoCoordinates.isUsable(47.0, Double.NEGATIVE_INFINITY));
        assertFalse(GeoCoordinates.isUsable(90.0001, 8.0));
        assertFalse(GeoCoordinates.isUsable(-90.0001, 8.0));
        assertFalse(GeoCoordinates.isUsable(47.0, 180.0001));
        assertFalse(GeoCoordinates.isUsable(47.0, -180.0001));
        assertFalse(GeoCoordinates.isUsable(0.0, -0.0));
    }

    @Test
    public void snapshotsOnlyReportUsableFixes() {
        assertTrue(snapshot(47.0, 8.0).hasFix());
        assertFalse(GeoSnapshot.empty().hasFix());
        assertFalse(snapshot(0.0, 0.0).hasFix());
        assertFalse(snapshot(Double.POSITIVE_INFINITY, 8.0).hasFix());
        assertFalse(snapshot(91.0, 8.0).hasFix());
        assertFalse(snapshot(47.0, -181.0).hasFix());
    }

    private static GeoSnapshot snapshot(double lat, double lng) {
        return new GeoSnapshot(lat, lng, 0f, 0L, 0L, 0L);
    }
}

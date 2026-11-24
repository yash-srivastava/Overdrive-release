package com.overdrive.app.geo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure-Java coverage for SafeLocation cache invalidation near cell boundaries. */
public class GeohashRadiusTest {

    @Test
    public void adjacentCellsWithDifferentParentsAreIncluded() {
        double lat = 0.00005;
        double lng = 0.00005;
        String nearby = Geohash.encode(lat, -0.00005);
        assertNotEquals(Geohash.encode(lat, lng).substring(0, 6), nearby.substring(0, 6));
        assertTrue(Geohash.mayOverlapRadius(nearby, lat, lng, 15.0));
    }

    @Test
    public void nearbyParentCellOutsideTheRadiusIsPreserved() {
        double lat = 0.00005;
        double lng = 0.00005;
        String outside = Geohash.encode(lat, 0.004);
        assertEquals(Geohash.encode(lat, lng).substring(0, 6), outside.substring(0, 6));
        assertFalse(Geohash.mayOverlapRadius(outside, lat, lng, 15.0));
    }

    @Test
    public void radiusIncludesCellsBeyondTheImmediateParent() {
        double lat = 37.7751;
        double lng = -122.4201;
        String across = Geohash.encode(lat, -122.4141);
        assertFalse(Geohash.mayOverlapRadius(across, lat, lng, 100.0));
        assertTrue(Geohash.mayOverlapRadius(across, lat, lng, 650.0));
    }

    @Test
    public void wraparoundAtDateLineDoesNotHideNearbyCell() {
        String acrossDateLine = Geohash.encode(0.001, -179.99995);
        assertTrue(Geohash.mayOverlapRadius(acrossDateLine, 0.001, 179.99995, 20.0));
    }

    @Test
    public void distantAndMalformedKeysAreNotInvalidated() {
        assertFalse(Geohash.mayOverlapRadius(Geohash.encode(1.0, 1.0), 0.0, 0.0, 500.0));
        assertFalse(Geohash.mayOverlapRadius("invalid", 0.0, 0.0, 500.0));
        assertFalse(Geohash.mayOverlapRadius(Geohash.encode(0.0, 0.0),
                Double.NaN, 0.0, 500.0));
        assertFalse(Geohash.mayOverlapRadius(Geohash.encode(0.0, 0.0),
                0.0, 0.0, -1.0));
    }
}

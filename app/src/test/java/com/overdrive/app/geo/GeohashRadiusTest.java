package com.overdrive.app.geo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

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

    @Test
    public void matchesFourCornerReferenceAtRadiusBoundaries() {
        Random random = new Random(52017L);
        for (int i = 0; i < 20000; i++) {
            double cellLat = i % 20 == 0 ? (i % 40 == 0 ? -90 : 90)
                    : random.nextDouble() * 180 - 90;
            double cellLng = i % 21 == 0 ? (i % 42 == 0 ? -180 : 180)
                    : random.nextDouble() * 360 - 180;
            String hash = Geohash.encode(cellLat, cellLng);
            double lat = i % 2 == 0 ? Math.max(-90, Math.min(90,
                    cellLat + (random.nextDouble() - 0.5) * 0.02))
                    : random.nextDouble() * 180 - 90;
            double lng = i % 2 == 0 ? cellLng + (random.nextDouble() - 0.5) * 0.02
                    : random.nextDouble() * 360 - 180;
            if (lng > 180) lng -= 360;
            if (lng < -180) lng += 360;
            double[] geometry = referenceGeometry(hash, lat, lng);
            double boundary = Math.max(0.0, geometry[0] - geometry[1]);
            for (double radius : new double[] {0, 15, 500, 20000000,
                    Math.max(0.0, Math.nextDown(boundary)), boundary,
                    Math.nextUp(boundary), Math.nextUp(Math.nextUp(boundary))}) {
                assertEquals(hash + " radius=" + radius,
                        geometry[0] <= radius + geometry[1],
                        Geohash.mayOverlapRadius(hash, lat, lng, radius));
            }
        }
    }

    @Test
    public void rejectsEveryNonAlphabetCharacterInEveryPosition() {
        String alphabet = "0123456789bcdefghjkmnpqrstuvwxyz";
        String valid = "s000000";
        for (int position = 0; position < valid.length(); position++) {
            for (int c = 0; c < 256; c++) {
                String hash = valid.substring(0, position) + (char) c + valid.substring(position + 1);
                assertEquals(alphabet.indexOf(c) >= 0,
                        Geohash.mayOverlapRadius(hash, 0, 0, Double.MAX_VALUE));
            }
        }
        assertFalse(Geohash.mayOverlapRadius("s00000\u2603", 0, 0, Double.MAX_VALUE));
        assertFalse(Geohash.mayOverlapRadius(null, 0, 0, 1));
        assertFalse(Geohash.mayOverlapRadius(valid, 0, 181, 1));
        assertFalse(Geohash.mayOverlapRadius(valid, 91, 0, 1));
        assertFalse(Geohash.mayOverlapRadius(valid, 0, 0, Double.POSITIVE_INFINITY));
    }

    // Independent bisection and four-corner reference, including the legacy
    // floating-point distance formula so adjacent radius values can be tested.
    private static double[] referenceGeometry(String hash, double lat, double lng) {
        String alphabet = "0123456789bcdefghjkmnpqrstuvwxyz";
        double south = -90, north = 90, west = -180, east = 180;
        boolean longitude = true;
        for (int i = 0; i < hash.length(); i++) {
            int value = alphabet.indexOf(hash.charAt(i));
            for (int bit = 4; bit >= 0; bit--) {
                boolean upper = (value & (1 << bit)) != 0;
                if (longitude) {
                    double mid = (west + east) / 2;
                    if (upper) west = mid; else east = mid;
                } else {
                    double mid = (south + north) / 2;
                    if (upper) south = mid; else north = mid;
                }
                longitude = !longitude;
            }
        }
        double centerLat = (south + north) / 2;
        double centerLng = (west + east) / 2;
        double radius = 0;
        for (double y : new double[] {south, north}) {
            for (double x : new double[] {west, east}) {
                radius = Math.max(radius, referenceDistance(centerLat, centerLng, y, x));
            }
        }
        return new double[] {referenceDistance(lat, lng, centerLat, centerLng), radius};
    }

    private static double referenceDistance(double lat1, double lng1, double lat2, double lng2) {
        double sinLat = Math.sin(Math.toRadians(lat2 - lat1) / 2.0);
        double sinLng = Math.sin(Math.toRadians(lng2 - lng1) / 2.0);
        double a = sinLat * sinLat
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * sinLng * sinLng;
        return 2.0 * 6_371_000.0 * Math.asin(Math.sqrt(Math.min(1.0, Math.max(0.0, a))));
    }
}

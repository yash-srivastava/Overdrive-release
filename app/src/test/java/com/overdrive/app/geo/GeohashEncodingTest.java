package com.overdrive.app.geo;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Protect the stable geographic keys used by the place cache. */
public class GeohashEncodingTest {

    @Test
    public void knownCoordinatesMatchReferenceHashes() {
        // Published geohash examples, independent of the app's encoder.
        assertEquals("ezs42", Geohash.encode(42.6, -5.6, 5));
        assertEquals("ezs42e44yx96", Geohash.encode(42.6, -5.6, 12));
        assertEquals("u4pruydqqvj", Geohash.encode(57.64911, 10.40744, 11));
    }

    @Test
    public void defaultPrecisionPreservesExistingCacheKeys() {
        assertEquals(7, Geohash.PRECISION_DEFAULT);
        assertEquals("ezs42e4", Geohash.encode(42.6, -5.6));
        assertEquals("u4pruyd", Geohash.encode(57.64911, 10.40744));
    }

    @Test
    public void everyPrecisionMatchesTheCorrespondingReferencePrefix() {
        String full = "ezs42e44yx96";
        for (int precision = 1; precision <= 12; precision++) {
            assertEquals(full.substring(0, precision),
                    Geohash.encode(42.6, -5.6, precision));
        }
    }

    @Test
    public void requestedPrecisionIsClampedToSupportedRange() {
        assertEquals("e", Geohash.encode(42.6, -5.6, Integer.MIN_VALUE));
        assertEquals("e", Geohash.encode(42.6, -5.6, 0));
        assertEquals("ezs42e44yx96", Geohash.encode(42.6, -5.6, 13));
        assertEquals("ezs42e44yx96", Geohash.encode(42.6, -5.6, Integer.MAX_VALUE));
    }

    @Test
    public void geographicExtremesAndMidpointUseStableCells() {
        assertEquals("0000000", Geohash.encode(-90.0, -180.0));
        assertEquals("zzzzzzz", Geohash.encode(90.0, 180.0));
        // The encoder assigns exact subdivision midpoints to the upper half.
        assertEquals("s000000", Geohash.encode(0.0, 0.0));
    }
}

package com.overdrive.app.trips;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class TelemetrySampleGpsFixTest {

    @Test
    public void hasGpsFix_rejectsMissingAndOutOfBounds() {
        assertFalse(TelemetrySample.hasGpsFix(0, 0));
        assertFalse(TelemetrySample.hasGpsFix(Double.NaN, 10));
        assertFalse(TelemetrySample.hasGpsFix(10, Double.POSITIVE_INFINITY));
        assertFalse(TelemetrySample.hasGpsFix(91, 0));
        assertFalse(TelemetrySample.hasGpsFix(0, 181));
    }

    @Test
    public void hasGpsFix_acceptsPlausibleFixes() {
        assertTrue(TelemetrySample.hasGpsFix(-33.8688, 151.2093));
        assertTrue(TelemetrySample.hasGpsFix(32.0853, 34.7818));
        assertTrue(TelemetrySample.hasGpsFix(0.0001, 10.5));
        assertTrue(TelemetrySample.hasGpsFix(0, 151.2));
        assertTrue(TelemetrySample.hasGpsFix(-33.8, 0));
    }

    @Test
    public void fromJson_preservesExplicitZeroDegreeCoordinates() throws Exception {
        TelemetrySample equator = TelemetrySample.fromJson(new JSONObject()
                .put("la", 0).put("lo", 151.2));
        TelemetrySample meridian = TelemetrySample.fromJson(new JSONObject()
                .put("la", -33.8).put("lo", 0));
        assertEquals(0.0, equator.lat, 0.0);
        assertEquals(151.2, equator.lon, 0.0);
        assertTrue(TelemetrySample.hasGpsFix(equator.lat, equator.lon));
        assertEquals(-33.8, meridian.lat, 0.0);
        assertEquals(0.0, meridian.lon, 0.0);
        assertTrue(TelemetrySample.hasGpsFix(meridian.lat, meridian.lon));
    }

    @Test
    public void fromJson_rejectsMissingOrInvalidCoordinatePairs() throws Exception {
        JSONObject[] invalid = {
                new JSONObject(),
                new JSONObject().put("lo", 151.2),
                new JSONObject().put("la", -33.8),
                new JSONObject().put("la", JSONObject.NULL).put("lo", 151.2),
                new JSONObject().put("la", -33.8).put("lo", "invalid"),
                new JSONObject().put("la", "Infinity").put("lo", 10),
                new JSONObject().put("la", 91).put("lo", 10),
                new JSONObject().put("la", 10).put("lo", 181),
                new JSONObject().put("la", 0).put("lo", 0)
        };
        for (JSONObject row : invalid) {
            TelemetrySample sample = TelemetrySample.fromJson(row.put("t", 1234).put("s", 42));
            assertEquals(row.toString(), 0.0, sample.lat, 0.0);
            assertEquals(row.toString(), 0.0, sample.lon, 0.0);
            assertFalse(row.toString(), TelemetrySample.hasGpsFix(sample.lat, sample.lon));
            assertEquals(1234L, sample.timestampMs);
            assertEquals(42, sample.speedKmh);
        }
    }
}

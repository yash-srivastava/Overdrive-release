package com.overdrive.app.trips;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TelemetrySampleOptionalMeasurementsTest {

    @Test
    public void invalidVerticalAccuracyDoesNotDiscardLaterFields() throws Exception {
        for (double invalid : new double[]{0, -1, Double.NaN,
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            JSONObject json = sample(invalid, 8f, 12.5f).toJson();
            assertFalse(json.has("va"));
            assertEquals(8.0, json.getDouble("ha"), 0.0);
            assertEquals(12.5, json.getDouble("dl"), 0.0);
            assertRemainingFields(json);
            assertEquals(0.0, TelemetrySample.fromJson(json).verticalAccuracyM, 0.0);
        }
    }

    @Test
    public void invalidHorizontalAccuracyDoesNotDiscardDistanceOrSoc() throws Exception {
        for (float invalid : new float[]{0, -1, Float.NaN,
                Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY}) {
            JSONObject json = sample(4.5, invalid, 12.5f).toJson();
            assertFalse(json.has("ha"));
            assertEquals(4.5, json.getDouble("va"), 0.0);
            assertEquals(12.5, json.getDouble("dl"), 0.0);
            assertRemainingFields(json);
            assertEquals(0f, TelemetrySample.fromJson(json).horizontalAccuracyM, 0f);
        }
    }

    @Test
    public void invalidLandedDistanceDoesNotDiscardSoc() throws Exception {
        for (float invalid : new float[]{0, -1, Float.NaN,
                Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY}) {
            JSONObject json = sample(4.5, 8f, invalid).toJson();
            assertFalse(json.has("dl"));
            assertEquals(4.5, json.getDouble("va"), 0.0);
            assertEquals(8.0, json.getDouble("ha"), 0.0);
            assertRemainingFields(json);
            assertEquals(0f, TelemetrySample.fromJson(json).distanceLandedM, 0f);
        }
    }

    @Test
    public void positiveFiniteMeasurementsSurviveJsonRoundTrip() throws Exception {
        JSONObject json = new JSONObject(sample(4.5, 8f, 12.5f).toJson().toString());
        assertRemainingFields(json);
        TelemetrySample restored = TelemetrySample.fromJson(json);
        assertEquals(4.5, restored.verticalAccuracyM, 0.0);
        assertEquals(8f, restored.horizontalAccuracyM, 0f);
        assertEquals(12.5f, restored.distanceLandedM, 0f);
        assertEquals(70.25, restored.socPercent, 0.0);
    }

    private static void assertRemainingFields(JSONObject json) throws Exception {
        assertEquals(10_000L, json.getLong("t"));
        assertEquals(9_000L, json.getLong("gt"));
        assertTrue(json.getBoolean("am"));
        assertEquals(TelemetrySample.SPEED_GPS, json.getInt("ss"));
        assertEquals(18, json.getInt("sp"));
        assertEquals(70.25, json.getDouble("so"), 0.0);
    }

    private static TelemetrySample sample(double vertical, float horizontal, float distance) {
        return new TelemetrySample(10_000L, 20, 0, 0, false, 4,
                47.0, 8.0, 400.0, vertical, true,
                TelemetrySample.SPEED_GPS, true, false, false, 18,
                horizontal, distance, 9_000L, 70.25);
    }
}

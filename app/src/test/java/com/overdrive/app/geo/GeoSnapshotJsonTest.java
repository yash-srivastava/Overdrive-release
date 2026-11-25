package com.overdrive.app.geo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.json.JSONObject;
import org.junit.Test;

public class GeoSnapshotJsonTest {

    @Test
    public void invalidAccuracyDoesNotDiscardTimingMetadata() throws Exception {
        for (float accuracy : new float[] {
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN, 0f, -1f
        }) {
            JSONObject json = snapshot(accuracy).toJson();
            assertFalse("accuracy=" + accuracy, json.has("accuracy"));
            assertLocationAndTiming(json);
        }
    }

    @Test
    public void validAccuracyAndTimingArePreserved() throws Exception {
        JSONObject json = snapshot(12.5f).toJson();
        assertEquals(12.5, json.getDouble("accuracy"), 0.0);
        assertLocationAndTiming(json);
    }

    @Test
    public void unknownTimingFieldsRemainAbsent() throws Exception {
        JSONObject json = new GeoSnapshot(47.0, 8.0, Float.POSITIVE_INFINITY,
                -1L, 0L, -1L).toJson();
        assertEquals(2, json.length());
        assertEquals(47.0, json.getDouble("lat"), 0.0);
        assertEquals(8.0, json.getDouble("lng"), 0.0);
    }

    @Test
    public void unusableFixStillProducesEmptyJson() {
        assertEquals(0, GeoSnapshot.empty().toJson().length());
        assertEquals(0, new GeoSnapshot(91.0, 8.0, 12.5f,
                40_000L, 100_000L, 2_500L).toJson().length());
    }

    private static GeoSnapshot snapshot(float accuracy) {
        return new GeoSnapshot(47.0, 8.0, accuracy, 40_000L, 100_000L, 2_500L);
    }

    private static void assertLocationAndTiming(JSONObject json) throws Exception {
        assertEquals(47.0, json.getDouble("lat"), 0.0);
        assertEquals(8.0, json.getDouble("lng"), 0.0);
        assertEquals(40_000L, json.getLong("ageMs"));
        assertEquals(100_000L, json.getLong("capturedAtMs"));
        assertEquals(2_500L, json.getLong("tMs"));
    }
}

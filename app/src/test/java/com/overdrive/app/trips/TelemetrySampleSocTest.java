package com.overdrive.app.trips;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

/** Per-row battery SoC: the trip timeline plots it instead of a line from start to end SoC. */
public class TelemetrySampleSocTest {

    private static TelemetrySample row(long t, double soc) {
        return new TelemetrySample(t, 60, 10, 0, false, 4, -33.8, 151.2, 20.0, 0.0, false,
                TelemetrySample.SPEED_VEHICLE, false, false, false, -1, 0f, 0f, 0L, soc);
    }

    @Test
    public void recordedSocIsWrittenAndReadBack() throws Exception {
        JSONObject json = row(1_000L, 70.25).toJson();
        assertEquals(70.25, json.getDouble("so"), 0.0);
        assertEquals(70.25, TelemetrySample.fromJson(json).socPercent, 0.0);
    }

    @Test
    public void socIsRoundedToTwoDecimals() throws Exception {
        assertEquals(69.87, row(1_000L, 69.8749).toJson().getDouble("so"), 0.0);
    }

    @Test
    public void missingOrInvalidSocIsNotARecording() throws Exception {
        double[] invalid = {Double.NaN, 0.0, -1.0, 100.01, Double.POSITIVE_INFINITY};
        for (double soc : invalid) {
            TelemetrySample sample = row(1_000L, soc);
            assertTrue("soc " + soc, Double.isNaN(sample.socPercent));
            assertFalse("soc " + soc, sample.toJson().has("so"));
        }
        assertTrue(Double.isNaN(TelemetrySample.fromJson(new JSONObject().put("t", 1)).socPercent));
        assertTrue(Double.isNaN(TelemetrySample.fromJson(
                new JSONObject().put("t", 1).put("so", 0)).socPercent));
        assertTrue(Double.isNaN(TelemetrySample.fromJson(
                new JSONObject().put("t", 1).put("so", "x")).socPercent));
        assertEquals(100.0, TelemetrySample.fromJson(
                new JSONObject().put("t", 1).put("so", 100)).socPercent, 0.0);
    }

    @Test
    public void olderConstructorsRecordNoSoc() {
        TelemetrySample legacy = new TelemetrySample(1_000L, 60, 10, 0, false, 4,
                -33.8, 151.2, 20.0, 0.0, false);
        assertTrue(Double.isNaN(legacy.socPercent));
        assertFalse(legacy.toJson().has("so"));
    }

    @Test
    public void copiesKeepTheSoc() {
        TelemetrySample sample = row(1_000L, 70.0);
        assertEquals(70.0, sample.atTimestamp(1_200L).socPercent, 0.0);
        assertEquals(70.0, sample.withDistanceLandedM(5f).socPercent, 0.0);
    }

    @Test
    public void oneHertzDownsampleKeepsTheChosenRowsSoc() {
        TelemetrySample landed = row(1_400L, 70.5).withDistanceLandedM(3f);
        List<TelemetrySample> rows = Arrays.asList(
                row(1_000L, 71.0), row(1_200L, 70.9), landed, row(2_100L, 70.0));
        List<TelemetrySample> out = TripTelemetryRecorder.downsampleTo1Hz(rows);
        assertEquals(2, out.size());
        // Second 1: the 1000 ms row is closest; its landed distance is re-summed onto a copy.
        assertEquals(1_000L, out.get(0).timestampMs);
        assertEquals(71.0, out.get(0).socPercent, 0.0);
        assertEquals(3f, out.get(0).distanceLandedM, 0f);
        assertEquals(70.0, out.get(1).socPercent, 0.0);
        assertSame(rows.get(3), out.get(1));
    }
}

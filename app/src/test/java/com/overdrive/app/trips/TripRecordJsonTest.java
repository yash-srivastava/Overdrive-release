package com.overdrive.app.trips;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Iterator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TripRecordJsonTest {
    @Test
    public void fullAndSummaryShareFieldsButOnlyFullContainsDetails() throws Exception {
        TripRecord trip = new TripRecord();
        trip.id = 42;
        trip.distanceKm = 12.5;
        trip.kwhStart = 10;
        trip.kwhEnd = 8;
        trip.elecConStart = 20;
        trip.elecConEnd = 23;
        trip.isPhev = true;
        trip.fuelPctStart = 80;
        trip.fuelPctEnd = 75;
        trip.fuelConStart = 12;
        trip.fuelConEnd = 13;
        trip.iceSecondsAtomic.set(17);
        trip.microMomentsJson = "[]";
        trip.telemetryFilePath = "trip.jsonl.gz";

        JSONObject full = trip.toJson();
        JSONObject summary = trip.toSummaryJson();
        assertEquals("[]", full.remove("microMomentsJson"));
        assertEquals("trip.jsonl.gz", full.remove("telemetryFilePath"));
        assertFalse(summary.has("microMomentsJson"));
        assertFalse(summary.has("telemetryFilePath"));
        assertSameFields(full, summary);
        assertEquals(42, summary.getLong("id"));
        assertEquals(2.0, summary.getDouble("energyUsedKwh"), 0.0);
        assertEquals(2.0, summary.getDouble("signedEnergyKwh"), 0.0);
        assertTrue(summary.getBoolean("energyMetered"));
        assertEquals(17, summary.getInt("iceSeconds"));
        assertEquals(13.0, summary.getDouble("fuelConEnd"), 0.0);
    }

    @Test
    public void absentCountersAndNullStringsKeepTheirExistingRepresentation() throws Exception {
        TripRecord trip = new TripRecord();
        JSONObject summary = trip.toSummaryJson();
        for (String key : new String[]{"elecConStart", "elecConEnd", "fuelPctStart",
                "fuelPctEnd", "fuelConStart", "fuelConEnd"}) {
            assertFalse(summary.has(key));
        }
        assertEquals("", summary.getString("currency"));
        assertEquals("", trip.toJson().getString("microMomentsJson"));
        assertEquals("", trip.toJson().getString("telemetryFilePath"));
    }

    @Test
    public void nonFiniteFieldPreservesPartialObjectAndDoesNotAppendDetails() throws Exception {
        TripRecord trip = new TripRecord();
        trip.id = 7;
        trip.distanceKm = Double.NaN;
        JSONObject full = trip.toJson();
        JSONObject summary = trip.toSummaryJson();
        assertEquals(3, full.length());
        assertEquals(7, full.optLong("id"));
        assertFalse(full.has("distanceKm"));
        assertFalse(full.has("microMomentsJson"));
        assertSameFields(full, summary);
    }

    @Test
    public void nonFiniteElectricSnapshotsDoNotTruncateTripJson() throws Exception {
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY}) {
            TripRecord trip = new TripRecord();
            trip.kwhStart = invalid;
            trip.kwhEnd = 38;
            trip.elecConStart = 100;
            trip.elecConEnd = invalid;
            trip.currency = "USD";
            trip.tripCost = 0.5;
            trip.telemetryFilePath = "trip.jsonl.gz";

            for (JSONObject json : new JSONObject[]{trip.toJson(), trip.toSummaryJson()}) {
                assertEquals(0, json.getDouble("kwhStart"), 0);
                assertEquals(38, json.getDouble("kwhEnd"), 0);
                assertEquals(100, json.getDouble("elecConStart"), 0);
                assertFalse(json.has("elecConEnd"));
                assertFalse(json.getBoolean("energyMetered"));
                assertEquals(0, json.getDouble("energyUsedKwh"), 0);
                assertEquals(0, json.getDouble("signedEnergyKwh"), 0);
                assertEquals("USD", json.getString("currency"));
                assertEquals(0.5, json.getDouble("tripCost"), 0);
                assertTrue(json.has("overallScore"));
            }
            assertEquals("trip.jsonl.gz", trip.toJson().getString("telemetryFilePath"));
            assertFalse(trip.toSummaryJson().has("telemetryFilePath"));

            trip.kwhStart = 40;
            trip.kwhEnd = invalid;
            trip.elecConStart = invalid;
            trip.elecConEnd = 101;
            for (JSONObject json : new JSONObject[]{trip.toJson(), trip.toSummaryJson()}) {
                assertEquals(40, json.getDouble("kwhStart"), 0);
                assertEquals(0, json.getDouble("kwhEnd"), 0);
                assertFalse(json.has("elecConStart"));
                assertEquals(101, json.getDouble("elecConEnd"), 0);
                assertFalse(json.getBoolean("energyMetered"));
                assertEquals(0, json.getDouble("energyUsedKwh"), 0);
                assertEquals(0.5, json.getDouble("tripCost"), 0);
            }
        }
    }

    // JSONObject.similar is not in the Android API the tests compile against.
    private static void assertSameFields(JSONObject expected, JSONObject actual) throws Exception {
        assertEquals(expected.length(), actual.length());
        for (Iterator<String> it = expected.keys(); it.hasNext(); ) {
            String key = it.next();
            assertEquals(key, expected.get(key), actual.get(key));
        }
    }
}

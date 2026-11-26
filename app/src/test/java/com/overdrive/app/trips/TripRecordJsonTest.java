package com.overdrive.app.trips;

import org.json.JSONObject;
import org.junit.Test;

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
        assertTrue(full.similar(summary));
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
    public void nonFiniteFieldPreservesPartialObjectAndDoesNotAppendDetails() {
        TripRecord trip = new TripRecord();
        trip.id = 7;
        trip.distanceKm = Double.NaN;
        JSONObject full = trip.toJson();
        JSONObject summary = trip.toSummaryJson();
        assertEquals(3, full.length());
        assertEquals(7, full.optLong("id"));
        assertFalse(full.has("distanceKm"));
        assertFalse(full.has("microMomentsJson"));
        assertTrue(full.similar(summary));
    }
}

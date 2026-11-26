package com.overdrive.app.trips;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class MicroMomentsTest {
    @Test public void allThreeCollectionsRoundTripInOrder() throws Exception {
        String input = "{\"launches\":[{\"startTime\":10,\"peakAccelPercent\":30,\"accelCurve\":[10,30]},"
                + "{\"startTime\":20,\"peakAccelPercent\":5,\"accelCurve\":[5]}],"
                + "\"coastBrakeEvents\":[{\"coastGapMs\":1500,\"speedAtBrake\":22}],"
                + "\"smoothnessWindows\":[{\"startTime\":40,\"stdDev\":2.5}]}";
        JSONObject encoded = MicroMoments.fromJson(input).toJson();
        assertEquals(3, encoded.length());
        JSONArray launches = encoded.getJSONArray("launches");
        assertEquals(2, launches.length());
        JSONObject first = launches.getJSONObject(0);
        assertEquals(10L, first.getLong("startTime"));
        assertEquals(30, first.getInt("peakAccelPercent"));
        assertEquals("[10,30]", first.getJSONArray("accelCurve").toString());
        JSONObject second = launches.getJSONObject(1);
        assertEquals(20L, second.getLong("startTime"));
        assertEquals(5, second.getInt("peakAccelPercent"));
        assertEquals("[5]", second.getJSONArray("accelCurve").toString());
        JSONArray coasts = encoded.getJSONArray("coastBrakeEvents");
        assertEquals(1, coasts.length());
        assertEquals(1500L, coasts.getJSONObject(0).getLong("coastGapMs"));
        assertEquals(22, coasts.getJSONObject(0).getInt("speedAtBrake"));
        JSONArray windows = encoded.getJSONArray("smoothnessWindows");
        assertEquals(1, windows.length());
        assertEquals(40L, windows.getJSONObject(0).getLong("startTime"));
        assertEquals(2.5, windows.getJSONObject(0).getDouble("stdDev"), 0.0);
    }

    @Test public void absentArraysAndInvalidDocumentsRemainEmpty() throws Exception {
        for (String input : new String[]{null, "", "not json", "{}", "{\"launches\":false}"}) {
            JSONObject encoded = MicroMoments.fromJson(input).toJson();
            assertEquals(0, encoded.getJSONArray("launches").length());
            assertEquals(0, encoded.getJSONArray("coastBrakeEvents").length());
            assertEquals(0, encoded.getJSONArray("smoothnessWindows").length());
        }
    }

    @Test public void malformedEntryRetainsEarlierEntriesAndStopsDecoding() {
        MicroMoments moments = MicroMoments.fromJson("{\"launches\":[{\"startTime\":10}],"
                + "\"coastBrakeEvents\":[{\"coastGapMs\":1500},false,{\"coastGapMs\":2000}],"
                + "\"smoothnessWindows\":[{\"stdDev\":2.5}]}");
        assertEquals(1, moments.launches.size());
        assertEquals(10L, moments.launches.get(0).startTime);
        assertEquals(1, moments.coastBrakeEvents.size());
        assertEquals(1500L, moments.coastBrakeEvents.get(0).coastGapMs);
        assertTrue(moments.smoothnessWindows.isEmpty());
    }

    @Test public void failedArrayEncodingRetainsOnlyEarlierArrays() throws Exception {
        MicroMoments moments = new MicroMoments();
        moments.launches.add(new MicroMoments.LaunchProfile());
        moments.coastBrakeEvents.add(new MicroMoments.CoastBrakeEvent());
        moments.coastBrakeEvents.add(null);
        JSONObject encoded = moments.toJson();
        assertEquals(1, encoded.getJSONArray("launches").length());
        assertFalse(encoded.has("coastBrakeEvents"));
        assertFalse(encoded.has("smoothnessWindows"));
    }

    @Test public void completedAndTrailingLaunchesKeepTheirOwnCurves() {
        List<TelemetrySample> samples = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            int speed = i == 0 || i == 51 ? 0 : 20;
            int accel = speed == 0 ? 0 : i;
            samples.add(new TelemetrySample(1000L + i * 200L, speed, accel, 0,
                    false, 4, 47, 8, 100, 3, true));
        }
        TripRecord trip = new TripRecord();
        trip.distanceKm = 1;
        trip.durationSeconds = 16;
        trip.energyPerKm = 0.18;
        MicroMoments moments = MicroMoments.fromJson(
                new TripScoreEngine().computeSummary(trip, samples, 0, 0).microMomentsJson);
        assertEquals(2, moments.launches.size());
        assertLaunch(moments.launches.get(0), 1200L, 1, 50);
        assertLaunch(moments.launches.get(1), 11400L, 52, 79);
    }

    private static void assertLaunch(MicroMoments.LaunchProfile launch, long startTime,
                                     int first, int last) {
        assertEquals(startTime, launch.startTime);
        assertEquals(last, launch.peakAccelPercent);
        int[] expected = new int[last - first + 1];
        for (int i = 0; i < expected.length; i++) expected[i] = first + i;
        assertArrayEquals(expected, launch.accelCurve);
    }
}

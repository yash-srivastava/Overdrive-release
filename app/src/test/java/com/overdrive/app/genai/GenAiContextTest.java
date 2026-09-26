package com.overdrive.app.genai;
import com.overdrive.app.util.ScratchPaths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Set;

public class GenAiContextTest {

    @Test
    public void contextRequiresExplicitGroundedIntent() throws Exception {
        assertEquals(GenAiContext.GENERAL,
                GenAiContext.resolveMode("general",
                        "How do electric cars calculate range?"));
        assertEquals(GenAiContext.LATEST_TRIP,
                GenAiContext.resolveMode("general",
                        "Explain my latest trip"));
        assertEquals(GenAiContext.CHARGING,
                GenAiContext.resolveMode("charging", "review it"));
        assertEquals(GenAiContext.TRIP_COMPARISON,
                GenAiContext.resolveMode("general",
                        "Why was my battery consumption higher?"));
        assertEquals(GenAiContext.AUTOMATION_DIAGNOSTICS,
                GenAiContext.resolveMode("general",
                        "Help with the shell command in my automation"));
        assertEquals(GenAiContext.VEHICLE_ACTION,
                GenAiContext.resolveMode("general",
                        "Set the AC to 22 degrees"));
        assertEquals(GenAiContext.VEHICLE_ACTION,
                GenAiContext.resolveMode("general",
                        "Open the sunshade"));
        assertEquals(GenAiContext.VEHICLE_ACTION,
                GenAiContext.resolveMode("general",
                        "Run my commute automation"));

        JSONObject safe = GenAiContext.copyAllowed(
                new JSONObject()
                        .put("battery", new JSONObject().put("soc", 60))
                        .put("vin", "secret")
                        .put("path", "/private/recording.mp4"),
                "battery");
        assertTrue(safe.has("battery"));
        assertFalse(safe.has("vin"));
        assertFalse(safe.has("path"));
    }

    @Test
    public void realtimeStartsWithoutDataAndRequiresANarrowToolMode()
            throws Exception {
        GenAiContext.Snapshot session = GenAiContext.buildRealtime();
        assertFalse(session.hasContext());
        assertEquals("get_overdrive_context",
                GenAiContext.openAiRealtimeTool()
                        .getString("name"));
        assertEquals(null, GenAiContext.realtimeToolRequest(
                new JSONObject().put("mode", "everything")));
        assertEquals("latest_trip",
                GenAiContext.realtimeToolRequest(
                        new JSONObject()
                                .put("mode", "latest_trip")
                                .put("query", "Explain it"))
                        .getString("mode"));
    }

    @Test
    public void responseLanguageIsNormalizedWithoutTranslatingMachineValues() {
        String french = GenAiContext.withResponseLanguage(
                "Return structured data.", "fr-CA");
        assertTrue(french.contains("OverDrive language (fr)"));
        assertTrue(french.contains(
                "latest message or speech is clearly in another language"));
        assertTrue(french.contains(
                "identifiers, JSON keys, enum values"));

        String unsupported = GenAiContext.withResponseLanguage(
                "", "xx-YY");
        assertTrue(unsupported.contains("OverDrive language (en)"));
    }

    @Test
    public void diagnosticLogSelectionIsBoundedAndLocallyRedacted() {
        JSONArray lines = GenAiContext.selectDiagnosticLines(
                "normal heartbeat\n"
                        + "[WARN] request failed at https://secret.example/path "
                        + "from 10.0.0.8 user@example.com "
                        + ScratchPaths.path("private.mp4") + " lat=12.345\n"
                        + "at com.example.Work.run(Work.java:1)\n"
                        + "normal again\n",
                2);

        assertEquals(2, lines.length());
        String joined = lines.toString();
        assertTrue(joined.contains("[REDACTED_URL]"));
        assertTrue(joined.contains("[REDACTED_IP]"));
        assertTrue(joined.contains("[REDACTED_EMAIL]"));
        assertTrue(joined.contains("[REDACTED_PATH]"));
        assertTrue(joined.contains("[REDACTED_COORDINATE]"));
        assertFalse(joined.contains("private.mp4"));
        assertFalse(joined.contains("normal heartbeat"));
    }

    @Test
    public void tripComparisonUsesMedianAndOmitsRouteCoordinates()
            throws Exception {
        JSONObject latest = trip(1, 10, 0.22, 42)
                .put("startLat", 12.3)
                .put("startLon", 45.6);
        JSONArray candidates = new JSONArray()
                .put(trip(2, 9.8, 0.15, 36))
                .put(trip(3, 10.2, 0.17, 38))
                .put(trip(4, 10.1, 0.19, 40));

        JSONObject result = GenAiContext.buildTripComparison(
                latest, candidates,
                "same_route_and_similar_distance");
        assertEquals("high", result.getJSONObject("baseline")
                .getString("quality"));
        JSONObject consumption = signal(
                result.getJSONObject("baseline")
                        .getJSONArray("signals"),
                "electricConsumptionKwhPer100Km");
        assertEquals(22.0, consumption.getDouble("latest"), 0.01);
        assertEquals(17.0,
                consumption.getDouble("baselineMedian"), 0.01);
        assertEquals(3, consumption.getInt("sampleCount"));
        assertEquals("high", consumption.getString("quality"));
        assertFalse(result.getJSONObject("latestTrip")
                .has("startLat"));
        assertFalse(result.getJSONArray("comparableTrips")
                .getJSONObject(0).has("startLon"));
    }

    @Test
    public void tripComparisonTreatsZeroCelsiusAsRealData()
            throws Exception {
        JSONObject latest = trip(1, 10, 0.20, 40)
                .put("extTempC", 0);
        JSONArray candidates = new JSONArray()
                .put(trip(2, 10, 0.18, 40).put("extTempC", -2))
                .put(trip(3, 10, 0.19, 40).put("extTempC", 0))
                .put(trip(4, 10, 0.21, 40).put("extTempC", 2));

        JSONObject temperature = signal(
                GenAiContext.buildTripComparison(
                        latest, candidates, "same_route_and_similar_distance")
                        .getJSONObject("baseline")
                        .getJSONArray("signals"),
                "outsideTemperatureC");

        assertEquals(0.0, temperature.getDouble("latest"), 0.01);
        assertEquals(0.0,
                temperature.getDouble("baselineMedian"), 0.01);
        assertEquals(3, temperature.getInt("sampleCount"));
    }

    @Test
    public void automationTraceIsBoundedToSelectedRuleAndRedacted() {
        JSONArray lines = GenAiContext.selectAutomationLines(
                "Automations: Adding automation to queue: rule-123\n"
                        + "Automations: ShellAction: refusing --token secret-value for rule-123\n"
                        + "Automations: Triggering automation actions: other-rule\n"
                        + "CameraDaemon: normal heartbeat\n",
                Set.of("rule-123"), 10);

        assertEquals(2, lines.length());
        String joined = lines.toString();
        assertTrue(joined.contains("rule-123"));
        assertTrue(joined.contains("[REDACTED]"));
        assertFalse(joined.contains("other-rule"));
        assertFalse(joined.contains("normal heartbeat"));
    }

    private static JSONObject trip(
            long id, double distanceKm,
            double energyPerKm, double speedKmh)
            throws Exception {
        return new JSONObject()
                .put("available", true)
                .put("id", id)
                .put("distanceKm", distanceKm)
                .put("energyPerKm", energyPerKm)
                .put("socStart", 80)
                .put("socEnd", 75)
                .put("avgSpeedKmh", speedKmh)
                .put("maxSpeedKmh", speedKmh + 20)
                .put("durationSeconds", 900)
                .put("smoothnessScore", 80)
                .put("anticipationScore", 80)
                .put("overallScore", 80);
    }

    private static JSONObject signal(
            JSONArray signals, String name) throws Exception {
        for (int i = 0; i < signals.length(); i++) {
            JSONObject signal = signals.getJSONObject(i);
            if (name.equals(signal.getString("name"))) return signal;
        }
        throw new AssertionError("Missing signal: " + name);
    }
}

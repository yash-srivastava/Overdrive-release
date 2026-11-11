package com.overdrive.app.genai;

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
    public void theLocationModeExistsOnlyForHandsFreeSessions() throws Exception {
        String page = GenAiContext.openAiRealtimeTool().toString();
        assertFalse(page.contains("\"location\""));
        assertEquals(page, GenAiContext.openAiRealtimeTool(false).toString());
        assertFalse(GenAiContext.geminiRealtimeTool().toString().contains("\"location\""));
        JSONArray farModes = GenAiContext.geminiRealtimeTool(true).getJSONObject("parameters")
                .getJSONObject("properties").getJSONObject("mode").getJSONArray("enum");
        assertEquals("location", farModes.getString(farModes.length() - 1));
        assertTrue(GenAiContext.openAiRealtimeTool(true).toString().contains("\"location\""));
        JSONObject ask = new JSONObject().put("mode", "location").put("query", "where am I");
        assertEquals(null, GenAiContext.realtimeToolRequest(ask));
        assertEquals(null, GenAiContext.realtimeToolRequest(ask, false));
        assertEquals("location", GenAiContext.realtimeToolRequest(ask, true).getString("mode"));
        // The chat/page mode table does not know it either.
        assertEquals(GenAiContext.GENERAL, GenAiContext.resolveMode("location", ""));
    }

    @Test
    public void handsFreeLocationResultsFollowTheToggleAndCarryTheLocalTime() throws Exception {
        JSONObject ask = new JSONObject().put("mode", "location").put("query", "");
        JSONObject off = GenAiContext.realtimeToolResult(ask, true, false);
        assertEquals("location", off.getString("mode"));
        assertFalse(off.getJSONObject("data").getBoolean("available"));
        assertTrue(off.getJSONObject("data").getString("reason").contains("turned off"));
        JSONObject time = off.getJSONObject("localTime");
        for (String key : new String[] {"date", "weekday", "time", "timeZone", "utcOffset"}) {
            assertTrue(key, time.has(key));
        }
        // The page path is unchanged: no location mode, no local time.
        JSONObject page = GenAiContext.realtimeToolResult(ask, false, true);
        assertFalse(page.optBoolean("available", true));
        assertFalse(page.has("localTime"));
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
                        + "/data/local/tmp/private.mp4 lat=12.345\n"
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
    public void diagnosticLogSelectionPreservesTsPackagesAndFiltersStaleTimestamps() {
        long nowMs = GenAiContext.parseDiagnosticLineTimestampMs(
                "+0100 2026-11-10 17:02:33 ERROR connection");
        assertTrue(nowMs > 0L);

        JSONArray lines = GenAiContext.selectDiagnosticLines(
                "[2026-11-03 08:06:53.084] BatteryPowerMonitor: SecurityException when binding "
                        + "Intent { act=android.Otasdk.OtaTsUpdateService cmp=com.ts.ota.otasdkmgr/.OtaService }\n"
                        + "at android.app.ContextImpl.bindServiceCommon(ContextImpl.java:1921)\n"
                        + "2026/11/03 07:02:27 controlhttp: all connection attempts failed\n"
                        + "[2026-11-10 16:55:00.120] BatteryPowerMonitor: SecurityException when binding "
                        + "com.ts.ota.otasdkmgr clip_001.ts\n"
                        + "+0100 2026-11-10 17:02:31 ERROR connection: i/o timeout\n",
                10,
                nowMs);

        assertEquals(2, lines.length());
        String joined = lines.toString();
        assertTrue(joined.contains("com.ts.ota.otasdkmgr"));
        assertFalse(joined.contains("[REDACTED_MEDIA].ota.otasdkmgr"));
        assertTrue(joined.contains("[REDACTED_MEDIA]"));
        assertFalse(joined.contains("clip_001.ts"));
        assertFalse(joined.contains("2026-11-03"));
        assertFalse(joined.contains("2026/11/03"));
        assertFalse(joined.contains("ContextImpl.bindServiceCommon"));
        assertTrue(joined.contains("2026-11-10 17:02:31"));
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

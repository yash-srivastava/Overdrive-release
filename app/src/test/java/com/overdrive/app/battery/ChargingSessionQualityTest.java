package com.overdrive.app.battery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class ChargingSessionQualityTest {
    private static JSONObject completed(double startSoc, double endSoc) throws Exception {
        return new JSONObject()
                .put("id", 42)
                .put("startTime", 1_000L)
                .put("endTime", 2_000L)
                .put("startSoc", startSoc)
                .put("endSoc", endSoc)
                .put("isDc", false)
                .put("peakPower", 155.0)
                .put("energyAdded", 61.2);
    }

    @Test
    public void realShapedEightToFullAcSessionIsCalibrationButPoisonedEnergyIsExcluded()
            throws Exception {
        JSONObject session = completed(8, 100);
        String originalEvidence = session.toString();

        ChargingSessionQuality.enrich(session, false, 3.65, "lfp");

        assertTrue(session.getJSONObject("calibration").getBoolean("qualified"));
        assertTrue(session.getJSONObject("calibration").getBoolean("readOnly"));
        assertTrue(session.getJSONObject("calibration").getBoolean("chargingEnergyExcluded"));
        assertEquals("poisoned", session.getString("powerDataQuality"));
        assertFalse(session.getBoolean("energyUsable"));

        // Classification is metadata only: every original historical value is unchanged.
        JSONObject original = new JSONObject(originalEvidence);
        for (String key : new String[]{"id", "startTime", "endTime", "startSoc",
                "endSoc", "isDc", "peakPower", "energyAdded"}) {
            Object before = original.get(key);
            Object after = session.get(key);
            if (before instanceof Number && after instanceof Number) {
                assertEquals(((Number) before).doubleValue(), ((Number) after).doubleValue(), 0.0);
            } else {
                assertEquals(before, after);
            }
        }
    }

    @Test
    public void calibrationDoesNotDependOnEnergyPeakTemperatureOrDuration() throws Exception {
        JSONObject session = completed(10, 99);
        session.remove("energyAdded");
        session.remove("peakPower");
        ChargingSessionQuality.enrich(session, false, Double.NaN, "lfp");
        assertTrue(session.getJSONObject("calibration").getBoolean("qualified"));
    }

    @Test
    public void missingChemistryNeverDefaultsToLfp() throws Exception {
        JSONObject session = completed(8, 100);
        ChargingSessionQuality.enrich(session, false, 3.45, "unknown");
        assertFalse(session.getJSONObject("calibration").getBoolean("qualified"));
        assertEquals("unknown", session.getJSONObject("calibration").getString("reason"));
    }

    @Test
    public void rejectsWrongSocChemistryDrivetrainAndIncompleteSessions() throws Exception {
        JSONObject highStart = completed(11, 100);
        ChargingSessionQuality.enrich(highStart, false, 3.65, "lfp");
        assertFalse(highStart.getJSONObject("calibration").getBoolean("qualified"));

        JSONObject lowEnd = completed(8, 98);
        ChargingSessionQuality.enrich(lowEnd, false, 3.65, "lfp");
        assertFalse(lowEnd.getJSONObject("calibration").getBoolean("qualified"));

        JSONObject phev = completed(8, 100);
        ChargingSessionQuality.enrich(phev, true, 3.65, "lfp");
        assertEquals("unsupported_phev", phev.getJSONObject("calibration").getString("reason"));

        JSONObject nmc = completed(8, 100);
        ChargingSessionQuality.enrich(nmc, false, 4.10, "nmc");
        assertEquals("unsupported_nmc", nmc.getJSONObject("calibration").getString("reason"));

        JSONObject conflict = completed(8, 100);
        ChargingSessionQuality.enrich(conflict, false, 4.10, "lfp");
        assertEquals("chemistry_conflict",
                conflict.getJSONObject("calibration").getString("reason"));

        JSONObject active = completed(8, 100).put("inProgress", true).put("endTime", 0L);
        ChargingSessionQuality.enrich(active, false, 3.65, "lfp");
        assertEquals("session_incomplete", active.getJSONObject("calibration").getString("reason"));
    }
}

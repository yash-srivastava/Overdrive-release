package com.overdrive.app.trips;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class TripRollupJsonTest {
    @Test
    public void weeklySummaryRetainsPeriodAndAllTotals() throws Exception {
        WeeklyRollup rollup = new WeeklyRollup();
        populate(rollup);
        rollup.weekNumber = 53;

        JSONObject json = rollup.toJson();
        assertEquals(53, json.getInt("weekNumber"));
        assertFalse(json.has("month"));
        assertTotals(json);
    }

    @Test
    public void monthlySummaryRetainsPeriodAndAllTotals() throws Exception {
        MonthlyRollup rollup = new MonthlyRollup();
        populate(rollup);
        rollup.month = 12;

        JSONObject json = rollup.toJson();
        assertEquals(12, json.getInt("month"));
        assertFalse(json.has("weekNumber"));
        assertTotals(json);
    }

    @Test
    public void emptySummariesRetainNumericZeroFields() throws Exception {
        WeeklyRollup weekly = new WeeklyRollup();
        weekly.year = 2026;
        weekly.weekNumber = 1;
        MonthlyRollup monthly = new MonthlyRollup();
        monthly.year = 2026;
        monthly.month = 1;

        JSONObject[] summaries = {weekly.toJson(), monthly.toJson()};
        for (JSONObject json : summaries) {
            assertEquals(14, json.length());
            assertEquals(2026, json.getInt("year"));
            for (String key : new String[]{"tripCount", "totalDistanceKm",
                    "totalDurationSeconds", "avgEfficiency", "totalEnergyKwh",
                    "totalCost", "avgEnergyPerKm", "avgAnticipation", "avgSmoothness",
                    "avgSpeedDiscipline", "avgEfficiencyScore", "avgConsistency"}) {
                assertEquals(key, 0.0, json.getDouble(key), 0.0);
            }
        }
        assertEquals(1, summaries[0].getInt("weekNumber"));
        assertEquals(1, summaries[1].getInt("month"));
    }

    private static void populate(TripRollup rollup) {
        rollup.year = 2026;
        rollup.tripCount = 3;
        rollup.totalDistanceKm = 125.5;
        rollup.totalDurationSeconds = 7200;
        rollup.avgEfficiency = 0.25;
        rollup.totalEnergyKwh = 25.1;
        rollup.totalCost = 7.53;
        rollup.avgEnergyPerKm = 0.2;
        rollup.avgAnticipation = 81;
        rollup.avgSmoothness = 82;
        rollup.avgSpeedDiscipline = 83;
        rollup.avgEfficiencyScore = 84;
        rollup.avgConsistency = 85;
    }

    private static void assertTotals(JSONObject json) throws Exception {
        assertEquals(14, json.length());
        assertEquals(2026, json.getInt("year"));
        assertEquals(3, json.getInt("tripCount"));
        assertEquals(125.5, json.getDouble("totalDistanceKm"), 0.0);
        assertEquals(7200, json.getInt("totalDurationSeconds"));
        assertEquals(0.25, json.getDouble("avgEfficiency"), 0.0);
        assertEquals(25.1, json.getDouble("totalEnergyKwh"), 0.0);
        assertEquals(7.53, json.getDouble("totalCost"), 0.0);
        assertEquals(0.2, json.getDouble("avgEnergyPerKm"), 0.0);
        assertEquals(81, json.getInt("avgAnticipation"));
        assertEquals(82, json.getInt("avgSmoothness"));
        assertEquals(83, json.getInt("avgSpeedDiscipline"));
        assertEquals(84, json.getInt("avgEfficiencyScore"));
        assertEquals(85, json.getInt("avgConsistency"));
    }
}

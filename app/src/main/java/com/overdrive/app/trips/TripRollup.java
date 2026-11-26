package com.overdrive.app.trips;

import org.json.JSONObject;

/** Shared fields and JSON representation of weekly and monthly trip summaries. */
public abstract class TripRollup {

    public int year;
    public int tripCount;
    public double totalDistanceKm;
    public int totalDurationSeconds;
    public double avgEfficiency;
    public double totalEnergyKwh;      // Sum of energyUsedKwh across trips
    public double totalCost;           // Sum of tripCost across trips
    public double avgEnergyPerKm;      // Average kWh/km
    public int avgAnticipation;
    public int avgSmoothness;
    public int avgSpeedDiscipline;
    public int avgEfficiencyScore;
    public int avgConsistency;

    /** Serialize common fields using the concrete summary's existing period key. */
    protected JSONObject toJson(String periodKey, int period) {
        JSONObject json = new JSONObject();
        try {
            json.put("year", year);
            json.put(periodKey, period);
            json.put("tripCount", tripCount);
            json.put("totalDistanceKm", totalDistanceKm);
            json.put("totalDurationSeconds", totalDurationSeconds);
            json.put("avgEfficiency", avgEfficiency);
            json.put("totalEnergyKwh", totalEnergyKwh);
            json.put("totalCost", totalCost);
            json.put("avgEnergyPerKm", avgEnergyPerKm);
            json.put("avgAnticipation", avgAnticipation);
            json.put("avgSmoothness", avgSmoothness);
            json.put("avgSpeedDiscipline", avgSpeedDiscipline);
            json.put("avgEfficiencyScore", avgEfficiencyScore);
            json.put("avgConsistency", avgConsistency);
        } catch (Exception e) {
            // Preserve the fields already written if a value is not JSON-compatible.
        }
        return json;
    }
}

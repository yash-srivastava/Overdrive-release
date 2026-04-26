package com.overdrive.app.trips;

import org.json.JSONObject;

/**
 * Mutable record representing a trip from start to finalization.
 * Contains all trip summary fields, Driving DNA scores, and references
 * to micro-moments JSON and telemetry file.
 */
public class TripRecord {

    public long id;                    // Auto-increment PK
    public long startTime;             // Epoch ms
    public long endTime;               // Epoch ms
    public double distanceKm;          // Odometer delta
    public int durationSeconds;
    public double avgSpeedKmh;
    public int maxSpeedKmh;
    public double socStart;            // %
    public double socEnd;              // %
    public double kwhStart;            // Remaining kWh at trip start (from BMS)
    public double kwhEnd;              // Remaining kWh at trip end (from BMS)
    public double efficiencySocPerKm;  // SoC% / km (legacy)
    public double energyPerKm;         // kWh / km (from BMS kWh readings)
    public double electricityRate;     // Cost per kWh at time of trip
    public String currency;            // Currency symbol (₹, $, €, £)
    public double tripCost;            // Total trip cost (energyUsed × rate)
    public String kinematicState;      // HEAVY_GRIDLOCK, URBAN_FLOW, HIGHWAY_CRUISING
    public String gradientProfile;     // FLAT, HILLY, MOUNTAIN (terrain classification)
    public double elevationGainM;      // Cumulative meters gained (uphill)
    public double elevationLossM;      // Cumulative meters lost (downhill)
    public double avgGradientPercent;  // Average gradient over the trip
    public double startLat, startLon;
    public double endLat, endLon;
    public int extTempC;

    // Driving DNA scores (0-100)
    public int anticipationScore;
    public int smoothnessScore;
    public int speedDisciplineScore;
    public int efficiencyScore;
    public int consistencyScore;

    public String microMomentsJson;    // JSON blob
    public String telemetryFilePath;   // Path to .jsonl.gz
    public long routeId = -1;          // Route cluster ID for O(1) similar-trip lookups

    /**
     * Compute the overall Driving DNA score as the average of all 5 axis scores.
     */
    public int getOverallScore() {
        return (int) Math.round((anticipationScore + smoothnessScore + speedDisciplineScore
                + efficiencyScore + consistencyScore) / 5.0);
    }

    /**
     * Get the actual energy consumed in kWh from BMS readings.
     * Returns 0 if kWh data not available (caller should use SoC-based estimation).
     */
    public double getEnergyUsedKwh() {
        if (kwhStart > 0 && kwhEnd > 0 && kwhStart > kwhEnd) {
            return kwhStart - kwhEnd;
        }
        return 0;
    }

    /**
     * Serialize all fields to JSON (full detail, including micro-moments).
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            json.put("startTime", startTime);
            json.put("endTime", endTime);
            json.put("distanceKm", distanceKm);
            json.put("durationSeconds", durationSeconds);
            json.put("avgSpeedKmh", avgSpeedKmh);
            json.put("maxSpeedKmh", maxSpeedKmh);
            json.put("socStart", socStart);
            json.put("socEnd", socEnd);
            json.put("kwhStart", kwhStart);
            json.put("kwhEnd", kwhEnd);
            json.put("energyUsedKwh", getEnergyUsedKwh());
            json.put("efficiencySocPerKm", efficiencySocPerKm);
            json.put("energyPerKm", energyPerKm);
            json.put("electricityRate", electricityRate);
            json.put("currency", currency != null ? currency : "");
            json.put("tripCost", tripCost);
            json.put("kinematicState", kinematicState != null ? kinematicState : "");
            json.put("gradientProfile", gradientProfile != null ? gradientProfile : "");
            json.put("elevationGainM", elevationGainM);
            json.put("elevationLossM", elevationLossM);
            json.put("avgGradientPercent", avgGradientPercent);
            json.put("startLat", startLat);
            json.put("startLon", startLon);
            json.put("endLat", endLat);
            json.put("endLon", endLon);
            json.put("extTempC", extTempC);
            json.put("anticipationScore", anticipationScore);
            json.put("smoothnessScore", smoothnessScore);
            json.put("speedDisciplineScore", speedDisciplineScore);
            json.put("efficiencyScore", efficiencyScore);
            json.put("consistencyScore", consistencyScore);
            json.put("overallScore", getOverallScore());
            json.put("microMomentsJson", microMomentsJson != null ? microMomentsJson : "");
            json.put("telemetryFilePath", telemetryFilePath != null ? telemetryFilePath : "");
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }
        return json;
    }

    /**
     * Serialize to summary JSON (excludes microMomentsJson for list views).
     */
    public JSONObject toSummaryJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            json.put("startTime", startTime);
            json.put("endTime", endTime);
            json.put("distanceKm", distanceKm);
            json.put("durationSeconds", durationSeconds);
            json.put("avgSpeedKmh", avgSpeedKmh);
            json.put("maxSpeedKmh", maxSpeedKmh);
            json.put("socStart", socStart);
            json.put("socEnd", socEnd);
            json.put("kwhStart", kwhStart);
            json.put("kwhEnd", kwhEnd);
            json.put("energyUsedKwh", getEnergyUsedKwh());
            json.put("efficiencySocPerKm", efficiencySocPerKm);
            json.put("energyPerKm", energyPerKm);
            json.put("electricityRate", electricityRate);
            json.put("currency", currency != null ? currency : "");
            json.put("tripCost", tripCost);
            json.put("kinematicState", kinematicState != null ? kinematicState : "");
            json.put("gradientProfile", gradientProfile != null ? gradientProfile : "");
            json.put("elevationGainM", elevationGainM);
            json.put("elevationLossM", elevationLossM);
            json.put("avgGradientPercent", avgGradientPercent);
            json.put("startLat", startLat);
            json.put("startLon", startLon);
            json.put("endLat", endLat);
            json.put("endLon", endLon);
            json.put("extTempC", extTempC);
            json.put("anticipationScore", anticipationScore);
            json.put("smoothnessScore", smoothnessScore);
            json.put("speedDisciplineScore", speedDisciplineScore);
            json.put("efficiencyScore", efficiencyScore);
            json.put("consistencyScore", consistencyScore);
            json.put("overallScore", getOverallScore());
        } catch (Exception e) {
            // JSONObject.put only throws on null key
        }
        return json;
    }
}

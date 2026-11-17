package com.overdrive.app.trips;

import org.json.JSONObject;

/**
 * Result of a personalized range prediction: the predicted range with its 80% band, what it
 * rests on, and (electric leg) the trips-only band the calibration slider spans.
 */
public class RangeEstimate {

    public double predictedRangeKm;
    public double lowerBoundKm;
    public double upperBoundKm;
    public String bucketKey;
    public int sampleCount;
    public int builtInRangeKm;

    /** Litres instead of kWh: the petrol leg of a hybrid. */
    public boolean fuel;
    public double basisKm = Double.NaN;
    /** kWh/km (or L/km) behind {@link #predictedRangeKm}. */
    public double rate = Double.NaN;
    public double tempC = Double.NaN;
    public double tempFactor = Double.NaN;
    /** kWh (or litres) the prediction spends. */
    public double usable = Double.NaN;
    public double socPercent = Double.NaN;
    public double liveShare;
    /** The driver's calibration, when it still carries weight. */
    public RangeCalibration calibration;
    public double calibrationShare;

    public double tripsRate = Double.NaN;
    public double tripsLowRate = Double.NaN;
    public double tripsHighRate = Double.NaN;
    public double tripsRangeKm = Double.NaN;
    public double tripsLowKm = Double.NaN;
    public double tripsHighKm = Double.NaN;

    /**
     * Serialize to JSON for API responses.
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("predictedRangeKm", predictedRangeKm);
            json.put("lowerBoundKm", lowerBoundKm);
            json.put("upperBoundKm", upperBoundKm);
            json.put("bucketKey", bucketKey != null ? bucketKey : "");
            json.put("sampleCount", sampleCount);
            json.put("builtInRangeKm", builtInRangeKm);
            json.put("model", "personal-v2");
            putFinite(json, "basisKm", basisKm);
            putFinite(json, fuel ? "litresPer100Km" : "kwhPer100Km", rate * 100.0);
            putFinite(json, "tempC", tempC);
            putFinite(json, "tempFactor", tempFactor);
            putFinite(json, fuel ? "usableLitres" : "usableKwh", usable);
            if (fuel) return json;
            putFinite(json, "socPercent", socPercent);
            if (liveShare > 0) json.put("liveSharePct", Math.round(liveShare * 100.0));
            if (calibration != null) {
                JSONObject cal = new JSONObject();
                putFinite(cal, "pickedRangeKm", calibration.pickedRangeKm);
                putFinite(cal, "pickedSocPercent", calibration.pickedSocPercent);
                putFinite(cal, "pickedKwhPer100Km", calibration.pickedRateKwhPerKm * 100.0);
                cal.put("setAtMs", calibration.setAtMs);
                cal.put("sharePct", Math.round(calibrationShare * 100.0));
                json.put("calibration", cal);
            }
            if (Double.isFinite(tripsRangeKm) && Double.isFinite(tripsLowKm) && Double.isFinite(tripsHighKm)) {
                JSONObject slider = new JSONObject();
                slider.put("tripsRangeKm", tripsRangeKm);
                slider.put("minRangeKm", tripsLowKm);
                slider.put("maxRangeKm", tripsHighKm);
                putFinite(slider, "tripsKwhPer100Km", tripsRate * 100.0);
                putFinite(slider, "minKwhPer100Km", tripsLowRate * 100.0);
                putFinite(slider, "maxKwhPer100Km", tripsHighRate * 100.0);
                json.put("calibrate", slider);
            }
        } catch (Exception e) {
            // JSONObject.put only throws on null key or a non-finite number
        }
        return json;
    }

    private static void putFinite(JSONObject json, String key, double value) throws Exception {
        if (Double.isFinite(value)) json.put(key, value);
    }
}

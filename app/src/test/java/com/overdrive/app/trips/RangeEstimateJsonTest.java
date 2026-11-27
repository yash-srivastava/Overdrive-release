package com.overdrive.app.trips;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class RangeEstimateJsonTest {
    private static final double EPS = 1e-9;

    @Test
    public void electricEstimateIncludesCalibrationAndCorrectUnits() throws Exception {
        RangeEstimate estimate = populatedEstimate();
        JSONObject json = estimate.toJson();

        assertCommonFields(json);
        assertEquals(20.0, json.getDouble("kwhPer100Km"), EPS);
        assertEquals(60.0, json.getDouble("usableKwh"), EPS);
        assertEquals(75.0, json.getDouble("socPercent"), EPS);
        assertEquals(33, json.getInt("liveSharePct"));
        assertFalse(json.has("litresPer100Km"));
        assertFalse(json.has("usableLitres"));

        JSONObject calibration = json.getJSONObject("calibration");
        assertEquals(320.0, calibration.getDouble("pickedRangeKm"), EPS);
        assertEquals(80.0, calibration.getDouble("pickedSocPercent"), EPS);
        assertEquals(18.0, calibration.getDouble("pickedKwhPer100Km"), EPS);
        assertEquals(1700000000000L, calibration.getLong("setAtMs"));
        assertEquals(68, calibration.getInt("sharePct"));

        JSONObject slider = json.getJSONObject("calibrate");
        assertEquals(300.0, slider.getDouble("tripsRangeKm"), EPS);
        assertEquals(250.0, slider.getDouble("minRangeKm"), EPS);
        assertEquals(375.0, slider.getDouble("maxRangeKm"), EPS);
        assertEquals(20.0, slider.getDouble("tripsKwhPer100Km"), EPS);
        assertEquals(16.0, slider.getDouble("minKwhPer100Km"), EPS);
        assertEquals(24.0, slider.getDouble("maxKwhPer100Km"), EPS);
    }

    @Test
    public void fuelEstimateOmitsElectricOnlyFieldsEvenWhenPopulated() throws Exception {
        RangeEstimate estimate = populatedEstimate();
        estimate.fuel = true;
        estimate.rate = 0.075;
        estimate.usable = 22.5;
        JSONObject json = estimate.toJson();

        assertCommonFields(json);
        assertEquals(7.5, json.getDouble("litresPer100Km"), EPS);
        assertEquals(22.5, json.getDouble("usableLitres"), EPS);
        assertAbsent(json, "kwhPer100Km", "usableKwh", "socPercent", "liveSharePct",
                "calibration", "calibrate");
    }

    @Test
    public void unavailableOptionalValuesAreOmittedWithoutLosingLaterFields() throws Exception {
        RangeEstimate estimate = new RangeEstimate();
        estimate.basisKm = Double.POSITIVE_INFINITY;
        estimate.rate = Double.NEGATIVE_INFINITY;
        estimate.tempFactor = 1.0;
        estimate.usable = 0.0;
        JSONObject json = estimate.toJson();

        assertEquals("", json.getString("bucketKey"));
        assertEquals("personal-v2", json.getString("model"));
        assertAbsent(json, "basisKm", "kwhPer100Km", "tempC", "socPercent",
                "liveSharePct", "calibration", "calibrate");
        assertEquals(1.0, json.getDouble("tempFactor"), EPS);
        assertEquals(0.0, json.getDouble("usableKwh"), EPS);
    }

    @Test
    public void incompleteSliderBoundsDoNotHideManualCalibration() throws Exception {
        RangeEstimate estimate = populatedEstimate();
        estimate.tripsHighKm = Double.NaN;
        JSONObject json = estimate.toJson();
        assertFalse(json.has("calibrate"));
        assertEquals(320.0, json.getJSONObject("calibration").getDouble("pickedRangeKm"), EPS);
    }

    private static RangeEstimate populatedEstimate() {
        RangeEstimate estimate = new RangeEstimate();
        estimate.predictedRangeKm = 300.0;
        estimate.lowerBoundKm = 250.0;
        estimate.upperBoundKm = 375.0;
        estimate.bucketKey = "suburban_mild_high";
        estimate.sampleCount = 12;
        estimate.builtInRangeKm = 310;
        estimate.basisKm = 125.5;
        estimate.rate = 0.2;
        estimate.tempC = 18.0;
        estimate.tempFactor = 1.05;
        estimate.usable = 60.0;
        estimate.socPercent = 75.0;
        estimate.liveShare = 0.334;
        estimate.calibration = new RangeCalibration(0.17, 0.18, 320.0, 80.0, 1700000000000L);
        estimate.calibrationShare = 0.676;
        estimate.tripsRate = 0.2;
        estimate.tripsLowRate = 0.16;
        estimate.tripsHighRate = 0.24;
        estimate.tripsRangeKm = 300.0;
        estimate.tripsLowKm = 250.0;
        estimate.tripsHighKm = 375.0;
        return estimate;
    }

    private static void assertCommonFields(JSONObject json) throws Exception {
        assertEquals(300.0, json.getDouble("predictedRangeKm"), EPS);
        assertEquals(250.0, json.getDouble("lowerBoundKm"), EPS);
        assertEquals(375.0, json.getDouble("upperBoundKm"), EPS);
        assertEquals("suburban_mild_high", json.getString("bucketKey"));
        assertEquals(12, json.getInt("sampleCount"));
        assertEquals(310, json.getInt("builtInRangeKm"));
        assertEquals("personal-v2", json.getString("model"));
        assertEquals(125.5, json.getDouble("basisKm"), EPS);
        assertEquals(18.0, json.getDouble("tempC"), EPS);
        assertEquals(1.05, json.getDouble("tempFactor"), EPS);
    }

    private static void assertAbsent(JSONObject json, String... keys) {
        for (String key : keys) {
            assertFalse(key, json.has(key));
        }
    }
}

package com.overdrive.app.charging;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TariffProfileTest {
    private static final double EPS = 1e-9;

    @Test
    public void onlyConfirmedDcUsesDcRate() {
        TariffProfile profile = new TariffProfile("Home", 47.0, 8.0, 50, 0.25, 0.60, "CHF");
        assertEquals(0.25, profile.rateFor(0), EPS);
        assertEquals(0.25, profile.rateFor(-1), EPS);
        assertEquals(0.60, profile.rateFor(1), EPS);
        assertTrue(profile.hasAnyRate());

        profile.setDcRate(0);
        assertEquals(0.25, profile.rateFor(1), EPS);
        profile.setAcRate(0);
        assertFalse(profile.hasAnyRate());
        assertEquals(0.0, profile.rateFor(1), EPS);

        profile.setDcRate(0.60);
        assertTrue(profile.hasAnyRate());
        assertEquals(0.0, profile.rateFor(-1), EPS);
        assertEquals(0.0, profile.rateFor(0), EPS);
        assertEquals(0.60, profile.rateFor(1), EPS);
    }

    @Test
    public void constructorAndSettersClampInvalidValues() {
        TariffProfile profile = new TariffProfile(null, 91, -181, 1,
                Double.NaN, Double.POSITIVE_INFINITY, null);
        assertEquals("", profile.getLabel());
        assertEquals("", profile.getCurrency());
        assertEquals(0.0, profile.getLatitude(), EPS);
        assertEquals(0.0, profile.getLongitude(), EPS);
        assertEquals(TariffProfile.MIN_RADIUS_M, profile.getRadiusMeters());
        assertFalse(profile.hasAnyRate());

        profile.setLatitude(-90);
        profile.setLongitude(180);
        profile.setRadiusMeters(Integer.MAX_VALUE);
        assertEquals(-90.0, profile.getLatitude(), EPS);
        assertEquals(180.0, profile.getLongitude(), EPS);
        assertEquals(TariffProfile.MAX_RADIUS_M, profile.getRadiusMeters());
        profile.setLatitude(Double.NEGATIVE_INFINITY);
        profile.setLongitude(Double.NaN);
        assertEquals(0.0, profile.getLatitude(), EPS);
        assertEquals(0.0, profile.getLongitude(), EPS);

        for (double invalid : new double[] {-1, 0, 100000, Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            profile.setAcRate(0.25);
            profile.setDcRate(0.60);
            profile.setAcRate(invalid);
            profile.setDcRate(invalid);
            assertEquals(0.0, profile.getAcRate(), EPS);
            assertEquals(0.0, profile.getDcRate(), EPS);
        }
    }

    @Test
    public void jsonLoadAppliesSameValidationAndDefaults() throws Exception {
        JSONObject json = new JSONObject()
                .put("label", "  Home\nGarage  ")
                .put("lat", "NaN")
                .put("lng", 181)
                .put("radiusM", 3000)
                .put("acRate", -1)
                .put("dcRate", 100000)
                .put("useCount", -2);
        TariffProfile profile = new TariffProfile(json);
        assertEquals("Home Garage", profile.getLabel());
        assertEquals(0.0, profile.getLatitude(), EPS);
        assertEquals(0.0, profile.getLongitude(), EPS);
        assertEquals(TariffProfile.MAX_RADIUS_M, profile.getRadiusMeters());
        assertFalse(profile.hasAnyRate());
        assertEquals(0, profile.getUseCount());
        assertTrue(profile.isEnabled());
        assertEquals("", profile.getCurrency());
        assertEquals(TariffProfile.DEFAULT_RADIUS_M,
                new TariffProfile(new JSONObject()).getRadiusMeters());
    }

    @Test
    public void usageCountAdvancesWithoutMovingLastUsedBackwards() {
        TariffProfile profile = new TariffProfile("Office", 47, 8, 50, 0.25, 0, "CHF");
        assertEquals(0, profile.getUseCount());
        assertEquals(0L, profile.getLastUsedAt());
        profile.markUsed(2000L);
        profile.markUsed(1000L);
        assertEquals(2, profile.getUseCount());
        assertEquals(2000L, profile.getLastUsedAt());
        profile.markUsed(3000L);
        assertEquals(3, profile.getUseCount());
        assertEquals(3000L, profile.getLastUsedAt());
    }

    @Test
    public void jsonRoundTripPreservesProfileAndUsage() throws Exception {
        TariffProfile original = new TariffProfile("Office", 47.25, -8.5, 125,
                0.25, 0.60, "CHF ");
        original.setEnabled(false);
        original.markUsed(1000L);
        original.markUsed(2000L);
        TariffProfile restored = new TariffProfile(new JSONObject(original.toJson().toString()));
        assertEquals(original.getId(), restored.getId());
        assertEquals(original.getLabel(), restored.getLabel());
        assertEquals(original.getLatitude(), restored.getLatitude(), EPS);
        assertEquals(original.getLongitude(), restored.getLongitude(), EPS);
        assertEquals(original.getRadiusMeters(), restored.getRadiusMeters());
        assertEquals(original.getAcRate(), restored.getAcRate(), EPS);
        assertEquals(original.getDcRate(), restored.getDcRate(), EPS);
        assertEquals(original.getCurrency(), restored.getCurrency());
        assertFalse(restored.isEnabled());
        assertEquals(original.getCreatedAt(), restored.getCreatedAt());
        assertEquals(2000L, restored.getLastUsedAt());
        assertEquals(2, restored.getUseCount());
    }
}

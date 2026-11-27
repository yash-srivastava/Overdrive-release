package com.overdrive.app.trips;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TripApiHandlerTest {
    @Test
    public void invalidElectricityRatesRejectEntireUpdateBeforeMutation() throws Exception {
        TrackingManager manager = new TrackingManager();
        TripApiHandler handler = new TripApiHandler(manager);
        String[] invalidRates = {"-1", "100000", "100001", "1e100", "\"NaN\"",
                "\"Infinity\"", "\"-Infinity\"", "\"not a rate\"", "null", "true", "{}", "[]"};

        for (String rate : invalidRates) {
            JSONObject response = post(handler, "{\"electricityRate\":" + rate
                    + ",\"enabled\":false,\"currency\":\"EUR\",\"fuelPricePerL\":9}");

            assertFalse(rate, response.getBoolean("success"));
            assertEquals(rate, 400, response.getInt("_status"));
            assertTrue(rate, response.getString("error").contains("electricityRate"));
            assertEquals(rate, 2.5, manager.config.getElectricityRate(), 0);
            assertEquals(rate, "USD", manager.config.getCurrency());
            assertEquals(rate, 1.75, manager.config.getFuelPricePerL(), 0);
            assertTrue(rate, manager.config.isEnabled());
            assertEquals(rate, 0, manager.enabledUpdates);
            assertEquals(rate, 0, manager.config.saves);
        }
    }

    @Test
    public void validElectricityRatesAndExplicitZeroAreSaved() throws Exception {
        TrackingManager manager = new TrackingManager();
        TripApiHandler handler = new TripApiHandler(manager);

        for (double rate : new double[]{0.001, 0.30, 99999.99, 0}) {
            JSONObject response = post(handler, new JSONObject().put("electricityRate", rate)
                    .put("currency", "EUR").toString());
            assertTrue(response.getBoolean("success"));
            assertEquals(rate, manager.config.getElectricityRate(), 0);
            assertEquals("EUR", manager.config.getCurrency());
        }
        assertEquals(4, manager.config.saves);
    }

    @Test
    public void omittedElectricityRateDoesNotPreventOtherSettingsUpdates() throws Exception {
        TrackingManager manager = new TrackingManager();
        TripApiHandler handler = new TripApiHandler(manager);

        JSONObject response = post(handler, "{\"currency\":\"EUR\"}");

        assertTrue(response.getBoolean("success"));
        assertEquals(2.5, manager.config.getElectricityRate(), 0);
        assertEquals("EUR", manager.config.getCurrency());
        assertEquals(1, manager.config.saves);
    }

    private static JSONObject post(TripApiHandler handler, String body) {
        return handler.handleRequest("/api/trips/config", "POST", null, body);
    }

    private static final class TrackingManager extends TripAnalyticsManager {
        final TrackingConfig config = new TrackingConfig();
        int enabledUpdates;

        TrackingManager() {
            config.setEnabled(true);
            config.setElectricityRate(2.5);
            config.setCurrency("USD");
            config.setFuelPricePerL(1.75);
        }

        @Override
        public TripConfig getConfig() {
            return config;
        }

        @Override
        public void onConfigChanged(boolean enabled) {
            enabledUpdates++;
            config.setEnabled(enabled);
        }
    }

    private static final class TrackingConfig extends TripConfig {
        int saves;

        @Override
        public boolean save() {
            saves++;
            return true;
        }
    }
}

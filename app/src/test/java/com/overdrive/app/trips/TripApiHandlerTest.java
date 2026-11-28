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

    @Test
    public void invalidFuelSettingsRejectEntireUpdateBeforeMutation() throws Exception {
        TrackingManager manager = new TrackingManager();
        TripApiHandler handler = new TripApiHandler(manager);
        String[] invalidValues = {"-1", "1e100", "\"NaN\"", "\"Infinity\"", "\"-Infinity\"",
                "\"not a number\"", "null", "true", "{}", "[]"};

        for (String field : new String[]{"tankCapacityL", "fuelPricePerL"}) {
            for (String value : invalidValues) {
                assertInvalidFuelSetting(handler, manager, field, value);
            }
            assertInvalidFuelSetting(handler, manager, field,
                    "tankCapacityL".equals(field) ? "200" : "100000");
        }
    }

    private static void assertInvalidFuelSetting(TripApiHandler handler, TrackingManager manager,
                                                String field, String value) throws Exception {
        String otherField = "tankCapacityL".equals(field) ? "fuelPricePerL" : "tankCapacityL";
        JSONObject response = post(handler, "{\"" + field + "\":" + value
                + ",\"" + otherField + "\":10,\"enabled\":false,\"currency\":\"EUR\",\"electricityRate\":3}");
        String label = field + "=" + value;
        assertFalse(label, response.getBoolean("success"));
        assertEquals(label, 400, response.getInt("_status"));
        assertTrue(label, response.getString("error").contains(field));
        assertEquals(label, 40, manager.config.getTankCapacityL(), 0);
        assertEquals(label, 1.75, manager.config.getFuelPricePerL(), 0);
        assertEquals(label, 2.5, manager.config.getElectricityRate(), 0);
        assertEquals(label, "USD", manager.config.getCurrency());
        assertTrue(label, manager.config.isEnabled());
        assertEquals(label, 0, manager.enabledUpdates);
        assertEquals(label, 0, manager.config.saves);
    }

    @Test
    public void validFuelSettingsAndExplicitZeroAreSaved() throws Exception {
        TrackingManager manager = new TrackingManager();
        TripApiHandler handler = new TripApiHandler(manager);

        for (double[] values : new double[][]{{0.001, 0.001}, {60, 1.75}, {199.99, 99999.99}, {0, 0}}) {
            JSONObject response = post(handler, new JSONObject().put("tankCapacityL", values[0])
                    .put("fuelPricePerL", values[1]).toString());
            assertTrue(response.getBoolean("success"));
            assertEquals(values[0], manager.config.getTankCapacityL(), 0);
            assertEquals(values[1], manager.config.getFuelPricePerL(), 0);
        }
        assertEquals(4, manager.config.saves);
    }

    @Test
    public void omittedFuelSettingsRemainUnchangedAndNumericStringsAreAccepted() throws Exception {
        TrackingManager manager = new TrackingManager();
        TripApiHandler handler = new TripApiHandler(manager);

        assertTrue(post(handler, "{\"tankCapacityL\":\"50.5\"}").getBoolean("success"));
        assertEquals(50.5, manager.config.getTankCapacityL(), 0);
        assertEquals(1.75, manager.config.getFuelPricePerL(), 0);
        assertTrue(post(handler, "{\"fuelPricePerL\":\"2.25\"}").getBoolean("success"));
        assertEquals(50.5, manager.config.getTankCapacityL(), 0);
        assertEquals(2.25, manager.config.getFuelPricePerL(), 0);
        assertEquals(2, manager.config.saves);
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
            config.setTankCapacityL(40);
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

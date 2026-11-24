package com.overdrive.app.trips;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TripPricingTest {
    private final TripConfig config = new TripConfig();
    private final TripRecord trip = new TripRecord();

    public TripPricingTest() {
        config.setElectricityRate(0.30);
        config.setCurrency("€");
        config.setFuelPricePerL(1.75);
        config.setTankCapacityL(40);
    }

    @Test
    public void electricTripUsesChargeCurrencyAndRetainsCents() throws Exception {
        JSONObject charge = new JSONObject().put("rate", .11).put("currency", "CHF ").put("tariffLabel", "Garage");
        TripPricing.apply(trip, 2, config, charge);
        assertEquals("CHF ", trip.currency);
        assertEquals("charge", trip.rateSource);
        assertEquals("Garage", trip.rateLabel);
        assertEquals(.22, trip.tripCost, .000001);
    }

    @Test
    public void meteredHybridFuelKeepsBothLegsInOneCurrency() throws Exception {
        trip.isPhev = true;
        trip.fuelConStart = 100;
        trip.fuelConEnd = 101;
        trip.fuelPctStart = 80;
        trip.fuelPctEnd = 70;
        JSONObject charge = new JSONObject().put("rate", .11).put("currency", "CHF ");
        TripPricing.apply(trip, 2, config, charge);
        assertEquals("€", trip.currency);
        assertEquals("config", trip.rateSource);
        assertEquals(2.35, trip.tripCost, .000001);
        // Same-currency tariff applies to the electric leg alongside metered petrol.
        charge.put("currency", "€");
        TripPricing.apply(trip, 2, config, charge);
        assertEquals(1.97, trip.tripCost, .000001);
    }

    @Test
    public void petrolUsesGaugeAfterCounterResetAndHonoursMeasuredZero() {
        trip.isPhev = true;
        trip.fuelConStart = 20;
        trip.fuelConEnd = 10;
        trip.fuelPctStart = 80;
        trip.fuelPctEnd = 77;
        TripPricing.apply(trip, 0, config, null);
        assertEquals(2.10, trip.tripCost, .000001);
        trip.fuelConEnd = 20;
        TripPricing.apply(trip, 0, config, null);
        assertEquals(0, trip.tripCost, 0);
    }
}

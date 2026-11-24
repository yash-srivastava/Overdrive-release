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

    @Test
    public void nonFiniteCountersFallBackToValidGauge() {
        trip.fuelPctStart = 80;
        trip.fuelPctEnd = 75;
        trip.fuelConStart = 10;
        for (double invalid : new double[]{Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN}) {
            trip.fuelConEnd = invalid;
            assertEquals(2.0, TripPricing.litresUsed(trip, 40), .000001);
            trip.fuelConStart = invalid;
            trip.fuelConEnd = 12;
            assertEquals(2.0, TripPricing.litresUsed(trip, 40), .000001);
            trip.fuelConStart = 10;
        }
        trip.fuelConStart = Double.POSITIVE_INFINITY;
        trip.fuelConEnd = Double.POSITIVE_INFINITY;
        assertEquals(2.0, TripPricing.litresUsed(trip, 40), .000001);
    }

    @Test
    public void invalidGaugePercentagesDoNotProduceFuelUsage() {
        for (double invalid : new double[]{101, 255, -1, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.NaN}) {
            trip.fuelPctStart = invalid;
            trip.fuelPctEnd = 50;
            assertEquals(0, TripPricing.litresUsed(trip, 40), 0);
            trip.fuelPctStart = 100;
            trip.fuelPctEnd = invalid;
            assertEquals(0, TripPricing.litresUsed(trip, 40), 0);
        }
    }

    @Test
    public void gaugeFallbackRequiresFinitePositiveTankCapacity() {
        trip.fuelPctStart = 100;
        trip.fuelPctEnd = 0;
        for (double invalid : new double[]{0, -1, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.NaN}) {
            assertEquals(0, TripPricing.litresUsed(trip, invalid), 0);
        }
        assertEquals(40, TripPricing.litresUsed(trip, 40), 0);
    }

    @Test
    public void validCounterRemainsAuthoritativeEvenWithInvalidGauge() {
        trip.fuelConStart = 10;
        trip.fuelConEnd = 12;
        trip.fuelPctStart = 255;
        trip.fuelPctEnd = -1;
        assertEquals(2, TripPricing.litresUsed(trip, Double.NaN), 0);
        trip.fuelConEnd = 10;
        assertEquals(0, TripPricing.litresUsed(trip, 40), 0);
    }

    @Test
    public void invalidFuelCounterDoesNotPoisonTripCost() {
        trip.isPhev = true;
        trip.fuelConStart = 10;
        trip.fuelConEnd = Double.POSITIVE_INFINITY;
        trip.fuelPctStart = 80;
        trip.fuelPctEnd = 75;
        TripPricing.apply(trip, 2, config, null);
        assertEquals(2, trip.litresUsed, .000001);
        assertEquals(3.50, trip.fuelCost, .000001);
        assertEquals(4.10, trip.tripCost, .000001);
    }

}

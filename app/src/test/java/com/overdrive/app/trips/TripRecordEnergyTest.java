package com.overdrive.app.trips;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TripRecordEnergyTest {
    private static final double EPS = 1e-9;

    @Test
    public void netBatteryDrawTakesPrecedenceOverGrossMeteredDraw() {
        TripRecord trip = tripWithReadings(40, 38, 100, 103);

        assertTrue(trip.hasMeteredEnergy());
        assertEquals(3, trip.getMeteredEnergyKwh(), EPS);
        assertEquals(2, trip.getEnergyUsedKwh(), EPS);
        assertEquals(2, trip.getSignedEnergyKwh(), EPS);
        assertEquals(2, trip.getResolvedEnergyKwh(), EPS);
    }

    @Test
    public void netRegenerationIsSignedButNotBilledAsGrossDraw() {
        TripRecord trip = tripWithReadings(38, 40, 100, 101);

        assertEquals(1, trip.getMeteredEnergyKwh(), EPS);
        assertEquals(0, trip.getEnergyUsedKwh(), EPS);
        assertEquals(-2, trip.getSignedEnergyKwh(), EPS);
        assertEquals(0, trip.getResolvedEnergyKwh(), EPS);
    }

    @Test
    public void flatBatteryReadingFallsThroughToMeteredEnergy() {
        TripRecord trip = tripWithReadings(40, 40, 100, 100.25);

        assertEquals(0.25, trip.getEnergyUsedKwh(), EPS);
        assertEquals(0.25, trip.getSignedEnergyKwh(), EPS);
        assertEquals(0.25, trip.getResolvedEnergyKwh(), EPS);
    }

    @Test
    public void missingBatteryReadingsStillAllowMeteredEnergy() {
        TripRecord trip = tripWithReadings(0, 0, 100, 101.5);
        trip.distanceKm = 10;
        trip.energyPerKm = 0.2;

        assertTrue(trip.hasMeteredEnergy());
        assertEquals(1.5, trip.getEnergyUsedKwh(), EPS);
        assertEquals(1.5, trip.getSignedEnergyKwh(), EPS);
        assertEquals(1.5, trip.getResolvedEnergyKwh(), EPS);
    }

    @Test
    public void flatMeterIsAvailableEvenWhenConsumptionIsZero() {
        TripRecord trip = tripWithReadings(40, 40, 100, 100);

        assertTrue(trip.hasMeteredEnergy());
        assertEquals(0, trip.getMeteredEnergyKwh(), EPS);
        assertEquals(0, trip.getEnergyUsedKwh(), EPS);
        assertEquals(0, trip.getSignedEnergyKwh(), EPS);
        assertEquals(0, trip.getResolvedEnergyKwh(), EPS);
    }

    @Test
    public void missingOrResetCounterAllowsResolvedEnergyFallback() {
        for (TripRecord trip : new TripRecord[]{
                tripWithReadings(0, 0, -1, 100),
                tripWithReadings(0, 0, 100, -1),
                tripWithReadings(0, 0, 100, 99)}) {
            trip.distanceKm = 12.5;
            trip.energyPerKm = 0.2;

            assertFalse(trip.hasMeteredEnergy());
            assertEquals(0, trip.getMeteredEnergyKwh(), EPS);
            assertEquals(0, trip.getEnergyUsedKwh(), EPS);
            assertEquals(0, trip.getSignedEnergyKwh(), EPS);
            assertEquals(2.5, trip.getResolvedEnergyKwh(), EPS);
        }
    }

    @Test
    public void nonFiniteBatteryReadingsFallBackToFiniteMeter() {
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY}) {
            for (TripRecord trip : new TripRecord[]{
                    tripWithReadings(invalid, 38, 100, 101.5),
                    tripWithReadings(40, invalid, 100, 101.5),
                    tripWithReadings(invalid, invalid, 100, 101.5)}) {
                assertFalse(trip.hasRemainingEnergy());
                assertEquals(1.5, trip.getEnergyUsedKwh(), EPS);
                assertEquals(1.5, trip.getSignedEnergyKwh(), EPS);
                assertEquals(1.5, trip.getResolvedEnergyKwh(), EPS);
            }
        }
    }

    @Test
    public void nonFiniteMeterIsUnavailableRatherThanMeasuredZero() {
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY}) {
            for (TripRecord trip : new TripRecord[]{
                    tripWithReadings(40, 40, invalid, 100),
                    tripWithReadings(40, 40, 100, invalid),
                    tripWithReadings(40, 40, invalid, invalid)}) {
                trip.distanceKm = 10;
                trip.energyPerKm = 0.2;
                assertFalse(trip.hasMeteredEnergy());
                assertEquals(0, trip.getMeteredEnergyKwh(), EPS);
                assertEquals(0, trip.getEnergyUsedKwh(), EPS);
                assertEquals(0, trip.getSignedEnergyKwh(), EPS);
                assertEquals(2, trip.getResolvedEnergyKwh(), EPS);
            }
        }
    }

    private static TripRecord tripWithReadings(double batteryStart, double batteryEnd,
                                               double meterStart, double meterEnd) {
        TripRecord trip = new TripRecord();
        trip.kwhStart = batteryStart;
        trip.kwhEnd = batteryEnd;
        trip.elecConStart = meterStart;
        trip.elecConEnd = meterEnd;
        return trip;
    }
}

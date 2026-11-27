package com.overdrive.app.trips;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ConsumptionBucketTest {
    private static final double EPSILON = 1e-12;

    @Test
    public void emptyBucketReturnsZeroStatistics() {
        ConsumptionBucket bucket = new ConsumptionBucket();
        assertEquals(0.0, bucket.getMean(), 0.0);
        assertEquals(0.0, bucket.getStdDev(), 0.0);
    }

    @Test
    public void singleObservationHasNoSpread() {
        ConsumptionBucket bucket = bucket(1, 0.125, 0.015625);
        assertEquals(0.125, bucket.getMean(), EPSILON);
        assertEquals(0.0, bucket.getStdDev(), 0.0);
    }

    @Test
    public void standardDeviationUsesPopulationRatherThanSampleVariance() {
        // Three observations: 0.125, 0.250 and 0.375 kWh/km.
        ConsumptionBucket bucket = bucket(3, 0.75, 0.21875);
        assertEquals(0.25, bucket.getMean(), EPSILON);
        // Squared deviations from 0.25 sum to 0.03125; divide by all three observations.
        assertEquals(Math.sqrt(0.03125 / 3.0), bucket.getStdDev(), EPSILON);
    }

    @Test
    public void identicalObservationsHaveZeroSpread() {
        ConsumptionBucket bucket = bucket(4, 1.0, 0.25);
        assertEquals(0.25, bucket.getMean(), EPSILON);
        assertEquals(0.0, bucket.getStdDev(), 0.0);
    }

    @Test
    public void negativeVarianceFromRoundedAggregatesIsClampedToZero() {
        // Two 0.1 observations, with independently rounded stored sums.
        ConsumptionBucket bucket = bucket(2, 0.2, 0.02);
        double mean = bucket.getMean();
        assertEquals(0.1, mean, EPSILON);
        assertTrue(bucket.sumSquaredKwhPerKm / bucket.sampleCount - mean * mean < 0.0);
        assertEquals(0.0, bucket.getStdDev(), 0.0);
    }

    private static ConsumptionBucket bucket(int count, double sum, double sumSquared) {
        ConsumptionBucket bucket = new ConsumptionBucket();
        bucket.sampleCount = count;
        bucket.sumKwhPerKm = sum;
        bucket.sumSquaredKwhPerKm = sumSquared;
        return bucket;
    }
}

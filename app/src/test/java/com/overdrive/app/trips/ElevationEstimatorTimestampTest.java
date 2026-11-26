package com.overdrive.app.trips;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ElevationEstimatorTimestampTest {

    private static void sample(ElevationEstimator estimator, long timestampMs, double altitudeM) {
        // 11-second steps in the tests isolate each new sample in the 10-second
        // median window, without triggering the normal 30-second gap reset.
        estimator.addSample(timestampMs, altitudeM, 0, true, true);
    }

    @Test
    public void backwardTimestampStartsNewClimbWithoutLosingAccumulatedGain() {
        ElevationEstimator estimator = new ElevationEstimator();
        sample(estimator, 100_000, 100);
        sample(estimator, 111_000, 110);
        sample(estimator, 122_000, 120);
        assertEquals(10.0, estimator.getGainM(), 0.0);

        sample(estimator, 1_000, 300);
        assertEquals(10.0, estimator.getGainM(), 0.0);
        sample(estimator, 12_000, 310);
        sample(estimator, 23_000, 320);
        assertEquals(20.0, estimator.getGainM(), 0.0);
        assertEquals(0.0, estimator.getLossM(), 0.0);
    }

    @Test
    public void backwardTimestampStartsNewDescentWithoutLosingAccumulatedLoss() {
        ElevationEstimator estimator = new ElevationEstimator();
        sample(estimator, 100_000, 300);
        sample(estimator, 111_000, 290);
        sample(estimator, 122_000, 280);
        assertEquals(10.0, estimator.getLossM(), 0.0);

        sample(estimator, 1_000, 120);
        assertEquals(10.0, estimator.getLossM(), 0.0);
        sample(estimator, 12_000, 110);
        sample(estimator, 23_000, 100);
        assertEquals(20.0, estimator.getLossM(), 0.0);
        assertEquals(0.0, estimator.getGainM(), 0.0);
    }

    @Test
    public void forwardGapStillResetsAndEqualTimestampsDoNotReset() {
        ElevationEstimator estimator = new ElevationEstimator();
        sample(estimator, 100_000, 100);
        sample(estimator, 111_000, 110);
        sample(estimator, 122_000, 120);
        sample(estimator, 122_000, 140);
        // Median of 120 and 140 is 130; the existing climb continues.
        assertEquals(20.0, estimator.getGainM(), 0.0);

        sample(estimator, 153_000, 300);
        assertEquals(20.0, estimator.getGainM(), 0.0);
        sample(estimator, 164_000, 310);
        sample(estimator, 175_000, 320);
        assertEquals(30.0, estimator.getGainM(), 0.0);
        assertEquals(0.0, estimator.getLossM(), 0.0);
    }
}

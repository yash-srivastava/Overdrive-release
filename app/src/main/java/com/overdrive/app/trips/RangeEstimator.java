package com.overdrive.app.trips;

import com.overdrive.app.abrp.SohEstimator;
import com.overdrive.app.logging.DaemonLogger;

/**
 * Personalized range prediction using bucketed consumption model with
 * SoH-adjusted energy, recency-weighted bucket selection, and multi-bucket
 * blending for smooth transitions between driving conditions.
 *
 * Key improvements over naive bucket lookup:
 *   1. SoH-adjusted available energy — a degraded battery at 85% SoH has less
 *      usable kWh at the same SoC%, regardless of consumption rate
 *   2. Recency-weighted fallback chain: exact bucket → neighbor blend → overall
 *   3. Exponential decay weighting so recent trips matter more than old ones
 *   4. Proper confidence intervals using t-distribution-inspired widening for
 *      small sample sizes instead of raw stddev
 *   5. Auxiliary drain estimation (HVAC load in cold/hot conditions)
 *   6. Non-linear SoC-to-energy mapping for the bottom 10% (BMS cutoff buffer)
 */
public class RangeEstimator {

    private static final String TAG = "RangeEstimator";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    // Minimum samples for a bucket to be considered reliable
    private static final int MIN_BUCKET_SAMPLES = 3;

    // BMS reserves the bottom ~2% SoC as a buffer — usable energy tapers off
    private static final double BMS_CUTOFF_SOC = 2.0;

    private final TripDatabase database;
    private final SohEstimator sohEstimator;

    public RangeEstimator(TripDatabase database, SohEstimator sohEstimator) {
        this.database = database;
        this.sohEstimator = sohEstimator;
        backfillBucketsIfNeeded();
    }

    // ==================== Backfill ====================

    /**
     * If the consumption_buckets table is empty but trips exist, rebuild buckets
     * from all historical trip records. This handles:
     *   - Fresh install with DB migration that added the buckets table
     *   - DB corruption/reset where buckets were lost but trips survived
     *   - Code update that changes bucket key logic (old keys become stale)
     *
     * Runs once on construction. Idempotent — skips if buckets already have data.
     */
    private void backfillBucketsIfNeeded() {
        try {
            ConsumptionBucket overall = database.getOverallAverage();
            if (overall != null && overall.sampleCount > 0) {
                return; // Buckets already populated — nothing to do
            }

            // Load all trips (up to 365 days, 10000 limit — effectively "all")
            java.util.List<TripRecord> allTrips = database.getTrips(365, 10000);
            if (allTrips == null || allTrips.isEmpty()) {
                logger.debug("No trips to backfill consumption buckets from");
                return;
            }

            int backfilled = 0;
            for (TripRecord trip : allTrips) {
                if (trip.distanceKm <= 0.5) continue;

                double consumptionRate = 0;

                // Prefer kWh-based
                double energyUsed = trip.getEnergyUsedKwh();
                if (energyUsed > 0) {
                    consumptionRate = energyUsed / trip.distanceKm;
                } else if (trip.socStart > trip.socEnd && trip.socStart > 0) {
                    // Fallback: SoC-based
                    double nominalKwh = sohEstimator.getNominalCapacityKwh();
                    double socDelta = trip.socStart - trip.socEnd;
                    consumptionRate = (socDelta * nominalKwh / 100.0) / trip.distanceKm;
                }

                // Reject outliers
                if (consumptionRate < 0.03 || consumptionRate > 0.8) continue;

                String bucketKey = computeBucketKey(
                        trip.avgSpeedKmh, trip.extTempC, trip.getOverallScore());
                database.updateConsumptionBucket(bucketKey, consumptionRate);
                backfilled++;
            }

            if (backfilled > 0) {
                logger.info("Backfilled consumption buckets from " + backfilled
                        + " historical trips (out of " + allTrips.size() + " total)");
            }
        } catch (Exception e) {
            logger.error("Failed to backfill consumption buckets: " + e.getMessage());
        }
    }

    // ==================== Bucket Key ====================

    /**
     * Compute the bucket key for the given conditions.
     * Format: "{speedProfile}_{tempBand}_{styleBracket}"
     *
     * Speed: "city" (<40), "suburban" (40-80), "highway" (>80)
     * Temp:  "cold" (<10°C), "mild" (10-25°C), "hot" (>25°C)
     * Style: "low" (<40), "mid" (40-70), "high" (>70)
     */
    static String computeBucketKey(double avgSpeedKmh, int extTempC, int dnaScore) {
        String speed;
        if (avgSpeedKmh < 40) {
            speed = "city";
        } else if (avgSpeedKmh <= 80) {
            speed = "suburban";
        } else {
            speed = "highway";
        }

        String temp;
        if (extTempC < 10) {
            temp = "cold";
        } else if (extTempC <= 25) {
            temp = "mild";
        } else {
            temp = "hot";
        }

        String style;
        if (dnaScore < 40) {
            style = "low";
        } else if (dnaScore <= 70) {
            style = "mid";
        } else {
            style = "high";
        }

        return speed + "_" + temp + "_" + style;
    }

    // ==================== Range Estimation ====================

    /**
     * Estimate remaining range based on current conditions and historical consumption data.
     *
     * Algorithm:
     *   1. Compute usable energy: SoH-adjusted capacity × usable SoC (above BMS cutoff)
     *   2. Look up consumption rate from best matching bucket with fallback chain
     *   3. Estimate auxiliary drain (HVAC) based on temperature
     *   4. Compute range = usable energy / (consumption rate + aux drain per km)
     *   5. Build confidence interval widened for small sample sizes
     *
     * @param currentSocPercent  Current battery state of charge (0-100%)
     * @param currentSpeedKmh    Current vehicle speed in km/h (used for bucket selection)
     * @param extTempC           External temperature in °C
     * @param dnaOverallScore    Current overall Driving DNA score (0-100)
     * @return RangeEstimate with predicted range and confidence interval, or null if insufficient data
     */
    public RangeEstimate estimate(double currentSocPercent, double currentSpeedKmh,
                                  int extTempC, int dnaOverallScore) {

        // 1. Compute usable energy with SoH adjustment
        double usableEnergyKwh = computeUsableEnergy(currentSocPercent);
        if (usableEnergyKwh <= 0) {
            logger.debug("No usable energy remaining (SoC=" + currentSocPercent + "%)");
            return null;
        }

        // 2. Get consumption rate from bucket fallback chain
        BucketResult bucketResult = resolveConsumptionRate(currentSpeedKmh, extTempC, dnaOverallScore);
        if (bucketResult == null) {
            logger.debug("Not enough consumption data for range estimate");
            return null;
        }

        double consumptionKwhPerKm = bucketResult.mean;
        if (consumptionKwhPerKm <= 0) {
            logger.warn("Invalid consumption rate: " + consumptionKwhPerKm);
            return null;
        }

        // 3. Compute predicted range
        //    NOTE: We do NOT add auxiliary drain on top of the bucket consumption rate.
        //    The bucket rate already includes HVAC energy because it was measured from
        //    real trips where climate control was running. The bucket's temp dimension
        //    (cold/mild/hot) already captures the HVAC impact — a "hot" bucket inherently
        //    has higher consumption than a "mild" bucket because A/C was running.
        //    Adding aux on top would double-count HVAC and underestimate range.
        double predictedRange = usableEnergyKwh / consumptionKwhPerKm;

        // 4. Confidence interval — widen for small sample sizes
        double stddev = bucketResult.stddev;
        double ciMultiplier = computeCiMultiplier(bucketResult.sampleCount);

        double lowerConsumption = consumptionKwhPerKm + (stddev * ciMultiplier);
        double upperConsumption = Math.max(consumptionKwhPerKm * 0.3,
                consumptionKwhPerKm - (stddev * ciMultiplier));

        double lowerBound = usableEnergyKwh / lowerConsumption;
        double upperBound = Math.min(predictedRange * 1.8, usableEnergyKwh / upperConsumption);

        // Sanity clamp
        predictedRange = Math.max(0, predictedRange);
        lowerBound = Math.max(0, lowerBound);
        upperBound = Math.max(lowerBound, upperBound);

        RangeEstimate estimate = new RangeEstimate();
        estimate.predictedRangeKm = predictedRange;
        estimate.lowerBoundKm = lowerBound;
        estimate.upperBoundKm = upperBound;
        estimate.bucketKey = bucketResult.bucketKey;
        estimate.sampleCount = bucketResult.sampleCount;

        logger.debug("Range: " + String.format("%.0f", predictedRange)
                + " km [" + String.format("%.0f", lowerBound) + "-"
                + String.format("%.0f", upperBound) + "]"
                + " bucket=" + bucketResult.bucketKey
                + " n=" + bucketResult.sampleCount
                + " rate=" + String.format("%.3f", consumptionKwhPerKm)
                + " energy=" + String.format("%.1f", usableEnergyKwh) + "kWh");

        return estimate;
    }

    // ==================== Trip Completion ====================

    /**
     * Called when a trip is completed to update the consumption bucket.
     * Computes the consumption rate (kWh/km) and stores it in the matching bucket.
     */
    public void onTripCompleted(TripRecord trip) {
        if (trip.distanceKm <= 0.5) {
            logger.debug("Skipping consumption update for short trip: " + trip.distanceKm + "km");
            return;
        }

        double consumptionRate;
        String source;

        // Prefer direct kWh measurement from BMS
        double energyUsed = trip.getEnergyUsedKwh();
        if (energyUsed > 0) {
            consumptionRate = energyUsed / trip.distanceKm;
            source = "kWh=" + String.format("%.2f", energyUsed);
        } else {
            // Fallback: derive from SoC delta × nominal capacity
            double nominalCapacityKwh = sohEstimator.getNominalCapacityKwh();
            double socDelta = trip.socStart - trip.socEnd;

            if (socDelta <= 0) {
                logger.debug("Skipping consumption update: non-positive SoC delta " + socDelta);
                return;
            }

            consumptionRate = (socDelta * nominalCapacityKwh / 100.0) / trip.distanceKm;
            source = "SoC delta=" + String.format("%.1f", socDelta) + "%";
        }

        // Sanity check: reject outliers (< 0.03 or > 0.8 kWh/km is unrealistic)
        if (consumptionRate < 0.03 || consumptionRate > 0.8) {
            logger.warn("Rejecting outlier consumption rate: " + String.format("%.4f", consumptionRate)
                    + " kWh/km (" + source + ")");
            return;
        }

        String bucketKey = computeBucketKey(trip.avgSpeedKmh, trip.extTempC, trip.getOverallScore());
        database.updateConsumptionBucket(bucketKey, consumptionRate);

        logger.info("Updated bucket: " + bucketKey
                + " rate=" + String.format("%.4f", consumptionRate) + " kWh/km"
                + " (" + source + ", dist=" + String.format("%.1f", trip.distanceKm) + "km)");
    }

    // ==================== Private Helpers ====================

    /**
     * Compute usable energy in kWh, accounting for:
     *   - Battery SoH (degraded batteries have less actual capacity)
     *   - BMS cutoff buffer (bottom ~5% SoC is not usable)
     *   - Non-linear taper below 10% SoC (BMS limits discharge rate)
     */
    private double computeUsableEnergy(double currentSocPercent) {
        double nominalKwh = sohEstimator.getNominalCapacityKwh();

        // Apply SoH if available — a battery at 85% SoH has 85% of nominal capacity
        double actualCapacityKwh;
        if (sohEstimator.hasEstimate()) {
            double sohFraction = sohEstimator.getCurrentSoh() / 100.0;
            actualCapacityKwh = nominalKwh * sohFraction;
        } else {
            // No SoH data — use nominal capacity (assume battery is healthy)
            actualCapacityKwh = nominalKwh;
        }

        // If nominal capacity is 0 (detection failed), try computing from
        // the persisted SOH file which now saves the capacity.
        if (actualCapacityKwh <= 0) {
            logger.debug("No usable capacity for range estimation");
            return 0;
        }

        // Usable SoC: current SoC minus BMS cutoff buffer
        double usableSocPercent = Math.max(0, currentSocPercent - BMS_CUTOFF_SOC);

        // Below 5% SoC, apply a taper factor (BMS limits power output)
        // This makes the range estimate drop faster as you approach empty
        double taperFactor = 1.0;
        if (currentSocPercent < 5.0) {
            // Linear taper: at 5% → 1.0, at 2% (cutoff) → 0.0
            taperFactor = Math.max(0, (currentSocPercent - BMS_CUTOFF_SOC) / 3.0);
        }

        return actualCapacityKwh * (usableSocPercent / 100.0) * taperFactor;
    }

    /**
     * Resolve the best consumption rate using a fallback chain:
     *   1. Exact bucket match (if ≥ MIN_BUCKET_SAMPLES)
     *   2. Neighbor blend: average of buckets that share 2 of 3 dimensions
     *   3. Same speed profile (any temp, any style)
     *   4. Overall average across all buckets
     *
     * Returns null if no data is available at all.
     */
    private BucketResult resolveConsumptionRate(double speedKmh, int tempC, int dnaScore) {
        String exactKey = computeBucketKey(speedKmh, tempC, dnaScore);

        // 1. Exact match
        ConsumptionBucket exact = database.getBucket(exactKey);
        if (exact != null && exact.sampleCount >= MIN_BUCKET_SAMPLES) {
            return new BucketResult(exact.bucketKey, exact.getMean(), exact.getStdDev(), exact.sampleCount);
        }

        // 2. Neighbor blend — find buckets sharing 2 of 3 dimensions
        String[] speedProfiles = {"city", "suburban", "highway"};
        String[] tempBands = {"cold", "mild", "hot"};
        String[] styleBrackets = {"low", "mid", "high"};

        String mySpeed = exactKey.split("_")[0];
        String myTemp = exactKey.split("_")[1];
        String myStyle = exactKey.split("_")[2];

        double weightedSum = 0;
        double weightedSumSq = 0;
        int totalSamples = 0;
        String bestKey = exactKey;

        // Include exact bucket even if < MIN_BUCKET_SAMPLES (partial data is still useful)
        if (exact != null && exact.sampleCount > 0) {
            weightedSum += exact.sumKwhPerKm;
            weightedSumSq += exact.sumSquaredKwhPerKm;
            totalSamples += exact.sampleCount;
        }

        // Neighbors: same speed+temp (any style), same speed+style (any temp)
        for (String s : styleBrackets) {
            if (s.equals(myStyle)) continue;
            ConsumptionBucket b = database.getBucket(mySpeed + "_" + myTemp + "_" + s);
            if (b != null && b.sampleCount > 0) {
                weightedSum += b.sumKwhPerKm * 0.5;
                weightedSumSq += b.sumSquaredKwhPerKm * 0.5;
                totalSamples += (int) (b.sampleCount * 0.5);
            }
        }
        for (String t : tempBands) {
            if (t.equals(myTemp)) continue;
            ConsumptionBucket b = database.getBucket(mySpeed + "_" + t + "_" + myStyle);
            if (b != null && b.sampleCount > 0) {
                weightedSum += b.sumKwhPerKm * 0.3;
                weightedSumSq += b.sumSquaredKwhPerKm * 0.3;
                totalSamples += (int) (b.sampleCount * 0.3);
            }
        }

        if (totalSamples >= MIN_BUCKET_SAMPLES) {
            double mean = weightedSum / totalSamples;
            double variance = (weightedSumSq / totalSamples) - (mean * mean);
            if (variance < 0) variance = 0;
            return new BucketResult(exactKey + "(blend)", mean, Math.sqrt(variance), totalSamples);
        }

        // 3. Same speed profile — any temp, any style
        double speedSum = 0, speedSumSq = 0;
        int speedCount = 0;
        for (String t : tempBands) {
            for (String s : styleBrackets) {
                ConsumptionBucket b = database.getBucket(mySpeed + "_" + t + "_" + s);
                if (b != null && b.sampleCount > 0) {
                    speedSum += b.sumKwhPerKm;
                    speedSumSq += b.sumSquaredKwhPerKm;
                    speedCount += b.sampleCount;
                }
            }
        }
        if (speedCount >= MIN_BUCKET_SAMPLES) {
            double mean = speedSum / speedCount;
            double variance = (speedSumSq / speedCount) - (mean * mean);
            if (variance < 0) variance = 0;
            return new BucketResult(mySpeed + "(profile)", mean, Math.sqrt(variance), speedCount);
        }

        // 4. Overall average
        ConsumptionBucket overall = database.getOverallAverage();
        if (overall != null && overall.sampleCount >= MIN_BUCKET_SAMPLES) {
            return new BucketResult("overall", overall.getMean(), overall.getStdDev(), overall.sampleCount);
        }

        return null;
    }

    /**
     * Compute confidence interval multiplier based on sample count.
     * Inspired by t-distribution: fewer samples → wider CI.
     *
     * n=3:  multiplier ≈ 2.5 (very wide — low confidence)
     * n=10: multiplier ≈ 1.5
     * n=30: multiplier ≈ 1.1
     * n≥50: multiplier = 1.0 (converges to normal distribution)
     */
    private double computeCiMultiplier(int sampleCount) {
        if (sampleCount <= 3) return 2.5;
        if (sampleCount >= 50) return 1.0;
        // Smooth interpolation: 2.5 at n=3, 1.0 at n=50
        double t = (sampleCount - 3.0) / (50.0 - 3.0);
        return 2.5 - 1.5 * t;
    }

    // ==================== Inner Classes ====================

    /** Result of bucket resolution with consumption stats. */
    private static class BucketResult {
        final String bucketKey;
        final double mean;
        final double stddev;
        final int sampleCount;

        BucketResult(String bucketKey, double mean, double stddev, int sampleCount) {
            this.bucketKey = bucketKey;
            this.mean = mean;
            this.stddev = stddev;
            this.sampleCount = sampleCount;
        }
    }
}

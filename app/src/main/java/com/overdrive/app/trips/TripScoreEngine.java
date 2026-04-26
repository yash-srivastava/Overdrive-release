package com.overdrive.app.trips;

import com.overdrive.app.logging.DaemonLogger;

import java.util.ArrayList;
import java.util.List;

/**
 * Single-Pass Kinematic Scoring Engine for Driving DNA.
 *
 * Processes the entire telemetry array in ONE pass, computing all five Driving DNA
 * scores, micro-moments, speed histogram, avg/max speed, and kinematic state
 * classification simultaneously. On a 2-hour 5Hz drive (36,000 samples), this
 * touches the array exactly once — critical for constrained car head unit hardware
 * where cache thrashing and memory bandwidth are real concerns.
 *
 * Architecture:
 *   1. Single O(N) loop accumulates all accumulators and state machines in parallel
 *   2. Post-loop finalization converts accumulators into scores
 *   3. Micro-moments (launches, coast-brake, smoothness) are extracted inline
 *      using state machines rather than separate passes
 *
 * Scoring axes:
 *   - Anticipation: EV-aware coast gap detection (accel < 5% = regen lift-off)
 *   - Smoothness: Pedal jerk integral (sum of |Δaccel| + |Δbrake| per second)
 *   - Speed Discipline: Rolling-window speed stddev via naive sum-of-squares variance
 *   - Efficiency: kWh/km with state-dependent baselines (falls back to SoC-based)
 *   - Consistency: Percentage deviation from rolling 10-trip average
 *
 * All scores are integers in [0, 100] where 100 is optimal.
 */
public class TripScoreEngine {

    private static final DaemonLogger logger = DaemonLogger.getInstance("TripScoreEngine");

    // ==================== Constants ====================

    private static final int MIN_SAMPLES = 30;
    private static final int MIN_COAST_TRANSITIONS = 3;
    private static final double MAX_COAST_GAP_SECONDS = 30.0;
    private static final int MIN_DRIVING_SPEED = 3;
    private static final double MIN_EFFICIENCY_DISTANCE = 0.5;
    private static final int MIN_RECENT_TRIPS_FOR_CONSISTENCY = 2;
    private static final int LAUNCH_PROFILE_SAMPLES = 50;
    private static final int HISTOGRAM_BUCKET_WIDTH = 10;
    private static final int HISTOGRAM_BUCKET_COUNT = 11;
    private static final int DEFAULT_SCORE = 50;

    // Speed discipline window: 30 samples at 5Hz = 6 seconds
    private static final int SD_WINDOW_SIZE = 30;
    private static final int SD_WINDOW_STEP = 15; // 50% overlap
    private static final int SD_MIN_DRIVING = 15;  // At least half must be driving

    // Pedal smoothness window: 10 samples at 5Hz = 2 seconds
    private static final int SMOOTH_WINDOW_SIZE = 10;
    private static final int SMOOTH_WINDOW_STEP = 5; // 50% overlap
    private static final int SMOOTH_MIN_DRIVING = 5;

    // ==================== Kinematic State ====================

    public enum KinematicState {
        HEAVY_GRIDLOCK,   // avgSpeed < 22, stopsPerKm >= 1.5
        URBAN_FLOW,       // Default — mixed city driving
        HIGHWAY_CRUISING  // avgSpeed > 75, stopsPerKm <= 0.2
    }

    // ==================== Gradient Profile ====================
    // Orthogonal to KinematicState — classifies terrain, not traffic.
    // Computed from cumulative elevation gain per km driven.

    public enum GradientProfile {
        FLAT,             // < 5 m gain/loss per km — flat or very gentle terrain
        HILLY,            // 5–15 m gain or loss per km — rolling hills, moderate climbs
        MOUNTAIN_CLIMB,   // > 15 m gain per km — steep sustained climbs
        MOUNTAIN_DESCENT  // > 15 m loss per km — steep sustained descents (regen territory)
    }

    // Minimum altitude delta to count (filters GPS noise, ~2m accuracy)
    private static final double ALT_NOISE_THRESHOLD = 2.0;
    // Smoothing: minimum distance between altitude samples to reduce GPS jitter
    private static final int ALT_SAMPLE_INTERVAL = 5; // Every 5th sample at 5Hz = 1 second

    // ==================== Public API ====================

    /**
     * Compute all trip scores, micro-moments, and stats in a single pass.
     *
     * This is the main entry point. After this call, the TripRecord has all five
     * DNA scores, kinematicState, microMomentsJson, avgSpeedKmh, and maxSpeedKmh
     * populated.
     */
    public TripRecord computeSummary(TripRecord trip, List<TelemetrySample> samples) {
        if (samples == null || samples.size() < MIN_SAMPLES) {
            logger.warn("computeSummary: insufficient samples ("
                    + (samples != null ? samples.size() : 0) + "), using defaults");
            trip.anticipationScore = DEFAULT_SCORE;
            trip.smoothnessScore = DEFAULT_SCORE;
            trip.speedDisciplineScore = DEFAULT_SCORE;
            trip.efficiencyScore = DEFAULT_SCORE;
            trip.consistencyScore = DEFAULT_SCORE;
            trip.kinematicState = KinematicState.URBAN_FLOW.name();
            trip.microMomentsJson = new MicroMoments().toJson().toString();
            return trip;
        }

        final int n = samples.size();

        // ── Accumulators: kinematic state classification ──
        int stopCount = 0;
        boolean wasMoving = false;

        // ── Accumulators: avg/max speed ──
        long sumSpeed = 0;
        int maxSpeed = 0;

        // ── Accumulators: elevation (gradient profile) ──
        double elevationGain = 0;
        double elevationLoss = 0;
        double lastValidAlt = Double.NaN;
        int altSampleCounter = 0;

        // ── Accumulators: speed histogram ──
        int[] histCounts = new int[HISTOGRAM_BUCKET_COUNT];

        // ── Accumulators: pedal jerk (smoothness score) ──
        double pedalJerkSum = 0;
        int lastAccel = 0, lastBrake = 0;

        // ── State machine: coast gap (anticipation score) ──
        Long coastStartTime = null;
        long coastGapSumMs = 0;
        int coastGapCount = 0;

        // ── State machine: launch profiles (micro-moments) ──
        boolean wasStationary = false;
        int launchCaptureRemaining = 0;  // Countdown of samples left to capture
        int launchPeakAccel = 0;
        long launchStartTime = 0;
        List<Integer> launchCurveBuffer = null;

        // ── State machine: coast-brake events (micro-moments) ──
        Long mmCoastStartTime = null;

        // ── Rolling window: speed discipline (naive sum-of-squares variance) ──
        // We use a circular buffer of speed values for the window
        int[] sdWindowSpeeds = new int[SD_WINDOW_SIZE];
        boolean[] sdWindowIsDriving = new boolean[SD_WINDOW_SIZE];
        int sdWindowDrivingCount = 0;
        double sdWindowSum = 0;
        double sdWindowSumSq = 0;
        double sdTotalScore = 0;
        int sdWindowCount = 0;
        int sdStepCounter = 0;

        // ── Rolling window: pedal smoothness (micro-moments) ──
        int[] smoothWindowAccel = new int[SMOOTH_WINDOW_SIZE];
        boolean[] smoothWindowDriving = new boolean[SMOOTH_WINDOW_SIZE];
        int smoothStepCounter = 0;

        // ── Output collectors ──
        MicroMoments microMoments = new MicroMoments();

        // ════════════════════════════════════════════════════════════
        //  SINGLE PASS: iterate samples exactly once
        // ════════════════════════════════════════════════════════════
        for (int i = 0; i < n; i++) {
            final TelemetrySample s = samples.get(i);
            final int speed = s.speedKmh;
            final int accel = s.accelPedalPercent;
            final int brake = s.brakePedalPercent;

            // ── 1. Avg/Max speed ──
            sumSpeed += speed;
            if (speed > maxSpeed) maxSpeed = speed;

            // ── 2. Speed histogram ──
            int bucket = speed / HISTOGRAM_BUCKET_WIDTH;
            if (bucket >= HISTOGRAM_BUCKET_COUNT) bucket = HISTOGRAM_BUCKET_COUNT - 1;
            histCounts[bucket]++;

            // ── 3. Kinematic state: stop counting ──
            if (speed > MIN_DRIVING_SPEED) wasMoving = true;
            if (speed == 0 && wasMoving) {
                stopCount++;
                wasMoving = false;
            }

            // ── 3b. Elevation tracking (gradient profile) ──
            // Sample every ALT_SAMPLE_INTERVAL to smooth GPS altitude noise
            altSampleCounter++;
            if (altSampleCounter >= ALT_SAMPLE_INTERVAL) {
                altSampleCounter = 0;
                double alt = s.altitude;
                if (alt != 0.0 && !Double.isNaN(alt)) {
                    if (!Double.isNaN(lastValidAlt)) {
                        double delta = alt - lastValidAlt;
                        // Only count if above noise threshold
                        if (Math.abs(delta) >= ALT_NOISE_THRESHOLD) {
                            if (delta > 0) elevationGain += delta;
                            else elevationLoss += Math.abs(delta);
                            lastValidAlt = alt;
                        }
                    } else {
                        lastValidAlt = alt;
                    }
                }
            }

            // ── 4. Pedal jerk (smoothness) ──
            if (i > 0) {
                pedalJerkSum += Math.abs(accel - lastAccel) + Math.abs(brake - lastBrake);
            }

            // ── 5. Coast gap detection (anticipation) ──
            // EV-aware: accel < 5% counts as lifting off (regen zone)
            // Coasting ends when driver brakes OR re-applies power (one-pedal driving)
            if (accel < 5 && brake == 0 && speed > MIN_DRIVING_SPEED) {
                if (coastStartTime == null) coastStartTime = s.timestampMs;
            } else if ((brake > 0 || accel >= 15) && coastStartTime != null) {
                // Coasting ended via friction brake OR re-applying power
                long gapMs = s.timestampMs - coastStartTime;
                double gapSec = gapMs / 1000.0;
                if (gapSec > 0 && gapSec < MAX_COAST_GAP_SECONDS) {
                    coastGapSumMs += gapMs;
                    coastGapCount++;
                }
                coastStartTime = null;
            } else if (accel >= 5 && accel < 15) {
                // Light throttle re-application — not a full coast-end, just cancel coast tracking
                coastStartTime = null;
            }

            // ── 6. Launch profile capture (micro-moments) ──
            if (launchCaptureRemaining > 0) {
                // Currently capturing a launch profile
                launchCurveBuffer.add(accel);
                if (accel > launchPeakAccel) launchPeakAccel = accel;
                launchCaptureRemaining--;
                if (launchCaptureRemaining == 0) {
                    // Finalize this launch profile
                    MicroMoments.LaunchProfile lp = new MicroMoments.LaunchProfile();
                    lp.startTime = launchStartTime;
                    lp.peakAccelPercent = launchPeakAccel;
                    lp.accelCurve = new int[launchCurveBuffer.size()];
                    for (int k = 0; k < launchCurveBuffer.size(); k++) {
                        lp.accelCurve[k] = launchCurveBuffer.get(k);
                    }
                    microMoments.launches.add(lp);
                }
            } else {
                // Detect launch: was stationary, now moving
                if (speed == 0) {
                    wasStationary = true;
                } else if (wasStationary && speed > MIN_DRIVING_SPEED) {
                    // Start capturing launch profile
                    launchStartTime = s.timestampMs;
                    launchPeakAccel = accel;
                    launchCurveBuffer = new ArrayList<>(LAUNCH_PROFILE_SAMPLES);
                    launchCurveBuffer.add(accel);
                    launchCaptureRemaining = LAUNCH_PROFILE_SAMPLES - 1;
                    wasStationary = false;
                }
                if (speed > 0) wasStationary = false;
            }

            // ── 7. Coast-brake events (micro-moments) ──
            if (accel < 5 && brake == 0 && mmCoastStartTime == null && speed > MIN_DRIVING_SPEED) {
                mmCoastStartTime = s.timestampMs;
            }
            if (brake > 0 && mmCoastStartTime != null) {
                long gapMs = s.timestampMs - mmCoastStartTime;
                if (gapMs > 0 && gapMs < (long) (MAX_COAST_GAP_SECONDS * 1000)) {
                    MicroMoments.CoastBrakeEvent event = new MicroMoments.CoastBrakeEvent();
                    event.coastGapMs = gapMs;
                    event.speedAtBrake = speed;
                    microMoments.coastBrakeEvents.add(event);
                }
                mmCoastStartTime = null;
            }
            if (accel >= 5) mmCoastStartTime = null;

            // ── 8. Speed discipline: rolling window via circular buffer ──
            boolean isDriving = speed > MIN_DRIVING_SPEED;
            int circIdx = i % SD_WINDOW_SIZE;

            // If window is full, evict the oldest entry
            if (i >= SD_WINDOW_SIZE) {
                if (sdWindowIsDriving[circIdx]) {
                    int oldSpeed = sdWindowSpeeds[circIdx];
                    sdWindowDrivingCount--;
                    sdWindowSum -= oldSpeed;
                    sdWindowSumSq -= (double) oldSpeed * oldSpeed;
                }
            }

            // Insert new entry
            sdWindowSpeeds[circIdx] = speed;
            sdWindowIsDriving[circIdx] = isDriving;
            if (isDriving) {
                sdWindowDrivingCount++;
                sdWindowSum += speed;
                sdWindowSumSq += (double) speed * speed;
            }

            // Evaluate window every SD_WINDOW_STEP samples, once we have a full window
            sdStepCounter++;
            if (i >= SD_WINDOW_SIZE - 1 && sdStepCounter >= SD_WINDOW_STEP) {
                sdStepCounter = 0;
                if (sdWindowDrivingCount >= SD_MIN_DRIVING) {
                    double mean = sdWindowSum / sdWindowDrivingCount;
                    double variance = (sdWindowSumSq / sdWindowDrivingCount) - (mean * mean);
                    if (variance < 0) variance = 0; // Floating point guard
                    double sd = Math.sqrt(variance);
                    // Score this window (threshold applied post-loop when we know the state)
                    sdTotalScore += sd;
                    sdWindowCount++;
                }
            }

            // ── 9. Pedal smoothness: rolling window via circular buffer ──
            int smoothCircIdx = i % SMOOTH_WINDOW_SIZE;
            smoothWindowAccel[smoothCircIdx] = accel;
            smoothWindowDriving[smoothCircIdx] = isDriving;

            smoothStepCounter++;
            if (i >= SMOOTH_WINDOW_SIZE - 1 && smoothStepCounter >= SMOOTH_WINDOW_STEP) {
                smoothStepCounter = 0;
                // Count driving samples and compute stddev
                int drivingCount = 0;
                double aSum = 0, aSumSq = 0;
                for (int w = 0; w < SMOOTH_WINDOW_SIZE; w++) {
                    if (smoothWindowDriving[w]) {
                        drivingCount++;
                        aSum += smoothWindowAccel[w];
                        aSumSq += (double) smoothWindowAccel[w] * smoothWindowAccel[w];
                    }
                }
                if (drivingCount >= SMOOTH_MIN_DRIVING) {
                    double aMean = aSum / drivingCount;
                    double aVar = (aSumSq / drivingCount) - (aMean * aMean);
                    if (aVar < 0) aVar = 0;
                    MicroMoments.PedalSmoothnessWindow window = new MicroMoments.PedalSmoothnessWindow();
                    window.startTime = samples.get(Math.max(0, i - SMOOTH_WINDOW_SIZE + 1)).timestampMs;
                    window.stdDev = Math.sqrt(aVar);
                    microMoments.smoothnessWindows.add(window);
                }
            }

            lastAccel = accel;
            lastBrake = brake;
        }
        // ════════════════════════════════════════════════════════════
        //  END SINGLE PASS
        // ════════════════════════════════════════════════════════════

        // Finalize any in-progress launch capture (trip ended mid-launch)
        if (launchCaptureRemaining > 0 && launchCurveBuffer != null && !launchCurveBuffer.isEmpty()) {
            MicroMoments.LaunchProfile lp = new MicroMoments.LaunchProfile();
            lp.startTime = launchStartTime;
            lp.peakAccelPercent = launchPeakAccel;
            lp.accelCurve = new int[launchCurveBuffer.size()];
            for (int k = 0; k < launchCurveBuffer.size(); k++) {
                lp.accelCurve[k] = launchCurveBuffer.get(k);
            }
            microMoments.launches.add(lp);
        }

        // ── Classify kinematic state ──
        double avgSpeedKmh = trip.durationSeconds > 0
                ? trip.distanceKm / (trip.durationSeconds / 3600.0) : 0;
        double stopsPerKm = trip.distanceKm > 0 ? (double) stopCount / trip.distanceKm : 0;

        KinematicState kinState;
        if (avgSpeedKmh < 22 && stopsPerKm >= 1.5) {
            kinState = KinematicState.HEAVY_GRIDLOCK;
        } else if (avgSpeedKmh > 75 && stopsPerKm <= 0.2) {
            kinState = KinematicState.HIGHWAY_CRUISING;
        } else {
            kinState = KinematicState.URBAN_FLOW;
        }
        trip.kinematicState = kinState.name();

        // ── Classify gradient profile ──
        // Elevation gain/loss per km — standard metric for terrain difficulty.
        // Climb and descent are separate profiles because the physics and
        // optimal driving behavior are fundamentally different.
        double gainPerKm = trip.distanceKm > 0 ? elevationGain / trip.distanceKm : 0;
        double lossPerKm = trip.distanceKm > 0 ? elevationLoss / trip.distanceKm : 0;
        GradientProfile gradProfile;
        if (gainPerKm > 15) {
            gradProfile = GradientProfile.MOUNTAIN_CLIMB;
        } else if (lossPerKm > 15) {
            gradProfile = GradientProfile.MOUNTAIN_DESCENT;
        } else if (gainPerKm > 5 || lossPerKm > 5) {
            gradProfile = GradientProfile.HILLY;
        } else {
            gradProfile = GradientProfile.FLAT;
        }
        trip.gradientProfile = gradProfile.name();
        trip.elevationGainM = elevationGain;
        trip.elevationLossM = elevationLoss;
        trip.avgGradientPercent = trip.distanceKm > 0
                ? (elevationGain - elevationLoss) / (trip.distanceKm * 1000) * 100 : 0;

        // ── Gradient compensation factors ──
        // These adjust thresholds based on terrain so drivers aren't penalized
        // (or over-rewarded) for physics they can't control.
        //
        // CLIMB: more energy needed, more pedal variation, less coasting opportunity
        // DESCENT: regen recovers energy (bestEff can go negative), driver modulates
        //          regen via accelerator (higher jerk is expected), less coasting
        //          because you're managing speed via regen, not coasting to a stop
        double efficiencyBestAdjust;       // Added to bestEff (negative = regen baseline)
        double efficiencyGradientFactor;   // Multiplier on worstEff (higher = more lenient)
        double smoothnessGradientFactor;   // Multiplier on maxJerk (higher = more lenient)
        double anticipationGradientFactor; // Multiplier on targetGapMs (lower = more lenient)
        switch (gradProfile) {
            case MOUNTAIN_CLIMB:
                efficiencyBestAdjust = 0;
                efficiencyGradientFactor = 1.6;    // 60% wider efficiency range
                smoothnessGradientFactor = 1.4;    // 40% more jerk tolerance
                anticipationGradientFactor = 0.6;   // 40% shorter coast gap expected
                break;
            case MOUNTAIN_DESCENT:
                efficiencyBestAdjust = -0.05;       // Good driver should be net-negative kWh/km
                efficiencyGradientFactor = 1.0;     // Normal worst-case (they shouldn't be consuming much)
                smoothnessGradientFactor = 1.35;    // Regen modulation causes pedal variation
                anticipationGradientFactor = 0.5;   // Very little coasting — managing speed via regen
                break;
            case HILLY:
                efficiencyBestAdjust = 0;
                efficiencyGradientFactor = 1.25;   // 25% wider efficiency range
                smoothnessGradientFactor = 1.15;   // 15% more jerk tolerance
                anticipationGradientFactor = 0.85;  // 15% shorter coast gap expected
                break;
            default: // FLAT
                efficiencyBestAdjust = 0;
                efficiencyGradientFactor = 1.0;
                smoothnessGradientFactor = 1.0;
                anticipationGradientFactor = 1.0;
                break;
        }

        // ── Populate avg/max speed ──
        trip.avgSpeedKmh = (double) sumSpeed / n;
        trip.maxSpeedKmh = maxSpeed;

        // ════════════════════════════════════════════════════════════
        //  SCORE COMPUTATION (all from accumulators, no re-iteration)
        // ════════════════════════════════════════════════════════════

        // A. Anticipation — coast gap before braking
        //    Gradient-adjusted: on steep terrain, less coasting is expected
        double targetGapMs;
        switch (kinState) {
            case HEAVY_GRIDLOCK:   targetGapMs = 800;  break;
            case HIGHWAY_CRUISING: targetGapMs = 1500; break;
            default:               targetGapMs = 2500; break;
        }
        targetGapMs *= anticipationGradientFactor;
        if (targetGapMs <= 0) {
            trip.anticipationScore = DEFAULT_SCORE;
        } else if (coastGapCount >= MIN_COAST_TRANSITIONS) {
            double avgGapMs = (double) coastGapSumMs / coastGapCount;
            trip.anticipationScore = clamp((int) Math.round(avgGapMs / targetGapMs * 100), 0, 100);
        } else {
            trip.anticipationScore = DEFAULT_SCORE;
        }

        // B. Smoothness — pedal jerk integral (lower = smoother)
        //    Gradient-adjusted: steeper roads require more pedal variation
        //    Use actual elapsed time from timestamps, not assumed 5Hz polling rate,
        //    because Android CPU governor and GC cause polling jitter.
        long actualDurationMs = samples.get(n - 1).timestampMs - samples.get(0).timestampMs;
        double durationSec = actualDurationMs / 1000.0;
        if (durationSec < 1) durationSec = 1;
        double normalizedJerk = pedalJerkSum / durationSec;
        double maxJerk;
        switch (kinState) {
            case HEAVY_GRIDLOCK:   maxJerk = 20; break;
            case HIGHWAY_CRUISING: maxJerk = 8;  break;
            default:               maxJerk = 12; break;
        }
        maxJerk *= smoothnessGradientFactor;
        trip.smoothnessScore = clamp((int) Math.round((1.0 - normalizedJerk / maxJerk) * 100), 0, 100);

        // C. Speed Discipline — rolling window stddev average
        //    sdTotalScore holds the SUM of per-window stddev values.
        //    Convert to average stddev, then score against state-dependent threshold.
        double maxStdDev;
        switch (kinState) {
            case HEAVY_GRIDLOCK:   maxStdDev = 20.0; break;
            case HIGHWAY_CRUISING: maxStdDev = 12.0; break;
            default:               maxStdDev = 16.0; break;
        }
        if (sdWindowCount > 0) {
            double avgStdDev = sdTotalScore / sdWindowCount;
            trip.speedDisciplineScore = clamp(
                    (int) Math.round((1.0 - avgStdDev / maxStdDev) * 100), 0, 100);
        } else {
            trip.speedDisciplineScore = DEFAULT_SCORE;
        }

        // D. Efficiency — kWh/km with realistic BYD EV baselines
        //    Gradient-adjusted: uphill widens acceptable range, downhill shifts
        //    bestEff below zero (good driver should be gaining battery on descent)
        double energyUsed = trip.getEnergyUsedKwh();
        if (energyUsed > 0 && trip.distanceKm >= MIN_EFFICIENCY_DISTANCE) {
            double kwhPerKm = energyUsed / trip.distanceKm;
            double bestEff, worstEff;
            switch (kinState) {
                case HEAVY_GRIDLOCK:   bestEff = 0.10; worstEff = 0.35; break;
                case HIGHWAY_CRUISING: bestEff = 0.14; worstEff = 0.35; break;
                default:               bestEff = 0.11; worstEff = 0.32; break;
            }
            bestEff += efficiencyBestAdjust;
            worstEff *= efficiencyGradientFactor;
            double effRange = worstEff - bestEff;
            if (effRange <= 0) {
                trip.efficiencyScore = 100;
            } else {
                double score = (worstEff - kwhPerKm) / effRange * 100;
                trip.efficiencyScore = clamp((int) Math.round(score), 0, 100);
            }
        } else if (trip.distanceKm >= MIN_EFFICIENCY_DISTANCE) {
            double socDelta = trip.socStart - trip.socEnd;
            if (socDelta > 0) {
                double consumptionPerKm = socDelta / trip.distanceKm;
                double score = (3.5 - consumptionPerKm) / 3.0 * 100;
                trip.efficiencyScore = clamp((int) Math.round(score), 0, 100);
            } else {
                trip.efficiencyScore = DEFAULT_SCORE;
            }
        } else {
            trip.efficiencyScore = DEFAULT_SCORE;
        }

        // E. Consistency — computed later with recent trips from DB
        trip.consistencyScore = DEFAULT_SCORE;

        // ── Micro-moments ──
        trip.microMomentsJson = microMoments.toJson().toString();

        logger.info("Scores [" + kinState + "/" + gradProfile
                + " avgSpd=" + String.format("%.0f", avgSpeedKmh)
                + " stops/km=" + String.format("%.1f", stopsPerKm)
                + " elev+" + String.format("%.0f", elevationGain)
                + "/-" + String.format("%.0f", elevationLoss) + "m"
                + " gain/km=" + String.format("%.1f", gainPerKm)
                + " loss/km=" + String.format("%.1f", lossPerKm) + "] "
                + "A=" + trip.anticipationScore + " S=" + trip.smoothnessScore
                + " SD=" + trip.speedDisciplineScore + " E=" + trip.efficiencyScore
                + " C=" + trip.consistencyScore);

        return trip;
    }

    /**
     * Consistency Score (0-100): percentage deviation from rolling average.
     *
     * Uses energyPerKm when available, falls back to efficiencySocPerKm.
     * Percentage-based so that a 0.02 kWh/km deviation on a 0.15 average (~13%)
     * is treated the same as a 0.04 deviation on a 0.30 average (~13%).
     * 0% deviation → 100, ≥50% deviation → 0.
     */
    public int computeConsistency(double currentEfficiency, List<TripRecord> recentTrips) {
        if (recentTrips == null || recentTrips.size() < MIN_RECENT_TRIPS_FOR_CONSISTENCY) {
            return DEFAULT_SCORE;
        }

        boolean useKwh = currentEfficiency > 0 && currentEfficiency < 1;
        double sum = 0;
        int count = 0;
        for (TripRecord t : recentTrips) {
            double val = useKwh ? t.energyPerKm : t.efficiencySocPerKm;
            if (val > 0) { sum += val; count++; }
        }
        if (count < MIN_RECENT_TRIPS_FOR_CONSISTENCY) return DEFAULT_SCORE;

        double avgEfficiency = sum / count;
        double deviation = Math.abs(currentEfficiency - avgEfficiency);
        double pctDeviation = avgEfficiency > 0 ? deviation / avgEfficiency : 0;
        double maxPctDeviation = 0.50;
        int score = (int) Math.round((1.0 - pctDeviation / maxPctDeviation) * 100);
        return clamp(score, 0, 100);
    }

    // ==================== Speed Histogram ====================

    /**
     * Compute speed histogram from pre-counted buckets.
     * This is a lightweight post-processing step — the actual counting
     * happens inside the single pass in computeSummary().
     */
    int[] computeSpeedHistogram(List<TelemetrySample> samples) {
        int[] counts = new int[HISTOGRAM_BUCKET_COUNT];
        for (TelemetrySample s : samples) {
            int bucket = s.speedKmh / HISTOGRAM_BUCKET_WIDTH;
            if (bucket >= HISTOGRAM_BUCKET_COUNT) bucket = HISTOGRAM_BUCKET_COUNT - 1;
            counts[bucket]++;
        }
        int[] pct = new int[HISTOGRAM_BUCKET_COUNT];
        int total = samples.size();
        if (total > 0) {
            for (int i = 0; i < HISTOGRAM_BUCKET_COUNT; i++) {
                pct[i] = (int) Math.round((double) counts[i] / total * 100);
            }
        }
        return pct;
    }

    // ==================== Utilities ====================

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

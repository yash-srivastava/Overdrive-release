package com.overdrive.app.trips;

import com.overdrive.app.logging.DaemonLogger;

import java.util.ArrayList;
import java.util.List;

/**
 * Single-Pass Kinematic Scoring Engine for Driving DNA.
 *
 * Processes the entire telemetry array in ONE pass, computing all five Driving DNA
 * scores, micro-moments, avg/max speed, and kinematic state
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
 * Scoring axes (all five computed in the single pass):
 *   - Anticipation: EV-aware coast gap detection (accel < 5% = regen lift-off);
 *     credits coasts that end via brake, power re-application, light throttle, OR
 *     rolling to a stop — so one-pedal driving is rewarded, not penalized.
 *   - Smoothness: mean per-driving-sample pedal jerk (|Δaccel| + |Δbrake|),
 *     idle samples excluded, each sample's jerk winsorized so one panic stop
 *     can't crater the score.
 *   - Speed Discipline: rolling-window speed stddev, GPS spikes winsorized.
 *   - Efficiency: kWh/km against a single state-dependent band. The caller
 *     (TripAnalyticsManager) resolves energy — BMS kWh or SoC×nominal-capacity —
 *     into trip.energyPerKm BEFORE this runs, so there is exactly one unit axis.
 *   - Consistency: INTRA-trip behavioral uniformity — the coefficient of
 *     variation of per-window pedal demand (Welford, single-pass). Erratic
 *     "slam-then-coast" driving raises CV → lowers the score; a steady profile
 *     (uniformly calm OR uniformly assertive) scores high. This matches the
 *     user-facing definition and replaces the old trip-to-trip efficiency
 *     deviation (which was unit-confused and stuck at 0).
 *
 * Normalization & confidence
 * ───────────────────────────
 * Every axis maps its metric through {@link #smoothScore} (a logistic S-curve)
 * instead of a linear (1 - x/max) clamp, so real drivers spread across the
 * range rather than piling at the 0/100 rails. Each axis also computes a
 * coverage∈[0,1] — how much of the trip actually exercised that axis — and the
 * final score is shrunk toward the neutral 50 by coverage:
 *
 *     final = round(50 + coverage * (raw - 50))
 *
 * So a trip that couldn't measure an axis lands honestly near 50 (no magic
 * sentinel), and overall = mean/5 stays meaningful. Coverage is logged.
 *
 * All scores are integers in [0, 100] where 100 is optimal.
 */
public class TripScoreEngine {

    private static final DaemonLogger logger = DaemonLogger.getInstance("TripScoreEngine");

    // ==================== Constants ====================

    static final int MIN_SAMPLES = 30;
    private static final double MAX_COAST_GAP_SECONDS = 30.0;
    private static final int MIN_DRIVING_SPEED = 3;
    private static final double MIN_EFFICIENCY_DISTANCE = 0.5;
    private static final int LAUNCH_PROFILE_SAMPLES = 50;
    private static final int DEFAULT_SCORE = 50;

    // ── Logistic scoring (smoothScore) ──
    // SMOOTH_K is solved so that value==good → ~85 and value==bad → ~15:
    //   1/(1+e^{-k}) = 0.85  ⇒  k = ln(0.85/0.15) ≈ 1.7346
    private static final double SMOOTH_K = 1.7346;
    private static final double NEUTRAL_SCORE = 50.0;
    // Where "excellent" (→85) sits relative to each axis's "acceptable ceiling"
    // (→15). Lower-is-better axes treat `bad` as the ceiling and good = ceiling×frac.
    private static final double SMOOTH_GOOD_FRAC_JERK = 0.40;     // smoothness
    private static final double SMOOTH_GOOD_FRAC_STDDEV = 0.45;   // speed discipline
    private static final double ANTICIPATION_GOOD_FRAC = 0.25;    // gap≥target→excellent, ≤25%→poor

    // Anticipation coverage: ~one credited coast every 2 km is "fully measured".
    private static final double COASTS_PER_KM = 0.5;

    // Smoothness: per-sample combined pedal swing cap (winsorize one panic stop).
    // A single 5Hz tick physically can't swing both pedals more than this much.
    private static final double JERK_CAP_PER_SAMPLE = 60.0;
    // Fraction of trip samples that must be "driving jerk" samples for full coverage.
    private static final double SMOOTH_MIN_DRIVING_FRACTION = 0.30;

    // Speed discipline: clip GPS/CAN speed spikes before they inflate variance.
    private static final int SPEED_WINSOR_CAP = 200;

    // Consistency (intra-trip behavioral uniformity): CV of per-window pedal demand.
    // Anchors are the FLAT/URBAN baseline; kinematic state and gradient widen them
    // below (gridlock stop-and-go and mountain regen are inherently variable, so a
    // fixed 0.25 "excellent" anchor mis-scored normal driving as erratic).
    private static final double CONS_GOOD_CV = 0.35;   // ≤35% swing around mean → uniform
    private static final double CONS_BAD_CV = 0.95;    // ~95% swing → erratic
    private static final int CONS_MIN_WINDOWS = 4;     // need a few windows to talk uniformity
    private static final double CONS_MIN_MEAN_INTENSITY = 1.0; // guard CV div-by-~0 on idle trips
    // CV denominator floor. CV = σ/μ blows up when mean demand μ is tiny, which
    // perversely punishes the smoothest low-demand drivers (a 4% swing around a
    // 10% mean reads as 40% CV). Measuring the swing against max(μ, floor) keeps
    // CV a measure of demand variability, not an artifact of a small denominator.
    private static final double CONS_INTENSITY_FLOOR = 20.0;

    // Speed discipline window: 30 samples at 5Hz = 6 seconds
    private static final int SD_WINDOW_SIZE = 30;
    private static final int SD_WINDOW_STEP = 15; // 50% overlap
    private static final int SD_MIN_DRIVING = 6;   // ≥1.2s of motion qualifies a window

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

    // Elevation gain/loss estimation (smoothing, noise threshold, accuracy gate,
    // source-flip resets) lives in ElevationEstimator — SHARED with the recovery
    // path (TripDatabase.reconstructTripFromTelemetry) so the two can't drift.

    // ==================== Public API ====================

    /** DI5: elevation gain/loss [m] over a trip's journal rows up to endMs, on the estimator recovery uses. */
    static double[] journalElevation(List<TelemetrySample> rows, long endMs) {
        ElevationEstimator elevation = new ElevationEstimator();
        for (TelemetrySample s : rows) {
            if (s.timestampMs > endMs) continue;
            // DI5: a P read off the unchecked gear signal does not stop a moving car's climb counting, and
            // a stale gear (outside 1-6) is unknown.
            int gear = s.gearMode < 1 || s.gearMode > 6 ? 0
                    : s.gearMode == 1 && s.isDiLink5Moving() ? 4 : s.gearMode;
            elevation.addSample(s.timestampMs, s.altitude, s.verticalAccuracyM, s.altitudeIsMsl,
                    ElevationEstimator.isElevationEligible(gear, s.speedKmh));
        }
        return new double[] {elevation.getGainM(), elevation.getLossM()};
    }

    /** Time the samples cover: each spans to the next, at most 1 s, so 5 Hz ticks and 1 Hz journal rows both count true. */
    static double scoreableSeconds(List<TelemetrySample> samples) {
        double ms = 0;
        long lastStep = 0;
        for (int i = 1; i < samples.size(); i++) {
            long step = samples.get(i).timestampMs - samples.get(i - 1).timestampMs;
            if (step > 0) {
                lastStep = Math.min(step, 1_000L);
                ms += lastStep;
            }
        }
        return samples.isEmpty() ? 0 : (ms + lastStep) / 1000.0;
    }

    /**
     * DI5: the engine's windows and per-sample jerk count 5 Hz samples, so a 1 Hz journal row (a
     * resumed or recovered trip) stands in for each 200 ms of its second; 5 Hz samples pass through.
     */
    static List<TelemetrySample> diLink5At5Hz(List<TelemetrySample> samples) {
        List<TelemetrySample> out = new ArrayList<>(samples.size() * 2);
        for (int i = 0; i < samples.size(); i++) {
            TelemetrySample s = samples.get(i);
            out.add(s);
            // The last row spans as long as the step before it.
            long next = nextRowTime(samples, i, s.timestampMs);
            for (long t = s.timestampMs + 200L; t <= s.timestampMs + 800L && t < next; t += 200L) {
                out.add(s.atTimestamp(t));
            }
        }
        return out;
    }

    private static long nextRowTime(List<TelemetrySample> samples, int i, long timestamp) {
        return i + 1 < samples.size() ? samples.get(i + 1).timestampMs
                : i > 0 ? 2 * timestamp - samples.get(i - 1).timestampMs : timestamp + 1_000L;
    }

    /**
     * Virtual 5 Hz scoring stream for recovered DI5 journals. The scorer needs only
     * the original immutable row and the effective timestamp, not a copied row for
     * each tick. The input list must remain unchanged until scoring completes.
     */
    static final class ExpandedScoringSamples {
        private final List<TelemetrySample> rows;
        final int count;
        final double seconds;

        ExpandedScoringSamples(List<TelemetrySample> rows) {
            this.rows = rows;
            Cursor cursor = cursor();
            int n = 0;
            long previous = 0, lastStep = 0;
            double ms = 0;
            while (cursor.next() != null) {
                long timestamp = cursor.timestamp;
                if (n > 0) {
                    long step = timestamp - previous;
                    if (step > 0) {
                        lastStep = Math.min(step, 1_000L);
                        ms += lastStep;
                    }
                }
                previous = timestamp;
                n++;
            }
            count = n;
            seconds = n == 0 ? 0 : (ms + lastStep) / 1000.0;
        }

        Cursor cursor() { return new Cursor(rows); }

        static final class Cursor {
            private final List<TelemetrySample> rows;
            private int index = -1;
            private TelemetrySample row;
            private long next, limit;
            long timestamp;

            Cursor(List<TelemetrySample> rows) { this.rows = rows; }

            TelemetrySample next() {
                if (row != null && next <= row.timestampMs + 800L && next < limit) {
                    timestamp = next;
                    next += 200L;
                    return row;
                }
                if (++index >= rows.size()) {
                    row = null;
                    return null;
                }
                row = rows.get(index);
                timestamp = row.timestampMs;
                next = timestamp + 200L;
                limit = nextRowTime(rows, index, timestamp);
                return row;
            }
        }
    }

    /** DI5: scores need at least 30 s of scoreable data, covering at least a quarter of the trip. */
    static boolean hasDiLink5ScoringCoverage(double scoreableSeconds, int tripSeconds) {
        return scoreableSeconds >= 30.0 && (tripSeconds <= 0 || scoreableSeconds >= 0.25 * tripSeconds);
    }

    /** Stores elevation gain/loss and the gradient profile and average they imply for the trip's distance. */
    static GradientProfile applyElevation(TripRecord trip, double elevationGain, double elevationLoss) {
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
        return gradProfile;
    }

    /**
     * Compute all trip scores, micro-moments, and stats in a single pass.
     *
     * This is the main entry point. After this call, the TripRecord has all five
     * DNA scores, kinematicState, microMomentsJson, avgSpeedKmh, and maxSpeedKmh
     * populated.
     */
    public TripRecord computeSummary(TripRecord trip, List<TelemetrySample> samples) {
        return computeSummary(trip, samples, Double.NaN, Double.NaN);
    }

    /**
     * As {@link #computeSummary(TripRecord, List)}, with the trip's elevation gain/loss measured
     * elsewhere (NaN: from {@code samples}). DI5 passes the whole journal's, since its scoring
     * stream leaves out ticks without a vehicle speed.
     */
    TripRecord computeSummary(TripRecord trip, List<TelemetrySample> samples,
                              double presetGainM, double presetLossM) {
        return computeSummary(trip, samples, null, presetGainM, presetLossM);
    }

    TripRecord computeExpandedSummary(TripRecord trip, ExpandedScoringSamples expanded,
                                      double presetGainM, double presetLossM) {
        return computeSummary(trip, null, expanded, presetGainM, presetLossM);
    }

    private TripRecord computeSummary(TripRecord trip, List<TelemetrySample> samples,
                                      ExpandedScoringSamples expanded,
                                      double presetGainM, double presetLossM) {
        final int n = expanded != null ? expanded.count : samples == null ? 0 : samples.size();
        if (n < MIN_SAMPLES) {
            logger.warn("computeSummary: insufficient samples (" + n + "), using defaults");
            trip.anticipationScore = DEFAULT_SCORE;
            trip.smoothnessScore = DEFAULT_SCORE;
            trip.speedDisciplineScore = DEFAULT_SCORE;
            trip.efficiencyScore = DEFAULT_SCORE;
            trip.consistencyScore = DEFAULT_SCORE;
            trip.kinematicState = KinematicState.URBAN_FLOW.name();
            trip.microMomentsJson = new MicroMoments().toJson().toString();
            return trip;
        }

        ExpandedScoringSamples.Cursor cursor = expanded == null ? null : expanded.cursor();

        // ── Accumulators: kinematic state classification ──
        int stopCount = 0;
        boolean wasMoving = false;

        // ── Accumulators: avg/max speed ──
        long sumSpeed = 0;
        int maxSpeed = 0;

        // ── Accumulators: elevation (gradient profile) ──
        // Shared gated/smoothed/hysteresis pipeline — see ElevationEstimator.
        // A supplied whole-journal gain is authoritative (including zero). Do not
        // rebuild a second median window from the filtered scoring stream.
        final ElevationEstimator elevation = Double.isNaN(presetGainM)
                ? new ElevationEstimator() : null;

        // ── Accumulators: pedal jerk (smoothness score) ──
        // Only sums jerk between two consecutive DRIVING samples, each sample's
        // contribution winsorized at JERK_CAP_PER_SAMPLE, and normalized per
        // driving-sample (not wall-clock) so the metric is idle- and
        // polling-jitter-invariant.
        double drivingJerkSum = 0;
        int drivingJerkSamples = 0;
        int lastAccel = 0, lastBrake = 0, lastSpeed = 0;

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

        // ── Rolling window: speed discipline (sum-of-squares variance) ──
        // We use a circular buffer of speed values for the window. Speeds are
        // winsorized at SPEED_WINSOR_CAP on insertion so a single GPS/CAN spike
        // can't inflate the window variance quadratically.
        int[] sdWindowSpeeds = new int[SD_WINDOW_SIZE];
        boolean[] sdWindowIsDriving = new boolean[SD_WINDOW_SIZE];
        int sdWindowDrivingCount = 0;
        double sdWindowSum = 0;
        double sdWindowSumSq = 0;
        double sdTotalScore = 0;
        int sdWindowCount = 0;
        int sdStepCounter = 0;
        int sdDrivingSampleTotal = 0; // for speed-discipline coverage

        // ── Consistency (intra-trip behavioral uniformity) ──
        // Per-window mean pedal demand (accel+brake) over the DRIVING samples in
        // the window, accumulated on the SAME circular buffer cadence as speed
        // discipline, then fed through Welford online mean/variance so we get the
        // coefficient of variation of the driver's "intensity profile" across the
        // trip in a single pass. The accel/brake sums are gated on isDriving (and
        // divided by the driving count) so idle dwell at lights can't dilute the
        // intensity — same population the window qualifies on.
        int[] sdWindowAccel = new int[SD_WINDOW_SIZE];
        int[] sdWindowBrake = new int[SD_WINDOW_SIZE];
        double sdWindowAccelSum = 0;
        double sdWindowBrakeSum = 0;
        // Welford accumulators over the stream of per-window intensities:
        int consWindowCount = 0;
        double consMean = 0;
        double consM2 = 0;

        // ── Rolling window: pedal smoothness (micro-moments) ──
        int[] smoothWindowAccel = new int[SMOOTH_WINDOW_SIZE];
        boolean[] smoothWindowDriving = new boolean[SMOOTH_WINDOW_SIZE];
        int smoothStepCounter = 0;
        long[] smoothWindowTimes = cursor == null ? null : new long[SMOOTH_WINDOW_SIZE];

        // ── Output collectors ──
        MicroMoments microMoments = new MicroMoments();

        // ════════════════════════════════════════════════════════════
        //  SINGLE PASS: iterate samples exactly once
        // ════════════════════════════════════════════════════════════
        for (int i = 0; i < n; i++) {
            final TelemetrySample s = cursor == null ? samples.get(i) : cursor.next();
            final long timestamp = cursor == null ? s.timestampMs : cursor.timestamp;
            final int speed = s.speedKmh;
            final int accel = s.accelPedalPercent;
            final int brake = s.brakePedalPercent;

            // ── 1. Avg/Max speed ──
            sumSpeed += speed;
            if (speed > maxSpeed) maxSpeed = speed;

            // ── 2. Kinematic state: stop counting ──
            // Count a stop when the car drops to a near-standstill (≤1 km/h, not
            // exact 0) after having been driving — creep-to-a-crawl in gridlock
            // and sensors that floor at 1 km/h never hit a clean 0 but are still
            // stops. wasMoving arms once above the driving threshold.
            if (speed > MIN_DRIVING_SPEED) wasMoving = true;
            if (speed <= 1 && wasMoving) {
                stopCount++;
                wasMoving = false;
            }

            // ── 3b. Elevation tracking (gradient profile) ──
            // Without journal totals, every sample feeds the shared estimator: it gates on movement
            // (gear P / unknown-at-standstill excluded, so the park-debounce
            // tail and idle noise can't accumulate), gates on reported vertical
            // accuracy, median-smooths over 10 s, resets on MSL/ellipsoid source
            // flips and long gaps, and banks movement through a hysteresis band
            // so sub-threshold noise contributes exactly zero.
            if (elevation != null) {
                elevation.addSample(timestamp, s.altitude, s.verticalAccuracyM,
                        s.altitudeIsMsl,
                        ElevationEstimator.isElevationEligible(s.gearMode, speed));
            }

            final boolean driving = speed > MIN_DRIVING_SPEED;
            final boolean wasDriving = lastSpeed > MIN_DRIVING_SPEED;

            // ── 4. Pedal jerk (smoothness) ──
            // Only between two consecutive driving samples (excludes idle creep
            // and the launch/stop transients), and each sample's contribution is
            // winsorized so a single panic stop can't dominate the integral.
            if (i > 0 && driving && wasDriving) {
                double sampleJerk = Math.abs(accel - lastAccel) + Math.abs(brake - lastBrake);
                if (sampleJerk > JERK_CAP_PER_SAMPLE) sampleJerk = JERK_CAP_PER_SAMPLE;
                drivingJerkSum += sampleJerk;
                drivingJerkSamples++;
            }

            // ── 5. Coast gap detection (anticipation) ──
            // EV-aware: accel < 5% counts as lifting off (regen zone). A coast is
            // CREDITED whenever it ends by any of: friction brake, re-applying
            // power (≥15%), feathering light throttle (5–14%), OR rolling to a
            // stop. Crediting light-throttle and roll-to-stop coasts is what lets
            // one-pedal EV drivers (who rarely touch the brake) score honestly.
            if (accel < 5 && brake == 0 && speed > MIN_DRIVING_SPEED) {
                if (coastStartTime == null) coastStartTime = timestamp;
            } else if (coastStartTime != null
                    && (brake > 0 || accel >= 5 || speed <= MIN_DRIVING_SPEED)) {
                // Coast ended — credit the gap regardless of HOW it ended.
                long gapMs = timestamp - coastStartTime;
                double gapSec = gapMs / 1000.0;
                if (gapSec > 0 && gapSec < MAX_COAST_GAP_SECONDS) {
                    coastGapSumMs += gapMs;
                    coastGapCount++;
                }
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
                    microMoments.launches.add(buildLaunchProfile(
                            launchStartTime, launchPeakAccel, launchCurveBuffer));
                }
            } else {
                // Detect launch: was stationary, now moving. Arm at a near-
                // standstill (≤1 km/h) so a gentle 1→2→3→4 creep-off still counts
                // as a launch, and keep the arm until the car genuinely crosses
                // the driving threshold (a 1 km/h creep must NOT disarm it — that
                // was dropping every soft launch).
                if (speed <= 1) {
                    wasStationary = true;
                } else if (wasStationary && speed > MIN_DRIVING_SPEED) {
                    // Start capturing launch profile
                    launchStartTime = timestamp;
                    launchPeakAccel = accel;
                    launchCurveBuffer = new ArrayList<>(LAUNCH_PROFILE_SAMPLES);
                    launchCurveBuffer.add(accel);
                    launchCaptureRemaining = LAUNCH_PROFILE_SAMPLES - 1;
                    wasStationary = false;
                }
                if (speed > MIN_DRIVING_SPEED) wasStationary = false;
            }

            // ── 7. Coast-brake events (micro-moments) ──
            if (accel < 5 && brake == 0 && mmCoastStartTime == null && speed > MIN_DRIVING_SPEED) {
                mmCoastStartTime = timestamp;
            }
            if (brake > 0 && mmCoastStartTime != null) {
                long gapMs = timestamp - mmCoastStartTime;
                if (gapMs > 0 && gapMs < (long) (MAX_COAST_GAP_SECONDS * 1000)) {
                    MicroMoments.CoastBrakeEvent event = new MicroMoments.CoastBrakeEvent();
                    event.coastGapMs = gapMs;
                    event.speedAtBrake = speed;
                    microMoments.coastBrakeEvents.add(event);
                }
                mmCoastStartTime = null;
            }
            if (accel >= 5) mmCoastStartTime = null;

            // ── 8. Speed discipline + consistency: rolling window via circular buffer ──
            // One circular buffer feeds two axes:
            //   • speed discipline = avg per-window σ(speed)  (driving samples only)
            //   • consistency      = CV of per-window mean pedal demand (all samples)
            final boolean isDriving = driving;
            if (isDriving) sdDrivingSampleTotal++;
            int circIdx = i % SD_WINDOW_SIZE;

            // If window is full, evict the oldest entry. Both the speed sums
            // (for σ) and the pedal-intensity sums (for consistency) are gated on
            // the slot's stored driving flag, so eviction is symmetric with the
            // driving-gated insertion below.
            if (i >= SD_WINDOW_SIZE) {
                if (sdWindowIsDriving[circIdx]) {
                    int oldSpeed = sdWindowSpeeds[circIdx];
                    sdWindowDrivingCount--;
                    sdWindowSum -= oldSpeed;
                    sdWindowSumSq -= (double) oldSpeed * oldSpeed;
                    sdWindowAccelSum -= sdWindowAccel[circIdx];
                    sdWindowBrakeSum -= sdWindowBrake[circIdx];
                }
            }

            // Insert new entry. Speed is winsorized so one GPS/CAN spike doesn't
            // blow up the window variance; pedal values are already 0–100 bounded.
            // Pedal demand is accumulated only for driving samples (same
            // population as the σ sums), so idle dwell doesn't dilute intensity.
            int winsorSpeed = speed > SPEED_WINSOR_CAP ? SPEED_WINSOR_CAP : speed;
            sdWindowSpeeds[circIdx] = winsorSpeed;
            sdWindowIsDriving[circIdx] = isDriving;
            sdWindowAccel[circIdx] = accel;
            sdWindowBrake[circIdx] = brake;
            if (isDriving) {
                sdWindowDrivingCount++;
                sdWindowSum += winsorSpeed;
                sdWindowSumSq += (double) winsorSpeed * winsorSpeed;
                sdWindowAccelSum += accel;
                sdWindowBrakeSum += brake;
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

                    // Consistency: this window's mean pedal demand (accel+brake)
                    // over its DRIVING samples, streamed into Welford. Dividing by
                    // sdWindowDrivingCount (the same count this window qualified on,
                    // ≥ SD_MIN_DRIVING here so never zero) makes intensity a pure
                    // measure of pedal style, invariant to how much idle dwell the
                    // 6s window happened to straddle.
                    double windowIntensity =
                            (sdWindowAccelSum + sdWindowBrakeSum) / sdWindowDrivingCount;
                    consWindowCount++;
                    double delta = windowIntensity - consMean;
                    consMean += delta / consWindowCount;
                    consM2 += delta * (windowIntensity - consMean);
                }
            }

            // ── 9. Pedal smoothness: rolling window via circular buffer ──
            int smoothCircIdx = i % SMOOTH_WINDOW_SIZE;
            smoothWindowAccel[smoothCircIdx] = accel;
            smoothWindowDriving[smoothCircIdx] = isDriving;
            if (smoothWindowTimes != null) smoothWindowTimes[smoothCircIdx] = timestamp;

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
                    window.startTime = smoothWindowTimes == null
                            ? samples.get(i - SMOOTH_WINDOW_SIZE + 1).timestampMs
                            : smoothWindowTimes[(smoothCircIdx + 1) % SMOOTH_WINDOW_SIZE];
                    window.stdDev = Math.sqrt(aVar);
                    microMoments.smoothnessWindows.add(window);
                }
            }

            lastAccel = accel;
            lastBrake = brake;
            lastSpeed = speed;
        }
        // ════════════════════════════════════════════════════════════
        //  END SINGLE PASS
        // ════════════════════════════════════════════════════════════

        // Finalize any in-progress launch capture (trip ended mid-launch)
        if (launchCaptureRemaining > 0 && launchCurveBuffer != null && !launchCurveBuffer.isEmpty()) {
            microMoments.launches.add(buildLaunchProfile(launchStartTime, launchPeakAccel, launchCurveBuffer));
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
        GradientProfile gradProfile = elevation != null
                ? applyElevation(trip, elevation.getGainM(), elevation.getLossM())
                : applyElevation(trip, presetGainM, presetLossM);
        final double elevationGain = trip.elevationGainM;
        final double elevationLoss = trip.elevationLossM;
        double gainPerKm = trip.distanceKm > 0 ? elevationGain / trip.distanceKm : 0;
        double lossPerKm = trip.distanceKm > 0 ? elevationLoss / trip.distanceKm : 0;

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
        double consistencyGradientFactor;  // Multiplier on CV anchors (higher = more lenient)
        switch (gradProfile) {
            case MOUNTAIN_CLIMB:
                efficiencyBestAdjust = 0;
                efficiencyGradientFactor = 1.6;    // 60% wider efficiency range
                smoothnessGradientFactor = 1.4;    // 40% more jerk tolerance
                anticipationGradientFactor = 0.6;   // 40% shorter coast gap expected
                consistencyGradientFactor = 1.3;   // sustained climb forces pedal modulation
                break;
            case MOUNTAIN_DESCENT:
                efficiencyBestAdjust = -0.05;       // Good driver should be net-negative kWh/km
                efficiencyGradientFactor = 1.0;     // Normal worst-case (they shouldn't be consuming much)
                smoothnessGradientFactor = 1.35;    // Regen modulation causes pedal variation
                anticipationGradientFactor = 0.5;   // Very little coasting — managing speed via regen
                consistencyGradientFactor = 1.4;   // regen speed-management = inherently variable demand
                break;
            case HILLY:
                efficiencyBestAdjust = 0;
                efficiencyGradientFactor = 1.25;   // 25% wider efficiency range
                smoothnessGradientFactor = 1.15;   // 15% more jerk tolerance
                anticipationGradientFactor = 0.85;  // 15% shorter coast gap expected
                consistencyGradientFactor = 1.15;  // rolling terrain adds some demand variation
                break;
            default: // FLAT
                efficiencyBestAdjust = 0;
                efficiencyGradientFactor = 1.0;
                smoothnessGradientFactor = 1.0;
                anticipationGradientFactor = 1.0;
                consistencyGradientFactor = 1.0;
                break;
        }

        // ── Populate avg/max speed ──
        trip.avgSpeedKmh = (double) sumSpeed / n;
        trip.maxSpeedKmh = maxSpeed;

        // ════════════════════════════════════════════════════════════
        //  SCORE COMPUTATION (all from accumulators, no re-iteration)
        //
        //  Each axis: raw = smoothScore(metric, good, bad) on a logistic curve,
        //  then final = applyCoverage(raw, coverage) to shrink toward the
        //  neutral 50 by how much of the trip actually exercised the axis.
        // ════════════════════════════════════════════════════════════

        // A. Anticipation — average coast gap before the coast ends (higher = better).
        //    Coverage scales with distance: ~one credited coast per 2 km is "fully
        //    measured". This replaces the old hard ≥3-transitions gate that pinned
        //    one-pedal drivers at 50.
        double targetGapMs;
        switch (kinState) {
            case HEAVY_GRIDLOCK:   targetGapMs = 800;  break;
            case HIGHWAY_CRUISING: targetGapMs = 1500; break;
            default:               targetGapMs = 2500; break;
        }
        targetGapMs *= anticipationGradientFactor;
        double anticipationCoverage = 0;
        double rawAnticipation = NEUTRAL_SCORE;
        if (coastGapCount > 0 && targetGapMs > 0) {
            double avgGapMs = (double) coastGapSumMs / coastGapCount;
            rawAnticipation = smoothScore(avgGapMs,
                    /* good */ targetGapMs,
                    /* bad  */ targetGapMs * ANTICIPATION_GOOD_FRAC);
            double expectedCoasts = Math.max(1.0, trip.distanceKm * COASTS_PER_KM);
            anticipationCoverage = Math.min(1.0, coastGapCount / expectedCoasts);
        }
        trip.anticipationScore = applyCoverage(rawAnticipation, anticipationCoverage);

        // B. Smoothness — mean per-driving-sample pedal jerk (lower = smoother).
        //    Cadence- and idle-invariant: we divide the (winsorized) jerk sum by
        //    the number of driving-jerk samples, not wall-clock seconds. Coverage
        //    gates on having enough driving samples to characterize smoothness.
        double maxJerk;
        switch (kinState) {
            case HEAVY_GRIDLOCK:   maxJerk = 20; break;
            case HIGHWAY_CRUISING: maxJerk = 8;  break;
            default:               maxJerk = 12; break;
        }
        maxJerk *= smoothnessGradientFactor;
        double smoothnessCoverage = 0;
        double rawSmoothness = NEUTRAL_SCORE;
        if (drivingJerkSamples > 0) {
            double meanJerk = drivingJerkSum / drivingJerkSamples;
            rawSmoothness = smoothScore(meanJerk,
                    /* good */ maxJerk * SMOOTH_GOOD_FRAC_JERK,
                    /* bad  */ maxJerk);
            // Coverage is relative to DRIVING time, not wall-clock: a smooth
            // driving segment inside an idle-heavy trip should keep its score,
            // not be dragged toward 50 by time spent parked at lights.
            smoothnessCoverage = Math.min(1.0,
                    drivingJerkSamples / Math.max(1.0, sdDrivingSampleTotal * SMOOTH_MIN_DRIVING_FRACTION));
        }
        trip.smoothnessScore = applyCoverage(rawSmoothness, smoothnessCoverage);

        // C. Speed Discipline — average per-window σ(speed) (lower = steadier).
        //    Coverage = qualifying windows vs how many windows the driving samples
        //    could have produced, so an almost-idle trip honestly reads ~50 rather
        //    than the old flat DEFAULT.
        double maxStdDev;
        switch (kinState) {
            case HEAVY_GRIDLOCK:   maxStdDev = 20.0; break;
            case HIGHWAY_CRUISING: maxStdDev = 12.0; break;
            default:               maxStdDev = 16.0; break;
        }
        double speedDisciplineCoverage = 0;
        double rawSpeedDiscipline = NEUTRAL_SCORE;
        if (sdWindowCount > 0) {
            double avgStdDev = sdTotalScore / sdWindowCount;
            rawSpeedDiscipline = smoothScore(avgStdDev,
                    /* good */ maxStdDev * SMOOTH_GOOD_FRAC_STDDEV,
                    /* bad  */ maxStdDev);
            double expectedWindows = Math.max(1.0, (double) sdDrivingSampleTotal / SD_WINDOW_STEP);
            speedDisciplineCoverage = Math.min(1.0, sdWindowCount / expectedWindows);
        }
        trip.speedDisciplineScore = applyCoverage(rawSpeedDiscipline, speedDisciplineCoverage);

        // D. Efficiency — kWh/km against a single state-dependent band (lower = better).
        //    Energy is resolved upstream (BMS kWh, or SoC×nominal-capacity) BEFORE
        //    this runs, so there is exactly one unit axis — no SoC%/km fallback,
        //    no dimensionally-mismatched constants. When BMS kWh is available we
        //    use the SIGNED net energy so a regen-dominant descent yields a
        //    negative kWh/km that smoothScore maps above the `good` anchor; the
        //    SoC-estimated fallback (trip.energyPerKm) stays consumption-only
        //    because 1%-resolution SoC can't reliably tell regen from jitter.
        double signedBmsKwh = trip.getSignedEnergyKwh();
        // Non-zero means a MEASURED energy figure is available. Signed (regen can
        // make it negative) when it came from the remaining-energy pair; when it
        // came from the metered consumption counter it is gross, so non-negative.
        // Either way the branch below divides by distance, which is what this
        // needs — it is not an assertion that the remaining-kWh pair exists.
        boolean haveBmsKwh = signedBmsKwh != 0;
        double kwhPerKm;
        boolean efficiencyMeasured;
        if (trip.distanceKm >= MIN_EFFICIENCY_DISTANCE && haveBmsKwh) {
            kwhPerKm = signedBmsKwh / trip.distanceKm; // may be negative (regen)
            efficiencyMeasured = true;
        } else if (trip.distanceKm >= MIN_EFFICIENCY_DISTANCE && trip.energyPerKm > 0) {
            kwhPerKm = trip.energyPerKm; // SoC-estimated, non-negative
            efficiencyMeasured = true;
        } else {
            kwhPerKm = 0;
            efficiencyMeasured = false;
        }
        double efficiencyCoverage = 0;
        double rawEfficiency = NEUTRAL_SCORE;
        if (efficiencyMeasured) {
            double bestEff, worstEff;
            switch (kinState) {
                case HEAVY_GRIDLOCK:   bestEff = 0.10; worstEff = 0.35; break;
                case HIGHWAY_CRUISING: bestEff = 0.14; worstEff = 0.35; break;
                default:               bestEff = 0.11; worstEff = 0.32; break;
            }
            bestEff += efficiencyBestAdjust;
            worstEff *= efficiencyGradientFactor;
            rawEfficiency = smoothScore(kwhPerKm, /* good */ bestEff, /* bad */ worstEff);
            efficiencyCoverage = 1.0; // whole-trip aggregate: measured or not
        }
        trip.efficiencyScore = applyCoverage(rawEfficiency, efficiencyCoverage);

        // E. Consistency — INTRA-trip behavioral uniformity (lower CV = steadier).
        //    consCV is the coefficient of variation of per-window pedal demand,
        //    accumulated online (Welford) during the single pass. A driver with a
        //    steady intensity profile (uniformly calm OR uniformly assertive) has
        //    low CV → high score; "slam-then-coast" alternation has high CV → low
        //    score. This matches the user-facing "how uniform is your style" copy.
        double consistencyCoverage = 0;
        double rawConsistency = NEUTRAL_SCORE;
        double consCV = 0;
        // Need ≥2 windows for a coefficient of variation to mean anything: a
        // single window forces between-window variance to 0, which would hand a
        // spuriously near-perfect uniformity score to a trip that demonstrated no
        // measurable intra-trip variation at all. With <2 windows we stay at the
        // neutral 50 (coverage 0).
        if (consWindowCount >= 2 && consMean > CONS_MIN_MEAN_INTENSITY) {
            double consVariance = consM2 / consWindowCount; // population variance
            if (consVariance < 0) consVariance = 0;
            // Divide the std-dev by max(mean, floor) rather than the raw mean so a
            // small denominator can't inflate CV for a calm, low-demand driver.
            double consDenom = Math.max(consMean, CONS_INTENSITY_FLOOR);
            consCV = Math.sqrt(consVariance) / consDenom;
            // Context-aware anchors: gridlock stop-and-go is unavoidably variable,
            // highway cruising should be tighter; gradient widens further. Mirrors
            // how every other axis adapts its band to kinematic state + terrain.
            double goodCV = CONS_GOOD_CV;
            double badCV = CONS_BAD_CV;
            switch (kinState) {
                case HEAVY_GRIDLOCK:   goodCV = 0.45; badCV = 1.10; break;
                case HIGHWAY_CRUISING: goodCV = 0.25; badCV = 0.70; break;
                default: /* URBAN_FLOW: baseline */                 break;
            }
            goodCV *= consistencyGradientFactor;
            badCV *= consistencyGradientFactor;
            rawConsistency = smoothScore(consCV, /* good */ goodCV, /* bad */ badCV);
            consistencyCoverage = Math.min(1.0, (double) consWindowCount / CONS_MIN_WINDOWS);
        }
        trip.consistencyScore = applyCoverage(rawConsistency, consistencyCoverage);

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
                + " C=" + trip.consistencyScore
                + " | cov[A=" + String.format("%.2f", anticipationCoverage)
                + " S=" + String.format("%.2f", smoothnessCoverage)
                + " SD=" + String.format("%.2f", speedDisciplineCoverage)
                + " E=" + String.format("%.2f", efficiencyCoverage)
                + " C=" + String.format("%.2f", consistencyCoverage) + "]"
                + " consCV=" + String.format("%.2f", consCV)
                + " kwh/km=" + String.format("%.3f", kwhPerKm));

        return trip;
    }

    // ==================== Utilities ====================

    private static MicroMoments.LaunchProfile buildLaunchProfile(long startTime, int peakAccel,
                                                                List<Integer> curve) {
        MicroMoments.LaunchProfile profile = new MicroMoments.LaunchProfile();
        profile.startTime = startTime;
        profile.peakAccelPercent = peakAccel;
        profile.accelCurve = new int[curve.size()];
        for (int k = 0; k < curve.size(); k++) {
            profile.accelCurve[k] = curve.get(k);
        }
        return profile;
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Logistic score in (0, 100). Maps a raw metric onto a smooth S-curve so
     * scores spread across the range instead of saturating at the rails the way
     * the old linear {@code (1 - value/max)} clamp did.
     *
     * <p>The two reference points define the curve:
     * <ul>
     *   <li>{@code value == good} → ~85</li>
     *   <li>midpoint of good/bad → 50</li>
     *   <li>{@code value == bad}  → ~15</li>
     * </ul>
     *
     * <p>Works for both higher-is-better axes (pass {@code good > bad}, e.g.
     * anticipation gap) and lower-is-better axes ({@code good < bad}, e.g. jerk)
     * — the sign of the half-width handles the direction. Extreme inputs
     * compress toward 0/100 asymptotically rather than clamping, and negative
     * values are fine (e.g. regen-positive kWh/km on a descent).
     *
     * @param good  metric value that should score ~85 (the "excellent" anchor)
     * @param bad   metric value that should score ~15 (the "poor" anchor)
     */
    static double smoothScore(double value, double good, double bad) {
        double m = (good + bad) / 2.0;
        double h = (bad - good) / 2.0;
        if (h == 0) return NEUTRAL_SCORE; // misconfig: good == bad
        double z = (value - m) / h; // z = -1 at `good`, +1 at `bad`
        return 100.0 / (1.0 + Math.exp(SMOOTH_K * z));
    }

    /**
     * Shrink a raw score toward the neutral 50 by how much of the trip actually
     * exercised the axis (empirical-Bayes style). An unmeasured axis (coverage 0)
     * lands at 50; a fully-measured one (coverage 1) keeps its raw score.
     * Returns an int in [0, 100].
     */
    static int applyCoverage(double raw, double coverage) {
        double cov = Math.max(0.0, Math.min(1.0, coverage));
        return clamp((int) Math.round(NEUTRAL_SCORE + cov * (raw - NEUTRAL_SCORE)), 0, 100);
    }
}

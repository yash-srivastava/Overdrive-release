package com.overdrive.app.trips;

import java.util.Arrays;

/**
 * Streaming elevation gain/loss estimator shared by the LIVE scoring path
 * (TripScoreEngine) and the RECOVERY path (TripDatabase.reconstructTripFromTelemetry),
 * so the two can never drift apart again (they previously hand-synced a 2.0 m
 * deadband constant and used different sampling/trim rules).
 *
 * Why this exists — GPS altitude is the noisiest GNSS axis (vertical error is
 * typically 1.5-3x horizontal). The old implementation fabricated climb totals
 * three ways:
 *  1. Count-based decimation ("every 5th sample") that never averaged, so each
 *     retained sample carried full noise amplitude;
 *  2. A 2 m full-delta deadband ratchet that banked the ENTIRE noise excursion
 *     (threshold included) every time jitter crossed ±2 m — parked or idling,
 *     since nothing gated on movement;
 *  3. No vertical-accuracy gate and no reset when the altitude source flipped
 *     between MSL and ellipsoidal height (a 30-60 m geoid step banked as climb).
 *
 * The pipeline here:
 *  1. GATE: samples must be movement-eligible (not in Park / not gear-unknown
 *     at standstill), carry a plausible altitude, and pass the vertical-accuracy
 *     gate when the fix reports one.
 *  2. RESET: the filter state is cleared when the MSL/ellipsoid source flips or
 *     after a long gap in accepted samples, so a step change in the altitude
 *     datum can never be integrated as real climb.
 *  3. SMOOTH: a time-based (10 s) moving MEDIAN over accepted altitudes. Median
 *     (not mean) so a single multipath spike inside the window contributes
 *     nothing. Works at both the live 5 Hz rate and the recovered 1 Hz rate
 *     because the window is defined in time, not sample counts.
 *  4. HYSTERESIS: direction-change counting on the smoothed signal. Movement is
 *     only banked after the smoothed altitude has moved >= threshold away from
 *     the last confirmed extremum. Noise oscillating inside the threshold band
 *     contributes exactly zero; a real sustained climb pays the threshold cost
 *     ONCE per direction change instead of once per banked delta (a naive
 *     "delta - threshold" ratchet systematically zeroes out real climbs that
 *     arrive in threshold-sized increments). The threshold is the noise floor
 *     or the fix's own reported vertical accuracy, whichever is larger.
 */
public final class ElevationEstimator {

    // ==================== Shared constants (single source of truth) ====================

    /** Minimum hysteresis band (m). GPS vertical noise is routinely 3-5 m even
     *  with a good sky view; below this, movement is indistinguishable from noise. */
    public static final double ALT_NOISE_THRESHOLD_M = 5.0;

    /** Reject fixes whose reported vertical accuracy (1-sigma, metres) is worse
     *  than this. Unreported accuracy (0 / NaN — older telemetry files, HALs that
     *  don't populate it) is ACCEPTED so pre-existing recordings keep working. */
    public static final double MAX_VERTICAL_ACCURACY_M = 10.0;

    /** Time span of the moving-median smoothing window. */
    public static final long SMOOTHING_WINDOW_MS = 10_000;

    /** A gap in accepted altitude samples longer than this resets the filter:
     *  the vehicle may have moved vertically (parking garage) or the receiver
     *  re-acquired with a different solution, so bridging the gap with a delta
     *  would fabricate gain/loss. */
    public static final long MAX_ALT_GAP_MS = 30_000;

    /** Gear codes matching TelemetrySample.gearMode (1=P). 0 = unknown. */
    private static final int GEAR_PARK = 1;
    private static final int GEAR_UNKNOWN = 0;

    // ==================== Filter state ====================

    private final MedianWindow window = new MedianWindow();
    private long lastAcceptedTs = Long.MIN_VALUE;
    private boolean lastSourceMsl = false;
    private boolean haveSource = false;

    // Hysteresis state over the smoothed signal.
    private double extremum = Double.NaN; // last confirmed turning point / tracking edge
    private int direction = 0;            // 0 = undetermined, +1 climbing, -1 descending

    private double gainM = 0;
    private double lossM = 0;

    /**
     * Movement-eligibility gate for elevation accumulation. Excludes the parked
     * state (gear P — covers the park-debounce tail and mid-trip parking) and the
     * fully-unknown state (no gear AND no speed). Gear D at a standstill (idling
     * at a light) is deliberately allowed through: real altitude is constant
     * there, and the median + hysteresis stages reduce its noise to zero — while
     * excluding it would starve trips whose speed channel is dead (GPS-carried
     * trips record speedKmh == 0 throughout).
     */
    public static boolean isElevationEligible(int gearMode, int speedKmh) {
        if (gearMode == GEAR_PARK) return false;
        return gearMode != GEAR_UNKNOWN || speedKmh > 0;
    }

    /**
     * Feed one telemetry sample. Non-eligible or rejected samples are skipped
     * (they do not reset the filter; a prolonged rejection run resets naturally
     * via the MAX_ALT_GAP_MS rule).
     *
     * @param timestampMs        sample wall-clock time
     * @param altitudeM          altitude in metres; 0.0 / NaN = no fix
     * @param verticalAccuracyM  reported 1-sigma vertical accuracy; <=0 / NaN = unreported
     * @param altitudeIsMsl      true if this altitude is MSL (geoid-corrected)
     * @param eligible           movement gate, see {@link #isElevationEligible}
     */
    public void addSample(long timestampMs, double altitudeM, double verticalAccuracyM,
                          boolean altitudeIsMsl, boolean eligible) {
        if (!eligible) return;
        if (Double.isNaN(altitudeM) || altitudeM == 0.0) return;

        // Vertical-accuracy gate — only when the fix actually reports one.
        boolean vAccReported = !Double.isNaN(verticalAccuracyM) && verticalAccuracyM > 0;
        if (vAccReported && verticalAccuracyM > MAX_VERTICAL_ACCURACY_M) return;

        // Source-flip reset: MSL vs ellipsoidal differ by the local geoid
        // undulation (tens of metres). A per-fix HAL flip is a datum step, not
        // vertical movement.
        if (haveSource && altitudeIsMsl != lastSourceMsl) {
            reset();
        }
        lastSourceMsl = altitudeIsMsl;
        haveSource = true;

        // Long-gap reset.
        if (lastAcceptedTs != Long.MIN_VALUE
                && timestampMs - lastAcceptedTs > MAX_ALT_GAP_MS) {
            reset();
        }
        lastAcceptedTs = timestampMs;

        // Smoothing window (time-based).
        window.add(timestampMs, altitudeM, vAccReported ? verticalAccuracyM : 0.0);
        double smoothed = window.medianAltitude();
        double threshold = Math.max(ALT_NOISE_THRESHOLD_M, window.medianVerticalAccuracy());

        // Hysteresis direction-change counting on the smoothed signal.
        if (Double.isNaN(extremum)) {
            extremum = smoothed;
            return;
        }
        if (direction == 0) {
            // Undetermined: commit to a direction only once the smoothed signal
            // has escaped the noise band around the starting extremum. The first
            // band-width of movement is intentionally NOT banked (unknowable
            // whether it was drift or motion).
            if (smoothed >= extremum + threshold) {
                direction = 1;
                extremum = smoothed;
            } else if (smoothed <= extremum - threshold) {
                direction = -1;
                extremum = smoothed;
            }
        } else if (direction > 0) {
            if (smoothed > extremum) {
                gainM += smoothed - extremum;   // still climbing: bank incrementally
                extremum = smoothed;
            } else if (extremum - smoothed >= threshold) {
                direction = -1;                 // confirmed reversal
                extremum = smoothed;            // threshold band consumed, not banked
            }
        } else {
            if (smoothed < extremum) {
                lossM += extremum - smoothed;
                extremum = smoothed;
            } else if (smoothed - extremum >= threshold) {
                direction = 1;
                extremum = smoothed;
            }
        }
    }

    /** Cumulative smoothed elevation gain (m). */
    public double getGainM() { return gainM; }

    /** Cumulative smoothed elevation loss (m). */
    public double getLossM() { return lossM; }

    private void reset() {
        window.clear();
        extremum = Double.NaN;
        direction = 0;
    }

    /** FIFO expiry plus two sorted primitive windows. Typical 5 Hz input holds 51 rows;
     * grow for denser or non-monotonic input rather than imposing a sample-count cap. */
    static final class MedianWindow {
        private long[] timestamps = new long[16];
        private double[] altitudes = new double[16];
        private double[] accuracies = new double[16];
        private double[] sortedAltitudes = new double[16];
        private double[] sortedAccuracies = new double[16];
        private int head;
        private int size;
        private int accuracyCount;

        void add(long timestamp, double altitude, double accuracy) {
            // Expire exactly the same FIFO prefix as the original time-based deque.
            while (size > 0 && timestamp - timestamps[head] > SMOOTHING_WINDOW_MS) {
                remove(sortedAltitudes, size, altitudes[head]);
                double expiredAccuracy = accuracies[head];
                if (expiredAccuracy > 0) {
                    remove(sortedAccuracies, accuracyCount, expiredAccuracy);
                    accuracyCount--;
                }
                head = (head + 1) & (timestamps.length - 1);
                size--;
            }
            if (size == timestamps.length) grow();
            int tail = (head + size) & (timestamps.length - 1);
            timestamps[tail] = timestamp;
            altitudes[tail] = altitude;
            accuracies[tail] = accuracy;
            insert(sortedAltitudes, size, altitude);
            size++;
            if (accuracy > 0) {
                insert(sortedAccuracies, accuracyCount, accuracy);
                accuracyCount++;
            }
        }

        void clear() {
            head = size = accuracyCount = 0;
        }

        double medianAltitude() {
            return median(sortedAltitudes, size);
        }

        double medianVerticalAccuracy() {
            return accuracyCount == 0 ? 0 : median(sortedAccuracies, accuracyCount);
        }

        private void grow() {
            int oldCapacity = timestamps.length;
            int capacity = oldCapacity * 2;
            long[] newTimestamps = new long[capacity];
            double[] newAltitudes = new double[capacity];
            double[] newAccuracies = new double[capacity];
            for (int i = 0; i < size; i++) {
                int old = (head + i) & (oldCapacity - 1);
                newTimestamps[i] = timestamps[old];
                newAltitudes[i] = altitudes[old];
                newAccuracies[i] = accuracies[old];
            }
            timestamps = newTimestamps;
            altitudes = newAltitudes;
            accuracies = newAccuracies;
            sortedAltitudes = Arrays.copyOf(sortedAltitudes, capacity);
            sortedAccuracies = Arrays.copyOf(sortedAccuracies, capacity);
            head = 0;
        }

        private static int lowerBound(double[] values, int count, double value) {
            int lo = 0, hi = count;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (Double.compare(values[mid], value) < 0) lo = mid + 1;
                else hi = mid;
            }
            return lo;
        }

        private static void insert(double[] values, int count, double value) {
            int at = lowerBound(values, count, value);
            System.arraycopy(values, at, values, at + 1, count - at);
            values[at] = value;
        }

        private static void remove(double[] values, int count, double value) {
            // Every expired FIFO value has exactly one matching sorted entry.
            int at = lowerBound(values, count, value);
            System.arraycopy(values, at + 1, values, at, count - at - 1);
        }

        private static double median(double[] values, int count) {
            return (count % 2 == 1) ? values[count / 2]
                    : (values[count / 2 - 1] + values[count / 2]) / 2.0;
        }
    }
}

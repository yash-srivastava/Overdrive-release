package com.overdrive.app.trips;

import java.util.ArrayDeque;
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
 *     after a long gap or backward timestamp jump in accepted samples, so
 *     discontinuities cannot be integrated as real climb.
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

    private final ArrayDeque<long[]> window = new ArrayDeque<>(); // {timestampMs, altBits, vAccBits}
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

        // The median window assumes chronological samples. A backward clock
        // jump would otherwise retain future-dated samples and bridge the jump.
        // Reset only the filter, preserving already accumulated gain and loss.
        if (lastAcceptedTs != Long.MIN_VALUE
                && (timestampMs < lastAcceptedTs
                    || timestampMs - lastAcceptedTs > MAX_ALT_GAP_MS)) {
            reset();
        }
        lastAcceptedTs = timestampMs;

        // Smoothing window (time-based).
        window.addLast(new long[]{
                timestampMs,
                Double.doubleToRawLongBits(altitudeM),
                Double.doubleToRawLongBits(vAccReported ? verticalAccuracyM : 0.0)});
        while (!window.isEmpty()
                && timestampMs - window.peekFirst()[0] > SMOOTHING_WINDOW_MS) {
            window.removeFirst();
        }

        double smoothed = medianAltitude();
        double threshold = Math.max(ALT_NOISE_THRESHOLD_M, medianVerticalAccuracy());

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

    private double medianAltitude() {
        double[] v = new double[window.size()];
        int i = 0;
        for (long[] e : window) v[i++] = Double.longBitsToDouble(e[1]);
        return median(v);
    }

    /** Median of the REPORTED vertical accuracies in the window; 0 if none reported. */
    private double medianVerticalAccuracy() {
        double[] v = new double[window.size()];
        int n = 0;
        for (long[] e : window) {
            double a = Double.longBitsToDouble(e[2]);
            if (a > 0) v[n++] = a;
        }
        if (n == 0) return 0;
        return median(Arrays.copyOf(v, n));
    }

    private static double median(double[] v) {
        Arrays.sort(v);
        int n = v.length;
        return (n % 2 == 1) ? v[n / 2] : (v[n / 2 - 1] + v[n / 2]) / 2.0;
    }
}

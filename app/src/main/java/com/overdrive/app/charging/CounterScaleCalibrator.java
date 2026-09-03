package com.overdrive.app.charging;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.util.ScratchPaths;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides the UNIT FACTOR of a charged-energy counter by comparing it against an independent
 * full-scale energy register over a long baseline.
 *
 * <p>{@link com.overdrive.app.byd.ChargeSourceClassifier} answers whether a source is a COUNTER or a
 * RATE. It does not answer what UNIT the counter counts in. A field capture (log {@code AL37RNJ9},
 * BEV, 2026-08-09) recorded {@code getChargingCapacity} advancing 10.675 kWh while remaining pack
 * energy advanced 20.200 kWh over the same 1.900 h — the documented-kWh counter running at very
 * nearly half scale, corroborated by two rate accessors that both read ~10.05 kW against the
 * counter's 5.62 kW slope.
 *
 * <p><b>Why this cannot be a magnitude rule.</b> A half-scale counter is indistinguishable from a
 * full-scale one by inspection: both are plausible kWh values in a plausible range. The only thing
 * that separates them is DISAGREEMENT WITH AN INDEPENDENT MEASUREMENT OF THE SAME ENERGY, which is
 * what this class accumulates. That keeps it inside the project rule that a counter's unit is decided
 * by behaviour and never by how big the number looks.
 *
 * <p><b>Why the factor snaps to a known set.</b> A free-floating ratio would "calibrate" ordinary
 * measurement disagreement — charging loss, register quantisation, a BMS re-estimate — into a
 * permanent scale correction. Sub-units are properties of a register's width, so they land on exact
 * submultiples. The ratio is therefore snapped to the nearest of {@link #KNOWN_FACTORS} in log space
 * and REFUSED when it does not sit close to one. Refusing is safe: the caller withholds and falls
 * back to a flagged estimate, which is recoverable, whereas a wrong factor is priced.
 *
 * <p><b>Independence is the caller's contract.</b> The reference series must not derive from the
 * counter under test, or the comparison is vacuous and latches 1.0 on exactly the trims that need
 * correcting. Remaining pack energy qualifies: it is a separate register that the charging counter
 * does not feed.
 *
 * <p>A verdict is a property of the firmware, so it is persisted and keyed by firmware identity —
 * an OTA may change a register's width. Accumulation is per-session; the verdict outlives it.
 */
public final class CounterScaleCalibrator {

    private static final DaemonLogger logger = DaemonLogger.getInstance("CounterScaleCalibrator");

    /**
     * Unit factors this hardware family is known to use, as MULTIPLIERS onto the raw reading.
     *
     * <p>{@code 1.0} is the documented behaviour. {@code 2.0} is the observed half-scale register —
     * it reads half the energy actually delivered, so truth is twice the reading. {@code 0.01} is the
     * centi-kWh form of the hectowatt unit already recognised on the rate accessors, where the
     * register reads 100x high.
     *
     * <p>Note the direction: this is {@code reference / counter}, so a counter that under-reports has
     * a factor ABOVE one. The rate path's {@code UNIT_FACTOR} is expressed as a divisor instead, which
     * is why the same physical unit appears there as 100 and here as 0.01.
     */
    private static final double[] KNOWN_FACTORS = {0.01, 1.0, 2.0};

    /**
     * How far the observed ratio may sit from a candidate factor, as a multiplicative band.
     *
     * <p>1.25 is deliberately narrower than the gap between adjacent candidates (the geometric
     * midpoint of 1.0 and 2.0 is 1.414), so a value that could be either is rejected rather than
     * assigned to the closer one. The captured trim landed at 1.892 — 5% off an exact half and
     * comfortably inside the 2.0 band, while being nowhere near 1.0.
     */
    private static final double SNAP_TOLERANCE = 1.25;
    /**
     * A unity verdict is confidence, not a correction, so require closer agreement before persisting
     * it. Falling outside this band is harmless: factorFor() still defaults to 1.0, but the source
     * remains challengeable instead of storing a weak full-scale conclusion. The wider tolerance
     * above remains appropriate for identifying a discrete non-unity register width.
     */
    private static final double UNITY_SNAP_TOLERANCE = 1.15;

    /**
     * Baseline required before a factor may be decided, ms.
     *
     * <p>Matches the counter-classification discipline: a short window is dominated by quantisation
     * and by whichever register happened to tick first, and a charging ramp can make any two series
     * disagree transiently. 20 minutes of accumulated rise is long enough that neither can explain a
     * factor-of-two gap.
     */
    private static final long MIN_CALIBRATION_SPAN_MS = 20 * 60_000L;

    /** Energy each series must accumulate before the ratio means anything, kWh. */
    private static final double MIN_CALIBRATION_KWH = 1.0;

    /**
     * Energy each series must accumulate before a SUSPICION may be raised, kWh.
     *
     * <p>Not the same floor as {@link #MIN_CALIBRATION_KWH}, and it cannot be the raw noise floor
     * either: the two registers are quantised differently (~0.1 kWh against ~0.2 kWh in the field
     * capture), so whichever ticks first makes the early ratio wildly wrong on a perfectly healthy
     * trim — 0.09 against 0.20 reads as 2.2x. Since suspicion WITHHOLDS the rate, too low a floor
     * blanks the power card for the opening minutes of every charge on hardware with no fault at all.
     * At 0.5 kWh per series the quantum is under a fifth of the accumulated value, so a factor-of-two
     * gap can no longer be manufactured by step alignment.
     */
    private static final double MIN_SUSPICION_KWH = 0.5;

    /**
     * Longest gap that may be bridged inside one accumulation, ms.
     *
     * <p>Beyond this the two series are no longer describing the same interval — a daemon restart or
     * a parked-poll outage can advance one register unobserved — so the pair is re-anchored instead.
     */
    private static final long MAX_PAIR_GAP_MS = 15 * 60_000L;

    /** Rises below this are quantisation noise on either series, kWh. */
    private static final double MIN_STEP_KWH = 0.005;

    private static final String STATE_FILE = ScratchPaths.path("od_counter_scale.json");
    private static final String IDENTITY_KEY = "__identity";

    private static final ConcurrentHashMap<String, Calibration> calibrations =
            new ConcurrentHashMap<>();
    private static volatile boolean loaded = false;

    private CounterScaleCalibrator() {}

    private static final class Calibration {
        /** Decided factor, or NaN while undecided. */
        volatile double factor = Double.NaN;
        /** Accumulated rise on the counter under test, kWh. */
        double counterKwh = 0;
        /** Accumulated rise on the independent reference over the same intervals, kWh. */
        double referenceKwh = 0;
        /** Accumulated paired span, ms. */
        long spanMs = 0;
        double lastCounter = Double.NaN;
        double lastReference = Double.NaN;
        long lastAtMs = 0;
        /** Ratio that produced the decided factor, kept for diagnostics. */
        double decidedRatio = Double.NaN;
    }

    private static Calibration cal(String source) {
        return calibrations.computeIfAbsent(source, k -> new Calibration());
    }

    /**
     * Feed one PAIRED observation of the counter and an independent full-scale energy register.
     *
     * <p>Both readings must come from the same telemetry tick, and the caller must only call this
     * while the fused verdict is CHARGING and the vehicle is parked — otherwise regen or V2L would
     * enter the series. Either value being NaN, or either series stepping backwards, re-anchors the
     * pair rather than accumulating across the discontinuity.
     *
     * @param source        the counter's {@code SRC_*} key
     * @param counterKwh    raw reading of the counter under test
     * @param referenceKwh  reading of an independent register measuring the same delivered energy
     */
    public static synchronized void observePaired(String source, double counterKwh,
                                                  double referenceKwh, long atMs) {
        if (source == null || atMs <= 0) return;
        if (!Double.isFinite(counterKwh) || !Double.isFinite(referenceKwh)) return;
        loadIfNeeded();
        Calibration c = cal(source);
        // A non-unity correction is already a proven register-width fault. A stored 1.0 verdict is
        // different: it only says an earlier baseline looked full-scale. A later physical session
        // may provide stronger contradictory evidence, and field captures have shown exactly that
        // after an early comparison landed near the edge of the 1.0 snap band. Keep collecting
        // session-local evidence for 1.0 so it can be revised to a discrete non-unity factor.
        if (!Double.isNaN(c.factor) && c.factor != 1.0) return;

        if (Double.isNaN(c.lastCounter) || Double.isNaN(c.lastReference) || c.lastAtMs <= 0) {
            anchor(c, counterKwh, referenceKwh, atMs);
            return;
        }
        long dt = atMs - c.lastAtMs;
        double dCounter = counterKwh - c.lastCounter;
        double dReference = referenceKwh - c.lastReference;
        // A backwards step on either series is a counter reset, a BMS re-estimate, or a wrap. None of
        // them describe delivered energy, and pairing across one would corrupt the ratio in an
        // unbounded direction — so re-anchor and keep whatever was already accumulated.
        if (dt <= 0 || dt > MAX_PAIR_GAP_MS || dCounter < 0 || dReference < 0) {
            anchor(c, counterKwh, referenceKwh, atMs);
            return;
        }
        if (dCounter >= MIN_STEP_KWH || dReference >= MIN_STEP_KWH) {
            c.counterKwh += dCounter;
            c.referenceKwh += dReference;
            c.spanMs += dt;
        }
        c.lastCounter = counterKwh;
        c.lastReference = referenceKwh;
        c.lastAtMs = atMs;
        decideIfReady(source, c);
    }

    private static void anchor(Calibration c, double counterKwh, double referenceKwh, long atMs) {
        c.lastCounter = counterKwh;
        c.lastReference = referenceKwh;
        c.lastAtMs = atMs;
    }

    private static void decideIfReady(String source, Calibration c) {
        if (c.spanMs < MIN_CALIBRATION_SPAN_MS) return;
        if (c.counterKwh < MIN_CALIBRATION_KWH || c.referenceKwh < MIN_CALIBRATION_KWH) return;
        double ratio = c.referenceKwh / c.counterKwh;
        if (!Double.isFinite(ratio) || ratio <= 0) return;
        double snapped = snap(ratio);
        if (Double.isNaN(snapped)) {
            // Between two candidates: the evidence does not identify a register width. Keep
            // accumulating — a longer baseline may resolve it, and until then callers withhold.
            logger.warn(String.format(java.util.Locale.US,
                    "Counter '%s' scale ratio %.3f (%.3f kWh vs independent %.3f kWh over %d min)"
                    + " does not sit near a known register width — withholding a verdict",
                    source, ratio, c.counterKwh, c.referenceKwh, c.spanMs / 60_000L));
            return;
        }
        double previousFactor = c.factor;
        if (!Double.isNaN(previousFactor)) {
            if (Double.compare(previousFactor, snapped) == 0) return;
            // Only a unity verdict is revisable. Once a discrete correction has been proven, this
            // method stops accumulating above and cannot oscillate between factors.
            if (previousFactor != 1.0) return;
        }
        c.factor = snapped;
        c.decidedRatio = ratio;
        logger.info(String.format(java.util.Locale.US,
                "%s counter '%s' at unit factor %.0f — it advanced %.3f kWh while an"
                + " independent register advanced %.3f kWh over %d min (ratio %.3f)",
                Double.isNaN(previousFactor) ? "Calibrated" : "Recalibrated",
                source, snapped, c.counterKwh, c.referenceKwh, c.spanMs / 60_000L, ratio));
        persist();
    }

    /** Nearest known factor in log space, or NaN when the ratio is not close enough to one. */
    private static double snap(double ratio) {
        double best = Double.NaN;
        double bestDeviation = Double.MAX_VALUE;
        for (double candidate : KNOWN_FACTORS) {
            double deviation = Math.abs(Math.log(ratio / candidate));
            if (deviation < bestDeviation) {
                bestDeviation = deviation;
                best = candidate;
            }
        }
        double tolerance = Double.compare(best, 1.0) == 0
                ? UNITY_SNAP_TOLERANCE : SNAP_TOLERANCE;
        return bestDeviation <= Math.log(tolerance) ? best : Double.NaN;
    }

    /**
     * The multiplier that converts this counter's raw readings into true kWh.
     *
     * <p>Returns {@code 1.0} when no fault has been proven, so an uncalibrated trim behaves exactly
     * as before. Callers that must not publish an unproven figure should consult
     * {@link #isScaleSuspect} rather than reading this as confirmation.
     */
    public static double factorFor(String source) {
        if (source == null) return 1.0;
        loadIfNeeded();
        Calibration c = calibrations.get(source);
        if (c == null) return 1.0;
        double f = c.factor;
        return Double.isNaN(f) ? 1.0 : f;
    }

    /** Whether this counter's unit has been decided by evidence. */
    public static boolean isCalibrated(String source) {
        if (source == null) return false;
        loadIfNeeded();
        Calibration c = calibrations.get(source);
        return c != null && !Double.isNaN(c.factor);
    }

    /**
     * Whether a sub-unit is INDICATED but not yet proven.
     *
     * <p>True once the accumulated evidence points away from full scale while still short of the
     * baseline needed to decide. Consumers use this to withhold a figure rather than price a value
     * that is probably out by a whole factor: withholding is recoverable, a factor error is not.
     */
    public static boolean isScaleSuspect(String source) {
        if (source == null) return false;
        loadIfNeeded();
        Calibration c = calibrations.get(source);
        if (c == null) return false;
        synchronized (CounterScaleCalibrator.class) {
            // A proven non-unity factor is already corrected by factorFor(). A unity verdict remains
            // challengeable by the current session, because persisted evidence from an earlier
            // session can be weaker than a later clean 1:2 comparison.
            if (!Double.isNaN(c.factor) && c.factor != 1.0) return false;
            if (c.counterKwh < MIN_SUSPICION_KWH || c.referenceKwh < MIN_SUSPICION_KWH) return false;
            double ratio = c.referenceKwh / c.counterKwh;
            if (!Double.isNaN(c.factor)) {
                // Challenging an existing full-scale verdict needs evidence that identifies a
                // specific replacement factor, not merely ordinary measurement disagreement.
                return Double.compare(snap(ratio), 2.0) == 0;
            }
            // The geometric midpoint between full scale and the first sub-unit candidate. Below it the
            // gap is ordinary disagreement (charging loss, quantisation); above it, no explanation
            // other than a register width remains.
            return Double.isFinite(ratio) && ratio >= Math.sqrt(2.0);
        }
    }

    /**
     * Drop per-session accumulation, keeping any decided verdict.
     *
     * <p>The anchors and partial sums describe one physical charge. Carrying them into the next
     * session would pair a reading from this charge against one from the next, which is the
     * outliving-state trap this subsystem has hit before.
     */
    public static synchronized void onSessionEnded() {
        for (Calibration c : calibrations.values()) {
            c.lastCounter = Double.NaN;
            c.lastReference = Double.NaN;
            c.lastAtMs = 0;
            // Accumulated deltas describe exactly one physical charge, regardless of whether they
            // were sufficient to decide a factor. Carrying an undecided partial baseline into the
            // next session pairs unrelated operating conditions and can eventually create a false
            // firmware verdict. Keep only the decided factor itself.
            c.counterKwh = 0;
            c.referenceKwh = 0;
            c.spanMs = 0;
        }
    }

    /** Drop all state, including decided verdicts. Tests only. */
    static synchronized void resetForTests() {
        calibrations.clear();
        loaded = true;   // do not read the device state file under test
    }

    /** Diagnostic snapshot for the telemetry surface. */
    public static synchronized JSONObject describe() {
        loadIfNeeded();
        JSONObject out = new JSONObject();
        try {
            for (java.util.Map.Entry<String, Calibration> e : calibrations.entrySet()) {
                Calibration c = e.getValue();
                JSONObject j = new JSONObject();
                if (!Double.isNaN(c.factor)) j.put("factor", c.factor);
                if (!Double.isNaN(c.decidedRatio)) j.put("ratio", c.decidedRatio);
                j.put("counterKwh", c.counterKwh);
                j.put("referenceKwh", c.referenceKwh);
                j.put("spanMs", c.spanMs);
                out.put(e.getKey(), j);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static String currentIdentity() {
        try {
            return android.os.Build.FINGERPRINT != null ? android.os.Build.FINGERPRINT : "unknown";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static synchronized void loadIfNeeded() {
        if (loaded) return;
        loaded = true;
        try {
            File f = new File(STATE_FILE);
            if (!f.exists()) return;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            }
            JSONObject root = new JSONObject(bos.toString("UTF-8"));
            String identity = currentIdentity();
            if (!identity.equals(root.optString(IDENTITY_KEY, ""))) {
                // A register's width is not guaranteed across an OTA or a different head unit.
                logger.warn("Discarding stored counter scale — firmware identity changed."
                        + " Counters will be re-calibrated.");
                return;
            }
            for (java.util.Iterator<String> it = root.keys(); it.hasNext(); ) {
                String key = it.next();
                if (IDENTITY_KEY.equals(key)) continue;
                JSONObject j = root.optJSONObject(key);
                if (j == null || !j.has("factor")) continue;
                double factor = j.optDouble("factor", Double.NaN);
                // Only a value from the known set may be restored: a corrupted or hand-edited file
                // must not introduce an arbitrary multiplier into a priced figure.
                if (!isKnownFactor(factor)) continue;
                Calibration c = cal(key);
                c.factor = factor;
                c.decidedRatio = j.optDouble("ratio", Double.NaN);
            }
            logger.info("Loaded counter scale verdicts: " + root);
        } catch (Exception e) {
            logger.debug("Could not load counter scale: " + e.getMessage());
        }
    }

    private static boolean isKnownFactor(double factor) {
        if (!Double.isFinite(factor)) return false;
        for (double candidate : KNOWN_FACTORS) {
            if (Double.compare(candidate, factor) == 0) return true;
        }
        return false;
    }

    private static void persist() {
        try {
            JSONObject root = describe();
            try { root.put(IDENTITY_KEY, currentIdentity()); } catch (Exception ignored) {}
            try (FileOutputStream out = new FileOutputStream(STATE_FILE)) {
                out.write(root.toString().getBytes("UTF-8"));
            }
        } catch (Exception e) {
            logger.debug("Could not persist counter scale: " + e.getMessage());
        }
    }
}

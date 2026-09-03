package com.overdrive.app.byd;

import android.content.Context;
import com.overdrive.app.util.ScratchPaths;

import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides, per vehicle, what each ambiguous charging-power getter actually MEANS.
 *
 * <p>Some BYD charging getters are not consistent across firmware families. The same accessor
 * answers an instantaneous rate in kW on some trims and a cumulative charged-energy counter in
 * kWh on others, with no unit flag anywhere in the API. Guessing from the magnitude cannot work:
 * a counter sitting at 119.0 and a DC session at 119 kW are the same number, and every
 * magnitude rule that resolves one breaks the other. That guess is what made charging history
 * wrong on some models and right on others.
 *
 * <p>The dedicated {@code BYDAutoChargingDevice.getChargingPower()} signal is not ambiguous: the
 * framework defines it as a signed charging rate in kW over [-500, 500], and its listener is driven
 * by the charging battery-voltage/current inputs. {@link #SRC_DEVICE} is therefore always
 * {@link Kind#RATE}. The documented charged-capacity field is likewise always
 * {@link Kind#COUNTER}. Only the remaining firmware-dependent accessors need behavioral learning.
 *
 * <p>The two ambiguous kinds are separable by BEHAVIOUR:
 * <ul>
 *   <li>a COUNTER only ever rises while energy is flowing, and never falls (except a reset to
 *       ~zero when a new session begins);</li>
 *   <li>a RATE moves both ways — it ramps up, tapers, and dips.</li>
 * </ul>
 * So we watch each source across a charge and let it declare itself. The verdict is a property
 * of the firmware, so it is cached per source and persisted; it is re-verified but never
 * re-guessed.
 *
 * <p>Three rules keep this honest:
 * <ul>
 *   <li><b>Only observe while plugged in and delivering.</b> A value read with the gun out is
 *       not evidence of anything — a stuck counter produces no transitions, and a stale rate
 *       looks like a frozen counter. Callers gate on that before feeding samples.</li>
 *   <li><b>UNKNOWN excludes the source.</b> Before a verdict, a source is unusable, exactly as
 *       if the getter were dead. It is never "used anyway" on a guess. This is what makes the
 *       design fail-closed: an unclassified source cannot reach a displayed number or a price.</li>
 *   <li><b>A reset is not a fall.</b> A drop to near zero is a new session, which is a COUNTER
 *       behaviour, not evidence against it.</li>
 * </ul>
 */
public final class ChargeSourceClassifier {

    private static final DaemonLogger logger = DaemonLogger.getInstance("ChargeSourceClassifier");

    /** Stable source keys. Persisted, so do not rename. */
    public static final String SRC_EXTERNAL = "externalChargingPower";
    public static final String SRC_DEVICE   = "chargingPower";
    public static final String SRC_CLUSTER  = "clusterChargePower";
    public static final String SRC_CAPACITY = "chargingCapacity";

    public enum Kind {
        /** Not enough evidence yet — the source must not be used for display, energy or cost. */
        UNKNOWN,
        /** Cumulative charged-energy counter, kWh. Session energy = end - start. */
        COUNTER,
        /** Instantaneous charge rate, kW. */
        RATE
    }

    /**
     * Transitions (a change in value between two consecutive charging observations) required
     * before a verdict is issued. A rate dips within a handful of samples on any real charge;
     * a counter needs enough rises that a single noisy read cannot carry the decision.
     */
    private static final int MIN_TRANSITIONS = 8;
    /**
     * Monotonic rises required to call something a counter.
     *
     * <p>Rises ALONE are not sufficient evidence, no matter how many: a charge ramping through its
     * constant-current phase produces a long monotonic run on a genuine RATE source too. At a 12 s
     * sample a DC ramp reaches 8 consecutive rises in ~96 s, so a rise-count test on its own
     * permanently mislabels every rate source on a fast charge. The additional
     * {@link #COUNTER_MIN_SPAN_MS} requirement is what separates them: a cumulative counter rises
     * for the WHOLE session, while a rate stops rising within minutes of reaching its plateau.
     */
    private static final int MIN_RISES_FOR_COUNTER = 3;
    /** Falls required to call something a rate. One dip could be a HAL glitch; two is behaviour. */
    private static final int MIN_FALLS_FOR_RATE = 2;
    /**
     * A source must rise monotonically for at least this long before it can be called a COUNTER.
     *
     * <p>No real charge holds a strictly rising RATE for 20 minutes: AC plateaus within a minute,
     * and DC reaches its taper knee and starts falling well before this. A cumulative counter, by
     * contrast, rises for as long as energy flows. Sized generously because the cost of a wrong
     * COUNTER verdict is permanent and severe (a kW series' slope is meaningless), while the cost of
     * deciding late is only that the source stays unusable a little longer.
     */
    private static final long COUNTER_MIN_SPAN_MS = 20 * 60_000L;
    /**
     * How long an in-progress rise run may go without a new rise before it stops counting.
     *
     * <p>A cumulative counter keeps ticking while energy flows, so a genuine counter always produces
     * another rise inside this window even on the slowest charge (at 0.3 kW a 1 Wh counter advances
     * every ~12 s). A rate that has plateaued produces none, which is precisely the distinction.
     * Sized well above the 90 s parked poll so a slow cadence is never mistaken for a plateau.
     */
    private static final long RISE_RUN_IDLE_TIMEOUT_MS = 6 * 60_000L;
    /**
     * Fraction of observations that must be RISES before a source can be called a COUNTER.
     *
     * <p>Duration alone is not enough: a RATE accessor whose reported power creeps upward through the
     * constant-current phase also rises for 20+ minutes without ever falling. Magnitude cannot
     * separate them either — a 0.3 kW counter advances 0.1 kWh over 20 minutes, which is the same
     * order as a plateau's creep, so any kWh threshold that admits the slow counter also admits the
     * creep.
     *
     * <p>What DOES separate them is CONSISTENCY. A cumulative counter advances on essentially every
     * observation, because energy flows continuously: at 0.3 kW a 1 Wh counter ticks every ~12 s, so
     * at any sampling cadence nearly every sample differs from the last. A plateauing rate reports the
     * SAME value most of the time and creeps occasionally — so its rises are sparse relative to how
     * often it is observed. Requiring a high rise density admits the slow counter and rejects the
     * creep, which no magnitude test can do.
     */
    private static final double COUNTER_MIN_RISE_DENSITY = 0.80;
    /**
     * A drop of at least this fraction of the running value, landing near zero, is read as a
     * session reset rather than a fall.
     */
    private static final double RESET_DROP_FRACTION = 0.75;
    /**
     * Absolute ceiling on the landing value for a fall to count as a session RESET.
     *
     * <p>A reset restarts the counter, so it lands at zero or within a quantum or two of it. This is
     * what separates a reset from a steep taper, which the relative test alone cannot do (a 40 -> 8
     * taper is an 80% drop landing at 20%, satisfying every proportional condition). Sized to admit
     * counter quantisation and a small residual while excluding any value a real charge would still be
     * delivering at.
     */
    private static final double RESET_ABSOLUTE_FLOOR = 0.25;
    /**
     * How long a value must hold PERFECTLY STILL, while charging, to be ruled a RATE.
     *
     * <p>Mirror of {@link #COUNTER_MIN_SPAN_MS}: that asks how long a value must rise to be a counter,
     * this asks how long it must not. A counter at even 0.3 kW advances by a 1 Wh quantum every ~12 s,
     * so it cannot stay bit-identical across this span while energy is flowing. A constant-current AC
     * charger does exactly that for the whole session. Deliberately longer than the 90 s parked poll so
     * a couple of missed cycles cannot fabricate a verdict.
     */
    private static final long STEADY_RATE_MIN_SPAN_MS = 10 * 60_000L;
    /** Consecutive identical observations required alongside the span, so cadence alone cannot decide. */
    private static final int STEADY_RATE_MIN_REPEATS = 5;
    /**
     * A plateau may be classified only from independent movement observed close to the current raw
     * sample. This spans the 90-second parked poll with scheduling margin without letting an old SoC
     * rise from earlier in the charge certify a level that has since frozen.
     */
    private static final long STEADY_RATE_CORROBORATION_MAX_AGE_MS = 2 * 60_000L;

    private static volatile File stateFile;

    private static final ConcurrentHashMap<String, Observation> observations = new ConcurrentHashMap<>();
    private static volatile boolean loaded = false;

    private ChargeSourceClassifier() {}

    /** Configure firmware-scoped persistence in storage writable by the current process UID. */
    public static synchronized void initializePersistence(Context context) {
        if (context == null) return;
        File next = android.os.Process.myUid() == 2000
                ? new File(ScratchPaths.path("od_charge_source_kinds.json"))
                : new File(context.getFilesDir(), "od_charge_source_kinds.json");
        if (next.equals(stateFile)) return;
        stateFile = next;
        loaded = false;
        loadIfNeeded();
    }

    private static final class Observation {
        volatile Kind kind = Kind.UNKNOWN;
        double lastValue = Double.NaN;
        int rises = 0;
        /** Non-reset falls observed in the current physical charging session only. */
        int sessionFalls = 0;
        int falls = 0;
        int transitions = 0;
        int resets = 0;
        /** When the current unbroken run of rises began; 0 when not currently rising. */
        long riseRunStartMs = 0;
        /** Longest unbroken monotonic-rise run observed, ms. */
        long longestRiseRunMs = 0;
        /** When the most recent rise was observed; 0 when no run is in progress. */
        long lastRiseAtMs = 0;
        /** Every observation fed while charging, including repeats — the rise-density denominator. */
        int observations = 0;
        /** Observations that repeated the previous value exactly, while charging. */
        int repeats = 0;
        /** Rises in the current physical session. Reset by {@link #onSessionEnded()}. */
        int sessionRises = 0;
        /** Consecutive repeats in the current plateau run, not lifetime repeats. */
        int repeatRunObservations = 0;
        /** When the current unbroken run of repeats began; 0 when not currently repeating. */
        long repeatRunStartMs = 0;
        /** When the most recent repeat was observed; 0 when no run is in progress. */
        long lastRepeatAtMs = 0;
    }

    private static Observation obs(String source) {
        return observations.computeIfAbsent(source, k -> new Observation());
    }

    /**
     * Fraction of observations that were rises. A cumulative counter advances on nearly every
     * observation; a plateauing rate repeats its value and creeps only occasionally.
     */
    private static double riseDensity(Observation o) {
        if (o.observations <= 0) return 0;
        return (double) o.rises / (double) o.observations;
    }

    /**
     * Longest unbroken rising run, including one currently in progress.
     *
     * <p>An in-progress run is only counted while it is still ACTIVELY rising. Without that check
     * the value measured time-since-last-fall rather than a rise run, so a source that rises a few
     * times and then holds a steady value — a flat AC charge on a rate accessor is exactly this —
     * accrued the whole plateau as if it were one continuous rise and eventually cleared the
     * anti-ramp threshold. It would then be classified COUNTER, and the slope of a flat kW series is
     * zero, so the source would publish nothing for the rest of the vehicle's life.
     */
    private static long sustainedRiseMs(Observation o, long nowMs) {
        long current = 0;
        if (o.riseRunStartMs > 0 && o.lastRiseAtMs > 0
                && (nowMs - o.lastRiseAtMs) <= RISE_RUN_IDLE_TIMEOUT_MS) {
            current = o.lastRiseAtMs - o.riseRunStartMs;
        }
        return Math.max(o.longestRiseRunMs, current);
    }

    /**
     * Feed one observation. The caller MUST only call this when the gun asserts a
     * charging-direction connection and the session is live — see the class contract.
     *
     * @param source one of the {@code SRC_*} keys
     * @param value  the RAW getter value, unscaled
     */
    public static synchronized void observeWhileCharging(String source, double value) {
        observeWhileCharging(source, value, System.currentTimeMillis());
    }

    /** Testable variant taking an explicit observation time. */
    static synchronized void observeWhileCharging(String source, double value, long nowMs) {
        if (source == null || Double.isNaN(value) || Double.isInfinite(value)) return;
        loadIfNeeded();
        Observation o = obs(source);
        if (SRC_DEVICE.equals(source)) {
            // Framework contract: signed instantaneous kW, not a firmware-dependent counter.
            o.kind = Kind.RATE;
            o.lastValue = value;
            o.observations++;
            return;
        }
        if (SRC_CAPACITY.equals(source)) {
            // This SDK field is documented as a bounded per-session kWh total. It is not an
            // ambiguous firmware-dependent power accessor, so behavioral classification is both
            // unnecessary and dangerous: a newly reset counter can repeat zero long enough to look
            // like a steady RATE, after which its kWh total would be published directly as kW.
            o.kind = Kind.COUNTER;
            o.lastValue = value;
            o.observations++;
            return;
        }

        if (Double.isNaN(o.lastValue)) {
            o.lastValue = value;
            o.observations++;
            return;
        }
        o.observations++;
        if (value == o.lastValue) {
            // A REPEAT IS EVIDENCE, not silence. Observations are only fed while the gun asserts a
            // charging connection and the session is live, so energy IS flowing — and a cumulative
            // counter cannot hold still while it flows. Repeats therefore argue for a RATE, and they
            // are the ONLY thing a constant AC charger produces: a rock-steady 3.3 or 7 kW source
            // generates no transitions at all, so it never reached MIN_TRANSITIONS, stayed UNKNOWN for
            // the whole charge, and was refused by the resolver — leaving the session on a flagged
            // estimate that the sampler deliberately excludes from energy and cost. That is the
            // commonest AC case on some trims, so "no data" was the normal outcome rather than an edge.
            o.repeats++;
            if (o.repeatRunStartMs == 0) {
                o.repeatRunStartMs = nowMs;
                o.repeatRunObservations = 1;
            } else {
                o.repeatRunObservations++;
            }
            o.lastRepeatAtMs = nowMs;
            return;
        }
        // A transition breaks any repeat run in progress.
        o.repeatRunStartMs = 0;
        o.lastRepeatAtMs = 0;
        o.repeatRunObservations = 0;

        double delta = value - o.lastValue;
        if (delta > 0) {
            o.rises++;
            o.sessionRises++;
            o.transitions++;
            // Track how long this unbroken rising run has lasted. A rate ramps then plateaus or
            // tapers; only a cumulative counter rises for the whole session.
            if (o.riseRunStartMs == 0) {
                o.riseRunStartMs = nowMs;
            } else {
                long run = nowMs - o.riseRunStartMs;
                if (run > o.longestRiseRunMs) o.longestRiseRunMs = run;
            }
            o.lastRiseAtMs = nowMs;
        } else {
            o.riseRunStartMs = 0;   // the run is broken (fall or reset)
            o.lastRiseAtMs = 0;
            // A large drop to near zero is a session reset — the defining behaviour of a
            // per-session counter, so it must not be counted as evidence of a rate.
            //
            // "Near zero" must be ABSOLUTE as well as relative. A purely relative test calls any
            // sharp proportional drop a reset, and a normal DC taper is exactly that: 40 kW -> 8 kW
            // is a 80% fall landing at 20% of the previous value, so it satisfied both relative
            // conditions and was recorded as a reset. That suppressed the fall evidence which is the
            // ONLY thing that identifies a RATE — so a tapering rate source could accumulate rises,
            // never register a fall, and eventually persist an incorrect COUNTER verdict. A genuine
            // per-session counter reset lands at or very near zero in absolute terms, not merely at a
            // small fraction of a large number.
            boolean looksLikeReset = o.lastValue > 0
                    && Math.abs(delta) >= o.lastValue * RESET_DROP_FRACTION
                    && value <= o.lastValue * (1.0 - RESET_DROP_FRACTION)
                    && value <= RESET_ABSOLUTE_FLOOR;
            if (looksLikeReset) {
                o.resets++;
            } else {
                o.sessionFalls++;
                o.falls++;
                o.transitions++;
            }
        }
        o.lastValue = value;

        // DEMOTION. A cumulative counter cannot fall except at a reset, and resets are excluded
        // from the session fall count above. Require two falls in ONE physical charge: one isolated
        // drop can be HAL noise, and combining one glitch from each of two unrelated sessions makes
        // that acknowledged noise look like rate behavior. Without demotion the first decision is
        // frozen for the life of the install, so retain it with session-scoped contrary evidence.
        if (o.kind == Kind.COUNTER && o.sessionFalls >= MIN_FALLS_FOR_RATE) {
            o.kind = Kind.RATE;
            logger.warn("Re-classified " + source + " COUNTER -> RATE after observing "
                    + o.sessionFalls + " non-reset falls in one charging session"
                    + " (lifetime falls=" + o.falls + ") — the earlier verdict was wrong");
            persist();
            return;
        }

        if (o.kind == Kind.UNKNOWN && o.transitions >= MIN_TRANSITIONS) {
            Kind verdict = Kind.UNKNOWN;
            // RATE is decided on same-session falls: a cumulative counter cannot fall except at a
            // reset, while isolated glitches from separate sessions must never combine into a verdict.
            if (o.sessionFalls >= MIN_FALLS_FOR_RATE) {
                verdict = Kind.RATE;
            } else if (o.sessionFalls == 0 && o.rises >= MIN_RISES_FOR_COUNTER
                    && sustainedRiseMs(o, nowMs) >= COUNTER_MIN_SPAN_MS
                    && riseDensity(o) >= COUNTER_MIN_RISE_DENSITY) {
                // COUNTER needs a rise run no rate could produce. Checked SECOND so that any
                // observed fall wins outright — falls are the stronger signal.
                verdict = Kind.COUNTER;
            }
            if (verdict != Kind.UNKNOWN) {
                o.kind = verdict;
                logger.info("Classified " + source + " as " + verdict
                        + " (rises=" + o.rises + " falls=" + o.falls
                        + " resets=" + o.resets + " transitions=" + o.transitions
                        + " longestRiseRunMin="
                        + (sustainedRiseMs(o, nowMs) / 60000) + ")");
                persist();
            }
        }
    }

    private static boolean isSteadyRateCandidateLocked(
            String source, Observation o, double value, long nowMs) {
        if (SRC_CAPACITY.equals(source) || SRC_DEVICE.equals(source)) return false;
        if (o.kind != Kind.UNKNOWN
                || Double.doubleToLongBits(o.lastValue) != Double.doubleToLongBits(value)) {
            return false;
        }
        if (o.repeatRunObservations < STEADY_RATE_MIN_REPEATS) return false;
        if (o.repeatRunStartMs <= 0 || o.lastRepeatAtMs <= o.repeatRunStartMs) return false;
        if ((o.lastRepeatAtMs - o.repeatRunStartMs) < STEADY_RATE_MIN_SPAN_MS) return false;
        return nowMs >= o.lastRepeatAtMs
                && nowMs - o.lastRepeatAtMs <= STEADY_RATE_CORROBORATION_MAX_AGE_MS;
    }

    /**
     * Whether the current physical session has established a long, uninterrupted plateau.
     *
     * <p>This is intentionally only a candidate query. A steady level is ambiguous with a cumulative
     * counter frozen at its final value, so it cannot issue a verdict until
     * {@link #classifySteadyRateWithCorroboration} receives independent post-plateau movement.
     */
    public static synchronized boolean isSteadyRateCandidate(
            String source, double value, long nowMs) {
        if (source == null || !Double.isFinite(value)) return false;
        loadIfNeeded();
        Observation o = observations.get(source);
        return o != null && isSteadyRateCandidateLocked(source, o, value, nowMs);
    }

    /**
     * Rule a current-session plateau a RATE after fresh independent movement proves delivery continued.
     *
     * <p>A normal AC rate may rise once during startup and then stay bit-identical for the rest of the
     * charge. Lifetime {@code rises > 0} therefore cannot disqualify the plateau. Conversely, a
     * FINISHED cumulative counter also stays bit-identical, so the corroboration must be newer than the
     * current plateau and close to this observation. The detector supplies a distinct post-plateau SoC
     * rise and fences this call by its physical-session epoch.
     */
    public static synchronized boolean classifySteadyRateWithCorroboration(
            String source, double value, long observedAtMs, long corroborationAtMs) {
        if (source == null || !Double.isFinite(value)
                || corroborationAtMs <= 0 || observedAtMs < corroborationAtMs) {
            return false;
        }
        loadIfNeeded();
        Observation o = observations.get(source);
        if (o == null || !isSteadyRateCandidateLocked(source, o, value, observedAtMs)) {
            return false;
        }
        if (corroborationAtMs < o.repeatRunStartMs
                || observedAtMs - corroborationAtMs
                        > STEADY_RATE_CORROBORATION_MAX_AGE_MS) {
            return false;
        }
        o.kind = Kind.RATE;
        logger.info("Classified " + source + " as RATE on a steady run - held "
                + String.format(java.util.Locale.US, "%.3f", o.lastValue) + " unchanged for "
                + ((o.lastRepeatAtMs - o.repeatRunStartMs) / 60000) + " min across "
                + o.repeatRunObservations + " current-run repeats after " + o.sessionRises
                + " current-session rises; fresh post-plateau SoC movement proves energy is flowing");
        persist();
        return true;
    }

    /** @return the verdict for a source; {@link Kind#UNKNOWN} until enough evidence exists. */
    public static Kind kindOf(String source) {
        if (SRC_DEVICE.equals(source)) return Kind.RATE;
        if (SRC_CAPACITY.equals(source)) return Kind.COUNTER;
        loadIfNeeded();
        Observation o = observations.get(source);
        return o != null ? o.kind : Kind.UNKNOWN;
    }

    public static boolean isCounter(String source) { return kindOf(source) == Kind.COUNTER; }
    public static boolean isRate(String source)    { return kindOf(source) == Kind.RATE; }

    /**
     * Clear the running value for a source without discarding its verdict. Called when a session
     * ends so the next charge does not diff across the gap between two sessions.
     */
    public static synchronized void onSessionEnded() {
        for (Observation o : observations.values()) {
            o.lastValue = Double.NaN;
            // A single non-reset drop can be a HAL glitch. It only becomes RATE evidence when another
            // fall occurs in the same physical charge; never combine glitches from unrelated sessions.
            o.sessionFalls = 0;
            o.sessionRises = 0;
            // Close any rise run in progress. Leaving riseRunStartMs set meant the NEXT session's
            // first rise measured its run from the PREVIOUS session's timestamp, crediting the whole
            // parked gap to longestRiseRunMs — which trivially cleared the 20-minute anti-ramp guard
            // and let a rate source unplugged mid-ramp be latched as a COUNTER.
            // Close any repeat run too — a run measured across the gap between two sessions is not a
            // steady charge, and leaving it open would let the parked interval satisfy the span test.
            o.repeatRunStartMs = 0;
            o.lastRepeatAtMs = 0;
            o.repeatRunObservations = 0;
            if (o.riseRunStartMs > 0) {
                // Measure the run to the LAST OBSERVED RISE, not to now. `now` includes the entire
                // plateau between the final rise and the end of the session — on a charge that
                // finished and sat plugged in, that is minutes or hours of not rising, credited as
                // though the counter had been climbing throughout. That is the same defect
                // sustainedRiseMs guards against for an in-progress run, and it matters here for the
                // same reason: longestRiseRunMs is the anti-ramp evidence that decides COUNTER, so
                // inflating it can latch a rate source as a counter permanently.
                long end = o.lastRiseAtMs > 0 ? o.lastRiseAtMs : o.riseRunStartMs;
                long run = end - o.riseRunStartMs;
                if (run > o.longestRiseRunMs) o.longestRiseRunMs = run;
                o.riseRunStartMs = 0;
                o.lastRiseAtMs = 0;
            }
        }
    }

    /** Diagnostic snapshot for the telemetry surface. */
    public static synchronized JSONObject describe() {
        loadIfNeeded();
        JSONObject out = new JSONObject();
        try {
            for (java.util.Map.Entry<String, Observation> e : observations.entrySet()) {
                Observation o = e.getValue();
                JSONObject j = new JSONObject();
                j.put("kind", o.kind.name());
                j.put("rises", o.rises);
                j.put("falls", o.falls);
                j.put("resets", o.resets);
                j.put("transitions", o.transitions);
                // Carried across restarts so evidence accumulated over several charges is not
                // thrown away — a slow AC charge may not reach the rise-span threshold in one go.
                j.put("longestRiseRunMs", o.longestRiseRunMs);
                j.put("observations", o.observations);
                j.put("repeats", o.repeats);
                // lastRiseAtMs / riseRunStartMs are NOT persisted: they are wall-clock instants, and a
                // restart means any run in progress is over. Only the accumulated longest run carries.
                out.put(e.getKey(), j);
            }
        } catch (Exception ignored) {}
        return out;
    }

    // ==================== PERSISTENCE ====================
    // A verdict is a property of the firmware, so it outlives the process. Only the verdict and
    // its evidence counts are stored; the running value deliberately is not (a value from a
    // previous session must never seed a delta).

    /**
     * Identity the stored verdicts belong to.
     *
     * <p>A verdict describes how a particular firmware's accessor BEHAVES, so it is only valid for
     * that firmware. An OTA can change the unit an accessor reports, and the state file lives at a
     * fixed path that a different head unit would also read — so a verdict keyed on the source name
     * alone can outlive the thing it describes and silently reinterpret a rate as a counter (or the
     * reverse), which is a 10-100x error in the published figure. Stamping the file lets a changed
     * identity discard the verdicts instead of inheriting them; re-learning costs one charge, whereas
     * a wrong inherited verdict is permanent.
     */
    private static String currentIdentity() {
        try {
            return android.os.Build.FINGERPRINT != null ? android.os.Build.FINGERPRINT : "unknown";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /** JSON key holding {@link #currentIdentity}; not a source name. */
    private static final String IDENTITY_KEY = "__identity";

    private static synchronized void loadIfNeeded() {
        if (loaded) return;
        loaded = true;
        try {
            File f = stateFile;
            if (f == null) return;
            if (!f.exists()) return;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            }
            JSONObject root = new JSONObject(bos.toString("UTF-8"));
            String storedIdentity = root.optString(IDENTITY_KEY, "");
            String identity = currentIdentity();
            if (!identity.equals(storedIdentity)) {
                // Different firmware/head unit (or a file written before identities were stamped).
                // Discard rather than inherit: an accessor's unit is not guaranteed across an OTA.
                logger.warn("Discarding stored charge-source kinds — firmware identity changed"
                        + " (stored='" + storedIdentity + "'). Sources will be re-learned.");
                return;
            }
            for (java.util.Iterator<String> it = root.keys(); it.hasNext(); ) {
                String key = it.next();
                if (IDENTITY_KEY.equals(key)) continue;   // metadata, not a source
                JSONObject j = root.optJSONObject(key);
                if (j == null) continue;
                Observation o = obs(key);
                try {
                    o.kind = Kind.valueOf(j.optString("kind", "UNKNOWN"));
                } catch (IllegalArgumentException e) {
                    o.kind = Kind.UNKNOWN;
                }
                if (SRC_DEVICE.equals(key)) o.kind = Kind.RATE;
                if (SRC_CAPACITY.equals(key)) o.kind = Kind.COUNTER;
                o.rises = j.optInt("rises", 0);
                o.falls = j.optInt("falls", 0);
                o.resets = j.optInt("resets", 0);
                o.transitions = j.optInt("transitions", 0);
                o.longestRiseRunMs = j.optLong("longestRiseRunMs", 0L);
                o.observations = j.optInt("observations", 0);
                o.repeats = j.optInt("repeats", 0);
            }
            logger.info("Loaded charge-source kinds: " + root);
        } catch (Exception e) {
            logger.debug("Could not load charge-source kinds: " + e.getMessage());
        }
    }

    private static void persist() {
        try {
            File f = stateFile;
            if (f == null) return;
            JSONObject root = describe();
            // Stamp the firmware identity these verdicts describe, so a later OTA or a different head
            // unit discards them instead of reinterpreting this vehicle's accessors as its own.
            try { root.put(IDENTITY_KEY, currentIdentity()); } catch (Exception ignored) {}
            File parent = f.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                logger.debug("Could not create charge-source state directory: " + parent);
                return;
            }
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(root.toString().getBytes("UTF-8"));
            }
        } catch (Exception e) {
            logger.debug("Could not persist charge-source kinds: " + e.getMessage());
        }
    }
}

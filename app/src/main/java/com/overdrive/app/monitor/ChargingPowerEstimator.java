package com.overdrive.app.monitor;

import com.overdrive.app.logging.DaemonLogger;

import java.util.ArrayDeque;

/**
 * Ring-buffer charging-power estimator — a FALLBACK power source for BYD models
 * that report no direct/external charging power (the charger-reported getters
 * read 0/UNAVAILABLE and the {@code onExternalChargingPowerChanged} callback is
 * silent under uid-2000, so {@link VehicleDataMonitor#getChargingState()} would
 * otherwise fall through to a nominal placeholder and the UI shows "--").
 *
 * <p>Mechanism: differentiate a monotonic charge-energy counter over a sliding
 * window — {@code ΔkWh × 3_600_000 / Δms = kW}. We feed it two counters: remaining
 * pack energy ({@code remainKwh}) and the cumulative session charge-energy counter
 * ({@code chargingCapacityKwh}); both rise only while charging, so their positive
 * time-derivative is charging power. {@code remainKwh} remains preferred on DC and
 * unknown connector states because it is verified full-scale on the Seal, whereas
 * {@code getChargingCapacity()} rises at ~half the true rate there. A confirmed AC
 * connector is cross-checked, however: the Atto 3 exposes a cycling/jumping value as
 * {@code remainKwh} (1.5→6.6 kWh in one poll while the session counter rose smoothly),
 * which otherwise generated a false 155.5 kW peak and poisoned session energy/cost.
 * When both AC counters are present, a gross disagreement latches the smooth session
 * counter for the rest of that charge; an impossible AC slope is never published.
 * Power is the slope across the WHOLE window (total ΔkWh / total Δt),
 * NOT a per-interval derivative: at the ~90 s parked cadence a single interval's
 * rise is only 1-2 counter quanta, so per-interval division amplified the ±½-quantum
 * error to ±50% and beat against the poll cadence — the periodic "8 → 3.3 → 8"
 * oscillation. Spanning the window averages that quantisation error down to ≈ q/WINDOW.
 * The slope is then EMA-smoothed across cycles.
 *
 * <p><b>Regen / V2L safety:</b> {@link #sample} only accumulates while the fused
 * {@link ChargingDetector} verdict is CHARGING <i>and</i> the car is in Park, and
 * only counts strictly-increasing counter values. Driving regen (gear D/R) and
 * V2L discharge (counter falling) are therefore structurally excluded — the
 * estimator clears its buffers the moment either gate opens, so a stale value can
 * never latch a phantom reading.
 *
 * <p><b>Accuracy caveat:</b> the daemon polls every ~5 s (ACC on) but ~90 s while
 * parked, and the counter resolution can be coarse (≈0.1 kWh), so the parked-AC
 * estimate is approximate. It is intentionally a last-resort source ordered
 * AFTER every real power getter and is only as alive as the counter feeding it —
 * if the model reports no capacity/remaining-energy movement either, this yields
 * nothing and the nominal placeholder still applies.
 *
 * <p>Threading: all access via {@code synchronized(lock)} — {@link #sample} runs
 * on the collector thread, {@link #estimatePowerKw} from HTTP/daemon threads.
 */
public final class ChargingPowerEstimator {

    private static final String TAG = "ChargingPowerEstimator";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private static final ChargingPowerEstimator INSTANCE = new ChargingPowerEstimator();
    public static ChargingPowerEstimator getInstance() { return INSTANCE; }

    /** Sliding window for the counter ring. The power estimate is the slope of the
     *  counter across this whole span (see {@link #pushAndDerive}), so the window
     *  length is the dominant accuracy knob: the irreducible error is ≈ q / WINDOW
     *  (q ≈ 0.1 kWh counter quantum). 10 min → total rise ~7 quanta at a 6-7 kW
     *  charge → ±q/2 endpoint error is ~±3.7% (1σ), vs ~±6% at 6 min. We trade a
     *  little extra averaging lag for that lower noise — fine for a parked-AC
     *  fallback whose power is near-constant. MUST stay < {@link #MAX_INTERVAL_MS}
     *  so the full-span dt always clears the staleness guard below. */
    private static final long WINDOW_MS = 10 * 60_000L;
    /** Reject a span longer than this (e.g. across a poll-rate change / long gap).
     *  Because the ring evicts points older than WINDOW_MS, the span is bounded by
     *  WINDOW_MS — this guard only fires if WINDOW_MS is ever raised past it. */
    private static final long MAX_INTERVAL_MS = 15 * 60_000L;
    /** Minimum counter increase (kWh) to treat a step as real movement, not sensor jitter. */
    private static final double MIN_STEP_KWH = 0.01;
    /**
     * Ring floor for the COARSE SOC-derived counter: never evict below this many points on age
     * alone, so the derive span stretches to cover ≥2 SOC quanta instead of collapsing to NaN
     * when one 1% step outlasts {@link #WINDOW_MS}.
     *
     * <p><b>4, not 3.</b> The anchor loop SKIPS the session-baseline point (its interval spans the
     * charger's 0→full ramp), so the baseline consumes one interval: N points yield N-2 usable
     * intervals, not N-1. 3 points therefore gave a single quantum — the very "one step ÷ whenever
     * the gauge flipped" measurement this floor exists to prevent, and the ±½-quantum error
     * halving claimed for it never happened. 4 points = 2 usable intervals = ≥2 quanta.
     */
    private static final int SOC_MIN_RING_POINTS = 4;
    /**
     * Largest downward counter step a COARSE ring absorbs as gauge dither rather than treating as
     * a session reset. Sized at just over one 1%-SOC quantum on the smallest PHEV pack we see
     * (~0.21 kWh on 21.5 kWh), so a single-quantum wobble is tolerated and a genuine multi-quantum
     * drop still clears the ring. Applied only when {@code minPoints >= 2} — never to the
     * fine-grained BEV counters (I1).
     */
    private static final double DITHER_TOLERANCE_KWH = 0.25;
    /**
     * Hard staleness bound for the coarse SOC ring. Sized off {@link #SOC_MIN_RING_POINTS}: that
     * floor needs N-1 = 3 quanta inside this span (the baseline consumes one interval), and at
     * 0.3 kW a 1% step on a 21.5 kWh pack takes ~43 min, so 3 quanta need ~129 min. 135 min covers
     * the deep CV taper — the regime asymmetry 5 says PHEV charging actually lives in — while
     * still refusing to derive a CURRENT rate from arbitrarily old data. Publication floor with
     * these constants: ~0.29 kW on a 21.5 kWh pack.
     *
     * <p><b>Coupled to two other constants — change them together.</b> Raising
     * {@code SOC_MIN_RING_POINTS} without raising this silently mutes the low-taper regime (at 90
     * min the floor was ~0.43 kW, so 0.3-0.4 kW charges published NOTHING). And this MUST stay
     * below {@link #STALE_ESTIMATE_MS}, or the destructive expiry becomes defeatable: points older
     * than this span are evicted before the expiry fires, which is what guarantees a post-expiry
     * ring can never re-derive its old slope.
     *
     * <p>Deliberately NOT applied to the fine-grained BEV counters (I1).
     */
    private static final long SOC_MAX_SPAN_MS = 135 * 60_000L;
    /**
     * How long a smoothed estimate may be reported after the last MOVEMENT of the counter that
     * produced it, before {@link #estimatePowerKw()} withholds and discards it as stale.
     *
     * <p>MUST stay above {@link #SOC_MAX_SPAN_MS} — this ordering is load-bearing, not cosmetic.
     * It is what makes the destructive expiry undefeatable: because the stamp tracks the newest
     * point, by the time the expiry fires every coarse point is older than the span and has
     * already been evicted, so the emptied ring cannot re-derive and republish its old slope.
     * Invert the two and that guarantee disappears. It also means a legitimately slow coarse
     * counter is never expired mid-charge, so this catches a DEAD counter, not a slow one.
     */
    private static final long STALE_ESTIMATE_MS = 150 * 60_000L;
    /** Physical plausibility band for the derived power (kW). */
    private static final double MIN_KW = 0.1;
    private static final double MAX_KW = 350.0;
    /** BYD gun state values used without importing the collector model. */
    private static final int GUN_DISCONNECTED = 1;
    private static final int GUN_AC = 2;
    private static final int GUN_V2L = 5;
    /**
     * Upper bound for an explicit AC connector. BYD's highest supported AC input is 22 kW;
     * 25 kW leaves measurement headroom and matches the UI's DC-only power threshold.
     */
    private static final double AC_MAX_COUNTER_KW = 25.0;
    /**
     * The session-capacity counter is known to be half-scale on one Seal firmware, so a 2:1
     * disagreement is legitimate. A remain slope more than 4x the simultaneous capacity slope
     * is not that known scale ambiguity; on the Atto 3 it is the cycling-field failure.
     */
    private static final double AC_MAX_REMAIN_TO_CAP_RATIO = 4.0;
    /**
     * Give two present counters time to produce their first slopes before trusting either one.
     * During this bounded warm-up the UI receives the existing estimated placeholder, which is
     * deliberately excluded from the persisted CPS/cost curve.
     */
    private static final long AC_COUNTER_COMPARE_WAIT_MS = 5 * 60_000L;
    private static final int AC_SOURCE_UNDECIDED = 0;
    private static final int AC_SOURCE_REMAIN = 1;
    private static final int AC_SOURCE_CAPACITY = 2;
    /**
     * EMA weight on the prior estimate when smoothing a fresh reading. Kept
     * modest: the ramp-up interval is now excluded structurally (see
     * {@link #pushAndDerive}) so the FIRST derived value already reflects
     * steady-state power and seeds the EMA directly — the smoothing only needs
     * to damp the per-step quantisation noise of a coarse (~0.1 kWh) counter,
     * not to drag a low ramp seed up over many minutes. A heavier prior (0.7)
     * was the dominant cause of the "stuck at ~3 kW for 6 min on a 6 kW charge"
     * lag, because it averaged the 0→full ramp interval in and crawled up.
     */
    private static final double SMOOTH_PRIOR = 0.5;
    /** Throttle for the (debug) diagnostic line so a real charge can be validated without spam. */
    private static final long LOG_THROTTLE_MS = 60_000L;

    private final Object lock = new Object();

    // (timestampMs, milliKwh, baselineFlag) rings — one per counter source.
    // baselineFlag==1 marks the first point of a charging session; the interval
    // ORIGINATING at that point spans the charger's 0→full ramp and is skipped.
    private final ArrayDeque<long[]> capRing = new ArrayDeque<>();      // {t, milliKwh, baseline}
    private final ArrayDeque<long[]> remainRing = new ArrayDeque<>();   // {t, milliKwh, baseline}
    private final ArrayDeque<long[]> socRing = new ArrayDeque<>();      // {t, milliKwh, baseline} — SOC×nominal×SOH

    /** Latest smoothed estimate, or NaN when no usable estimate is available. */
    private double estimateKw = Double.NaN;
    /**
     * When {@link #estimateKw} was last refreshed by a real derive. 0 = never.
     * {@link #estimatePowerKw()} refuses to report a value older than
     * {@link #STALE_ESTIMATE_MS} so a frozen number can't pose as a live measurement.
     */
    private long lastDeriveMs = 0L;
    /**
     * Set by {@link #pushAndDerive} when THAT call appended a new point (i.e. the counter really
     * moved), cleared at its entry. Read by {@link #sample} immediately after each call — always
     * under {@code lock}, so this scratch flag needs no synchronisation of its own. Exists because
     * a successful slope computation does NOT imply fresh data once minPoints shields old points
     * from eviction.
     */
    private boolean appendedLastCall = false;
    /** Last time the diagnostic line was emitted (throttle). */
    private long lastLogMs = 0L;
    /**
     * SOC→energy scale (kWh per 1.0 SOC-fraction = nominal × SOH), FROZEN at the
     * first charging sample of a session. socEnergyKwh must move ONLY with the SOC
     * gauge — if we recomputed it from the live SohEstimator every cycle, a
     * mid-charge nominal/SOH revision (auto-detect, capacity-Ah refine, calibration)
     * would jump the counter independent of SOC: an upward jump injects a false
     * power spike (recreating the ~7 kW bug), a downward one trips the
     * counter-went-backwards reset and wipes the ring. Freezing the scale makes
     * socE a pure function of SOC. Reset to NaN on charge-stop.
     */
    private double frozenSocScaleKwh = Double.NaN;
    /** Latched counter choice for a confirmed-AC charge; reset at the charging boundary. */
    private int acCounterSource = AC_SOURCE_UNDECIDED;
    /** First time both raw AC counters were simultaneously present, or 0 before comparison. */
    private long acCounterCompareStartMs = 0L;

    private ChargingPowerEstimator() {}

    /**
     * Feed one collect-cycle observation. Counters are in kWh; pass NaN when a
     * counter is unavailable this cycle. {@code fusedCharging} is the
     * {@link ChargingDetector} verdict and {@code inPark} the gear==P gate — both
     * must be true for the estimator to accumulate (regen/V2L safety).
     */
    public void sample(long nowMs, double capacityKwh, double remainKwh,
                       double socPercent, double socScaleKwh,
                       boolean fusedCharging, boolean inPark, int chargingGunState) {
        synchronized (lock) {
            if (!fusedCharging || !inPark || chargingGunState == GUN_V2L) {
                // Not a plug-in charge (or moving): drop everything so a later
                // genuine charge starts from a clean window and no stale delta
                // survives as a phantom reading.
                reset();
                return;
            }
            // SOC-derived energy = SOC-fraction × scale, where scale (nominal × SOH)
            // is FROZEN at the first charging sample so socE moves only with SOC and
            // not with live SohEstimator revisions (see frozenSocScaleKwh). PHEV
            // passes a valid socScaleKwh; BEV passes NaN → socE stays NaN and the
            // estimator falls through to remain/cap exactly as before.
            double socEnergyKwh = Double.NaN;
            if (!Double.isNaN(socPercent) && socPercent > 0 && !Double.isNaN(socScaleKwh) && socScaleKwh > 0) {
                if (Double.isNaN(frozenSocScaleKwh)) frozenSocScaleKwh = socScaleKwh;
                socEnergyKwh = (socPercent / 100.0) * frozenSocScaleKwh;
            }
            // socE is the COARSE counter (SOC × frozen scale, so it moves in whole-1% steps —
            // 0.215 kWh on a 21.5 kWh PHEV pack). It gets a higher point floor and a wider span
            // bound so a slow taper cannot starve it (see pushAndDerive). It is PHEV-ONLY: the
            // caller passes socScaleKwh = NaN on BEV, so socEnergyKwh stays NaN and socRing is
            // never fed — every rule here is unreachable on BEV (invariant I1).
            //
            // SOC_MIN_RING_POINTS = 4 means the span always covers ≥3 quanta before we publish a
            // rate (the baseline point consumes one interval, so N points give N-2 usable ones),
            // cutting the ±½-quantum endpoint error versus a bare 2-point derive — a single
            // quantum can't support a magnitude at all: it yields "one step ÷ whenever the gauge
            // happened to flip", which is what produced the bogus 1.54/4.30/8.60/154.8 spread.
            // Track whether the counter actually accepted a new point this cycle. This — not the
            // success of the slope computation — is what "fresh" means: with old points shielded
            // from eviction by minPoints, a frozen counter re-derives the SAME slope from the SAME
            // points indefinitely. Stamping freshness on a successful derive therefore re-armed it
            // forever and STALE_ESTIMATE_MS could never fire (measured: 8.60 kW republished
            // unchanged for 85+ min, then 100 more before expiry). Anchoring freshness to counter
            // MOVEMENT makes the expiry mean "the counter is dead", which is its stated purpose.
            // NB the read must happen IMMEDIATELY after each call, because the next call resets
            // the flag. Accumulate with |= so an earlier move is never clobbered by a later
            // no-move call — the ordering is load-bearing, hence the adjacency.
            // PER-SOURCE liveness. A disjunction over all three rings would let a ring that did
            // NOT move have its frozen slope stamped fresh by a DIFFERENT ring's movement — the
            // published number comes from ONE ring (priority-selected below), so freshness has to
            // be that ring's. Field-shaped failure: near full the SOC gauge sticks while
            // chargingCapacityKwh keeps ticking, so `cap` movement re-armed a stale `socE` slope
            // every cycle and the expiry could never fire (measured in simulation: 3.44 kW held
            // for 76 min against a true 0.50 kW taper, unflagged and therefore CPS/cost-eligible).
            // This is asymmetry 6 again, relocated from "the maths succeeded" to "some data moved".
            double socE = pushAndDerive(socRing, socEnergyKwh, nowMs, "socE",
                    SOC_MIN_RING_POINTS, SOC_MAX_SPAN_MS);
            boolean socMoved = appendedLastCall;
            // remain/cap are fine-grained (~0.1 kWh on a large pack) and are the BEV path.
            //
            // minPoints=0 is DELIBERATE and makes the eviction BIT-IDENTICAL to the pre-change
            // rule: `ring.size() > 0` ≡ `!ring.isEmpty()`, so the age loop below behaves exactly
            // as before, and the maxSpan loop cannot fire because everything surviving the first
            // loop is younger than WINDOW_MS (10 min) < MAX_INTERVAL_MS (15 min). Passing 2 here
            // would instead RETAIN points aged 10-15 min that the old code dropped — a real
            // behaviour change on BEV, which invariant I1 forbids. The coarse-counter floor is
            // exactly what BEV must not inherit: its counter is fine enough that starvation
            // cannot occur, so it needs no protection and must not get new semantics.
            double rem = pushAndDerive(remainRing, remainKwh, nowMs, "remain", 0, MAX_INTERVAL_MS);
            boolean remMoved = appendedLastCall;
            double cap = pushAndDerive(capRing, capacityKwh, nowMs, "cap", 0, MAX_INTERVAL_MS);
            boolean capMoved = appendedLastCall;
            // Source selection, in order of trustworthiness:
            //   1. socEnergyKwh = SOC × nominal × SOH — PHEV ONLY (the caller passes
            //      NaN on BEV, so this never fires there and BEV stays remain-first).
            //      On PHEV the hardware energy getters lie: getBatteryRemainPowerEV=0,
            //      getBatteryPowerHEV is a dead constant, and getRemainingBatteryPower
            //      FREEZES for tens of minutes while charging (observed: pinned at 64.3
            //      for ~48 min). SOC still ticks, so its derivative is the only truthful
            //      power. externalChargingPower is worse still — it reports the EVSE's
            //      RATED capacity (a flat 7.13 kW), not the ~1.7 kW actually drawn.
            //   2. BEV counter selection. DC/unknown stays remain-first (verified full-scale on
            //      the Seal). Confirmed AC cross-checks remain against chargingCapacityKwh and
            //      latches capacity only when remain is physically impossible or >4x the
            //      simultaneous capacity slope (the Atto 3 cycling-field signature).
            //   3. chargingCapacityKwh — legacy last resort outside confirmed AC.
            String usedSrc;
            double derived;
            // Liveness travels WITH the selected source (see socMoved/remMoved/capMoved above).
            boolean movedThisCycle;
            boolean resetSmoothing = false;
            if (chargingGunState == GUN_DISCONNECTED) {
                // The BMS can announce CHARGING a few seconds before the gun byte catches up.
                // Do not persist a counter slope while the connector still explicitly says
                // disconnected; keep accumulating so the first connected sample has history.
                derived = Double.NaN;
                usedSrc = "awaiting-connected-gun";
                movedThisCycle = false;
                invalidateEstimate();
            } else if (!Double.isNaN(socE)) {
                derived = socE;
                usedSrc = "socE";
                movedThisCycle = socMoved;
            } else if (chargingGunState == GUN_AC) {
                boolean remPlausible = isPlausibleAcSlope(rem);
                boolean capPlausible = isPlausibleAcSlope(cap);
                boolean remImpossible = isPositiveFinite(rem) && rem > AC_MAX_COUNTER_KW;
                boolean capImpossible = isPositiveFinite(cap) && cap > AC_MAX_COUNTER_KW;
                boolean rawRemainPresent = isPositiveFinite(remainKwh);
                boolean rawCapacityPresent = isPositiveFinite(capacityKwh);

                if (acCounterSource == AC_SOURCE_REMAIN && !remPlausible && capPlausible) {
                    // A source trusted earlier in the session has now produced an impossible AC
                    // slope. Fail closed and latch the simultaneously-live session counter.
                    acCounterSource = AC_SOURCE_CAPACITY;
                    resetSmoothing = true;
                }

                if (acCounterSource == AC_SOURCE_UNDECIDED) {
                    if (remImpossible && capPlausible) {
                        acCounterSource = AC_SOURCE_CAPACITY;
                        resetSmoothing = true;
                    } else if (capImpossible && remPlausible) {
                        acCounterSource = AC_SOURCE_REMAIN;
                        resetSmoothing = true;
                    } else if (remPlausible && capPlausible) {
                        acCounterSource = rem > cap * AC_MAX_REMAIN_TO_CAP_RATIO
                                ? AC_SOURCE_CAPACITY : AC_SOURCE_REMAIN;
                        resetSmoothing = true;
                    } else if (rawRemainPresent && rawCapacityPresent
                            && (remPlausible || capPlausible)) {
                        // Both counters exist but only one has accumulated enough movement for a
                        // slope. Wait briefly for a fair comparison instead of letting the first
                        // coarse quantum poison the peak/cost curve.
                        if (acCounterCompareStartMs == 0L) acCounterCompareStartMs = nowMs;
                        if (nowMs - acCounterCompareStartMs >= AC_COUNTER_COMPARE_WAIT_MS) {
                            acCounterSource = remPlausible
                                    ? AC_SOURCE_REMAIN : AC_SOURCE_CAPACITY;
                            resetSmoothing = true;
                        }
                    } else if (remPlausible && !rawCapacityPresent) {
                        acCounterSource = AC_SOURCE_REMAIN;
                        resetSmoothing = true;
                    } else if (capPlausible && !rawRemainPresent) {
                        acCounterSource = AC_SOURCE_CAPACITY;
                        resetSmoothing = true;
                    }
                }

                if (acCounterSource == AC_SOURCE_REMAIN) {
                    derived = remPlausible ? rem : Double.NaN;
                    usedSrc = "remain(ac-validated)";
                    movedThisCycle = remPlausible && remMoved;
                } else if (acCounterSource == AC_SOURCE_CAPACITY) {
                    derived = capPlausible ? cap : Double.NaN;
                    usedSrc = "cap(ac-validated)";
                    movedThisCycle = capPlausible && capMoved;
                } else {
                    derived = Double.NaN;
                    usedSrc = "awaiting-ac-counter-crosscheck";
                    movedThisCycle = false;
                    invalidateEstimate();
                }
            } else if (!Double.isNaN(rem)) {
                // DC, AC_DC and unavailable/legacy gun states retain the proven Seal behaviour.
                derived = rem;
                usedSrc = "remain";
                movedThisCycle = remMoved;
            } else {
                derived = cap;
                usedSrc = "cap";
                movedThisCycle = capMoved;
            }
            // Recorded even when NO source produced a usable slope this cycle, provided the
            // SELECTED source's counter moved — e.g. a session-reset/backwards step, which proves
            // the HAL is alive but yields no rate. Confining it to the derive block would drop that
            // evidence and could expire a live estimate on a counter that keeps resetting.
            //
            // Still safe against the freeze this expiry exists to catch, on both axes: the anchor
            // requires real MOVEMENT (so unchanged data cannot re-arm it) and it requires movement
            // of the ring that actually produced the published number (so a different, still-ticking
            // counter cannot vouch for a frozen one).
            if (movedThisCycle) lastDeriveMs = nowMs;
            if (!Double.isNaN(derived)) {
                // Never blend the rejected AC counter into the source that replaced it. A source
                // decision is a trust boundary, not a normal EMA update.
                if (resetSmoothing) estimateKw = Double.NaN;
                estimateKw = (estimateKw > MIN_KW)
                    ? estimateKw * SMOOTH_PRIOR + derived * (1.0 - SMOOTH_PRIOR)
                    : derived;
                // Diagnostic: surface which counter won, its raw derived kW, and
                // the smoothed output so a single real charge confirms the magnitude
                // (the device reported 3.3 kW on a true ~6 kW charge before this).
                if (nowMs - lastLogMs > LOG_THROTTLE_MS) {
                    lastLogMs = nowMs;
                    // INFO (not debug) so it lands in a default-level log capture —
                    // throttled to 1/min, so it's not spammy. Drop back to debug once
                    // the on-device magnitude is confirmed.
                    logger.info(String.format(
                        "estimate: src=%s derived=%.2fkW smoothed=%.2fkW socE=%.3fkWh remain=%.3fkWh cap=%.3fkWh socRing=%d remainRing=%d capRing=%d",
                        usedSrc, derived, estimateKw,
                        socEnergyKwh, remainKwh, capacityKwh,
                        socRing.size(), remainRing.size(), capRing.size()));
                }
            }
            // If neither counter produced a delta this cycle we keep the last smoothed value,
            // but only for STALE_ESTIMATE_MS — see estimatePowerKw(). Keeping it indefinitely
            // (the previous behaviour, cleared only by reset() on charge-stop) is what let a
            // single early derive be reported as a live measurement for an entire session.
        }
    }

    /**
     * Push a counter reading into its ring (only strictly-increasing values),
     * evict stale entries, and return the average power (kW) as the slope of the
     * counter across the whole retained window (total ΔkWh / total Δt), or NaN
     * when there isn't enough movement to derive one.
     */
    private double pushAndDerive(ArrayDeque<long[]> ring, double counterKwh, long nowMs, String label,
                                 int minPoints, long maxSpanMs) {
        appendedLastCall = false;   // per-call; see the field's doc
        if (Double.isNaN(counterKwh) || counterKwh <= 0) return Double.NaN;
        long milli = Math.round(counterKwh * 1000.0);
        long[] last = ring.peekLast();
        // Only record real upward movement. A flat or DECREASING counter (V2L /
        // session reset) records nothing — its derivative is not charging power.
        if (last == null) {
            // First point of a (re)started session — the BASELINE. The interval
            // that originates here covers the charger's 0→full ramp-up, so it is
            // excluded below (baseline flag = 1). NOT counted as movement: a baseline
            // is the absence of a rate, so it must not refresh the freshness anchor.
            ring.addLast(new long[]{ nowMs, milli, 1 });
        } else if (milli > last[1] + Math.round(MIN_STEP_KWH * 1000.0)) {
            ring.addLast(new long[]{ nowMs, milli, 0 });
            appendedLastCall = true;   // genuine counter movement
        } else if (minPoints >= 2 && milli < last[1]
                && milli >= last[1] - Math.round(DITHER_TOLERANCE_KWH * 1000.0)) {
            // COARSE-RING DITHER TOLERANCE. A 1%-quantised SOC gauge dithers across a boundary
            // near top-of-charge (97→98→97→98), and a single down-step used to wipe the ring —
            // which now costs 3 quanta (~129 min at 0.3 kW) to rebuild, so dithering more often
            // than that would silence the estimator for the rest of the session. A drop of ONE
            // quantum is treated as gauge noise: record nothing, keep the ring. A larger drop is
            // still a genuine session reset / V2L and falls through to the clear below.
            // Fine-grained rings (minPoints 0) are excluded — their counters are monotonic in
            // practice and a real decrease there IS a reset, so BEV behaviour is unchanged (I1).
            //
            // `milli < last[1]` IS LOAD-BEARING. Without it this branch also caught
            // `milli == last[1]` — a bit-identical, completely FROZEN gauge — and claimed movement
            // for it, re-arming lastDeriveMs on every poll while the ring could still derive. That
            // resurrected the exact freeze this class was rewritten to kill, at 15x the duration:
            // ~267 min of an unflagged constant value (135 min span + 150 min expiry) versus the
            // original defect's 17 min, feeding charging_power_samples, energy_added_kwh,
            // session_cost and the latched time-to-full. Asymmetry 6/13 a fourth time — "nothing
            // moved and we called it movement". A flat counter must fall through to no-movement.
            appendedLastCall = true;   // a real (sub-quantum) decrease — the gauge did move
        } else if (milli < last[1]) {
            // Counter went backwards (new session / reset): start fresh, and the
            // fresh point is the new baseline.
            //
            // Counted as LIVENESS (appendedLastCall) even though it yields no rate: the counter
            // demonstrably moved, so the HAL is alive. Without this, a counter that oscillates
            // down-then-up — SOC dithering across a 1% boundary, or a session-counter reset loop —
            // would re-baseline on the down step and append on the up step, and if the poll landed
            // on down steps the freshness anchor could be starved while the car was genuinely
            // charging, expiring a live estimate. Liveness is "the data changed", not "the data
            // gave us a usable slope".
            ring.clear();
            ring.addLast(new long[]{ nowMs, milli, 1 });
            appendedLastCall = true;
            return Double.NaN;
        }
        // QUANTUM-AWARE EVICTION. Evicting purely on wall-clock starves a COARSE counter:
        // ring growth is driven by counter MOVEMENT (only strictly-increasing values are
        // appended above), while eviction was driven by TIME — so when one quantum takes longer
        // than the window, the previous point is evicted in the very same call that appends the
        // new one and the ring can never hold two points. Field case (log_X5RRX996): a 1% SOC
        // step on a 21.5 kWh pack is 0.215 kWh, and the 98→99% step took 12 min against a 10-min
        // window → every derive returned NaN for the whole session, so the last smoothed value
        // (1.54 kW) was reported frozen for 17+ minutes. Below ~1.3 kW this is permanent, i.e.
        // exactly the CV taper where PHEV charging lives.
        //
        // Fix: keep at least `minPoints` samples regardless of age, so the SPAN grows until
        // enough quanta accumulate instead of collapsing to NaN. The fine-grained BEV callers
        // (`remainKwh`/`cap`) pass minPoints=0 — which reproduces the old rule EXACTLY, since
        // `size() > 0` ≡ `!isEmpty()` (see the callers' comment for why 0 and not 2) — and the
        // coarse SOC ring passes SOC_MIN_RING_POINTS. maxSpanMs bounds staleness per caller.
        while (ring.size() > minPoints && nowMs - ring.peekFirst()[0] > WINDOW_MS) {
            ring.removeFirst();
        }
        // Hard staleness bound: drop points older than the caller's max span even if that takes
        // the ring below minPoints. Without this a stalled counter could pin an ancient anchor
        // and derive power across a gap that no longer reflects the present rate.
        while (!ring.isEmpty() && nowMs - ring.peekFirst()[0] > maxSpanMs) {
            ring.removeFirst();
        }
        // PUBLICATION floor, not just an eviction floor. minPoints previously gated only
        // eviction while publication still used a bare `size() < 2`, so the very two-point state
        // the floor exists to prevent stayed reachable: with [baseline, p1] the anchor loop below
        // finds no non-baseline pair, falls back to anchoring ON the baseline, and publishes
        // "one quantum ÷ however long the gauge took to flip". Measured on a 21.5 kWh pack:
        // 8.6 kW at the 90 s parked cadence and 154.8 kW at the 5 s ACC-on cadence, for a true
        // ~1.7 kW charge — and because estimateKw is NaN at that moment the EMA seeds it
        // UNDAMPED. Requiring minPoints before publishing closes it.
        if (ring.size() < Math.max(2, minPoints)) return Double.NaN;

        // Slope over the WHOLE WINDOW SPAN, not per-interval-then-median.
        //
        // The counter (remainKwh) is coarsely quantised (~0.1 kWh steps). The old
        // per-interval method computed ΔkWh/Δt for each adjacent pair: at the ~90 s
        // parked cadence a single interval's ΔkWh is just 1-2 quanta (0.1 or 0.2),
        // so a ±0.05 quantisation error on that tiny delta becomes a ±50% power
        // error, and consecutive intervals ALTERNATE 0.1/0.2 (quantisation beat
        // against the near-constant poll interval) → per-interval power alternates
        // ~4 kW / ~8 kW. The median can't cancel that when the window holds only
        // 3-4 intervals (a 3-interval window's median flips 4↔8 as it slides), and
        // the EMA just lags between the two — the visible periodic "8 → 3.3 → 8"
        // oscillation.
        //
        // Fix: divide the TOTAL rise by the TOTAL elapsed time across the window.
        // Over the 10-min window at ~6.6 kW the total rise is ~1.1 kWh (~11 quanta),
        // so the ±0.05 kWh endpoint quantisation is ~±3.7% (1σ) instead of the
        // per-interval method's ±50% — the beat cancels by construction (the error
        // is q/WINDOW, not q/interval). We anchor on the first NON-baseline point
        // (the baseline-originating interval spans the charger ramp and reads low)
        // and the last.
        long[] first = null, lastPt = null;
        for (long[] pt : ring) {
            // Anchor start at the first point that is NOT the session baseline, so
            // the 0→full ramp interval is excluded from the span.
            if (first == null) {
                if (pt[2] == 1) continue;   // skip the baseline point itself
                first = pt;
            }
            lastPt = pt;
        }
        // Baseline-anchoring fallback: "better an approximate value than none" — TRUE for a
        // fine-grained counter (many quanta per window, so including the ramp interval skews the
        // slope slightly), FALSE for a coarse one (the span IS a single quantum, so the result is
        // not approximate but arbitrary — it measures gauge-flip timing, not power; this is the
        // 8.6 / 154.8 kW artifact). Restrict the fallback to callers that did not ask for a
        // multi-point floor, which is exactly the fine-grained BEV callers (minPoints 0) and
        // leaves their behaviour bit-identical.
        if (first == null || lastPt == null || first == lastPt) {
            // >= 2, not > 2: the intent is "any caller that asked for a multi-point floor", and
            // a hypothetical minPoints=2 caller would otherwise get NO effective floor
            // (Math.max(2,2)) AND the baseline fallback — landing in exactly the single-quantum
            // [baseline, p1] state asymmetry 7 forbids. Identical behaviour for 0/3/4.
            if (minPoints >= 2) return Double.NaN;  // coarse ring: refuse rather than guess
            first = ring.peekFirst();
            lastPt = ring.peekLast();
        }
        if (first == null || lastPt == null) return Double.NaN;

        long dtMs = lastPt[0] - first[0];
        long dKwhMilli = lastPt[1] - first[1];
        // Span bound is the CALLER's, not the old global MAX_INTERVAL_MS: raising the coarse
        // counter's retention above 15 min would otherwise be undone here, re-failing every
        // derive it was meant to enable. Fine-grained callers still pass MAX_INTERVAL_MS.
        if (dtMs <= 0 || dtMs > maxSpanMs || dKwhMilli <= 0) return Double.NaN;
        double kw = (dKwhMilli / 1000.0) * 3_600_000.0 / dtMs;
        if (kw < MIN_KW || kw > MAX_KW) return Double.NaN;
        return kw;
    }

    /**
     * Latest estimate (kW), or NaN if none / too stale to be called a measurement.
     * Safe to call from any thread.
     *
     * <p><b>Staleness expiry.</b> This used to return the last smoothed value for as long as the
     * session lasted, cleared only by {@link #reset()} on charge-stop. That is how a single early
     * derive became a permanent "measurement": in log_X5RRX996 every derive in the captured window
     * returned NaN (see {@link #pushAndDerive}), yet 1.54 kW was reported on all 18 polls across
     * 17 minutes — and because the estimator branch is NOT flagged {@code isEstimated}, that frozen
     * number fed the CPS ramp curve and the session's energy/cost integral (invariants I2, I3).
     *
     * <p>Expiring instead means the consumer cascade falls through to the honest
     * nominal-placeholder branch (which IS flagged estimated and is excluded from the curve) once
     * the counter stops producing rates. Bounded generously — one coarse quantum at a low taper
     * rate can legitimately take tens of minutes, and expiring mid-charge would flap the display —
     * so this only catches a genuinely dead counter, not a slow one.
     */
    public double estimatePowerKw() {
        synchronized (lock) {
            if (Double.isNaN(estimateKw)) return Double.NaN;
            if (lastDeriveMs <= 0) return Double.NaN;
            if (System.currentTimeMillis() - lastDeriveMs > STALE_ESTIMATE_MS) {
                // DESTRUCTIVE expiry. Merely withholding the value left it in estimateKw, where
                // the EMA at the top of sample() would blend it into the next derive at
                // SMOOTH_PRIOR (50%) weight — so a value we just declared "not a measurement"
                // still shaped the number we published, e.g. a stale 3.0 kW + a true 0.8 kW
                // taper → 1.9 kW, unflagged and therefore CPS/cost-eligible (I3). Discarding it
                // makes the next derive take the `: derived` arm and publish the honest rate.
                estimateKw = Double.NaN;
                lastDeriveMs = 0L;
                return Double.NaN;
            }
            return estimateKw;
        }
    }

    private static boolean isPositiveFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value) && value > 0;
    }

    private static boolean isPlausibleAcSlope(double kw) {
        return isPositiveFinite(kw) && kw >= MIN_KW && kw <= AC_MAX_COUNTER_KW;
    }

    private void invalidateEstimate() {
        estimateKw = Double.NaN;
        lastDeriveMs = 0L;
    }

    private void reset() {
        socRing.clear();
        capRing.clear();
        remainRing.clear();
        estimateKw = Double.NaN;
        lastDeriveMs = 0L;
        frozenSocScaleKwh = Double.NaN;  // next session re-freezes its own scale
        acCounterSource = AC_SOURCE_UNDECIDED;
        acCounterCompareStartMs = 0L;
    }
}

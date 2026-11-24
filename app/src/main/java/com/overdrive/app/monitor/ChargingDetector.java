package com.overdrive.app.monitor;

import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.logging.DaemonLogger;

/**
 * Fused charging-state detector.
 *
 * Purpose: replace the old polling-only inference path (which produced the
 * "very inconsistent" detection) with an event-driven layered model that
 * fuses three independent BYD HAL signals plus broadcast-receiver edges.
 *
 * Layers, evaluated in order:
 *
 *   L1. BMS state edge (chargingState == 1 CHARGING) — pushed by the
 *       BYDAutoChargingDevice typed listener (onBatteryManagementDeviceStateChanged).
 *       Authoritative when present. The known firmware bug: some PHEV builds
 *       leave this stuck at 15 IDLE while AC charging, so it's not sufficient
 *       on its own.
 *
 *   L2. BYDAutoPowerDevice.isCharging() — independent ground truth from the
 *       power MCU. Polled once per collect cycle. Used as the primary
 *       cross-check that catches the L1 firmware lie.
 *
 *   L3. Power-flow inference — only fires when L1 AND L2 disagree for
 *       {@link #INFERENCE_DISAGREEMENT_MIN_MS}. Requires the gear-in-park
 *       guard, {@link #HYSTERESIS_SAMPLES} distinct observations, and a gun
 *       state that is not CONTRADICTORY: a positive AC/DC assertion (2/3/4),
 *       or UNAVAILABLE accompanied by recent movement on a raw charging
 *       channel. V2L (5) and DISCONNECTED (1) always block — they positively
 *       state the pack is discharging or unplugged. UNAVAILABLE is a dead
 *       accessor rather than evidence against charging, and refusing it left
 *       this layer unable to fire on the very trims it exists to serve (BMS
 *       stuck at IDLE, power MCU unavailable, no gun report).
 *       enginePowerKw is invalidated on ACC OFF, so a stale value from
 *       yesterday's drive cannot retrigger this layer.
 *
 *   Edge inputs: ACTION_POWER_CONNECTED / ACTION_POWER_DISCONNECTED
 *       transitions are pushed in directly. CONNECTED nudges fusion toward
 *       charging (sets a "plug recently inserted" flag, accelerating L3
 *       hysteresis); DISCONNECTED forces immediate transition to NOT_CHARGING
 *       and clears all sticky power values.
 *
 * Threading: all mutations via synchronized methods; reads return immutable
 * ChargingStateData snapshots.
 */
public final class ChargingDetector {

    private static final String TAG = "ChargingDetector";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    // ===== Tuning =====

    /**
     * How long L1 (BMS) and L2 (Power.isCharging) must disagree before we
     * fall through to L3 (power-flow inference). Both layers can take a few
     * seconds to settle after plug-in, so we tolerate a brief window.
     */
    private static final long INFERENCE_DISAGREEMENT_MIN_MS = 10_000L;

    /**
     * Samples required to flip the L3 inferred state. Each call to
     * {@link #updatePollEvidence} is one sample. With the 5-second
     * collect cadence, 3 samples = ~15 seconds of consistent evidence
     * before L3 declares charging — long enough to ride out a CAN-bus glitch.
     */
    private static final int HYSTERESIS_SAMPLES = 3;

    /**
     * After ACTION_POWER_CONNECTED, we know the user just plugged in. This
     * window biases fusion toward charging — useful because the BMS can
     * take 5-10s to start reporting state and we don't want to flap to
     * "not charging" between plug-in and the first BMS event.
     */
    private static final long PLUG_BIAS_WINDOW_MS = 30_000L;

    /**
     * After ACTION_POWER_DISCONNECTED, we know charging is over regardless
     * of what the BMS says. We snap to NOT_CHARGING and ignore stale BMS
     * for this long (handles the case where the BMS still says 1 CHARGING
     * for a few seconds after unplug).
     */
    private static final long L2_FRESHNESS_MS = 5 * 60_000L;
    /** A reconnect requires one positive callback nomination plus one matching current poll. */
    private static final int POSITIVE_CONNECTION_CONFIRMATIONS_REQUIRED = 2;

    /**
     * Engine-power evidence deadband (kW). Negative enginePowerKw = current flowing into the pack;
     * -0.3 kW is the threshold below which sensor noise dominates.
     *
     * <p>There is deliberately NO equivalent threshold for the charging accessors. Their unit is
     * firmware-dependent (kW on some trims, cumulative kWh on others), so a level test on them is
     * meaningless and latches permanently on the counter trims — they are movement-tested instead.
     * Do not reintroduce one.
     */
    private static final double ENGINE_POWER_DEADBAND = 0.3;

    /**
     * Maximum age for enginePowerKw to be trusted as live evidence. Beyond
     * this, the value is stale and {@link #invalidateAccDependentSignals}
     * will have already cleared it on ACC OFF anyway.
     */
    private static final long ENGINE_POWER_FRESHNESS_MS = 15_000L;

    /**
     * How long a POSITIVE BMS charging verdict may go unrefreshed before it is discarded.
     *
     * <p>Generous relative to the poll cadence: the parked poll is 90 s, so this must ride out two
     * missed cycles without dropping a verdict on a healthy-but-slow trim. It exists only to stop an
     * accessor that has stopped answering from pinning L1 at CHARGING forever.
     */
    private static final long BMS_FRESHNESS_MS = 5 * 60_000L;

    /** True when the held BMS verdict has not been refreshed inside {@link #BMS_FRESHNESS_MS}. */
    private boolean bmsStale(long now) {
        return bmsStateAtElapsedMs <= 0 || (now - bmsStateAtElapsedMs) > BMS_FRESHNESS_MS;
    }

    private static boolean isExplicitTerminalBmsState(int state) {
        return state == ChargingStateData.CHARGING_BATTERY_STATE_READY
                || state == ChargingStateData.CHARGING_BATTERY_STATE_CHARG_FINISH
                || state == ChargingStateData.CHARGING_BATTERY_STATE_CHARG_TERMINATE
                || state == ChargingStateData.CHARGING_BATTERY_STATE_BREAKDOWN_C10
                || state == ChargingStateData.CHARGING_BATTERY_STATE_BREAKDOWN_CHARGING_GUN
                || state == ChargingStateData.CHARGING_BATTERY_STATE_BREAKDOWN_AC
                || state == ChargingStateData.CHARGING_BATTERY_STATE_BREAKDOWN_CHARGER
                || state == ChargingStateData.CHARGING_BATTERY_STATE_TIMEOUT
                || state == ChargingStateData.CHARGING_BATTERY_STATE_DISCHARG_FINISH;
    }

    private static boolean isDischargingBmsState(int state) {
        return state == ChargingStateData.CHARGING_BATTERY_STATE_DISCHARG
                || state == ChargingStateData.CHARGING_BATTERY_STATE_DISCHARG_CBU;
    }

    private static boolean isAuthoritativeOffBmsState(int state) {
        return isExplicitTerminalBmsState(state) || isDischargingBmsState(state);
    }

    /**
     * Output-edge debounce. The session's "charging" verdict is frequently
     * held by a single layer (e.g. L2 Power.isCharging() on a PHEV whose BMS
     * is stuck at 15 IDLE while AC charging). When that layer's reflective
     * call momentarily fails or returns a sentinel, its input goes null for
     * one cycle and {@link #recompute} would briefly resolve to NOT_CHARGING,
     * firing a spurious stopped+started pair — the "charging started keeps
     * re-triggering" symptom. We require an ON->OFF verdict to persist this
     * long before committing it. At the ~5s collect cadence this rides out a
     * 1-2 cycle dropout. A genuine physical unplug or explicit terminal BMS
     * state bypasses the debounce and stops immediately.
     */
    private static final long OFF_EDGE_DEBOUNCE_MS = 12_000L;

    private static final java.util.concurrent.ScheduledExecutorService TIMER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ChargingDetectorTimer");
                t.setDaemon(true);
                return t;
            });

    /**
     * Seqlock-style fence for state published outside this detector, chiefly the collector snapshot
     * and VehicleDataMonitor's taper-admission boundary. Writers do not hold this monitor while doing
     * their work; readers reject a window that overlaps or spans any writer.
     */
    private static final Object PUBLICATION_FENCE_LOCK = new Object();
    private static long externalPublicationGeneration = 1L;
    private static int externalPublicationWriters = 0;
    private static final ThreadLocal<Integer> PUBLICATION_MUTATION_DEPTH =
            new ThreadLocal<>();

    public static final class PublicationMutation implements AutoCloseable {
        private final Thread owner;
        private boolean closed;

        private PublicationMutation(Thread owner) {
            this.owner = owner;
        }

        @Override
        public void close() {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException(
                        "Charging publication mutation closed by a different thread");
            }
            synchronized (PUBLICATION_FENCE_LOCK) {
                if (closed) return;
                closed = true;
                externalPublicationGeneration++;
                externalPublicationWriters--;
            }
            Integer depth = PUBLICATION_MUTATION_DEPTH.get();
            if (depth == null || depth <= 1) {
                PUBLICATION_MUTATION_DEPTH.remove();
            } else {
                PUBLICATION_MUTATION_DEPTH.set(depth - 1);
            }
        }
    }

    /** Begin a collector/monitor mutation that must be coherent with the fused detector view. */
    public static PublicationMutation beginPublicationMutation() {
        synchronized (PUBLICATION_FENCE_LOCK) {
            externalPublicationWriters++;
            externalPublicationGeneration++;
        }
        Integer depth = PUBLICATION_MUTATION_DEPTH.get();
        PUBLICATION_MUTATION_DEPTH.set(depth == null ? 1 : depth + 1);
        return new PublicationMutation(Thread.currentThread());
    }

    /** True while this thread owns a coherent collector/detector publication transaction. */
    public static boolean isCurrentThreadPublicationMutationActive() {
        Integer depth = PUBLICATION_MUTATION_DEPTH.get();
        return depth != null && depth > 0;
    }

    // ===== State =====

    private static final ChargingDetector INSTANCE = new ChargingDetector();
    public static ChargingDetector getInstance() { return INSTANCE; }

    /**
     * Listener for fused-state edges. Fires only on actual transitions
     * (true→false or false→true), not on every input. Use this when you
     * want session-level events rather than the raw BMS edge stream
     * (which misses PHEV-stuck-at-IDLE charging sessions entirely).
     */
    public interface FusedStateListener {
        void onFusedChargingChanged(boolean isCharging, String source);
    }

    /**
     * Physical stop confirmation delivered even when the fused output is already OFF. This closes
     * a deferred FINISHED/taper session immediately when the cable is removed or V2L starts.
     */
    public interface AuthoritativeStopListener {
        void onAuthoritativeChargingStop(String source);
    }

    private final java.util.concurrent.CopyOnWriteArrayList<FusedStateListener> fusedListeners =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.concurrent.CopyOnWriteArrayList<AuthoritativeStopListener>
            authoritativeStopListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addFusedStateListener(FusedStateListener l) {
        if (l != null) fusedListeners.addIfAbsent(l);
    }
    public void removeFusedStateListener(FusedStateListener l) {
        if (l != null) fusedListeners.remove(l);
    }
    public void addAuthoritativeStopListener(AuthoritativeStopListener l) {
        if (l != null) authoritativeStopListeners.addIfAbsent(l);
    }
    public void removeAuthoritativeStopListener(AuthoritativeStopListener l) {
        if (l != null) authoritativeStopListeners.remove(l);
    }

    private final Object lock = new Object();

    // L1
    private int bmsState = BydVehicleData.UNAVAILABLE;
    private long bmsStateAtMs = 0L;
    private long bmsStateAtElapsedMs = 0L;

    // L2
    /** Tri-state: TRUE/FALSE/null (unavailable). */
    private Boolean powerIsChargingTri = null;
    private long powerIsChargingAtMs = 0L;
    // L3 inputs (snapshot pushed in by the collector each cycle)
    private double enginePowerKw = Double.NaN;
    private long enginePowerAtMs = 0L;
    private long enginePowerAtElapsedMs = 0L;
    private double lastEnginePowerObservedKw = Double.NaN;
    private long enginePowerChangedAtMs = 0L;
    private long enginePowerChangedAtElapsedMs = 0L;
    private double externalChargingPowerKw = Double.NaN;
    private double chargingPowerKw = Double.NaN;
    private int chargingGunState = BydVehicleData.UNAVAILABLE;
    private boolean v2lActive = false;
    private boolean inPark = false;

    // L3 hysteresis counter (positive = consecutive "charging" samples,
    // negative = consecutive "not charging" samples).
    private int inferenceHysteresis = 0;
    private boolean l3Latched = false;

    // ACC awareness
    private boolean accIsOn = true;

    // Edge events
    private long lastPlugConnectedMs = 0L;
    private long lastPlugConnectedElapsedMs = 0L;
    private long lastPlugDisconnectedMs = 0L;
    /** Physical gun-out remains authoritative until a real reconnect is observed. */
    private boolean disconnectedLatched = false;
    /**
     * A completed session cannot be reopened by evidence delivered late from that same session.
     * Cleared only by an explicit new-session signal.
     */
    private boolean terminalSessionBarrier = false;
    /** Start of the current completed/disconnected epoch. Evidence predating it cannot restart. */
    private long terminalBarrierSinceMs = 0L;
    private long terminalBarrierSinceElapsedMs = 0L;
    /** Advances whenever evidence is admitted for a genuinely new charging epoch. */
    private long sessionEpoch = 1L;
    /** Epoch in which a fused ON verdict has actually existed. */
    private long activeSessionEpoch = 0L;
    private int pendingTerminalBmsState = BydVehicleData.UNAVAILABLE;
    private long pendingTerminalEpoch = 0L;
    private int pendingTerminalPositivePollConfirmations = 0;
    /**
     * A delayed callback barrier may be contradicted only when no cohesive positive poll was already
     * observed before OFF. Otherwise the same cached levels could straddle the stop and reopen it.
     */
    private boolean terminalBarrierAllowsCohesiveRecovery = false;
    /** A same-cable restart needs a current negative phase before positive levels can reopen it. */
    private boolean scheduledRestartLevelArmed = false;
    private int positiveConnectionConfirmations = 0;
    private int positiveConnectionCandidate = BydVehicleData.UNAVAILABLE;
    private int v2lExitConfirmations = 0;

    // Fused output (what callers see)
    private boolean fusedCharging = false;
    private long fusedAtMs = 0L;
    /** Advances whenever the lifecycle surface visible to API publishers changes. */
    private long publicationGeneration = 1L;
    private boolean publishedGenerationCharging = false;
    private boolean publishedGenerationPhysicalStop = false;
    private boolean publishedGenerationTerminalBarrier = false;
    private boolean publishedGenerationPendingTerminalStop = false;
    /** Start of the most recent fused charging session; retained through FINISHED for taper checks. */
    private long lastSessionStartedAtMs = 0L;
    /** Earliest live sample in the poll currently being fused, or 0 outside poll recomputation. */
    private long pendingSessionEvidenceAtMs = 0L;
    /** Which layer last decided the fused state. For diagnostic logging only. */
    private String fusedSource = "init";
    private boolean externalVerdictActive = false;
    private boolean externalVerdictCharging = false;
    private long externalVerdictAtElapsedMs = 0L;
    private long externalVerdictFreshnessMs = 0L;
    private String externalVerdictSource = "external-telemetry";

    /**
     * When a recompute first resolves ON->OFF for a non-unplug reason, we
     * record the timestamp here instead of committing the flip immediately.
     * The OFF only commits once it has persisted {@link #OFF_EDGE_DEBOUNCE_MS}
     * (see recompute). 0 = no pending OFF. Reset the moment any layer resolves
     * back to charging, so a transient dropout never fires a stopped edge.
     */
    private long pendingOffSinceMs = 0L;
    private long l1L2DisagreementSinceMs = 0L;

    private final long disagreementMinMs;
    private final long offEdgeDebounceMs;
    private final long l2FreshnessMs;
    private final long steadyRawMinSpanMs;
    private final long rawSignalMoveWindowMs;
    private long disagreementTimerGeneration = 0L;
    private long offTimerGeneration = 0L;
    private long l2TimerGeneration = 0L;
    private long bmsTimerGeneration = 0L;
    private long l3EvidenceTimerGeneration = 0L;
    private long externalVerdictTimerGeneration = 0L;

    /** Transitions are enqueued while holding {@link #lock}, then drained in that exact order. */
    private final Object transitionDispatchLock = new Object();
    private final java.util.ArrayDeque<FusedTransition> pendingTransitions =
            new java.util.ArrayDeque<>();
    private boolean transitionDispatchRunning = false;

    private ChargingDetector() {
        this(INFERENCE_DISAGREEMENT_MIN_MS, OFF_EDGE_DEBOUNCE_MS, L2_FRESHNESS_MS,
                STEADY_RAW_MIN_SPAN_MS, RAW_SIGNAL_MOVE_WINDOW_MS);
    }

    private static long monotonicNowMs() {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    }

    /** Short-duration constructor used by deterministic JVM tests. */
    ChargingDetector(long disagreementMinMs, long offEdgeDebounceMs, long l2FreshnessMs) {
        this(disagreementMinMs, offEdgeDebounceMs, l2FreshnessMs, STEADY_RAW_MIN_SPAN_MS);
    }

    /** Short-duration constructor that also makes steady-rate qualification testable. */
    ChargingDetector(long disagreementMinMs, long offEdgeDebounceMs, long l2FreshnessMs,
                     long steadyRawMinSpanMs) {
        this(disagreementMinMs, offEdgeDebounceMs, l2FreshnessMs,
                steadyRawMinSpanMs, RAW_SIGNAL_MOVE_WINDOW_MS);
    }

    /** Fully configurable constructor for freshness/deadline regression tests. */
    ChargingDetector(long disagreementMinMs, long offEdgeDebounceMs, long l2FreshnessMs,
                     long steadyRawMinSpanMs, long rawSignalMoveWindowMs) {
        this.disagreementMinMs = Math.max(1L, disagreementMinMs);
        this.offEdgeDebounceMs = Math.max(1L, offEdgeDebounceMs);
        this.l2FreshnessMs = Math.max(1L, l2FreshnessMs);
        this.steadyRawMinSpanMs = Math.max(1L, steadyRawMinSpanMs);
        this.rawSignalMoveWindowMs = Math.max(1L, rawSignalMoveWindowMs);
    }

    // ===== Inputs =====

    /**
     * BMS state edge. Called by the typed charging listener on
     * onBatteryManagementDeviceStateChanged AND by the collector after
     * polling getBatteryManagementDeviceState() / chargingState feature ID.
     */
    public void updateBmsState(int newState) {
        applyBmsState(newState, false);
    }

    /** A BMS value read synchronously from the current hardware poll. */
    public void confirmBmsState(int newState) {
        applyBmsState(newState, true);
    }

    private static final class BmsApplyResult {
        final boolean accepted;
        final boolean authoritativeStop;
        BmsApplyResult(boolean accepted, boolean authoritativeStop) {
            this.accepted = accepted;
            this.authoritativeStop = authoritativeStop;
        }
    }

    private void applyBmsState(int newState, boolean pollConfirmed) {
        synchronized (lock) {
            BmsApplyResult result = applyBmsStateLocked(
                    newState, pollConfirmed, System.currentTimeMillis(), monotonicNowMs());
            if (result.accepted) {
                enqueueTransitionLocked(recompute(pollConfirmed ? "bms-poll" : "bms-edge"));
                if (result.authoritativeStop) {
                    enqueueTransitionLocked(FusedTransition.authoritativeStop("v2l-export"));
                }
            }
        }
        drainFusedTransitions();
    }

    private BmsApplyResult applyBmsStateLocked(int newState, boolean pollConfirmed,
                                               long wallNow, long elapsedNow) {
        int previous = bmsState;
        // READY is also emitted during normal BMS initialization. It ends a completed session only
        // when this BMS stream had first asserted CHARGING; otherwise it may stop an L2-only verdict,
        // but must not fence the immediately following READY -> CHARGING startup sequence.
        boolean readyEndsEstablishedSession =
                newState == ChargingStateData.CHARGING_BATTERY_STATE_READY
                && (previous == ChargingStateData.CHARGING_BATTERY_STATE_CHARGING
                    || (fusedCharging && previous != BydVehicleData.UNAVAILABLE));
        boolean terminalForObservedSession = isAuthoritativeOffBmsState(newState)
                && (newState != ChargingStateData.CHARGING_BATTERY_STATE_READY
                    || readyEndsEstablishedSession);
        if ((disconnectedLatched || v2lActive)
                && newState == ChargingStateData.CHARGING_BATTERY_STATE_CHARGING) {
            return new BmsApplyResult(false, false);
        }
        if (newState == ChargingStateData.CHARGING_BATTERY_STATE_CHARGING
                && hasPendingTerminalCallbackLocked()) {
            // A terminal callback has already invalidated the previous positive levels. A getter
            // returning the same cached CHARGING value is not a new lifecycle edge, even when it was
            // read synchronously. Let the pending stop commit; a later armed restart can open a new
            // epoch without allowing one lagging poll to erase a genuine FINISHED callback.
            return new BmsApplyResult(false, false);
        }
        if (!pollConfirmed && isAuthoritativeOffBmsState(newState)
                && activeSessionEpoch != sessionEpoch) {
            // A source-less listener callback cannot end an epoch that has never been ON. This is
            // the connection-generation fence: queued FINISHED from the previous cable/session is
            // ignored before the new epoch starts, while a genuine FINISHED after ON is accepted.
            return new BmsApplyResult(false, false);
        }
        if (pollConfirmed) {
            // A current terminal poll owns its connection identity and may commit immediately. An
            // ambiguous IDLE poll is not positive charging evidence and must not erase a genuine
            // pending terminal callback. Positive levels remain fenced until that candidate commits.
            if (isAuthoritativeOffBmsState(newState)) {
                clearPendingTerminalCallbackLocked();
            }
        } else if (terminalForObservedSession
                && activeSessionEpoch == sessionEpoch
                && sessionEpoch > 1L) {
            pendingTerminalBmsState = newState;
            pendingTerminalEpoch = sessionEpoch;
            // The callback has no connection identity, so elapsed time must never promote it to a
            // "poll confirmed" terminal state. Remove the positive layers it directly contradicts
            // and let the normal OFF debounce run; level-only evidence has no new-session identity.
            bmsState = BydVehicleData.UNAVAILABLE;
            bmsStateAtMs = 0L;
            bmsStateAtElapsedMs = 0L;
            invalidateBmsTimerLocked();
            powerIsChargingTri = null;
            powerIsChargingAtMs = 0L;
            invalidateL2TimerLocked();
            // This callback may be current, so pre-callback L3 credit cannot be allowed to keep the
            // candidate ON until some unrelated event arrives. Cached positive levels cannot cancel
            // this candidate; preserve raw/engine LEVEL baselines so a genuinely fresh post-callback
            // change can still prove a taper after the stop commits, but remove all
            // activity timestamps and hysteresis earned by the session that may just have ended.
            clearRawSignalActivityPreservingBaselines();
            invalidateAccDependentSignals();
            externalChargingPowerKw = Double.NaN;
            chargingPowerKw = Double.NaN;
            enginePowerChangedAtMs = 0L;
            enginePowerChangedAtElapsedMs = 0L;
            lastCountedEngineChangeSequence = engineChangeSequence;
            inferenceHysteresis = 0;
            l3Latched = false;
            invalidateL3EvidenceTimerLocked();
            return new BmsApplyResult(true, false);
        }

        boolean readyStopsAmbiguousBmsSession =
                newState == ChargingStateData.CHARGING_BATTERY_STATE_READY
                && fusedCharging
                && previous == BydVehicleData.UNAVAILABLE;
        if (readyStopsAmbiguousBmsSession) {
            // READY is authoritative for the active L2/L3-only session even though it is also a
            // normal startup state. Drop that session's cached evidence so a following IDLE cannot
            // reuse it, but do not install the completed-session barrier: a genuine READY ->
            // CHARGING startup must still be admitted immediately.
            activeSessionEpoch = 0L;
            clearCompletedSessionEvidence();
        }

        bmsState = newState;
        bmsStateAtMs = wallNow;
        bmsStateAtElapsedMs = elapsedNow;
        if (newState == ChargingStateData.CHARGING_BATTERY_STATE_CHARGING) {
            scheduleBmsExpiryLocked();
        } else {
            invalidateBmsTimerLocked();
        }

        boolean authoritativeStop = false;
        if (terminalForObservedSession
                && activeSessionEpoch == sessionEpoch) {
            enterTerminalBarrierLocked(wallNow, elapsedNow);
            authoritativeStop = isDischargingBmsState(newState)
                    && !isDischargingBmsState(previous);
        }
        return new BmsApplyResult(true, authoritativeStop);
    }

    /**
     * BYDAutoPowerDevice.isCharging() result. May be null if the call
     * failed or returned a sentinel — null means "unavailable, do not use".
     */
    /**
     * Raw charging-signal observation, delivered by the collector BEFORE its admission gate.
     *
     * <p>This exists to break a circular dependency. The collector's gate consults
     * {@link #isCharging()}, while L3 inference reads the snapshot fields that gate populates — so
     * without an ungated channel neither could bootstrap once the admission gate was introduced.
     *
     * <p><b>Scope, stated precisely.</b> A trim whose gun state is UNAVAILABLE can bootstrap only
     * after this channel is corroborated by a distinct SOC rise or charging-direction pack-flow
     * event. Raw movement alone remains weak wake-up evidence in that case, because retained rate
     * accessors can jitter after charging stops. With a positive gun assertion, a substantial
     * monotonic startup ramp may still bootstrap before those slower independent channels arrive.
     *
     * <p>Deliberately narrow: it records only that a charging-related channel is MOVING, which is
     * evidence of activity regardless of whether the value is a rate or a cumulative counter and
     * regardless of its unit. It never contributes a magnitude, so it cannot reach a displayed rate
     * or a persisted energy figure. Movement rather than level is what matters — a counter that has
     * stopped advancing means the charge has stopped, and a level test would latch forever.
     */
    public void observeRawChargingSignal(String source, double raw) {
        if (source == null || Double.isNaN(raw) || Double.isInfinite(raw) || raw < 0) return;
        boolean observedActivity = false;
        boolean observedStop = false;
        synchronized (lock) {
            if (disconnectedLatched || v2lActive) return;
            if (hasPendingTerminalCallbackLocked()) return;
            long wallNow = System.currentTimeMillis();
            long elapsedNow = monotonicNowMs();
            synchronized (rawSignalLock) {
                Double prev = lastRawSignal.put(source, raw);
                if (raw <= 0) {
                    steadyRawSince.remove(source);
                    steadyRawEpochBySource.remove(source);
                    removeRawMovementLocked(source);
                    removeWeakRawMovementLocked(source);
                    observedStop = prev != null && prev > 0;
                } else if (prev == null) {
                    return;
                } else if (prev.doubleValue() != raw) {
                    long weakSequence = recordWeakRawMovementLocked(source, wallNow, elapsedNow);
                    if (shouldPromoteRawMovementLocked(source, prev, raw, elapsedNow)) {
                        promoteRawMovementLocked(source, wallNow, elapsedNow, weakSequence);
                        observedActivity = true;
                    }
                    steadyRawSince.remove(source);
                    steadyRawEpochBySource.remove(source);
                } else {
                    // A repeated positive level can count only when independent movement proves that
                    // this plateau is live. COUNTER repeats are never eligible: a stopped counter is
                    // expected to retain its final total indefinitely.
                    com.overdrive.app.byd.ChargeSourceClassifier.Kind kind =
                            com.overdrive.app.byd.ChargeSourceClassifier.kindOf(source);
                    if (kind == com.overdrive.app.byd.ChargeSourceClassifier.Kind.COUNTER) {
                        steadyRawSince.remove(source);
                        steadyRawEpochBySource.remove(source);
                    } else {
                        Long since = steadyRawSince.get(source);
                        Long plateauEpoch = steadyRawEpochBySource.get(source);
                        if (since == null || plateauEpoch == null
                                || plateauEpoch.longValue() != sessionEpoch) {
                            Long sourceMovedAt = rawMoveElapsedBySource.get(source);
                            long from = sourceMovedAt != null && sourceMovedAt <= elapsedNow
                                    ? sourceMovedAt : elapsedNow;
                            steadyRawSince.put(source, from);
                            steadyRawEpochBySource.put(source, sessionEpoch);
                        } else if (!terminalSessionBarrier
                                && !isAuthoritativeOffBmsState(bmsState)
                                && elapsedNow - since >= steadyRawMinSpanMs) {
                            boolean corroborated = false;
                            if (kind == com.overdrive.app.byd.ChargeSourceClassifier.Kind.RATE) {
                                corroborated = consumeIndependentCorroborationLocked(
                                        elapsedNow, since);
                            } else if (kind
                                    == com.overdrive.app.byd.ChargeSourceClassifier.Kind.UNKNOWN) {
                                long socRiseAtMs = consumePostPlateauSocRiseLocked(
                                        elapsedNow, since);
                                if (socRiseAtMs > 0) {
                                    corroborated = true;
                                    // The collector cannot train this source until L3 is already live.
                                    // This observation is safe to train because the post-plateau SOC
                                    // rise independently proves that energy continued to enter the pack.
                                    com.overdrive.app.byd.ChargeSourceClassifier
                                            .observeWhileCharging(source, raw);
                                    com.overdrive.app.byd.ChargeSourceClassifier
                                            .classifySteadyRateWithCorroboration(
                                                    source, raw, wallNow, socRiseAtMs);
                                } else {
                                    corroborated = consumeIndependentCorroborationLocked(
                                            elapsedNow, since);
                                    if (corroborated) {
                                        com.overdrive.app.byd.ChargeSourceClassifier
                                                .observeWhileCharging(source, raw);
                                    }
                                }
                            }
                            if (corroborated) {
                                // One consumed corroboration produces one raw observation sequence.
                                // Recomputes and repeated getter reads cannot spend it again.
                                long weakSequence = recordWeakRawMovementLocked(
                                        source, wallNow, elapsedNow);
                                promoteRawMovementLocked(
                                        source, wallNow, elapsedNow, weakSequence);
                                observedActivity = true;
                            }
                        }
                    }
                }
            }
            if (observedActivity || observedStop) {
                scheduleL3EvidenceExpiryLocked();
                updateL3Hysteresis(elapsedNow);
                maybeReleaseTerminalBarrierForFlowLocked(wallNow, elapsedNow);
                enqueueTransitionLocked(recompute(
                        observedActivity ? "raw-charging-signal" : "raw-charging-stop"));
            }
        }
        if (observedActivity || observedStop) drainFusedTransitions();
    }

    /**
     * Per-source start of the current unbroken run of identical readings; absent when none.
     *
     * <p>Keyed by source because the channel receives interleaved readings from several accessors: a
     * single shared timer was reset by whichever source reported last, so a genuinely steady value could
     * never accumulate its span. Cleared per source on a change, on a non-positive reading, and wholesale
     * on unplug (see {@link #clearRawSignalEvidence}) — without that last one a value frozen from a
     * previous session would keep qualifying as activity.
     */
    private final java.util.HashMap<String, Long> steadyRawSince = new java.util.HashMap<>();
    /** Physical detector epoch owning each plateau run. */
    private final java.util.HashMap<String, Long> steadyRawEpochBySource = new java.util.HashMap<>();

    /** Last SOC seen, and when it was last observed to RISE. */
    private double lastSocSeen = Double.NaN;
    private long socRoseAtMs = 0L;
    private long socRoseAtElapsedMs = 0L;
    /**
     * How recently the gauge must have risen for a steady reading to count as activity.
     *
     * <p>Generous: SOC is 1%-quantised, so on a large pack at a low rate a whole percent can take many
     * minutes. Sized to span that without believing a gauge that stopped moving an hour ago.
     */
    private static final long SOC_RISE_WINDOW_MS = 30 * 60_000L;

    /**
     * Record the gauge reading, noting when it rises.
     *
     * <p>Only RISES matter: a fall is discharge and a flat reading says nothing (the quantum may simply
     * not have been crossed yet), which is why the window above is wide.
     */
    private void noteSocForSteadyEvidence(double soc) {
        if (Double.isNaN(soc) || soc < 0 || soc > 100) return;
        synchronized (rawSignalLock) {
            if (!Double.isNaN(lastSocSeen) && soc > lastSocSeen) {
                socRoseAtMs = System.currentTimeMillis();
                socRoseAtElapsedMs = monotonicNowMs();
                socRiseSequence++;
            }
            lastSocSeen = soc;
        }
    }

    /** True when the gauge has risen inside {@link #SOC_RISE_WINDOW_MS}. Caller holds rawSignalLock. */
    private boolean socRisingRecently(long elapsedNow) {
        return socRoseAtElapsedMs > 0
                && elapsedNow - socRoseAtElapsedMs < SOC_RISE_WINDOW_MS;
    }
    /**
     * How long a positive value must hold perfectly steady before the run itself counts as activity.
     *
     * <p>Long enough that a frozen accessor on a car that is NOT charging does not qualify on its own
     * (the gun and in-park gates still apply above it), short enough to latch L3 inside the first few
     * minutes of a constant-output AC charge.
     */
    private static final long STEADY_RAW_MIN_SPAN_MS = 90_000L;

    /** Public form of {@link #rawSignalMovingRecently}, for the collector's ACC-off gate. */
    public boolean hasRecentRawChargingSignal() {
        return weakRawSignalMovingRecently(monotonicNowMs());
    }

    /**
     * Forget all raw-signal movement evidence. Called on unplug: the remembered values belong to the
     * session that just ended, and a diff against them across a replug is not evidence of anything.
     */
    private void clearRawSignalEvidence() {
        synchronized (rawSignalLock) {
            lastRawSignal.clear();
            clearRawSignalActivityLocked();
        }
    }

    /**
     * A terminal BMS edge ends activity but not the physical connection. Keep each source's last
     * value as a baseline so the first post-FINISHED change can prove taper/restart flow; all
     * timestamps and hysteresis credit are still cleared.
     */
    private void clearRawSignalActivityPreservingBaselines() {
        synchronized (rawSignalLock) {
            clearRawSignalActivityLocked();
        }
    }

    private void clearRawSignalActivityLocked() {
            lastRawSignalMoveMs = 0;
            lastRawSignalMoveElapsedMs = 0;
            rawMoveWallBySource.clear();
            rawMoveElapsedBySource.clear();
            lastCountedRawSequence = rawObservationSequence;
            weakRawMoveWallBySource.clear();
            weakRawMoveElapsedBySource.clear();
            weakRawMoveSequenceBySource.clear();
            lastWeakRawSignalMoveMs = 0L;
            lastWeakRawSignalMoveElapsedMs = 0L;
            lastPromotedWeakRawSequence = weakRawObservationSequence;
            lastRawCorroboratingSocRiseSequence = socRiseSequence;
            lastRawCorroboratingEngineChangeSequence = engineChangeSequence;
            // Steady runs die with the plug too, or a value frozen at its final positive reading keeps
            // presenting itself as activity on a car with no cable in it.
            steadyRawSince.clear();
            steadyRawEpochBySource.clear();
            lastSocSeen = Double.NaN;
            socRoseAtMs = 0L;
            socRoseAtElapsedMs = 0L;
    }

    /** True when some charging channel changed value recently — activity, not a magnitude. */
    private boolean rawSignalMovingRecently(long elapsedNow) {
        synchronized (rawSignalLock) {
            return lastRawSignalMoveElapsedMs > 0
                    && elapsedNow - lastRawSignalMoveElapsedMs < rawSignalMoveWindowMs;
        }
    }

    /** True when a raw charging channel changed after the supplied wall-clock boundary. */
    public boolean hasRawChargingMovementSince(long sinceMs) {
        synchronized (rawSignalLock) {
            if (sinceMs <= 0) return false;
            for (Long movedAt : rawMoveWallBySource.values()) {
                if (movedAt != null && movedAt >= sinceMs) return true;
            }
            return false;
        }
    }

    /** Wall-clock time of the newest promoted movement for one source, or 0. */
    public long getRawChargingMovementAtMs(String source) {
        if (source == null) return 0L;
        synchronized (rawSignalLock) {
            Long movedAt = rawMoveWallBySource.get(source);
            return movedAt != null ? movedAt : 0L;
        }
    }

    private void recordRawMovementLocked(String source, long wallNow, long elapsedNow) {
        rawMoveWallBySource.put(source, wallNow);
        rawMoveElapsedBySource.put(source, elapsedNow);
        if (elapsedNow >= lastRawSignalMoveElapsedMs) {
            lastRawSignalMoveMs = wallNow;
            lastRawSignalMoveElapsedMs = elapsedNow;
        }
    }

    /**
     * Record an ungated movement candidate for collector wake-up. This is deliberately weaker than
     * L3 evidence: stopped-but-plugged rate accessors can jitter, but waking one collection cycle lets
     * the independent pack-flow channel confirm or reject that movement.
     */
    private long recordWeakRawMovementLocked(String source, long wallNow, long elapsedNow) {
        long sequence = ++weakRawObservationSequence;
        weakRawMoveWallBySource.put(source, wallNow);
        weakRawMoveElapsedBySource.put(source, elapsedNow);
        weakRawMoveSequenceBySource.put(source, sequence);
        if (elapsedNow >= lastWeakRawSignalMoveElapsedMs) {
            lastWeakRawSignalMoveMs = wallNow;
            lastWeakRawSignalMoveElapsedMs = elapsedNow;
        }
        return sequence;
    }

    private void promoteRawMovementLocked(String source, long wallNow, long elapsedNow,
                                          long weakSequence) {
        recordRawMovementLocked(source, wallNow, elapsedNow);
        rawObservationSequence++;
        if (weakSequence > lastPromotedWeakRawSequence) {
            lastPromotedWeakRawSequence = weakSequence;
        }
    }

    /**
     * A confirmed counter may prove activity by rising. A rate/unknown source needs independent
     * charging-direction evidence unless an authoritative charging layer is already positive. The
     * one pre-latch exception is a substantial monotonic startup ramp behind a positive gun assertion;
     * small retained-rate oscillations remain weak collector wake-up evidence and cannot latch L3.
     */
    private boolean shouldPromoteRawMovementLocked(String source, double previous, double raw,
                                                   long elapsedNow) {
        double delta = raw - previous;
        com.overdrive.app.byd.ChargeSourceClassifier.Kind kind =
                com.overdrive.app.byd.ChargeSourceClassifier.kindOf(source);
        if (kind == com.overdrive.app.byd.ChargeSourceClassifier.Kind.COUNTER) {
            return delta >= RAW_COUNTER_MIN_RISE;
        }
        if (consumeIndependentCorroborationLocked(elapsedNow, 0L)) return true;

        double materialChange = Math.max(
                RAW_RATE_MIN_ABSOLUTE_CHANGE,
                Math.max(Math.abs(previous), Math.abs(raw)) * RAW_RATE_MIN_RELATIVE_CHANGE);
        if (Math.abs(delta) < materialChange) return false;

        boolean bmsCharging = bmsState
                == ChargingStateData.CHARGING_BATTERY_STATE_CHARGING
                && !bmsStale(elapsedNow);
        boolean l2Fresh = powerIsChargingAtMs > 0
                && elapsedNow - powerIsChargingAtMs <= l2FreshnessMs;
        if (bmsCharging || (l2Fresh && Boolean.TRUE.equals(powerIsChargingTri))) {
            return true;
        }
        if (l2Fresh && Boolean.FALSE.equals(powerIsChargingTri)) return false;

        boolean gunCharging = chargingGunState == 2
                || chargingGunState == 3 || chargingGunState == 4;
        double startupRamp = Math.max(
                RAW_UNCORROBORATED_RAMP_MIN_ABSOLUTE_CHANGE,
                Math.max(Math.abs(previous), Math.abs(raw))
                        * RAW_UNCORROBORATED_RAMP_MIN_RELATIVE_CHANGE);
        return gunCharging && delta >= startupRamp - 1e-9;
    }

    private boolean hasFreshChargingDirectionEngineFlowLocked(long elapsedNow) {
        return enginePowerAtElapsedMs > 0
                && elapsedNow - enginePowerAtElapsedMs < ENGINE_POWER_FRESHNESS_MS
                && enginePowerChangedAtElapsedMs > 0
                && elapsedNow - enginePowerChangedAtElapsedMs < ENGINE_POWER_FRESHNESS_MS
                && !Double.isNaN(enginePowerKw)
                && enginePowerKw < -ENGINE_POWER_DEADBAND;
    }

    /** Spend one distinct post-plateau SoC rise on UNKNOWN -> RATE qualification. */
    private long consumePostPlateauSocRiseLocked(long elapsedNow, long plateauStartedElapsedMs) {
        boolean available = socRisingRecently(elapsedNow)
                && socRoseAtElapsedMs >= plateauStartedElapsedMs
                && socRiseSequence > lastRawCorroboratingSocRiseSequence;
        if (!available) return 0L;
        lastRawCorroboratingSocRiseSequence = socRiseSequence;
        return socRoseAtMs;
    }

    /**
     * Spend each independent engine/SOC event at most once on raw-channel promotion.
     *
     * <p>{@code notBeforeElapsedMs} binds a steady rate to evidence produced after that steady run
     * began. A SOC rise from the charge that just stopped therefore cannot make its frozen final
     * rate, or a frozen cumulative counter, look live for another thirty minutes.
     */
    private boolean consumeIndependentCorroborationLocked(long elapsedNow,
                                                          long notBeforeElapsedMs) {
        boolean engineAvailable = hasFreshChargingDirectionEngineFlowLocked(elapsedNow)
                && enginePowerChangedAtElapsedMs >= notBeforeElapsedMs
                && engineChangeSequence > lastRawCorroboratingEngineChangeSequence;
        boolean socAvailable = socRisingRecently(elapsedNow)
                && socRoseAtElapsedMs >= notBeforeElapsedMs
                && socRiseSequence > lastRawCorroboratingSocRiseSequence;
        if (!engineAvailable && !socAvailable) return false;
        if (engineAvailable) {
            lastRawCorroboratingEngineChangeSequence = engineChangeSequence;
        }
        if (socAvailable) {
            lastRawCorroboratingSocRiseSequence = socRiseSequence;
        }
        return true;
    }

    private void removeRawMovementLocked(String source) {
        rawMoveWallBySource.remove(source);
        rawMoveElapsedBySource.remove(source);
        lastRawSignalMoveMs = 0L;
        lastRawSignalMoveElapsedMs = 0L;
        for (java.util.Map.Entry<String, Long> entry : rawMoveElapsedBySource.entrySet()) {
            Long elapsed = entry.getValue();
            if (elapsed != null && elapsed >= lastRawSignalMoveElapsedMs) {
                lastRawSignalMoveElapsedMs = elapsed;
                Long wall = rawMoveWallBySource.get(entry.getKey());
                lastRawSignalMoveMs = wall != null ? wall : 0L;
            }
        }
    }

    private void removeWeakRawMovementLocked(String source) {
        weakRawMoveWallBySource.remove(source);
        weakRawMoveElapsedBySource.remove(source);
        weakRawMoveSequenceBySource.remove(source);
        lastWeakRawSignalMoveMs = 0L;
        lastWeakRawSignalMoveElapsedMs = 0L;
        for (java.util.Map.Entry<String, Long> entry : weakRawMoveElapsedBySource.entrySet()) {
            Long elapsed = entry.getValue();
            if (elapsed != null && elapsed >= lastWeakRawSignalMoveElapsedMs) {
                lastWeakRawSignalMoveElapsedMs = elapsed;
                Long wall = weakRawMoveWallBySource.get(entry.getKey());
                lastWeakRawSignalMoveMs = wall != null ? wall : 0L;
            }
        }
    }

    private boolean weakRawSignalMovingRecently(long elapsedNow) {
        synchronized (rawSignalLock) {
            return lastWeakRawSignalMoveElapsedMs > 0
                    && elapsedNow - lastWeakRawSignalMoveElapsedMs < rawSignalMoveWindowMs;
        }
    }

    /** True when the independent pack-flow value itself changed after the supplied boundary. */
    public boolean hasEnginePowerChangedSince(long sinceMs) {
        synchronized (lock) {
            return sinceMs > 0 && enginePowerChangedAtMs >= sinceMs;
        }
    }

    private final Object rawSignalLock = new Object();
    private final java.util.HashMap<String, Double> lastRawSignal = new java.util.HashMap<>();
    private final java.util.HashMap<String, Long> rawMoveWallBySource =
            new java.util.HashMap<>();
    private final java.util.HashMap<String, Long> rawMoveElapsedBySource =
            new java.util.HashMap<>();
    private final java.util.HashMap<String, Long> weakRawMoveWallBySource =
            new java.util.HashMap<>();
    private final java.util.HashMap<String, Long> weakRawMoveElapsedBySource =
            new java.util.HashMap<>();
    private final java.util.HashMap<String, Long> weakRawMoveSequenceBySource =
            new java.util.HashMap<>();
    private long lastRawSignalMoveMs = 0;
    private long lastRawSignalMoveElapsedMs = 0;
    private long lastWeakRawSignalMoveMs = 0;
    private long lastWeakRawSignalMoveElapsedMs = 0;
    private long rawObservationSequence = 0L;
    private long lastCountedRawSequence = 0L;
    private long weakRawObservationSequence = 0L;
    private long lastPromotedWeakRawSequence = 0L;
    private long socRiseSequence = 0L;
    private long lastRawCorroboratingSocRiseSequence = 0L;
    private long engineChangeSequence = 0L;
    private long lastCountedEngineChangeSequence = 0L;
    private long lastRawCorroboratingEngineChangeSequence = 0L;
    /**
     * How recently a raw charging channel must have moved to count as activity. Generous because the
     * parked poll is 90 s, so two consecutive observations can be that far apart.
     */
    private static final long RAW_SIGNAL_MOVE_WINDOW_MS = 2 * 60_000L;
    /** Minimum real rise on a documented/confirmed kWh counter (half of a 1 Wh quantum). */
    private static final double RAW_COUNTER_MIN_RISE = 0.0005;
    /** Minimum uncorroborated change for ordinary kW-scale rate values. */
    private static final double RAW_RATE_MIN_ABSOLUTE_CHANGE = 0.05;
    /** Relative floor for raw/hectowatt values, where a fixed 0.05 would admit sensor jitter. */
    private static final double RAW_RATE_MIN_RELATIVE_CHANGE = 0.01;
    /** A raw-only bootstrap must look like charger ramp-up, not retained-rate oscillation. */
    private static final double RAW_UNCORROBORATED_RAMP_MIN_ABSOLUTE_CHANGE = 0.10;
    private static final double RAW_UNCORROBORATED_RAMP_MIN_RELATIVE_CHANGE = 0.05;

    public void updatePowerIsCharging(Boolean tri) {
        synchronized (lock) {
            applyPowerIsChargingLocked(tri, monotonicNowMs());
            enqueueTransitionLocked(recompute("power-isCharging"));
        }
        drainFusedTransitions();
    }

    private void applyPowerIsChargingLocked(Boolean tri, long elapsedNow) {
        if (terminalSessionBarrier || disconnectedLatched || v2lActive
                || hasPendingTerminalCallbackLocked()
                || isAuthoritativeOffBmsState(bmsState) || tri == null) {
            powerIsChargingTri = null;
            powerIsChargingAtMs = 0L;
            invalidateL2TimerLocked();
            return;
        }
        powerIsChargingTri = tri;
        powerIsChargingAtMs = elapsedNow;
        scheduleL2ExpiryLocked();
    }

    /**
     * Push the latest poll snapshot into the detector. Called once per
     * collect cycle by the collector. Used for L3 inference + log
     * diagnostics.
     *
     * @param vd may be null — treated as "no fresh evidence this cycle"
     */
    public void updatePollEvidence(BydVehicleData vd, int gearMode, int gearP) {
        if (vd != null && vd.chargingState != BydVehicleData.UNAVAILABLE) {
            // Legacy/test entry point has no version proof that this field came from a current
            // synchronous read. Treat it as an ordinary edge; production uses updatePollObservation.
            updateBmsState(vd.chargingState);
        }
        updatePollObservation(vd, gearMode, gearP,
                false, false, false, BydVehicleData.UNAVAILABLE, false, null);
    }

    /**
     * Apply one version-validated hardware poll as a single observation. No intermediate
     * connection/BMS/L2 recompute is allowed to consume or decrement L3 hysteresis.
     */
    public void updatePollObservation(BydVehicleData vd, int gearMode, int gearP,
                                      boolean connectionObserved, boolean typeObserved,
                                      boolean bmsObserved, int observedBmsState,
                                      boolean powerObserved, Boolean observedPowerIsCharging) {
        if (vd == null) return;
        synchronized (lock) {
            long wallNow = System.currentTimeMillis();
            long elapsedNow = monotonicNowMs();
            boolean wasAuthoritativeOff = disconnectedLatched || v2lActive;
            boolean bmsAuthoritativeStop = false;

            resolveDelayedTerminalWithCohesivePollLocked(
                    vd, connectionObserved, typeObserved,
                    bmsObserved, observedBmsState,
                    powerObserved, observedPowerIsCharging);
            if (terminalSessionBarrier && !disconnectedLatched && !v2lActive
                    && ((bmsObserved
                            && observedBmsState
                                    != ChargingStateData.CHARGING_BATTERY_STATE_CHARGING)
                        || (powerObserved
                            && Boolean.FALSE.equals(observedPowerIsCharging)))) {
                scheduledRestartLevelArmed = true;
            }
            if (isConfirmedScheduledRestartPollLocked(
                    vd, connectionObserved, typeObserved, bmsObserved, observedBmsState,
                    powerObserved, observedPowerIsCharging)) {
                // These three values belong to one version-validated hardware observation. Admit
                // the new epoch before applying them individually so the barrier cannot discard L2
                // and deadlock a scheduled restart. Source-less callbacks still cannot enter here.
                releaseTerminalBarrierLocked("atomic scheduled-restart poll");
            }
            noteSocForSteadyEvidence(vd.socPercent);
            chargingGunState = vd.chargingGunState;
            if (connectionObserved) {
                if (vd.chargingGunState == 1) {
                    markDisconnectedLocked(wallNow, elapsedNow);
                } else if (vd.chargingGunState == 5) {
                    markV2lLocked(wallNow, elapsedNow);
                } else if (vd.chargingGunState == 2 || vd.chargingGunState == 3
                        || vd.chargingGunState == 4) {
                    confirmPositiveConnectionLocked(vd.chargingGunState, wallNow, elapsedNow);
                }
            }
            if (typeObserved) {
                confirmV2lStateLocked(vd.vtolCharging, wallNow, elapsedNow);
            }

            // Derive the session boundary only after a positive connection observation has had the
            // chance to confirm a reconnect and install its wall-clock floor. A current CHARGING
            // poll that starts a new epoch is itself fresh evidence; its snapshot transition stamp
            // may still belong to the previous same-valued session and must not move the start back.
            pendingSessionEvidenceAtMs = earliestLivePollEvidenceAtMs(vd, wallNow);
            if (bmsObserved
                    && observedBmsState
                            == ChargingStateData.CHARGING_BATTERY_STATE_CHARGING
                    && activeSessionEpoch != sessionEpoch) {
                pendingSessionEvidenceAtMs = wallNow;
            }
            if (pendingSessionEvidenceAtMs > 0 && lastPlugConnectedMs > 0) {
                pendingSessionEvidenceAtMs =
                        Math.max(pendingSessionEvidenceAtMs, lastPlugConnectedMs);
            }
            if (fusedCharging && pendingSessionEvidenceAtMs > 0
                    && pendingSessionEvidenceAtMs < lastSessionStartedAtMs
                    && wallNow - lastSessionStartedAtMs <= 5_000L) {
                // A poll's BMS/L2 confirmation is delivered just before this snapshot. If that
                // confirmation created the ON edge, include the samples collected by the same poll
                // instead of rejecting them as "before this session" by a few milliseconds.
                lastSessionStartedAtMs = pendingSessionEvidenceAtMs;
            }

            if (bmsObserved) {
                BmsApplyResult result = applyBmsStateLocked(
                        observedBmsState, true, wallNow, elapsedNow);
                bmsAuthoritativeStop = result.authoritativeStop;
            } else if (bmsState == ChargingStateData.CHARGING_BATTERY_STATE_CHARGING
                    && bmsStale(elapsedNow)) {
                logger.warn("BMS state unavailable for " + BMS_FRESHNESS_MS / 1000
                        + "s while holding CHARGING - discarding stale verdict");
                bmsState = BydVehicleData.UNAVAILABLE;
                bmsStateAtMs = 0L;
                bmsStateAtElapsedMs = 0L;
                invalidateBmsTimerLocked();
            }

            if (powerObserved) {
                applyPowerIsChargingLocked(observedPowerIsCharging, elapsedNow);
            }

            inPark = isObservedParkGear(gearMode, gearP);
            noteEnginePowerChangeLocked(vd, wallNow, elapsedNow);

            boolean evidenceAllowed = !disconnectedLatched && !v2lActive
                    && !hasPendingTerminalCallbackLocked()
                    && !isAuthoritativeOffBmsState(bmsState);
            if (evidenceAllowed && !Double.isNaN(vd.enginePowerKw)) {
                enginePowerKw = vd.enginePowerKw;
                enginePowerAtMs = vd.enginePowerAtMs > 0 ? vd.enginePowerAtMs : wallNow;
                enginePowerAtElapsedMs = sourceWallTimeToElapsed(
                        enginePowerAtMs, wallNow, elapsedNow);
            } else if (!evidenceAllowed) {
                invalidateAccDependentSignals();
            }
            if (evidenceAllowed) {
                externalChargingPowerKw = vd.externalChargingPowerKw;
                chargingPowerKw = vd.chargingPowerKw;
            } else {
                externalChargingPowerKw = Double.NaN;
                chargingPowerKw = Double.NaN;
            }
            promoteLatestWeakRawMovementIfCorroboratedLocked(elapsedNow);
            maybeReleaseTerminalBarrierForFlowLocked(wallNow, elapsedNow);

            updateL3Hysteresis(elapsedNow);
            scheduleL3EvidenceExpiryLocked();
            enqueueTransitionLocked(recompute("poll"));
            boolean newlyAuthoritativeOff = !wasAuthoritativeOff
                    && (disconnectedLatched || v2lActive);
            if (newlyAuthoritativeOff || bmsAuthoritativeStop) {
                enqueueTransitionLocked(FusedTransition.authoritativeStop(
                        v2lActive || bmsAuthoritativeStop ? "v2l-export" : "edge-unplug"));
            }
            pendingSessionEvidenceAtMs = 0L;
        }
        drainFusedTransitions();
    }

    static boolean isObservedParkGear(int gearMode, int gearP) {
        return gearMode != BydVehicleData.UNAVAILABLE && gearMode == gearP;
    }

    /**
     * Two complete current polls may outweigh an identity-less terminal callback. After OFF, that
     * recovery remains available only if no cohesive positive was seen before the stop committed;
     * otherwise unchanged cached levels could straddle the boundary and manufacture a new session.
     */
    private void resolveDelayedTerminalWithCohesivePollLocked(
            BydVehicleData vd,
            boolean connectionObserved, boolean typeObserved,
            boolean bmsObserved, int observedBmsState,
            boolean powerObserved, Boolean observedPowerIsCharging) {
        boolean pendingCandidate = hasPendingTerminalCallbackLocked();
        boolean recoverableCallbackBarrier = terminalSessionBarrier
                && terminalBarrierAllowsCohesiveRecovery
                && !disconnectedLatched && !v2lActive;
        if (!pendingCandidate && !recoverableCallbackBarrier) {
            pendingTerminalPositivePollConfirmations = 0;
            return;
        }
        boolean chargingConnection = vd.chargingGunState == 2
                || vd.chargingGunState == 3 || vd.chargingGunState == 4;
        boolean cohesivePositive = connectionObserved
                && chargingConnection
                && (!typeObserved || !vd.vtolCharging)
                && bmsObserved
                && observedBmsState
                        == ChargingStateData.CHARGING_BATTERY_STATE_CHARGING
                && powerObserved
                && Boolean.TRUE.equals(observedPowerIsCharging);
        if (!cohesivePositive) {
            pendingTerminalPositivePollConfirmations = 0;
            return;
        }
        pendingTerminalPositivePollConfirmations++;
        if (pendingTerminalPositivePollConfirmations
                < POSITIVE_CONNECTION_CONFIRMATIONS_REQUIRED) {
            return;
        }
        if (recoverableCallbackBarrier) {
            releaseTerminalBarrierLocked(
                    "two cohesive polls contradicted delayed terminal callback");
        } else {
            clearPendingTerminalCallbackLocked();
        }
        pendingOffSinceMs = 0L;
        invalidateOffTimerLocked();
    }

    private boolean isConfirmedScheduledRestartPollLocked(
            BydVehicleData vd, boolean connectionObserved, boolean typeObserved,
            boolean bmsObserved, int observedBmsState, boolean powerObserved,
            Boolean observedPowerIsCharging) {
        boolean chargingConnection = vd.chargingGunState == 2
                || vd.chargingGunState == 3 || vd.chargingGunState == 4;
        return terminalSessionBarrier && scheduledRestartLevelArmed
                && !disconnectedLatched && !v2lActive
                && connectionObserved && chargingConnection
                && (!typeObserved || !vd.vtolCharging)
                && bmsObserved
                && observedBmsState == ChargingStateData.CHARGING_BATTERY_STATE_CHARGING
                && powerObserved && Boolean.TRUE.equals(observedPowerIsCharging);
    }

    private static long earliestLivePollEvidenceAtMs(BydVehicleData vd, long now) {
        long earliest = now;
        boolean found = false;
        if (!Double.isNaN(vd.enginePowerKw) && vd.enginePowerAtMs > 0
                && vd.enginePowerAtMs <= now + 1_000L
                && now - vd.enginePowerAtMs <= ENGINE_POWER_FRESHNESS_MS) {
            earliest = Math.min(earliest, vd.enginePowerAtMs);
            found = true;
        }
        if (!Double.isNaN(vd.clusterChargePowerKw) && vd.clusterChargePowerAtMs > 0
                && vd.clusterChargePowerAtMs <= now + 1_000L
                && now - vd.clusterChargePowerAtMs <= ENGINE_POWER_FRESHNESS_MS) {
            earliest = Math.min(earliest, vd.clusterChargePowerAtMs);
            found = true;
        }
        if (vd.chargingState == ChargingStateData.CHARGING_BATTERY_STATE_CHARGING
                && vd.chargingStateAtMs > 0
                && vd.chargingStateAtMs <= now + 1_000L
                && now - vd.chargingStateAtMs <= BMS_FRESHNESS_MS) {
            earliest = Math.min(earliest, vd.chargingStateAtMs);
            found = true;
        }
        return found ? earliest : 0L;
    }

    private void noteEnginePowerChangeLocked(BydVehicleData vd, long wallNow, long elapsedNow) {
        if (Double.isNaN(vd.enginePowerKw)) return;
        if (Double.isNaN(lastEnginePowerObservedKw)
                || Math.abs(vd.enginePowerKw - lastEnginePowerObservedKw) >= 0.05) {
            enginePowerChangedAtMs = vd.enginePowerAtMs > 0
                    ? Math.min(wallNow, vd.enginePowerAtMs) : wallNow;
            enginePowerChangedAtElapsedMs = sourceWallTimeToElapsed(
                    enginePowerChangedAtMs, wallNow, elapsedNow);
            engineChangeSequence++;
        }
        lastEnginePowerObservedKw = vd.enginePowerKw;
    }

    static long sourceWallTimeToElapsed(long sourceWallMs, long wallNowMs, long elapsedNowMs) {
        if (sourceWallMs <= 0 || sourceWallMs > wallNowMs) return elapsedNowMs;
        long ageMs = wallNowMs - sourceWallMs;
        if (ageMs >= elapsedNowMs) return 1L;
        return elapsedNowMs - ageMs;
    }

    /**
     * Upgrade the newest weak raw candidate after this poll supplied independent charging flow. This
     * closes the bootstrap loop: raw movement wakes the parked engine collection, then the engine/SOC
     * result grants L3 credit without waiting for another raw callback.
     */
    private boolean promoteLatestWeakRawMovementIfCorroboratedLocked(long elapsedNow) {
        synchronized (rawSignalLock) {
            String newestSource = null;
            long newestSequence = lastPromotedWeakRawSequence;
            for (java.util.Map.Entry<String, Long> entry
                    : weakRawMoveSequenceBySource.entrySet()) {
                Long sequence = entry.getValue();
                Long movedAt = weakRawMoveElapsedBySource.get(entry.getKey());
                if (sequence != null && sequence > newestSequence
                        && movedAt != null && elapsedNow - movedAt < rawSignalMoveWindowMs) {
                    newestSequence = sequence;
                    newestSource = entry.getKey();
                }
            }
            if (newestSource == null) return false;
            long movedElapsed = weakRawMoveElapsedBySource.get(newestSource);
            Long plateauSince = steadyRawSince.get(newestSource);
            boolean unknownPlateauNeedsSocProof = plateauSince != null
                    && elapsedNow - plateauSince >= steadyRawMinSpanMs
                    && com.overdrive.app.byd.ChargeSourceClassifier.kindOf(newestSource)
                            == com.overdrive.app.byd.ChargeSourceClassifier.Kind.UNKNOWN
                    && socRisingRecently(elapsedNow)
                    && socRoseAtElapsedMs >= plateauSince
                    && socRiseSequence > lastRawCorroboratingSocRiseSequence;
            if (unknownPlateauNeedsSocProof) {
                // The next repeated raw observation must hand this same rise to the source
                // classifier. Spending it here would promote the old ramp but leave a genuinely
                // live, steady UNKNOWN source permanently unclassifiable.
                return false;
            }
            if (!consumeIndependentCorroborationLocked(elapsedNow, movedElapsed)) return false;
            Long movedWallValue = weakRawMoveWallBySource.get(newestSource);
            long movedWall = movedWallValue != null ? movedWallValue : System.currentTimeMillis();
            promoteRawMovementLocked(newestSource, movedWall, movedElapsed, newestSequence);
            return true;
        }
    }

    /** Called when ACC transitions on/off. */
    public void updateAccState(boolean isOn) {
        synchronized (lock) {
            this.accIsOn = isOn;
            if (!isOn) {
                // Preserve a currently-fresh sample through the ACC transition. Its source timestamp
                // still expires at ENGINE_POWER_FRESHNESS_MS, while the immediate parked collection
                // gets a chance to replace it. Clearing it here dropped valid engine-only PHEV
                // charging for one poll and emitted a false stop/start pair.
                scheduleL3EvidenceExpiryLocked();
            }
            enqueueTransitionLocked(recompute("acc-" + (isOn ? "on" : "off")));
        }
        drainFusedTransitions();
    }

    /**
     * Accept a fused verdict produced by the process that owns the vehicle
     * telemetry connection. This bypasses local evidence fusion without
     * bypassing the existing ordered listener dispatch.
     */
    public void acceptExternalVerdict(
            boolean charging, String source, long freshnessMs) {
        synchronized (lock) {
            boolean previous = fusedCharging;
            if (!externalVerdictActive) {
                clearLocalEvidenceForExternalOwnerLocked();
            }
            externalVerdictActive = true;
            externalVerdictCharging = charging;
            externalVerdictAtElapsedMs = monotonicNowMs();
            externalVerdictFreshnessMs = Math.max(1L, freshnessMs);
            externalVerdictSource = source == null || source.isEmpty()
                    ? "external-telemetry" : source;
            pendingOffSinceMs = 0L;
            invalidateOffTimerLocked();
            fusedCharging = charging;
            fusedAtMs = System.currentTimeMillis();
            fusedSource = externalVerdictSource;
            if (charging) {
                disconnectedLatched = false;
                v2lActive = false;
                terminalSessionBarrier = false;
                terminalBarrierAllowsCohesiveRecovery = false;
                terminalBarrierSinceMs = 0L;
                terminalBarrierSinceElapsedMs = 0L;
                clearPendingTerminalCallbackLocked();
                if (!previous) {
                    lastSessionStartedAtMs = fusedAtMs;
                    activeSessionEpoch = sessionEpoch;
                }
            } else {
                activeSessionEpoch = 0L;
            }
            scheduleExternalVerdictExpiryLocked();
            updatePublicationGenerationLocked();
            if (charging != previous) {
                enqueueTransitionLocked(new FusedTransition(
                        true, charging, fusedSource, false));
            }
        }
        drainFusedTransitions();
    }

    public void clearExternalVerdict() {
        synchronized (lock) {
            if (!externalVerdictActive) return;
            externalVerdictActive = false;
            externalVerdictAtElapsedMs = 0L;
            externalVerdictTimerGeneration++;
            enqueueTransitionLocked(recompute("external-verdict-cleared"));
        }
        drainFusedTransitions();
    }

    private void clearLocalEvidenceForExternalOwnerLocked() {
        bmsState = BydVehicleData.UNAVAILABLE;
        bmsStateAtMs = 0L;
        bmsStateAtElapsedMs = 0L;
        powerIsChargingTri = null;
        powerIsChargingAtMs = 0L;
        enginePowerKw = Double.NaN;
        enginePowerAtMs = 0L;
        enginePowerAtElapsedMs = 0L;
        externalChargingPowerKw = Double.NaN;
        chargingPowerKw = Double.NaN;
        inferenceHysteresis = 0;
        l3Latched = false;
        l1L2DisagreementSinceMs = 0L;
        clearRawSignalEvidence();
        clearEnginePowerChangeBaselineLocked();
        invalidateBmsTimerLocked();
        invalidateL2TimerLocked();
        invalidateL3EvidenceTimerLocked();
        invalidateDisagreementTimerLocked();
    }

    /**
     * Invalidate signals that go stale when ACC is off. Called on ACC OFF
     * AND on ACTION_POWER_DISCONNECTED.
     */
    private void invalidateAccDependentSignals() {
        enginePowerKw = Double.NaN;
        enginePowerAtMs = 0L;
        enginePowerAtElapsedMs = 0L;
        scheduleL3EvidenceExpiryLocked();
    }

    /**
     * Drop evidence belonging to the session that an authoritative BMS state just ended.
     *
     * <p>FINISHED is often followed by ambiguous IDLE. Without this reset, that IDLE evaluation
     * could immediately reuse the previous session's held L2=true or recent raw movement and
     * resurrect charging. Fresh evidence remains able to start a scheduled charge later.
     */
    private void clearCompletedSessionEvidence() {
        clearRawSignalActivityPreservingBaselines();
        invalidateAccDependentSignals();
        externalChargingPowerKw = Double.NaN;
        chargingPowerKw = Double.NaN;
        powerIsChargingTri = null;
        powerIsChargingAtMs = 0L;
        lastPlugConnectedMs = 0L;
        lastPlugConnectedElapsedMs = 0L;
        inferenceHysteresis = 0;
        l3Latched = false;
        l1L2DisagreementSinceMs = 0L;
        invalidateDisagreementTimerLocked();
        pendingOffSinceMs = 0L;
        invalidateOffTimerLocked();
        invalidateL2TimerLocked();
        invalidateL3EvidenceTimerLocked();
    }

    private void enterTerminalBarrierLocked(long wallNow, long elapsedNow) {
        terminalSessionBarrier = true;
        terminalBarrierAllowsCohesiveRecovery = false;
        scheduledRestartLevelArmed = false;
        terminalBarrierSinceMs = wallNow;
        terminalBarrierSinceElapsedMs = elapsedNow;
        activeSessionEpoch = 0L;
        clearPendingTerminalCallbackLocked();
        enginePowerChangedAtMs = 0L;
        enginePowerChangedAtElapsedMs = 0L;
        clearCompletedSessionEvidence();
    }

    private void markDisconnectedLocked(long wallNow, long elapsedNow) {
        if (!disconnectedLatched) logger.info("Plug state: DISCONNECTED");
        disconnectedLatched = true;
        v2lActive = false;
        terminalSessionBarrier = true;
        terminalBarrierAllowsCohesiveRecovery = false;
        scheduledRestartLevelArmed = false;
        terminalBarrierSinceMs = wallNow;
        terminalBarrierSinceElapsedMs = elapsedNow;
        activeSessionEpoch = 0L;
        clearPendingTerminalCallbackLocked();
        lastPlugDisconnectedMs = wallNow;
        lastPlugConnectedMs = 0L;
        lastPlugConnectedElapsedMs = 0L;
        resetPositiveConnectionConfirmationLocked();
        clearCompletedSessionEvidence();
        clearRawSignalEvidence();
        clearEnginePowerChangeBaselineLocked();
        if (bmsState == ChargingStateData.CHARGING_BATTERY_STATE_CHARGING) {
            bmsState = BydVehicleData.UNAVAILABLE;
            bmsStateAtMs = 0L;
            bmsStateAtElapsedMs = 0L;
            invalidateBmsTimerLocked();
        }
    }

    private void markV2lLocked(long wallNow, long elapsedNow) {
        if (!v2lActive) logger.info("Plug state: V2L export");
        v2lActive = true;
        disconnectedLatched = false;
        terminalSessionBarrier = true;
        terminalBarrierAllowsCohesiveRecovery = false;
        scheduledRestartLevelArmed = false;
        terminalBarrierSinceMs = wallNow;
        terminalBarrierSinceElapsedMs = elapsedNow;
        activeSessionEpoch = 0L;
        clearPendingTerminalCallbackLocked();
        lastPlugConnectedMs = 0L;
        lastPlugConnectedElapsedMs = 0L;
        v2lExitConfirmations = 0;
        resetPositiveConnectionConfirmationLocked();
        clearCompletedSessionEvidence();
        clearRawSignalEvidence();
        clearEnginePowerChangeBaselineLocked();
        if (bmsState == ChargingStateData.CHARGING_BATTERY_STATE_CHARGING) {
            bmsState = BydVehicleData.UNAVAILABLE;
            bmsStateAtMs = 0L;
            bmsStateAtElapsedMs = 0L;
            invalidateBmsTimerLocked();
        }
    }

    private void markConnectedLocked(long wallNow, long elapsedNow) {
        boolean newConnection = disconnectedLatched || v2lActive;
        resetPositiveConnectionConfirmationLocked();
        disconnectedLatched = false;
        v2lActive = false;
        lastPlugDisconnectedMs = 0L;
        if (newConnection) {
            clearCompletedSessionEvidence();
            clearRawSignalEvidence();
            clearEnginePowerChangeBaselineLocked();
            // The previous connection's terminal BMS verdict has no authority in this epoch. A
            // current callback/poll will repopulate it; retaining FINISHED here blocked every L2
            // and flow input on a genuine reconnect.
            if (isAuthoritativeOffBmsState(bmsState)) {
                bmsState = BydVehicleData.UNAVAILABLE;
                bmsStateAtMs = 0L;
                bmsStateAtElapsedMs = 0L;
                invalidateBmsTimerLocked();
            }
            releaseTerminalBarrierLocked("physical reconnect");
            lastPlugConnectedMs = wallNow;
            lastPlugConnectedElapsedMs = elapsedNow;
            logger.info("Plug state: charging connection observed");
        }
    }

    private void applyConnectionStateLocked(int gunState, boolean vtol,
                                            long wallNow, long elapsedNow) {
        chargingGunState = gunState;
        if (gunState == 1) {
            markDisconnectedLocked(wallNow, elapsedNow);
        } else if (vtol || gunState == 5) {
            markV2lLocked(wallNow, elapsedNow);
        } else if (gunState == 2 || gunState == 3 || gunState == 4) {
            // A positive callback is only a reconnect candidate. HAL callbacks can be delivered
            // late; accepting one here lets an old pre-unplug gun=2 clear an authoritative gun=1.
            // Count it as candidate one, then require one matching current versioned poll. Duplicate
            // callbacks never advance the count, so callbacks alone cannot clear authoritative OFF.
            recordPositiveConnectionCandidateLocked(gunState);
        }
    }

    private void releaseTerminalBarrierLocked(String reason) {
        boolean released = terminalSessionBarrier;
        terminalSessionBarrier = false;
        terminalBarrierAllowsCohesiveRecovery = false;
        terminalBarrierSinceMs = 0L;
        terminalBarrierSinceElapsedMs = 0L;
        scheduledRestartLevelArmed = false;
        activeSessionEpoch = 0L;
        clearPendingTerminalCallbackLocked();
        powerIsChargingTri = null;
        powerIsChargingAtMs = 0L;
        invalidateL2TimerLocked();
        if (released) sessionEpoch++;
        logger.info("Completed-session barrier released by " + reason);
    }

    private void resetPositiveConnectionConfirmationLocked() {
        positiveConnectionConfirmations = 0;
        positiveConnectionCandidate = BydVehicleData.UNAVAILABLE;
    }

    private void clearEnginePowerChangeBaselineLocked() {
        lastEnginePowerObservedKw = Double.NaN;
        enginePowerChangedAtMs = 0L;
        enginePowerChangedAtElapsedMs = 0L;
        lastCountedEngineChangeSequence = engineChangeSequence;
    }

    private void recordPositiveConnectionCandidateLocked(int gunState) {
        if (!disconnectedLatched && !v2lActive) return;
        positiveConnectionCandidate = gunState;
        positiveConnectionConfirmations = 1;
    }

    private void confirmPositiveConnectionLocked(int gunState, long wallNow, long elapsedNow) {
        if (!disconnectedLatched && !v2lActive) {
            resetPositiveConnectionConfirmationLocked();
            return;
        }
        if (positiveConnectionCandidate != gunState) {
            // A synchronous level confirms a reconnect nominated by an edge; it cannot nominate
            // one itself. Repeated cached gun=2 polls therefore cannot clear gun-out/V2L.
            resetPositiveConnectionConfirmationLocked();
            return;
        }
        positiveConnectionConfirmations++;
        if (positiveConnectionConfirmations >= POSITIVE_CONNECTION_CONFIRMATIONS_REQUIRED) {
            markConnectedLocked(wallNow, elapsedNow);
        }
    }

    private void maybeReleaseTerminalBarrierForFlowLocked(long wallNow, long elapsedNow) {
        if (!terminalSessionBarrier || disconnectedLatched || v2lActive
                || isAuthoritativeOffBmsState(bmsState)) {
            return;
        }
        boolean gunCharging = chargingGunState == 2 || chargingGunState == 3
                || chargingGunState == 4;
        boolean engineFresh = enginePowerAtElapsedMs > 0
                && elapsedNow - enginePowerAtElapsedMs < ENGINE_POWER_FRESHNESS_MS
                && enginePowerChangedAtElapsedMs >= terminalBarrierSinceElapsedMs
                && !Double.isNaN(enginePowerKw) && enginePowerKw < -ENGINE_POWER_DEADBAND;
        boolean engineChanged = enginePowerChangedAtElapsedMs >= terminalBarrierSinceElapsedMs;
        boolean rawMoving;
        boolean socRose;
        synchronized (rawSignalLock) {
            rawMoving = lastRawSignalMoveElapsedMs >= terminalBarrierSinceElapsedMs
                    && lastRawSignalMoveElapsedMs > 0
                    && elapsedNow - lastRawSignalMoveElapsedMs < rawSignalMoveWindowMs;
            socRose = socRoseAtElapsedMs >= terminalBarrierSinceElapsedMs
                    && socRisingRecently(elapsedNow);
        }
        // A freshly timestamped engine getter is not sufficient: some firmware keeps returning the
        // final negative value after charging stops and the builder would stamp that stale level as
        // new on every poll. Require a charging channel to move as independent proof of delivery.
        if (gunCharging && rawMoving && ((engineFresh && engineChanged) || socRose)) {
            releaseTerminalBarrierLocked("corroborated fresh post-barrier pack flow");
        }
    }

    /** Immediate gun/mode edge from the collector listener. */
    public void updateConnectionState(int gunState, boolean vtol) {
        synchronized (lock) {
            boolean wasAuthoritativeOff = disconnectedLatched || v2lActive;
            applyConnectionStateLocked(gunState, vtol,
                    System.currentTimeMillis(), monotonicNowMs());
            boolean newlyAuthoritativeOff = !wasAuthoritativeOff
                    && (disconnectedLatched || v2lActive);
            enqueueTransitionLocked(recompute("connection-edge"));
            if (newlyAuthoritativeOff) {
                enqueueTransitionLocked(FusedTransition.authoritativeStop(
                        vtol || gunState == 5 ? "v2l-export" : "edge-unplug"));
            }
        }
        drainFusedTransitions();
    }

    /** Collector-proven pack inflow contradicts a gun-out latch; treated as a physical reconnect. */
    public boolean releaseGunOutForProvenPackFlow() {
        synchronized (lock) {
            if (!disconnectedLatched || v2lActive) return false;
            logger.info("Plug state: gun-out latch released by proven pack inflow");
            markConnectedLocked(System.currentTimeMillis(), monotonicNowMs());
            enqueueTransitionLocked(recompute("pack-flow-override"));
        }
        drainFusedTransitions();
        return true;
    }

    /** Current versioned poll confirmation; unlike a callback, a positive state may clear OFF. */
    public void confirmConnectionState(int gunState, boolean vtol) {
        synchronized (lock) {
            long wallNow = System.currentTimeMillis();
            long elapsedNow = monotonicNowMs();
            boolean wasAuthoritativeOff = disconnectedLatched || v2lActive;
            chargingGunState = gunState;
            if (gunState == 1) {
                markDisconnectedLocked(wallNow, elapsedNow);
            } else if (vtol || gunState == 5) {
                markV2lLocked(wallNow, elapsedNow);
            } else if (gunState == 2 || gunState == 3 || gunState == 4) {
                confirmPositiveConnectionLocked(gunState, wallNow, elapsedNow);
            }
            boolean newlyAuthoritativeOff = !wasAuthoritativeOff
                    && (disconnectedLatched || v2lActive);
            enqueueTransitionLocked(recompute("connection-confirmed"));
            if (newlyAuthoritativeOff) {
                enqueueTransitionLocked(FusedTransition.authoritativeStop(
                        vtol || gunState == 5 ? "v2l-export" : "edge-unplug"));
            }
        }
        drainFusedTransitions();
    }

    /** Current charging-type poll, used when the gun getter is unavailable. */
    public void confirmV2lState(boolean vtol) {
        synchronized (lock) {
            long wallNow = System.currentTimeMillis();
            long elapsedNow = monotonicNowMs();
            boolean wasV2l = v2lActive;
            confirmV2lStateLocked(vtol, wallNow, elapsedNow);
            enqueueTransitionLocked(recompute("v2l-mode-confirmed"));
            if (!wasV2l && v2lActive) {
                enqueueTransitionLocked(FusedTransition.authoritativeStop("v2l-export"));
            }
        }
        drainFusedTransitions();
    }

    private void confirmV2lStateLocked(boolean vtol, long wallNow, long elapsedNow) {
        if (vtol) {
            v2lExitConfirmations = 0;
            markV2lLocked(wallNow, elapsedNow);
            return;
        }
        if (!v2lActive) {
            v2lExitConfirmations = 0;
            return;
        }
        // A false mode bit proves only that export ended. It does not say whether the cable was
        // unplugged or replaced by a charging connection, so it cannot clear authoritative OFF or
        // fabricate a plug timestamp. A positive gun callback plus versioned poll releases V2L
        // through confirmPositiveConnectionLocked().
        v2lExitConfirmations = Math.min(
                POSITIVE_CONNECTION_CONFIRMATIONS_REQUIRED, v2lExitConfirmations + 1);
    }

    /** ACTION_POWER_CONNECTED received. */
    public void onPowerConnected() {
        synchronized (lock) {
            long wallNow = System.currentTimeMillis();
            long elapsedNow = monotonicNowMs();
            boolean hadAuthoritativeOff = disconnectedLatched || v2lActive;
            if (hadAuthoritativeOff) {
                resetPositiveConnectionConfirmationLocked();
                disconnectedLatched = false;
                v2lActive = false;
                lastPlugDisconnectedMs = 0L;
                clearCompletedSessionEvidence();
                if (isAuthoritativeOffBmsState(bmsState)) {
                    bmsState = BydVehicleData.UNAVAILABLE;
                    bmsStateAtMs = 0L;
                    bmsStateAtElapsedMs = 0L;
                    invalidateBmsTimerLocked();
                }
                releaseTerminalBarrierLocked("ACTION_POWER_CONNECTED after authoritative OFF");
                lastPlugConnectedMs = wallNow;
                lastPlugConnectedElapsedMs = elapsedNow;
            } else if (!terminalSessionBarrier) {
                // Duplicate CONNECTED broadcasts are common. They may refresh plug bias during an
                // active/ambiguous session, but cannot reopen a completed one.
                lastPlugConnectedMs = wallNow;
                lastPlugConnectedElapsedMs = elapsedNow;
            }
            logger.info("Plug edge: CONNECTED");
            enqueueTransitionLocked(recompute("plug-connected"));
        }
        drainFusedTransitions();
    }

    /** ACTION_POWER_DISCONNECTED received. */
    public void onPowerDisconnected() {
        synchronized (lock) {
            boolean newlyAuthoritativeOff = !disconnectedLatched && !v2lActive;
            markDisconnectedLocked(System.currentTimeMillis(), monotonicNowMs());
            enqueueTransitionLocked(recompute("plug-disconnected"));
            if (newlyAuthoritativeOff) {
                enqueueTransitionLocked(FusedTransition.authoritativeStop("edge-unplug"));
            }
        }
        drainFusedTransitions();
    }

    // ===== Outputs =====

    /** Immutable detector view used to fence multi-component API reads. */
    public static final class StateSnapshot {
        public final long generation;
        public final boolean charging;
        public final boolean physicalStop;
        public final boolean terminalBarrier;
        public final boolean pendingTerminalStop;
        public final long externalGeneration;
        public final int externalWriters;
        public final long observedAtMs;
        public final String source;

        private StateSnapshot(long generation, boolean charging,
                              boolean physicalStop, boolean terminalBarrier,
                              boolean pendingTerminalStop,
                              long externalGeneration, int externalWriters,
                              long observedAtMs, String source) {
            this.generation = generation;
            this.charging = charging;
            this.physicalStop = physicalStop;
            this.terminalBarrier = terminalBarrier;
            this.pendingTerminalStop = pendingTerminalStop;
            this.externalGeneration = externalGeneration;
            this.externalWriters = externalWriters;
            this.observedAtMs = observedAtMs;
            this.source = source;
        }
    }

    /** One atomic view of the fused verdict and the physical-stop fences that own it. */
    public StateSnapshot getStateSnapshot() {
        synchronized (lock) {
            long externalGeneration;
            int externalWriters;
            synchronized (PUBLICATION_FENCE_LOCK) {
                externalGeneration = externalPublicationGeneration;
                externalWriters = externalPublicationWriters;
            }
            return new StateSnapshot(
                    publicationGeneration,
                    fusedCharging,
                    disconnectedLatched || v2lActive,
                    terminalSessionBarrier,
                    hasPendingTerminalCallbackLocked(),
                    externalGeneration,
                    externalWriters,
                    fusedAtMs,
                    fusedSource);
        }
    }

    /**
     * True only when no detector update completed while a caller read state from other components.
     * A physical unplug/export is also a hard publication fence even when the fused value was
     * already false before that edge arrived.
     */
    public static boolean isPublicationWindowStable(
            StateSnapshot before, StateSnapshot after) {
        return isComponentPublicationWindowStable(before, after)
                && !after.physicalStop
                && !after.pendingTerminalStop;
    }

    /**
     * True when a multi-component read did not overlap any detector or external publication.
     *
     * <p>Unlike {@link #isPublicationWindowStable}, this accepts a coherent stopped state. Component
     * readers need to publish FINISHED/IDLE as well as CHARGING; response publishers apply the
     * additional fail-closed physical-stop checks.
     */
    public static boolean isComponentPublicationWindowStable(
            StateSnapshot before, StateSnapshot after) {
        return before != null
                && after != null
                && before.generation == after.generation
                && before.externalGeneration == after.externalGeneration
                && before.externalWriters == 0
                && after.externalWriters == 0;
    }

    @FunctionalInterface
    public interface StablePublicationCommit {
        boolean commit();
    }

    /**
     * Validate a component read and publish its derived side effects as one linearizable operation.
     *
     * <p>The detector lock prevents an internal lifecycle transition between validation and commit;
     * the external fence lock prevents a collector/callback publication from starting in that gap.
     * The callback must be short and must not call back into this detector.
     */
    public boolean commitIfComponentPublicationWindowStable(
            StateSnapshot before, StablePublicationCommit commit) {
        if (before == null || commit == null) return false;
        synchronized (lock) {
            synchronized (PUBLICATION_FENCE_LOCK) {
                if (before.generation != publicationGeneration
                        || before.externalGeneration != externalPublicationGeneration
                        || before.externalWriters != 0
                        || externalPublicationWriters != 0) {
                    return false;
                }
                return commit.commit();
            }
        }
    }

    /** True if the fused detector currently believes the vehicle is charging. */
    public boolean isCharging() {
        synchronized (lock) { return fusedCharging; }
    }

    /** Start of the most recent fused session, retained while FINISHED taper evidence is evaluated. */
    public long getLastSessionStartedAtMs() {
        synchronized (lock) { return lastSessionStartedAtMs; }
    }

    /** Test/diagnostic visibility for the persistent completed-session epoch. */
    long getTerminalBarrierSinceMs() {
        synchronized (lock) { return terminalBarrierSinceMs; }
    }

    /** True while completed-session evidence is fenced from classifier and slope training. */
    public boolean isTerminalSessionBarrierActive() {
        synchronized (lock) {
            return terminalSessionBarrier || hasPendingTerminalCallbackLocked();
        }
    }

    /** Diagnostic: which layer/event last decided the fused state. */
    public String lastSource() {
        synchronized (lock) { return fusedSource; }
    }

    // ===== Fusion =====

    /**
     * Carrier for a flip the synchronized recompute saw, so the public
     * caller can dispatch listeners AFTER releasing the lock. Avoids the
     * usual deadlock hazard where a listener calls back into the detector.
     */
    private static final class FusedTransition {
        final boolean fired;
        final boolean isCharging;
        final String source;
        final boolean authoritativeStop;
        FusedTransition(boolean fired, boolean isCharging, String source,
                        boolean authoritativeStop) {
            this.fired = fired;
            this.isCharging = isCharging;
            this.source = source;
            this.authoritativeStop = authoritativeStop;
        }
        static FusedTransition authoritativeStop(String source) {
            return new FusedTransition(false, false, source, true);
        }
        static final FusedTransition NONE =
                new FusedTransition(false, false, "", false);
    }

    private void enqueueTransitionLocked(FusedTransition t) {
        if (t == null || (!t.fired && !t.authoritativeStop)) return;
        synchronized (transitionDispatchLock) {
            pendingTransitions.addLast(t);
        }
    }

    private void drainFusedTransitions() {
        synchronized (transitionDispatchLock) {
            if (transitionDispatchRunning) return;
            transitionDispatchRunning = true;
        }
        try {
            while (true) {
                FusedTransition t;
                synchronized (transitionDispatchLock) {
                    t = pendingTransitions.pollFirst();
                    if (t == null) return;
                }
                if (t.fired) {
                    for (FusedStateListener l : fusedListeners) {
                        try { l.onFusedChargingChanged(t.isCharging, t.source); }
                        catch (Throwable e) {
                            logger.debug("FusedStateListener error: " + e.getMessage());
                        }
                    }
                }
                if (t.authoritativeStop) {
                    for (AuthoritativeStopListener l : authoritativeStopListeners) {
                        try { l.onAuthoritativeChargingStop(t.source); }
                        catch (Throwable e) {
                            logger.debug("AuthoritativeStopListener error: " + e.getMessage());
                        }
                    }
                }
            }
        } finally {
            boolean retry;
            synchronized (transitionDispatchLock) {
                transitionDispatchRunning = false;
                retry = !pendingTransitions.isEmpty();
            }
            // An enqueue can race the empty poll above while dispatchRunning is still true. Its
            // caller correctly declines to start a second drainer, so restart here after releasing
            // ownership or that transition would remain queued forever.
            if (retry) drainFusedTransitions();
        }
    }

    private void scheduleDisagreementDeadlineLocked() {
        final long generation = ++disagreementTimerGeneration;
        scheduleDisagreementCheck(generation, disagreementMinMs);
    }

    private void scheduleDisagreementCheck(long generation, long delayMs) {
        TIMER.schedule(() -> {
            long remaining;
            synchronized (lock) {
                if (generation != disagreementTimerGeneration) return;
                remaining = l1L2DisagreementSinceMs + disagreementMinMs
                        - monotonicNowMs();
                if (remaining > 0) {
                    scheduleDisagreementCheck(generation, remaining);
                    return;
                }
                enqueueTransitionLocked(recompute("l1-l2-disagreement-deadline"));
            }
            drainFusedTransitions();
        }, Math.max(1L, delayMs), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void clearDisagreementLocked() {
        if (l1L2DisagreementSinceMs == 0L) return;
        l1L2DisagreementSinceMs = 0L;
        invalidateDisagreementTimerLocked();
    }

    private void invalidateDisagreementTimerLocked() {
        disagreementTimerGeneration++;
    }

    private void scheduleOffDeadlineLocked() {
        final long generation = ++offTimerGeneration;
        scheduleOffCheck(generation, offEdgeDebounceMs);
    }

    private void scheduleOffCheck(long generation, long delayMs) {
        TIMER.schedule(() -> {
            long remaining;
            synchronized (lock) {
                if (generation != offTimerGeneration || pendingOffSinceMs == 0L) return;
                remaining = pendingOffSinceMs + offEdgeDebounceMs
                        - monotonicNowMs();
                if (remaining > 0) {
                    scheduleOffCheck(generation, remaining);
                    return;
                }
                enqueueTransitionLocked(recompute("off-debounce-deadline"));
            }
            drainFusedTransitions();
        }, Math.max(1L, delayMs), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void invalidateOffTimerLocked() {
        offTimerGeneration++;
    }

    private void scheduleL2ExpiryLocked() {
        final long generation = ++l2TimerGeneration;
        scheduleL2ExpiryCheck(generation, l2FreshnessMs + 1L);
    }

    private void scheduleL2ExpiryCheck(long generation, long delayMs) {
        TIMER.schedule(() -> {
            long remaining;
            synchronized (lock) {
                if (generation != l2TimerGeneration || powerIsChargingAtMs == 0L) return;
                remaining = powerIsChargingAtMs + l2FreshnessMs + 1L
                        - monotonicNowMs();
                if (remaining > 0) {
                    scheduleL2ExpiryCheck(generation, remaining);
                    return;
                }
                enqueueTransitionLocked(recompute("l2-freshness-deadline"));
            }
            drainFusedTransitions();
        }, Math.max(1L, delayMs), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void invalidateL2TimerLocked() {
        l2TimerGeneration++;
    }

    private void scheduleBmsExpiryLocked() {
        final long generation = ++bmsTimerGeneration;
        scheduleBmsExpiryCheck(generation, BMS_FRESHNESS_MS + 1L);
    }

    private void scheduleBmsExpiryCheck(long generation, long delayMs) {
        TIMER.schedule(() -> {
            long remaining;
            synchronized (lock) {
                if (generation != bmsTimerGeneration
                        || bmsState != ChargingStateData.CHARGING_BATTERY_STATE_CHARGING) {
                    return;
                }
                remaining = bmsStateAtElapsedMs + BMS_FRESHNESS_MS + 1L
                        - monotonicNowMs();
                if (remaining > 0) {
                    scheduleBmsExpiryCheck(generation, remaining);
                    return;
                }
                enqueueTransitionLocked(recompute("bms-freshness-deadline"));
            }
            drainFusedTransitions();
        }, Math.max(1L, delayMs), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void invalidateBmsTimerLocked() {
        bmsTimerGeneration++;
    }

    private void scheduleExternalVerdictExpiryLocked() {
        final long generation = ++externalVerdictTimerGeneration;
        scheduleExternalVerdictExpiryCheck(generation, externalVerdictFreshnessMs + 1L);
    }

    private void scheduleExternalVerdictExpiryCheck(long generation, long delayMs) {
        TIMER.schedule(() -> {
            synchronized (lock) {
                if (generation != externalVerdictTimerGeneration
                        || !externalVerdictActive) {
                    return;
                }
                long remaining = externalVerdictAtElapsedMs
                        + externalVerdictFreshnessMs + 1L - monotonicNowMs();
                if (remaining > 0L) {
                    scheduleExternalVerdictExpiryCheck(generation, remaining);
                    return;
                }
                externalVerdictActive = false;
                externalVerdictAtElapsedMs = 0L;
                enqueueTransitionLocked(recompute("external-verdict-expired"));
            }
            drainFusedTransitions();
        }, Math.max(1L, delayMs), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void clearPendingTerminalCallbackLocked() {
        pendingTerminalBmsState = BydVehicleData.UNAVAILABLE;
        pendingTerminalEpoch = 0L;
        pendingTerminalPositivePollConfirmations = 0;
    }

    private boolean hasPendingTerminalCallbackLocked() {
        return pendingTerminalEpoch == sessionEpoch
                && pendingTerminalBmsState != BydVehicleData.UNAVAILABLE;
    }

    private void scheduleL3EvidenceExpiryLocked() {
        final long generation = ++l3EvidenceTimerGeneration;
        long elapsedNow = monotonicNowMs();
        long remaining = l3EvidenceRemainingMsLocked(elapsedNow);
        if (remaining != Long.MAX_VALUE) {
            scheduleL3EvidenceExpiryCheck(generation, Math.max(1L, remaining));
        } else if (l3Latched) {
            scheduleL3EvidenceExpiryCheck(generation, 1L);
        }
    }

    private long l3EvidenceRemainingMsLocked(long elapsedNow) {
        long latestDeadline = Long.MIN_VALUE;
        synchronized (rawSignalLock) {
            if (lastRawSignalMoveElapsedMs > 0) {
                latestDeadline = Math.max(latestDeadline,
                        lastRawSignalMoveElapsedMs + rawSignalMoveWindowMs + 1L);
            }
        }
        if (enginePowerAtElapsedMs > 0 && enginePowerChangedAtElapsedMs > 0
                && !Double.isNaN(enginePowerKw)
                && enginePowerKw < -ENGINE_POWER_DEADBAND) {
            long engineDeadline = Math.min(
                    enginePowerAtElapsedMs + ENGINE_POWER_FRESHNESS_MS + 1L,
                    enginePowerChangedAtElapsedMs + ENGINE_POWER_FRESHNESS_MS + 1L);
            latestDeadline = Math.max(latestDeadline, engineDeadline);
        }
        return latestDeadline == Long.MIN_VALUE
                ? Long.MAX_VALUE : latestDeadline - elapsedNow;
    }

    private void scheduleL3EvidenceExpiryCheck(long generation, long delayMs) {
        TIMER.schedule(() -> {
            synchronized (lock) {
                if (generation != l3EvidenceTimerGeneration) return;
                long remaining = l3EvidenceRemainingMsLocked(monotonicNowMs());
                if (remaining > 0 && remaining != Long.MAX_VALUE) {
                    scheduleL3EvidenceExpiryCheck(generation, remaining);
                    return;
                }
                if (remaining == Long.MAX_VALUE || remaining <= 0) {
                    inferenceHysteresis = -HYSTERESIS_SAMPLES;
                    l3Latched = false;
                    enqueueTransitionLocked(recompute("l3-freshness-deadline"));
                }
            }
            drainFusedTransitions();
        }, Math.max(1L, delayMs), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void invalidateL3EvidenceTimerLocked() {
        l3EvidenceTimerGeneration++;
    }

    private FusedTransition recompute(String trigger) {
        long wallNow = System.currentTimeMillis();
        long monotonicNow = monotonicNowMs();
        boolean prev = fusedCharging;
        boolean next;
        String source;
        boolean authoritativeOff = false;
        boolean callbackAuthoritativeStop = false;
        boolean externalVerdictFresh = externalVerdictActive
                && externalVerdictAtElapsedMs > 0L
                && monotonicNow - externalVerdictAtElapsedMs
                        <= externalVerdictFreshnessMs;
        if (externalVerdictActive && !externalVerdictFresh) {
            externalVerdictActive = false;
            externalVerdictAtElapsedMs = 0L;
            externalVerdictTimerGeneration++;
        }

        if (disconnectedLatched) {
            next = false;
            source = "edge-unplug";
            authoritativeOff = true;
        } else if (v2lActive) {
            next = false;
            source = "v2l-export";
            authoritativeOff = true;
        } else if (externalVerdictFresh) {
            next = externalVerdictCharging;
            source = externalVerdictSource;
            authoritativeOff = !next;
        } else if (terminalSessionBarrier) {
            next = false;
            source = "completed-session-barrier";
            authoritativeOff = true;
        } else {
            // L1: BMS direct.
            boolean l1Says = bmsState == ChargingStateData.CHARGING_BATTERY_STATE_CHARGING
                    && !bmsStale(monotonicNow);
            // BMS gives explicit non-charging terminal states we trust.
            boolean l1Negative = isAuthoritativeOffBmsState(bmsState);
            // BMS ambiguous: UNAVAILABLE, IDLE (15 — buggy on PHEVs), or
            // any other code we don't explicitly recognize as terminal.
            boolean l1Ambiguous = !l1Says && !l1Negative;

            Boolean l2 = powerIsChargingAtMs > 0
                    && monotonicNow - powerIsChargingAtMs <= l2FreshnessMs
                    ? powerIsChargingTri : null;

            if (l1Says && (l2 == null || l2)) {
                clearDisagreementLocked();
                next = true; source = "l1-bms";
            } else if (l1Says && Boolean.FALSE.equals(l2)) {
                if (l1L2DisagreementSinceMs == 0L) {
                    l1L2DisagreementSinceMs = monotonicNow;
                    scheduleDisagreementDeadlineLocked();
                }
                if (monotonicNow - l1L2DisagreementSinceMs >= disagreementMinMs) {
                    // L2=false is not sufficient to overrule explicit BMS CHARGING when a third,
                    // independent layer has repeatedly observed live pack flow. This is the PHEV
                    // failure mode where Power.isCharging() returns a stable false value despite
                    // BMS and charging-direction flow agreeing. Without corroborated L3, retain the
                    // existing fail-closed behavior so a stale BMS positive still stops.
                    if (computeL3Inference(monotonicNow)) {
                        next = true; source = "l3-corroborates-l1";
                    } else {
                        next = false; source = "l2-overrides-uncorroborated-l1";
                    }
                } else {
                    next = true; source = "l1-bms";
                }
            } else if (l1Negative) {
                clearDisagreementLocked();
                // BMS reports an explicit terminal state (READY/FINISHED/
                // TERMINATED/DISCHARG_FINISH). Trust it — even if Power MCU
                // (L2) momentarily disagrees, an L2-overrides path here
                // would produce inconsistent state codes (caller's
                // effectiveState=CHARGING vs raw vd.chargingState=12). The
                // PHEV firmware bug we route around is BMS *stuck at 15 IDLE*
                // while charging, NOT BMS reporting an explicit terminal
                // state by mistake.
                next = false; source = "l1-bms-negative";
                authoritativeOff = true;
                inferenceHysteresis = Math.min(inferenceHysteresis, 0);
                l3Latched = false;
            } else if (Boolean.TRUE.equals(l2)) {
                clearDisagreementLocked();
                next = true; source = "l2-power";
            } else if (Boolean.FALSE.equals(l2)) {
                clearDisagreementLocked();
                // On affected PHEVs both BMS IDLE and Power.isCharging=false are unreliable. L3 is
                // admitted here only after connected-gun, Park and repeated live-flow evidence, the
                // same strong corroboration that may overrule L2 against explicit BMS CHARGING.
                if (computeL3Inference(monotonicNow)) {
                    next = true; source = "l3-overrides-ambiguous-l2";
                } else {
                    next = false; source = "l2-power-negative";
                }
            } else {
                clearDisagreementLocked();
                // L1 ambiguous + L2 unavailable: fall through to L3 inference.
                next = computeL3Inference(monotonicNow);
                source = next ? "l3-inferred" : "l3-not-inferred";
            }

            // Plug-bias: within PLUG_BIAS_WINDOW_MS of CONNECTED, if any
            // power evidence is positive, force-charging. Handles the
            // ramp-up window where BMS is still initializing.
            if (!next && !authoritativeOff && lastPlugConnectedElapsedMs > 0
                    && monotonicNow - lastPlugConnectedElapsedMs < PLUG_BIAS_WINDOW_MS
                    && hasAnyPowerEvidence(monotonicNow)) {
                next = true;
                source = "plug-bias-power";
            }
        }

        // Output-edge debounce on ON->OFF. Without this, a single cycle where
        // the holding layer's input briefly drops (L2 reflection returns null,
        // BMS poll skips, etc.) collapses the verdict to OFF and immediately
        // back to ON next cycle, firing a spurious stopped+started pair. We
        // hold a tentative OFF for OFF_EDGE_DEBOUNCE_MS and only commit it if
        // it persists. A genuine unplug or explicit terminal BMS state is authoritative and
        // bypasses the debounce entirely.
        if (next || authoritativeOff) {
            // Resolved back to charging, or received authoritative proof of stop: no pending OFF.
            pendingOffSinceMs = 0L;
            invalidateOffTimerLocked();
        } else if (prev) {
            // prev ON, this recompute resolved OFF for a non-unplug reason.
            // Start (or continue) the debounce window; keep reporting ON until
            // the OFF has persisted long enough to be trusted.
            if (pendingOffSinceMs == 0L) {
                pendingOffSinceMs = monotonicNow;
                scheduleOffDeadlineLocked();
            }
            if (monotonicNow - pendingOffSinceMs < offEdgeDebounceMs) {
                next = true;          // suppress the flip for now
                source = fusedSource; // keep prior source; don't churn logs
            } else {
                // Window elapsed — commit the OFF (next stays false) and clear
                // the marker so a future session's debounce starts fresh.
                pendingOffSinceMs = 0L;
                invalidateOffTimerLocked();
            }
        }

        if (!next && pendingTerminalEpoch == sessionEpoch
                && pendingTerminalBmsState != BydVehicleData.UNAVAILABLE
                && activeSessionEpoch == sessionEpoch) {
            int terminalState = pendingTerminalBmsState;
            boolean noPositivePollBeforeStop =
                    pendingTerminalPositivePollConfirmations == 0;
            enterTerminalBarrierLocked(wallNow, monotonicNow);
            terminalBarrierAllowsCohesiveRecovery = noPositivePollBeforeStop;
            source = isDischargingBmsState(terminalState)
                    ? "v2l-export-callback" : "bms-terminal-callback";
            callbackAuthoritativeStop = isDischargingBmsState(terminalState);
        }

        fusedCharging = next;
        fusedAtMs = wallNow;
        fusedSource = source;
        updatePublicationGenerationLocked();
        if (next && !prev) {
            lastSessionStartedAtMs = pendingSessionEvidenceAtMs > 0
                    ? Math.min(wallNow, pendingSessionEvidenceAtMs) : wallNow;
            activeSessionEpoch = sessionEpoch;
        }

        if (next != prev) {
            logger.info("Charging fused " + (prev ? "ON" : "OFF") + "->"
                + (next ? "ON" : "OFF") + " trigger=" + trigger
                + " source=" + source + " bms=" + bmsState
                + " power=" + powerIsChargingTri
                + " gun=" + chargingGunState
                + " engineKw=" + fmt(enginePowerKw)
                + " extKw=" + fmt(externalChargingPowerKw)
                + " chgKw=" + fmt(chargingPowerKw));
            return new FusedTransition(true, next, source, callbackAuthoritativeStop);
        }
        if (callbackAuthoritativeStop) {
            return FusedTransition.authoritativeStop(source);
        }
        return FusedTransition.NONE;
    }

    private void updatePublicationGenerationLocked() {
        boolean physicalStop = disconnectedLatched || v2lActive;
        boolean terminalBarrier = terminalSessionBarrier;
        boolean pendingTerminalStop = hasPendingTerminalCallbackLocked();
        if (fusedCharging != publishedGenerationCharging
                || physicalStop != publishedGenerationPhysicalStop
                || terminalBarrier != publishedGenerationTerminalBarrier
                || pendingTerminalStop != publishedGenerationPendingTerminalStop) {
            publicationGeneration++;
            publishedGenerationCharging = fusedCharging;
            publishedGenerationPhysicalStop = physicalStop;
            publishedGenerationTerminalBarrier = terminalBarrier;
            publishedGenerationPendingTerminalStop = pendingTerminalStop;
        }
    }

    private boolean computeL3Inference(long now) {
        if (!inPark) {
            inferenceHysteresis = Math.min(inferenceHysteresis, 0);
            l3Latched = false;
            return false;
        }
        // Positive gun assertion. AC=2, DC=3, AC_DC=4. VTOL=5 is V2L
        // (vehicle-to-load) — pack is DISCHARGING through the gun, the
        // exact opposite of charging. We must NOT count gun=5 as evidence,
        // and we similarly reject UNAVAILABLE (the PHEV hole the old
        // "!= 1 disconnected" guard fell through).
        boolean gunPlausible =
            chargingGunState == 2 || chargingGunState == 3
            || chargingGunState == 4;
        // NARROW RELAXATION for a gun state that is UNAVAILABLE rather than contradictory. That is a
        // dead accessor, not evidence against charging — and on a trim where the BMS also sits at IDLE
        // and the power MCU is unavailable, requiring a positive assertion left L3 unable to fire at
        // all, which is the exact fallback L3 exists to be. V2L (5) and DISCONNECTED (1) still block
        // outright: those are positive statements that the pack is discharging or unplugged.
        //
        // Substituted evidence is deliberately stricter than the gun assertion it replaces: the raw
        // charging channel must have MOVED inside its window, which on a cumulative counter or a live
        // rate only happens while energy is flowing, and the hysteresis below still requires that across
        // several distinct observations. In park is already required above.
        if (!gunPlausible && chargingGunState == BydVehicleData.UNAVAILABLE
                && rawSignalMovingRecently(now)) {
            gunPlausible = true;
        }
        if (!gunPlausible) {
            inferenceHysteresis = Math.min(inferenceHysteresis, 0);
            l3Latched = false;
            return false;
        }
        return l3Latched;
    }

    private void updateL3Hysteresis(long elapsedNow) {
        if (hasPendingTerminalCallbackLocked()) {
            inferenceHysteresis = 0;
            l3Latched = false;
            invalidateL3EvidenceTimerLocked();
            return;
        }
        boolean rawMoving = rawSignalMovingRecently(elapsedNow);
        boolean engineFresh = enginePowerAtElapsedMs > 0
                && elapsedNow - enginePowerAtElapsedMs < ENGINE_POWER_FRESHNESS_MS;
        boolean engineChangedRecently = enginePowerChangedAtElapsedMs > 0
                && elapsedNow - enginePowerChangedAtElapsedMs < ENGINE_POWER_FRESHNESS_MS;
        // A repeated getter timestamp is not independent evidence. The engine level counts only
        // when the value itself moved recently; a frozen final -3 kW cannot be refreshed forever.
        boolean engineEvidence = engineFresh && engineChangedRecently
                && !Double.isNaN(enginePowerKw)
                && enginePowerKw < -ENGINE_POWER_DEADBAND;
        boolean evidence = engineEvidence || rawMoving;
        // NO LEVEL TEST on externalChargingPowerKw / chargingPowerKw. Their UNIT is decided at
        // runtime (see ChargeSourceClassifier) because the same accessor answers an instantaneous kW
        // on some firmware and a CUMULATIVE kWh counter on others. A level test on a cumulative value
        // latches forever: once a session has delivered anything, the counter stays above any
        // threshold for as long as it is readable, so a finished-but-plugged car keeps presenting
        // "evidence" and L3 holds the session open indefinitely. Only MOVEMENT distinguishes energy
        // actually flowing from a stale total, and it is unit-agnostic — which is what this needs,
        // since these fields cannot be interpreted as a magnitude here at all.
        //
        // This is also the channel that bootstraps L3: the fields above are populated behind an
        // admission gate that itself consults this detector, so movement is observed through a
        // channel the gate does not control (see observeRawChargingSignal).
        if (evidence) {
            // Count DISTINCT observations, not recomputes. recompute() is reached from six triggers
            // (BMS edge, L2 poll, collect poll, ACC, plug connect/disconnect), and movement evidence
            // stays true for RAW_SIGNAL_MOVE_WINDOW_MS — so a single counter transition plus two
            // unrelated BMS callbacks arriving seconds apart satisfied "3 consecutive samples" without
            // any new charging evidence at all. That defeats the purpose of the hysteresis, which is to
            // require evidence to PERSIST. Requiring the movement timestamp to have advanced makes each
            // increment a genuinely new observation.
            boolean freshObservation;
            synchronized (rawSignalLock) {
                freshObservation = rawObservationSequence != lastCountedRawSequence;
                if (freshObservation) lastCountedRawSequence = rawObservationSequence;
            }
            // Engine power carries its own observation timestamp, so apply the same distinctness test
            // to it. Being freshness-bounded is NOT the same as being new: one engine sample stays
            // fresh for ENGINE_POWER_FRESHNESS_MS, during which several unrelated recomputes (BMS edge,
            // plug edge, L2 poll) would each have counted it again and satisfied the hysteresis off a
            // single observation.
            boolean freshEngineObservation = engineEvidence
                    && engineChangeSequence != lastCountedEngineChangeSequence;
            if (freshEngineObservation) {
                lastCountedEngineChangeSequence = engineChangeSequence;
            }
            if (freshObservation || freshEngineObservation) {
                inferenceHysteresis = Math.max(0, inferenceHysteresis) + 1;
            }
            if (inferenceHysteresis >= HYSTERESIS_SAMPLES) {
                l3Latched = true;
            }
        } else {
            inferenceHysteresis = Math.min(0, inferenceHysteresis) - 1;
            if (-inferenceHysteresis >= HYSTERESIS_SAMPLES) {
                l3Latched = false;
                inferenceHysteresis = -HYSTERESIS_SAMPLES; // clamp
            }
        }
    }

    private boolean hasAnyPowerEvidence(long elapsedNow) {
        boolean engineFresh = enginePowerAtElapsedMs > 0
                && elapsedNow - enginePowerAtElapsedMs < ENGINE_POWER_FRESHNESS_MS;
        boolean engineChangedRecently = enginePowerChangedAtElapsedMs > 0
                && elapsedNow - enginePowerChangedAtElapsedMs < ENGINE_POWER_FRESHNESS_MS;
        // enginePowerKw keeps a level test: it is unambiguously an instantaneous kW (negative =
        // flowing into the pack) and it is freshness-bounded, so it cannot latch.
        if (engineFresh && engineChangedRecently && !Double.isNaN(enginePowerKw)
                && enginePowerKw < -ENGINE_POWER_DEADBAND) return true;
        // The charging accessors are movement-tested, not level-tested, for the reason given in
        // updateL3Hysteresis: their unit is firmware-dependent, and a level test on a cumulative
        // counter never stops being true. This is the plug-bias window (30 s after CONNECTED), so
        // movement is the right question anyway — the point is whether delivery has begun.
        if (rawSignalMovingRecently(elapsedNow)) return true;
        return false;
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "NaN" : String.format(java.util.Locale.US, "%.2f", v);
    }
}

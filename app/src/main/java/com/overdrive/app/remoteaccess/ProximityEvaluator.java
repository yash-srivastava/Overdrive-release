package com.overdrive.app.remoteaccess;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Locale;

/**
 * Deterministic proximity decisions from one selected phone's fresh RSSI readings.
 *
 * <p>Uses caller-supplied monotonic time and owns no timer, radio, listener, thread,
 * or vehicle command. Missing data clears the approach arm rather than counting
 * as departure. Consumers apply authorization, vehicle safety, and action cooldown.
 */
public final class ProximityEvaluator {
    public enum State { UNKNOWN, TRANSITION, NEAR, AWAY }
    public enum Event { NONE, ARRIVAL, DEPARTURE }
    public enum UnlockTrigger {
        AWAY_TO_NEAR("away_to_near"), NEAR("near");

        public final String setting;

        UnlockTrigger(String setting) { this.setting = setting; }

        public static UnlockTrigger fromSetting(Object setting) {
            for (UnlockTrigger trigger : values())
                if (trigger.setting.equals(setting)) return trigger;
            throw new IllegalArgumentException("unlockTrigger must be away_to_near or near");
        }
    }

    /**
     * Hard floor (18 Oct) beneath every window and dwell setting: a zone is confirmed only by at
     * least this many readings in it — two Near readings for an arrival, two Away readings for a
     * departure — because single RSSI samples swing 10-20 dB at the door in the field logs. It is
     * also the confirming count of the settings-derived configuration (a 1 Hz collector delivers
     * two readings inside the 2 s default arrival window); a {@link Config} asking for fewer is
     * raised to it, never below.
     */
    public static final int MIN_CONFIRMING_SAMPLES = 2;

    /**
     * Native disconnection confirmation (20 Oct, snappy locks): a confirmed-disconnection departure needs
     * this many consecutive not-connected native reads, spanning at least the greater of
     * {@link #DISCONNECTION_CONFIRM_SPAN_MS} and the configured departure confirmation. Before 20 Oct the
     * rule was three reads over six seconds; its shape is otherwise unchanged.
     */
    public static final int DISCONNECTION_CONFIRM_READS = 2;
    public static final long DISCONNECTION_CONFIRM_SPAN_MS = 2_000;

    public static final class Config {
        public final int nearThreshold;
        public final int awayThreshold;
        public final long windowMs;
        public final long stableMs;
        public final long staleMs;
        /** Confirming readings per zone; never below {@link #MIN_CONFIRMING_SAMPLES}. */
        public final int minStableSamples;
        public final UnlockTrigger unlockTrigger;
        public final long nearWindowMs;
        public final long nearStableMs;
        public final boolean separateNearConfirmation;

        public Config(int nearThreshold, int awayThreshold, long windowMs,
                long stableMs, long staleMs) {
            this(nearThreshold, awayThreshold, windowMs, stableMs, staleMs, MIN_CONFIRMING_SAMPLES,
                    UnlockTrigger.AWAY_TO_NEAR);
        }

        public Config(int nearThreshold, int awayThreshold, long windowMs,
                long stableMs, long staleMs, UnlockTrigger unlockTrigger) {
            this(nearThreshold, awayThreshold, windowMs, stableMs, staleMs, MIN_CONFIRMING_SAMPLES,
                    unlockTrigger);
        }

        public Config(int nearThreshold, int awayThreshold, long windowMs,
                long stableMs, long staleMs, int minStableSamples) {
            this(nearThreshold, awayThreshold, windowMs, stableMs, staleMs, minStableSamples,
                    UnlockTrigger.AWAY_TO_NEAR);
        }

        public Config(int nearThreshold, int awayThreshold, long windowMs,
                long stableMs, long staleMs, int minStableSamples, UnlockTrigger unlockTrigger) {
            this(nearThreshold, awayThreshold, windowMs, stableMs, staleMs,
                    minStableSamples, unlockTrigger, windowMs, stableMs, false);
        }

        /** Separate arrival confirmation retains conservative departure filtering. */
        public Config(int nearThreshold, int awayThreshold, long windowMs,
                long stableMs, long staleMs, long nearWindowMs, long nearStableMs,
                UnlockTrigger unlockTrigger) {
            this(nearThreshold, awayThreshold, windowMs, stableMs, staleMs,
                    MIN_CONFIRMING_SAMPLES, unlockTrigger, nearWindowMs, nearStableMs, true);
        }

        private Config(int nearThreshold, int awayThreshold, long windowMs,
                long stableMs, long staleMs, int minStableSamples, UnlockTrigger unlockTrigger,
                long nearWindowMs, long nearStableMs, boolean separateNearConfirmation) {
            if (awayThreshold < -127 || nearThreshold > 20
                    || nearThreshold <= awayThreshold) {
                throw new IllegalArgumentException("Near must exceed Away within [-127, 20]");
            }
            if (windowMs < 1 || windowMs > 120_000
                    || stableMs < 0 || stableMs > 120_000
                    || staleMs < 1 || staleMs > 120_000
                    || nearWindowMs < 1 || nearWindowMs > 120_000
                    || nearStableMs < 0 || nearStableMs > 120_000
                    || minStableSamples < 1 || minStableSamples > 100) {
                throw new IllegalArgumentException("Invalid proximity timing or sample count");
            }
            if (unlockTrigger == null) throw new IllegalArgumentException("Unlock trigger is required");
            this.nearThreshold = nearThreshold;
            this.awayThreshold = awayThreshold;
            this.windowMs = windowMs;
            this.stableMs = stableMs;
            this.staleMs = staleMs;
            // The floor is absolute: no configuration confirms a zone on a single reading.
            this.minStableSamples = Math.max(MIN_CONFIRMING_SAMPLES, minStableSamples);
            this.unlockTrigger = unlockTrigger;
            this.nearWindowMs = nearWindowMs;
            this.nearStableMs = nearStableMs;
            this.separateNearConfirmation = separateNearConfirmation;
        }

        /** Experimental defaults; saved thresholds should follow vehicle calibration. */
        public static Config defaults() {
            return new Config(-12, -24, 15_000, 4_000, 5_000, 4_000, 2_000,
                    UnlockTrigger.AWAY_TO_NEAR);
        }
    }

    public static final class Snapshot {
        public final String address;
        public final Integer rawRssi;
        public final Double medianRssi;
        public final Double arrivalMedianRssi;
        public final Double departureMedianRssi;
        public final String filterKind;
        public final int nearSampleCount;
        public final long nearStableForMs;
        public final boolean nearCandidate;
        /** Current evidence permits considering this phone Away for departure locking. */
        public final boolean departureEligible;
        public final String evidenceKind;
        public final boolean connectionKnown, radioOn, arrivalCandidate, lockCandidate;
        public final long disconnectedForMs, episodeId;
        public final int disconnectionSampleCount;
        public final State state;
        public final State stableState;
        public final State candidate;
        public final long stableForMs;
        /** Age of the latest accepted sample, or -1 when there is no usable sample. */
        public final long freshnessMs;
        public final int sampleCount;
        public final int candidateSamples;
        public final boolean armed;
        /** Only an observe call that confirms a new zone returns an event. */
        public final Event event;
        public final String reason;
        /**
         * Diagnostic only (R4, 18 Oct): this observe call confirmed a Near episode that cannot
         * unlock because, in away_to_near mode, the history holds no confirmed Away since its
         * last reset (the phone's first connection). True on exactly that one call; it changes
         * no state and no decision.
         */
        public final boolean firstConnectionNoPriorAway;

        private Snapshot(String address, Integer rawRssi, Double medianRssi,
                State state, State stableState, State candidate, long stableForMs,
                long freshnessMs, int sampleCount, int candidateSamples,
                boolean armed, Event event, String reason, Double arrivalMedianRssi,
                Double departureMedianRssi, String filterKind, int nearSampleCount,
                long nearStableForMs, boolean nearCandidate, boolean departureEligible,
                String evidenceKind, boolean connectionKnown, boolean radioOn,
                long disconnectedForMs, int disconnectionSampleCount,
                boolean arrivalCandidate, boolean lockCandidate, long episodeId,
                boolean firstConnectionNoPriorAway) {
            this.firstConnectionNoPriorAway = firstConnectionNoPriorAway;
            this.address = address;
            this.rawRssi = rawRssi;
            this.medianRssi = medianRssi;
            this.arrivalMedianRssi = arrivalMedianRssi;
            this.departureMedianRssi = departureMedianRssi;
            this.filterKind = filterKind;
            this.nearSampleCount = nearSampleCount;
            this.nearStableForMs = nearStableForMs;
            this.nearCandidate = nearCandidate;
            this.departureEligible = departureEligible;
            this.evidenceKind = evidenceKind;
            this.connectionKnown = connectionKnown;
            this.radioOn = radioOn;
            this.disconnectedForMs = disconnectedForMs;
            this.disconnectionSampleCount = disconnectionSampleCount;
            this.arrivalCandidate = arrivalCandidate;
            this.lockCandidate = lockCandidate;
            this.episodeId = episodeId;
            this.state = state;
            this.stableState = stableState;
            this.candidate = candidate;
            this.stableForMs = stableForMs;
            this.freshnessMs = freshnessMs;
            this.sampleCount = sampleCount;
            this.candidateSamples = candidateSamples;
            this.armed = armed;
            this.event = event;
            this.reason = reason;
        }
    }

    private static final int MAX_WINDOW_SAMPLES = 512;

    private static final class Sample {
        final long timeMs;
        final int rssi;

        Sample(long timeMs, int rssi) {
            this.timeMs = timeMs;
            this.rssi = rssi;
        }
    }

    private final Config config;
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private final ArrayDeque<Sample> arrivalSamples = new ArrayDeque<>();
    private final ArrayDeque<Sample> nearRunSamples = new ArrayDeque<>();
    private String address;
    private Integer rawRssi;
    private Double medianRssi;
    private Double arrivalMedianRssi;
    private long nearSinceMs = -1;
    private long lastNowMs = -1;
    private long lastSampleMs = -1;
    private State stableState = State.UNKNOWN;
    private State candidate = State.UNKNOWN;
    private long candidateSinceMs = -1;
    private int candidateSamples;
    private boolean armed;
    private boolean confirmedNearVisit;
    private boolean carriedNearVisit;
    private String reason = "waiting_for_signal";
    private String connectionModeGeneration, evidenceKind = "unknown";
    private boolean trustedConnectionSeen, confirmedDisconnection;
    private boolean connectionKnown, radioOn, arrivalEpisodeEligible, lockEpisodeEligible;
    private long lastEvidenceMs = -1, lastConnectionProofMs = -1;
    private long disconnectedSinceMs = -1, lastDisconnectedMs = -1;
    private int disconnectionSamples;
    private long nextEpisodeId, episodeId;

    public ProximityEvaluator(Config config) {
        if (config == null) throw new IllegalArgumentException("Config is required");
        this.config = config;
    }

    /**
     * Accepts a newly completed reading for the selected phone. Null, invalid,
     * disconnected, and ineligible readings immediately revoke all pending actions.
     * Duplicate or older timestamps cannot advance a candidate or refresh a sample.
     */
    public synchronized Snapshot observe(String selectedAddress, Integer rssi,
            long nowMs, boolean connected, boolean vehicleEligible) {
        return observeInternal(selectedAddress, rssi, nowMs, connected, vehicleEligible, null, true);
    }

    /** Explicit native connection proof adds an absence channel without inventing RSSI. */
    public synchronized Snapshot observe(String selectedAddress, Integer rssi,
            long nowMs, boolean connected, boolean vehicleEligible,
            BluetoothConnectionEvidence proof) {
        return observeInternal(selectedAddress, rssi, nowMs, connected, vehicleEligible, proof, false);
    }

    private Snapshot observeInternal(String selectedAddress, Integer rssi,
            long nowMs, boolean connected, boolean vehicleEligible,
            BluetoothConnectionEvidence proof, boolean legacy) {
        if (nowMs < 0 || (lastNowMs >= 0 && nowMs < lastNowMs)) {
            return result(Math.max(0, lastNowMs), Event.NONE, "out_of_order");
        }
        lastNowMs = nowMs;
        String normalizedAddress = normalizeAddress(selectedAddress);
        if (!same(address, normalizedAddress)) {
            clearSignal("device_changed");
            address = normalizedAddress;
        }
        if (address == null) return unknown(nowMs, "no_device");
        if (!vehicleEligible) return unknown(nowMs, "vehicle_ineligible");
        if (legacy) {
            if (connectionModeGeneration != null) clearSignal("connection_proof_unavailable");
            if (!connected) return unknown(nowMs, "disconnected");
        } else {
            if (proof == null || !proof.healthyAt(nowMs, config.staleMs))
                return unknown(nowMs, "connection_proof_unavailable");
            if ((proof.state == BluetoothConnectionEvidence.State.CONNECTED) != connected)
                return unknown(nowMs, "connection_proof_mismatch");
            if (connectionModeGeneration != null
                    && !connectionModeGeneration.equals(proof.modeGeneration))
                clearSignal("connection_source_changed");
            if (lastEvidenceMs >= 0 && nowMs - lastEvidenceMs > config.staleMs)
                clearSignal("stale_signal");
            connectionModeGeneration = proof.modeGeneration;
            connectionKnown = radioOn = true;
            if (!connected) return observeDisconnection(proof, nowMs);
            if (lastConnectionProofMs >= 0 && proof.observedAtElapsedMs < lastConnectionProofMs)
                return unknown(nowMs, "connection_proof_replayed");
            lastConnectionProofMs = proof.observedAtElapsedMs;
            trustedConnectionSeen = true;
            boolean returning = confirmedDisconnection || disconnectedSinceMs >= 0;
            clearDisconnection();
            if (returning) clearRssiForReconnect();
            if (rssi == null) {
                clearRssiForReconnect();
                lastEvidenceMs = proof.observedAtElapsedMs;
                reason = "missing_signal";
                return result(nowMs, Event.NONE, reason);
            }
        }
        if (rssi == null) return unknown(nowMs, "missing_signal");
        if (rssi < -127 || rssi > 20) return unknown(nowMs, "invalid_signal");
        if (lastSampleMs == nowMs) {
            return result(nowMs, Event.NONE, "duplicate_sample");
        }
        if (lastSampleMs >= 0 && nowMs - lastSampleMs > config.staleMs) {
            clearSignal("stale_signal");
        }

        rawRssi = rssi;
        lastSampleMs = nowMs;
        lastEvidenceMs = nowMs;
        evidenceKind = "rssi";
        samples.addLast(new Sample(nowMs, rssi));
        while (samples.size() > MAX_WINDOW_SAMPLES
                || (!samples.isEmpty() && nowMs - samples.peekFirst().timeMs > config.windowMs)) {
            samples.removeFirst();
        }
        medianRssi = median();
        if (config.separateNearConfirmation) {
            arrivalSamples.addLast(new Sample(nowMs, rssi));
            while (arrivalSamples.size() > 3
                    || (!arrivalSamples.isEmpty()
                    && nowMs - arrivalSamples.peekFirst().timeMs > config.nearWindowMs))
                arrivalSamples.removeFirst();
            arrivalMedianRssi = median(arrivalSamples);
            return observeSeparateNear(rssi, nowMs);
        }

        State zone = medianRssi >= config.nearThreshold ? State.NEAR
                : medianRssi <= config.awayThreshold ? State.AWAY : State.TRANSITION;
        if (zone == State.TRANSITION) {
            clearCandidate();
            reason = "within_hysteresis";
            return result(nowMs, Event.NONE, reason, true);
        }
        if (zone == stableState) {
            clearCandidate();
            reason = zone == State.NEAR ? "stable_near" : "stable_away";
            return result(nowMs, Event.NONE, reason, true);
        }
        if (candidate != zone) {
            candidate = zone;
            candidateSinceMs = nowMs;
            candidateSamples = 1;
        } else {
            candidateSamples++;
        }
        if (nowMs - candidateSinceMs < config.stableMs
                || candidateSamples < config.minStableSamples) {
            reason = zone == State.NEAR ? "stabilizing_near" : "stabilizing_away";
            return result(nowMs, Event.NONE, reason, true);
        }

        Event event = Event.NONE;
        if (zone == State.AWAY) {
            lockEpisodeEligible = stableState == State.NEAR && confirmedNearVisit
                    || carriedNearVisit && stableState == State.UNKNOWN;
            arrivalEpisodeEligible = false;
            if (lockEpisodeEligible) {
                event = Event.DEPARTURE;
            }
            armed = true;
            confirmedNearVisit = false;
        } else {
            arrivalEpisodeEligible = armed || config.unlockTrigger == UnlockTrigger.NEAR;
            lockEpisodeEligible = false;
            if (arrivalEpisodeEligible) event = Event.ARRIVAL;
            armed = false;
            confirmedNearVisit = true;
        }
        carriedNearVisit = false;
        stableState = zone;
        episodeId = ++nextEpisodeId;
        clearCandidate();
        reason = zone == State.NEAR ? "stable_near" : "stable_away";
        return result(nowMs, event, reason, true, zone == State.NEAR && !arrivalEpisodeEligible);
    }

    private Snapshot observeDisconnection(BluetoothConnectionEvidence proof, long nowMs) {
        if (proof.observedAtElapsedMs < lastConnectionProofMs)
            return unknown(nowMs, "connection_proof_replayed");
        if (proof.observedAtElapsedMs == lastConnectionProofMs)
            return result(nowMs, Event.NONE, "duplicate_connection_proof");
        lastConnectionProofMs = proof.observedAtElapsedMs;
        if (!trustedConnectionSeen) return unknown(nowMs, "disconnected_without_connection_history");
        boolean alreadyConfirmed = confirmedDisconnection;
        samples.clear();
        arrivalSamples.clear();
        nearRunSamples.clear();
        carriedNearVisit = false;
        rawRssi = null;
        medianRssi = arrivalMedianRssi = null;
        nearSinceMs = lastSampleMs = -1;
        clearCandidate();
        arrivalEpisodeEligible = false;
        if (disconnectedSinceMs < 0) {
            disconnectedSinceMs = proof.observedAtElapsedMs;
            disconnectionSamples = 0;
        }
        lastDisconnectedMs = lastEvidenceMs = proof.observedAtElapsedMs;
        if (disconnectionSamples < Integer.MAX_VALUE) disconnectionSamples++;
        if (disconnectionSamples < DISCONNECTION_CONFIRM_READS
                || lastDisconnectedMs - disconnectedSinceMs
                        < Math.max(DISCONNECTION_CONFIRM_SPAN_MS, config.stableMs)) {
            stableState = State.UNKNOWN;
            lockEpisodeEligible = false;
            evidenceKind = "unknown";
            reason = "confirming_disconnection";
            return result(nowMs, Event.NONE, reason);
        }
        confirmedDisconnection = true;
        stableState = State.AWAY;
        lockEpisodeEligible = true;
        armed = true;
        confirmedNearVisit = false;
        evidenceKind = reason = "confirmed_disconnection";
        if (!alreadyConfirmed) episodeId = ++nextEpisodeId;
        return result(nowMs, alreadyConfirmed ? Event.NONE : Event.DEPARTURE, reason, true);
    }

    private Snapshot observeSeparateNear(int rssi, long nowMs) {
        if (rssi >= config.nearThreshold) {
            if (nearSinceMs < 0 || (!nearRunSamples.isEmpty()
                    && nowMs - nearRunSamples.peekLast().timeMs > config.nearWindowMs)) {
                nearRunSamples.clear();
                nearSinceMs = nowMs;
            }
            nearRunSamples.addLast(new Sample(nowMs, rssi));
            while (nearRunSamples.size() > MAX_WINDOW_SAMPLES
                    || (!nearRunSamples.isEmpty()
                    && nowMs - nearRunSamples.peekFirst().timeMs > config.nearWindowMs))
                nearRunSamples.removeFirst();
            if (stableState == State.NEAR) {
                clearCandidate();
                reason = "stable_near";
                return result(nowMs, Event.NONE, reason, true);
            }
            candidate = State.NEAR;
            candidateSinceMs = nearSinceMs;
            candidateSamples = nearRunSamples.size();
            if (nowMs - nearSinceMs < config.nearStableMs
                    || candidateSamples < config.minStableSamples) {
                reason = "stabilizing_near";
                return result(nowMs, Event.NONE, reason, true);
            }
            arrivalEpisodeEligible = armed || config.unlockTrigger == UnlockTrigger.NEAR;
            lockEpisodeEligible = false;
            Event event = arrivalEpisodeEligible
                    ? Event.ARRIVAL : Event.NONE;
            armed = false;
            confirmedNearVisit = true;
            carriedNearVisit = false;
            stableState = State.NEAR;
            episodeId = ++nextEpisodeId;
            clearCandidate();
            reason = "stable_near";
            return result(nowMs, event, reason, true, !arrivalEpisodeEligible);
        }

        nearRunSamples.clear();
        nearSinceMs = -1;
        // Historical Away values cannot turn a current deadband reading into departure.
        if (rssi > config.awayThreshold || medianRssi > config.awayThreshold) {
            clearCandidate();
            reason = "within_hysteresis";
            return result(nowMs, Event.NONE, reason, true);
        }
        if (stableState == State.AWAY) {
            clearCandidate();
            reason = "stable_away";
            return result(nowMs, Event.NONE, reason, true);
        }
        if (candidate != State.AWAY) {
            candidate = State.AWAY;
            candidateSinceMs = nowMs;
            candidateSamples = 1;
        } else candidateSamples++;
        if (nowMs - candidateSinceMs < config.stableMs
                || candidateSamples < config.minStableSamples) {
            reason = "stabilizing_away";
            return result(nowMs, Event.NONE, reason, true);
        }
        lockEpisodeEligible = stableState == State.NEAR && confirmedNearVisit
                || carriedNearVisit && stableState == State.UNKNOWN;
        arrivalEpisodeEligible = false;
        Event event = lockEpisodeEligible
                ? Event.DEPARTURE : Event.NONE;
        armed = true;
        confirmedNearVisit = false;
        carriedNearVisit = false;
        stableState = State.AWAY;
        episodeId = ++nextEpisodeId;
        clearCandidate();
        reason = "stable_away";
        return result(nowMs, event, reason, true);
    }

    /** Ages a status read without adding a sample, advancing dwell, or emitting actions. */
    public synchronized Snapshot snapshot(long nowMs) {
        if (nowMs < 0 || (lastNowMs >= 0 && nowMs < lastNowMs)) {
            return result(Math.max(0, lastNowMs), Event.NONE, "out_of_order");
        }
        lastNowMs = nowMs;
        if (lastEvidenceMs >= 0 && nowMs - lastEvidenceMs > config.staleMs) {
            clearSignal("stale_signal");
        }
        return result(nowMs, Event.NONE, reason);
    }

    /** Releases all sample history and session state, suitable for a master switch turning off. */
    public synchronized void reset() {
        clearSignal("waiting_for_signal");
        address = null;
        lastNowMs = -1;
    }

    /**
     * Counts a connection the caller proved before a car-side radio restart as this phone's
     * connection history, so a later confirmed disconnection can depart. Any reset forgets it.
     */
    public synchronized void adoptConnectionHistory(String selectedAddress) {
        String normalized = normalizeAddress(selectedAddress);
        if (normalized == null) return;
        // A status-read revoke resets the address; the reading being adopted selects it again.
        if (address == null) address = normalized;
        if (normalized.equals(address)) trustedConnectionSeen = true;
    }

    /**
     * Counts a Near visit the caller proved with the car on as this phone's last confirmed zone, so
     * the first Away confirmation since a wipe can depart. Emits nothing; any wipe or zone forgets it.
     */
    public synchronized void adoptNearVisit(String selectedAddress) {
        String normalized = normalizeAddress(selectedAddress);
        if (normalized != null && normalized.equals(address) && stableState == State.UNKNOWN)
            carriedNearVisit = true;
    }

    public int nearThreshold() {
        return config.nearThreshold;
    }

    private Snapshot unknown(long nowMs, String unknownReason) {
        clearSignal(unknownReason);
        return result(nowMs, Event.NONE, reason);
    }

    private void clearSignal(String clearReason) {
        samples.clear();
        arrivalSamples.clear();
        nearRunSamples.clear();
        nearSinceMs = -1;
        rawRssi = null;
        medianRssi = null;
        arrivalMedianRssi = null;
        lastSampleMs = -1;
        lastEvidenceMs = lastConnectionProofMs = -1;
        connectionModeGeneration = null;
        connectionKnown = radioOn = trustedConnectionSeen = false;
        arrivalEpisodeEligible = lockEpisodeEligible = false;
        episodeId = 0;
        clearDisconnection();
        evidenceKind = "unknown";
        stableState = State.UNKNOWN;
        clearCandidate();
        armed = false;
        confirmedNearVisit = false;
        carriedNearVisit = false;
        reason = clearReason;
    }

    private void clearCandidate() {
        candidate = State.UNKNOWN;
        candidateSinceMs = -1;
        candidateSamples = 0;
    }

    private void clearDisconnection() {
        confirmedDisconnection = false;
        disconnectedSinceMs = lastDisconnectedMs = -1;
        disconnectionSamples = 0;
    }

    private void clearRssiForReconnect() {
        samples.clear();
        arrivalSamples.clear();
        nearRunSamples.clear();
        nearSinceMs = lastSampleMs = -1;
        rawRssi = null;
        medianRssi = arrivalMedianRssi = null;
        stableState = State.UNKNOWN;
        confirmedNearVisit = false;
        carriedNearVisit = false;
        arrivalEpisodeEligible = lockEpisodeEligible = false;
        evidenceKind = "unknown";
        clearCandidate();
    }

    private Snapshot result(long nowMs, Event event, String resultReason) {
        return result(nowMs, event, resultReason, false);
    }

    private Snapshot result(long nowMs, Event event, String resultReason, boolean newObservation) {
        return result(nowMs, event, resultReason, newObservation, false);
    }

    private Snapshot result(long nowMs, Event event, String resultReason, boolean newObservation,
            boolean firstConnectionNoPriorAway) {
        State state = confirmedDisconnection ? State.AWAY : medianRssi == null ? State.UNKNOWN
                : stableState == State.UNKNOWN ? State.TRANSITION : stableState;
        boolean nearFilter = config.separateNearConfirmation
                && (candidate == State.NEAR || stableState == State.NEAR);
        boolean departureEligible = confirmedDisconnection || stableState == State.AWAY
                && candidate == State.UNKNOWN && rawRssi != null && rawRssi <= config.awayThreshold
                && medianRssi != null && medianRssi <= config.awayThreshold;
        boolean arrivalCandidate = newObservation && arrivalEpisodeEligible
                && stableState == State.NEAR && candidate == State.UNKNOWN
                && rawRssi != null && rawRssi >= config.nearThreshold;
        boolean lockCandidate = newObservation && lockEpisodeEligible && departureEligible;
        return new Snapshot(address, rawRssi, nearFilter ? arrivalMedianRssi : medianRssi, state, stableState,
                candidate, candidateSinceMs < 0 ? 0 : Math.max(0, nowMs - candidateSinceMs),
                lastEvidenceMs < 0 ? -1 : Math.max(0, nowMs - lastEvidenceMs),
                samples.size(), candidateSamples, armed, event, resultReason,
                config.separateNearConfirmation ? arrivalMedianRssi : medianRssi, medianRssi,
                nearFilter ? "arrival" : "departure", nearRunSamples.size(),
                nearSinceMs < 0 ? 0 : Math.max(0, nowMs - nearSinceMs),
                candidate == State.NEAR, departureEligible, evidenceKind, connectionKnown, radioOn,
                disconnectedSinceMs < 0 ? 0 : Math.max(0, lastDisconnectedMs - disconnectedSinceMs),
                disconnectionSamples, arrivalCandidate, lockCandidate, episodeId,
                firstConnectionNoPriorAway);
    }

    private double median() {
        return median(samples);
    }

    private static double median(ArrayDeque<Sample> source) {
        int[] values = new int[source.size()];
        int index = 0;
        for (Sample sample : source) values[index++] = sample.rssi;
        Arrays.sort(values);
        int middle = values.length / 2;
        return values.length % 2 == 0
                ? (values[middle - 1] + values[middle]) / 2.0 : values[middle];
    }

    private static String normalizeAddress(String value) {
        if (value == null) return null;
        String normalized = value.trim().toUpperCase(Locale.US);
        return normalized.isEmpty() ? null : normalized;
    }

    private static boolean same(String first, String second) {
        return first == null ? second == null : first.equals(second);
    }
}

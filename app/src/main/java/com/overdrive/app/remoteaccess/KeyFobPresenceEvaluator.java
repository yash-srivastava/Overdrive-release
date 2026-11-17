package com.overdrive.app.remoteaccess;

import java.util.HashSet;
import org.json.JSONObject;

/**
 * Pure policy for completed physical-key searches. Heartbeats keep the source
 * healthy but never refresh a result or advance an absence confirmation.
 * The window is counted in consecutive completed Absent queries at the search
 * cadence derived from it (ten seconds, or five for windows that are not a
 * multiple of ten); {@link #relockGraceBlocks} is the pure relock-grace rule
 * applied after a proximity unlock. Since 20 Oct the handler passes the
 * {@code keyFobLockSamples} setting: 1 (the default) confirms absence on the
 * first clean Absent result ({@link #requiredAbsentSamples}).
 */
public final class KeyFobPresenceEvaluator {
    public static final long POLL_INTERVAL_MS = 10_000;
    public static final long RESULT_FRESH_MS = 20_000;
    public static final long SOURCE_FRESH_MS = 5_000;
    public static final long MAX_QUERY_MS = 6_000;
    public static final long FAST_POLL_INTERVAL_MS = 5_000;
    public static final long FAST_RESULT_FRESH_MS = 12_000;
    public static final long RELOCK_GRACE_MS = 15_000;

    /** Search spacing for a window: ten seconds for multiples of ten, otherwise five (B2). */
    public static long pollIntervalMs(long windowMs) {
        return windowMs % POLL_INTERVAL_MS == 0 ? POLL_INTERVAL_MS : FAST_POLL_INTERVAL_MS;
    }

    /** Result freshness for a cadence: one missed cycle is tolerated, two are not. */
    public static long resultFreshMs(long pollIntervalMs) {
        return pollIntervalMs == POLL_INTERVAL_MS ? RESULT_FRESH_MS : FAST_RESULT_FRESH_MS;
    }

    /**
     * Consecutive completed Absent queries that span the window at the cadence: N results at
     * spacing I cover (N-1)*I, so window / interval + 1, never fewer than two.
     */
    public static int minimumAbsentSamples(long windowMs, long pollIntervalMs) {
        return (int) Math.max(2, windowMs / pollIntervalMs + 1);
    }

    /** 20 Oct: bounds of the {@code keyFobLockSamples} setting. */
    public static final int MIN_LOCK_SAMPLES = 1;
    public static final int MAX_LOCK_SAMPLES = 3;

    /**
     * 20 Oct (snappy locks): completed Absent queries that confirm absence for a {@code keyFobLockSamples}
     * setting. 1 confirms on the first clean Absent result; 2-3 need the greater of the setting and
     * {@link #minimumAbsentSamples}, so they still span the window. Present, unknown and stale results
     * reset the count exactly as before.
     */
    public static int requiredAbsentSamples(long windowMs, long pollIntervalMs, int lockSamples) {
        return lockSamples <= MIN_LOCK_SAMPLES ? MIN_LOCK_SAMPLES
                : Math.max(lockSamples, minimumAbsentSamples(windowMs, pollIntervalMs));
    }

    /**
     * B4: an automatic lock is held for {@link #RELOCK_GRACE_MS} after a dispatched proximity
     * unlock unless a door or lid opened after that dispatch. A lock or no action never blocks;
     * a clock regression blocks, as the cooldown policy does.
     */
    public static boolean relockGraceBlocks(String lastActionKind, long lastActionElapsed,
                                            long lastDoorOpenedElapsed, long now) {
        return relockGraceBlocks(lastActionKind, lastActionElapsed, lastDoorOpenedElapsed, now, RELOCK_GRACE_MS);
    }

    /**
     * B4 with the grace period supplied by the caller (18 Oct: the {@code relockGraceMs} setting,
     * read live from the effective configuration); the four-argument form keeps the original
     * {@link #RELOCK_GRACE_MS}. A non-positive grace never blocks.
     */
    public static boolean relockGraceBlocks(String lastActionKind, long lastActionElapsed,
                                            long lastDoorOpenedElapsed, long now, long graceMs) {
        if (!"unlock".equals(lastActionKind) || lastActionElapsed < 0 || graceMs <= 0) return false;
        if (now < lastActionElapsed) return true;
        if (now - lastActionElapsed >= graceMs) return false;
        return !(lastDoorOpenedElapsed >= lastActionElapsed && lastDoorOpenedElapsed <= now);
    }

    public static final class Observation {
        public final KeyFobEventEvaluator.Observation scope;
        public final boolean provided, active;
        public final String state, searchStatus;
        public final long generation, resultId, cycleId, observed, queryStarted;

        private Observation(JSONObject value, KeyFobEventEvaluator.Observation scope) {
            this.scope = scope;
            provided = value.has("presenceObservationState");
            if (!provided) {
                active = false;
                state = "unknown";
                searchStatus = "unavailable";
                generation = resultId = cycleId = 0;
                observed = queryStarted = -1;
                return;
            }
            state = text(value, "presenceObservationState", "present", "absent", "unknown");
            searchStatus = text(value, "presenceSearchStatus", "idle", "pending", "unavailable", "paused_locked");
            Object sourceActive = value.opt("presenceSourceActive");
            if (!(sourceActive instanceof Boolean))
                throw new IllegalArgumentException("presenceSourceActive must be a boolean");
            active = (Boolean) sourceActive;
            generation = integer(value, "presenceSourceGeneration", 1);
            resultId = integer(value, "presenceObservationId", 0);
            cycleId = integer(value, "presenceSearchCycleId", 0);
            observed = integer(value, "presenceObservationElapsedRealtimeMs", -1);
            queryStarted = integer(value, "presenceQueryStartedElapsedRealtimeMs", -1);
            if (resultId == 0) {
                if (!"unknown".equals(state) || cycleId != 0 || observed != -1 || queryStarted != -1)
                    throw new IllegalArgumentException("A presence value requires a completed query");
            } else if (cycleId == 0 || observed < 0 || queryStarted < 0 || observed < queryStarted) {
                throw new IllegalArgumentException("Invalid completed presence query timestamps");
            }
            if ("paused_locked".equals(searchStatus)
                    && (active || scope.power != 0 || scope.driverLock != 2)) {
                throw new IllegalArgumentException("A locked search pause requires an off/locked scope");
            }
        }

        public static Observation parse(JSONObject value, KeyFobEventEvaluator.Observation scope) {
            return new Observation(value, scope);
        }
    }

    public static final class Snapshot {
        public final String state, reason;
        public final boolean active, fresh, armed, departureKnown, lockCandidate;
        public final ProximityEvaluator.Event event;
        public final long absenceSinceElapsedMs, absentForMs, windowMs, lastObservationAgeMs, heartbeatAgeMs, scopeAgeMs;
        public final long pollIntervalMs;
        public final int sampleCount, requiredSamples;

        private Snapshot(KeyFobPresenceEvaluator owner, long now, ProximityEvaluator.Event event) {
            this(owner, now, event, false);
        }

        private Snapshot(KeyFobPresenceEvaluator owner, long now, ProximityEvaluator.Event event,
                         boolean completedAbsentQuery) {
            active = owner.sourceHealthy(now);
            boolean resultAgeFresh = fresh(now, owner.lastObserved, owner.resultFreshMs);
            fresh = active && resultAgeFresh && !"unknown".equals(owner.lastState);
            state = fresh ? owner.lastState.toUpperCase(java.util.Locale.US) : "UNKNOWN";
            reason = !active ? owner.reason : !resultAgeFresh ? "presence_result_stale"
                    : "fresh_key_approach".equals(owner.reason) ? owner.reason
                    : "present".equals(owner.lastState) ? "physical_key_present"
                    : "absent".equals(owner.lastState)
                        ? owner.power != 0 ? "presence_vehicle_on"
                            : owner.driverLock != 1 ? "presence_vehicle_locked"
                            : owner.absenceConfirmed ? "physical_key_absence_confirmed" : owner.reason
                        : owner.reason;
            armed = fresh && owner.power == 0 && owner.driverLock == 1
                    && !owner.absenceConfirmed;
            departureKnown = owner.allowsLock(now);
            // Only a newly completed native query can renew an attempt. A status
            // read, pending query or heartbeat never creates an actuation lease.
            lockCandidate = completedAbsentQuery && owner.driverLock == 1
                    && departureKnown && owner.departureId == owner.resultId
                    && fresh(now, owner.lastObserved, KeyFobEventEvaluator.FRESH_MS);
            this.event = event;
            absenceSinceElapsedMs = owner.absenceSince;
            // Countdown advances only on actual query results, never a heartbeat.
            absentForMs = owner.absenceSince < 0 || owner.lastAbsent < owner.absenceSince
                    ? 0 : owner.lastAbsent - owner.absenceSince;
            windowMs = owner.windowMs;
            pollIntervalMs = owner.pollIntervalMs;
            requiredSamples = owner.minimumSamples;
            sampleCount = owner.absentSamples;
            lastObservationAgeMs = owner.lastObserved < 0 || owner.lastObserved > now
                    ? -1 : now - owner.lastObserved;
            heartbeatAgeMs = age(now, owner.heartbeat);
            scopeAgeMs = age(now, owner.scopeObserved);
        }

        public JSONObject toJson() throws Exception {
            return new JSONObject().put("state", state).put("reason", reason).put("active", active)
                    .put("fresh", fresh).put("armed", armed).put("departureKnown", departureKnown)
                    .put("lockCandidate", lockCandidate)
                    .put("absenceSinceElapsedMs", absenceSinceElapsedMs < 0 ? JSONObject.NULL : absenceSinceElapsedMs)
                    .put("absentForMs", absentForMs).put("windowMs", windowMs).put("sampleCount", sampleCount)
                    .put("pollIntervalMs", pollIntervalMs).put("requiredSamples", requiredSamples)
                    .put("event", event.name())
                    .put("lastObservationAgeMs", lastObservationAgeMs < 0 ? JSONObject.NULL : lastObservationAgeMs)
                    .put("heartbeatAgeMs", heartbeatAgeMs < 0 ? JSONObject.NULL : heartbeatAgeMs)
                    .put("scopeAgeMs", scopeAgeMs < 0 ? JSONObject.NULL : scopeAgeMs);
        }
    }

    private final long expectedRevision, windowMs, pollIntervalMs, resultFreshMs;
    private final int minimumSamples;
    private final HashSet<String> retiredInstances = new HashSet<>();
    private String instance = "", lastState = "unknown", lastResultState = "unknown",
            reason = "waiting_for_physical_key_search";
    private long generation = -1, serial, resultId, cycleId, lastObserved = -1, lastQueryStarted = -1;
    private long heartbeat = -1, scopeObserved = -1, absenceSince = -1, lastAbsent = -1, departureId = -1;
    private int power = -1, driverLock = -1, absentSamples;
    private boolean active, pausedLocked, absenceConfirmed;

    /** The original rule: the window-derived count, never fewer than two ({@link #minimumAbsentSamples}). */
    public KeyFobPresenceEvaluator(long expectedRevision, long windowMs) {
        this(expectedRevision, windowMs, 2);
    }

    /** 20 Oct: {@code lockSamples} is the {@code keyFobLockSamples} setting (1-3), see {@link #requiredAbsentSamples}. */
    public KeyFobPresenceEvaluator(long expectedRevision, long windowMs, int lockSamples) {
        long interval = pollIntervalMs(windowMs);
        if (expectedRevision < 0 || windowMs < interval || windowMs > 300_000
                || windowMs % interval != 0)
            throw new IllegalArgumentException("Invalid physical-key absence window");
        if (lockSamples < MIN_LOCK_SAMPLES || lockSamples > MAX_LOCK_SAMPLES)
            throw new IllegalArgumentException("Invalid physical-key absence result count");
        this.expectedRevision = expectedRevision;
        this.windowMs = windowMs;
        this.pollIntervalMs = interval;
        this.resultFreshMs = resultFreshMs(interval);
        this.minimumSamples = requiredAbsentSamples(windowMs, interval, lockSamples);
    }

    public Snapshot observe(Observation value, long now, boolean enabled) {
        KeyFobEventEvaluator.Observation source = value.scope;
        if (now < 0 || source.scopeElapsed > now || value.observed > now || value.queryStarted > now) {
            invalidate("presence_timestamp_invalid");
            throw new IllegalArgumentException("Presence timestamp is in the future");
        }
        if (!instance.equals(source.instance)) {
            if (retiredInstances.contains(source.instance))
                throw new IllegalArgumentException("Retired presence source");
            if (!instance.isEmpty()) retiredInstances.add(instance);
            if (retiredInstances.size() > 64)
                throw new IllegalArgumentException("Too many presence source instances");
            resetSource(source.instance, value.generation);
        }
        if (!value.provided) {
            if (source.serial <= serial)
                throw new IllegalArgumentException("Presence observation was already consumed");
            serial = source.serial;
            heartbeat = now;
            scopeObserved = source.scopeElapsed;
            invalidate("presence_source_unavailable");
            return new Snapshot(this, now, ProximityEvaluator.Event.NONE);
        }
        if (source.serial <= serial || value.generation < generation)
            throw new IllegalArgumentException("Presence observation was already consumed");
        if (value.generation != generation) resetSource(source.instance, value.generation);
        if (!fresh(now, heartbeat, SOURCE_FRESH_MS)
                || !fresh(now, lastObserved, resultFreshMs)) {
            reason = !fresh(now, heartbeat, SOURCE_FRESH_MS)
                    ? "presence_collector_stale" : "presence_result_stale";
            clearEvidence();
        }
        serial = source.serial;
        heartbeat = now;
        scopeObserved = source.scopeElapsed;
        power = source.power;
        driverLock = source.driverLock;
        pausedLocked = enabled && source.revision == expectedRevision && !value.active
                && "paused_locked".equals(value.searchStatus) && power == 0 && driverLock == 2
                && fresh(now, scopeObserved, SOURCE_FRESH_MS);
        active = enabled && source.revision == expectedRevision && value.provided && value.active
                && power >= 0 && power <= 3 && (driverLock == 1 || driverLock == 2)
                && fresh(now, scopeObserved, SOURCE_FRESH_MS);
        if (!active) {
            clearEvidence();
            reason = !enabled ? "presence_disabled" : source.revision != expectedRevision ? "presence_revoked"
                    : pausedLocked ? "presence_search_paused_locked"
                    : !value.provided ? "presence_source_unavailable"
                    : !value.active ? power > 0 && power <= 3 ? "presence_vehicle_on" : "presence_source_unavailable"
                    : power < 0 || power > 3 ? "presence_power_unknown"
                    : driverLock != 1 && driverLock != 2 ? "presence_lock_unknown" : "presence_scope_stale";
            return new Snapshot(this, now, ProximityEvaluator.Event.NONE);
        }
        if (value.resultId < resultId || (value.resultId == resultId
                && (value.cycleId != cycleId || value.observed != lastObserved
                    || value.queryStarted != lastQueryStarted || !value.state.equals(lastResultState)))) {
            invalidate("presence_result_replayed");
            throw new IllegalArgumentException("Presence result changed without a new query");
        }
        // Only an off, unlocked vehicle can accumulate an absence window.
        if (power != 0 || driverLock != 1) clearAbsence();
        if (value.resultId == resultId)
            return new Snapshot(this, now, ProximityEvaluator.Event.NONE);
        if (value.cycleId <= cycleId || value.observed <= lastObserved) {
            invalidate("presence_result_replayed");
            throw new IllegalArgumentException("Presence query result was already consumed");
        }
        resultId = value.resultId;
        cycleId = value.cycleId;
        lastObserved = value.observed;
        lastQueryStarted = value.queryStarted;
        lastState = value.state;
        lastResultState = value.state;
        ProximityEvaluator.Event event = ProximityEvaluator.Event.NONE;
        boolean lockCandidate = false;
        boolean unknown = "unknown".equals(value.state);
        long queryDuration = lastObserved - lastQueryStarted;
        if (!fresh(now, lastObserved, resultFreshMs)
                || queryDuration > MAX_QUERY_MS || (!unknown && queryDuration == MAX_QUERY_MS)) {
            clearEvidence();
            reason = "presence_result_stale";
        } else if (unknown) {
            // Unavailable is never absence. New completed Absent queries (the
            // required count) must establish absence again after this result.
            clearEvidence();
            reason = "physical_key_search_unavailable";
        } else if ("present".equals(value.state)) {
            clearAbsence();
            reason = "physical_key_present";
        } else if (power != 0) {
            reason = "presence_vehicle_on";
        } else if (driverLock != 1) {
            reason = "presence_vehicle_locked";
        } else {
            if (absenceSince < 0) absenceSince = lastObserved;
            lastAbsent = lastObserved;
            if (!absenceConfirmed && absentSamples < Integer.MAX_VALUE) absentSamples++;
            // B3: the window is counted in consecutive completed Absent queries at the cadence;
            // every reset above (present, unknown, stale, source gap, power/lock) still applies.
            if (!absenceConfirmed && absentSamples >= minimumSamples) {
                absenceConfirmed = true;
                if (driverLock == 1)
                    event = ProximityEvaluator.Event.DEPARTURE;
            }
            if (absenceConfirmed && driverLock == 1) {
                // A temporary command guard may have blocked the first edge.
                // Renew only from this new query's original callback timestamp.
                departureId = resultId;
                lockCandidate = true;
            }
            reason = absenceConfirmed ? "physical_key_absence_confirmed" : "confirming_absence";
        }
        return new Snapshot(this, now, event, lockCandidate);
    }

    public Snapshot current(long now) {
        if (pausedLocked && power == 0 && driverLock == 2
                && fresh(now, heartbeat, SOURCE_FRESH_MS)
                && fresh(now, scopeObserved, SOURCE_FRESH_MS)) {
            reason = "presence_search_paused_locked";
            return new Snapshot(this, now, ProximityEvaluator.Event.NONE);
        }
        if (!sourceHealthy(now) || !fresh(now, lastObserved, resultFreshMs)) {
            pausedLocked = false;
            clearEvidence();
            reason = !sourceHealthy(now) ? "presence_collector_stale" : "presence_result_stale";
        }
        return new Snapshot(this, now, ProximityEvaluator.Event.NONE);
    }

    public boolean allowsLock(long now) {
        return sourceHealthy(now) && power == 0 && driverLock == 1
                && fresh(now, lastObserved, resultFreshMs)
                && "absent".equals(lastState) && absenceConfirmed;
    }

    public boolean decisionStillValid(Observation value, long now) {
        return value.scope.revision == expectedRevision && instance.equals(value.scope.instance)
                && generation == value.generation && departureId == value.resultId
                && driverLock == 1 && allowsLock(now)
                && fresh(now, value.observed, KeyFobEventEvaluator.FRESH_MS);
    }

    /**
     * Cloud fallback re-validation (17 Oct product decision): everything {@link #decisionStillValid}
     * checks EXCEPT the age of the departure result itself. That 5 s bound belongs to the first
     * (local) dispatch; the cloud leg continues that already-dispatched decision, so only revocation
     * counts: the same source, generation and departure result, the car still off and unlocked, and
     * the absence still standing ({@link #allowsLock}: healthy source, a result within its own
     * freshness, absent and confirmed — a new "present" result or a timed-out search revokes).
     */
    public boolean decisionStillCurrent(Observation value, long now) {
        return value.scope.revision == expectedRevision && instance.equals(value.scope.instance)
                && generation == value.generation && departureId == value.resultId
                && driverLock == 1 && allowsLock(now);
    }

    /** Invalid input immediately retires all prior absence evidence. */
    public void invalidate(String failureReason) {
        active = false;
        pausedLocked = false;
        clearEvidence();
        reason = failureReason;
    }

    /**
     * A fresh passive Near observation vetoes locking without supplying an Away
     * result. A new sequence of completed absent searches must start the window.
     */
    public void vetoFreshApproach() {
        clearAbsence();
        reason = "fresh_key_approach";
    }

    /**
     * Shared radio preferences are independent of physical searches. Any other
     * saved/effective rule change, including a revision change, requires reset.
     */
    public static boolean canRetainAcrossRadioChange(JSONObject oldSaved, JSONObject oldEffective,
                                                     JSONObject newSaved, JSONObject newEffective) {
        try {
            return oldSaved.optLong("revision", -1) == newSaved.optLong("revision", -2)
                    && withoutRadio(oldSaved).equals(withoutRadio(newSaved))
                    && withoutRadio(oldEffective).equals(withoutRadio(newEffective));
        } catch (Exception malformed) { return false; }
    }

    private static String withoutRadio(JSONObject value) throws Exception {
        JSONObject copy = new JSONObject(value.toString());
        copy.remove("keepBluetoothOn");
        return copy.toString();
    }

    private boolean sourceHealthy(long now) {
        return active && fresh(now, heartbeat, SOURCE_FRESH_MS)
                && fresh(now, scopeObserved, SOURCE_FRESH_MS);
    }

    private void resetSource(String newInstance, long newGeneration) {
        clearEvidence();
        pausedLocked = false;
        instance = newInstance;
        generation = newGeneration;
        serial = resultId = cycleId = 0;
        lastObserved = lastQueryStarted = heartbeat = scopeObserved = -1;
        lastState = "unknown";
        lastResultState = "unknown";
    }

    private void clearEvidence() {
        lastState = "unknown";
        clearAbsence();
    }

    private void clearAbsence() {
        absenceSince = lastAbsent = -1;
        absentSamples = 0;
        absenceConfirmed = false;
        departureId = -1;
    }

    private static boolean fresh(long now, long observed, long ttl) {
        return observed >= 0 && observed <= now && now - observed <= ttl;
    }

    private static long age(long now, long observed) {
        return observed < 0 || observed > now ? -1 : now - observed;
    }

    private static long integer(JSONObject value, String key, long min) {
        Object raw = value.opt(key);
        if (!(raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long)
                || ((Number) raw).longValue() < min)
            throw new IllegalArgumentException(key + " must be an integer");
        return ((Number) raw).longValue();
    }

    private static String text(JSONObject value, String key, String... allowed) {
        Object raw = value.opt(key);
        for (String candidate : allowed) if (candidate.equals(raw)) return candidate;
        throw new IllegalArgumentException(key + " is invalid");
    }
}

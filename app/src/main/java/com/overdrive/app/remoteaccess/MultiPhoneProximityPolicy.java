package com.overdrive.app.remoteaccess;

import java.util.List;

import org.json.JSONObject;

/** Physical-key departure takes priority; phone fallback requires a complete Away roster. */
public final class MultiPhoneProximityPolicy {
    /**
     * Rule (b) grace (18 Oct): a trusted phone that has never connected in the current monitor
     * generation counts as Away for LOCK consensus only after the monitor has been running this
     * long with the car's radio On. A bonded phone inside the car with Bluetooth On connects
     * within seconds and is then visible through its RSSI (which still vetoes); a phone with
     * Bluetooth Off is undetectable by any means, and the existing confirmed-disconnection path
     * already treats that phone as Away. 20 Oct (snappy locks): 15 s, down from 60 s.
     */
    public static final long ABSENT_PHONE_GRACE_MS = 15_000;

    private MultiPhoneProximityPolicy() {}

    public static final class Observation {
        public final boolean enabled;
        public final boolean connected;
        public final boolean rawPresent;
        public final ProximityEvaluator.State state;
        public final long lastObservedMs;
        public final boolean confirmedDisconnection;
        // 18 Oct additive fields; the older constructors pass the conservative defaults below, which
        // make rules (a) and (b) unreachable, so an observation built from them behaves as before.
        /** A fresh positive native connection read agreed with {@link #connected}; never inferred. */
        public final boolean connectionKnown;
        /** A fresh positive native read found the car's radio On. */
        public final boolean radioOn;
        /** No connected observation of this phone in the current monitor generation. */
        public final boolean neverConnected;
        /** First observation of the current monitor generation, or -1 when unknown. */
        public final long monitorStartedElapsedMs;
        /** Time of the native read behind {@link #connectionKnown}/{@link #radioOn}, or -1 when none. */
        public final long nativeObservedMs;
        /** Latest connected observation that was not a departure-eligible Away reading, or -1. */
        public final long lastNearEvidenceMs;

        public Observation(boolean enabled, boolean connected, boolean rawPresent,
                ProximityEvaluator.State state, long lastObservedMs) {
            this(enabled, connected, rawPresent, state, lastObservedMs, false);
        }

        public Observation(boolean enabled, boolean connected, boolean rawPresent,
                ProximityEvaluator.State state, long lastObservedMs, boolean confirmedDisconnection) {
            this(enabled, connected, rawPresent, state, lastObservedMs, confirmedDisconnection,
                    false, false, false, -1, -1, -1);
        }

        public Observation(boolean enabled, boolean connected, boolean rawPresent,
                ProximityEvaluator.State state, long lastObservedMs, boolean confirmedDisconnection,
                boolean connectionKnown, boolean radioOn, boolean neverConnected,
                long monitorStartedElapsedMs, long nativeObservedMs, long lastNearEvidenceMs) {
            this.enabled = enabled;
            this.connected = connected;
            this.rawPresent = rawPresent;
            this.state = state;
            this.lastObservedMs = lastObservedMs;
            this.confirmedDisconnection = confirmedDisconnection;
            this.connectionKnown = connectionKnown;
            this.radioOn = radioOn;
            this.neverConnected = neverConnected;
            this.monitorStartedElapsedMs = monitorStartedElapsedMs;
            this.nativeObservedMs = nativeObservedMs;
            this.lastNearEvidenceMs = lastNearEvidenceMs;
        }
    }

    /**
     * A phone-triggered lock requires every participating phone to be positively Away.
     * The caller must include an Unknown observation for any enabled phone with
     * no reading; omitted roster members cannot be inferred from this list.
     * Only positively confirmed native disconnection can replace an RSSI Away
     * reading. Absent data, future timestamps and expiry never mean Away, with
     * the two explicit absent-phone exceptions of {@link #absentAway}.
     */
    public static boolean allAway(List<Observation> observations, long now, long staleMs) {
        if (observations == null || now < 0 || staleMs <= 0) return false;
        boolean hasEnabledPhone = false;
        for (Observation observation : observations) {
            if (observation == null) return false;
            if (!observation.enabled) continue;
            hasEnabledPhone = true;
            if (!awayForLock(observation, now, staleMs)) return false;
        }
        return hasEnabledPhone;
    }

    /**
     * One enabled phone's Away verdict for LOCK consensus: the original rule (fresh connected RSSI
     * Away, or confirmed native disconnection), else the absent-phone rules of {@link #absentAway}.
     */
    public static boolean awayForLock(Observation observation, long now, long staleMs) {
        if (observation == null || now < 0 || staleMs <= 0) return false;
        boolean evidence = observation.connected && observation.rawPresent
                || observation.confirmedDisconnection && !observation.connected
                    && !observation.rawPresent;
        if (evidence && observation.state == ProximityEvaluator.State.AWAY
                && observation.lastObservedMs >= 0 && observation.lastObservedMs <= now
                && now - observation.lastObservedMs <= staleMs) return true;
        return absentAway(observation, now, staleMs);
    }

    /**
     * 18 Oct absent-phone rules, both additive to the original verdict. An enabled phone that is
     * not connected and shows no RSSI counts as Away for LOCK consensus when
     * (a) a fresh positive native read says it is disconnected (connectionKnown with connected
     * false), or (b) it has never connected in this monitor generation, the monitor has run for
     * at least {@link #ABSENT_PHONE_GRACE_MS} and a fresh native read found the radio On.
     * In both cases the native read must be within {@code staleMs}, the phone's state must not be
     * Near or Transition, and no connected non-Away evidence may be fresher than {@code staleMs}.
     * Everything else vetoes exactly as before: a connected phone (Near, Transition or without
     * RSSI), fresh Near evidence, a radio Off or unknown without a positive disconnection read,
     * stale or future timestamps, and a phone that connected once but has no fresh native read.
     * Rationale: a bonded phone inside the car with Bluetooth On connects within seconds and is
     * then visible through Near (which vetoes); a phone with Bluetooth Off is undetectable by any
     * means, and the confirmed-disconnection path already treats that as Away, so neither rule is
     * less safe than today's verdict. Only the roster's absent members can satisfy them: a single
     * departing phone must still carry its own confirmed departure.
     */
    public static boolean absentAway(Observation observation, long now, long staleMs) {
        if (observation == null || now < 0 || staleMs <= 0) return false;
        if (observation.connected || observation.rawPresent) return false;
        if (observation.state == ProximityEvaluator.State.NEAR
                || observation.state == ProximityEvaluator.State.TRANSITION) return false;
        if (observation.lastNearEvidenceMs > now || observation.lastNearEvidenceMs >= 0
                && now - observation.lastNearEvidenceMs <= staleMs) return false;
        if (observation.nativeObservedMs < 0 || observation.nativeObservedMs > now
                || now - observation.nativeObservedMs > staleMs || !observation.radioOn) return false;
        if (observation.connectionKnown) return true;
        return observation.neverConnected && observation.monitorStartedElapsedMs >= 0
                && observation.monitorStartedElapsedMs <= now
                && now - observation.monitorStartedElapsedMs >= ABSENT_PHONE_GRACE_MS;
    }

    /**
     * M2 (18 Oct): the shared phone rules an evaluator is built from or the lock consensus acts on
     * are equal in both effective settings: master switch, Learning mode, unlock trigger and the
     * departure/arrival timing. Revision, the phone roster, the fob rules and the shared radio
     * switch may differ; a per-phone entry is compared separately with {@link #sameProfile}.
     */
    public static boolean canRetainPhoneRules(JSONObject oldEffective, JSONObject newEffective) {
        try {
            if (oldEffective == null || newEffective == null) return false;
            if (oldEffective.optBoolean("enabled") != newEffective.optBoolean("enabled")
                    || oldEffective.optBoolean("dryRun", true) != newEffective.optBoolean("dryRun", true)
                    || !oldEffective.optString("unlockTrigger", "away_to_near")
                            .equals(newEffective.optString("unlockTrigger", "away_to_near"))) return false;
            for (String key : new String[] {"staleMs", "windowMs", "dwellMs", "nearWindowMs", "nearDwellMs"})
                if (oldEffective.optLong(key, -1) != newEffective.optLong(key, -1)) return false;
            return true;
        } catch (Exception malformed) { return false; }
    }

    /** M2: a phone's own entry is unchanged: address, name, enabled and both thresholds. */
    public static boolean sameProfile(JSONObject oldProfile, JSONObject newProfile) {
        try {
            return oldProfile != null && newProfile != null
                    && oldProfile.length() == newProfile.length()
                    && oldProfile.getString("address").equalsIgnoreCase(newProfile.getString("address"))
                    && oldProfile.optString("name").equals(newProfile.optString("name"))
                    && oldProfile.optBoolean("enabled") == newProfile.optBoolean("enabled")
                    && oldProfile.optInt("nearThreshold", -12) == newProfile.optInt("nearThreshold", -12)
                    && oldProfile.optInt("awayThreshold", -24) == newProfile.optInt("awayThreshold", -24);
        } catch (Exception malformed) { return false; }
    }

    /**
     * Lock consensus after the switch-off guard. With both opt-in flags off this is the original rule:
     * a detected key vetoes, then {@link #canLock}. Key-inside consent lets a phone departure pass a
     * detected key and skip the key-search wait; phone priority lets a confirmed-Near phone veto a
     * keyfob departure. {@code fobAway} is already gated on keyfob locking being configured.
     */
    public static boolean consensus(boolean phoneDeparture, boolean keyDetected, boolean keyInsideConsent,
            boolean phonePriority, boolean fobConfigured, boolean waitsForKeyAbsence, boolean fobAway,
            List<Observation> observations, long now, long staleMs) {
        boolean keyInside = phoneDeparture && keyInsideConsent;
        if (keyDetected && !keyInside) return false;
        if (!phoneDeparture && phonePriority && anyConfirmedNear(observations, now, staleMs)) return false;
        return canLock(phoneDeparture, !phoneDeparture || (fobConfigured && !keyInside && waitsForKeyAbsence),
                fobAway, observations, now, staleMs);
    }

    /** Phone priority: an enabled phone whose fresh connected reading is a confirmed Near. */
    public static boolean anyConfirmedNear(List<Observation> observations, long now, long staleMs) {
        if (observations == null || now < 0 || staleMs <= 0) return false;
        for (Observation observation : observations) {
            if (observation != null && observation.enabled && observation.connected && observation.rawPresent
                    && observation.state == ProximityEvaluator.State.NEAR
                    && observation.lastObservedMs >= 0 && observation.lastObservedMs <= now
                    && now - observation.lastObservedMs <= staleMs) return true;
        }
        return false;
    }

    /**
     * A verified physical-key departure is authoritative and independent of
     * saved phone observations. Phone-triggered fallback requires every
     * participating phone to be freshly Away, plus confirmed fob absence when
     * that source is configured. The caller must validate fobAway against its
     * fresh native search result, source generation and current vehicle guards.
     */
    public static boolean canLock(boolean phoneDeparture, boolean requiresFobAway, boolean fobAway,
            List<Observation> observations, long now, long staleMs) {
        if (now < 0 || staleMs <= 0) return false;
        if (!phoneDeparture) return requiresFobAway && fobAway;
        return (!requiresFobAway || fobAway) && allAway(observations, now, staleMs);
    }
}

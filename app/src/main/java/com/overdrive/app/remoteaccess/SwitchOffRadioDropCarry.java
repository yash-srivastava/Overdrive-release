package com.overdrive.app.remoteaccess;

/**
 * Restores a phone's recent connection history using fresh passive native evidence.
 * A radio or observation outage is never departure evidence. Restoration requires continuous
 * radio-on disconnection and verified quiet closures; active reconnect failures are irrelevant.
 * Pure state, confined to the caller's phone-state lock.
 */
public final class SwitchOffRadioDropCarry {
    /** The last healthy connected reading with the car on, measured to the first parked reading. */
    public static final long EDGE_WINDOW_MS = 30_000;
    /** Early parked readings may still be connected before the head unit shuts its radio down. */
    public static final long DROP_WINDOW_MS = 20_000;
    /** Bounds restoring pre-switch-off history; a continuously fresh confirmed departure outlives it. */
    public static final long CARRY_TTL_MS = 300_000;
    public static final long DOOR_QUIET_MS = 15_000;
    public static final long RADIO_READY_GRACE_MS = 30_000;

    private enum Stage { IDLE, ARMED, DROPPED, RADIO_BACK, CARRIED, DONE }

    private Stage stage = Stage.IDLE;
    private boolean vehicleWasOn;
    private long onConnectedMs = -1, onDropMs = -1, edgeMs = -1;
    private long lastObservedMs = -1, radioReadyMs = -1, lastDisconnectedMs = -1;
    private long doorsClosedMs = -1, staleMs;
    private long parkedConnectedMs = -1, parkedRevision;
    private String parkedInstance = "", parkedGeneration = "";
    private boolean parkedProofInterrupted;
    private int disconnectedReadings;
    private boolean historyGranted, historyRevoked, guardRequired;
    private String instance = "";
    private String generation = "", onGeneration = "";
    private long revision;
    private final StringBuilder transitions = new StringBuilder();

    /**
     * @param power vehicle power with the reading: 0 off, above 0 on, anything else unknown
     * @param observedMs native read time for healthy evidence; envelope time for a radio outage
     * @param modeGeneration committed car mode, including when a shutdown packet has no native mode tag
     * @param doorsClosed all six closures were freshly read closed and their edge observer is active
     * @return true when the caller must adopt the connection history before evaluating this reading
     */
    public boolean observe(long observedMs, long nowMs, int power, boolean eligible, String sourceInstance,
            String modeGeneration, long settingsRevision, boolean healthy, boolean connected,
            boolean radioDown, boolean doorsClosed, long lastDoorActivityMs, long staleMs) {
        transitions.setLength(0);
        historyRevoked = false;
        if (staleMs <= 0 || observedMs < 0 || nowMs < observedMs
                || nowMs - observedMs > staleMs) {
            invalidateProof();
            return false;
        }
        if (observedMs <= lastObservedMs) {
            if (observedMs < lastObservedMs) invalidateProof();
            return false;
        }
        // A collector or transport pause may have no explicit radio-off packet.
        // The first fresh read after that gap must earn a new recovery grace.
        if (lastObservedMs >= 0 && observedMs - lastObservedMs > staleMs) invalidateProof();
        lastObservedMs = observedMs;
        this.staleMs = staleMs;
        if (power > 0) {
            if (reported()) mark("cancelled_vehicle_on");
            revokeHistory();
            guardRequired = false;
            resetGrace();
            clearParkedConnection();
            stage = Stage.IDLE;
            vehicleWasOn = true;
            if (healthy) {
                onConnectedMs = connected ? observedMs : -1;
                onGeneration = modeGeneration == null ? "" : modeGeneration;
                onDropMs = -1;
            } else if (radioDown && onConnectedMs >= 0 && onDropMs < 0) {
                onDropMs = observedMs;
            }
            return false;
        }
        if (power != 0) {
            invalidateProof();
            return false;
        }
        if (!eligible) {
            if (reported()) mark("cancelled_ineligible");
            stage = Stage.DONE;
            vehicleWasOn = false;
            onConnectedMs = onDropMs = -1;
            revokeHistory();
            guardRequired = false;
            resetGrace();
            clearParkedConnection();
            return false;
        }
        if (vehicleWasOn) {
            vehicleWasOn = false;
            boolean armed = onConnectedMs >= 0 && observedMs >= onConnectedMs
                    && observedMs - onConnectedMs <= EDGE_WINDOW_MS
                    && sourceInstance != null && !sourceInstance.isEmpty()
                    && !onGeneration.isEmpty() && onGeneration.equals(modeGeneration);
            stage = armed ? Stage.ARMED : Stage.DONE;
            guardRequired = armed;
            edgeMs = observedMs;
            instance = sourceInstance == null ? "" : sourceInstance;
            generation = modeGeneration == null ? "" : modeGeneration;
            revision = settingsRevision;
            resetGrace();
            if (armed && onDropMs >= onConnectedMs) drop();
            onConnectedMs = onDropMs = -1;
        }
        if (parkedConnectedMs >= 0 && !parkedConnectionMatches(
                sourceInstance, modeGeneration, settingsRevision)) clearParkedConnection();
        if (healthy && connected && sourceInstance != null && !sourceInstance.isEmpty()
                && modeGeneration != null && !modeGeneration.isEmpty()) {
            // A native connection observed while parked can survive a later radio shutdown,
            // including one after the early switch-off window or after an actual reconnect.
            parkedConnectedMs = observedMs;
            parkedInstance = sourceInstance;
            parkedGeneration = modeGeneration;
            parkedRevision = settingsRevision;
            parkedProofInterrupted = false;
        } else if ((!healthy || parkedProofInterrupted)
                && parkedConnectionFresh(observedMs, sourceInstance, modeGeneration, settingsRevision)) {
            revokeHistory();
            resetGrace();
            stage = Stage.ARMED;
            guardRequired = true;
            edgeMs = observedMs;
            instance = sourceInstance;
            generation = modeGeneration;
            revision = settingsRevision;
            // Consume the contact once. Repeated unknown reads cannot extend this episode.
            clearParkedConnection();
            mark("parked_history_armed");
        } else if (healthy) {
            // An ordinary, uninterrupted native disconnection stays on the normal evaluator path.
            clearParkedConnection();
        }
        if (!guardRequired) return false;
        if (revision != settingsRevision) return finish("cancelled_source_changed");
        if (modeGeneration != null && !modeGeneration.isEmpty()
                && !generation.equals(modeGeneration)) return finish("cancelled_mode_changed");
        if (!instance.equals(sourceInstance)) {
            // A collector restart keeps the bounded pre-switch-off history,
            // but must earn a complete new grace with fresh native observations.
            if (sourceInstance == null || sourceInstance.isEmpty())
                return finish("cancelled_source_changed");
            instance = sourceInstance;
            invalidateProof();
            if (reported()) mark("instance_adopted");
        }
        if (observedMs < edgeMs || observedMs - edgeMs > CARRY_TTL_MS && !confirmedDeparture())
            return finish("cancelled_expired");
        if (healthy && connected) {
            parkedProofInterrupted = false;
            // The radio can still drop after the first parked readings; wait out the drop window.
            if (stage == Stage.ARMED && observedMs - edgeMs <= DROP_WINDOW_MS) return false;
            // The native connection itself revokes absence. Keep the evaluator's confirmed Away
            // state so a returning phone can earn its normal signal-strength arrival.
            return finish("cancelled_reconnected", false);
        }
        if (radioDown) {
            if (stage != Stage.DROPPED) drop();
            else invalidateProof();
            return false;
        }
        if (!healthy || modeGeneration == null || modeGeneration.isEmpty()) {
            invalidateProof();
            return false;
        }
        if (lastDisconnectedMs >= 0 && observedMs - lastDisconnectedMs > staleMs)
            invalidateProof();
        if (radioReadyMs < 0) {
            stage = Stage.RADIO_BACK;
            radioReadyMs = observedMs;
            mark("radio_back");
        }
        lastDisconnectedMs = observedMs;
        if (disconnectedReadings < Integer.MAX_VALUE) disconnectedReadings++;
        if (!doorsClosed || lastDoorActivityMs > nowMs) {
            doorsClosedMs = -1;
            revokeHistory();
            if (stage == Stage.CARRIED || stage == Stage.DONE) stage = Stage.RADIO_BACK;
            return false;
        }
        if (doorsClosedMs < 0) doorsClosedMs = nowMs;
        if (lastDoorActivityMs >= 0) doorsClosedMs = Math.max(doorsClosedMs, lastDoorActivityMs);
        if (!ready(nowMs, lastDoorActivityMs)) {
            revokeHistory();
            return false;
        }
        if (historyGranted) return false;
        historyGranted = true;
        stage = Stage.CARRIED;
        mark("carry");
        return true;
    }

    /** The evaluator's verdict on the reading just observed. */
    public void evaluated(boolean departure, String reason) {
        if (stage != Stage.CARRIED) return;
        if (departure) {
            stage = Stage.DONE;
            mark("departure");
        } else if ("disconnected_without_connection_history".equals(reason)) {
            historyGranted = false;
            stage = Stage.RADIO_BACK;
        }
    }

    /** A rejected, stale or unavailable observation cannot keep a recovery grace alive. */
    public void invalidateProof() {
        if (parkedConnectedMs >= 0) parkedProofInterrupted = true;
        revokeHistory();
        resetGrace();
        if (stage == Stage.CARRIED || stage == Stage.DONE && guardRequired)
            stage = Stage.RADIO_BACK;
    }

    public boolean takeHistoryRevocation() {
        boolean revoked = historyRevoked;
        historyRevoked = false;
        return revoked;
    }

    /** Applies to pending companion phones too, before their history has been restored. */
    public boolean requiresLockGuard() { return guardRequired; }

    /** Rechecked at command validation; a door edge after the observation revokes permission. */
    public boolean lockEvidenceValid(long nowMs, long lastDoorActivityMs) {
        return historyGranted && ready(nowMs, lastDoorActivityMs);
    }

    /** Comma-separated transitions of the latest reading, empty when nothing is worth logging. */
    public String takeTransitions() {
        String value = transitions.toString();
        transitions.setLength(0);
        return value;
    }

    public long sinceOffMs(long observedMs) {
        return edgeMs < 0 ? -1 : Math.max(0, observedMs - edgeMs);
    }

    private void drop() {
        invalidateProof();
        stage = Stage.DROPPED;
        mark("radio_drop");
    }

    private boolean finish(String reason) {
        return finish(reason, true);
    }

    private boolean finish(String reason, boolean resetEvaluator) {
        if (reported()) mark(reason);
        if (resetEvaluator) revokeHistory();
        else historyGranted = historyRevoked = false;
        guardRequired = false;
        resetGrace();
        stage = Stage.DONE;
        return false;
    }

    private boolean reported() {
        return stage == Stage.DROPPED || stage == Stage.RADIO_BACK
                || stage == Stage.CARRIED;
    }

    private void mark(String transition) {
        if (transitions.length() > 0) transitions.append(',');
        transitions.append(transition);
    }

    private boolean ready(long nowMs, long lastDoorActivityMs) {
        return guardRequired && edgeMs >= 0 && nowMs >= edgeMs
                && (nowMs - edgeMs <= CARRY_TTL_MS || confirmedDeparture())
                && radioReadyMs >= 0 && lastDisconnectedMs >= radioReadyMs
                && nowMs >= lastDisconnectedMs && nowMs - lastDisconnectedMs <= staleMs
                && lastDisconnectedMs - radioReadyMs >= RADIO_READY_GRACE_MS
                && disconnectedReadings >= 2 && doorsClosedMs >= 0 && nowMs >= doorsClosedMs
                && lastDoorActivityMs <= nowMs
                && nowMs - Math.max(doorsClosedMs, lastDoorActivityMs) >= DOOR_QUIET_MS;
    }

    private boolean confirmedDeparture() {
        return stage == Stage.DONE && historyGranted;
    }

    private boolean parkedConnectionFresh(long observedMs, String sourceInstance,
            String modeGeneration, long settingsRevision) {
        return parkedConnectedMs >= 0 && observedMs >= parkedConnectedMs
                && observedMs - parkedConnectedMs <= EDGE_WINDOW_MS
                && parkedConnectionMatches(sourceInstance, modeGeneration, settingsRevision);
    }

    private boolean parkedConnectionMatches(String sourceInstance,
            String modeGeneration, long settingsRevision) {
        return parkedRevision == settingsRevision && parkedInstance.equals(sourceInstance)
                && parkedGeneration.equals(modeGeneration);
    }

    private void clearParkedConnection() {
        parkedConnectedMs = -1;
        parkedInstance = parkedGeneration = "";
        parkedProofInterrupted = false;
    }

    private void revokeHistory() {
        if (historyGranted) historyRevoked = true;
        historyGranted = false;
    }

    private void resetGrace() {
        radioReadyMs = lastDisconnectedMs = doorsClosedMs = -1;
        disconnectedReadings = 0;
    }
}

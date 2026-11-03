package com.overdrive.app.server;

import android.os.SystemClock;
import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.DiLink5LockRail;
import com.overdrive.app.byd.routing.VehicleCommandRouter;
import com.overdrive.app.remote.RemoteDevViewBridgeAuth;
import com.overdrive.app.remoteaccess.ProximityEvaluator;
import com.overdrive.app.remoteaccess.BluetoothConnectionEvidence;
import com.overdrive.app.remoteaccess.BluetoothProximityDecisionGuard;
import com.overdrive.app.remoteaccess.ProximityCooldownPolicy;
import com.overdrive.app.remoteaccess.DiLink5GateActuationPolicy;
import com.overdrive.app.remoteaccess.RemoteAccessSettings;
import com.overdrive.app.remoteaccess.RemoteAccessPhoneSavePolicy;
import com.overdrive.app.remoteaccess.RemoteAccessNotifications;
import com.overdrive.app.remoteaccess.MultiPhoneProximityPolicy;
import com.overdrive.app.remoteaccess.KeyFobEventEvaluator;
import com.overdrive.app.remoteaccess.KeyFobPresenceEvaluator;
import com.overdrive.app.remoteaccess.ProximitySafeZonePolicy;
import com.overdrive.app.remoteaccess.VehiclePowerReader;
import com.overdrive.app.remoteaccess.BluetoothPlatformSupport;
import com.overdrive.app.remoteaccess.KeyFobAccessMonitor;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.camera.dilink5.DiLink5Platform;
import com.overdrive.app.config.UnifiedConfigManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Canonical Bluetooth proximity snapshot, durable rules and local-only actions.
 * No feature executor, poller or timer lives in the daemon. Observations arrive
 * from the authenticated app collector; long-polling uses existing HTTP workers.
 */
public final class RemoteAccessApiHandler {
    private static final Object STATE = new Object();
    private static final Object MUTATION = new Object();
    private static final Object ACTION = new Object();
    private static final String SESSION_ID = java.util.UUID.randomUUID().toString();
    private static final LinkedHashMap<String, Long> NONCES = new LinkedHashMap<>();
    private static final ArrayDeque<JSONObject> EVENTS = new ArrayDeque<>();
    private static JSONObject config = RemoteAccessSettings.read();
    private static JSONObject effectiveConfig = RemoteAccessSettings.effective(config);
    private static final LinkedHashMap<String, PhoneState> PHONES = new LinkedHashMap<>();
    private static JSONObject runtime = new JSONObject();
    private static JSONObject lastAction = new JSONObject();
    private static JSONArray pairedDevices = new JSONArray();
    private static long sequence = 1;
    // Dispatch bookkeeping: written by the dispatch sites under ACTION and, since 18 Oct, by the
    // confirm job's thread (the frozen legacy re-send; lastCommandConfirmed when a readback confirms),
    // read by the gate under ACTION — volatile for cross-thread visibility, same semantics as before.
    private static volatile long lastActionElapsed = -1;
    private static long lastRecordedActionElapsed = -1;
    private static volatile String lastActionKind = "";
    private static volatile boolean lastCommandConfirmed;
    private static String lastBluetoothDiagnostic = "";
    private static long nextBluetoothDiagnosticAt;
    private static KeyFobEventEvaluator keyFob;
    private static KeyFobPresenceEvaluator keyFobPresence;
    private static String presenceModeGeneration;
    private static String lastKeyFobDiagnostic = "";
    private static long nextKeyFobDiagnosticAt;
    private static String lastCollectorDiagnostic = "";
    private static long nextCollectorDiagnosticAt;
    // Envelope time of the latest accepted phone observation and the vehicle power read with it.
    private static long lastObservationElapsed = -1;
    private static int lastObservedPower = BydVehicleData.UNAVAILABLE;
    // B4: the last bodywork "open" edge seen by this daemon; releases the post-unlock relock grace early.
    private static volatile long lastDoorOpenedElapsed = -1;
    // Written under ACTION (the SDK dispatch) or by the confirm job (a cloud leg); the two never run
    // concurrently for an unlock, so a volatile flag keeps the registration visible and single.
    private static volatile boolean doorOpenObserverRegistered;
    private static final BydDataCollector.DoorStateListener DOOR_OPEN_OBSERVER = (area, state) -> {
        if (state == com.overdrive.app.byd.bodywork.BodyworkConstants.STATE_OPEN)
            lastDoorOpenedElapsed = SystemClock.elapsedRealtime();
    };
    // ACTION serializes dispatches; the authenticated relay reads this without acquiring ACTION.
    private static volatile PendingActuation pendingActuation;
    private static final class PendingActuation {
        final com.overdrive.app.remoteaccess.DiLink5ProximityCommandGuard.Scope scope;
        final ActionDecision decision;
        PendingActuation(com.overdrive.app.remoteaccess.DiLink5ProximityCommandGuard.Scope scope,
                         ActionDecision decision) { this.scope = scope; this.decision = decision; }
    }
    // S2 (18 Oct): the ONE confirmation job of the last dispatched proximity command. Everything
    // that waits on the car — the readback window(s), the frozen legacy unlock re-send, the cloud
    // fallback leg and the terminal recordAction/notifyOutcome — runs on it, so ACTION is held for
    // the gate and the dispatch only. Replaced and read under ACTION; the job never takes ACTION.
    private static volatile ConfirmJob pendingConfirmJob;
    /**
     * Single-use daemon thread ({@code proximity-confirm}) carrying one dispatched decision's
     * confirmation. {@link #pending()} is true until the sequence ended (however it ended); the
     * opposite direction {@link #supersede supersedes} a pending job by interrupting it, so the
     * stale direction's readback or in-flight cloud leg stops ("arrive, immediately leave" never
     * cloud-unlocks after the lock). Nothing throws out of {@link #run()}: an interrupted or failing
     * sequence gets exactly one honest terminal record unless it already recorded its own, and the
     * job always logs one {@code Proximity: confirm …} line.
     */
    private static final class ConfirmJob extends Thread {
        final ActionDecision decision;
        final JSONObject settings;
        final BydDataCollector collector;
        final VehicleCommandRouter.VehicleCommand command;
        final VehicleCommandRouter.CommandResult result;
        final BydDataCollector.DiLink5LockEvidence finalEvidence;
        final DiLink5LockRail actuationRail;
        final BydDataCollector.DiLink5LockEvidence typedEvidence;
        final int target;
        final boolean cloudFallback, legacyDispatch;
        final long dispatchedAtElapsed;
        // Set by runProximityConfirmJob's finally for the log line and the interrupted record.
        volatile String path = DiLink5GateActuationPolicy.PATH_SDK;
        volatile boolean cloudAcknowledged;
        volatile String lastResult;            // last recordAction result written on this thread
        private volatile String supersededBy;  // the newer opposite-direction action, when interrupted by one
        private volatile boolean done;
        ConfirmJob(ActionDecision decision, JSONObject settings, BydDataCollector collector,
                   VehicleCommandRouter.VehicleCommand command, VehicleCommandRouter.CommandResult result,
                   BydDataCollector.DiLink5LockEvidence finalEvidence, DiLink5LockRail actuationRail,
                   BydDataCollector.DiLink5LockEvidence typedEvidence, int target, boolean cloudFallback,
                   boolean legacyDispatch, long dispatchedAtElapsed) {
            super(DiLink5GateActuationPolicy.CONFIRM_JOB_THREAD_NAME);
            setDaemon(true);
            this.decision = decision;
            this.settings = settings;
            this.collector = collector;
            this.command = command;
            this.result = result;
            this.finalEvidence = finalEvidence;
            this.actuationRail = actuationRail;
            this.typedEvidence = typedEvidence;
            this.target = target;
            this.cloudFallback = cloudFallback;
            this.legacyDispatch = legacyDispatch;
            this.dispatchedAtElapsed = dispatchedAtElapsed;
        }
        String action() { return decision.action; }
        boolean pending() { return !done; }
        void supersede(String newerAction) {
            supersededBy = newerAction;
            interrupt();
        }
        private boolean terminalRecorded() {
            String recorded = lastResult;
            return recorded != null && !"accepted".equals(recorded);
        }
        @Override public void run() {
            long startedAt = SystemClock.elapsedRealtime();
            String action = decision.action;
            try {
                runProximityConfirmJob(this, decision, settings, collector, command, result, finalEvidence,
                        actuationRail, typedEvidence, target, cloudFallback, legacyDispatch, dispatchedAtElapsed);
            } catch (Throwable failed) {
                if (failed instanceof InterruptedException) Thread.currentThread().interrupt();
                try {
                    if (!terminalRecorded()) {
                        boolean sdkAccepted = result.outcome == VehicleCommandRouter.Outcome.SUCCESS;
                        String status = DiLink5GateActuationPolicy.interruptedConfirmStatus(sdkAccepted, cloudAcknowledged);
                        String text = DiLink5GateActuationPolicy.interruptedConfirmText(sdkAccepted, cloudAcknowledged,
                                supersededBy, result.displayMessage);
                        // J5: a cloud acknowledgement alone is published as an unconfirmed notification.
                        String published = "cloud_acknowledged".equals(status) ? "unconfirmed" : status;
                        recordAction(action, status, text);
                        notifyOutcome(decision, settings, published, text);
                    }
                } catch (Throwable ignored) {
                    // The log line below still reports the job.
                }
            } finally {
                done = true;
                com.overdrive.app.daemon.CameraDaemon.log(DiLink5GateActuationPolicy.confirmJobLogLine(action,
                        lastResult, path, SystemClock.elapsedRealtime() - startedAt, supersededBy));
            }
        }
    }
    private static final class PhoneState {
        final JSONObject profile;
        final ProximityEvaluator evaluator;
        final ArrayDeque<long[]> calibration = new ArrayDeque<>();
        JSONObject signal;
        String sourceInstance = "";
        String activeModeGeneration;
        long sourceSequence;
        long stateSequence;
        long satisfiedEpisodeId;
        String lastDiagnostic = "";
        long nextDiagnosticAt;
        // 18 Oct multi-phone bookkeeping (daemon side; survives evaluator resets and signal revocation).
        long generationStartedElapsedMs = -1;   // first accepted observation of the current source instance
        long firstConnectedElapsedMs = -1;      // first connected=true observation in that generation
        long lastNearEvidenceElapsedMs = -1;    // latest connected observation without departure-eligible Away
        long lastProofElapsedMs = -1;           // envelope time of the latest parseable native proof
        BluetoothConnectionEvidence lastProof;  // that proof, healthy or not
        boolean lastProofHealthy;               // it passed relay()'s full health check
        long adoptInstanceUntilElapsedMs = -1;  // M2: a retained phone adopts a restarted source instance until then
        PhoneState(JSONObject profile, JSONObject settings) throws Exception {
            this.profile = new JSONObject(profile.toString());
            evaluator = new ProximityEvaluator(RemoteAccessSettings.evaluatorConfig(settings, profile));
            signal = emptySignal(settings.optBoolean("enabled") && profile.optBoolean("enabled")
                    ? "waiting_for_phone" : "disabled")
                    .put("deviceAddress", profile.getString("address")).put("deviceName", profile.getString("name"));
        }
    }
    static {
        synchronized (STATE) {
            try { reset(config); } catch (Exception invalid) { throw new IllegalStateException(invalid); }
        }
    }
    private RemoteAccessApiHandler() {}

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String route = path.split("\\?", 2)[0];
        if ("GET".equals(method) && route.equals("/api/remote-access/status")) {
            // Perform the bounded app read before taking the proximity state lock.
            BluetoothRadioStatusReader.refreshIfNeeded();
            long after = queryLong(path, "after", -1);
            synchronized (STATE) {
                refreshSettings();
                if (config.optBoolean("enabled") && after >= sequence) STATE.wait(12000);
                HttpResponse.sendJson(out, status().toString());
            }
            return true;
        }
        if ("GET".equals(method) && route.equals("/api/remote-access/devices")) {
            JSONObject result = bridge("devices");
            if (result != null && result.optBoolean("success")) {
                synchronized (STATE) { pairedDevices = result.optJSONArray("devices"); }
                HttpResponse.sendJson(out, result.toString());
            } else {
                HttpResponse.sendJson(out, 503, error("The car app is unavailable. Open OverDrive and retry.").toString());
            }
            return true;
        }
        if ("POST".equals(method) && route.equals("/api/remote-access/config")) {
            saveConfig(body, out);
            return true;
        }
        if ("POST".equals(method) && route.equals("/api/remote-access/calibrate")) {
            synchronized (STATE) {
                refreshSettings();
                JSONObject request = new JSONObject(body);
                PhoneState phone = PHONES.get(request.optString("deviceAddress", config.optString("deviceAddress"))
                        .toUpperCase(java.util.Locale.US));
                if (phone == null || !phone.profile.optBoolean("enabled"))
                    throw new IllegalArgumentException("Select an enabled trusted phone");
                JSONObject signal = phone.signal;
                String side = request.optString("side");
                if (!config.optBoolean("advancedMode"))
                    throw new IllegalArgumentException("Enable Advanced mode to capture calibration values");
                if (!"near".equals(side) && !"away".equals(side))
                    throw new IllegalArgumentException("Choose near or away");
                pruneCalibration(phone, SystemClock.elapsedRealtime());
                long observed = signal.optLong("observedAtElapsedMs", -1);
                long age = observed < 0 ? -1 : SystemClock.elapsedRealtime() - observed;
                if (!config.optBoolean("enabled") || !signal.optBoolean("connected") || phone.calibration.size() < 3
                        || signal.isNull("rawRssi") || age < 0 || age > effectiveConfig.optLong("staleMs")) {
                    HttpResponse.sendJson(out, 409, error("Wait for at least three fresh connected readings").toString());
                } else {
                    int[] samples = new int[phone.calibration.size()];
                    int i = 0;
                    for (long[] item : phone.calibration) samples[i++] = (int) item[1];
                    java.util.Arrays.sort(samples);
                    double median = samples.length % 2 == 1 ? samples[samples.length / 2]
                            : (samples[samples.length / 2 - 1] + samples[samples.length / 2]) / 2.0;
                    HttpResponse.sendJson(out, new JSONObject().put("success", true).put("side", side)
                            .put("value", Math.round(median)).put("sampleCount", samples.length).toString());
                }
            }
            return true;
        }
        return false;
    }

    private static void saveConfig(String body, OutputStream out) throws Exception {
        synchronized (MUTATION) {
            JSONObject patch = new JSONObject(body);
            JSONObject current = RemoteAccessSettings.read();
            JSONObject next = RemoteAccessSettings.merge(current, patch);
            if (patch.length() == 1 && patch.has("keepBluetoothOn")) {
                boolean keepBluetoothOn = next.getBoolean("keepBluetoothOn");
                synchronized (ACTION) {
                    if (!UnifiedConfigManager.setBluetoothKeepAliveEnabled(keepBluetoothOn))
                        throw new IllegalStateException("Bluetooth setting could not be saved");
                    synchronized (STATE) { refreshSettings(); }
                }
                boolean applied = reconcileBluetoothKeepAlive(true);
                // The shared switch alone owns only the radio. The app-process phone collector
                // (HFP reconnect, RSSI reader, observation publisher) starts on "apply" alone and a
                // running one observes OFF by itself, so re-apply after a radio change while the
                // master is on. Radio-only save: no revision bump, no pairing validation and none of
                // the generic branch's revoked-generation handling; a failure is reported, not acted on.
                String collectorOutcome = next.optBoolean("enabled")
                        ? applyCollectorAfterRadioChange() : "master switch off";
                synchronized (STATE) {
                    event("settings", "Shared parked Bluetooth " + (keepBluetoothOn ? "on" : "off")
                            + " (" + collectorOutcome + ")");
                    JSONObject response = status().put("bluetoothApplyPending", !applied)
                            .put("collectorApply", collectorOutcome);
                    HttpResponse.sendJson(out, response.toString());
                }
                return;
            }
            next.put("revision", current.optLong("revision") + 1);
            JSONArray devices = RemoteAccessPhoneSavePolicy.validateRequiredPairings(
                    current, next, () -> bridge("devices"));
            if (devices != null) {
                synchronized (STATE) { pairedDevices = devices; }
            }
            // Serialize disabling with the final command boundary. No action may
            // start after a successful disable response or after a settings change.
            synchronized (ACTION) {
                JSONObject persisted = new JSONObject(next.toString());
                // Unrelated rule saves cannot overwrite a shared radio choice
                // made on another page while this request was being validated.
                if (!patch.has("keepBluetoothOn")) persisted.remove("keepBluetoothOn");
                if (!UnifiedConfigManager.updateSection(RemoteAccessSettings.SECTION, persisted))
                    throw new IllegalStateException("Settings could not be saved");
                next = RemoteAccessSettings.read();
                synchronized (STATE) { reset(next); }
            }
            if (patch.has("keepBluetoothOn")) reconcileBluetoothKeepAlive(true);
            JSONObject applied = bridge("apply");
            if (next.optBoolean("enabled") && (applied == null || !applied.optBoolean("success"))) {
                // Persist a revoked generation if the app failed to accept it.
                next.put("enabled", false).put("revision", next.optLong("revision") + 1);
                next.remove("keepBluetoothOn");
                UnifiedConfigManager.updateSection(RemoteAccessSettings.SECTION, next);
                next = RemoteAccessSettings.read();
                synchronized (STATE) { reset(next); event("error", "Monitoring could not start; master switch is off"); }
                bridge("stop");
            } else synchronized (STATE) {
                mergeApplied(applied);
                event("settings", next.optBoolean("enabled") ? "Proximity monitoring enabled" : "Master switch off");
            }
            synchronized (STATE) { HttpResponse.sendJson(out, status().toString()); }
        }
    }

    /** Caller holds STATE. Mirrors the app's "apply" reply (runtime, paired devices) into daemon state. */
    private static void mergeApplied(JSONObject applied) throws Exception {
        runtime = applied == null ? new JSONObject().put("active", false).put("cleanupPending", true)
                : applied.optJSONObject("runtime");
        if (applied != null && applied.optJSONArray("devices") != null) pairedDevices = applied.getJSONArray("devices");
    }

    /**
     * Alias-save re-apply: the same bounded bridge round trip and runtime merge as the generic
     * branch, folded into one outcome text for the settings event. Nothing here writes settings,
     * disables the master switch or throws: a radio-only save must never revoke a generation.
     */
    private static String applyCollectorAfterRadioChange() {
        try {
            JSONObject applied = bridge("apply");
            synchronized (STATE) { mergeApplied(applied); }
            if (applied != null && applied.optBoolean("success")) {
                JSONObject appRuntime = applied.optJSONObject("runtime");
                return "collector started/refreshed; active="
                        + (appRuntime != null && appRuntime.optBoolean("active"));
            }
            return "collector not started: " + (applied == null
                    ? "car app unavailable" : applied.optString("error", "unavailable"));
        } catch (Exception unavailable) {
            return "collector not started: " + unavailable.getClass().getSimpleName();
        }
    }

    /** Called only after socket-level loopback AND JWT checks in HttpServer. */
    public static void relay(String body, OutputStream out) throws Exception {
        RemoteDevViewBridgeAuth.AuthenticatedRequest authenticated = RemoteDevViewBridgeAuth.verify(body);
        JSONObject sample = authenticated.request;
        if ("remoteAccessActuationCheck".equals(sample.optString("command"))) {
            relayActuationCheck(authenticated, out);
            return;
        }
        if ("remoteAccessPowerRequest".equals(sample.optString("command"))
                || "remoteAccessBodyworkPowerRequest".equals(sample.optString("command"))) {
            relayPower(authenticated, out);
            return;
        }
        if ("remoteAccessKeyFobObservation".equals(sample.optString("command"))) {
            relayKeyFob(authenticated, out);
            return;
        }
        if (!"remoteAccessObservation".equals(sample.optString("command")))
            throw new SecurityException("Invalid signal envelope");
        Integer diLink5Power = null;
        boolean nonceRemembered = false;
        if (DiLink5Platform.isSelected()) {
            synchronized (STATE) {
                rememberNonce(authenticated.nonce, authenticated.timestampMs);
                nonceRemembered = true;
                refreshSettings();
                PhoneState phone = PHONES.get(
                        sample.optString("deviceAddress").toUpperCase(java.util.Locale.US));
                if (!effectiveConfig.optBoolean("enabled")
                        || sample.optLong("revision", -1) != effectiveConfig.optLong("revision")
                        || phone == null || !phone.profile.optBoolean("enabled")) {
                    HttpResponse.sendJson(out,
                            new JSONObject().put("success", false).put("revoked", true).toString());
                    return;
                }
            }
            // Native car-service reads must not hold up the app's independent
            // power query, UI snapshots or settings revocation on STATE.
            diLink5Power = readVehiclePowerNow();
        }
        ProximityEvaluator.Snapshot evaluated;
        JSONObject settings;
        long actionObserved;
        String actionModeGeneration;
        boolean actionSatisfied;
        synchronized (STATE) {
            if (!nonceRemembered) rememberNonce(authenticated.nonce, authenticated.timestampMs);
            refreshSettings();
            settings = new JSONObject(effectiveConfig.toString());
            PhoneState phone = PHONES.get(sample.optString("deviceAddress").toUpperCase(java.util.Locale.US));
            if (!settings.optBoolean("enabled") || sample.optLong("revision", -1) != settings.optLong("revision")
                    || phone == null || !phone.profile.optBoolean("enabled")) {
                HttpResponse.sendJson(out, new JSONObject().put("success", false).put("revoked", true).toString());
                return;
            }
            long now = SystemClock.elapsedRealtime();
            Object outerTime = sample.opt("observedAtElapsedMs");
            if (!(outerTime instanceof Byte || outerTime instanceof Short
                    || outerTime instanceof Integer || outerTime instanceof Long)) {
                revokePhoneSignal(phone, "invalid_observation", BluetoothConnectionEvidence.hasAny(sample));
                throw new IllegalArgumentException("Invalid observation timestamp");
            }
            long observed = ((Number) outerTime).longValue();
            if (!BluetoothProximityDecisionGuard.fresh(observed, now, settings.optLong("staleMs"))) {
                revokePhoneSignal(phone, "stale", BluetoothConnectionEvidence.hasAny(sample));
                throw new IllegalArgumentException("Observation is stale");
            }
            boolean nativeProof = BluetoothConnectionEvidence.hasAny(sample);
            BluetoothConnectionEvidence proof = null;
            String modeGeneration = activeModeGeneration();
            JSONObject reportedRuntime = sample.optJSONObject("runtime");
            boolean connected = sample.optBoolean("connected");
            boolean healthyNativeProof = false;
            if (nativeProof) {
                try {
                    proof = BluetoothConnectionEvidence.parse(sample);
                } catch (IllegalArgumentException malformed) {
                    revokePhoneSignal(phone, "connection_proof_unavailable", true);
                    throw new IllegalArgumentException("Invalid native connection proof");
                }
                healthyNativeProof = proof.healthyAt(now, settings.optLong("staleMs"))
                        && proof.healthyAt(observed, settings.optLong("staleMs"))
                        && modeGeneration != null && modeGeneration.equals(proof.modeGeneration)
                        && sample.opt("connected") instanceof Boolean
                        && connected == (proof.state == BluetoothConnectionEvidence.State.CONNECTED)
                        && reportedRuntime != null && Boolean.TRUE.equals(reportedRuntime.opt("active"));
                if (!healthyNativeProof) {
                    revokePhoneSignal(phone, "connection_proof_unavailable", true);
                    connected = false;
                }
            }
            String instance = sample.getString("instanceId");
            long serial = sample.getLong("observationSequence");
            if (!instance.equals(phone.sourceInstance)) {
                if (!adoptSourceInstance(phone, now)) {
                    phone.evaluator.reset();
                    phone.calibration.clear();
                    phone.satisfiedEpisodeId = 0;
                }
                phone.sourceInstance = instance;
                phone.sourceSequence = 0;
            }
            if (serial <= 0 || serial <= phone.sourceSequence)
                throw new IllegalArgumentException("Observation was already consumed");
            phone.sourceSequence = serial;
            Integer raw = null;
            Object number = sample.opt("rawRssi");
            if (number instanceof Number) {
                double value = ((Number) number).doubleValue();
                if (Double.isFinite(value) && value == Math.rint(value) && value >= -127 && value <= 20) raw = (int) value;
            }
            // Only a fresh, confirmed OFF observation can arm either platform.
            int power = diLink5Power == null ? readVehiclePowerNow() : diLink5Power;
            boolean eligible = power == 0
                    && !UnifiedConfigManager.isVehicleOnOnlyMode()
                    && UnifiedConfigManager.isBluetoothKeepAliveEnabled();
            evaluated = nativeProof
                    ? phone.evaluator.observe(phone.profile.getString("address"), raw, observed,
                            connected, eligible, healthyNativeProof ? proof : null)
                    : phone.evaluator.observe(phone.profile.getString("address"), raw, observed, connected, eligible);
            if (!evaluated.state.name().equals(phone.signal.optString("state")))
                phone.stateSequence = serial;
            // Absence evidence keeps its native lease; HTTP delivery cannot renew it.
            long signalObserved = nativeProof && evaluated.rawRssi == null
                    ? evaluated.freshnessMs < 0 ? -1 : observed - evaluated.freshnessMs
                    : evaluated.rawRssi == null ? -1 : observed;
            phone.activeModeGeneration = modeGeneration;
            if (phone.satisfiedEpisodeId != evaluated.episodeId) phone.satisfiedEpisodeId = 0;
            actionSatisfied = evaluated.episodeId > 0 && phone.satisfiedEpisodeId == evaluated.episodeId;
            actionObserved = signalObserved;
            actionModeGeneration = modeGeneration;
            phone.signal = snapshotJson(evaluated)
                    .put("connected", connected).put("timestampMs", System.currentTimeMillis())
                    .put("observedAtElapsedMs", signalObserved)
                    .put("ageMs", signalObserved < 0 ? JSONObject.NULL : now - signalObserved)
                    .put("actionSatisfied", actionSatisfied)
                    .put("deviceName", phone.profile.getString("name"));
            if (nativeProof) {
                phone.signal.put("connectionReadKnown", proof.connectionReadKnown)
                        .put("connectionState", proof.state.name().toLowerCase(java.util.Locale.US))
                        .put("radioReadKnown", proof.radioReadKnown)
                        .put("connectionObservedAtElapsedMs", proof.observedAtElapsedMs)
                        .put("connectionModeGeneration", proof.modeGeneration)
                        .put("modeGeneration", modeGeneration == null ? JSONObject.NULL : modeGeneration);
            } else {
                // Legacy RSSI remains compatible and has no native absence authority.
                phone.signal.remove("connectionKnown");
                phone.signal.remove("radioOn");
            }
            JSONObject reconnect = sample.optJSONObject("reconnect");
            if (reconnect != null)
                phone.signal.put("reconnect", new JSONObject(reconnect.toString()));
            if (evaluated.rawRssi == null || !connected) {
                if ("missing_signal".equals(evaluated.reason)
                        || !nativeProof && "disconnected".equals(evaluated.reason))
                    phone.signal.put("reason", sample.optString("reason", evaluated.reason));
                phone.calibration.clear();
            } else {
                phone.calibration.add(new long[]{observed, evaluated.rawRssi});
                pruneCalibration(phone, now);
            }
            runtime = reportedRuntime == null ? new JSONObject() : new JSONObject(reportedRuntime.toString());
            lastObservationElapsed = observed;
            lastObservedPower = power;
            notePhoneObservation(phone, sample, observed, proof, healthyNativeProof, evaluated);
            logPhoneDiagnostic(phone, sample, evaluated, eligible, power, now);
            changed();
        }
        // Release the collector before readback. Confirmation must not delay
        // sampling beyond its freshness window. Reuse this existing HTTP worker.
        HttpResponse.sendJson(out, new JSONObject().put("success", true).toString());
        if (!actionSatisfied && (evaluated.arrivalCandidate || evaluated.lockCandidate
                || evaluated.event != ProximityEvaluator.Event.NONE)) {
            try {
                perform(evaluated, settings, actionObserved, sample.getString("instanceId"),
                        sample.getLong("observationSequence"), actionModeGeneration);
            } catch (Exception failed) {
                recordAction(evaluated.arrivalCandidate || evaluated.event == ProximityEvaluator.Event.ARRIVAL ? "unlock" : "lock",
                        "failed", "Local action interrupted or unavailable");
                if (failed instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * App callers may lack the shell daemon's fresh ignition/bodywork read.
     * Return a signed current observation through the local authenticated lane.
     * This handler never calls the app bridge or starts a monitoring worker.
     */
    private static void relayPower(RemoteDevViewBridgeAuth.AuthenticatedRequest authenticated,
                                   OutputStream out) throws Exception {
        JSONObject request = authenticated.request;
        String requestId = request.getString("requestId");
        String generation = request.getString("modeGeneration");
        long revision = request.getLong("revision");
        boolean bodywork = "remoteAccessBodyworkPowerRequest".equals(request.opt("command"));
        if (requestId.isEmpty() || requestId.length() > 128
                || generation.isEmpty() || generation.length() > 1024)
            throw new IllegalArgumentException("Invalid power observation scope");
        boolean valid;
        synchronized (STATE) {
            rememberNonce(authenticated.nonce, authenticated.timestampMs);
            refreshSettings();
            valid = powerObservationModeMatches(bodywork)
                    && DiLink5Platform.matchesActiveModeGeneration(generation)
                    && config.optLong("revision") == revision;
        }
        // Stamp before probing so transport/probe delays count against freshness.
        // Do not hold STATE across a car_service read or app-process roundtrip.
        long observed = SystemClock.elapsedRealtime();
        int power = valid ? readVehiclePowerNow() : BydVehicleData.UNAVAILABLE;
        synchronized (STATE) {
            refreshSettings();
            valid = valid && powerObservationModeMatches(bodywork)
                    && DiLink5Platform.matchesActiveModeGeneration(generation)
                    && config.optLong("revision") == revision;
        }
        if (!valid) power = BydVehicleData.UNAVAILABLE;
        JSONObject response = new JSONObject().put("success", valid)
                .put("command", bodywork ? "remoteAccessBodyworkPowerResponse"
                        : "remoteAccessPowerResponse").put("requestId", requestId)
                .put("modeGeneration", generation).put("revision", revision)
                .put("power", power).put("observedAtElapsedMs", observed);
        HttpResponse.sendJson(out, RemoteDevViewBridgeAuth.sign(response));
    }

    private static boolean powerObservationModeMatches(boolean bodywork) {
        return bodywork ? !DiLink5Platform.isSelected()
                && android.os.Build.VERSION.SDK_INT >= 31 && android.os.Build.VERSION.SDK_INT <= 34
                : DiLink5Platform.isSelected();
    }

    /** No SDK reads or ACTION lock here: the app calls while ACTION awaits its bridge result. */
    private static void relayActuationCheck(
            RemoteDevViewBridgeAuth.AuthenticatedRequest authenticated, OutputStream out) throws Exception {
        JSONObject request = authenticated.request;
        com.overdrive.app.remoteaccess.DiLink5ProximityCommandGuard.Scope scope =
                com.overdrive.app.remoteaccess.DiLink5ProximityCommandGuard.Scope.parse(request.toString());
        String requestId = request.getString("requestId");
        if (requestId.isEmpty() || requestId.length() > 128)
            throw new IllegalArgumentException("Invalid actuation check");
        // Case (C) re-validation input (LOW-4): the current ledger evidence, read BEFORE STATE so the
        // lock order stays STATE -> diLink5IngressLock -> ledger. Memory only; nothing is spawned.
        boolean carsvcScope = scope.gateRailOrNull() == DiLink5LockRail.CARSVC;
        BydDataCollector.DiLink5LockEvidence carsvcEvidence =
                carsvcScope ? BydDataCollector.getInstance().readDiLink5GateDoorLockEvidence() : null;
        boolean allowed = false;
        long observed = SystemClock.elapsedRealtime();
        synchronized (STATE) {
            rememberNonce(authenticated.nonce, authenticated.timestampMs);
            refreshSettings();
            PendingActuation pending = pendingActuation;
            allowed = pending != null && pending.scope.token.equals(scope.token)
                    && pending.scope.action.equals(scope.action)
                    && pending.scope.revision == scope.revision
                    && pending.scope.expiresAt == scope.expiresAt
                    && pending.scope.modeGeneration.equals(scope.modeGeneration)
                    && scope.locallyCurrent() && pending.decision.validForDelayedActuation()
                    && (!carsvcScope || DiLink5GateActuationPolicy.carsvcEvidenceAllowsWrite(
                            carsvcEvidence, scope, SystemClock.elapsedRealtime()));
        }
        JSONObject response = scope.toJson().put("command", "remoteAccessActuationResponse")
                .put("requestId", requestId).put("allowed", allowed)
                .put("observedAtElapsedMs", observed);
        HttpResponse.sendJson(out, RemoteDevViewBridgeAuth.sign(response));
    }

    private static void relayKeyFob(RemoteDevViewBridgeAuth.AuthenticatedRequest authenticated,
                                   OutputStream out) throws Exception {
        KeyFobEventEvaluator.Observation observation;
        KeyFobPresenceEvaluator.Observation presenceObservation;
        try {
            observation = KeyFobEventEvaluator.Observation.parse(authenticated.request);
            presenceObservation = KeyFobPresenceEvaluator.Observation.parse(authenticated.request, observation);
        } catch (IllegalArgumentException rejected) {
            synchronized (STATE) {
                refreshSettings();
                keyFobPresence.invalidate("invalid_presence_observation");
            }
            logKeyFobRejected(rejected);
            throw rejected;
        }
        KeyFobEventEvaluator.Snapshot evaluated;
        KeyFobPresenceEvaluator.Snapshot presence;
        JSONObject settings;
        synchronized (STATE) {
            rememberNonce(authenticated.nonce, authenticated.timestampMs);
            refreshSettings();
            settings = new JSONObject(effectiveConfig.toString());
            if (!settings.optBoolean("enabled") || !settings.optBoolean("keyFobEnabled")
                    || observation.revision != settings.optLong("revision")) {
                logKeyFobDiagnostic(observation, null, settings, "revoked");
                HttpResponse.sendJson(out, new JSONObject().put("success", false).put("revoked", true).toString());
                return;
            }
            try {
                evaluated = keyFob.observe(observation, SystemClock.elapsedRealtime(),
                        !UnifiedConfigManager.isVehicleOnOnlyMode());
                presence = keyFobPresence.observe(presenceObservation, SystemClock.elapsedRealtime(),
                        settings.optBoolean("keyFobLockEnabled") && !UnifiedConfigManager.isVehicleOnOnlyMode());
                if (settings.optBoolean("keyFobLockEnabled")
                        && hasFreshKeyFobApproach(SystemClock.elapsedRealtime())) {
                    keyFobPresence.vetoFreshApproach();
                    presence = keyFobPresence.current(SystemClock.elapsedRealtime());
                }
            } catch (IllegalArgumentException rejected) {
                keyFobPresence.invalidate("invalid_presence_observation");
                logKeyFobRejected(rejected);
                throw rejected;
            }
            logKeyFobDiagnostic(observation, evaluated, settings, "accepted");
            logKeyFobPresenceDiagnostic(presence, settings, presenceObservation, authenticated.request);
            changed();
        }
        HttpResponse.sendJson(out, new JSONObject().put("success", true).toString());
        // CAN remains the approach/unlock input. Completed active searches are
        // the sole fob departure input, so both streams cannot issue two locks.
        if (evaluated.event == ProximityEvaluator.Event.ARRIVAL) {
            try {
                performKeyFob(observation, settings, evaluated.event);
            } catch (Exception failed) {
                recordAction(evaluated.arrival ? "unlock" : "lock", "failed",
                        "Local key fob action interrupted or unavailable");
                if (failed instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }
        if (presence.lockCandidate) {
            try {
                performKeyFobPresence(presenceObservation, settings,
                        presence.event == ProximityEvaluator.Event.DEPARTURE);
            } catch (Exception failed) {
                recordAction("lock", "failed", "Local physical key search action interrupted or unavailable");
                if (failed instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }
    }

    private static String lastPresenceDiagnostic = "";
    private static long nextPresenceDiagnosticAt;

    private static void logKeyFobPresenceDiagnostic(KeyFobPresenceEvaluator.Snapshot presence,
                                                    JSONObject settings,
                                                    KeyFobPresenceEvaluator.Observation observation,
                                                    JSONObject envelope) {
        JSONObject sourceRuntime = envelope.optJSONObject("runtime");
        JSONObject search = sourceRuntime == null ? null : sourceRuntime.optJSONObject("presenceSearch");
        String diagnostic = "state=" + presence.state + " active=" + presence.active + " fresh=" + presence.fresh
                + " armed=" + presence.armed + " departureKnown=" + presence.departureKnown
                + " lockCandidate=" + presence.lockCandidate
                + " samples=" + presence.sampleCount + " windowMs=" + presence.windowMs
                + " intervalMs=" + presence.pollIntervalMs + " requiredSamples=" + presence.requiredSamples
                + " lockEnabled=" + settings.optBoolean("lockEnabled")
                + " keyFobLockEnabled=" + settings.optBoolean("keyFobLockEnabled")
                + " event=" + presence.event.name() + " reason=" + diagnosticCode(presence.reason)
                + " searchReason=" + diagnosticCode(search == null ? "" : search.optString("reason"))
                + " searchGeneration=" + observation.generation;
        long now = SystemClock.elapsedRealtime();
        if (diagnostic.equals(lastPresenceDiagnostic) && now < nextPresenceDiagnosticAt
                && presence.event == ProximityEvaluator.Event.NONE) return;
        lastPresenceDiagnostic = diagnostic;
        nextPresenceDiagnosticAt = now + 30_000;
        // Result/cycle IDs aid diagnosis but do not make a new query or heartbeat
        // produce a duplicate log while the actual policy/source state is stable.
        com.overdrive.app.daemon.CameraDaemon.log("Proximity: physicalKeySearch " + diagnostic
                + " resultId=" + observation.resultId + " cycleId=" + observation.cycleId
                + " searchStatus=" + diagnosticCode(observation.searchStatus)
                + " baselineStatus=" + diagnosticCode(search == null ? "" : search.optString("baselineStatus"))
                + " baselineRaw=" + diagnosticInteger(search, "baselineRaw")
                + " requestAccepted=" + diagnosticBoolean(search, "requestAccepted")
                + " requestReturnCode=" + diagnosticInteger(search, "requestReturnCode")
                + " progressObserved=" + diagnosticBoolean(search, "progressObserved")
                + " lastCallbackRaw=" + diagnosticInteger(search, "lastCallbackRaw"));
    }

    private static void logKeyFobDiagnostic(KeyFobEventEvaluator.Observation observation,
                                            KeyFobEventEvaluator.Snapshot evaluated,
                                            JSONObject settings, String disposition) {
        long now = SystemClock.elapsedRealtime();
        String key = com.overdrive.app.remoteaccess.KeyFobDiagnostic.changeKey(
                observation, evaluated, settings, disposition);
        boolean actionable = evaluated != null && evaluated.event != ProximityEvaluator.Event.NONE;
        synchronized (STATE) {
            if (!com.overdrive.app.remoteaccess.KeyFobDiagnostic.shouldLog(
                    lastKeyFobDiagnostic, key, nextKeyFobDiagnosticAt, now, actionable)) return;
            lastKeyFobDiagnostic = key;
            nextKeyFobDiagnosticAt = now + com.overdrive.app.remoteaccess.KeyFobDiagnostic.HEARTBEAT_LOG_MS;
        }
        com.overdrive.app.daemon.CameraDaemon.log("Proximity: fob "
                + com.overdrive.app.remoteaccess.KeyFobDiagnostic.describe(
                        observation, evaluated, settings, disposition, now));
    }

    private static void logKeyFobRejected(IllegalArgumentException rejected) {
        String key = "observation=rejected reason="
                + com.overdrive.app.remoteaccess.KeyFobDiagnostic.rejectionReason(rejected);
        long now = SystemClock.elapsedRealtime();
        synchronized (STATE) {
            if (!com.overdrive.app.remoteaccess.KeyFobDiagnostic.shouldLog(
                    lastKeyFobDiagnostic, key, nextKeyFobDiagnosticAt, now, false)) return;
            lastKeyFobDiagnostic = key;
            nextKeyFobDiagnosticAt = now + com.overdrive.app.remoteaccess.KeyFobDiagnostic.HEARTBEAT_LOG_MS;
        }
        com.overdrive.app.daemon.CameraDaemon.log("Proximity: fob " + key);
    }

    private abstract static class ActionDecision {
        final String action, source, deviceName, eventMessage;
        final long observed, freshnessMs;
        final boolean requiresBluetooth;
        private boolean safeZonesBound, safeZoneRestricted;
        private ProximitySafeZonePolicy.Lease safeZoneLease;
        private String safeZoneFailure;
        ActionDecision(String action, String source, String deviceName, String eventMessage,
                       long observed, long freshnessMs, boolean requiresBluetooth) {
            this.action = action;
            this.source = source;
            this.deviceName = deviceName;
            this.eventMessage = eventMessage;
            this.observed = observed;
            this.freshnessMs = freshnessMs;
            this.requiresBluetooth = requiresBluetooth;
        }
        abstract boolean sourceValid() throws Exception;
        void satisfied() throws Exception {}
        boolean quietSatisfied() throws Exception { return false; }
        boolean publishesTransition() { return true; }
        boolean sourceValidForDelayedActuation() throws Exception { return sourceValid(); }
        /** Cloud fallback (17 Oct): the source's revocation-only check; the full check unless overridden. */
        boolean sourceCurrent() throws Exception { return sourceValid(); }
        final boolean valid() throws Exception { return sourceValid() && safeZonesCurrent(); }
        /**
         * Cloud fallback re-validation: everything {@link #valid()} checks EXCEPT the original
         * observation's age. That lease proved the observation fresh for the FIRST (local) command;
         * the cloud leg continues the same already-dispatched decision, so only revocation counts.
         */
        final boolean current() throws Exception { return sourceCurrent() && safeZonesCurrent(); }
        final boolean validForDelayedActuation() throws Exception {
            return sourceValidForDelayedActuation() && safeZonesCurrent();
        }
        final boolean bindSafeZones(JSONObject settings) {
            safeZonesBound = true;
            safeZoneRestricted = settings.optBoolean("lock".equals(action)
                    ? "restrictLockToOutsideSafeZones" : "restrictUnlockToOutsideSafeZones");
            if (!safeZoneRestricted) return true;
            ProximitySafeZonePolicy.Result result = safeZoneSnapshot(settings);
            safeZoneLease = result.lease(action);
            if (safeZoneLease == null) safeZoneFailure = safeZoneMessage(result.actionReason(action));
            return safeZoneLease != null;
        }
        private boolean safeZonesCurrent() throws Exception {
            if (!safeZonesBound) return false;
            if (!safeZoneRestricted) return true;
            JSONObject live;
            synchronized (STATE) {
                refreshSettings();
                live = new JSONObject(effectiveConfig.toString());
            }
            ProximitySafeZonePolicy.Result result = safeZoneSnapshot(live);
            if (result.permits(safeZoneLease)) return true;
            safeZoneFailure = result.allowed(action)
                    ? "Safe-zone selection or geometry changed before the command"
                    : safeZoneMessage(result.actionReason(action));
            return false;
        }
        final String blockedReason(String fallback) {
            return safeZoneFailure == null ? fallback : safeZoneFailure;
        }
    }

    private static void perform(ProximityEvaluator.Snapshot reading, JSONObject settings, long observed,
                                String instance, long serial) throws Exception {
        String generation;
        synchronized (STATE) {
            PhoneState current = PHONES.get(reading.address);
            generation = current == null ? null : current.activeModeGeneration;
        }
        perform(reading, settings, observed, instance, serial, generation);
    }

    private static void perform(ProximityEvaluator.Snapshot reading, JSONObject settings, long observed,
                                String instance, long serial, String generation) throws Exception {
        JSONObject phone = RemoteAccessSettings.phone(settings, reading.address);
        boolean arriving = reading.arrivalCandidate || reading.event == ProximityEvaluator.Event.ARRIVAL;
        performDecision(new ActionDecision(
                arriving ? "unlock" : "lock",
                "bluetooth_proximity", phone == null ? "Trusted phone" : phone.optString("name", "Trusted phone"),
                arriving ? "Stable arrival" : "Stable departure",
                observed, Math.min(settings.optLong("staleMs"),
                        BluetoothProximityDecisionGuard.MAX_ACTION_LEASE_MS), true) {
            @Override boolean publishesTransition() { return reading.event != ProximityEvaluator.Event.NONE; }
            @Override boolean sourceValid() throws Exception {
                return decisionStillValid(reading, settings, instance, serial, observed, generation);
            }
            @Override boolean sourceValidForDelayedActuation() throws Exception {
                return decisionStillValid(reading, settings, instance, serial, observed, generation);
            }
            @Override boolean sourceCurrent() throws Exception {
                return decisionStillCurrent(reading, settings, instance, serial, generation);
            }
            @Override boolean quietSatisfied() throws Exception {
                synchronized (STATE) {
                    refreshSettings();
                    PhoneState current = PHONES.get(reading.address);
                    return capturedEpisodeCurrent(current)
                            && current.satisfiedEpisodeId == reading.episodeId
                            && current.signal.optBoolean("actionSatisfied");
                }
            }
            @Override void satisfied() throws Exception {
                synchronized (STATE) {
                    refreshSettings();
                    PhoneState current = PHONES.get(reading.address);
                    if (capturedEpisodeCurrent(current) && current.satisfiedEpisodeId != reading.episodeId) {
                        current.satisfiedEpisodeId = reading.episodeId;
                        current.signal.put("actionSatisfied", true);
                        changed();
                    }
                }
            }
            private boolean capturedEpisodeCurrent(PhoneState current) {
                return current != null && config.optBoolean("enabled")
                        && config.optBoolean("keepBluetoothOn") && current.profile.optBoolean("enabled")
                        && config.optLong("revision") == settings.optLong("revision")
                        && instance.equals(current.sourceInstance) && current.sourceSequence >= serial
                        && reading.episodeId > 0 && reading.episodeId == current.signal.optLong("episodeId")
                        && java.util.Objects.equals(generation, current.activeModeGeneration)
                        && java.util.Objects.equals(generation, activeModeGeneration());
            }
        }, settings);
    }

    private static void performKeyFob(KeyFobEventEvaluator.Observation reading, JSONObject settings,
                                      ProximityEvaluator.Event event) throws Exception {
        boolean locking = event == ProximityEvaluator.Event.DEPARTURE;
        performDecision(new ActionDecision(locking ? "lock" : "unlock", "key_fob_proximity", "Physical key fob",
                locking ? "Fresh physical key fob departure" : "Fresh physical key fob arrival",
                reading.eventElapsed, KeyFobEventEvaluator.FRESH_MS, false) {
            @Override boolean sourceValid() throws Exception {
                synchronized (STATE) {
                    refreshSettings();
                    return config.optBoolean("enabled") && config.optBoolean("keyFobEnabled")
                            && config.optLong("revision") == settings.optLong("revision")
                             && (!locking || config.optBoolean("keyFobLockEnabled"))
                             && keyFob.decisionStillValid(reading, SystemClock.elapsedRealtime(), event)
                             && (!locking || lockConsensus(false, SystemClock.elapsedRealtime()));
                }
            }
            @Override boolean sourceCurrent() throws Exception {
                synchronized (STATE) {
                    refreshSettings();
                    return config.optBoolean("enabled") && config.optBoolean("keyFobEnabled")
                            && config.optLong("revision") == settings.optLong("revision")
                             && (!locking || config.optBoolean("keyFobLockEnabled"))
                             && keyFob.decisionStillCurrent(reading, SystemClock.elapsedRealtime(), event)
                             && (!locking || lockConsensus(false, SystemClock.elapsedRealtime()));
                }
            }
        }, settings);
    }

    private static void performKeyFobPresence(KeyFobPresenceEvaluator.Observation reading,
                                              JSONObject settings, boolean firstDeparture) throws Exception {
        performDecision(new ActionDecision("lock", "key_fob_presence", "Physical key fob",
                "Confirmed physical key fob absence", reading.observed, KeyFobEventEvaluator.FRESH_MS, false) {
            @Override boolean publishesTransition() { return firstDeparture; }
            @Override boolean sourceValid() throws Exception {
                synchronized (STATE) {
                    refreshSettings();
                    return config.optBoolean("enabled") && config.optBoolean("keyFobEnabled")
                            && config.optBoolean("keyFobLockEnabled")
                            && config.optLong("revision") == settings.optLong("revision")
                            && keyFobPresence.decisionStillValid(reading, SystemClock.elapsedRealtime())
                            && lockConsensus(false, SystemClock.elapsedRealtime());
                }
            }
            @Override boolean sourceCurrent() throws Exception {
                synchronized (STATE) {
                    refreshSettings();
                    return config.optBoolean("enabled") && config.optBoolean("keyFobEnabled")
                            && config.optBoolean("keyFobLockEnabled")
                            && config.optLong("revision") == settings.optLong("revision")
                            && keyFobPresence.decisionStillCurrent(reading, SystemClock.elapsedRealtime())
                            && lockConsensus(false, SystemClock.elapsedRealtime());
                }
            }
        }, settings);
    }

    /** Both sources share the same final command guards and outcome delivery. */
    private static void performDecision(ActionDecision decision, JSONObject settings) throws Exception {
        String action = decision.action;
        synchronized (ACTION) {
            JSONObject live = RemoteAccessSettings.read();
            if (DiLink5Platform.isSelected()) {
                // Gate rails follow the live settings just read; never under diLink5IngressLock.
                try {
                    BydDataCollector gate = BydDataCollector.getInstance();
                    gate.setDiLink5LockRails(RemoteAccessSettings.di5LockRails(live));
                    gate.setDiLink5CarSvcLockMaxEventAgeSec(
                            RemoteAccessSettings.di5CarSvcLockMaxEventAgeSec(live));
                } catch (Throwable ignored) {
                    // The gate keeps its previous rail set; the decision proceeds on it.
                }
            }
            if (!live.optBoolean("enabled") || live.optLong("revision") != settings.optLong("revision")) return;
            // Cloud fallback actuator (17 Oct): read once from the live settings that just passed the
            // revision check. The gate below runs exactly as today whatever its value.
            boolean cloudFallback = RemoteAccessSettings.cloudFallback(live);
            // S3 (18 Oct): vehicle confirmation of the dispatched command (Advanced switch; normal mode
            // forces it on). Read from the effective settings, like the timing; gate unaffected.
            boolean confirmWithVehicle = RemoteAccessSettings.confirmWithVehicle(settings);
            if (decision.quietSatisfied()) return;
            if (decision.publishesTransition()) {
                synchronized (STATE) {
                    event("unlock".equals(action) ? "arrival" : "departure", decision.eventMessage);
                    EVENTS.peekFirst().put("source", decision.source);
                }
            }
            if (!settings.optBoolean("unlock".equals(action) ? "unlockEnabled" : "lockEnabled")) {
                if ("unlock".equals(action))
                    recordAction(action, "blocked", "Automatic unlock is disabled");
                else recordAction(action, "blocked", "Automatic lock is disabled");
                return;
            }
            if (!decision.bindSafeZones(live)) {
                recordAction(action, "blocked", decision.blockedReason("Safe-zone restriction paused this action"));
                return;
            }
            if (!decision.valid()) {
                recordAction(action, "blocked", decision.blockedReason("lock".equals(action)
                        ? decision.requiresBluetooth
                            ? phoneLockBlockedText()
                            : "Fresh confirmed keyfob departure is required"
                        : "Newer signal revoked this transition"));
                return;
            }
            if (settings.optBoolean("dryRun", true)) {
                recordAction(action, "dry_run", "Learning mode: no vehicle command sent");
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (!BluetoothProximityDecisionGuard.fresh(decision.observed, now, decision.freshnessMs)) {
                recordAction(action, "blocked", "Approach reading expired before the command");
                return;
            }
            if (!decision.valid()) {
                recordAction(action, "blocked", decision.blockedReason("Newer signal revoked this transition"));
                return;
            }
            BydDataCollector collector = BydDataCollector.getInstance();
            if (readVehiclePowerNow() != 0 || UnifiedConfigManager.isVehicleOnOnlyMode()
                    || (decision.requiresBluetooth && !UnifiedConfigManager.isBluetoothKeepAliveEnabled())) {
                recordAction(action, "blocked", "Vehicle must be confirmed off");
                return;
            }
            int before = collector.readDoorLockState();
            // DI5: which local rail produced the gate state, for the detail suffix and the daemon
            // log only. The numeric gate predicate below is unchanged; typed-rail texts stay as today.
            BydDataCollector.DiLink5LockEvidence beforeEvidence =
                    DiLink5Platform.isSelected() ? collector.readDiLink5GateDoorLockEvidence() : null;
            String railDetail = DiLink5GateActuationPolicy.detailSuffix(beforeEvidence);
            int target = "unlock".equals(action) ? 1 : 2; // SDK convention, driver-door scope
            if (before != 1 && before != 2) {
                recordAction(action, "blocked", "Local lock state is unknown");
                return;
            }
            if (!decision.valid() || !BluetoothProximityDecisionGuard.fresh(
                    decision.observed, SystemClock.elapsedRealtime(), decision.freshnessMs)) {
                recordAction(action, "blocked", decision.blockedReason("Signal expired during driver-door readback"));
                return;
            }
            if (before == target) {
                decision.satisfied();
                recordAction(action, "confirmed", "Driver door is already " + ("unlock".equals(action) ? "unlocked" : "locked") + railDetail);
                return;
            }
            if (ProximityCooldownPolicy.blocks(action, lastActionKind, SystemClock.elapsedRealtime(),
                    lastActionElapsed, settings.optLong("cooldownMs"), lastCommandConfirmed, before)) {
                recordAction(action, "blocked", "Cooldown active");
                return;
            }
            // B4: a lock right after OverDrive's own unlock waits for a door to open or the relock grace
            // (the relockGraceMs setting, 10 s by default; S4). Only the dispatch sites write
            // lastActionKind/lastActionElapsed, so a manual fob unlock never arms this.
            long relockGraceMs = RemoteAccessSettings.relockGraceMs(settings);
            if ("lock".equals(action) && !DiLink5Platform.isSelected()
                    && KeyFobPresenceEvaluator.relockGraceBlocks(lastActionKind, lastActionElapsed,
                            lastDoorOpenedElapsed, SystemClock.elapsedRealtime(), relockGraceMs)) {
                recordAction(action, "blocked", "Relock grace: a door must open or " + relockGraceMs / 1000
                        + " s must pass after the proximity unlock");
                return;
            }
            int[] doors = collector.readAllDoorOpenStates();
            if (doors == null || doors.length < 6) {
                recordAction(action, "blocked", "Door and lid state unavailable");
                return;
            }
            // P3: same predicate (any of the six closures != 0 blocks), the message names them.
            String doorBlock = DiLink5GateActuationPolicy.doorBlockReason(doors);
            if (doorBlock != null) {
                recordAction(action, "blocked", doorBlock);
                return;
            }
            if (!decision.valid()
                    || readVehiclePowerNow() != 0
                    || UnifiedConfigManager.isVehicleOnOnlyMode()
                    || (decision.requiresBluetooth && !UnifiedConfigManager.isBluetoothKeepAliveEnabled())) {
                recordAction(action, "blocked", decision.blockedReason("Signal or vehicle state changed before command"));
                return;
            }
            // Native safety reads can consume the lease. Revalidate immediately before dispatch.
            // LOW-3: ONE gate read here; on DI5 its state is exactly readDoorLockState() (same
            // ledger selection), and the same evidence tags the scope and picks the actuation rail.
            now = SystemClock.elapsedRealtime();
            BydDataCollector.DiLink5LockEvidence finalEvidence =
                    DiLink5Platform.isSelected() ? collector.readDiLink5GateDoorLockEvidence() : null;
            int finalLock = finalEvidence != null ? finalEvidence.state : collector.readDoorLockState();
            railDetail = finalEvidence != null ? DiLink5GateActuationPolicy.detailSuffix(finalEvidence) : railDetail;
            if (!BluetoothProximityDecisionGuard.fresh(decision.observed, now, decision.freshnessMs)
                    || !decision.valid() || (finalLock != 1 && finalLock != 2)) {
                recordAction(action, "blocked", decision.blockedReason("Signal or lock state expired before command"));
                return;
            }
            if (finalLock == target) {
                decision.satisfied();
                recordAction(action, "confirmed", "Driver door is already " + ("unlock".equals(action) ? "unlocked" : "locked") + railDetail);
                return;
            }
            // The rail the actuator confirms on: ota when it holds fresh evidence, else the gate's.
            BydDataCollector.DiLink5LockEvidence otaEvidence = finalEvidence == null
                    ? null : collector.readDiLink5GateDoorLockEvidenceFor(DiLink5LockRail.OTA);
            DiLink5LockRail actuationRail =
                    DiLink5GateActuationPolicy.preferredActuationRail(finalEvidence, otaEvidence);
            BydDataCollector.DiLink5LockEvidence actuationEvidence =
                    actuationRail == DiLink5LockRail.OTA ? otaEvidence : finalEvidence;
            // Cloud fallback: does the typed rail hold a fresh valid reading right now? null = typed=none
            // (disabled, INVALID or stale), i.e. the local writer cannot be confirmed by its own rail.
            BydDataCollector.DiLink5LockEvidence typedEvidence = cloudFallback && finalEvidence != null
                    ? collector.readDiLink5GateDoorLockEvidenceFor(DiLink5LockRail.TYPED_CABIN) : null;
            // Q14: slower-confirming rails (ota, carsvc) need lease left for their write path; the
            // typed rail and non-Di5 never block here. Remaining ms goes to the daemon log (D1).
            now = SystemClock.elapsedRealtime();
            if (DiLink5GateActuationPolicy.insufficientLease(
                    actuationRail, decision.observed, decision.freshnessMs, now)) {
                com.overdrive.app.daemon.CameraDaemon.log("Proximity: DI5 insufficient lease rail="
                        + actuationRail.tag() + " remainingMs=" + DiLink5GateActuationPolicy
                        .remainingLeaseMs(decision.observed, decision.freshnessMs, now));
                recordAction(action, "blocked", "Insufficient lease left for local actuation");
                return;
            }
            if (ProximityCooldownPolicy.blocks(action, lastActionKind, SystemClock.elapsedRealtime(),
                    lastActionElapsed, settings.optLong("cooldownMs"), lastCommandConfirmed, finalLock)) {
                recordAction(action, "blocked", "Cooldown active");
                return;
            }
            // S2 (18 Oct): the last command's confirmation runs off this monitor. The same direction is
            // answered from that job (one job per command, no second dispatch); the opposite direction
            // supersedes it — its stale readback or in-flight cloud leg is interrupted — and proceeds.
            ConfirmJob pendingJob = pendingConfirmJob;
            if (pendingJob != null && pendingJob.pending()) {
                if (pendingJob.action().equals(action)) {
                    recordAction(action, "blocked", "Command already in progress");
                    return;
                }
                pendingJob.supersede(action);
            }
            VehicleCommandRouter.VehicleCommand command = "unlock".equals(action)
                    ? new VehicleCommandRouter.UnlockCommand(false) : new VehicleCommandRouter.LockCommand();
            VehicleCommandRouter.CommandResult result;
            String lockOutcome = null;
            boolean legacyDispatch = false;
            long dispatchedAtElapsed = SystemClock.elapsedRealtime(); // origin of the 30 s decision budget
            if (DiLink5Platform.isSelected()) {
                com.overdrive.app.remoteaccess.DiLink5ProximityCommandGuard.Scope scope =
                        DiLink5GateActuationPolicy.gateScope(
                                java.util.UUID.randomUUID().toString(), action,
                                DiLink5Platform.currentActiveModeGeneration(), settings.optLong("revision"),
                                decision.observed + decision.freshnessMs, actuationEvidence);
                PendingActuation pending = new PendingActuation(scope, decision);
                pendingActuation = pending;
                try {
                    lastActionElapsed = SystemClock.elapsedRealtime();
                    lastActionKind = action;
                    lastCommandConfirmed = false;
                    result = com.overdrive.app.byd.VehicleActuatorBridge.withProximityCommand(scope,
                            () -> VehicleCommandRouter.getInstance().executeSdkOnly(command));
                } finally {
                    if (pendingActuation == pending) pendingActuation = null;
                    // The app's CONFIRMED / WRITTEN_UNCONFIRMED / FAILED, same thread as the dispatch.
                    lockOutcome = com.overdrive.app.byd.VehicleActuatorBridge.takeLastDiLink5LockOutcome();
                }
            } else {
                legacyDispatch = true;
                lastActionElapsed = SystemClock.elapsedRealtime();
                lastActionKind = action;
                lastCommandConfirmed = false;
                result = VehicleCommandRouter.getInstance().executeSdkOnly(command);
            }
            // A refused local leg is terminal here unless the cloud leg (which needs vehicle
            // confirmation on) is due; that leg and its local window belong to the confirm job.
            if (result.outcome != VehicleCommandRouter.Outcome.SUCCESS && !(cloudFallback && confirmWithVehicle)) {
                recordAction(action, "failed", result.displayMessage);
                notifyOutcome(decision, settings, "failed", result.displayMessage);
                return;
            }
            if (result.outcome == VehicleCommandRouter.Outcome.SUCCESS) {
                if (confirmWithVehicle)
                    recordAction(action, "accepted", "Local command accepted; awaiting driver-door readback" + railDetail);
                else recordAction(action, "sent", DiLink5GateActuationPolicy.CONFIRM_OFF_SENT_TEXT);
                if ("unlock".equals(action)) noteProximityUnlockDispatched(collector);
                if (finalEvidence != null) {
                    // Age belongs in the daemon log only (D1): recordAction dedups by exact message.
                    com.overdrive.app.daemon.CameraDaemon.log("Proximity: DI5 gate evidence at dispatch "
                            + DiLink5GateActuationPolicy.describe(finalEvidence, SystemClock.elapsedRealtime())
                            + " actuationRail=" + (actuationRail == null ? "none" : actuationRail.tag())
                            + " appOutcome=" + lockOutcome);
                }
                if (!confirmWithVehicle) {
                    // S3: the car is not asked to confirm — no readback window, no re-send, no cloud leg
                    // (it needs a readback to know it is due). lastCommandConfirmed stays false, so the
                    // cooldown treats the command as unconfirmed exactly as before.
                    notifyOutcome(decision, settings, "unconfirmed", DiLink5GateActuationPolicy.CONFIRM_OFF_SENT_TEXT);
                    return;
                }
            }
            // S2: everything from here on waits on the car and used to hold this monitor for up to the
            // 30 s decision budget; it now runs on the single-use confirm job (runProximityConfirmJob,
            // the same sequence verbatim). ACTION is released as soon as the job has started.
            ConfirmJob job = new ConfirmJob(decision, settings, collector, command, result, finalEvidence,
                    actuationRail, typedEvidence, target, cloudFallback, legacyDispatch, dispatchedAtElapsed);
            pendingConfirmJob = job;
            job.start();
        }
    }

    /**
     * S2 (18 Oct): the part of a decision that waits on the car, exactly the sequence that ran under
     * ACTION until then — rule (b)'s cloud leg after a refused local leg, the carsvc burst, the Di5
     * immediate cloud leg, the frozen legacy unlock sequence with its cloud hook, the distinct-readback
     * window, the post-window cloud leg and the terminal recordAction/notifyOutcome — on the job's own
     * thread, bounded by {@code DiLink5GateActuationPolicy.DECISION_BUDGET_MS} from the dispatch as
     * before. It takes STATE through the existing accessors only, never ACTION. The frozen methods
     * ({@code confirmLegacyUnlock}, {@code legacyUnlockResendAllowed}) are called exactly as before.
     * Interrupts (a superseding opposite decision) propagate to the caller, which records the outcome.
     */
    private static void runProximityConfirmJob(ConfirmJob job, ActionDecision decision, JSONObject settings,
            BydDataCollector collector, VehicleCommandRouter.VehicleCommand command,
            VehicleCommandRouter.CommandResult result, BydDataCollector.DiLink5LockEvidence finalEvidence,
            DiLink5LockRail actuationRail, BydDataCollector.DiLink5LockEvidence typedEvidence, int target,
            boolean cloudFallback, boolean legacyDispatch, long dispatchedAtElapsed) throws Exception {
        String action = decision.action;
        CloudLeg cloudLeg = null;                                  // at most ONE cloud attempt per decision
        try {
            if (result.outcome != VehicleCommandRouter.Outcome.SUCCESS) {
                if (cloudFallback) {
                    // Rule (b): the local leg was refused outright; ONE cloud attempt now, re-validated
                    // like the SDK dispatch, then a LOCAL window bounded by the hold budget.
                    cloudLeg = dispatchCloudFallback(decision, settings, collector, command, finalEvidence != null,
                            actuationRail, target, false, dispatchedAtElapsed,
                            DiLink5GateActuationPolicy.confirmationWindowMs(actuationRail), "sdk_failed");
                    DiLink5GateActuationPolicy.Confirmation afterCloud = cloudLeg.worthASecondWindow()
                            ? confirmLocallyAfterCloud(collector, finalEvidence != null, actuationRail, target,
                                    dispatchedAtElapsed)
                            : DiLink5GateActuationPolicy.Confirmation.UNCONFIRMED;
                    recordCloudFallbackOutcome(decision, settings, afterCloud, cloudLeg, actuationRail,
                            false, result.displayMessage, false);
                    return;
                }
                recordAction(action, "failed", result.displayMessage);
                notifyOutcome(decision, settings, "failed", result.displayMessage);
                return;
            }
            // Case (C): the daemon owns the confirming rail; refresh it for the whole window.
            if (actuationRail == DiLink5LockRail.CARSVC) collector.requestDiLink5CarSvcLockBurst(12_000L);
            // Cloud fallback, Di5 immediate rule: the typed rail cannot read back its own write
            // (typed=none), so the cloud leg goes out right after the SDK write; both legs command
            // the same direction (idempotent), and the ONE carsvc window below confirms either.
            if (cloudFallback && DiLink5GateActuationPolicy.cloudFallbackImmediate(finalEvidence != null, typedEvidence)) {
                // The rail's window below still runs after this leg: keep its length out of the cloud budget.
                cloudLeg = dispatchCloudFallback(decision, settings, collector, command, true, actuationRail, target,
                        true, dispatchedAtElapsed, DiLink5GateActuationPolicy.confirmationWindowMs(actuationRail),
                        "typed_none");
            }
            if (legacyDispatch && "unlock".equals(action)) {
                confirmLegacyUnlock(decision, settings, collector);
                // Cloud fallback after the other actor's readback/re-send sequence (J2): only when
                // that sequence did not confirm; a skipped leg is logged, never recorded twice.
                if (cloudFallback && !lastCommandConfirmed) {
                    cloudLeg = dispatchCloudFallback(decision, settings, collector, command, false, null, target,
                            true, dispatchedAtElapsed, DiLink5GateActuationPolicy.confirmationWindowMs(null),
                            "legacy_unconfirmed");
                    recordCloudFallbackOutcome(decision, settings, cloudLeg.result == null
                                    ? DiLink5GateActuationPolicy.Confirmation.UNCONFIRMED
                                    : confirmLocallyAfterCloud(collector, false, null, target, dispatchedAtElapsed),
                            cloudLeg, null, true, null, true);
                }
                return;
            }
            // Q12: two DISTINCT readbacks (different SDK read times) within the rail's window.
            // Non-Di5 keeps today's 4 s / 350 ms loop: each read is stamped with its own read time.
            DiLink5GateActuationPolicy.Confirmation confirmation =
                    DiLink5GateActuationPolicy.confirmByDistinctReadbacks(
                            finalEvidence != null
                                    ? collector::readDiLink5GateDoorLockEvidence
                                    : () -> DiLink5GateActuationPolicy.legacyReadback(
                                            collector.readDoorLockState(), SystemClock.elapsedRealtime()),
                            target, DiLink5GateActuationPolicy.confirmationWindowMs(actuationRail),
                            SystemClock::elapsedRealtime, Thread::sleep);
            if (confirmation == DiLink5GateActuationPolicy.Confirmation.CONFIRMED) {
                lastCommandConfirmed = true;
                decision.satisfied();
                recordAction(action, "confirmed", "Driver-door lock state confirmed");
                if ("lock".equals(action)) seedKeyFobArmingAfterProximityLock(decision);
                notifyOutcome(decision, settings, "confirmed", "Driver-door lock state confirmed");
                return;
            }
            if (cloudFallback) {
                // Rule (c): local leg accepted but unconfirmed. The Di5 immediate leg (if any) already
                // ran; otherwise ONE cloud attempt now, re-validated like the SDK dispatch, then a
                // second LOCAL window bounded by the hold budget.
                if (cloudLeg == null) {
                    cloudLeg = dispatchCloudFallback(decision, settings, collector, command, finalEvidence != null,
                            actuationRail, target, true, dispatchedAtElapsed,
                            DiLink5GateActuationPolicy.confirmationWindowMs(actuationRail), "unconfirmed");
                    if (cloudLeg.worthASecondWindow())
                        confirmation = confirmLocallyAfterCloud(collector, finalEvidence != null, actuationRail,
                                target, dispatchedAtElapsed);
                }
                recordCloudFallbackOutcome(decision, settings, confirmation, cloudLeg, actuationRail, true, null, false);
                return;
            }
            recordAction(action, "unconfirmed", "Command accepted; driver-door change could not be confirmed");
            notifyOutcome(decision, settings, "unconfirmed", "Command accepted; driver-door change could not be confirmed");
        } finally {
            // For the job's log line and, if the sequence was cut short, its terminal record.
            job.path = cloudLeg == null ? DiLink5GateActuationPolicy.PATH_SDK : cloudLeg.path;
            job.cloudAcknowledged = cloudLeg != null && cloudLeg.acknowledged();
        }
    }

    /** The legacy rail can ignore an accepted unlock for ~14 s after a lock; one re-send fits the 5 s lease. */
    private static final long LEGACY_UNLOCK_RESEND_AFTER_MS = 11 * DiLink5GateActuationPolicy.CONFIRMATION_POLL_MS;

    private static void confirmLegacyUnlock(ActionDecision decision, JSONObject settings,
                                            BydDataCollector collector) throws Exception {
        String action = decision.action;
        DiLink5GateActuationPolicy.Confirmation confirmation =
                confirmLegacyUnlockReadback(collector, LEGACY_UNLOCK_RESEND_AFTER_MS);
        if (confirmation != DiLink5GateActuationPolicy.Confirmation.CONFIRMED) {
            if (legacyUnlockResendAllowed(decision, collector)) {
                lastActionElapsed = SystemClock.elapsedRealtime();
                lastActionKind = action;
                lastCommandConfirmed = false;
                VehicleCommandRouter.CommandResult result = VehicleCommandRouter.getInstance()
                        .executeSdkOnly(new VehicleCommandRouter.UnlockCommand(false));
                if (result.outcome != VehicleCommandRouter.Outcome.SUCCESS) {
                    recordAction(action, "failed", result.displayMessage);
                    notifyOutcome(decision, settings, "failed", result.displayMessage);
                    return;
                }
                recordAction(action, "accepted", "Driver door still locked; local command re-sent");
                confirmation = confirmLegacyUnlockReadback(collector,
                        DiLink5GateActuationPolicy.TYPED_CONFIRMATION_WINDOW_MS);
            } else {
                confirmation = confirmLegacyUnlockReadback(collector,
                        2 * DiLink5GateActuationPolicy.CONFIRMATION_POLL_MS);
            }
        }
        if (confirmation == DiLink5GateActuationPolicy.Confirmation.CONFIRMED) {
            lastCommandConfirmed = true;
            decision.satisfied();
            recordAction(action, "confirmed", "Driver-door lock state confirmed");
            notifyOutcome(decision, settings, "confirmed", "Driver-door lock state confirmed");
            return;
        }
        recordAction(action, "unconfirmed", "Command accepted; driver-door change could not be confirmed");
        notifyOutcome(decision, settings, "unconfirmed", "Command accepted; driver-door change could not be confirmed");
    }

    private static DiLink5GateActuationPolicy.Confirmation confirmLegacyUnlockReadback(
            BydDataCollector collector, long windowMs) throws InterruptedException {
        return DiLink5GateActuationPolicy.confirmByDistinctReadbacks(
                () -> DiLink5GateActuationPolicy.legacyReadback(
                        collector.readDoorLockState(), SystemClock.elapsedRealtime()),
                BydDataCollector.DOOR_STATE_UNLOCK, windowMs, SystemClock::elapsedRealtime, Thread::sleep);
    }

    /** The first dispatch's guards, re-read: same lease, still valid, off, still locked, doors closed. */
    private static boolean legacyUnlockResendAllowed(ActionDecision decision, BydDataCollector collector)
            throws Exception {
        if (!BluetoothProximityDecisionGuard.fresh(decision.observed, SystemClock.elapsedRealtime(),
                decision.freshnessMs) || !decision.valid()
                || readVehiclePowerNow() != 0 || UnifiedConfigManager.isVehicleOnOnlyMode()
                || (decision.requiresBluetooth && !UnifiedConfigManager.isBluetoothKeepAliveEnabled())
                || collector.readDoorLockState() != BydDataCollector.DOOR_STATE_LOCK) return false;
        int[] doors = collector.readAllDoorOpenStates();
        if (doors == null || doors.length < 6) return false;
        for (int i = 0; i < 6; i++) if (doors[i] != 0) return false;
        return BluetoothProximityDecisionGuard.fresh(decision.observed, SystemClock.elapsedRealtime(),
                decision.freshnessMs) && decision.valid();
    }

    private static JSONObject bridge(String operation) throws Exception {
        RemoteDevViewBridgeClient.Response response = new RemoteDevViewBridgeClient().send(
                new JSONObject().put("command", "remoteAccess").put("operation", operation));
        return response == null ? null : response.metadata;
    }

    private static int readVehiclePowerNow() {
        if (com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected())
            return VehiclePowerReader.readLocalDiLink5Now(
                    com.overdrive.app.daemon.CameraDaemon.getAppContext());
        return BydDataCollector.getInstance().readPowerLevelNow();
    }

    // ── Cloud fallback actuator (product decision 17 Oct; docs "Cloud fallback actuator") ──────────

    /** One cloud-fallback leg: EITHER dispatched ({@code result} set) OR skipped ({@code skipped} set). */
    private static final class CloudLeg {
        final VehicleCommandRouter.CommandResult result; // null when skipped
        final String skipped;                             // why not, null when dispatched
        final String path;                                // DiLink5GateActuationPolicy.PATH_*
        CloudLeg(VehicleCommandRouter.CommandResult result, String skipped, String path) {
            this.result = result;
            this.skipped = skipped;
            this.path = path;
        }
        boolean acknowledged() {
            return result != null && result.outcome == VehicleCommandRouter.Outcome.SUCCESS;
        }
        /** A second LOCAL window is due: a cloud command went out, or the re-validation just saw the target. */
        boolean worthASecondWindow() {
            return result != null || DiLink5GateActuationPolicy.CLOUD_LEG_SKIP_LOCAL_AT_TARGET.equals(skipped);
        }
    }

    /**
     * Re-validates the decision (product-owner rule of 17 Oct; docs "Cloud fallback actuator") and
     * NOTHING more — the inputs of {@code DiLink5GateActuationPolicy.cloudLegSkipReason}: master on
     * with the same settings revision, cloud fallback still on, Learning mode off,
     * {@code decision.current()} (not revoked by a newer signal; the sources' revocation-only
     * variants), the vehicle still off, for a lock the closures still closed, and a LOCAL before-state
     * not already at the target (duplicate suppression). No observation-freshness lease here: that
     * lease proved the observation fresh for the FIRST (local) command; the cloud leg continues the
     * same decision. Then sends ONE cloud command through the router's existing cloud leg on a bounded
     * helper thread, with what is left of the 30 s decision budget ({@code reserveMs} is kept back for
     * a local window still due after the leg). Cloud state never feeds the gate or the confirmation.
     * Never throws from the re-validation or the cloud leg: any failure becomes a skipped leg or a
     * FAILED result. Never writes lastActionKind/lastActionElapsed/lastCommandConfirmed (J4: the cloud
     * leg belongs to the SAME decision as the SDK dispatch that wrote them).
     */
    private static CloudLeg dispatchCloudFallback(ActionDecision decision, JSONObject settings,
            BydDataCollector collector, VehicleCommandRouter.VehicleCommand command, boolean diLink5,
            DiLink5LockRail actuationRail, int target, boolean sdkAccepted, long dispatchedAtElapsed,
            long reserveMs, String trigger) throws Exception {
        String action = decision.action;
        boolean lock = "lock".equals(action);
        String skipped;
        long cloudTimeoutMs = 0L;
        try {
            JSONObject liveNow = RemoteAccessSettings.read();
            boolean settingsCurrent = liveNow.optBoolean("enabled")
                    && liveNow.optLong("revision") == settings.optLong("revision");
            boolean decisionCurrent = decision.current();
            // LOCAL reads only, the same the gate made: power, the closures for a lock, and the gate
            // ledger on Di5 / the legacy getter otherwise. Never cloud state.
            int power = readVehiclePowerNow();
            int[] doors = lock ? collector.readAllDoorOpenStates() : null;
            int local = diLink5 ? collector.readDiLink5GateDoorLockEvidence().state
                    : collector.readDoorLockState();
            cloudTimeoutMs = DiLink5GateActuationPolicy.cloudFallbackCloudTimeoutMs(dispatchedAtElapsed,
                    SystemClock.elapsedRealtime(), reserveMs, command.cloudTimeoutMs());
            skipped = DiLink5GateActuationPolicy.cloudLegSkipReason(settingsCurrent,
                    RemoteAccessSettings.cloudFallback(liveNow), liveNow.optBoolean("dryRun", true),
                    decisionCurrent, decision.blockedReason("decision revoked"), power, lock, doors,
                    local, target, cloudTimeoutMs);
        } catch (Throwable t) {
            skipped = "re-validation failed: " + t.getClass().getSimpleName(); // conservative: no cloud on error
        }
        if (skipped != null) {
            com.overdrive.app.daemon.CameraDaemon.log("Proximity: cloudFallback action=" + action
                    + " trigger=" + trigger + " skipped=" + skipped);
            return new CloudLeg(null, skipped, DiLink5GateActuationPolicy.PATH_SDK);
        }
        String path = sdkAccepted ? DiLink5GateActuationPolicy.PATH_SDK_THEN_CLOUD : DiLink5GateActuationPolicy.PATH_CLOUD;
        VehicleCommandRouter.CommandResult result = runCloudLegBounded(command, cloudTimeoutMs);
        // Nothing was sent in these three cases: the leg counts as skipped, not failed.
        if (result.outcome == VehicleCommandRouter.Outcome.AUTH_REQUIRED) skipped = "cloud not configured";
        else if (result.outcome == VehicleCommandRouter.Outcome.NOT_SUPPORTED) skipped = "cloud leg not available for this command";
        else if (result.outcome == VehicleCommandRouter.Outcome.BLOCKED_DRIVING) skipped = "vehicle not confirmed parked";
        if (skipped != null) {
            com.overdrive.app.daemon.CameraDaemon.log("Proximity: cloudFallback action=" + action
                    + " trigger=" + trigger + " skipped=" + skipped);
            return new CloudLeg(null, skipped, DiLink5GateActuationPolicy.PATH_SDK);
        }
        com.overdrive.app.daemon.CameraDaemon.log("Proximity: cloudFallback action=" + action
                + " trigger=" + trigger + " path=" + path + " outcome=" + result.outcome
                + " latencyMs=" + result.latencyMs + " budgetMs=" + cloudTimeoutMs
                + " detail=" + result.displayMessage);
        if (result.outcome == VehicleCommandRouter.Outcome.SUCCESS) {
            recordAction(action, "accepted", "Cloud command accepted; awaiting driver-door readback"
                    + DiLink5GateActuationPolicy.cloudFallbackDetail(path, actuationRail));
            if ("unlock".equals(action)) noteProximityUnlockDispatched(collector);
        }
        // A carsvc window still follows this leg (the Di5 immediate rule's pinned window, or the second
        // window): refresh the dump burst so that window sees back-to-back readings whatever the leg took.
        if (reserveMs > 0L && actuationRail == DiLink5LockRail.CARSVC)
            collector.requestDiLink5CarSvcLockBurst(reserveMs);
        return new CloudLeg(result, null, path);
    }

    /**
     * The ONE cloud leg on a single-use daemon helper thread, so the ACTION hold stays inside the
     * decision budget whatever the router's own 30 s cloud timeout does. At the bound the helper is
     * interrupted: the router treats a caller interruption exactly like its timeout (cancels the HTTP
     * call, returns FAILED, never dispatches after the interrupt). Never throws; never retries.
     */
    private static VehicleCommandRouter.CommandResult runCloudLegBounded(
            VehicleCommandRouter.VehicleCommand command, long boundMs) {
        java.util.concurrent.atomic.AtomicReference<VehicleCommandRouter.CommandResult> outcome =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread helper = new Thread(() -> {
            try {
                outcome.set(VehicleCommandRouter.getInstance().executeCloudOnly(command));
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "proximity-cloud-fallback");
        helper.setDaemon(true);
        long startedAt = SystemClock.elapsedRealtime();
        boolean interruptedWhileWaiting = false;
        try {
            helper.start();
            helper.join(Math.max(1L, boundMs));
        } catch (InterruptedException interrupted) {
            interruptedWhileWaiting = true;
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            failure.set(t);
        }
        long elapsed = SystemClock.elapsedRealtime() - startedAt;
        if (helper.isAlive()) {
            helper.interrupt();
            return VehicleCommandRouter.CommandResult.failed(VehicleCommandRouter.Path.CLOUD,
                    interruptedWhileWaiting ? "cloud leg abandoned: decision interrupted"
                            : "cloud leg exceeded the decision budget",
                    elapsed, new java.util.concurrent.TimeoutException("cloud leg bound " + boundMs + " ms"));
        }
        if (failure.get() != null) {
            return VehicleCommandRouter.CommandResult.failed(VehicleCommandRouter.Path.CLOUD,
                    "cloud leg threw " + failure.get().getClass().getSimpleName(), elapsed, failure.get());
        }
        VehicleCommandRouter.CommandResult result = outcome.get();
        return result != null ? result : VehicleCommandRouter.CommandResult.failed(
                VehicleCommandRouter.Path.CLOUD, "cloud leg returned no result", elapsed, null);
    }

    /** Second LOCAL window after a cloud leg; carsvc gets a fresh burst; cut to the decision budget. */
    private static DiLink5GateActuationPolicy.Confirmation confirmLocallyAfterCloud(BydDataCollector collector,
            boolean diLink5, DiLink5LockRail actuationRail, int target, long dispatchedAtElapsed)
            throws InterruptedException {
        long windowMs = DiLink5GateActuationPolicy.cloudFallbackSecondWindowMs(actuationRail, dispatchedAtElapsed,
                SystemClock.elapsedRealtime());
        if (windowMs <= 0L) return DiLink5GateActuationPolicy.Confirmation.UNCONFIRMED;
        if (actuationRail == DiLink5LockRail.CARSVC) collector.requestDiLink5CarSvcLockBurst(windowMs);
        // LOCAL rails only: the gate ledger on Di5, today's legacy getter otherwise. Never cloud state.
        return DiLink5GateActuationPolicy.confirmByDistinctReadbacks(
                diLink5 ? collector::readDiLink5GateDoorLockEvidence
                        : () -> DiLink5GateActuationPolicy.legacyReadback(
                                collector.readDoorLockState(), SystemClock.elapsedRealtime()),
                target, windowMs, SystemClock::elapsedRealtime, Thread::sleep);
    }

    /**
     * Terminal outcome of a decision that ran the cloud-fallback leg. "confirmed" is used ONLY when a
     * LOCAL readback confirmed the target; a cloud acknowledgement alone is {@code cloud_acknowledged}
     * (published as an unconfirmed notification, J5). With {@code terminalAlreadyRecorded} (the frozen
     * legacy unlock sequence recorded its own outcome) a skipped leg records nothing more (J2).
     */
    private static void recordCloudFallbackOutcome(ActionDecision decision, JSONObject settings,
            DiLink5GateActuationPolicy.Confirmation confirmation, CloudLeg leg, DiLink5LockRail actuationRail,
            boolean sdkAccepted, String sdkFailureText, boolean terminalAlreadyRecorded) throws Exception {
        if (terminalAlreadyRecorded && leg.result == null) return;
        String action = decision.action;
        String cloudDetail = DiLink5GateActuationPolicy.cloudFallbackDetail(leg.path, actuationRail);
        if (confirmation == DiLink5GateActuationPolicy.Confirmation.CONFIRMED) {
            // "via local command" only when the local leg was accepted; after a refused local leg the
            // confirmed state came from elsewhere (owner, fob) and the text says so.
            String localLeg = sdkAccepted ? "via local command"
                    : "without a command (local command failed: " + sdkFailureText + ")";
            String text = leg.result == null
                    ? "Driver-door lock state confirmed " + localLeg + "; cloud command not sent" + cloudDetail
                    : leg.acknowledged()
                            ? "Driver-door lock state confirmed via cloud command" + cloudDetail
                            : "Driver-door lock state confirmed " + localLeg + "; cloud command failed: "
                                    + cloudLegSummary(leg) + cloudDetail;
            lastCommandConfirmed = true;
            decision.satisfied();
            recordAction(action, "confirmed", text);
            if ("lock".equals(action)) seedKeyFobArmingAfterProximityLock(decision);
            notifyOutcome(decision, settings, "confirmed", text);
            return;
        }
        if (leg.acknowledged()) {
            String text = "Cloud command acknowledged; driver-door change could not be confirmed locally" + cloudDetail;
            recordAction(action, "cloud_acknowledged", text);
            notifyOutcome(decision, settings, "unconfirmed", text);
            return;
        }
        String text;
        if (leg.result == null) {
            text = sdkAccepted
                    ? "Command accepted; driver-door change could not be confirmed; cloud command not sent: "
                            + leg.skipped + cloudDetail
                    : "Local command failed (" + sdkFailureText + "); cloud command not sent: " + leg.skipped + cloudDetail;
            recordAction(action, sdkAccepted ? "unconfirmed" : "failed", text);
            notifyOutcome(decision, settings, sdkAccepted ? "unconfirmed" : "failed", text);
            return;
        }
        text = (sdkAccepted ? "Local command unconfirmed; cloud command failed: "
                : "Local command failed (" + sdkFailureText + "); cloud command failed: ")
                + cloudLegSummary(leg) + cloudDetail;
        recordAction(action, "failed", text);
        notifyOutcome(decision, settings, "failed", text);
    }

    /** Short text of a dispatched cloud leg's result; the cloud's onlineState only labels, never gates. */
    private static String cloudLegSummary(CloudLeg leg) {
        String onlineState = "unknown";
        try {
            onlineState = com.overdrive.app.byd.cloud.BydCloudDataProvider.getInstance().getStatusJson()
                    .optString("onlineState", "unknown");
        } catch (Throwable ignored) {
            // The label is cosmetic; the outcome never depends on it.
        }
        return DiLink5GateActuationPolicy.cloudLegSummary(leg.result.outcome.name(), leg.result.displayMessage,
                leg.result.error instanceof java.util.concurrent.TimeoutException, onlineState);
    }

    /** Daemon log exports must explain native signal failures without exposing phone identities. */
    private static void logPhoneDiagnostic(PhoneState phone, JSONObject sample,
                                           ProximityEvaluator.Snapshot evaluated,
                                           boolean eligible, int power, long now) {
        JSONObject reconnect = sample.optJSONObject("reconnect");
        String diagnostic = "connected=" + sample.optBoolean("connected")
                + " reason=" + diagnosticCode(evaluated.reason)
                + " state=" + evaluated.state.name() + " eligible=" + eligible
                + " power=" + power + " reconnect="
                + diagnosticCode(reconnect == null ? "" : reconnect.optString("reason"))
                + " stage=" + diagnosticCode(reconnect == null ? "" : reconnect.optString("errorStage"))
                + " raw=" + evaluated.rawRssi + " filtered=" + evaluated.medianRssi
                + " nearThreshold=" + phone.profile.optInt("nearThreshold", -12)
                + " awayThreshold=" + phone.profile.optInt("awayThreshold", -24)
                + " nearCount=" + evaluated.nearSampleCount + " nearDwellMs=" + evaluated.nearStableForMs
                + " disconnectionCount=" + evaluated.disconnectionSampleCount
                + " disconnectedForMs=" + evaluated.disconnectedForMs
                + " arrivalCandidate=" + evaluated.arrivalCandidate + " lockCandidate=" + evaluated.lockCandidate
                + " evidence=" + diagnosticCode(evaluated.evidenceKind)
                + " connectionKnown=" + evaluated.connectionKnown
                + " radioKnown=" + diagnosticBoolean(phone.signal, "radioReadKnown")
                + " radioOn=" + evaluated.radioOn;
        if (diagnostic.equals(phone.lastDiagnostic) || now < phone.nextDiagnosticAt) return;
        phone.lastDiagnostic = diagnostic;
        phone.nextDiagnosticAt = now + 30_000;
        int index = 1;
        for (PhoneState value : PHONES.values()) {
            if (value == phone) break;
            index++;
        }
        com.overdrive.app.daemon.CameraDaemon.log("Proximity: phone=" + index
                + " mode=" + com.overdrive.app.camera.dilink5.DiLink5Platform.currentActiveMode()
                + " sdk=" + android.os.Build.VERSION.SDK_INT + " " + diagnostic);
    }

    private static String diagnosticCode(String code) {
        return code != null && code.matches("[a-z_]{1,64}") ? code : "unknown";
    }

    private static String diagnosticInteger(JSONObject value, String key) {
        Object scalar = value == null ? null : value.opt(key);
        return scalar instanceof Integer ? scalar.toString() : "unknown";
    }

    private static String diagnosticBoolean(JSONObject value, String key) {
        Object scalar = value == null ? null : value.opt(key);
        return scalar instanceof Boolean ? scalar.toString() : "unknown";
    }

    /** Existing daemon/watchdog lanes share the app's one radio owner. */
    public static boolean reconcileBluetoothKeepAlive() {
        return reconcileBluetoothKeepAlive(false);
    }

    public static boolean reconcileBluetoothKeepAlive(boolean forceDisable) {
        try {
            int power = readVehiclePowerNow();
            RemoteDevViewBridgeClient.Response response = new RemoteDevViewBridgeClient().send(
                    new JSONObject().put("command", "remoteAccess").put("operation", "bluetoothKeepAlive")
                            .put("vehicleOff", power == 0).put("vehicleOn", power > 0)
                            .put("forceDisable", forceDisable));
            JSONObject result = response == null ? null : response.metadata;
            if (result == null || !result.optBoolean("success")) return false;
            JSONObject radio = result.optJSONObject("runtime");
            if (DiLink5Platform.isSelected()) logBluetoothDiagnostic(power, radio);
            if (radio != null) synchronized (STATE) {
                refreshSettings();
                if (runtime == null) runtime = new JSONObject();
                runtime.put("bluetoothRuntime", radio).put("radioOwned", radio.optBoolean("radioOwned"))
                        .put("bluetoothKeepAlive", radio.optBoolean("enabled"));
                changed();
            }
            return radio == null || !radio.optBoolean("cleanupPending");
        } catch (Exception unavailable) { return false; }
    }

    private static void logBluetoothDiagnostic(int power, JSONObject radio) {
        String diagnostic = "power=" + power
                + " radio=" + (radio == null ? -1 : radio.optInt("state", -1))
                + " requested=" + (radio != null && radio.optBoolean("requestedEnabled"))
                + " owned=" + (radio != null && radio.optBoolean("radioOwned"))
                + " error=" + diagnosticCode(radio == null ? "" : radio.optString("error"));
        long now = SystemClock.elapsedRealtime();
        synchronized (STATE) {
            if (diagnostic.equals(lastBluetoothDiagnostic) || now < nextBluetoothDiagnosticAt) return;
            lastBluetoothDiagnostic = diagnostic;
            nextBluetoothDiagnosticAt = now + 30_000;
        }
        com.overdrive.app.daemon.CameraDaemon.log("BluetoothKeepalive: mode=dilink5 " + diagnostic);
    }

    private static void refreshSettings() throws Exception {
        JSONObject disk = RemoteAccessSettings.read();
        if (!disk.toString().equals(config.toString())
                || !java.util.Objects.equals(presenceModeGeneration, DiLink5Platform.currentActiveModeGeneration()))
            reset(disk);
        // DI5 gate rails: the settings file was read above; the collector call takes
        // diLink5IngressLock after STATE (never the reverse) and must not fail a refresh.
        if (DiLink5Platform.isSelected()) {
            try {
                BydDataCollector collector = BydDataCollector.getInstance();
                collector.setDiLink5LockRails(RemoteAccessSettings.di5LockRails(config));
                collector.setDiLink5CarSvcLockMaxEventAgeSec(
                        RemoteAccessSettings.di5CarSvcLockMaxEventAgeSec(config));
                collector.noteDiLink5LockSettingsFallback(RemoteAccessSettings.di5PersistedFallbackDetail());
            } catch (Throwable ignored) {
                // The gate keeps its previous rail set; settings handling itself is unaffected.
            }
        }
    }

    private static void reset(JSONObject next) throws Exception {
        pendingActuation = null;
        JSONObject nextEffective = RemoteAccessSettings.effective(next);
        String nextModeGeneration = DiLink5Platform.currentActiveModeGeneration();
        // A radio-only change (the shared parked Bluetooth switch) keeps BOTH fob evaluators under
        // one predicate: the event evaluator's mode, revision and lock consent are unchanged, and
        // rebuilding it would disarm an armed away_to_near rail until the next fresh absence event.
        boolean radioOnlyChange = java.util.Objects.equals(presenceModeGeneration, nextModeGeneration)
                && KeyFobPresenceEvaluator.canRetainAcrossRadioChange(
                        config, effectiveConfig, next, nextEffective);
        // A1: a generic save that leaves every fob rule unchanged (only the revision and unrelated
        // settings moved) keeps the EVENT evaluator; it adopts the new revision in place.
        boolean fobRulesUnchanged = java.util.Objects.equals(presenceModeGeneration, nextModeGeneration)
                && KeyFobEventEvaluator.canRetainAcrossSettingsChange(effectiveConfig, nextEffective);
        boolean retainPresence = keyFobPresence != null && radioOnlyChange;
        boolean retainKeyFob = keyFob != null && (radioOnlyChange || fobRulesUnchanged);
        // M2 (18 Oct): a phone whose own entry and the shared phone rules (master switch, Learning
        // mode, unlock trigger, timing) are unchanged keeps its PhoneState — evaluator, history and
        // first-connected time — even though another phone was added, removed or edited and the
        // revision moved; relay() adopts the restarted source instance within a short window. A
        // changed or new phone gets a fresh state exactly as before, in the new roster order.
        boolean phoneRulesUnchanged = java.util.Objects.equals(presenceModeGeneration, nextModeGeneration)
                && MultiPhoneProximityPolicy.canRetainPhoneRules(effectiveConfig, nextEffective);
        LinkedHashMap<String, PhoneState> retainedPhones = new LinkedHashMap<>();
        if (phoneRulesUnchanged) for (PhoneState phone : PHONES.values()) {
            JSONObject nextProfile = RemoteAccessSettings.phone(nextEffective, phone.profile.getString("address"));
            if (MultiPhoneProximityPolicy.sameProfile(phone.profile, nextProfile))
                retainedPhones.put(phone.profile.getString("address"), phone);
        }
        config = new JSONObject(next.toString());
        effectiveConfig = nextEffective;
        presenceModeGeneration = nextModeGeneration;
        // 19 Oct: the fob rail runs its own trigger (keyFobUnlockTrigger, default near: the first fresh
        // detection of a locked, off car unlocks); the phones' unlockTrigger no longer selects its mode.
        if (!retainKeyFob)
            keyFob = new KeyFobEventEvaluator(ProximityEvaluator.UnlockTrigger.fromSetting(
                    effectiveConfig.optString("keyFobUnlockTrigger", "near")), effectiveConfig.optLong("revision"),
                    effectiveConfig.optBoolean("keyFobLockEnabled"));
        else keyFob.adoptRevision(effectiveConfig.optLong("revision"));
        if (!retainPresence)
            keyFobPresence = new KeyFobPresenceEvaluator(effectiveConfig.optLong("revision"),
                    effectiveConfig.optLong("keyFobLockWindowMs", 10_000), RemoteAccessSettings.keyFobLockSamples(effectiveConfig));
        PHONES.clear();
        JSONArray phones = effectiveConfig.getJSONArray("phones");
        long adoptUntil = SystemClock.elapsedRealtime() + SOURCE_INSTANCE_ADOPTION_WINDOW_MS;
        for (int i = 0; i < phones.length(); i++) {
            JSONObject profile = phones.getJSONObject(i);
            PhoneState retained = retainedPhones.get(profile.getString("address"));
            if (retained != null) retained.adoptInstanceUntilElapsedMs = adoptUntil;
            PHONES.put(profile.getString("address"),
                    retained != null ? retained : new PhoneState(profile, effectiveConfig));
        }
        runtime = new JSONObject().put("active", false).put("readerRegistered", false).put("radioOwned", false);
        changed();
    }

    /**
     * M2: after a retaining reset() the app restarts its collector (new instanceId, sequence from 1)
     * within a few seconds; a retained phone may adopt that one new instance until this deadline.
     */
    private static final long SOURCE_INSTANCE_ADOPTION_WINDOW_MS = 30_000;

    /**
     * M2 (18 Oct): called by relay() when a phone's observations arrive from a new source instance.
     * Inside the adoption window opened by a retaining reset(), the new instance is adopted onto
     * the retained evaluator, history and first-connected time; every other instance change (an
     * app-process restart, a brand-new phone, a closed window) resets them exactly as before. The
     * generation clock for rule (b) restarts at every instance change, and the previous instance's
     * native proof is never carried over; near evidence is kept because it can only veto.
     */
    private static boolean adoptSourceInstance(PhoneState phone, long now) {
        boolean adopt = phone.adoptInstanceUntilElapsedMs >= 0 && now <= phone.adoptInstanceUntilElapsedMs
                && !phone.sourceInstance.isEmpty();
        phone.adoptInstanceUntilElapsedMs = -1;
        phone.generationStartedElapsedMs = now;
        phone.lastProof = null;
        phone.lastProofElapsedMs = -1;
        phone.lastProofHealthy = false;
        if (!adopt) phone.firstConnectedElapsedMs = -1;
        return adopt;
    }

    /**
     * 18 Oct multi-phone bookkeeping, once per accepted observation (caller holds STATE). The
     * evaluator is reset by instance changes and signal revocation, so what the lock consensus
     * needs about an absent phone lives here: whether it ever connected in this monitor
     * generation, when it was last seen connected without departure-eligible Away evidence, and
     * what the app's native reader last said (connection state, radio, mode generation). Also
     * emits the R4 diagnostic exactly once per Near episode that cannot unlock (first connection,
     * no prior confirmed Away) — by roster index only, never a phone name or address.
     */
    private static void notePhoneObservation(PhoneState phone, JSONObject sample, long observed,
            BluetoothConnectionEvidence proof, boolean healthyProof, ProximityEvaluator.Snapshot evaluated) {
        boolean claimedConnected = sample.optBoolean("connected");
        if (claimedConnected && phone.firstConnectedElapsedMs < 0) phone.firstConnectedElapsedMs = observed;
        if (claimedConnected && !(evaluated.state == ProximityEvaluator.State.AWAY && evaluated.departureEligible))
            phone.lastNearEvidenceElapsedMs = observed;
        if (proof != null) {
            phone.lastProof = proof;
            phone.lastProofElapsedMs = observed;
            phone.lastProofHealthy = healthyProof;
        }
        if (!evaluated.firstConnectionNoPriorAway) return;
        int index = 1;
        for (PhoneState value : PHONES.values()) {
            if (value == phone) break;
            index++;
        }
        com.overdrive.app.daemon.CameraDaemon.log("Proximity: phone=" + index
                + " arrivalSuppressed reason=first_connection_no_prior_away state=" + evaluated.state.name()
                + " unlockTrigger=" + diagnosticCode(effectiveConfig.optString("unlockTrigger", "away_to_near"))
                + " raw=" + evaluated.rawRssi + " episodeId=" + evaluated.episodeId
                + " neverConnectedBefore=" + (phone.firstConnectedElapsedMs == observed));
    }

    /**
     * Parked-lane visibility for the app-process collector. {@link #status()} already
     * derives {@code collectorFresh}, but only when a client asks — so a collector that
     * stopped while nobody had the proximity page open left no trace in any export.
     * Logged on change, at most once a minute.
     */
    public static void logCollectorLiveness() {
        try {
            String diagnostic;
            synchronized (STATE) {
                refreshSettings();
                if (!config.optBoolean("enabled")) return;
                long now = SystemClock.elapsedRealtime();
                long latest = -1;
                int phones = 0;
                for (PhoneState phone : PHONES.values()) {
                    if (!phone.profile.optBoolean("enabled")) continue;
                    phones++;
                    long observed = phone.signal.optLong("observedAtElapsedMs", -1);
                    if (observed >= 0 && observed <= now) latest = Math.max(latest, observed);
                }
                if (phones == 0) return;
                long staleMs = effectiveConfig.optLong("staleMs");
                diagnostic = "phones=" + phones
                        + " fresh=" + (latest >= 0 && now - latest <= staleMs)
                        + " ageMs=" + (latest < 0 ? "none" : String.valueOf(now - latest))
                        + " staleMs=" + staleMs;
                if (diagnostic.equals(lastCollectorDiagnostic) || now < nextCollectorDiagnosticAt) return;
                lastCollectorDiagnostic = diagnostic;
                nextCollectorDiagnosticAt = now + 60_000;
            }
            com.overdrive.app.daemon.CameraDaemon.log("Proximity: collector " + diagnostic);
        } catch (Throwable unavailable) {
            // Visibility only: this must never break the parked maintenance lane.
        }
    }

    private static JSONObject status() throws Exception {
        refreshSettings();
        JSONArray signals = new JSONArray();
        JSONObject value = emptySignal(config.optBoolean("enabled") ? "waiting_for_phone" : "disabled");
        long now = SystemClock.elapsedRealtime();
        long latestObservation = -1;
        for (PhoneState phone : PHONES.values()) {
            ageSignal(phone, now);
            long observed = phone.signal.optLong("observedAtElapsedMs", -1);
            if (phone.profile.optBoolean("enabled") && observed >= 0 && observed <= now)
                latestObservation = Math.max(latestObservation, observed);
            JSONObject snapshot = new JSONObject(phone.signal.toString()).put("enabled", phone.profile.optBoolean("enabled"));
            signals.put(snapshot);
            if (phone.profile.getString("address").equals(config.optString("deviceAddress"))) value = snapshot;
        }
        // A paused or revoked signal carries no timestamp; the collector is alive while its deliveries are.
        if (lastObservationElapsed <= now) latestObservation = Math.max(latestObservation, lastObservationElapsed);
        JSONArray events = new JSONArray();
        for (JSONObject entry : EVENTS) events.put(entry);
        JSONObject currentRuntime = runtime == null ? new JSONObject() : new JSONObject(runtime.toString());
        boolean collectorFresh = config.optBoolean("enabled") && latestObservation >= 0
                && now - latestObservation <= effectiveConfig.optLong("staleMs");
        currentRuntime.put("collectorFresh", collectorFresh)
                .put("collectorAgeMs", latestObservation < 0 ? JSONObject.NULL : now - latestObservation);
        if (!collectorFresh) {
            currentRuntime.put("active", false).put("readerRegistered", false)
                    .put("reason", !config.optBoolean("enabled") ? "disabled"
                            : latestObservation < 0 ? "waiting_for_collector" : "collector_stale");
        }
        return new JSONObject().put("success", true).put("sequence", sequence).put("sessionId", SESSION_ID)
                .put("config", new JSONObject(effectiveConfig.toString()))
                .put("savedConfig", new JSONObject(config.toString())).put("signal", value).put("signals", signals)
                .put("runtime", currentRuntime)
                .put("keyFob", keyFob.current(now).toJson(now, effectiveConfig.optString("unlockTrigger", "away_to_near"))
                        .put("enabled", config.optBoolean("keyFobEnabled"))
                        .put("presence", keyFobPresence.current(now).toJson()))
                .put("bluetooth", BluetoothRadioStatusReader.cached())
                .put("safeZones", safeZoneSnapshot(effectiveConfig).toJson())
                .put("devices", pairedDevices == null ? new JSONArray() : pairedDevices)
                .put("events", events).put("lastAction", lastAction)
                .put("allPhonesAway", allPhonesAway(now))
                .put("vehicleOn", collectorFresh && lastObservedPower > 0)
                .put("capabilities", new JSONObject()
                        .put("bluetooth", BluetoothPlatformSupport.connectedRssiSupported())
                        .put("bluetoothReconnect", BluetoothPlatformSupport.reconnectSupported())
                        .put("keyFob", KeyFobAccessMonitor.platformSupported())
                        .put("keyFobLock", KeyFobAccessMonitor.platformSupported())
                        .put("keyFobUnlockOnly", false).put("maxPhones", 8));
    }

    /** The same aged snapshot is available to integrations without starting a sampler. */
    public static JSONObject currentSignal() throws Exception {
        synchronized (STATE) { return status().getJSONObject("signal"); }
    }

    public static JSONArray currentSignals() throws Exception {
        synchronized (STATE) { return status().getJSONArray("signals"); }
    }

    public static JSONObject currentKeyFob() throws Exception {
        synchronized (STATE) { return status().getJSONObject("keyFob"); }
    }

    private static boolean decisionStillValid(ProximityEvaluator.Snapshot reading, JSONObject settings,
                                              String instance, long serial) throws Exception {
        return decisionStillValid(reading, settings, instance, serial, false);
    }

    private static boolean decisionStillValid(ProximityEvaluator.Snapshot reading, JSONObject settings,
                                               String instance, long serial, boolean sameEpisode) throws Exception {
        long observed;
        String generation;
        synchronized (STATE) {
            PhoneState phone = PHONES.get(reading.address);
            observed = phone != null && phone.sourceSequence == serial
                    ? phone.signal.optLong("observedAtElapsedMs", -1) : -1;
            generation = phone == null ? null : phone.activeModeGeneration;
        }
        return decisionStillValid(reading, settings, instance, serial, observed, generation);
    }

    private static boolean decisionStillValid(ProximityEvaluator.Snapshot reading, JSONObject settings,
                                               String instance, long serial, long originalObserved,
                                               String originalModeGeneration) throws Exception {
        synchronized (STATE) {
            refreshSettings();
            PhoneState phone = PHONES.get(reading.address);
            if (phone == null || !phone.profile.optBoolean("enabled")
                    || !config.optBoolean("keepBluetoothOn")) return false;
            JSONObject signal = phone.signal;
            long now = SystemClock.elapsedRealtime();
            boolean arriving = reading.arrivalCandidate || reading.event == ProximityEvaluator.Event.ARRIVAL;
            if (!reading.arrivalCandidate && !reading.lockCandidate
                    && reading.event == ProximityEvaluator.Event.NONE) return false;
            return config.optBoolean("enabled") && config.optLong("revision") == settings.optLong("revision")
                    && BluetoothProximityDecisionGuard.allows(arriving ? "unlock" : "lock",
                        originalObserved, reading.episodeId, instance, serial, originalModeGeneration,
                        signal, phone.sourceInstance, phone.sourceSequence, activeModeGeneration(),
                        now, effectiveConfig.optLong("staleMs"),
                        phone.profile.optInt("nearThreshold", -12), phone.profile.optInt("awayThreshold", -24))
                    && (arriving || lockConsensus(true, now));
        }
    }

    /**
     * Cloud fallback (17 Oct): {@link #decisionStillValid} without the ORIGINAL observation's age bound
     * ({@code BluetoothProximityDecisionGuard.allowsIgnoringObservationAge}); every other guard — same
     * collector instance/serial/episode and mode generation, the action not yet satisfied, a fresh
     * latest reading with no pending candidate that still supports the direction, and the lock
     * consensus — is the same. Used only by the cloud leg of an already-dispatched decision.
     */
    private static boolean decisionStillCurrent(ProximityEvaluator.Snapshot reading, JSONObject settings,
                                                 String instance, long serial,
                                                 String originalModeGeneration) throws Exception {
        synchronized (STATE) {
            refreshSettings();
            PhoneState phone = PHONES.get(reading.address);
            if (phone == null || !phone.profile.optBoolean("enabled")
                    || !config.optBoolean("keepBluetoothOn")) return false;
            JSONObject signal = phone.signal;
            long now = SystemClock.elapsedRealtime();
            boolean arriving = reading.arrivalCandidate || reading.event == ProximityEvaluator.Event.ARRIVAL;
            if (!reading.arrivalCandidate && !reading.lockCandidate
                    && reading.event == ProximityEvaluator.Event.NONE) return false;
            return config.optBoolean("enabled") && config.optLong("revision") == settings.optLong("revision")
                    && BluetoothProximityDecisionGuard.allowsIgnoringObservationAge(arriving ? "unlock" : "lock",
                        reading.episodeId, instance, serial, originalModeGeneration,
                        signal, phone.sourceInstance, phone.sourceSequence, activeModeGeneration(),
                        now, effectiveConfig.optLong("staleMs"),
                        phone.profile.optInt("nearThreshold", -12), phone.profile.optInt("awayThreshold", -24))
                    && (arriving || lockConsensus(true, now));
        }
    }

    private static JSONObject snapshotJson(ProximityEvaluator.Snapshot s) throws Exception {
        return new JSONObject().put("deviceAddress", s.address)
                .put("rawRssi", s.rawRssi == null ? JSONObject.NULL : s.rawRssi)
                .put("filteredRssi", s.medianRssi == null ? JSONObject.NULL : s.medianRssi)
                .put("arrivalFilteredRssi", s.arrivalMedianRssi == null ? JSONObject.NULL : s.arrivalMedianRssi)
                .put("departureFilteredRssi", s.departureMedianRssi == null ? JSONObject.NULL : s.departureMedianRssi)
                .put("filterKind", s.filterKind).put("nearSampleCount", s.nearSampleCount)
                .put("nearStableForMs", s.nearStableForMs).put("nearCandidate", s.nearCandidate)
                .put("departureEligible", s.departureEligible)
                .put("evidenceKind", s.evidenceKind).put("connectionKnown", s.connectionKnown)
                .put("radioOn", s.radioOn).put("disconnectedForMs", s.disconnectedForMs)
                .put("disconnectionSampleCount", s.disconnectionSampleCount)
                .put("arrivalCandidate", s.arrivalCandidate).put("lockCandidate", s.lockCandidate)
                .put("episodeId", s.episodeId)
                .put("state", s.state.name()).put("candidate", s.candidate.name())
                .put("sampleCount", s.sampleCount).put("armed", s.armed)
                .put("ageMs", s.freshnessMs).put("stableForMs", s.stableForMs).put("reason", s.reason);
    }

    private static JSONObject emptySignal(String reason) {
        try { return new JSONObject().put("rawRssi", JSONObject.NULL).put("filteredRssi", JSONObject.NULL)
                .put("arrivalFilteredRssi", JSONObject.NULL).put("departureFilteredRssi", JSONObject.NULL)
                .put("filterKind", "departure").put("nearSampleCount", 0).put("nearStableForMs", 0)
                .put("nearCandidate", false).put("departureEligible", false)
                .put("evidenceKind", "unknown").put("connectionKnown", false).put("radioOn", false)
                .put("arrivalCandidate", false).put("lockCandidate", false).put("episodeId", 0)
                .put("actionSatisfied", false)
                .put("disconnectedForMs", 0).put("disconnectionSampleCount", 0)
                .put("state", "UNKNOWN").put("candidate", "UNKNOWN").put("sampleCount", 0)
                .put("ageMs", JSONObject.NULL).put("connected", false).put("armed", false)
                .put("reason", reason).put("deviceAddress", "").put("deviceName", "")
                .put("timestampMs", JSONObject.NULL).put("stableForMs", 0); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static void pruneCalibration(PhoneState phone, long now) {
        long window = effectiveConfig.optLong("windowMs", 15000);
        while (!phone.calibration.isEmpty() && (now - phone.calibration.peek()[0] > window || phone.calibration.size() > 256))
            phone.calibration.remove();
    }

    private static void ageSignal(PhoneState phone, long now) throws Exception {
        JSONObject signal = phone.signal;
        long observed = signal.optLong("observedAtElapsedMs", -1);
        long age = observed < 0 ? -1 : now - observed;
        signal.put("ageMs", age < 0 ? JSONObject.NULL : age);
        boolean nativeProof = BluetoothConnectionEvidence.hasAny(signal);
        if (!config.optBoolean("enabled") || !phone.profile.optBoolean("enabled"))
            revokePhoneSignal(phone, "disabled", nativeProof);
        else if (age < 0 || age > effectiveConfig.optLong("staleMs"))
            revokePhoneSignal(phone, age < 0 ? signal.optString("reason") : "stale", nativeProof);
        else if (nativeProof && !BluetoothProximityDecisionGuard.nativeProofHealthy(
                signal, now, effectiveConfig.optLong("staleMs"), activeModeGeneration()))
            revokePhoneSignal(phone, "connection_proof_unavailable", true);
    }

    private static void revokePhoneSignal(PhoneState phone, String reason, boolean nativeProof) throws Exception {
        phone.evaluator.reset();
        phone.calibration.clear();
        phone.activeModeGeneration = null;
        phone.satisfiedEpisodeId = 0;
        JSONObject revoked = emptySignal(reason).put("deviceAddress", phone.profile.getString("address"))
                .put("deviceName", phone.profile.getString("name")).put("observedAtElapsedMs", -1);
        if (nativeProof) {
            revoked.put("connectionReadKnown", false).put("connectionState", "unknown")
                    .put("radioReadKnown", false).put("connectionObservedAtElapsedMs", -1)
                    .put("connectionModeGeneration", "").put("modeGeneration", JSONObject.NULL);
        } else {
            revoked.remove("connectionKnown");
            revoked.remove("radioOn");
        }
        boolean changedSignal = !revoked.toString().equals(phone.signal.toString());
        phone.signal = revoked;
        if (changedSignal) changed();
    }

    private static String activeModeGeneration() {
        DiLink5Platform.ActiveModeSnapshot mode = DiLink5Platform.currentActiveModeSnapshot();
        return mode != null && DiLink5Platform.isActiveMode(mode.mode) ? mode.generation : null;
    }

    private static boolean allPhonesAway(long now) {
        return MultiPhoneProximityPolicy.allAway(phoneDepartureStates(), now, effectiveConfig.optLong("staleMs"));
    }

    private static java.util.List<MultiPhoneProximityPolicy.Observation> phoneDepartureStates() {
        java.util.List<MultiPhoneProximityPolicy.Observation> states = new java.util.ArrayList<>();
        long now = SystemClock.elapsedRealtime();
        long staleMs = effectiveConfig.optLong("staleMs");
        String generation = activeModeGeneration();
        for (PhoneState phone : PHONES.values()) {
            JSONObject signal = phone.signal;
            ProximityEvaluator.State state;
            try { state = ProximityEvaluator.State.valueOf(signal.optString("state", "UNKNOWN")); }
            catch (IllegalArgumentException unknown) { state = ProximityEvaluator.State.UNKNOWN; }
            // A returning phone blocks locking on its first strong raw reading.
            // Departure evidence uses its longer filter and a current raw Away
            // reading; a deadband sample cannot reuse historical Away values.
            boolean confirmedDisconnection = BluetoothProximityDecisionGuard.confirmedDisconnection(
                    signal, now, effectiveConfig.optLong("staleMs"), generation);
            boolean nativeProof = BluetoothConnectionEvidence.hasAny(signal);
            if (state == ProximityEvaluator.State.AWAY && !confirmedDisconnection
                    && (!signal.optBoolean("departureEligible")
                    || !"UNKNOWN".equals(signal.optString("candidate", "UNKNOWN"))
                    || !signal.optBoolean("connected") || signal.isNull("rawRssi")
                    || signal.optDouble("rawRssi", Double.POSITIVE_INFINITY)
                        > phone.profile.optInt("awayThreshold", -24)
                    || signal.isNull("departureFilteredRssi")
                    || signal.optDouble("departureFilteredRssi", Double.POSITIVE_INFINITY)
                        > phone.profile.optInt("awayThreshold", -24)
                    || nativeProof && !BluetoothProximityDecisionGuard.nativeProofHealthy(
                        signal, now, effectiveConfig.optLong("staleMs"), generation)))
                state = ProximityEvaluator.State.TRANSITION;
            // 18 Oct rules (a)/(b): the app reader's latest native read, kept in PhoneState because the
            // signal of a never-connected phone carries no timestamp and is revoked on every status
            // read. A positive connection read is one relay() accepted as healthy (fresh, radio On,
            // this mode generation) whose state agrees with the signal's connected flag; the radio
            // flag may also come from a read whose connection state was unknown.
            BluetoothConnectionEvidence proof = phone.lastProof;
            boolean proofFresh = proof != null && generation != null && generation.equals(proof.modeGeneration)
                    && BluetoothProximityDecisionGuard.fresh(phone.lastProofElapsedMs, now, staleMs);
            boolean connectionKnown = proofFresh && phone.lastProofHealthy && proof.healthyAt(now, staleMs)
                    && (proof.state == BluetoothConnectionEvidence.State.CONNECTED) == signal.optBoolean("connected");
            boolean radioOn = proofFresh && proof.radioReadKnown && proof.radioOn;
            states.add(new MultiPhoneProximityPolicy.Observation(
                    phone.profile.optBoolean("enabled") && config.optBoolean("keepBluetoothOn"),
                    signal.optBoolean("connected"), !signal.isNull("rawRssi"), state,
                    signal.optLong("observedAtElapsedMs", -1), confirmedDisconnection,
                    connectionKnown, radioOn, phone.firstConnectedElapsedMs < 0,
                    phone.generationStartedElapsedMs, proofFresh ? phone.lastProofElapsedMs : -1,
                    phone.lastNearEvidenceElapsedMs));
        }
        return states;
    }

    private static boolean lockConsensus(boolean phoneDeparture, long now) {
        boolean fobDetection = config.optBoolean("keyFobEnabled") && KeyFobAccessMonitor.platformSupported();
        boolean fobConfigured = fobDetection && config.optBoolean("keyFobLockEnabled");
        if (fobDetection && (hasFreshKeyFobApproach(now) || hasFreshKeyFobPresence(now))) return false;
        // 20 Oct: a phone departure waits for confirmed key absence only with lockWaitsForKeyAbsence (Advanced,
        // off by default and in normal mode); the fresh key approach/presence veto above still applies.
        return MultiPhoneProximityPolicy.canLock(phoneDeparture,
                !phoneDeparture || (fobConfigured && RemoteAccessSettings.lockWaitsForKeyAbsence(effectiveConfig)),
                fobConfigured && keyFobPresence.allowsLock(now), phoneDepartureStates(), now,
                effectiveConfig.optLong("staleMs"));
    }

    private static boolean hasFreshKeyFobPresence(long now) {
        KeyFobPresenceEvaluator.Snapshot presence = keyFobPresence.current(now);
        return presence.fresh && "PRESENT".equals(presence.state);
    }

    private static boolean hasFreshKeyFobApproach(long now) {
        KeyFobEventEvaluator.Snapshot passive = keyFob.current(now);
        return passive.fresh && "NEAR".equals(passive.state);
    }

    /** A2: a lock OverDrive dispatched and confirmed was gated by confirmed cabin absence (lockConsensus),
     *  so it is the car's own "key not found": seed the away_to_near rail with a 15 s quiet period. */
    private static void seedKeyFobArmingAfterProximityLock(ActionDecision decision) {
        if (DiLink5Platform.isSelected()) return;
        try {
            boolean applied;
            long quietMs;
            synchronized (STATE) {
                refreshSettings();
                if (!config.optBoolean("keyFobEnabled") || !KeyFobAccessMonitor.platformSupported()
                        || !config.optBoolean("keyFobLockEnabled")) return;
                // S4 (18 Oct): the quiet period is the postLockQuietMs setting (10 s default), read live
                // from the effective configuration; KeyFobEventEvaluator.POST_LOCK_QUIET_MS stays the
                // evaluator's own default.
                quietMs = RemoteAccessSettings.postLockQuietMs(effectiveConfig);
                applied = keyFob.seedAbsence(SystemClock.elapsedRealtime(), quietMs);
            }
            com.overdrive.app.daemon.CameraDaemon.log("Proximity: fob armingSeeded source=" + decision.source
                    + " applied=" + applied + " quietMs=" + quietMs);
        } catch (Throwable ignored) {
            // Seeding is an optimisation on the confirmed path; it must never surface an error.
        }
    }

    /**
     * A3: a phone departure whose only blocker is that the car is already locked (the key search is
     * paused while locked) says so instead of blaming a nearby key; otherwise today's text. Decision
     * logic is unchanged: this only picks the recorded message.
     */
    private static String phoneLockBlockedText() {
        try {
            synchronized (STATE) {
                if (config.optBoolean("keyFobEnabled") && config.optBoolean("keyFobLockEnabled")
                        && "presence_search_paused_locked".equals(
                                keyFobPresence.current(SystemClock.elapsedRealtime()).reason))
                    return "Driver door is already locked; the key fob search is paused while locked";
                // M4 (18 Oct): when a phone, not the fob, vetoed the consensus, name it and say why.
                if (phoneVetoesLock(SystemClock.elapsedRealtime()))
                    return blockingPhoneText(SystemClock.elapsedRealtime());
            }
        } catch (Throwable ignored) {
            // Fall through to the existing text.
        }
        return "Every active Bluetooth phone must confirm Away; a nearby key prevents locking";
    }

    /**
     * M4: the fob rules of lockConsensus did not block (no fresh fob approach or presence, and a
     * configured fob search allows locking) and at least one enabled phone fails the lock
     * consensus. Caller holds STATE; text selection only, no decision or state change.
     */
    private static boolean phoneVetoesLock(long now) {
        boolean fobDetection = config.optBoolean("keyFobEnabled") && KeyFobAccessMonitor.platformSupported();
        boolean fobConfigured = fobDetection && config.optBoolean("keyFobLockEnabled");
        if (fobDetection && (hasFreshKeyFobApproach(now) || hasFreshKeyFobPresence(now))) return false;
        if (fobConfigured && !keyFobPresence.allowsLock(now)) return false;
        return blockingPhoneText(now) != null;
    }

    /**
     * M4: names the first enabled phone (roster order) that fails the lock consensus and the
     * reason it does, or null when every enabled phone is Away. The phone name reaches the Activity
     * list and the daemon action log, as the owner asked for.
     */
    private static String blockingPhoneText(long now) {
        java.util.List<MultiPhoneProximityPolicy.Observation> states = phoneDepartureStates();
        long staleMs = effectiveConfig.optLong("staleMs");
        int index = 0;
        for (PhoneState phone : PHONES.values()) {
            MultiPhoneProximityPolicy.Observation observation = index < states.size() ? states.get(index) : null;
            index++;
            if (observation == null || !observation.enabled
                    || MultiPhoneProximityPolicy.awayForLock(observation, now, staleMs)) continue;
            String name = phone.profile.optString("name", "Trusted phone");
            if (observation.connected && observation.state == ProximityEvaluator.State.NEAR)
                return name + " is near";
            if (observation.connected)
                return name + " is connected but not confirmed away";
            if (observation.neverConnected) {
                long sinceMs = observation.monitorStartedElapsedMs < 0 ? 0
                        : Math.max(0, now - observation.monitorStartedElapsedMs);
                String since = sinceMs >= 60_000 ? sinceMs / 60_000 + " min" : sinceMs / 1_000 + " s";
                return name + " has not connected since " + since + "; bring it to the car once or disable it";
            }
            return name + " is disconnected but not confirmed away";
        }
        return null;
    }

    /** B4: make sure door edges are observed from the first proximity unlock on; idempotent, never throws. */
    private static void noteProximityUnlockDispatched(BydDataCollector collector) {
        if (DiLink5Platform.isSelected() || doorOpenObserverRegistered) return;
        try {
            collector.addDoorStateListener(DOOR_OPEN_OBSERVER);
            doorOpenObserverRegistered = true;
        } catch (Throwable ignored) {
            // Without door edges the grace simply lasts the full 15 s.
        }
    }

    /** Existing in-memory geometry and GPS only; never starts a GPS service or observer. */
    private static ProximitySafeZonePolicy.Result safeZoneSnapshot(JSONObject settings) {
        boolean master = settings.optBoolean("enabled");
        boolean lock = settings.optBoolean("restrictLockToOutsideSafeZones");
        boolean unlock = settings.optBoolean("restrictUnlockToOutsideSafeZones");
        java.util.List<String> ids = new java.util.ArrayList<>();
        JSONArray selected = settings.optJSONArray("proximitySafeZoneIds");
        if (selected != null) for (int i = 0; i < selected.length(); i++)
            ids.add(selected.optString(i, ""));
        java.util.List<ProximitySafeZonePolicy.Zone> zones = new java.util.ArrayList<>();
        ProximitySafeZonePolicy.Fix position = null, parked = null;
        try {
            // A configured selection can be labeled from existing memory even
            // while disabled. The default empty selection does no zone work.
            if (!ids.isEmpty()) for (com.overdrive.app.surveillance.SafeLocationManager.ZoneSnapshot zone
                    : com.overdrive.app.surveillance.SafeLocationManager.getInstance().getGeometrySnapshot()) {
                zones.add(new ProximitySafeZonePolicy.Zone(zone.id, zone.name,
                        zone.latitude, zone.longitude, zone.radiusMeters, zone.geometryValid));
            }
            if (master && (lock || unlock)) {
                com.overdrive.app.monitor.GpsMonitor.GpsFixSnapshot fix =
                        com.overdrive.app.monitor.GpsMonitor.getInstance().getFixSnapshot();
                if (fix != null && fix.hasLocation())
                    position = new ProximitySafeZonePolicy.Fix(fix.latitude, fix.longitude,
                            fix.accuracy, com.overdrive.app.monitor.GpsMonitor.fixAgeMs(fix));
                com.overdrive.app.monitor.ParkedPosition.Fix standing =
                        com.overdrive.app.monitor.ParkedPosition.get().current();
                if (standing != null)
                    parked = new ProximitySafeZonePolicy.Fix(standing.latitude, standing.longitude,
                            standing.accuracyM, SystemClock.elapsedRealtime() - standing.fixElapsedMs);
            }
        } catch (Exception unavailable) {
            position = null;
            parked = null;
            zones.clear();
        }
        return ProximitySafeZonePolicy.evaluate(master, lock, unlock, ids, zones, position, parked);
    }

    private static String safeZoneMessage(String reason) {
        switch (reason) {
            case "inside_safe_zone": return "Automatic action paused inside a selected safe zone";
            case "safe_zone_boundary": return "Automatic action paused near a safe-zone boundary";
            case "safe_zone_missing": return "A selected safe zone was removed; review the safe-zone selection";
            case "safe_zone_invalid": return "Selected safe-zone geometry is unavailable or invalid";
            case "no_safe_zones_selected": return "Choose a safe zone before restricting automatic actions";
            case "gps_fix_stale": return "Automatic action paused because the GPS fix is stale";
            case "gps_accuracy_poor": return "Automatic action paused because GPS accuracy is insufficient";
            case "disabled": return "Proximity is disabled";
            default: return "Automatic action paused until a fresh, reliable GPS position is available";
        }
    }

    private static void notifyOutcome(ActionDecision decision, JSONObject settings,
                                      String result, String reason) {
        RemoteAccessNotifications.publish(settings.optLong("revision"), decision.action, result, reason,
                decision.deviceName, decision.source);
    }

    private static void recordAction(String action, String result, String message) throws Exception {
        // S2: a confirm job remembers the last result it recorded (its log line; whether a terminal
        // outcome exists when its sequence is cut short). Thread-confined, no lock needed.
        Thread current = Thread.currentThread();
        if (current instanceof ConfirmJob) ((ConfirmJob) current).lastResult = result;
        synchronized (STATE) {
            long now = SystemClock.elapsedRealtime();
            boolean repeatableStatus = "blocked".equals(result) || "dry_run".equals(result)
                    || "confirmed".equals(result) && message.startsWith("Driver door is already ");
            if (repeatableStatus && action.equals(lastAction.optString("action"))
                    && result.equals(lastAction.optString("status"))
                    && message.equals(lastAction.optString("reason"))
                    && lastRecordedActionElapsed >= 0 && now >= lastRecordedActionElapsed
                    && now - lastRecordedActionElapsed < 60_000) return;
            lastRecordedActionElapsed = now;
            lastAction = new JSONObject().put("action", action).put("status", result).put("reason", message)
                    .put("timestampMs", System.currentTimeMillis()).put("confirmationScope", "driver_door");
            // Display-only translation code beside the unchanged English reason.
            com.overdrive.app.remoteaccess.RemoteAccessMessageCodes.annotate(lastAction, "reason", message);
            event("action", message);
            EVENTS.peekFirst().put("action", action).put("result", result);
            com.overdrive.app.daemon.CameraDaemon.log("Proximity: action=" + action
                    + " result=" + result + " reason=" + message);
        }
    }
    private static void event(String type, String message) throws Exception {
        EVENTS.addFirst(com.overdrive.app.remoteaccess.RemoteAccessMessageCodes.annotate(new JSONObject().put("timestampMs", System.currentTimeMillis())
                .put("type", type).put("message", message), "message", message));
        while (EVENTS.size() > 40) EVENTS.removeLast();
        if (!"action".equals(type))
            com.overdrive.app.daemon.CameraDaemon.log("Proximity: event=" + type + " reason=" + message);
        changed();
    }
    private static void changed() { sequence++; STATE.notifyAll(); }
    private static JSONObject error(String message) throws Exception {
        return com.overdrive.app.remoteaccess.RemoteAccessMessageCodes.annotate(new JSONObject().put("success", false).put("error", message), "error", message);
    }
    private static long queryLong(String path, String key, long fallback) {
        String[] pieces = path.split("\\?", 2);
        if (pieces.length == 2) for (String part : pieces[1].split("&"))
            if (part.startsWith(key + "=")) try { return Long.parseLong(part.substring(key.length() + 1)); }
            catch (NumberFormatException ignored) {}
        return fallback;
    }
    private static void rememberNonce(String nonce, long timestamp) {
        long cutoff = System.currentTimeMillis() - RemoteDevViewBridgeAuth.MAX_CLOCK_SKEW_MS;
        Iterator<Map.Entry<String, Long>> iterator = NONCES.entrySet().iterator();
        while (iterator.hasNext()) if (iterator.next().getValue() < cutoff) iterator.remove();
        if (NONCES.containsKey(nonce)) throw new SecurityException("Observation replay refused");
        if (NONCES.size() >= 512) throw new SecurityException("Too many signal envelopes");
        NONCES.put(nonce, timestamp);
    }
}

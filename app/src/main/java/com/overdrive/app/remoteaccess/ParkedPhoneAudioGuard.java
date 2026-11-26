package com.overdrive.app.remoteaccess;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Keeps calls and music on the phone while the car is parked and the proximity collector holds
 * the phone's HFP link (#1084). HFP itself is never disconnected and no profile priority is
 * changed: the collector and the reconnect controller depend on that link.
 *
 * <ul>
 *   <li>Call audio: the car's HFP client refuses the audio channel for that phone.</li>
 *   <li>Media: the car's media-sink connection is disconnected, a few times per park at most.</li>
 *   <li>Restore: at power-on, when the setting or collector goes away, and after a restart. A
 *       record is written before anything is changed, so a crash can never leave call audio
 *       refused on the next drive.</li>
 * </ul>
 *
 * <p>Every failure is contained here and recorded in the status; nothing flows back into
 * proximity evaluation, locking or unlocking. All calls are serialised on this object.
 */
final class ParkedPhoneAudioGuard {
    /** Native side effects. Implementations may block briefly; never called on the main thread. */
    interface Platform {
        boolean callAudioAllowed(String address) throws Exception;
        void setCallAudioAllowed(String address, boolean allowed) throws Exception;
        boolean mediaConnected(String address) throws Exception;
        boolean disconnectMedia(String address) throws Exception;
        boolean connectMedia(String address) throws Exception;
        /** Drops any profile bindings; the next call binds again. */
        void release();
    }

    /** What was changed for a phone, written before the change and removed after the restore. */
    static final class Record {
        /** Call-audio setting before the first refusal; null when it could not be read. */
        final Boolean priorAllowed;
        final boolean mediaDisconnected;

        Record(Boolean priorAllowed, boolean mediaDisconnected) {
            this.priorAllowed = priorAllowed;
            this.mediaDisconnected = mediaDisconnected;
        }

        Record withMediaDisconnected(boolean value) { return new Record(priorAllowed, value); }
    }

    interface Store {
        Map<String, Record> load();
        /** Synchronous: the record must be durable before the change it describes. */
        boolean save(Map<String, Record> records);
    }

    interface Clock { long elapsedRealtime(); }

    interface Logger { void log(String message); }

    private static final class PhoneState {
        boolean applied;
        boolean unsupported;
        boolean mediaUnsupported;
        boolean lastConnected;
        int applyFailures;
        int mediaDisconnects;
        long lastAttemptMs = -1L;
        long lastRestoreAttemptMs = -1L;
        String state = "off";
        String detail = "";
    }

    private final Platform platform;
    private final Store store;
    private final Clock clock;
    private final Logger logger;
    private final Map<String, PhoneState> phones = new HashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, org.json.JSONObject> published =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ScheduledExecutorService worker;
    private final Map<String, Tick> queued = new LinkedHashMap<>();
    private boolean drainScheduled;
    private Map<String, Record> records;

    /** Latest collector inputs for one phone; older ones are superseded, never replayed. */
    private static final class Tick {
        final boolean settingOn, connected;
        final java.util.function.BooleanSupplier monitorActive;
        final java.util.function.IntSupplier power;

        Tick(boolean settingOn, java.util.function.BooleanSupplier monitorActive,
                java.util.function.IntSupplier power, boolean connected) {
            this.settingOn = settingOn;
            this.monitorActive = monitorActive;
            this.power = power;
            this.connected = connected;
        }
    }

    /** Restore retries after a failed restore with no collector left to retry it. */
    static final int RESTORE_ATTEMPTS = 6;

    ParkedPhoneAudioGuard(Platform platform, Store store, Clock clock, Logger logger,
            java.util.concurrent.ScheduledExecutorService worker) {
        this.platform = platform;
        this.store = store;
        this.clock = clock;
        this.logger = logger;
        this.worker = worker;
    }

    /**
     * Collector entry point. Returns at once: Bluetooth binding can take seconds, and proximity
     * sampling must never wait for it. Only the latest tick per phone is evaluated, and
     * {@code monitorActive} is asked at evaluation time so a tick queued just before the
     * collector stopped can never re-apply behind the stop's restore.
     */
    void submit(String address, boolean settingOn, java.util.function.BooleanSupplier monitorActive,
            java.util.function.IntSupplier power, boolean connected) {
        synchronized (queued) {
            queued.put(normalize(address), new Tick(settingOn, monitorActive, power, connected));
            if (drainScheduled) return;
            drainScheduled = true;
        }
        try {
            worker.execute(this::drain);
        } catch (RuntimeException rejected) {
            synchronized (queued) { drainScheduled = false; }
        }
    }

    private void drain() {
        while (true) {
            Map<String, Tick> batch;
            synchronized (queued) {
                if (queued.isEmpty()) { drainScheduled = false; return; }
                batch = new LinkedHashMap<>(queued);
                queued.clear();
            }
            for (Map.Entry<String, Tick> entry : batch.entrySet()) {
                Tick tick = entry.getValue();
                try {
                    tick(entry.getKey(), tick.settingOn, tick.monitorActive.getAsBoolean(), tick.power,
                            tick.connected);
                } catch (Throwable unexpected) {
                    logger.log("Parked phone audio: tick failed: " + unexpected.getClass().getSimpleName());
                }
            }
        }
    }

    /**
     * Restores every recorded phone off the caller's thread, retrying a few times when no
     * collector remains to do it. {@code stillWanted} keeps a freshly re-applied park intact.
     */
    void restoreAllAsync(java.util.function.BooleanSupplier stillWanted) {
        scheduleRestore(stillWanted, RESTORE_ATTEMPTS, 0L);
    }

    private void scheduleRestore(java.util.function.BooleanSupplier stillWanted, int attemptsLeft, long delayMs) {
        if (attemptsLeft <= 0) return;
        try {
            worker.schedule(() -> {
                boolean done;
                try { done = restoreAll(stillWanted); }
                catch (Throwable unexpected) { done = false; }
                if (!done && !stillWanted.getAsBoolean())
                    scheduleRestore(stillWanted, attemptsLeft - 1, ParkedPhoneAudioPolicy.RETRY_INTERVAL_MS);
            }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (RuntimeException rejected) {
            // Process shutdown; the persisted record restores on the next start.
        }
    }

    /**
     * One collector tick for one trusted phone. {@code power} is read lazily so a disabled
     * feature with nothing to restore costs nothing.
     */
    synchronized void tick(String address, boolean settingOn, boolean monitorActive,
            java.util.function.IntSupplier power, boolean connected) {
        String key = normalize(address);
        PhoneState phone = phone(key);
        try { evaluate(key, phone, settingOn, monitorActive, power, connected); }
        finally { publish(key, phone); }
    }

    private void evaluate(String key, PhoneState phone, boolean settingOn, boolean monitorActive,
            java.util.function.IntSupplier power, boolean connected) {
        boolean reconnected = connected && !phone.lastConnected;
        phone.lastConnected = connected;
        boolean pending = records().containsKey(key);
        if (!settingOn && !pending && !phone.applied) {
            phone.state = "off";
            phone.detail = "";
            return;
        }
        ParkedPhoneAudioPolicy.Input in = new ParkedPhoneAudioPolicy.Input();
        in.settingOn = settingOn;
        in.monitorActive = monitorActive;
        in.power = power.getAsInt();
        in.connected = connected;
        in.reconnected = reconnected;
        in.applied = phone.applied;
        in.restorePending = pending;
        in.unsupported = phone.unsupported;
        in.applyFailures = phone.applyFailures;
        in.nowMs = clock.elapsedRealtime();
        in.lastAttemptMs = phone.lastAttemptMs;
        in.lastRestoreAttemptMs = phone.lastRestoreAttemptMs;
        switch (ParkedPhoneAudioPolicy.decide(in)) {
            case APPLY: apply(key, phone); break;
            case MAINTAIN: maintain(key, phone); break;
            case RESTORE: restore(key, phone); break;
            default:
                boolean wanted = ParkedPhoneAudioPolicy.wanted(settingOn, monitorActive, in.power);
                if (!wanted && !pending && !phone.applied) {
                    // Driving or switched off: the next park starts with a fresh budget.
                    phone.applyFailures = 0;
                    phone.mediaDisconnects = 0;
                    phone.unsupported = false;
                    phone.mediaUnsupported = false;
                    phone.state = settingOn ? "waiting_for_park" : "off";
                    phone.detail = "";
                } else if (wanted && !connected && !phone.applied && !phone.unsupported
                        && phone.applyFailures < ParkedPhoneAudioPolicy.MAX_APPLY_FAILURES) {
                    phone.state = "waiting_for_phone";
                }
                break;
        }
    }

    /**
     * Restores every recorded phone. Returns true when nothing is left to restore.
     * {@code stillWanted} is asked before each phone so a collector that has just re-applied
     * for a new park is never undone.
     */
    synchronized boolean restoreAll(java.util.function.BooleanSupplier stillWanted) {
        for (String key : new java.util.ArrayList<>(records().keySet())) {
            if (stillWanted.getAsBoolean()) return false;
            PhoneState phone = phone(key);
            try { restore(key, phone); }
            finally { publish(key, phone); }
        }
        boolean done = records().isEmpty();
        if (done) platform.release();
        return done;
    }

    /** Last published state; never waits for Bluetooth work in progress. */
    org.json.JSONObject snapshot(String address) {
        org.json.JSONObject value = published.get(normalize(address));
        if (value != null) return value;
        try { return new org.json.JSONObject().put("state", "off"); }
        catch (org.json.JSONException impossible) { return new org.json.JSONObject(); }
    }

    private void publish(String key, PhoneState phone) {
        org.json.JSONObject json = new org.json.JSONObject();
        try {
            json.put("state", phone.state).put("callAudioRefused", phone.applied)
                    .put("restorePending", records().containsKey(key))
                    .put("mediaDisconnects", phone.mediaDisconnects);
            if (!phone.detail.isEmpty()) json.put("detail", phone.detail);
        } catch (org.json.JSONException impossible) {
            // Keys and values are all valid JSON.
        }
        published.put(key, json);
    }

    private void apply(String key, PhoneState phone) {
        phone.lastAttemptMs = clock.elapsedRealtime();
        Record existing = records().get(key);
        if (existing == null) {
            Boolean prior;
            try { prior = platform.callAudioAllowed(key); }
            catch (Exception unreadable) { prior = null; }
            // Write-ahead: if this cannot be made durable, change nothing.
            if (!put(key, new Record(prior, false))) {
                fail(phone, "record_unavailable", null);
                return;
            }
        }
        try {
            platform.setCallAudioAllowed(key, false);
        } catch (Exception refused) {
            if (permanent(refused)) {
                // The call was rejected before it could change anything.
                phone.unsupported = true;
                if (existing == null) remove(key);
            }
            fail(phone, "call_audio_refuse_failed", refused);
            return;
        }
        boolean allowed;
        try { allowed = platform.callAudioAllowed(key); }
        catch (Exception unreadable) {
            // Assume it took effect; the record restores it either way.
            allowed = false;
        }
        if (allowed) {
            // The stack ignored the request; nothing was changed, so nothing needs restoring.
            phone.unsupported = true;
            Record record = records().get(key);
            if (record != null && !Boolean.FALSE.equals(record.priorAllowed) && !record.mediaDisconnected)
                remove(key);
            fail(phone, "call_audio_not_honoured", null);
            return;
        }
        phone.applied = true;
        phone.state = "blocked";
        phone.detail = "";
        logger.log("Parked phone audio: call audio refused for a trusted phone");
        dropMedia(key, phone);
    }

    private void maintain(String key, PhoneState phone) {
        phone.lastAttemptMs = clock.elapsedRealtime();
        try {
            if (platform.callAudioAllowed(key)) {
                // A reconnected phone gets a fresh HFP state machine with audio allowed again.
                platform.setCallAudioAllowed(key, false);
                logger.log("Parked phone audio: call audio refused again after a reconnect");
            }
        } catch (Exception unavailable) {
            phone.detail = stage("call_audio_check_failed", unavailable);
        }
        dropMedia(key, phone);
    }

    private void dropMedia(String key, PhoneState phone) {
        boolean connected;
        try { connected = platform.mediaConnected(key); }
        catch (Exception unreadable) { return; }
        if (!ParkedPhoneAudioPolicy.shouldDisconnectMedia(connected, phone.mediaDisconnects,
                phone.mediaUnsupported)) {
            if (connected && phone.mediaDisconnects >= ParkedPhoneAudioPolicy.MAX_MEDIA_DISCONNECTS)
                phone.detail = "media_keeps_reconnecting";
            return;
        }
        Record record = records().get(key);
        boolean marked = record != null && record.mediaDisconnected;
        if (!marked && (record == null || !put(key, record.withMediaDisconnected(true)))) return;
        phone.mediaDisconnects++;
        try {
            if (!platform.disconnectMedia(key)) phone.detail = "media_disconnect_rejected";
            else logger.log("Parked phone audio: media disconnected for a trusted phone");
        } catch (Exception refused) {
            phone.detail = stage("media_disconnect_failed", refused);
            if (permanent(refused)) {
                phone.mediaUnsupported = true;
                if (!marked) put(key, records().get(key).withMediaDisconnected(false));
            }
        }
    }

    private void restore(String key, PhoneState phone) {
        phone.lastRestoreAttemptMs = clock.elapsedRealtime();
        Record record = records().get(key);
        boolean ok = true;
        if (record != null || phone.applied) {
            boolean target = record == null || record.priorAllowed == null || record.priorAllowed;
            try {
                platform.setCallAudioAllowed(key, target);
            } catch (Exception refused) {
                ok = false;
                phone.detail = stage("call_audio_restore_failed", refused);
            }
            if (ok && record != null && record.mediaDisconnected) {
                try {
                    if (!platform.mediaConnected(key)) platform.connectMedia(key);
                } catch (Exception unavailable) {
                    // Media reconnect is best effort; the car and phone also reconnect it themselves.
                    phone.detail = stage("media_reconnect_failed", unavailable);
                }
            }
        }
        if (!ok) {
            phone.state = "restore_pending";
            return;
        }
        if (record != null) remove(key);
        if (phone.applied) logger.log("Parked phone audio: call and media audio restored");
        if (records().isEmpty()) platform.release();
        phone.applied = false;
        phone.lastRestoreAttemptMs = -1L;
        phone.lastAttemptMs = -1L;
        phone.applyFailures = 0;
        phone.mediaDisconnects = 0;
        phone.unsupported = false;
        phone.mediaUnsupported = false;
        phone.state = "restored";
        phone.detail = "";
    }

    private void fail(PhoneState phone, String stage, Exception cause) {
        phone.applyFailures++;
        phone.state = phone.unsupported ? "unsupported" : "failed";
        phone.detail = stage(stage, cause);
        logger.log("Parked phone audio: " + phone.detail);
    }

    private static String stage(String stage, Exception cause) {
        return cause == null ? stage : stage + ":" + cause.getClass().getSimpleName();
    }

    /** Failures that will not change by retrying this park. */
    static boolean permanent(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SecurityException || t instanceof NoSuchMethodException
                    || t instanceof UnsupportedOperationException || t instanceof ClassNotFoundException)
                return true;
            if (t.getCause() == t) break;
        }
        return false;
    }

    private PhoneState phone(String key) {
        PhoneState phone = phones.get(key);
        if (phone == null) {
            phone = new PhoneState();
            phones.put(key, phone);
        }
        return phone;
    }

    private Map<String, Record> records() {
        if (records == null) {
            Map<String, Record> loaded;
            try { loaded = store.load(); } catch (RuntimeException unreadable) { loaded = null; }
            records = loaded == null ? new LinkedHashMap<>() : new LinkedHashMap<>(loaded);
        }
        return records;
    }

    private boolean put(String key, Record record) {
        Map<String, Record> next = new LinkedHashMap<>(records());
        next.put(key, record);
        boolean saved;
        try { saved = store.save(next); } catch (RuntimeException unavailable) { saved = false; }
        if (saved) records = next;
        return saved;
    }

    private void remove(String key) {
        Map<String, Record> next = new LinkedHashMap<>(records());
        next.remove(key);
        boolean saved;
        try { saved = store.save(next); } catch (RuntimeException unavailable) { saved = false; }
        // Unsaved removal stays pending and is retried; restoring twice is harmless.
        if (saved) records = next;
    }

    private static String normalize(String address) {
        return address == null ? "" : address.trim().toUpperCase(Locale.US);
    }
}

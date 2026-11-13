package com.overdrive.app.remoteaccess;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.concurrent.TimeoutException;

/**
 * Serializes exact bonded-phone recovery on existing proximity ticks.
 * Creates no thread, timer, receiver, scan or pairing request.
 */
final class BluetoothPhoneReconnectController {
    static final int PROFILE_ID = 16; // Android 10 HEADSET_CLIENT, the car's HFP role.
    static final long PROFILE_TIMEOUT_MS = 5_000;
    static final long CONNECTION_TIMEOUT_MS = 15_000;
    static final long RETRY_INTERVAL_MS = 3_000;
    static final long GLOBAL_SPACING_MS = RETRY_INTERVAL_MS;
    /** M3 (18 Oct): per-phone backoff after this many consecutive timed-out or witnessed-failed attempts. */
    static final int BACKOFF_AFTER_FAILURES = 2;
    static final long BACKOFF_BASE_MS = CONNECTION_TIMEOUT_MS;
    static final long BACKOFF_CAP_MS = 120_000;
    static final long RADIO_RESTORE_GRACE_MS = 45_000;

    interface Clock {
        long elapsedRealtime();
        long wallTimeMillis();
    }

    /** Returns null only while this monitor generation and its parked consent remain valid. */
    interface Gate {
        String blockReason();
    }

    interface Profile {
        Collection<?> busyDevices() throws Exception;
        int connectionState(String address) throws Exception;
        int priority(String address) throws Exception;
        boolean connect(String address) throws Exception;
    }

    interface ProfileListener {
        void connected(int profileId, Profile profile);
        void disconnected(int profileId);
    }

    interface Platform {
        boolean supported();
        boolean adapterOn();
        boolean bonded(String address);
        boolean connected(String address);
        String activityBlockReason(String address) throws Exception;
        boolean openProfile(ProfileListener listener) throws Exception;
        void cancelPendingProfile();
        void closeProfile(Profile profile);
        void close();
    }

    /** Publishes only a fixed operation token and exception class, never native messages. */
    static final class Failure extends RuntimeException {
        final String stage;
        final String errorClass;

        Failure(String stage, Throwable error) {
            super(safeToken(stage));
            this.stage = safeToken(stage);
            this.errorClass = exceptionClass(error);
        }
    }

    static final class Snapshot {
        final String state;
        final String reason;
        final long lastAttemptMs;
        final long retryInMs;
        final int attemptCount;
        final String errorStage;
        final String errorClass;

        Snapshot(String state, String reason, long lastAttemptMs, long retryInMs, int attemptCount,
                 String errorStage, String errorClass) {
            this.state = state;
            this.reason = reason;
            this.lastAttemptMs = lastAttemptMs;
            this.retryInMs = retryInMs;
            this.attemptCount = attemptCount;
            this.errorStage = errorStage;
            this.errorClass = errorClass;
        }
    }

    private static final class Phone {
        String state = "waiting";
        String reason = "disconnected";
        long lastAttemptMs;
        long lastAttemptOrdinal;
        long nextAttemptElapsedMs;
        int attemptCount;
        /** M3: accepted attempts that timed out or were witnessed failing since the phone was last seen. */
        int consecutiveFailures;
        boolean unpairedSeen;
        String errorStage = "";
        String errorClass = "";
    }

    private final Platform platform;
    private final Gate gate;
    private final Clock clock;
    private final Map<String, Phone> phones = new LinkedHashMap<>();
    private Profile profile;
    private boolean binding;
    private long bindingStarted;
    private long profileGeneration;
    private long nextGlobalAttempt;
    private long radioRestoreGraceUntilElapsedMs;
    private long attemptOrdinal;
    private String connectingAddress;
    private long connectionDeadline;
    private boolean nativeConnectingObserved;
    private boolean stopped;

    BluetoothPhoneReconnectController(
            Collection<String> approvedPhones, Platform platform, Gate gate, Clock clock) {
        for (String address : approvedPhones) phones.put(address, new Phone());
        this.platform = platform;
        this.gate = gate;
        this.clock = clock;
    }

    /** Called by each existing phone worker; a successful request never fabricates a connection. */
    synchronized void tick(String address, boolean observedConnected) {
        Phone phone = phones.get(address);
        if (phone == null || stopped) return;
        long now = clock.elapsedRealtime();
        String blocked = blockedReason(address);
        if (blocked != null) {
            releaseProfile();
            set(phone, "unsupported_platform".equals(blocked) ? "unsupported" : "blocked", blocked);
            return;
        }
        boolean independentlyConnected;
        try { independentlyConnected = observedConnected && platform.connected(address); }
        catch (RuntimeException unavailable) {
            failure(phone, "adapter_state", unavailable);
            releaseProfile();
            set(phone, "error", "profile_unavailable");
            return;
        }
        if (independentlyConnected) {
            phone.nextAttemptElapsedMs = 0;
            phone.attemptCount = 0;
            phone.consecutiveFailures = 0;
            set(phone, "connected", "connected");
            clearFailure(phone);
            if (address.equals(connectingAddress)) clearPendingConnection();
            boolean allConnected = true;
            for (Phone value : phones.values()) allConnected &= "connected".equals(value.state);
            if (allConnected) releaseProfile();
            return;
        }
        if (connectingAddress != null && now >= connectionDeadline) {
            Phone timedOut = phones.get(connectingAddress);
            if (timedOut != null) {
                failure(timedOut, "profile_connect", new TimeoutException());
                set(timedOut, "waiting", "connection_timeout");
                backOff(timedOut, now);
            }
            clearPendingConnection();
        }
        blocked = attemptBlockReason(connectingAddress != null && now < connectionDeadline
                ? connectingAddress : address);
        // Aggregate activity may be our own accepted connection. Every other
        // activity/consent guard still revokes the pending lease immediately.
        if ("bluetooth_busy".equals(blocked) && connectingAddress != null
                && now < connectionDeadline) blocked = null;
        if (blocked != null) {
            releaseProfile();
            set(phone, "blocked", blocked);
            return;
        }
        if (connectingAddress != null) {
            // Persisted consent or adapter state can change during the aggregate
            // activity read; revoke the lease before probing the native profile.
            blocked = blockedReason(connectingAddress);
            if (blocked != null) {
                releaseProfile();
                set(phone, "blocked", blocked);
                return;
            }
            Phone pending = phones.get(connectingAddress);
            try {
                if (profile == null) throw new IllegalStateException();
                int state = profile.connectionState(connectingAddress);
                if (state < 0 || state > 3) throw new IllegalStateException();
                if (state == 1) nativeConnectingObserved = true;
                // An accepted request may still be queued with native state 0.
                // Only a witnessed CONNECTING -> DISCONNECTED transition ends
                // that grace early; this never cancels a native connection.
                if (state == 0 && nativeConnectingObserved) {
                    clearPendingConnection();
                    set(pending, "waiting", "connection_failed");
                    backOff(pending, now);
                    if (pending == phone && (now < phone.nextAttemptElapsedMs
                            || now < nextGlobalAttempt)) return;
                } else {
                    set(phone, connectingAddress.equals(address) ? "connecting" : "waiting",
                            connectingAddress.equals(address) ? "attempt_accepted" : "profile_busy");
                    return;
                }
            } catch (Exception unavailable) {
                failure(pending, "profile_connection_state", unavailable);
                delay(pending, now);
                releaseProfile();
                set(pending, "error", "profile_unavailable");
                if (pending != phone) set(phone, "waiting", "retry_backoff");
                return;
            }
        }
        if (now < phone.nextAttemptElapsedMs || now < nextGlobalAttempt) {
            set(phone, "waiting", "retry_backoff");
            return;
        }
        if (binding) {
            if (now - bindingStarted < PROFILE_TIMEOUT_MS) {
                set(phone, "binding", "profile_loading");
                return;
            }
            releaseProfile();
            delay(phone, now);
            failure(phone, "profile_binding", new TimeoutException());
            set(phone, "error", "profile_timeout");
            return;
        }
        if (profile == null) {
            openProfile(address, phone, now);
            return;
        }
        String stage = "profile_busy_devices";
        try {
            if (busy(profile)) {
                set(phone, "waiting", "profile_busy");
                return;
            }
            stage = "profile_connection_state";
            int nativeState = profile.connectionState(address);
            if (nativeState != 0) {
                // No request of ours is pending: a natively connected or disconnecting phone is present again.
                if (nativeState == 2 || nativeState == 3) clearBackoff(phone, now);
                set(phone, "waiting", "profile_busy");
                return;
            }
            stage = "profile_priority";
            if (profile.priority(address) <= 0) {
                set(phone, "blocked", "priority_disabled");
                return;
            }
            if (!nextPhoneTurn(address, now)) {
                set(phone, "waiting", "phone_turn");
                return;
            }
            // Recheck global profile ownership and persisted consent at the final call boundary.
            // HFP connect can otherwise evict another phone on a single-connection implementation.
            stage = "profile_busy_devices";
            if (busy(profile)) {
                set(phone, "waiting", "profile_busy");
                return;
            }
            stage = "adapter_state";
            boolean alreadyConnected = platform.connected(address);
            blocked = attemptBlockReason(address);
            if (blocked != null) {
                releaseProfile();
                set(phone, "blocked", blocked);
                return;
            }
            if (alreadyConnected) {
                phone.consecutiveFailures = 0;
                set(phone, "connected", "connected");
                clearFailure(phone);
                return;
            }
            phone.lastAttemptMs = clock.wallTimeMillis();
            phone.lastAttemptOrdinal = ++attemptOrdinal;
            phone.attemptCount++;
            delay(phone, now);
            stage = "profile_connect";
            boolean accepted = profile.connect(address);
            if (accepted) {
                clearFailure(phone);
                connectingAddress = address;
                connectionDeadline = now + CONNECTION_TIMEOUT_MS;
                nativeConnectingObserved = false;
                set(phone, "connecting", "attempt_accepted");
            } else {
                set(phone, "waiting", "attempt_rejected");
            }
        } catch (Exception unavailable) {
            failure(phone, stage, unavailable);
            delay(phone, now);
            releaseProfile();
            set(phone, "error", "profile_unavailable");
        }
    }

    synchronized Snapshot snapshot(String address) {
        Phone phone = phones.get(address);
        if (phone == null) return new Snapshot("blocked", "phone_not_trusted", 0, 0, 0, "", "");
        long retryIn = Math.max(0, Math.max(phone.nextAttemptElapsedMs, nextGlobalAttempt)
                - clock.elapsedRealtime());
        return new Snapshot(phone.state, phone.reason, phone.lastAttemptMs, retryIn, phone.attemptCount,
                phone.errorStage, phone.errorClass);
    }

    /** A radio restored after safe-zone suppression starts a fresh reconnect window. */
    synchronized void radioRestored() {
        if (stopped) return;
        long now = clock.elapsedRealtime();
        nextGlobalAttempt = Math.min(nextGlobalAttempt, now);
        radioRestoreGraceUntilElapsedMs = now + RADIO_RESTORE_GRACE_MS;
        releaseProfile();
        for (Phone phone : phones.values()) {
            if ("connected".equals(phone.state)) continue;
            phone.nextAttemptElapsedMs = Math.min(phone.nextAttemptElapsedMs, now);
            phone.consecutiveFailures = 0;
            clearFailure(phone);
            set(phone, "waiting", "radio_restored");
        }
    }

    synchronized void stop() {
        if (!stopped) {
            stopped = true;
            releaseProfile();
            try { platform.close(); }
            catch (RuntimeException ignored) {}
        }
        for (Phone phone : phones.values()) {
            clearFailure(phone);
            set(phone, "disabled", "stopped");
        }
    }

    /** Retains the failure after releasing every lease; it never restarts recovery. */
    synchronized void stopAfterUnexpectedFailure(Throwable error) {
        stop();
        for (Phone phone : phones.values()) {
            failure(phone, "monitor_tick", error);
            set(phone, "error", "profile_unavailable");
        }
    }

    private String blockedReason(String address) {
        if (stopped) return "stopped";
        String reason;
        try { reason = gate.blockReason(); }
        catch (RuntimeException unavailable) {
            failure(phones.get(address), "consent", unavailable);
            return "settings_unavailable";
        }
        if (reason != null) return reason;
        if (!phones.containsKey(address)) return "phone_not_trusted";
        String stage = "adapter_state";
        try {
            if (!platform.supported()) return "unsupported_platform";
            if (!platform.adapterOn()) return "radio_off";
            stage = "bonded_devices";
            if (!platform.bonded(address)) {
                phones.get(address).unpairedSeen = true;
                return "phone_not_paired";
            }
            if (phones.get(address).unpairedSeen) {
                // Bonded again after an unpaired spell: start its recovery budget afresh.
                phones.get(address).unpairedSeen = false;
                clearBackoff(phones.get(address), clock.elapsedRealtime());
            }
        } catch (RuntimeException unavailable) {
            failure(phones.get(address), stage, unavailable);
            return "profile_unavailable";
        }
        return null;
    }

    private static boolean busy(Profile profile) throws Exception {
        Collection<?> devices = profile.busyDevices();
        if (devices == null) throw new IllegalStateException("Profile state unavailable");
        return !devices.isEmpty();
    }

    private String attemptBlockReason(String address) {
        String blocked = blockedReason(address);
        if (blocked != null) return blocked;
        try { return platform.activityBlockReason(address); }
        catch (Exception unavailable) {
            failure(phones.get(address), "adapter_activity", unavailable);
            return "bluetooth_state_unavailable";
        }
    }

    private void openProfile(String address, Phone phone, long now) {
        String blocked = attemptBlockReason(address);
        if (blocked != null) {
            releaseProfile();
            set(phone, "blocked", blocked);
            return;
        }
        // No callback creates a connection. A later eligible worker tick performs that operation.
        binding = true;
        bindingStarted = now;
        long generation = ++profileGeneration;
        set(phone, "binding", "profile_loading");
        try {
            boolean requested = platform.openProfile(new ProfileListener() {
                @Override public void connected(int profileId, Profile incoming) {
                    synchronized (BluetoothPhoneReconnectController.this) {
                        if (stopped || generation != profileGeneration || !binding) {
                            close(incoming);
                            return;
                        }
                        if (profileId != PROFILE_ID || incoming == null) {
                            releaseProfile();
                            close(incoming);
                            delay(phone, clock.elapsedRealtime());
                            failure(phone, "profile_binding", new IllegalStateException());
                            set(phone, "error", "unexpected_profile");
                            return;
                        }
                        String reason = attemptBlockReason(address);
                        if (reason != null) {
                            close(incoming);
                            binding = false;
                            profileGeneration++;
                            set(phone, "blocked", reason);
                            return;
                        }
                        profile = incoming;
                        binding = false;
                    }
                }

                @Override public void disconnected(int profileId) {
                    synchronized (BluetoothPhoneReconnectController.this) {
                        if (profileId != PROFILE_ID || generation != profileGeneration) return;
                        releaseProfile();
                        delay(phone, clock.elapsedRealtime());
                        set(phone, "error", "profile_unavailable");
                    }
                }
            });
            if (!requested && generation == profileGeneration) {
                releaseProfile();
                delay(phone, now);
                failure(phone, "profile_binding", new IllegalStateException());
                set(phone, "error", "profile_unavailable");
            }
        } catch (Exception unavailable) {
            failure(phone, "profile_binding", unavailable);
            releaseProfile();
            delay(phone, now);
            set(phone, "error", "profile_unavailable");
        }
    }

    private void delay(Phone phone, long now) {
        phone.nextAttemptElapsedMs = now + RETRY_INTERVAL_MS;
        nextGlobalAttempt = now + GLOBAL_SPACING_MS;
    }

    /**
     * M3 (18 Oct): after {@link #BACKOFF_AFTER_FAILURES} consecutive accepted attempts that timed
     * out or were witnessed failing, this phone alone waits 15 s, then 30 s, 60 s and at most
     * 120 s before its next attempt, through the same {@code nextAttemptElapsedMs} that the retry
     * check and {@link #nextPhoneTurn} already honour. A present phone's turn is therefore no
     * longer held behind an absent phone's repeated 15-second attempts. Never applied to a
     * single-phone roster, whose scheduling is unchanged; the global 3 s spacing is untouched.
     */
    private void backOff(Phone phone, long now) {
        if (now < radioRestoreGraceUntilElapsedMs) {
            // A returning phone can take several connection windows to reappear after
            // the radio is restored. Keep the normal retry cadence during this grace.
            phone.nextAttemptElapsedMs = Math.max(phone.nextAttemptElapsedMs, now + RETRY_INTERVAL_MS);
            return;
        }
        if (phone.consecutiveFailures < Integer.MAX_VALUE) phone.consecutiveFailures++;
        if (phones.size() < 2 || phone.consecutiveFailures < BACKOFF_AFTER_FAILURES) return;
        int step = Math.min(3, phone.consecutiveFailures - BACKOFF_AFTER_FAILURES);
        long backoff = Math.min(BACKOFF_CAP_MS, BACKOFF_BASE_MS << step);
        phone.nextAttemptElapsedMs = Math.max(phone.nextAttemptElapsedMs, now + backoff);
    }

    /** The phone is visible or bonded again: forget its failures and any backoff beyond the normal retry. */
    private static void clearBackoff(Phone phone, long now) {
        phone.consecutiveFailures = 0;
        if (phone.nextAttemptElapsedMs > now + RETRY_INTERVAL_MS)
            phone.nextAttemptElapsedMs = now + RETRY_INTERVAL_MS;
    }

    /** Uses only existing worker observations; never probes another phone for scheduling. */
    private boolean nextPhoneTurn(String address, long now) {
        String selected = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<String, Phone> entry : phones.entrySet()) {
            Phone candidate = entry.getValue();
            boolean current = address.equals(entry.getKey());
            // The current worker just rechecked consent, pairing and priority.
            // Other workers refresh these cached states on their existing ticks.
            if (!current && ("connected".equals(candidate.state)
                    || "blocked".equals(candidate.state)
                    || "unsupported".equals(candidate.state)
                    || "disabled".equals(candidate.state))) continue;
            if (now < candidate.nextAttemptElapsedMs) continue;
            if (selected == null || candidate.lastAttemptOrdinal < oldest) {
                selected = entry.getKey();
                oldest = candidate.lastAttemptOrdinal;
            }
        }
        return address.equals(selected);
    }

    private void releaseProfile() {
        profileGeneration++;
        binding = false;
        clearPendingConnection();
        Profile current = profile;
        profile = null;
        try { platform.cancelPendingProfile(); }
        catch (RuntimeException ignored) {}
        close(current);
    }

    private void clearPendingConnection() {
        connectingAddress = null;
        connectionDeadline = 0;
        nativeConnectingObserved = false;
    }

    private void close(Profile profile) {
        if (profile == null) return;
        try { platform.closeProfile(profile); }
        catch (RuntimeException ignored) {}
    }

    private static void set(Phone phone, String state, String reason) {
        phone.state = state;
        phone.reason = reason;
    }

    private static void clearFailure(Phone phone) {
        phone.errorStage = "";
        phone.errorClass = "";
    }

    private static void failure(Phone phone, String stage, Throwable error) {
        if (phone == null) return;
        phone.errorStage = error instanceof Failure ? ((Failure) error).stage : safeToken(stage);
        phone.errorClass = error instanceof Failure ? ((Failure) error).errorClass : exceptionClass(error);
    }

    private static String exceptionClass(Throwable error) {
        for (int i = 0; i < 8 && (error instanceof InvocationTargetException
                || error instanceof UndeclaredThrowableException); i++) {
            Throwable cause = error.getCause();
            if (cause == null || cause == error) break;
            error = cause;
        }
        return error == null ? "" : safeToken(error.getClass().getSimpleName());
    }

    private static String safeToken(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_]{1,64}")) return "";
        return value;
    }
}

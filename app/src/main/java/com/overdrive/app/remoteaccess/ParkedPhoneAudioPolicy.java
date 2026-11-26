package com.overdrive.app.remoteaccess;

/**
 * When the parked phone link should refuse call audio and drop media audio, and when it must
 * give them back. Pure: no Bluetooth, settings or clock access.
 *
 * <p>Refusing audio is the risky state (a call while driving must reach the car), so anything
 * other than "setting on, collector running and power known off" restores. Unknown power
 * restores too.
 */
final class ParkedPhoneAudioPolicy {
    enum Action { NONE, APPLY, MAINTAIN, RESTORE }

    /** Re-check spacing while applied: a reconnected phone can get a fresh HFP state machine. */
    static final long MAINTAIN_INTERVAL_MS = 15_000L;
    /** Spacing between failed apply or restore attempts. */
    static final long RETRY_INTERVAL_MS = 10_000L;
    /** Failed applies per park before giving up until the next power cycle. */
    static final int MAX_APPLY_FAILURES = 2;
    /** Media disconnects per park; more means something reconnects it, so stop fighting it. */
    static final int MAX_MEDIA_DISCONNECTS = 3;

    private ParkedPhoneAudioPolicy() {}

    static final class Input {
        boolean settingOn;
        boolean monitorActive;
        /** 0 off, &gt;0 on, &lt;0 unknown. */
        int power = -1;
        boolean connected;
        boolean reconnected;
        /** Call audio confirmed refused in this process. */
        boolean applied;
        /** A persisted record says audio may still be refused (survives a crash). */
        boolean restorePending;
        /** The platform refused the call for a reason that will not change this park. */
        boolean unsupported;
        int applyFailures;
        long nowMs;
        /** Last apply or maintain attempt. */
        long lastAttemptMs = -1L;
        /** Last failed restore; -1 when none is outstanding, so power-on restores at once. */
        long lastRestoreAttemptMs = -1L;
    }

    static boolean wanted(boolean settingOn, boolean monitorActive, int power) {
        return settingOn && monitorActive && power == 0;
    }

    static Action decide(Input in) {
        if (!wanted(in.settingOn, in.monitorActive, in.power)) {
            return (in.applied || in.restorePending) && due(in.nowMs, in.lastRestoreAttemptMs, RETRY_INTERVAL_MS)
                    ? Action.RESTORE : Action.NONE;
        }
        if (!in.connected) return Action.NONE;
        if (!in.applied) {
            if (in.unsupported || in.applyFailures >= MAX_APPLY_FAILURES) return Action.NONE;
            return in.reconnected || due(in.nowMs, in.lastAttemptMs, RETRY_INTERVAL_MS)
                    ? Action.APPLY : Action.NONE;
        }
        return in.reconnected || due(in.nowMs, in.lastAttemptMs, MAINTAIN_INTERVAL_MS)
                ? Action.MAINTAIN : Action.NONE;
    }

    /** Media is dropped only while connected, within the per-park budget, on a platform that allows it. */
    static boolean shouldDisconnectMedia(boolean mediaConnected, int disconnects, boolean mediaUnsupported) {
        return mediaConnected && !mediaUnsupported && disconnects < MAX_MEDIA_DISCONNECTS;
    }

    private static boolean due(long now, long last, long interval) {
        return last < 0 || now - last >= interval || now < last;
    }
}

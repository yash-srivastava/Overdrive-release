package com.overdrive.app.launcher.layout;

/**
 * Admission/commit state independent of Android. Cancellation publishes a new identity before
 * queued work can finish, so a late allocation cannot bring a stopped layout back.
 */
public final class ClusterLayoutLifecycle {
    public enum Phase { IDLE, STARTING, ACTIVE, STOPPING, RECOVERING }
    public enum IdleCompletion { BLOCKED, STOPPED, RECOVERED }
    private volatile long generation;
    private volatile Phase phase = Phase.IDLE;
    private boolean restoreStandardAfterRecovery;

    public ClusterLayoutLifecycle(long seed) { generation = Math.max(0, seed); }
    public long generation() { return generation; }
    public Phase phase() { return phase; }
    public boolean busy() { return phase != Phase.IDLE && phase != Phase.ACTIVE; }
    public boolean active() { return phase == Phase.ACTIVE; }
    public boolean owns(long candidate) {
        return candidate > 0 && candidate == generation
                && (phase == Phase.STARTING || phase == Phase.ACTIVE);
    }

    public synchronized long begin() {
        if (busy()) return 0;
        generation++;
        phase = Phase.STARTING;
        return generation;
    }

    public synchronized boolean commit(long candidate) {
        if (candidate != generation || phase != Phase.STARTING) return false;
        phase = Phase.ACTIVE;
        return true;
    }

    public synchronized long cancel() {
        generation++;
        phase = Phase.STOPPING;
        return generation;
    }

    public synchronized void recovering() { phase = Phase.RECOVERING; }
    /** Recovery consumed an ACC-on restore opportunity while standard admission was fenced. */
    public synchronized void recoveringForStandardRestore() {
        restoreStandardAfterRecovery = true;
        phase = Phase.RECOVERING;
    }

    /** An absent cached marker is not proof of idle until the durable read has succeeded. */
    public synchronized IdleCompletion finishIdle(boolean recoveryReadConfirmed, boolean resourcesPending) {
        if (!recoveryReadConfirmed || resourcesPending || owns(generation))
            return IdleCompletion.BLOCKED;
        boolean replay = phase == Phase.RECOVERING && restoreStandardAfterRecovery;
        restoreStandardAfterRecovery = false;
        phase = Phase.IDLE;
        return replay ? IdleCompletion.RECOVERED : IdleCompletion.STOPPED;
    }

    public synchronized void idle() { phase = Phase.IDLE; }
}

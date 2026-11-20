package com.overdrive.app.remoteaccess;

/**
 * Read-only, roster-level proximity automation state. A confirmed Near visit is required
 * before an Away edge may be reported; missing or stale evidence cannot start an Away
 * episode. The caller supplies the same multi-phone evidence used for lock consensus.
 * This class never requests vehicle power or door-lock changes.
 */
public final class ProximityAutomationState {
    private String last = "unknown";
    private boolean visitedNear;
    private boolean awayEpisode;

    /**
     * Accept the current verified roster. Returns a new state or {@code null} if unchanged.
     * A temporarily unknown phone does not erase the visit while evidence stays fresh.
     * Call {@link #forgetVisit()} on stale evidence or movement: neither a cold-start
     * Away nor a recovered Away can then start an episode without a new confirmed Near.
     */
    public String observe(boolean anyConfirmedNear, boolean allConfirmedAway) {
        String next;
        if (anyConfirmedNear) {
            visitedNear = true;
            awayEpisode = false;
            next = "near";
        } else if (allConfirmedAway && (visitedNear || awayEpisode)) {
            visitedNear = false;
            awayEpisode = true;
            next = "away";
        } else {
            awayEpisode = false;
            next = "unknown";
        }
        if (last.equals(next)) return null;
        last = next;
        return next;
    }

    /** DrivingSafetyGuard reports verified movement independently of the automation rules. */
    public static boolean moving(String movementBlockReason) {
        return "moving".equals(movementBlockReason) || "not_park".equals(movementBlockReason);
    }

    /** Use the shared movement gate, including its GPS fallback for missing OEM speed. */
    public static boolean safeForPresence(int power, String movementBlockReason) {
        if (power < 0 || moving(movementBlockReason)) return false;
        // A confirmed power-off sample tolerates unavailable parked sensors. Power-on
        // requires DrivingSafetyGuard's authoritative stationary/Park confirmation.
        return power == 0 || movementBlockReason == null;
    }

    /** Invalidate departure history without changing the externally published state. */
    public void forgetVisit() {
        visitedNear = false;
        awayEpisode = false;
    }

    /** Motion must not publish Unknown or re-fire Near when the car next stops. */
    public void holdWhileMoving() {
        forgetVisit();
    }

    /** Forget observations when the configuration changes; true if a state was cleared. */
    public boolean reset() {
        boolean changed = !"unknown".equals(last);
        last = "unknown";
        forgetVisit();
        return changed;
    }
}

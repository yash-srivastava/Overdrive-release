package com.overdrive.app.remoteaccess;

/**
 * Read-only roster proximity state for automations: Away needs a confirmed Near visit first, and
 * stale evidence never starts an Away episode. Never requests power or lock changes.
 */
public final class ProximityAutomationState {
    private String last = "unknown";
    private boolean visitedNear;
    private boolean awayEpisode;

    /**
     * The new state, or null if unchanged. A briefly unknown phone keeps the visit while evidence is
     * fresh; callers forgetVisit() on stale evidence or movement so Away needs a new confirmed Near.
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

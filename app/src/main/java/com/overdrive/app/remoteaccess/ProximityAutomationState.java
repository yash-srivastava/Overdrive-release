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

    /**
     * Accept the current verified roster. Returns a new state or {@code null} if unchanged.
     * Unknown evidence does not erase the visit: the last phone may leave while another
     * phone is still being confirmed. It does, however, prevent a premature Away edge.
     */
    public String observe(boolean anyConfirmedNear, boolean allConfirmedAway) {
        String next;
        if (anyConfirmedNear) {
            visitedNear = true;
            next = "near";
        } else if (allConfirmedAway && (visitedNear || "away".equals(last))) {
            visitedNear = false;
            next = "away";
        } else {
            next = "unknown";
        }
        if (last.equals(next)) return null;
        last = next;
        return next;
    }

    /** Forget observations when the configuration changes; true if a state was cleared. */
    public boolean reset() {
        boolean changed = !"unknown".equals(last);
        last = "unknown";
        visitedNear = false;
        return changed;
    }
}

package com.overdrive.app.camera;

/**
 * BYD100F DiLink 100: keep our AVMCamera from cold-opening or restarting while the car is in R.
 *
 * <p>In R the car opens its own reverse view on the same camera service. Opening or reopening our
 * camera at that moment races the car's view and can freeze it (#1001). The gate only ever holds
 * for {@link #MAX_HOLD_MS}; after that the camera opens as before, so recording is delayed, never
 * lost for the whole reverse manoeuvre. An unknown or stale gear never holds.
 */
public final class ReverseViewCameraGate {

    /** Longest a cold open or stall restart waits for the car to leave R. */
    public static final long MAX_HOLD_MS = 12_000L;
    /** How often a waiting cold open re-reads the gear. */
    public static final long POLL_MS = 250L;

    private ReverseViewCameraGate() {
    }

    /**
     * @param holdStartMs when this hold began (elapsedRealtime), or 0 if it has not started
     */
    static boolean shouldHold(boolean byd100fAvmPath, int freshGear, long holdStartMs, long nowMs) {
        if (!byd100fAvmPath) return false;
        if (freshGear != com.overdrive.app.monitor.GearMonitor.GEAR_R) return false;
        return holdStartMs <= 0L || nowMs - holdStartMs < MAX_HOLD_MS;
    }

    /** True on a BYD100F unit on the AVMCamera path. */
    public static boolean appliesHere() {
        try {
            return DiLink100Platform.isAvmPathActive();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Current gear from the live poller, or a non-gear value when unknown or stale. */
    public static int freshGear() {
        try {
            return com.overdrive.app.monitor.GearMonitor.getInstance().getCurrentGearIfFresh();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Per-caller hold state. Not thread-safe; each caller owns one on a single thread. */
    public static final class Hold {
        private final boolean applies;
        private long startMs;

        public Hold(boolean applies) {
            this.applies = applies;
        }

        /** True to skip this open/restart now. Starts the hold on the first R, clears it off R. */
        public boolean holdNow(int freshGear, long nowMs) {
            if (!applies || freshGear != com.overdrive.app.monitor.GearMonitor.GEAR_R) {
                startMs = 0L;
                return false;
            }
            if (startMs <= 0L) startMs = Math.max(1L, nowMs);
            return shouldHold(true, freshGear, startMs, nowMs);
        }

        /** Milliseconds held so far, or 0 when not holding. */
        public long heldMs(long nowMs) {
            return startMs > 0L ? nowMs - startMs : 0L;
        }
    }

    /** Checks whether the caller still wants the camera; returns false to abandon the wait. */
    public interface StillRequired {
        boolean get();
    }

    /**
     * Wait (on the caller's own worker thread) while the car is in R, up to {@link #MAX_HOLD_MS}.
     *
     * @return false when {@code stillRequired} went false or the thread was interrupted
     */
    public static boolean awaitLeaveReverse(StillRequired stillRequired, Logger log) {
        if (!appliesHere()) return true;
        Hold hold = new Hold(true);
        boolean logged = false;
        while (true) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (!hold.holdNow(freshGear(), now)) {
                if (logged && log != null) {
                    log.info("Reverse-view hold over after " + hold.heldMs(now)
                            + "ms; opening the camera");
                }
                return true;
            }
            if (!logged && log != null) {
                log.info("Car is in R: holding our camera open (up to " + MAX_HOLD_MS
                        + "ms) so the car's reverse view opens first");
                logged = true;
            }
            if (stillRequired != null && !stillRequired.get()) return false;
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** Minimal logging hook so this class stays free of the daemon logger. */
    public interface Logger {
        void info(String message);
    }
}

package com.overdrive.app.camera;

/**
 * BYD100F only: camera recovery that does not fight the car's own 360/reverse view, which opens
 * the same AVMCamera service (#1001). Covers both the default AVMCamera mode and SurfaceTexture
 * mode; every other platform keeps its existing recovery unchanged.
 */
public final class Byd100fCameraRecovery {

    /** After HAL event 8, wait this long to see whether real frames still arrive. */
    public static final long ERROR_VERIFY_MS = 1_000L;
    /** How often a held error restart re-checks the gear and the frames. */
    public static final long REVERSE_RECHECK_MS = 1_000L;
    /** Zero-frame reopens allowed back to back, the same count that triggers the full rebuild. */
    public static final int FREE_ZERO_FRAME_REOPENS = 3;
    /** After those, one zero-frame reopen per this interval until a real frame arrives. */
    public static final long ZERO_FRAME_REOPEN_SPACING_MS = 30_000L;
    /** A watchdog tick whose wall-clock gap exceeds its uptime gap by this much slept. */
    public static final long SLEEP_GAP_MS = 5_000L;

    /** Shared by every camera instance in the process: a full rebuild makes a new one. */
    static final ReopenBudget PROCESS_BUDGET = new ReopenBudget();

    private Byd100fCameraRecovery() {
    }

    /** Same scope as the reverse-view gate: a BYD100F unit on an AVMCamera path. */
    public static boolean appliesHere() {
        return ReverseViewCameraGate.appliesHere();
    }

    /**
     * True when a watchdog tick spanned a device sleep (or a forward wall-clock jump), so the
     * wall-clock heartbeat age says nothing about the GL thread.
     */
    static boolean crossedSleep(long wallDeltaMs, long uptimeDeltaMs) {
        return wallDeltaMs - uptimeDeltaMs > SLEEP_GAP_MS;
    }

    /**
     * Spaces out reopens of a camera that keeps delivering zero frames. Reopens of a camera that
     * streamed are never delayed, and the first {@link #FREE_ZERO_FRAME_REOPENS} zero-frame
     * reopens (which drive the full-rebuild escalation) run as before.
     */
    static final class ReopenBudget {
        private volatile int zeroFrameReopens;
        private long lastReopenMs;

        /** Called for every real camera frame; cheap on the frame path. */
        void onGenuineFrame() {
            if (zeroFrameReopens != 0) zeroFrameReopens = 0;
        }

        /**
         * @param noFrameSinceOpen the current open has not delivered a frame
         * @param nowMs monotonic time (elapsedRealtime)
         * @return 0 when the reopen may run now (and is counted), otherwise ms left to wait
         */
        synchronized long delayBeforeReopen(boolean noFrameSinceOpen, long nowMs) {
            if (!noFrameSinceOpen) {
                zeroFrameReopens = 0;
                lastReopenMs = nowMs;
                return 0L;
            }
            if (zeroFrameReopens >= FREE_ZERO_FRAME_REOPENS) {
                long wait = lastReopenMs + ZERO_FRAME_REOPEN_SPACING_MS - nowMs;
                if (wait > 0L) return wait;
            }
            zeroFrameReopens++;
            lastReopenMs = nowMs;
            return 0L;
        }

        int zeroFrameReopens() {
            return zeroFrameReopens;
        }
    }
}

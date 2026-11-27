package com.overdrive.app.camera;

/**
 * BYD100F only (#1001): hold our stall restart after the car's own camera view takes the stream.
 *
 * <p>On BYD100F the AVMCamera event callback reports {@link #EVENT_CAR_VIEW_TOOK_STREAM} at the
 * moment our frames stop, ahead of a shift into R and also in D (the car's camera button). The
 * car's camera-user binder is missing on this firmware, so the coordinator cannot see the car's
 * view; without this hold the frame-stall watchdog reopens our camera about 4 s later and takes the
 * stream back while the car's view is still on screen, which freezes that view.
 *
 * <p>The hold ends as soon as our frames come back on their own, and is bounded: up to
 * {@link #CAR_VIEW_R_MAX_MS} from the event while the car is in R, otherwise
 * {@link #CAR_VIEW_MAX_MS}. After that the stall restart runs as before.
 */
public final class CarViewStreamHold {

    /** AVMCamera event seen when the car's own camera view takes the stream from us. */
    public static final int EVENT_CAR_VIEW_TOOK_STREAM = 1008;
    /** Longest hold after the event while the car is not in R. */
    public static final long CAR_VIEW_MAX_MS = 30_000L;
    /** Longest hold after the event while the car is in R. */
    public static final long CAR_VIEW_R_MAX_MS = 120_000L;
    /** A frame this soon after the event may have been in flight already; it does not end the hold. */
    public static final long FRAME_RESUME_GRACE_MS = 500L;

    private CarViewStreamHold() {
    }

    /**
     * @param applies          BYD100F unit on an AVMCamera path
     * @param eventMs          elapsedRealtime of the last event, or 0 when none is pending
     * @param framesResumed    a real frame arrived after the event (past the grace)
     * @param freshGear        live gear, or a non-gear value when unknown or stale
     * @param nowMs            elapsedRealtime
     */
    static boolean shouldHold(boolean applies, long eventMs, boolean framesResumed,
                              int freshGear, long nowMs) {
        if (!applies || eventMs <= 0L || framesResumed) return false;
        long age = Math.max(0L, nowMs - eventMs);
        long cap = freshGear == com.overdrive.app.monitor.GearMonitor.GEAR_R
                ? CAR_VIEW_R_MAX_MS : CAR_VIEW_MAX_MS;
        return age < cap;
    }

    /** True when a frame at {@code frameMs} shows our stream came back after the event. */
    static boolean frameEndsHold(long eventMs, long frameMs) {
        return eventMs > 0L && frameMs - eventMs >= FRAME_RESUME_GRACE_MS;
    }
}

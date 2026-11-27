package com.overdrive.app.surveillance;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lock-free camera load readings and a "recording is starting" signal for optional background work
 * (the Dashcam view export). Writers do one volatile store per frame; {@link #recordingStarting}
 * never blocks the caller.
 */
public final class CameraLoadProbe {
    /** Last pano encoder-thread frame (Stage) and mosaic draw+swap (DrawStage), ns. */
    public static volatile long stageNs;
    public static volatile long drawNs;
    /** AI lane frames processed (single writer: the AI worker thread). */
    public static volatile long aiFrames;

    private static final AtomicLong BUSY_EPOCH = new AtomicLong();
    private static volatile String busyReason;
    private static volatile Runnable busyListener;

    private CameraLoadProbe() {}

    public static void noteStage(long ns) { stageNs = ns; }

    public static void noteDraw(long ns) { drawNs = ns; }

    public static void noteAiFrame() { aiFrames++; }

    /** A recording, event clip, pre-roll flush, live view or ACC ON is starting: background work stops now. */
    public static void recordingStarting(String reason) {
        busyReason = reason;
        BUSY_EPOCH.incrementAndGet();
        Runnable r = busyListener;
        if (r != null) {
            try {
                r.run();
            } catch (Throwable ignored) {
            }
        }
    }

    public static long busyEpoch() { return BUSY_EPOCH.get(); }

    public static String lastBusyReason() { return busyReason; }

    /** The listener must only set flags (it runs on the recorder's thread). */
    public static void setBusyListener(Runnable listener) { busyListener = listener; }
}

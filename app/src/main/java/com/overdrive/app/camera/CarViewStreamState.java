package com.overdrive.app.camera;

import java.util.concurrent.atomic.AtomicLong;

/**
 * BYD100F only (#1001): the pending AVMCamera event 1008 as one atomic word, so the event
 * callback, the GL frame thread and the watchdog never act on a mix of two events.
 *
 * <p>The word packs the event time (elapsedRealtime, shifted left by one) with a "frames
 * resumed" bit; 0 means no event is pending. A new event always replaces the word; the frame
 * thread marks resumed and the watchdog clears only with compareAndSet on the exact word it
 * read, so a newer event published in between is never lost or marked resumed by mistake.
 */
public final class CarViewStreamState {

    private static final long RESUMED_BIT = 1L;

    private final AtomicLong word = new AtomicLong();

    /** Event callback: a new event replaces whatever was pending. */
    public void publishEvent(long nowMs) {
        word.set(pack(Math.max(1L, nowMs), false));
    }

    /** Current word; decode with {@link #eventMs(long)} and {@link #resumed(long)}. */
    public long read() {
        return word.get();
    }

    /**
     * Frame thread: mark this event's frames as back, if the frame is past the grace.
     *
     * @return true when this call set the resumed bit
     */
    public boolean onFrame(long nowMs) {
        long current = word.get();
        if (current == 0L || resumed(current)) return false;
        if (!CarViewStreamHold.frameEndsHold(eventMs(current), nowMs)) return false;
        return word.compareAndSet(current, current | RESUMED_BIT);
    }

    /** Watchdog: clear the event it decided on; a newer event stays pending. */
    public boolean clear(long seen) {
        return seen != 0L && word.compareAndSet(seen, 0L);
    }

    /** True while an event is pending and its frames have not come back. */
    public boolean pendingWithoutFrames() {
        long current = word.get();
        return current != 0L && !resumed(current);
    }

    static long pack(long eventMs, boolean resumed) {
        return (eventMs << 1) | (resumed ? RESUMED_BIT : 0L);
    }

    public static long eventMs(long word) {
        return word >>> 1;
    }

    public static boolean resumed(long word) {
        return (word & RESUMED_BIT) != 0L;
    }
}

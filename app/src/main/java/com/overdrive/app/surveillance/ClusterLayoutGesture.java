package com.overdrive.app.surveillance;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Reflected discrete input for a pinned Layout destination. Never replays through shell. */
public final class ClusterLayoutGesture {
    public interface Injector {
        boolean event(int action, long downTime, long eventTime, int x, int y, int displayId);
    }

    private ClusterLayoutGesture() {}

    public static boolean tap(int displayId, int x, int y, BooleanSupplier current,
                              LongSupplier clock, Injector injector) {
        return gesture(displayId, x, y, x, y, 60, false, current, clock, injector);
    }

    public static boolean swipe(int displayId, int x1, int y1, int x2, int y2, int durationMs,
                               BooleanSupplier current, LongSupplier clock, Injector injector) {
        return gesture(displayId, x1, y1, x2, y2, durationMs, true, current, clock, injector);
    }

    private static boolean gesture(int displayId, int x1, int y1, int x2, int y2, int durationMs,
                                   boolean move, BooleanSupplier current, LongSupplier clock,
                                   Injector injector) {
        if (displayId <= 0 || durationMs <= 0 || current == null || clock == null || injector == null)
            return false;
        long down = clock.getAsLong(), lastTime = down;
        int lastX = x1, lastY = y1;
        boolean pressed = false;
        try {
            if (!current.getAsBoolean() || !injector.event(0, down, down, x1, y1, displayId)) return false;
            pressed = true;
            if (move) {
                int steps = Math.max(1, Math.min(durationMs / 16, 64));
                for (int i = 1; i <= steps; i++) {
                    if (!current.getAsBoolean()) return false;
                    float fraction = (float) i / (steps + 1);
                    int x = Math.round(x1 + (x2 - x1) * fraction);
                    int y = Math.round(y1 + (y2 - y1) * fraction);
                    long time = down + (long) (durationMs * fraction);
                    lastTime = time;
                    if (!injector.event(2, down, time, x, y, displayId)) return false;
                    lastX = x; lastY = y;
                }
            }
            if (!current.getAsBoolean()) return false;
            lastTime = down + durationMs;
            if (!injector.event(1, down, lastTime, x2, y2, displayId)) return false;
            pressed = false;
            return true;
        } catch (RuntimeException failure) {
            return false;
        } finally {
            if (pressed) {
                // The caller holds the mirror's input lock. Slot teardown cannot release or
                // recycle this display until this cancellation has been attempted.
                try {
                    injector.event(3, down, Math.max(lastTime, clock.getAsLong()),
                            lastX, lastY, displayId);
                } catch (RuntimeException ignored) {}
            }
        }
    }
}

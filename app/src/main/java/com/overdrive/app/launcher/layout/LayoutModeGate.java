package com.overdrive.app.launcher.layout;

/** Short, cache-only admission fence. No lock is held while callers perform external work. */
public final class LayoutModeGate {
    private static long layoutOwner;
    private static int standardStarts;

    private LayoutModeGate() {}

    public static synchronized boolean reserveLayout(long generation) {
        if (generation <= 0 || layoutOwner != 0 || standardStarts != 0) return false;
        layoutOwner = generation;
        return true;
    }

    public static synchronized void releaseLayout(long generation) {
        if (layoutOwner == generation) layoutOwner = 0;
    }

    public static synchronized boolean transferLayout(long previous, long next) {
        if (previous <= 0 || next <= 0 || layoutOwner != previous) return false;
        layoutOwner = next;
        return true;
    }

    public static synchronized boolean layoutOwnsAdmission() {
        return layoutOwner != 0;
    }

    /** Camera overlays share the physical projection; the mirror joins only a committed layout. */
    public static synchronized boolean allowsSustainedToken(String token, boolean layoutActive) {
        return layoutOwner == 0 || "camview".equals(token)
                || (layoutActive && "viewmirror".equals(token))
                || (token != null && token.startsWith("layout:"));
    }

    public static synchronized StandardStart enterStandard() {
        if (layoutOwner != 0) return null;
        standardStarts++;
        return new StandardStart();
    }

    public static final class StandardStart implements AutoCloseable {
        private boolean closed;

        private StandardStart() {}

        @Override public void close() {
            synchronized (LayoutModeGate.class) {
                if (!closed) {
                    closed = true;
                    standardStarts--;
                }
            }
        }
    }
}

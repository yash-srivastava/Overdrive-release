package com.overdrive.app.remoteaccess;

import java.io.File;

/**
 * Opt-in "lock with the key inside": a phone departure lock without confirmed key absence may leave
 * the key in the cabin, so the key must not unlock the car until it is seen unlocked another way.
 * The latch survives a daemon restart through {@link Store}. No thread, timer or vehicle command.
 */
public final class KeyInsideLock {
    /** A consensus that passed a detected key counts for a lock dispatched this soon after it. */
    public static final long BYPASS_WINDOW_MS = 60_000L;
    /** An unlocked reading this soon after latching may predate the lock, so it does not clear the latch. */
    public static final long UNLOCKED_READING_GRACE_MS = 15_000L;

    public interface Store {
        boolean load();
        void save(boolean latched);
    }

    /** Presence of one marker file is the latch. */
    public static Store fileStore(String path) {
        return new Store() {
            @Override public boolean load() {
                try { return path != null && new File(path).exists(); } catch (Throwable unreadable) { return true; }
            }
            @Override public void save(boolean latched) {
                if (path == null) return;
                try {
                    File marker = new File(path);
                    if (latched) {
                        if (!marker.exists()) marker.createNewFile();
                    } else {
                        marker.delete();
                    }
                } catch (Throwable ignored) {
                    // The in-memory latch still holds for this process.
                }
            }
        };
    }

    private final Store store;
    private boolean latched;
    private long bypassAtMs = -1;
    private long latchedAtMs = -1;

    public KeyInsideLock(Store store) {
        this.store = store;
        this.latched = store != null && store.load();
    }

    /** Lock consensus allowed a phone departure without confirmed key absence. */
    public synchronized void noteKeyBypass(long now) {
        if (now >= 0) bypassAtMs = now;
    }

    /** A phone-departure lock was sent; returns true when it latched (or was already latched by it). */
    public synchronized boolean onPhoneLockDispatched(long now) {
        if (bypassAtMs < 0 || now < bypassAtMs || now - bypassAtMs > BYPASS_WINDOW_MS) return false;
        bypassAtMs = -1;
        if (!latched) {
            latched = true;
            latchedAtMs = now;
            if (store != null) store.save(true);
        }
        return true;
    }

    /** The key search confirmed the key absent: a pending bypass no longer means the key stays inside. */
    public synchronized void cancelBypass() {
        bypassAtMs = -1;
    }

    /** The car was seen unlocked; a pending bypass stays. True when this cleared the latch. */
    public synchronized boolean onCarSeenUnlocked(long now) {
        if (!latched || latchedAtMs >= 0 && now >= latchedAtMs && now - latchedAtMs < UNLOCKED_READING_GRACE_MS)
            return false;
        latched = false;
        latchedAtMs = -1;
        if (store != null) store.save(false);
        return true;
    }

    public synchronized boolean latched() {
        return latched;
    }

    /** An unlock was sent or the mode is off; true when it was latched. */
    public synchronized boolean clear() {
        bypassAtMs = -1;
        if (!latched) return false;
        latched = false;
        latchedAtMs = -1;
        if (store != null) store.save(false);
        return true;
    }
}

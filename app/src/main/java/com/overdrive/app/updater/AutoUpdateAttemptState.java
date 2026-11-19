package com.overdrive.app.updater;

import android.util.Log;

import com.overdrive.app.util.ScratchRoot;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Cross-daemon-restart admission latch for a single Braveheart APK asset. */
final class AutoUpdateAttemptState {
    private static final String TAG = "AutoUpdateAttempt";
    private static final File ATTEMPT = new File(ScratchRoot.path("overdrive_auto_update_attempt_braveheart"));
    private static final File BACKOFF = new File(ScratchRoot.path("overdrive_auto_update_check_backoff"));
    private static final long HOUR_MS = 60L * 60L * 1000L;

    private AutoUpdateAttemptState() {}

    // Asset digest is stable across daemon launches and changes on re-upload.
    static boolean isAttempted(String digest) {
        if (!validDigest(digest)) return true;
        String prior = read(ATTEMPT);
        // An unreadable or corrupt existing state is not permission to run destructively.
        if (ATTEMPT.exists() && (prior == null || !validDigest(prior))) return true;
        return digest.equals(prior);
    }

    /** Durable reservation before ANY daemon is killed. Failure means do not install. */
    static boolean reserve(String digest) {
        return validDigest(digest) && write(ATTEMPT, digest);
    }

    private static boolean validDigest(String digest) {
        return digest != null && digest.matches("sha256:[0-9a-f]{64}");
    }

    static boolean mayCheck() {
        String value = read(BACKOFF);
        if (value == null) return false;
        if (value.isEmpty()) return true;
        try {
            String[] parts = value.split(":", -1);
            long next = Long.parseLong(parts[1]);
            long now = System.currentTimeMillis();
            // A grossly future-dated record indicates clock skew: fail closed
            // for one current interval rather than remaining blocked forever.
            return now >= next || next - now > 24L * HOUR_MS;
        } catch (Exception ignored) { return false; }
    }

    static void checkFailed() {
        String old = read(BACKOFF);
        int n = 0;
        try {
            if (old != null && !old.isEmpty()) n = Integer.parseInt(old.split(":", -1)[0]);
        } catch (Exception ignored) { }
        n = Math.min(4, Math.max(0, n) + 1);
        long delay = Math.min(24L, 3L * (1L << (n - 1))) * HOUR_MS;
        if (!write(BACKOFF, n + ":" + (System.currentTimeMillis() + delay))) {
            Log.w(TAG, "Could not persist release-check backoff; skipping checks this session");
        }
    }

    static void checkSucceeded() { write(BACKOFF, ""); }

    private static String read(File file) {
        if (!file.exists()) return "";
        try (FileInputStream in = new FileInputStream(file)) {
            if (file.length() > 128) return null;
            byte[] data = new byte[(int) file.length()];
            if (in.read(data) != data.length) return null;
            return new String(data, StandardCharsets.UTF_8).trim();
        } catch (Exception e) { Log.w(TAG, "Cannot read " + file, e); return null; }
    }

    private static synchronized boolean write(File target, String contents) {
        File temp = new File(target.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(contents.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Exception e) {
            Log.w(TAG, "Cannot persist " + target, e);
            temp.delete();
            return false;
        }
        if (!temp.renameTo(target)) {
            Log.w(TAG, "Atomic state rename failed: " + target);
            temp.delete();
            return false;
        }
        return true;
    }
}

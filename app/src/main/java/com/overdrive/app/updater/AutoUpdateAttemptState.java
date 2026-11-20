package com.overdrive.app.updater;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.util.ScratchRoot;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Cross-daemon-restart admission latch for a single Braveheart APK asset. */
final class AutoUpdateAttemptState {
    private static final DaemonLogger logger = DaemonLogger.getInstance("CameraDaemon");
    private static final File ATTEMPT = new File(ScratchRoot.path("overdrive_auto_update_attempt_braveheart"));
    private static final File REJECTED = new File(ScratchRoot.path("overdrive_auto_update_rejected_braveheart"));
    private static final File BACKOFF = new File(ScratchRoot.path("overdrive_auto_update_check_backoff"));
    private static final long HOUR_MS = 60L * 60L * 1000L;
    private static volatile boolean persistenceFailed;

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
        return !persistenceFailed && validDigest(digest) && write(ATTEMPT, digest);
    }

    /** A staged APK that is deterministically incompatible must not be downloaded again. */
    static boolean isRejected(String digest) {
        if (!validDigest(digest)) return true;
        String prior = read(REJECTED);
        return (REJECTED.exists() && (prior == null || !validDigest(prior)))
                || digest.equals(prior);
    }

    /** Persist before reporting the rejection. If persistence fails, fail closed. */
    static void reject(String digest, String reason) {
        if (!validDigest(digest) || !write(REJECTED, digest)) {
            persistenceFailed = true;
            logger.warn("Auto-update: could not persist rejected APK digest; refusing retries");
        } else {
            logger.warn("Auto-update: rejected APK " + digest + ": " + reason);
        }
    }

    /** A failed detached spawn has not stopped anything: undo only our reservation. */
    static synchronized boolean releaseReservation(String digest) {
        if (!validDigest(digest) || !digest.equals(read(ATTEMPT))) return false;
        if (!ATTEMPT.delete()) {
            persistenceFailed = true;
            logger.warn("Auto-update: cannot release reservation after detached launch failure");
            return false;
        }
        logger.info("Auto-update: released reservation after detached launch failure");
        return true;
    }

    private static boolean validDigest(String digest) {
        return digest != null && digest.matches("sha256:[0-9a-f]{64}");
    }

    static boolean mayCheck() {
        if (persistenceFailed) return false;
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

    /** A gate that repeatedly flips during large downloads must not cause
     * another 135 MB transfer every 30 minutes. Preserve the same durable
     * consecutive-attempt count (and cap) as other pre-handoff failures. */
    static void downloadDeferred() {
        checkFailed();
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
            persistenceFailed = true;
            logger.warn("Auto-update: could not persist release-check backoff");
        }
    }

    /** A pending asset is not a success: preserve failures across download attempts. */
    static void checkSucceeded(boolean updatePending) {
        long delay = (updatePending ? 30L : 360L) * 60L * 1000L;
        int failures = 0;
        if (updatePending) {
            String old = read(BACKOFF);
            if (old == null) {
                persistenceFailed = true;
                logger.warn("Auto-update: cannot read retry count");
                return;
            }
            try {
                if (!old.isEmpty()) failures = Integer.parseInt(old.split(":", -1)[0]);
            } catch (Exception e) {
                persistenceFailed = true;
                logger.warn("Auto-update: corrupt retry count");
                return;
            }
        }
        if (!write(BACKOFF, failures + ":" + (System.currentTimeMillis() + delay))) {
            persistenceFailed = true;
            logger.warn("Auto-update: could not persist successful check interval");
        }
    }

    private static String read(File file) {
        if (!file.exists()) return "";
        try (FileInputStream in = new FileInputStream(file)) {
            if (file.length() > 128) return null;
            byte[] data = new byte[(int) file.length()];
            if (in.read(data) != data.length) return null;
            return new String(data, StandardCharsets.UTF_8).trim();
        } catch (Exception e) { logger.error("Auto-update: cannot read " + file, e); return null; }
    }

    private static synchronized boolean write(File target, String contents) {
        File temp = new File(target.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(contents.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Exception e) {
            logger.error("Auto-update: cannot persist " + target, e);
            temp.delete();
            return false;
        }
        if (!temp.renameTo(target)) {
            logger.warn("Auto-update: atomic state rename failed: " + target);
            temp.delete();
            return false;
        }
        return true;
    }
}

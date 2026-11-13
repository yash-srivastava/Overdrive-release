package com.overdrive.app.storage;

import java.sql.SQLException;
import java.util.Locale;

/**
 * Recognizes H2 socket-lock contention without touching H2's lock file.
 *
 * <p>With {@code FILE_LOCK=SOCKET}, a lock file's modification time does not
 * prove that its socket owner has exited. H2 itself checks the socket and
 * reclaims genuinely orphaned locks. Callers may retry an open after a short
 * delay, but must not delete {@code .lock.db} based on its age.
 */
public final class H2SocketLockRetry {
    // H2 ErrorCode.DATABASE_ALREADY_OPEN_1, independent of exception wording.
    private static final int DATABASE_ALREADY_OPEN = 90020;
    private static final int MAX_CAUSE_DEPTH = 16;

    private H2SocketLockRetry() {}

    /** True only for reported H2 lock contention, not corruption or unrelated I/O. */
    public static boolean isLockConflict(Throwable failure) {
        for (int i = 0; failure != null && i < MAX_CAUSE_DEPTH;
             i++, failure = failure.getCause()) {
            if (failure instanceof SQLException
                    && ((SQLException) failure).getErrorCode() == DATABASE_ALREADY_OPEN) {
                return true;
            }
            String message = failure.getMessage();
            if (message == null) continue;
            String lower = message.toLowerCase(Locale.ROOT);
            if (lower.contains("locked by another process")
                    || lower.contains("lock.db")
                    || lower.contains("already in use")) {
                return true;
            }
        }
        return false;
    }
}

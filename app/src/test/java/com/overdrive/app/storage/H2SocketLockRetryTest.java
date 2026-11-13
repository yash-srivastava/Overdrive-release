package com.overdrive.app.storage;

import org.junit.Test;

import java.io.IOException;
import java.sql.SQLException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Regression cases for bounded, non-destructive H2 socket-lock retries. */
public class H2SocketLockRetryTest {
    @Test public void recognizesH2DatabaseAlreadyOpenErrorCode() {
        assertTrue(H2SocketLockRetry.isLockConflict(
                new SQLException("localized error", "HY000", 90020)));
    }

    @Test public void recognizesLockMessageAndNestedCauses() {
        assertTrue(H2SocketLockRetry.isLockConflict(
                new IOException("could not open", new SQLException("Locked by another process"))));
        assertTrue(H2SocketLockRetry.isLockConflict(new IOException("DATABASE.LOCK.DB busy")));
        assertTrue(H2SocketLockRetry.isLockConflict(new IOException("Database ALREADY IN USE")));
    }

    @Test public void doesNotRetryCorruptionOrOtherFailuresAsLockContention() {
        assertFalse(H2SocketLockRetry.isLockConflict(null));
        assertFalse(H2SocketLockRetry.isLockConflict(new SQLException("File corrupted", "HY000", 90030)));
        assertFalse(H2SocketLockRetry.isLockConflict(new IOException("Permission denied")));
        assertFalse(H2SocketLockRetry.isLockConflict(new IOException("disk full")));
    }

    @Test public void recognizesLockErrorUnderUnrelatedWrapper() {
        Throwable wrapped = new IllegalStateException("opening store",
                new IOException("cannot connect", new SQLException("H2 file lock.db is held")));
        assertTrue(H2SocketLockRetry.isLockConflict(wrapped));
    }
}

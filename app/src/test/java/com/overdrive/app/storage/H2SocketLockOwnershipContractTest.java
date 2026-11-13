package com.overdrive.app.storage;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Prevent a regression to age-based deletion of an H2 SOCKET lock file. */
public class H2SocketLockOwnershipContractTest {
    private static final String BASE = "src/main/java/com/overdrive/app/";

    @Test public void daemonStoresLeaveSocketLockFilesToH2() throws Exception {
        String[] stores = {
                "monitor/SocHistoryDatabase.java",
                "trips/TripDatabase.java",
                "notifications/NotificationStore.java",
                "roadsense/store/RoadSenseStore.kt",
                "server/RecordingsIndex.java"
        };
        for (String store : stores) {
            String source = new String(Files.readAllBytes(Paths.get(BASE + store)),
                    StandardCharsets.UTF_8);
            assertTrue(store + " must use the shared policy",
                    source.contains("H2SocketLockRetry.isLockConflict("));
            assertFalse(store + " must not manually remove H2 locks",
                    source.contains("cleanupStaleLocks("));
        }
        String index = new String(Files.readAllBytes(Paths.get(BASE + "server/RecordingsIndex.java")),
                StandardCharsets.UTF_8);
        int start = index.indexOf("private void wipeCorruptStore()");
        int end = index.indexOf("private void createSchema()", start);
        assertTrue(start >= 0 && end > start);
        assertFalse(index.substring(start, end).contains("\".lock.db\""));
    }
}

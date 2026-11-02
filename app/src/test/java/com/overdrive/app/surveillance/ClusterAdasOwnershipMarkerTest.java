package com.overdrive.app.surveillance;

import static com.overdrive.app.surveillance.ClusterAdasLifecycle.Ownership.*;
import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ClusterAdasOwnershipMarkerTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private File file() { return new File(folder.getRoot(), "overlay.owner"); }

    @Test public void ownershipIsWrittenAheadAndOnlyClearedAfterKnownCompletion() {
        ClusterAdasOwnershipMarker m = new ClusterAdasOwnershipMarker(file());
        assertEquals(NONE, m.ownership("11111111-1111-1111-1111-111111111111"));
        assertTrue(m.acquire("11111111-1111-1111-1111-111111111111"));
        assertEquals(OWNED, m.ownership("11111111-1111-1111-1111-111111111111"));
        assertTrue(m.begin("11111111-1111-1111-1111-111111111111"));
        assertFalse(m.begin("11111111-1111-1111-1111-111111111111"));
        assertFalse(m.acquire("11111111-1111-1111-1111-111111111111"));
        assertFalse(m.clear());
        assertTrue(m.complete("11111111-1111-1111-1111-111111111111"));
        assertTrue(m.clear());
        assertEquals(NONE, m.ownership("11111111-1111-1111-1111-111111111111"));
        assertEquals(0, folder.getRoot().list().length);
    }

    @Test public void daemonRestartInSameBootDoesNotRecallUnknownTransaction() {
        File f = file();
        ClusterAdasOwnershipMarker first = new ClusterAdasOwnershipMarker(f);
        assertTrue(first.acquire("11111111-1111-1111-1111-111111111111")); assertTrue(first.begin("11111111-1111-1111-1111-111111111111"));
        ClusterAdasOwnershipMarker restarted = new ClusterAdasOwnershipMarker(f);
        assertEquals(UNCERTAIN, restarted.ownership("11111111-1111-1111-1111-111111111111"));
        assertTrue(restarted.read().pending);
        assertFalse(restarted.clear());
        assertFalse(restarted.complete("wrong-boot"));
    }

    @Test public void differentOsBootPermitsOwnedRecoveryWithoutForgettingMarker() {
        ClusterAdasOwnershipMarker m = new ClusterAdasOwnershipMarker(file());
        assertTrue(m.acquire("11111111-1111-1111-1111-111111111111")); assertTrue(m.begin("11111111-1111-1111-1111-111111111111"));
        assertEquals(OWNED, m.ownership("22222222-2222-2222-2222-222222222222"));
        assertFalse(m.read().pending);
        assertEquals("22222222-2222-2222-2222-222222222222", m.read().bootId);
        assertTrue(m.begin("22222222-2222-2222-2222-222222222222")); assertTrue(m.complete("22222222-2222-2222-2222-222222222222"));
        assertTrue(m.clear());
    }

    @Test public void malformedOrOversizedRecordsAreRetainedAndNeverOverwritten() throws Exception {
        for (String bad : new String[] {"", "partial\n", "x".repeat(300)}) {
            File f = file(); Files.write(f.toPath(), bad.getBytes(StandardCharsets.UTF_8));
            ClusterAdasOwnershipMarker m = new ClusterAdasOwnershipMarker(f);
            assertEquals(UNCERTAIN, m.ownership("11111111-1111-1111-1111-111111111111"));
            assertFalse(m.acquire("11111111-1111-1111-1111-111111111111")); assertFalse(m.begin("11111111-1111-1111-1111-111111111111"));
            assertFalse(m.clear());
            assertEquals(bad, new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        }
    }

    @Test public void unreadablePathAndInvalidBootIdPreventAcquisition() throws Exception {
        File parent = folder.newFile("file-parent");
        ClusterAdasOwnershipMarker m = new ClusterAdasOwnershipMarker(new File(parent, "overlay.owner"));
        assertFalse(m.acquire("11111111-1111-1111-1111-111111111111"));
        assertFalse(m.begin("11111111-1111-1111-1111-111111111111"));
        ClusterAdasOwnershipMarker validPath = new ClusterAdasOwnershipMarker(file());
        assertFalse(validPath.acquire("")); assertFalse(validPath.acquire("../boot"));
        assertEquals(UNCERTAIN, validPath.ownership(""));
        assertEquals(UNCERTAIN, validPath.ownership("unknown"));
        assertFalse(validPath.acquire("unknown"));
        assertFalse(file().exists());
    }

    @Test public void cleanOwnerSurvivesRestartAndUsesCurrentBootForNewCommand() {
        File f = file(); ClusterAdasOwnershipMarker first = new ClusterAdasOwnershipMarker(f);
        assertTrue(first.acquire("11111111-1111-1111-1111-111111111111"));
        ClusterAdasOwnershipMarker restarted = new ClusterAdasOwnershipMarker(f);
        assertEquals(OWNED, restarted.ownership("11111111-1111-1111-1111-111111111111"));
        assertTrue(restarted.begin("22222222-2222-2222-2222-222222222222"));
        assertTrue(restarted.complete("22222222-2222-2222-2222-222222222222"));
        assertEquals("22222222-2222-2222-2222-222222222222", restarted.read().bootId);
    }
}

package com.overdrive.app.genai;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GenAiStoreFilesTest {
    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void createsHomeAndRotatesPreviousUtf8Object() throws Exception {
        File home = new File(folder.getRoot(), "store");
        GenAiStoreFiles store = new GenAiStoreFiles(home, "insights.json");
        assertNull(store.load(1024));
        assertTrue(store.save(() -> new JSONObject().put("text", "Grüße"), 1024, false));
        assertTrue(store.save(() -> new JSONObject().put("text", "second"), 1024, false));
        assertEquals("second", store.load(1024).getString("text"));
        assertEquals("Grüße", GenAiStoreReader.read(
                new File(home, "insights.json.bak"), 1024).getString("text"));
        assertFalse(new File(home, "insights.json.tmp").exists());
    }

    @Test
    public void recoversBackupOnlyWhenPrimaryCannotBeRead() throws Exception {
        File home = folder.newFolder();
        GenAiStoreFiles store = new GenAiStoreFiles(home, "routine-learning.json");
        File backup = new File(home, "routine-learning.json.bak");
        Files.write(backup.toPath(), "{\"version\":1}".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, store.load(1024).getInt("version"));
        Files.write(store.file().toPath(), "not JSON".getBytes(StandardCharsets.UTF_8));
        assertEquals(1, store.load(1024).getInt("version"));
        Files.write(store.file().toPath(), "{\"version\":2}".getBytes(StandardCharsets.UTF_8));
        // Version checks belong to the caller, not to file recovery.
        assertEquals(2, store.load(1024).getInt("version"));
        Files.write(store.file().toPath(), new byte[1025]);
        assertEquals(1, store.load(1024).getInt("version"));
        assertNull(store.load(1));
    }

    @Test
    public void rejectedWriteLeavesPrimaryAndBackupIntactAndRemovesTemp() throws Exception {
        File home = folder.newFolder();
        GenAiStoreFiles store = new GenAiStoreFiles(home, "routine-learning.json");
        assertTrue(store.save(() -> new JSONObject().put("version", 1), 1024, true));
        JSONObject second = new JSONObject().put("version", 2);
        int size = second.toString().getBytes(StandardCharsets.UTF_8).length;
        assertTrue(store.save(() -> second, size, true));
        File tmp = new File(home, "routine-learning.json.tmp");
        File backup = new File(home, "routine-learning.json.bak");
        Files.write(tmp.toPath(), new byte[]{1});
        assertFalse(store.save(() -> second, size - 1, true));
        assertFalse(tmp.exists());
        Files.write(tmp.toPath(), new byte[]{1});
        assertFalse(store.save(() -> { throw new IOException("serialization failed"); }, 1024, true));
        assertFalse(tmp.exists());
        assertEquals(2, store.load(1024).getInt("version"));
        assertEquals(1, GenAiStoreReader.read(backup, 1024).getInt("version"));
    }

    @Test
    public void storesRemainIndependentAndRoutinePermissionsAreOwnerOnly() throws Exception {
        File home = folder.newFolder();
        GenAiStoreFiles insights = new GenAiStoreFiles(home, "insights.json");
        GenAiStoreFiles routines = new GenAiStoreFiles(home, "routine-learning.json");
        assertTrue(insights.save(() -> new JSONObject().put("items", 1), 1024, false));
        assertTrue(routines.save(() -> new JSONObject().put("version", 1), 1024, true));
        assertTrue(routines.save(() -> new JSONObject().put("version", 2), 1024, true));
        assertEquals(1, insights.load(1024).getInt("items"));
        assertEquals(2, routines.load(1024).getInt("version"));
        File backup = new File(home, "routine-learning.json.bak");
        try {
            EnumSet<PosixFilePermission> ownerOnly = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            assertEquals(ownerOnly, Files.getPosixFilePermissions(routines.file().toPath()));
            assertEquals(ownerOnly, Files.getPosixFilePermissions(backup.toPath()));
        } catch (UnsupportedOperationException ignored) {
            assertTrue(routines.file().canRead());
            assertTrue(routines.file().canWrite());
            assertTrue(backup.canRead());
            assertTrue(backup.canWrite());
        }
    }
}

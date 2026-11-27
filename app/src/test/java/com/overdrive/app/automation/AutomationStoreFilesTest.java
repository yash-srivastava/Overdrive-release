package com.overdrive.app.automation;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class AutomationStoreFilesTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void backupRunsAfterScratchWriteAndBeforePromotion() throws Exception {
        File live = folder.newFile("live.json");
        File scratch = new File(folder.getRoot(), "live.tmp");
        File backup = new File(folder.getRoot(), "live.bak");
        byte[] oldBytes = "{\"name\":\"old\"}".getBytes(StandardCharsets.UTF_8);
        byte[] newBytes = new JSONObject().put("name", "caf\u00e9").toString()
                .getBytes(StandardCharsets.UTF_8);
        Files.write(live.toPath(), oldBytes);
        List<String> errors = new ArrayList<>();

        assertTrue(AutomationStoreFiles.save(scratch, live, backup, newBytes, file -> {
            assertTrue(scratch.isFile());
            assertEquals(live, file);
            return AutomationStoreFiles.isParseable(file, "action-group", errors::add);
        }, "test", errors::add));

        assertTrue(errors.isEmpty());
        assertArrayEquals(oldBytes, Files.readAllBytes(backup.toPath()));
        assertArrayEquals(newBytes, Files.readAllBytes(live.toPath()));
        assertEquals("caf\u00e9", AutomationStoreFiles.readObject(live).getString("name"));
        assertFalse(scratch.exists());
    }

    @Test
    public void scratchFailureDoesNotRunBackupOrChangeLiveFile() throws Exception {
        File live = folder.newFile("live.json");
        byte[] oldBytes = {1, 2, 3};
        Files.write(live.toPath(), oldBytes);
        File scratch = new File(folder.getRoot(), "missing/unused.tmp");
        List<String> errors = new ArrayList<>();

        File backup = new File(folder.getRoot(), "live.bak");
        assertFalse(AutomationStoreFiles.save(scratch, live, backup, new byte[]{4},
                file -> { fail("backup must not run"); return false; }, "test", errors::add));
        assertArrayEquals(oldBytes, Files.readAllBytes(live.toPath()));
        assertEquals(java.util.Collections.singletonList("Failed to write test scratch file"), errors);
    }

    @Test
    public void failedPromotionReportsFailureAndLeavesScratch() throws Exception {
        File live = folder.newFolder("live");
        Files.write(new File(live, "child").toPath(), new byte[]{1});
        File scratch = new File(folder.getRoot(), "live.tmp");
        List<String> errors = new ArrayList<>();
        byte[] bytes = {4, 5};

        File backup = new File(folder.getRoot(), "live.bak");
        assertFalse(AutomationStoreFiles.save(scratch, live, backup, bytes,
                file -> false, "test", errors::add));
        assertArrayEquals(bytes, Files.readAllBytes(scratch.toPath()));
        assertTrue(live.isDirectory());
        assertEquals(java.util.Collections.singletonList("Failed to promote test scratch file"), errors);
    }

    @Test
    public void rejectedBackupLeavesLastKnownGoodFileUntouched() throws Exception {
        File live = folder.newFile("live.json");
        File scratch = new File(folder.getRoot(), "live.tmp");
        File backup = folder.newFile("live.bak");
        byte[] oldBackup = {1, 2};
        Files.write(backup.toPath(), oldBackup);
        Files.write(live.toPath(), new byte[]{3});
        List<String> errors = new ArrayList<>();
        byte[] bytes = {4, 5};

        assertTrue(AutomationStoreFiles.save(scratch, live, backup, bytes, file -> false,
                "test", errors::add));
        assertArrayEquals(oldBackup, Files.readAllBytes(backup.toPath()));
        assertArrayEquals(bytes, Files.readAllBytes(live.toPath()));
        assertTrue(errors.isEmpty());
    }

    @Test
    public void corruptLiveJsonDoesNotReplaceValidBackup() throws Exception {
        File live = folder.newFile("live.json");
        File scratch = new File(folder.getRoot(), "live.tmp");
        File backup = folder.newFile("live.bak");
        byte[] oldBackup = "{\"group\":{}}".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
        Files.write(backup.toPath(), oldBackup);
        Files.write(live.toPath(), "{".getBytes(StandardCharsets.UTF_8));
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        assertTrue(AutomationStoreFiles.save(scratch, live, backup, bytes,
                file -> AutomationStoreFiles.isParseable(file, "action-group", warnings::add),
                "action-groups", errors::add));
        assertArrayEquals(oldBackup, Files.readAllBytes(backup.toPath()));
        assertArrayEquals(bytes, Files.readAllBytes(live.toPath()));
        assertEquals(java.util.Collections.singletonList(
                "Live action-group config is unparseable — keeping the existing backup"), warnings);
        assertTrue(errors.isEmpty());
    }

    @Test
    public void backupFailureIsBestEffortAndDoesNotPreventPromotion() throws Exception {
        File live = folder.newFile("live.json");
        File scratch = new File(folder.getRoot(), "live.tmp");
        File backup = folder.newFolder("backup");
        List<String> errors = new ArrayList<>();
        byte[] bytes = {6, 7};

        assertTrue(AutomationStoreFiles.save(scratch, live, backup, bytes, file -> true,
                "test", errors::add));
        assertArrayEquals(bytes, Files.readAllBytes(live.toPath()));
        assertEquals(java.util.Collections.singletonList("Failed to back up test"), errors);
    }
}

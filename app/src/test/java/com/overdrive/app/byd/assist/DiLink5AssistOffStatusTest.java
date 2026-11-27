package com.overdrive.app.byd.assist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.overdrive.app.byd.assist.DiLink5AssistOffGate.Control;
import com.overdrive.app.byd.assist.DiLink5AssistOffStatus.State;

import java.io.File;
import java.nio.file.Files;

import org.json.JSONObject;
import org.junit.Test;

public class DiLink5AssistOffStatusTest {

    private static final int NA = DiLink5AssistOffControls.READ_UNAVAILABLE;

    @Test
    public void enabledControlsStartPendingDisabledOnesStayDisabled() throws Exception {
        DiLink5AssistOffStatus status = new DiLink5AssistOffStatus(
                7L, 3L, "ACC ON", "sealion7", 1_000L,
                new DiLink5AssistOffProfile(true, false, true, true));
        assertEquals(State.PENDING, status.stateOf(Control.CPD));
        assertEquals(State.DISABLED, status.stateOf(Control.LDA));
        assertEquals(State.PENDING, status.stateOf(Control.ELKA));
        assertEquals(State.PENDING, status.stateOf(Control.DMS));
        assertFalse(status.isReadinessObserved());

        JSONObject json = status.toJson();
        assertEquals(7L, json.getLong("cycleGeneration"));
        assertEquals(3L, json.getLong("accGeneration"));
        assertEquals("ACC ON", json.getString("trigger"));
        assertEquals(1_000L, json.getLong("startedAt"));
        assertEquals("sealion7", json.getString("modelId"));
        assertEquals("", json.getString("skipReason"));
        assertEquals(0L, json.getLong("finishedAt"));
        JSONObject controls = json.getJSONObject("controls");
        for (String key : new String[] {"cpd", "lda", "elka"}) {
            JSONObject c = controls.getJSONObject(key);
            for (String field : new String[] {
                    "state", "attempts", "lastAttemptAt", "before", "after", "detail"}) {
                assertTrue(key + "." + field, c.has(field));
            }
        }
        // DMS has no readback, so its entry carries no before/after at all.
        JSONObject dms = controls.getJSONObject("dms");
        assertTrue(dms.has("state"));
        assertTrue(dms.has("attempts"));
        assertTrue(dms.has("lastAttemptAt"));
        assertTrue(dms.has("detail"));
        assertFalse(dms.has("before"));
        assertFalse(dms.has("after"));
        assertEquals("pending", controls.getJSONObject("cpd").getString("state"));
        assertEquals("disabled", controls.getJSONObject("lda").getString("state"));
        // Unavailable readbacks serialize as null, not as the sentinel integer.
        assertTrue(controls.getJSONObject("cpd").isNull("before"));
    }

    @Test
    public void skipEndsPendingControlsAndFailsUnconfirmedWrites() throws Exception {
        DiLink5AssistOffStatus status = new DiLink5AssistOffStatus(
                1L, 1L, "ACC ON", "sealion7", 0L, new DiLink5AssistOffProfile(true, true, true, true));
        status.readinessObserved(5L);
        assertTrue(status.isReadinessObserved());
        status.update(Control.CPD, State.VERIFIED, 1, 10L, 1, 2, "readback confirmed off");
        status.update(Control.LDA, State.APPLIED, 1, 11L, 3, NA, "awaiting readback");
        status.update(Control.DMS, State.REQUEST_SENT, 1, 12L, NA, NA, "request sent");
        status.skip("moving (12.0 km/h)");
        // Terminal states keep their verdict; a never-attempted control is skipped; a control whose
        // write went out but was never confirmed is failed (the write did happen).
        assertEquals(State.VERIFIED, status.stateOf(Control.CPD));
        assertEquals(State.FAILED, status.stateOf(Control.LDA));
        assertEquals(State.SKIPPED, status.stateOf(Control.ELKA));
        assertEquals(State.REQUEST_SENT, status.stateOf(Control.DMS));
        assertEquals("moving (12.0 km/h)", status.skipReason());
        JSONObject json = status.toJson();
        assertEquals("moving (12.0 km/h)", json.getString("skipReason"));
        assertEquals(5L, json.getLong("readinessObservedAt"));
        JSONObject cpd = json.getJSONObject("controls").getJSONObject("cpd");
        assertEquals(1, cpd.getInt("before"));
        assertEquals(2, cpd.getInt("after"));
        assertEquals("verified", cpd.getString("state"));
        JSONObject lda = json.getJSONObject("controls").getJSONObject("lda");
        assertEquals("failed", lda.getString("state"));
        assertTrue(lda.getString("detail").startsWith("not confirmed; "));
        assertEquals("moving (12.0 km/h)",
                json.getJSONObject("controls").getJSONObject("elka").getString("detail"));
        String summary = status.summary();
        assertTrue(summary, summary.contains("cpd=verified(1)"));
        assertTrue(summary, summary.contains("lda=failed(1)"));
        assertTrue(summary, summary.contains("elka=skipped"));
        assertTrue(summary, summary.contains("dms=request_sent(1)"));
        assertTrue(summary, summary.contains("skip=\"moving (12.0 km/h)\""));
    }

    @Test
    public void dmsCanNeverBeReportedVerifiedOrApplied() {
        DiLink5AssistOffStatus status = new DiLink5AssistOffStatus(
                1L, 1L, "ACC ON", "sealion7", 0L, new DiLink5AssistOffProfile(false, false, false, true));
        for (State forbidden : new State[] {State.VERIFIED, State.APPLIED}) {
            try {
                status.update(Control.DMS, forbidden, 1, 1L, NA, NA, "x");
                fail("DMS accepted " + forbidden);
            } catch (IllegalArgumentException expected) {
                assertEquals(State.PENDING, status.stateOf(Control.DMS));
            }
        }
        status.update(Control.DMS, State.REQUEST_SENT, 1, 1L, NA, NA, "sent");
        assertEquals(State.REQUEST_SENT, status.stateOf(Control.DMS));
        // The other controls may of course be verified.
        status.update(Control.CPD, State.VERIFIED, 1, 1L, 1, 2, "ok");
    }

    @Test
    public void stateVocabularyMatchesTheApiContract() {
        assertEquals("disabled", State.DISABLED.json);
        assertEquals("pending", State.PENDING.json);
        assertEquals("applied", State.APPLIED.json);
        assertEquals("verified", State.VERIFIED.json);
        assertEquals("failed", State.FAILED.json);
        assertEquals("request_sent", State.REQUEST_SENT.json);
        assertEquals("skipped", State.SKIPPED.json);
    }

    @Test
    public void writeIsAtomicAndReadBackWithinTheSizeCap() throws Exception {
        File dir = Files.createTempDirectory("assist-off-status").toFile();
        try {
            File target = DiLink5AssistOffStatus.statusFile(dir);
            assertEquals(DiLink5AssistOffStatus.FILE_NAME, target.getName());
            DiLink5AssistOffStatus status = new DiLink5AssistOffStatus(
                    2L, 9L, "producer start", "sealion7", 42L,
                    new DiLink5AssistOffProfile(false, true, false, false));
            assertTrue(DiLink5AssistOffStatus.write(target, status.toJson()));
            assertTrue(target.isFile());
            // No tmp file left behind.
            assertEquals(1, dir.list().length);
            JSONObject read = DiLink5AssistOffStatus.read(target, 64L * 1024L);
            assertNotNull(read);
            assertEquals(2L, read.getLong("cycleGeneration"));
            assertEquals("producer start", read.getString("trigger"));
            assertEquals("pending",
                    read.getJSONObject("controls").getJSONObject("lda").getString("state"));
            // Overwrite replaces, cap rejects, missing/garbage return null.
            status.finished(100L);
            assertTrue(DiLink5AssistOffStatus.write(target, status.toJson()));
            assertEquals(100L, DiLink5AssistOffStatus.read(target, 64L * 1024L)
                    .getLong("finishedAt"));
            assertNull(DiLink5AssistOffStatus.read(target, 10L));
            assertNull(DiLink5AssistOffStatus.read(new File(dir, "absent"), 64L * 1024L));
            File garbage = new File(dir, "garbage");
            Files.write(garbage.toPath(), "not json".getBytes("UTF-8"));
            assertNull(DiLink5AssistOffStatus.read(garbage, 64L * 1024L));
            assertNull(DiLink5AssistOffStatus.statusFile(null));
            assertFalse(DiLink5AssistOffStatus.write(null, new JSONObject()));
        } finally {
            for (File f : dir.listFiles()) f.delete();
            dir.delete();
        }
    }

    @Test
    public void daemonCandidatesPointAtTheAppExternalFilesDirectory() {
        File[] candidates = DiLink5AssistOffStatus.daemonReadCandidates();
        assertEquals(2, candidates.length);
        for (File c : candidates) {
            // File uses the host separator; these assertions describe Android paths.
            String path = c.getPath().replace(File.separatorChar, '/');
            assertTrue(path, path.contains("/Android/data/"));
            assertTrue(path, path.endsWith("/files/" + DiLink5AssistOffStatus.FILE_NAME));
        }
        assertTrue(candidates[0].getPath().replace(File.separatorChar, '/')
                .startsWith("/storage/emulated/0/"));
        assertTrue(candidates[1].getPath().replace(File.separatorChar, '/')
                .startsWith("/sdcard/"));
    }
}

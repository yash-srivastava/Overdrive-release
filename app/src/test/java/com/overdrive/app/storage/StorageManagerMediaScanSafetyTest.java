package com.overdrive.app.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Regression tests for serialized/bounded MediaScanner indexing in StorageManager. */
public class StorageManagerMediaScanSafetyTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @After
    public void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    public void boundedScanCommandDrainsOutputAndKillsHungProcess() {
        FakeProcess ok = FakeProcess.completed("Broadcast completed: result=0\n");
        assertTrue(StorageManager.runBoundedScanCommand(cmd -> ok, new String[]{"am"}, 1_000L, "ok"));
        assertFalse(ok.destroyed);

        FakeProcess hung = FakeProcess.hung();
        long t0 = System.currentTimeMillis();
        assertFalse(StorageManager.runBoundedScanCommand(cmd -> hung, new String[]{"am"}, 100L, "hung"));
        long elapsed = System.currentTimeMillis() - t0;
        assertTrue(hung.destroyed);
        assertTrue("Expected fast timeout under 2s, was " + elapsed + "ms", elapsed < 2_000L);

        FakeProcess interrupted = FakeProcess.interrupted();
        assertFalse(StorageManager.runBoundedScanCommand(
                cmd -> interrupted, new String[]{"am"}, 1_000L, "int"));
        assertTrue(interrupted.destroyed);
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    public void broadcastFileApi30UsesSingleBoundedAmBroadcastOnly() throws IOException {
        File mp4 = tempFolder.newFile("cam_test.mp4");
        List<List<String>> invocations = new ArrayList<>();

        boolean ok = StorageManager.broadcastFile(
                mp4,
                30,
                cmd -> {
                    invocations.add(Arrays.asList(cmd));
                    return FakeProcess.completed("");
                });

        assertTrue(ok);
        assertEquals(1, invocations.size());
        assertEquals("am", invocations.get(0).get(0));
        assertEquals("broadcast", invocations.get(0).get(1));
        assertTrue(invocations.get(0).contains("android.intent.action.MEDIA_SCANNER_SCAN_FILE"));
    }

    @Test
    public void broadcastFileApi29RunsSerializedBroadcastThenContentInsert() throws IOException {
        File mp4 = tempFolder.newFile("cam_api29.mp4");
        List<List<String>> invocations = new ArrayList<>();
        AtomicInteger inFlight = new AtomicInteger(0);
        AtomicInteger maxInFlight = new AtomicInteger(0);

        boolean ok = StorageManager.broadcastFile(
                mp4,
                29,
                cmd -> {
                    int now = inFlight.incrementAndGet();
                    maxInFlight.accumulateAndGet(now, Math::max);
                    invocations.add(Arrays.asList(cmd));
                    return FakeProcess.onWaitRelease(inFlight);
                });

        assertTrue(ok);
        assertEquals(1, maxInFlight.get());
        assertEquals(2, invocations.size());
        assertEquals("am", invocations.get(0).get(0));
        assertEquals("content", invocations.get(1).get(0));

        // If am broadcast times out, do not spawn content insert.
        invocations.clear();
        boolean timedOut = StorageManager.broadcastFile(
                mp4,
                29,
                cmd -> {
                    invocations.add(Arrays.asList(cmd));
                    return FakeProcess.hung();
                });
        assertFalse(timedOut);
        assertEquals(1, invocations.size());
        assertEquals("am", invocations.get(0).get(0));
    }

    @Test
    public void selectStartupMediaScanBatchCapsMissingMarkerAndSortsOldestToNewest()
            throws IOException {
        File recDir = tempFolder.newFolder("recordings");
        File survDir = tempFolder.newFolder("surveillance");
        long now = 1_795_200_000_000L;
        long oneHour = 60L * 60L * 1000L;

        // 5 stale clips older than the 24h missing-marker window
        for (int i = 0; i < 5; i++) {
            File stale = new File(recDir, "stale_" + i + ".mp4");
            assertTrue(stale.createNewFile());
            assertTrue(stale.setLastModified(now - (48L * oneHour) + (i * 1000L)));
        }

        // 70 recent clips split across recordings and surveillance
        for (int i = 0; i < 70; i++) {
            File targetDir = (i % 2 == 0) ? recDir : survDir;
            File recent = new File(targetDir, String.format("clip_%02d.mp4", i));
            assertTrue(recent.createNewFile());
            assertTrue(recent.setLastModified(now - ((70L - i) * 10_000L)));
        }

        List<File> batch = StorageManager.selectStartupMediaScanBatch(
                Arrays.asList(recDir, survDir),
                0L,
                now,
                50,
                24L * oneHour);

        assertEquals(50, batch.size());
        // Picks newest 50 (clip_20..clip_69), ordered oldest-to-newest.
        assertEquals("clip_20.mp4", batch.get(0).getName());
        assertEquals("clip_69.mp4", batch.get(49).getName());
        for (int i = 1; i < batch.size(); i++) {
            assertTrue(batch.get(i - 1).lastModified() <= batch.get(i).lastModified());
        }

        // With an explicit recent watermark, only newer clips are selected.
        long watermark = now - (10L * 10_000L);
        List<File> incremental = StorageManager.selectStartupMediaScanBatch(
                Arrays.asList(recDir, survDir),
                watermark,
                now,
                50,
                24L * oneHour);
        assertEquals(9, incremental.size());
        assertEquals("clip_61.mp4", incremental.get(0).getName());
        assertEquals("clip_69.mp4", incremental.get(8).getName());
    }

    @Test
    public void startupBatchCheckpointsPeriodicallyAndAbortsOnConsecutiveTimeouts()
            throws IOException {
        File dir = tempFolder.newFolder("batch");
        List<File> files = new ArrayList<>();
        long base = 1_795_200_000_000L;
        for (int i = 0; i < 12; i++) {
            File f = new File(dir, String.format("vid_%02d.mp4", i));
            assertTrue(f.createNewFile());
            assertTrue(f.setLastModified(base + (i * 1_000L)));
            files.add(f);
        }

        List<Long> checkpoints = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger(0);
        int result = StorageManager.broadcastStartupBatch(
                files,
                file -> {
                    int idx = calls.getAndIncrement();
                    return idx != 5 && idx != 6;
                },
                checkpoints::add,
                0L,
                2);

        assertEquals(-1, result);
        // Stopped immediately after 2 consecutive failures at index 5 & 6.
        assertEquals(7, calls.get());
        // Checkpoint recorded at the 5th successful scan (vid_04).
        assertEquals(1, checkpoints.size());
        assertEquals(Long.valueOf(base + 4_000L), checkpoints.get(0));
    }

    @Test
    public void watermarkWriteIsStrictlyMonotonic() throws IOException {
        File marker = new File(tempFolder.getRoot(), "overdrive_last_mediascan");

        assertTrue(StorageManager.writeTimestampMarkerIfNewer(marker, 2_000L));
        assertEquals(2_000L, StorageManager.readTimestampMarker(marker));

        assertFalse(StorageManager.writeTimestampMarkerIfNewer(marker, 1_500L));
        assertEquals(2_000L, StorageManager.readTimestampMarker(marker));

        assertTrue(StorageManager.writeTimestampMarkerIfNewer(marker, 2_500L));
        assertEquals(2_500L, StorageManager.readTimestampMarker(marker));
    }

    private static final class FakeProcess extends Process {
        private final byte[] stdout;
        private final boolean complete;
        private final boolean interruptOnWait;
        private final AtomicInteger releaseCounter;
        boolean destroyed;

        private FakeProcess(
                String stdout,
                boolean complete,
                boolean interruptOnWait,
                AtomicInteger releaseCounter) {
            this.stdout = stdout.getBytes(StandardCharsets.UTF_8);
            this.complete = complete;
            this.interruptOnWait = interruptOnWait;
            this.releaseCounter = releaseCounter;
        }

        static FakeProcess completed(String stdout) {
            return new FakeProcess(stdout, true, false, null);
        }

        static FakeProcess onWaitRelease(AtomicInteger inFlight) {
            return new FakeProcess("", true, false, inFlight);
        }

        static FakeProcess hung() {
            return new FakeProcess("", false, false, null);
        }

        static FakeProcess interrupted() {
            return new FakeProcess("", false, true, null);
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            if (releaseCounter != null) {
                releaseCounter.decrementAndGet();
            }
            if (interruptOnWait) throw new InterruptedException("test interrupt");
            return complete || destroyed;
        }

        @Override
        public int waitFor() throws InterruptedException {
            if (interruptOnWait) throw new InterruptedException("test interrupt");
            return 0;
        }

        @Override
        public int exitValue() {
            if (!complete && !destroyed) throw new IllegalThreadStateException("still running");
            return destroyed ? -1 : 0;
        }

        @Override
        public void destroy() {
            destroyed = true;
        }

        @Override
        public Process destroyForcibly() {
            destroyed = true;
            return this;
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(stdout);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }
    }
}

package com.overdrive.app.safekeep.dest;

import com.overdrive.app.safekeep.util.StreamDigests;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;

import okhttp3.Call;
import okio.Buffer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class UploadJobTest {
    private static final byte[] DATA = new byte[140_000];
    private static final int OFFSET = 17;
    private static final int LENGTH = 130_000;

    static {
        for (int i = 0; i < DATA.length; i++) DATA[i] = (byte) (i % 251);
    }

    @Test
    public void httpBodyStreamsOnlyRequestedRangeAndRefusesReplay() throws Exception {
        RecordingHooks hooks = new RecordingHooks();
        StreamDigests digests = new StreamDigests(true, true, false);
        UploadJob.ChunkBody body = job(hooks, digests).body(OFFSET, LENGTH, null);
        Buffer sink = new Buffer();
        assertEquals(LENGTH, body.contentLength());
        assertEquals("application/octet-stream", body.contentType().toString());
        assertTrue(body.isOneShot());
        body.writeTo(sink);

        byte[] expected = Arrays.copyOfRange(DATA, OFFSET, OFFSET + LENGTH);
        assertArrayEquals(expected, sink.readByteArray());
        assertEquals(LENGTH, body.sent());
        assertEquals(hexDigest("MD5", expected), body.md5Hex());
        assertAccounting(hooks, digests, expected);

        try {
            body.writeTo(sink);
            fail("A one-shot body must not be replayed");
        } catch (IOException expectedError) {
            assertTrue(expectedError.getMessage().contains("replay refused"));
        }
        assertEquals(0L, sink.size());
        assertAccounting(hooks, digests, expected);
    }

    @Test
    public void nonHttpCopyStreamsOnlyRequestedRange() throws Exception {
        RecordingHooks hooks = new RecordingHooks();
        StreamDigests digests = new StreamDigests(true, true, false);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(LENGTH, job(hooks, digests).copyChunk(OFFSET, LENGTH, out));
        byte[] expected = Arrays.copyOfRange(DATA, OFFSET, OFFSET + LENGTH);
        assertArrayEquals(expected, out.toByteArray());
        assertAccounting(hooks, digests, expected);
    }

    @Test
    public void explicitRetryRestoresWholeObjectDigestWithoutDoubleCounting() throws Exception {
        RecordingHooks hooks = new RecordingHooks();
        StreamDigests digests = new StreamDigests(true, true, false);
        UploadJob job = job(hooks, digests);
        job.body(0, OFFSET, null).writeTo(new Buffer());
        job.markChunkStartForCore();
        job.body(OFFSET, LENGTH, null).writeTo(new Buffer());

        job.rewindChunk();
        assertEquals(OFFSET, digests.fedBytes());
        Buffer retry = new Buffer();
        job.body(OFFSET, LENGTH, null).writeTo(retry);

        assertArrayEquals(Arrays.copyOfRange(DATA, OFFSET, OFFSET + LENGTH), retry.readByteArray());
        byte[] expected = Arrays.copyOf(DATA, OFFSET + LENGTH);
        assertEquals(expected.length, digests.fedBytes());
        assertEquals(hexDigest("MD5", expected), digests.md5Hex());
        assertEquals(hexDigest("SHA-256", expected), digests.sha256Hex());
        assertEquals(OFFSET + 2L * LENGTH, hooks.sent);
        assertEquals(hooks.sent, hooks.paced);
    }

    @Test
    public void prematureEofIsReportedByBothStreamingPaths() throws Exception {
        for (boolean http : new boolean[]{true, false}) {
            RecordingHooks hooks = new RecordingHooks();
            StreamDigests digests = new StreamDigests(true, true, false);
            UploadJob job = job(hooks, digests);
            try {
                if (http) job.body(DATA.length - 3, 5, null).writeTo(new Buffer());
                else job.copyChunk(DATA.length - 3, 5, new ByteArrayOutputStream());
                fail("A short source must not report a successful upload");
            } catch (IOException expectedError) {
                assertTrue(expectedError.getMessage().contains("source ended early"));
            }
            assertAccounting(hooks, digests, Arrays.copyOfRange(DATA, DATA.length - 3, DATA.length));
        }
    }

    @Test
    public void cancellationDuringPacingDoesNotWriteOrHashBytes() throws Exception {
        for (boolean http : new boolean[]{true, false}) {
            RecordingHooks hooks = new RecordingHooks();
            hooks.cancelOnPace = true;
            StreamDigests digests = new StreamDigests(true, true, false);
            UploadJob job = job(hooks, digests);
            Buffer sink = new Buffer();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try {
                if (http) job.body(OFFSET, LENGTH, null).writeTo(sink);
                else job.copyChunk(OFFSET, LENGTH, out);
                fail("Cancellation must stop the transfer");
            } catch (IOException expectedError) {
                assertEquals("cancelled", expectedError.getMessage());
            }
            assertTrue(hooks.paced > 0);
            assertEquals(0L, sink.size());
            assertEquals(0, out.size());
            assertEquals(0L, hooks.sent);
            assertEquals(0L, digests.fedBytes());
            assertEquals(hexDigest("SHA-256", new byte[0]), digests.sha256Hex());
        }
    }

    private static UploadJob job(RecordingHooks hooks, StreamDigests digests) {
        return new UploadJob(1, "backup.bin", "test", DATA.length, null, null,
                offset -> new ByteArrayInputStream(DATA, (int) offset, DATA.length - (int) offset),
                hooks, digests);
    }

    private static void assertAccounting(RecordingHooks hooks, StreamDigests digests,
                                         byte[] expected) throws Exception {
        assertEquals(expected.length, hooks.paced);
        assertEquals(expected.length, hooks.sent);
        assertEquals(expected.length, digests.fedBytes());
        assertEquals(hexDigest("MD5", expected), digests.md5Hex());
        assertEquals(hexDigest("SHA-256", expected), digests.sha256Hex());
    }

    private static String hexDigest(String algorithm, byte[] bytes) throws Exception {
        byte[] hash = MessageDigest.getInstance(algorithm).digest(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return hex.toString();
    }

    private static final class RecordingHooks implements UploadJob.Hooks {
        long paced;
        long sent;
        boolean cancelOnPace;
        boolean cancelled;

        @Override public void persist(JSONObject state, long confirmed) {}
        @Override public void pace(int bytes) {
            paced += bytes;
            if (cancelOnPace) cancelled = true;
        }
        @Override public boolean cancelled() { return cancelled; }
        @Override public void onCall(Call call) {}
        @Override public void onBytes(int bytes) { sent += bytes; }
    }
}

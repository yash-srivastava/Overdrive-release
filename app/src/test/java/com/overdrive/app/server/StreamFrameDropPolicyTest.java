package com.overdrive.app.server;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * Keyframe-aware congestion policy of the single-port /ws H.264 sender.
 *
 * The invariant under test: a viewer never receives a P-frame whose
 * reference picture was dropped. Dropping is always "clear the backlog, then
 * wait for the next IDR", SPS/PPS is never lost, and the retirement sentinel
 * survives any clear.
 */
public class StreamFrameDropPolicyTest {

    private static final byte[] SPS_PPS = {0, 0, 0, 1, 0x67, 0x42, 0, 0, 0, 1, 0x68, (byte) 0xCE};

    private long nowMs = 1_000_000L;   // monotonic fake clock

    private StreamFrameDropPolicy policy(int capacity) {
        return new StreamFrameDropPolicy(capacity, StreamFrameDropPolicy.MAX_QUEUE_AGE_MS, () -> nowMs);
    }

    private static byte[] p(int n) { return new byte[]{0, 0, 0, 1, 0x41, (byte) n}; }
    private static byte[] idr(int n) { return new byte[]{0, 0, 0, 1, 0x65, (byte) n}; }

    private static boolean offerVideo(StreamFrameDropPolicy q, byte[] bytes, boolean key, long pts) {
        return q.offerVideo(ByteBuffer.wrap(bytes), 0, bytes.length, key, pts);
    }

    private static List<StreamFrameDropPolicy.Frame> drain(StreamFrameDropPolicy q) throws Exception {
        List<StreamFrameDropPolicy.Frame> out = new ArrayList<>();
        StreamFrameDropPolicy.Frame f;
        while ((f = q.poll(0, TimeUnit.MILLISECONDS)) != null) out.add(f);
        return out;
    }

    @Test
    public void pooledFramesKeepExactLengthAndDoNotAliasInFlightOrSource() throws Exception {
        StreamFrameDropPolicy q = policy(4);
        ByteBuffer source = ByteBuffer.allocateDirect(7000);
        byte[] first = new byte[6000];
        Arrays.fill(first, (byte) 0x5a);
        source.position(17);
        source.put(first);
        source.limit(6500);
        source.position(3);
        source.mark();
        assertTrue(q.offerVideo(source, 17, first.length, true, 1));
        assertEquals(3, source.position());
        assertEquals(6500, source.limit());
        source.reset();
        StreamFrameDropPolicy.Frame held = q.poll(0, TimeUnit.MILLISECONDS);
        assertEquals(6000, held.length);
        assertEquals(8192, held.bytes.length);

        byte[] next = new byte[5000];
        Arrays.fill(next, (byte) 0x23);
        source.position(17);
        source.put(next);
        assertTrue(q.offerVideo(source, 17, next.length, false, 2));
        StreamFrameDropPolicy.Frame second = q.poll(0, TimeUnit.MILLISECONDS);
        assertNotSame(held.bytes, second.bytes);
        assertArrayEquals(first, Arrays.copyOf(held.bytes, held.length));
        assertArrayEquals(next, Arrays.copyOf(second.bytes, second.length));
        q.release(held);
        q.release(held); // accidental double release must not duplicate ownership
        q.release(second);

        assertTrue(offerVideo(q, next, false, 3));
        StreamFrameDropPolicy.Frame reused = q.poll(0, TimeUnit.MILLISECONDS);
        assertSame(held.bytes, reused.bytes);
        assertEquals(next.length, reused.length);
        assertArrayEquals(next, Arrays.copyOf(reused.bytes, reused.length));
        assertTrue(offerVideo(q, first, false, 4));
        StreamFrameDropPolicy.Frame other = q.poll(0, TimeUnit.MILLISECONDS);
        assertSame(second.bytes, other.bytes);
        assertNotSame(reused.bytes, other.bytes);
        q.release(reused);
        q.release(other);
    }

    @Test
    public void congestionAndRetirementNeverRecycleAnInFlightFrame() throws Exception {
        StreamFrameDropPolicy q = policy(2);
        assertTrue(offerVideo(q, idr(1), true, 1));
        StreamFrameDropPolicy.Frame held = q.poll(0, TimeUnit.MILLISECONDS);
        assertTrue(offerVideo(q, p(2), false, 2));
        assertTrue(offerVideo(q, p(3), false, 3));
        assertFalse(offerVideo(q, p(4), false, 4));
        assertTrue(q.consumeSyncFrameRequest());
        assertTrue(offerVideo(q, idr(5), true, 5));
        StreamFrameDropPolicy.Frame resumed = q.poll(0, TimeUnit.MILLISECONDS);
        assertNotSame(held.bytes, resumed.bytes);
        assertArrayEquals(idr(1), Arrays.copyOf(held.bytes, held.length));
        assertArrayEquals(idr(5), Arrays.copyOf(resumed.bytes, resumed.length));
        q.release(resumed);

        q.offerConfig(SPS_PPS);
        assertTrue(offerVideo(q, p(6), false, 6));
        q.offerRetired();
        assertSame(StreamFrameDropPolicy.Frame.RETIRED, q.poll(0, TimeUnit.MILLISECONDS));
        assertArrayEquals(idr(1), Arrays.copyOf(held.bytes, held.length));
        q.release(held);
        q.resetForReattach();
        assertTrue(offerVideo(q, idr(7), true, 7));
        StreamFrameDropPolicy.Frame fresh = q.poll(0, TimeUnit.MILLISECONDS);
        assertNotSame(held.bytes, fresh.bytes);
        assertNotSame(resumed.bytes, fresh.bytes);
        q.release(fresh);
    }

    @Test
    public void oversizedPayloadsAreNotPooledAndViewersNeverShareArrays() throws Exception {
        StreamFrameDropPolicy q = policy(4);
        byte[] large = new byte[256 * 1024 + 1];
        assertTrue(offerVideo(q, large, true, 1));
        StreamFrameDropPolicy.Frame first = q.poll(0, TimeUnit.MILLISECONDS);
        assertEquals(large.length, first.bytes.length);
        q.release(first);
        assertTrue(offerVideo(q, large, true, 2));
        StreamFrameDropPolicy.Frame next = q.poll(0, TimeUnit.MILLISECONDS);
        assertNotSame(first.bytes, next.bytes);
        q.release(next);

        StreamFrameDropPolicy other = policy(4);
        assertTrue(offerVideo(q, idr(1), true, 3));
        assertTrue(offerVideo(other, idr(2), true, 3));
        StreamFrameDropPolicy.Frame a = q.poll(0, TimeUnit.MILLISECONDS);
        StreamFrameDropPolicy.Frame b = other.poll(0, TimeUnit.MILLISECONDS);
        assertNotSame(a.bytes, b.bytes);
        q.release(a);
        other.release(b);
    }

    @Test
    public void idlePoolRetainsAtMostTwoArraysInEachSizeClass() throws Exception {
        StreamFrameDropPolicy q = policy(4);
        for (int size = 4096; size <= 256 * 1024; size *= 2) {
            byte[] packet = new byte[size];
            for (int i = 0; i < 3; i++) assertTrue(offerVideo(q, packet, true, i));
            List<StreamFrameDropPolicy.Frame> first = drain(q);
            for (StreamFrameDropPolicy.Frame f : first) q.release(f);
            for (int i = 0; i < 3; i++) assertTrue(offerVideo(q, packet, true, i));
            List<StreamFrameDropPolicy.Frame> second = drain(q);
            assertSame(first.get(0).bytes, second.get(0).bytes);
            assertSame(first.get(1).bytes, second.get(1).bytes);
            assertNotSame(first.get(2).bytes, second.get(2).bytes);
            for (StreamFrameDropPolicy.Frame f : second) q.release(f);
        }
    }

    @Test
    public void healthyStreamPassesEveryFrameInOrder() throws Exception {
        StreamFrameDropPolicy q = policy(60);
        q.offerConfig(SPS_PPS);
        assertTrue(offerVideo(q, idr(0), true, 0));
        assertTrue(offerVideo(q, p(1), false, 33_000));
        assertTrue(offerVideo(q, p(2), false, 66_000));

        List<StreamFrameDropPolicy.Frame> sent = drain(q);
        assertEquals(4, sent.size());
        assertTrue(sent.get(0).isConfig);
        assertArrayEquals(SPS_PPS, sent.get(0).bytes);
        assertTrue(sent.get(1).isKey);
        assertEquals(66_000, sent.get(3).ptsUs);
        assertFalse(q.isSkipping());
        assertEquals(0, q.droppedFrames());
        assertNull("nothing to report on a healthy stream", q.drainSummary());
    }

    @Test
    public void fullQueueClearsBacklogAndEntersSkip() throws Exception {
        StreamFrameDropPolicy q = policy(4);
        assertTrue(offerVideo(q, idr(0), true, 0));
        assertTrue(offerVideo(q, p(1), false, 1));
        assertTrue(offerVideo(q, p(2), false, 2));
        assertTrue(offerVideo(q, p(3), false, 3));
        assertEquals(4, q.size());

        // 5th frame: queue full -> everything queued is discarded, skip begins,
        // the arriving P-frame is dropped too (its reference may be gone).
        assertFalse(offerVideo(q, p(4), false, 4));
        assertTrue(q.isSkipping());
        assertEquals(0, q.size());
        assertEquals(5, q.droppedFrames());
        assertEquals(1, q.gopSkips());
        assertTrue("entering skip asks for a sync frame once", q.consumeSyncFrameRequest());
        assertFalse(q.consumeSyncFrameRequest());
    }

    @Test
    public void nonKeyFramesAreDiscardedWhileSkippingAndIdrResumes() throws Exception {
        StreamFrameDropPolicy q = policy(2);
        offerVideo(q, idr(0), true, 0);
        offerVideo(q, p(1), false, 1);
        assertFalse(offerVideo(q, p(2), false, 2));   // full -> skip
        assertTrue(q.isSkipping());

        assertFalse(offerVideo(q, p(3), false, 3));
        assertFalse(offerVideo(q, p(4), false, 4));
        assertEquals(0, q.size());

        assertTrue(offerVideo(q, idr(5), true, 5));
        assertFalse(q.isSkipping());
        assertTrue(offerVideo(q, p(6), false, 6));

        List<StreamFrameDropPolicy.Frame> sent = drain(q);
        assertEquals(2, sent.size());
        assertTrue("delivery resumes exactly at the IDR", sent.get(0).isKey);
        assertEquals(5, sent.get(0).ptsUs);
        assertEquals(6, sent.get(1).ptsUs);
    }

    @Test
    public void staleOldestFrameTriggersSkipEvenWhenQueueHasRoom() throws Exception {
        StreamFrameDropPolicy q = policy(60);
        assertTrue(offerVideo(q, idr(0), true, 0));
        assertTrue(offerVideo(q, p(1), false, 1));
        nowMs += StreamFrameDropPolicy.MAX_QUEUE_AGE_MS;          // exactly at the cap: still fine
        assertTrue(offerVideo(q, p(2), false, 2));
        assertFalse(q.isSkipping());

        nowMs += 1;                                               // oldest is now > 1 s old
        assertFalse(offerVideo(q, p(3), false, 3));
        assertTrue(q.isSkipping());
        assertEquals("sender is stuck: backlog discarded", 0, q.size());
        assertEquals(4, q.droppedFrames());
        assertTrue(q.consumeSyncFrameRequest());

        assertTrue(offerVideo(q, idr(4), true, 4));
        assertFalse(q.isSkipping());
        String summary = q.drainSummary();
        assertNotNull(summary);
        assertTrue(summary, summary.contains("dropped 1 GOPs"));
        assertTrue(summary, summary.contains("stale >1000ms x1"));
    }

    @Test
    public void idrArrivingAtAFullQueueResumesImmediately() throws Exception {
        StreamFrameDropPolicy q = policy(2);
        offerVideo(q, idr(0), true, 0);
        offerVideo(q, p(1), false, 1);
        assertTrue("the IDR itself is kept; only the stale backlog goes", offerVideo(q, idr(2), true, 2));
        assertFalse(q.isSkipping());
        List<StreamFrameDropPolicy.Frame> sent = drain(q);
        assertEquals(1, sent.size());
        assertEquals(2, sent.get(0).ptsUs);
        assertEquals(2, q.droppedFrames());
    }

    @Test
    public void configIsNeverDroppedSilently_fullQueueDefersItToTheResumeIdr() throws Exception {
        StreamFrameDropPolicy q = policy(2);
        offerVideo(q, idr(0), true, 0);
        offerVideo(q, p(1), false, 1);
        q.offerConfig(SPS_PPS);                 // no room: deferred, not lost
        assertEquals(1, q.configDeferrals());
        assertEquals(2, q.size());

        assertFalse(offerVideo(q, p(2), false, 2));   // full -> skip
        assertTrue(offerVideo(q, idr(3), true, 3));   // resume

        List<StreamFrameDropPolicy.Frame> sent = drain(q);
        assertEquals(2, sent.size());
        assertTrue("deferred SPS/PPS goes out right before the resume IDR", sent.get(0).isConfig);
        assertArrayEquals(SPS_PPS, sent.get(0).bytes);
        assertTrue(sent.get(1).isKey);
        String summary = q.drainSummary();
        assertNotNull(summary);
        assertTrue(summary, summary.contains("SPS/PPS deferred x1"));
    }

    @Test
    public void configClearedWithTheBacklogIsResentBeforeTheResumeIdr() throws Exception {
        StreamFrameDropPolicy q = policy(3);
        q.offerConfig(SPS_PPS);                 // queued (sender has not drained it yet)
        offerVideo(q, idr(0), true, 0);
        offerVideo(q, p(1), false, 1);
        assertFalse(offerVideo(q, p(2), false, 2));   // full: clear discards the queued config too
        assertEquals(0, q.size());

        assertTrue(offerVideo(q, idr(3), true, 3));
        List<StreamFrameDropPolicy.Frame> sent = drain(q);
        assertEquals(2, sent.size());
        assertTrue(sent.get(0).isConfig);
        assertTrue(sent.get(1).isKey);
    }

    @Test
    public void configQueuedWhileSkippingIsDeliveredOnce() throws Exception {
        StreamFrameDropPolicy q = policy(2);
        offerVideo(q, idr(0), true, 0);
        offerVideo(q, p(1), false, 1);
        assertFalse(offerVideo(q, p(2), false, 2));   // skip
        q.offerConfig(SPS_PPS);                      // room now: queued directly
        assertEquals(0, q.configDeferrals());
        assertTrue(offerVideo(q, idr(3), true, 3));

        List<StreamFrameDropPolicy.Frame> sent = drain(q);
        assertEquals("config exactly once, then the IDR", 2, sent.size());
        assertTrue(sent.get(0).isConfig);
        assertTrue(sent.get(1).isKey);
    }

    @Test
    public void retirementSentinelIsNeverLost() throws Exception {
        StreamFrameDropPolicy q = policy(2);
        offerVideo(q, idr(0), true, 0);
        offerVideo(q, p(1), false, 1);
        q.offerRetired();                            // full: backlog yields to the notice
        List<StreamFrameDropPolicy.Frame> sent = drain(q);
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).isRetired());
        assertSame(StreamFrameDropPolicy.Frame.RETIRED, sent.get(0));
        assertNull(sent.get(0).bytes);

        // Sentinel already queued, then a late frame from the retiring
        // encoder forces a clear: the sentinel survives the clear.
        StreamFrameDropPolicy q2 = policy(2);
        q2.offerRetired();
        offerVideo(q2, p(1), false, 1);
        assertFalse(offerVideo(q2, p(2), false, 2));  // full -> clear
        List<StreamFrameDropPolicy.Frame> sent2 = drain(q2);
        assertEquals(1, sent2.size());
        assertTrue(sent2.get(0).isRetired());
    }

    @Test
    public void resetForReattachForgetsTheRetiredEncoder() throws Exception {
        StreamFrameDropPolicy q = policy(2);
        q.offerConfig(SPS_PPS);
        offerVideo(q, idr(0), true, 0);
        assertFalse(offerVideo(q, p(1), false, 1) && offerVideo(q, p(2), false, 2));
        q.resetForReattach();
        assertEquals(0, q.size());
        assertFalse(q.isSkipping());
        assertFalse(q.consumeSyncFrameRequest());

        // The new encoder's first IDR must NOT be prefixed with the old SPS/PPS.
        assertTrue(offerVideo(q, idr(9), true, 9));
        List<StreamFrameDropPolicy.Frame> sent = drain(q);
        assertEquals(1, sent.size());
        assertTrue(sent.get(0).isKey);
    }

    @Test
    public void dropSummaryIsRateLimitedNotPerFrame() throws Exception {
        StreamFrameDropPolicy q = policy(1);
        offerVideo(q, idr(0), true, 0);
        assertFalse(offerVideo(q, p(1), false, 1));
        String first = q.drainSummary();
        assertNotNull("first skip is reported immediately", first);
        assertTrue(first, first.startsWith("WS: dropped 1 GOPs"));
        assertTrue(first, first.contains("queue full x1"));

        offerVideo(q, idr(2), true, 2);
        assertFalse(offerVideo(q, p(3), false, 3));
        assertNull("second skip inside the window is coalesced", q.drainSummary());

        nowMs += StreamFrameDropPolicy.SUMMARY_INTERVAL_MS;
        String second = q.drainSummary();
        assertNotNull(second);
        assertTrue(second, second.startsWith("WS: dropped 2 GOPs"));
        assertNull("nothing new since the last summary", q.drainSummary());
    }

    @Test
    public void offersNeverBlockTheEncoderThread() throws Exception {
        StreamFrameDropPolicy q = policy(1);
        long start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            offerVideo(q, i % 30 == 0 ? idr(i) : p(i), i % 30 == 0, i);
            if (i % 1000 == 0) q.offerConfig(SPS_PPS);
            if (i % 5000 == 0) q.offerRetired();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue("10k offers against a full 1-slot queue took " + elapsedMs + "ms", elapsedMs < 2_000);
    }
}

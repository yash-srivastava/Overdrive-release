package com.overdrive.app.streaming;

import static com.overdrive.app.streaming.DirectStreamBacklogPolicy.Decision.RESUME;
import static com.overdrive.app.streaming.DirectStreamBacklogPolicy.Decision.SEND;
import static com.overdrive.app.streaming.DirectStreamBacklogPolicy.Decision.WAIT_FOR_IDR;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class DirectStreamBacklogPolicyTest {
    @Test
    public void emptyQueueAcceptsMaximumPacketButNotOversize() {
        assertFalse(DirectStreamBacklogPolicy.wouldExceed(Collections.emptyList(), 4 * 1024 * 1024));
        assertTrue(DirectStreamBacklogPolicy.wouldExceed(Collections.emptyList(), 4 * 1024 * 1024 + 1));
    }

    @Test
    public void partiallyWrittenPacketStillCountsItsFullCapacity() {
        ByteBuffer pending = ByteBuffer.allocate(3 * 1024 * 1024);
        pending.position(pending.limit() - 1);
        assertFalse(DirectStreamBacklogPolicy.wouldExceed(Collections.singletonList(pending), 1024 * 1024));
        assertTrue(DirectStreamBacklogPolicy.wouldExceed(Collections.singletonList(pending), 1024 * 1024 + 1));
    }

    @Test
    public void countBudgetStopsSmallPacketBacklog() {
        List<ByteBuffer> pending = new ArrayList<>();
        for (int i = 0; i < DirectStreamBacklogPolicy.MAX_PENDING_BUFFERS - 1; i++) {
            pending.add(ByteBuffer.allocate(10));
        }
        assertFalse(DirectStreamBacklogPolicy.wouldExceed(pending, 10));
        pending.add(ByteBuffer.allocate(10));
        assertTrue(DirectStreamBacklogPolicy.wouldExceed(pending, 10));
    }

    @Test
    public void congestedViewerWaitsThroughDrainAndResumesOnlyAtKeyframe() {
        List<ByteBuffer> pending = new ArrayList<>();
        for (int i = 0; i < DirectStreamBacklogPolicy.MAX_PENDING_BUFFERS; i++) {
            pending.add(ByteBuffer.allocate(10));
        }
        assertEquals(WAIT_FOR_IDR,
                DirectStreamBacklogPolicy.decide(pending, 100, false, 0, false));
        assertEquals(WAIT_FOR_IDR,
                DirectStreamBacklogPolicy.decide(pending, 100, true, 20, true));
        // The socket writer drains the queue; the waiting gate still rejects P-frames
        // and standalone config replays until a recovery pair can be sent.
        pending.clear();
        assertEquals(WAIT_FOR_IDR,
                DirectStreamBacklogPolicy.decide(pending, 100, false, 20, true));
        assertEquals(WAIT_FOR_IDR,
                DirectStreamBacklogPolicy.decide(pending, 20, false, 0, true));
        assertEquals(WAIT_FOR_IDR,
                DirectStreamBacklogPolicy.decide(pending, 100, true, 0, true));
        assertEquals(RESUME,
                DirectStreamBacklogPolicy.decide(pending, 100, true, 20, true));
        assertEquals(SEND,
                DirectStreamBacklogPolicy.decide(pending, 100, false, 0, false));
    }

    @Test
    public void recoveryReservesTwoSlotsAndBothFramingHeaders() {
        List<ByteBuffer> pending = new ArrayList<>();
        for (int i = 0; i < DirectStreamBacklogPolicy.MAX_PENDING_BUFFERS - 1; i++) {
            pending.add(ByteBuffer.allocate(10));
        }
        assertEquals(WAIT_FOR_IDR,
                DirectStreamBacklogPolicy.decide(pending, 100, true, 20, true));
        pending.remove(0);
        assertEquals(RESUME,
                DirectStreamBacklogPolicy.decide(pending, 100, true, 20, true));

        int max = 4 * 1024 * 1024;
        // The two 10-byte header allowances count against the same byte cap.
        assertEquals(RESUME, DirectStreamBacklogPolicy.decide(
                Collections.emptyList(), max - 30, true, 20, true));
        assertEquals(WAIT_FOR_IDR, DirectStreamBacklogPolicy.decide(
                Collections.emptyList(), max - 29, true, 20, true));
        ByteBuffer queued = ByteBuffer.allocate(max - 140);
        queued.position(queued.limit() - 1);
        assertEquals(RESUME, DirectStreamBacklogPolicy.decide(
                Collections.singletonList(queued), 100, true, 30, true));
        assertEquals(WAIT_FOR_IDR, DirectStreamBacklogPolicy.decide(
                Collections.singletonList(queued), 101, true, 30, true));
    }

    @Test
    public void oneViewersWaitingGateDoesNotSuppressAnotherViewersFrames() {
        List<ByteBuffer> empty = Collections.emptyList();
        assertEquals(WAIT_FOR_IDR,
                DirectStreamBacklogPolicy.decide(empty, 100, false, 0, true));
        assertEquals(SEND,
                DirectStreamBacklogPolicy.decide(empty, 100, false, 0, false));
    }
}

package com.overdrive.app.streaming;

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
}

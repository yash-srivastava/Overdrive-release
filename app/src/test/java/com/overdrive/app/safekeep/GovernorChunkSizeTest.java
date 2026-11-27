package com.overdrive.app.safekeep;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The suggested non-final upload chunk must satisfy both provider alignments. */
public class GovernorChunkSizeTest {
    private static final int KIB = 1024;
    private static final int MIB = 1024 * KIB;

    @Test
    public void unknownOrSlowThroughputUsesMinimumChunk() {
        assertEquals(5 * MIB, Governor.suggestChunk(0));
        assertEquals(5 * MIB, Governor.suggestChunk(-1));
        assertEquals(5 * MIB, Governor.suggestChunk(1));
        assertEquals(5 * MIB, Governor.suggestChunk(MIB));
    }

    @Test
    public void fourSecondTargetRoundsDownToCommonAlignment() {
        // 2 MiB/s targets 8 MiB; the nearest lower aligned size is 7.5 MiB.
        assertEquals(7680 * KIB, Governor.suggestChunk(2 * MIB));
        assertEquals(10 * MIB, Governor.suggestChunk(2.5 * MIB));
        // The boundary between 5 MiB and 6.25 MiB is 1,638,400 bytes/s.
        assertEquals(5 * MIB, Governor.suggestChunk(1_638_399));
        assertEquals(6400 * KIB, Governor.suggestChunk(1_638_400));
    }

    @Test
    public void fastThroughputIsCappedAtTwentyMiB() {
        assertEquals(20 * MIB, Governor.suggestChunk(5 * MIB));
        assertEquals(20 * MIB, Governor.suggestChunk(100 * MIB));
    }

    @Test
    public void suggestionsStayBoundedAlignedAndMonotonic() {
        int previous = 0;
        for (int rate = 0; rate <= 8 * MIB; rate += 64 * KIB) {
            int size = Governor.suggestChunk(rate);
            assertTrue(size >= 5 * MIB && size <= 20 * MIB);
            assertEquals(0, size % (256 * KIB));
            assertEquals(0, size % (320 * KIB));
            assertTrue("Chunk size decreased at rate " + rate, size >= previous);
            previous = size;
        }
    }
}

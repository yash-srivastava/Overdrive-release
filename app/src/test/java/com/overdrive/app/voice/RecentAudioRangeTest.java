package com.overdrive.app.voice;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Random;
import org.junit.Test;

public class RecentAudioRangeTest {
    // Reference: original recentTail followed by Arrays.copyOf for an interior range.
    private static short[] reference(short[] ring, int next, int back, int length) {
        short[] tail = new short[back];
        int from = next - back;
        if (from < 0) from += ring.length;
        for (int i = 0; i < back; i++) tail[i] = ring[(from + i) % ring.length];
        return Arrays.copyOf(tail, length);
    }

    @Test
    public void allSlicesOfSmallRings() {
        for (int capacity = 1; capacity <= 16; capacity++) {
            short[] ring = new short[capacity];
            for (int i = 0; i < capacity; i++) ring[i] = (short) (i + 1);
            for (int next = 0; next < capacity; next++) {
                for (int back = 0; back <= capacity; back++) {
                    for (int length = 0; length <= back; length++) {
                        assertArrayEquals(reference(ring, next, back, length),
                                RecentAudioRange.copy(ring, next, back, length));
                    }
                }
            }
        }
    }

    @Test
    public void randomizedVoiceHistorySlices() {
        Random random = new Random(7128);
        short[] ring = new short[64_000];
        for (int i = 0; i < ring.length; i++) ring[i] = (short) random.nextInt();
        for (int i = 0; i < 2_000; i++) {
            int next = random.nextInt(ring.length);
            int retained = random.nextInt(ring.length + 1);
            int back = random.nextInt(retained + 1);
            int length = random.nextInt(back + 1);
            assertArrayEquals(reference(ring, next, back, length),
                    RecentAudioRange.copy(ring, next, back, length));
            assertArrayEquals(reference(ring, next, back, back),
                    RecentAudioRange.copy(ring, next, back, back));
        }
    }

    @Test
    public void slicesAreDetachedAndChronological() {
        short[] ring = {4, 5, 6, 1, 2, 3};
        short[] slice = RecentAudioRange.copy(ring, 3, 5, 4);
        assertArrayEquals(new short[] {2, 3, 4, 5}, slice);
        slice[0] = 99;
        assertEquals(2, ring[4]);
        ring[5] = 99;
        assertEquals(3, slice[1]);
    }
}

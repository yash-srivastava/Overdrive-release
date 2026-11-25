package com.overdrive.app.surveillance;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class ThumbnailFrameSharingTest {
    private static final int W = 640, H = 640;

    @Test
    public void manyActorsShareOneOwnedCropButKeepTheirOwnBoxes() {
        ThumbnailBuffer buffer = new ThumbnailBuffer();
        byte[] source = frame(W, H, 17);
        List<Actor> actors = actors(32, 100, W, H);
        observe(buffer, actors, source, W, H);
        Arrays.fill(source, (byte) 99);

        List<ThumbnailBuffer.Slot> snap = buffer.drainSnapshotForAsync();
        assertEquals(32, snap.size());
        byte[] shared = snap.get(0).rgb;
        assertNotSame(source, shared);
        for (ThumbnailBuffer.Slot slot : snap) {
            assertSame(shared, slot.rgb);
            assertEquals((int) slot.actorId * 3, slot.bboxX);
            assertEquals(100L, slot.peakWallMs);
        }
        assertArrayEquals(frame(W, H, 17), shared);
    }

    @Test
    public void partialRefreshDoesNotOverwriteOtherActorsPeakPixels() {
        ThumbnailBuffer buffer = new ThumbnailBuffer();
        byte[] source = frame(W, H, 17);
        observe(buffer, actors(3, 100, W, H), source, W, H);
        Arrays.fill(source, (byte) 23);
        observe(buffer, Collections.singletonList(actor(1, 101, W, H, false)), source, W, H);
        Arrays.fill(source, (byte) 42);
        List<ThumbnailBuffer.Slot> snap = buffer.drainSnapshotForAsync();
        ThumbnailBuffer.Slot first = find(snap, 1), second = find(snap, 2), third = find(snap, 3);
        assertNotSame(first.rgb, second.rgb);
        assertSame(second.rgb, third.rgb);
        assertArrayEquals(frame(W, H, 23), first.rgb);
        assertArrayEquals(frame(W, H, 17), second.rgb);
        assertEquals(101L, first.peakWallMs);
        assertEquals(100L, second.peakWallMs);
    }

    @Test
    public void repeatedSharedRefreshesAlternateTwoBuffers() throws Exception {
        ThumbnailBuffer buffer = new ThumbnailBuffer();
        byte[] source = frame(W, H, 1);
        observe(buffer, actors(4, 100, W, H), source, W, H);
        byte[] a = activePixels(buffer, 1);
        Arrays.fill(source, (byte) 2);
        observe(buffer, actors(4, 101, W, H), source, W, H);
        byte[] b = activePixels(buffer, 1);
        assertNotSame(a, b);
        assertSame(a, spare(buffer));
        for (int i = 3; i < 20; i++) {
            Arrays.fill(source, (byte) i);
            observe(buffer, actors(4, 99 + i, W, H), source, W, H);
            byte[] expected = i % 2 == 1 ? a : b;
            for (int id = 1; id <= 4; id++) assertSame(expected, activePixels(buffer, id));
            assertArrayEquals(source, expected);
            assertSame(i % 2 == 1 ? b : a, spare(buffer));
        }
        buffer.drainSnapshotForAsync();
        assertNull(spare(buffer));
    }

    @Test
    public void exclusiveRefreshReusesPixelsAndDimensionChangeDoesNot() throws Exception {
        ThumbnailBuffer buffer = new ThumbnailBuffer();
        observe(buffer, actors(1, 100, W, H), frame(W, H, 1), W, H);
        byte[] first = activePixels(buffer, 1);
        observe(buffer, actors(1, 101, W, H), frame(W, H, 2), W, H);
        assertSame(first, activePixels(buffer, 1));
        observe(buffer, actors(1, 102, 320, 240), frame(320, 240, 3), 320, 240);
        byte[] resized = activePixels(buffer, 1);
        assertNotSame(first, resized);
        assertArrayEquals(frame(320, 240, 3), resized);
        assertNull(spare(buffer));
    }

    @Test
    public void separateCallsWithReusedSourceNeverShareDifferentFrames() {
        ThumbnailBuffer buffer = new ThumbnailBuffer();
        byte[] source = frame(W, H, 1);
        observe(buffer, Collections.singletonList(actor(1, 100, W, H, false)), source, W, H);
        Arrays.fill(source, (byte) 2);
        // Even identical timestamps do not establish that the bytes match.
        observe(buffer, Collections.singletonList(actor(2, 100, W, H, false)), source, W, H);
        List<ThumbnailBuffer.Slot> snap = buffer.drainSnapshotForAsync();
        assertNotSame(find(snap, 1).rgb, find(snap, 2).rgb);
        assertArrayEquals(frame(W, H, 1), find(snap, 1).rgb);
        assertArrayEquals(frame(W, H, 2), find(snap, 2).rgb);
    }

    @Test
    public void drainedSnapshotSurvivesLaterRefreshesAndClear() throws Exception {
        ThumbnailBuffer buffer = new ThumbnailBuffer();
        observe(buffer, actors(3, 100, W, H), frame(W, H, 1), W, H);
        observe(buffer, actors(3, 101, W, H), frame(W, H, 2), W, H);
        List<ThumbnailBuffer.Slot> snap = buffer.drainSnapshotForAsync();
        byte[] old = find(snap, 1).rgb;
        assertNull(spare(buffer));
        observe(buffer, actors(3, 102, W, H), frame(W, H, 3), W, H);
        observe(buffer, actors(3, 103, W, H), frame(W, H, 4), W, H);
        assertNotSame(old, activePixels(buffer, 1));
        buffer.clear();
        assertNull(spare(buffer));
        assertTrue(buffer.drainSnapshotForAsync().isEmpty());
        for (ThumbnailBuffer.Slot slot : snap) {
            assertSame(old, slot.rgb);
            assertArrayEquals(frame(W, H, 2), slot.rgb);
        }
    }

    @Test
    public void evictionOfSharedSceneryDoesNotDamageOtherSlots() {
        ThumbnailBuffer buffer = new ThumbnailBuffer();
        List<Actor> actors = actors(32, 100, W, H);
        actors.set(0, actor(1, 100, W, H, true));
        observe(buffer, actors, frame(W, H, 1), W, H);
        observe(buffer, Collections.singletonList(actor(33, 101, W, H, false)), frame(W, H, 2), W, H);
        List<ThumbnailBuffer.Slot> snap = buffer.drainSnapshotForAsync();
        assertEquals(32, snap.size());
        for (ThumbnailBuffer.Slot slot : snap) {
            assertTrue(slot.actorId != 1);
            assertArrayEquals(frame(W, H, slot.actorId == 33 ? 2 : 1), slot.rgb);
        }
    }

    @Test
    public void unchangedScoreAndPeakDoNotReplacePixels() throws Exception {
        ThumbnailBuffer buffer = new ThumbnailBuffer();
        observe(buffer, actors(3, 100, W, H), frame(W, H, 1), W, H);
        byte[] first = activePixels(buffer, 1);
        observe(buffer, actors(3, 100, W, H), frame(W, H, 2), W, H);
        assertSame(first, activePixels(buffer, 1));
        assertArrayEquals(frame(W, H, 1), first);
        assertNull(spare(buffer));
    }

    private static void observe(ThumbnailBuffer b, List<Actor> actors, byte[] rgb, int w, int h) {
        b.observe(actors, rgb, w, h, 0, null, 1f, 0f, 1f, 0f);
    }

    private static byte[] frame(int w, int h, int value) {
        byte[] rgb = new byte[w * h * 3];
        Arrays.fill(rgb, (byte) value);
        return rgb;
    }

    private static List<Actor> actors(int n, long time, int w, int h) {
        List<Actor> actors = new ArrayList<>();
        for (int id = 1; id <= n; id++) actors.add(actor(id, time, w, h, false));
        return actors;
    }

    private static Actor actor(int id, long time, int w, int h, boolean scenery) {
        return new Actor(id, Actor.ClassGroup.VEHICLE, 1, time, 0, time, 1,
                Actor.Proximity.MID, Actor.Proximity.MID, Actor.Trend.STABLE,
                scenery, scenery, !scenery, true, true,
                Actor.Severity.NOTICE, time, time, 0.8f,
                id * 3, 10, 20, 20, w, h, 0, id * 3, 10, 20, 20, 0);
    }

    private static ThumbnailBuffer.Slot find(List<ThumbnailBuffer.Slot> snap, int id) {
        for (ThumbnailBuffer.Slot s : snap) if (s.actorId == id) return s;
        throw new AssertionError("Missing slot " + id);
    }

    // Inspect allocation reuse without exposing mutable live buffers to production callers.
    private static byte[] activePixels(ThumbnailBuffer b, int id) throws Exception {
        Field f = ThumbnailBuffer.class.getDeclaredField("slots");
        f.setAccessible(true);
        Map<?, ?> slots = (Map<?, ?>) f.get(b);
        return ((ThumbnailBuffer.Slot) slots.get((long) id)).rgb;
    }

    private static byte[] spare(ThumbnailBuffer b) throws Exception {
        Field f = ThumbnailBuffer.class.getDeclaredField("spareRgb");
        f.setAccessible(true);
        return (byte[]) f.get(b);
    }
}

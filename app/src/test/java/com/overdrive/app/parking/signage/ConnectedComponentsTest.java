package com.overdrive.app.parking.signage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class ConnectedComponentsTest {
    // Original full-map BFS, retained as an independent regression oracle.
    private static List<ConnectedComponents.Component> reference(float[] score, int w, int h,
                                                                float threshold, int minArea) {
        List<ConnectedComponents.Component> result = new ArrayList<>();
        boolean[] visited = new boolean[w * h];
        int[] queue = new int[w * h];
        for (int start = 0; start < w * h; start++) {
            if (visited[start] || score[start] < threshold) continue;
            ConnectedComponents.Component c = new ConnectedComponents.Component();
            c.minX = c.maxX = start % w;
            c.minY = c.maxY = start / w;
            int head = 0, tail = 0;
            queue[tail++] = start;
            visited[start] = true;
            while (head < tail) {
                int idx = queue[head++];
                int x = idx % w, y = idx / w;
                c.area++;
                c.scoreSum += score[idx];
                c.minX = Math.min(c.minX, x);
                c.maxX = Math.max(c.maxX, x);
                c.minY = Math.min(c.minY, y);
                c.maxY = Math.max(c.maxY, y);
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = x + dx, ny = y + dy;
                        if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue;
                        int n = ny * w + nx;
                        if (visited[n] || score[n] < threshold) continue;
                        visited[n] = true;
                        queue[tail++] = n;
                    }
                }
            }
            if (c.area >= minArea) result.add(c);
        }
        return result;
    }

    private static void compare(float[] score, int w, int h, float threshold, int minArea) {
        float[] original = score.clone();
        List<ConnectedComponents.Component> expected = reference(score, w, h, threshold, minArea);
        List<ConnectedComponents.Component> actual = ConnectedComponents.label(score, w, h, threshold, minArea);
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            ConnectedComponents.Component a = expected.get(i), b = actual.get(i);
            assertEquals(a.minX, b.minX);
            assertEquals(a.maxX, b.maxX);
            assertEquals(a.minY, b.minY);
            assertEquals(a.maxY, b.maxY);
            assertEquals(a.area, b.area);
            // Exact accumulation order, including the existing NaN semantics.
            assertEquals(Double.doubleToLongBits(a.scoreSum), Double.doubleToLongBits(b.scoreSum));
        }
        assertTrue(Arrays.equals(original, score));
    }

    @Test public void randomizedMapsMatchOriginal() {
        Random random = new Random(81234);
        for (int trial = 0; trial < 1000; trial++) {
            int w = 1 + random.nextInt(75), h = 1 + random.nextInt(65);
            float[] score = new float[w * h];
            for (int i = 0; i < score.length; i++) {
                score[i] = random.nextInt(100) == 0 ? Float.NaN : random.nextFloat();
            }
            compare(score, w, h, random.nextFloat(), 1 + random.nextInt(12));
        }
    }

    @Test public void denseMapForcesWrappedQueueGrowth() {
        int w = 641, h = 480;
        float[] score = new float[w * h];
        for (int i = 0; i < score.length; i++) score[i] = 0.5f + (i % 17) * 0.02f;
        compare(score, w, h, 0.3f, 1);
        // Two dense components with a gap exercise reuse after the queue has grown.
        Arrays.fill(score, 200 * w, 205 * w, 0f);
        compare(score, w, h, 0.3f, 1);
    }

    @Test public void thinAndSparseMapsMatchOriginal() {
        float[] line = new float[4097];
        Arrays.fill(line, 0.75f);
        compare(line, 1, line.length, 0.3f, 1);
        compare(line, line.length, 1, 0.3f, 1);
        float[] sparse = new float[640 * 480];
        for (int i = 0; i < sparse.length; i += 997) sparse[i] = 0.9f;
        compare(sparse, 640, 480, 0.3f, 1);
        compare(sparse, 640, 480, 0.3f, 2);
        compare(new float[64], 8, 8, 0.3f, 1);
    }

    @Test public void invalidDimensionsReturnEmpty() {
        assertTrue(ConnectedComponents.label(null, 1, 1, 0.3f, 1).isEmpty());
        assertTrue(ConnectedComponents.label(new float[1], 0, 1, 0.3f, 1).isEmpty());
        assertTrue(ConnectedComponents.label(new float[1], 2, 2, 0.3f, 1).isEmpty());
        assertTrue(ConnectedComponents.label(new float[1], 65536, 65536, 0.3f, 1).isEmpty());
    }
}

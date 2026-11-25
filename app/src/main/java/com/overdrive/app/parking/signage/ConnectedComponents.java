package com.overdrive.app.parking.signage;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * 8-connected component labelling over a binary mask, iterative (explicit
 * queue) so a large text region can never overflow the stack. Used to turn a
 * DBNet-style text probability map into candidate text boxes without pulling
 * in OpenCV.
 */
public final class ConnectedComponents {

    /** Axis-aligned component with its pixel count. */
    public static final class Component {
        public int minX, minY, maxX, maxY;
        public int area;
        /** Sum of mask scores inside the component (for a mean-score filter). */
        public double scoreSum;

        public int width() { return maxX - minX + 1; }
        public int height() { return maxY - minY + 1; }
        public float meanScore() { return area == 0 ? 0f : (float) (scoreSum / area); }
    }

    private ConnectedComponents() {}

    /**
     * @param score   row-major {@code w*h} probability map (0..1)
     * @param w       map width
     * @param h       map height
     * @param thresh  binarisation threshold
     * @param minArea drop components smaller than this many pixels
     */
    public static List<Component> label(float[] score, int w, int h, float thresh, int minArea) {
        List<Component> out = new ArrayList<>();
        if (score == null || w <= 0 || h <= 0 || (long) w * h > score.length) return out;
        int pixels = w * h;
        BitSet visited = new BitSet(pixels);
        // Only pending pixels need queue storage, not the entire component or map.
        PixelQueue queue = new PixelQueue(pixels);
        for (int start = 0; start < pixels; start++) {
            if (visited.get(start) || score[start] < thresh) continue;
            Component c = new Component();
            c.minX = c.maxX = start % w;
            c.minY = c.maxY = start / w;
            queue.add(start);
            visited.set(start);
            while (!queue.isEmpty()) {
                int idx = queue.remove();
                int x = idx % w, y = idx / w;
                c.area++;
                c.scoreSum += score[idx];
                if (x < c.minX) c.minX = x;
                if (x > c.maxX) c.maxX = x;
                if (y < c.minY) c.minY = y;
                if (y > c.maxY) c.maxY = y;
                for (int dy = -1; dy <= 1; dy++) {
                    int ny = y + dy;
                    if (ny < 0 || ny >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = x + dx;
                        if (nx < 0 || nx >= w) continue;
                        int n = ny * w + nx;
                        if (visited.get(n) || score[n] < thresh) continue;
                        visited.set(n);
                        queue.add(n);
                    }
                }
            }
            if (c.area >= minArea) out.add(c);
        }
        return out;
    }

    /** FIFO ring, reused across components and grown only for the live frontier. */
    private static final class PixelQueue {
        private final int maxCapacity;
        private int[] data;
        private int head, tail, size;

        PixelQueue(int maxCapacity) {
            this.maxCapacity = maxCapacity;
            data = new int[Math.min(256, maxCapacity)];
        }

        boolean isEmpty() { return size == 0; }

        void add(int pixel) {
            if (size == data.length) {
                // Every pixel is enqueued at most once, so growth cannot exceed the map.
                int capacity = (int) Math.min((long) data.length * 2, maxCapacity);
                int[] grown = new int[capacity];
                int first = data.length - head;
                System.arraycopy(data, head, grown, 0, first);
                System.arraycopy(data, 0, grown, first, head);
                data = grown;
                head = 0;
                tail = size;
            }
            data[tail] = pixel;
            if (++tail == data.length) tail = 0;
            size++;
        }

        int remove() {
            int pixel = data[head];
            if (++head == data.length) head = 0;
            size--;
            return pixel;
        }
    }

    /**
     * DBNet "unclip": grow a shrunk text box back to the glyph extent. Uses
     * the polygon-offset formula on the axis-aligned box:
     * {@code d = area * ratio / perimeter}.
     */
    public static int[] unclip(Component c, float ratio, int maxW, int maxH) {
        float area = (float) c.width() * c.height();
        float perimeter = 2f * (c.width() + c.height());
        int d = perimeter <= 0 ? 0 : Math.round(area * ratio / perimeter);
        int x0 = Math.max(0, c.minX - d);
        int y0 = Math.max(0, c.minY - d);
        int x1 = Math.min(maxW - 1, c.maxX + d);
        int y1 = Math.min(maxH - 1, c.maxY + d);
        return new int[] {x0, y0, x1, y1};
    }
}

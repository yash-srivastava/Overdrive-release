package com.overdrive.app.parking.signage;

import java.util.ArrayList;
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
        // Classify once; a set bit is a foreground pixel not yet queued.
        // Keep the original < test (NaN scores also count as foreground).
        Foreground remaining = new Foreground(score, pixels, thresh);
        int start = remaining.nextSetBit(0);
        if (start < 0) return out;
        // Only pending pixels need queue storage, not the entire component or map.
        PixelQueue queue = new PixelQueue(pixels);
        for (; start >= 0; start = remaining.nextSetBit(start + 1)) {
            Component c = new Component();
            c.minX = c.maxX = start % w;
            c.minY = c.maxY = start / w;
            queue.add(start);
            remaining.clear(start);
            while (!queue.isEmpty()) {
                int idx = queue.remove();
                int y = idx / w, x = idx - y * w;
                c.area++;
                c.scoreSum += score[idx];
                if (x < c.minX) c.minX = x;
                if (x > c.maxX) c.maxX = x;
                if (y < c.minY) c.minY = y;
                if (y > c.maxY) c.maxY = y;
                // Claim each neighboring row as a bit range (at most three
                // pixels). The center bit was cleared when queued, so including
                // it is harmless. Top-to-bottom, left-to-right enqueue order
                // remains identical to the original eight-neighbor BFS.
                int left = x > 0 ? 1 : 0;
                int right = x + 1 < w ? 1 : 0;
                int first = idx - left;
                int count = left + 1 + right;
                if (y > 0) remaining.enqueue(first - w, count, queue);
                remaining.enqueue(first, count, queue);
                if (y + 1 < h) remaining.enqueue(first + w, count, queue);
            }
            if (c.area >= minArea) out.add(c);
        }
        return out;
    }

    /** One bit per pixel. Claim short row spans a word at a time, rather than
     * checking and clearing each neighbor separately. */
    private static final class Foreground {
        private final long[] words;

        Foreground(float[] score, int pixels, float threshold) {
            words = new long[((pixels - 1) >>> 6) + 1];
            for (int i = 0; i < pixels; i++) {
                if (!(score[i] < threshold)) words[i >>> 6] |= 1L << (i & 63);
            }
        }

        int nextSetBit(int from) {
            int word = from >>> 6;
            if (word >= words.length) return -1;
            long bits = words[word] & (-1L << (from & 63));
            while (bits == 0) {
                if (++word == words.length) return -1;
                bits = words[word];
            }
            return (word << 6) + Long.numberOfTrailingZeros(bits);
        }

        void clear(int pixel) {
            words[pixel >>> 6] &= ~(1L << (pixel & 63));
        }

        void enqueue(int first, int count, PixelQueue queue) {
            int word = first >>> 6;
            int shift = first & 63;
            long mask = (1L << count) - 1;
            enqueueWord(word, mask << shift, queue);
            // A three-pixel span can cross a 64-bit word boundary.
            if (shift + count > 64) enqueueWord(word + 1, mask >>> (64 - shift), queue);
        }

        private void enqueueWord(int word, long mask, PixelQueue queue) {
            long bits = words[word] & mask;
            if (bits == 0) return;
            words[word] ^= bits;
            int base = word << 6;
            do {
                queue.add(base + Long.numberOfTrailingZeros(bits));
                bits &= bits - 1;
            } while (bits != 0);
        }
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

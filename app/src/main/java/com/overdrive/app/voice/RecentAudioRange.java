package com.overdrive.app.voice;

/** Detached, chronological slices of a caller-owned PCM history ring. */
final class RecentAudioRange {
    private RecentAudioRange() {}

    /**
     * Copy {@code length} samples starting {@code samplesBack} samples before
     * the next write position. The caller clips the range to retained audio:
     * 0 <= length <= samplesBack <= ring.length.
     */
    static short[] copy(short[] ring, int next, int samplesBack, int length) {
        short[] out = new short[length];
        if (length == 0) return out;
        int start = next - samplesBack;
        if (start < 0) start += ring.length;
        int first = Math.min(length, ring.length - start);
        System.arraycopy(ring, start, out, 0, first);
        if (first < length) {
            System.arraycopy(ring, 0, out, first, length - first);
        }
        return out;
    }
}

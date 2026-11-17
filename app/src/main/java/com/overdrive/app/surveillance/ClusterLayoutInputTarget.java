package com.overdrive.app.surveillance;

/** Immutable gesture destination: one layout slot in physical-cluster coordinates. */
public final class ClusterLayoutInputTarget {
    public final int displayId, left, top, width, height, inputWidth, inputHeight;
    public final long generation;

    public ClusterLayoutInputTarget(int displayId, int left, int top, int width, int height,
                                    int inputWidth, int inputHeight, long generation) {
        if (displayId <= 0 || left < 0 || top < 0 || width <= 0 || height <= 0
                || inputWidth <= 0 || inputHeight <= 0 || generation <= 0) {
            throw new IllegalArgumentException("Invalid layout input destination");
        }
        this.displayId = displayId;
        this.left = left; this.top = top; this.width = width; this.height = height;
        this.inputWidth = inputWidth; this.inputHeight = inputHeight;
        this.generation = generation;
    }

    public boolean contains(float x, float y) {
        return Float.isFinite(x) && Float.isFinite(y)
                && x >= left && y >= top && x < (long) left + width && y < (long) top + height;
    }

    public float mapX(float x) { return clamp((x - left) * inputWidth / width, inputWidth); }
    public float mapY(float y) { return clamp((y - top) * inputHeight / height, inputHeight); }

    /** A pressed finger stays on this destination even when it crosses another slot. */
    public boolean isCurrent(boolean layoutActive, long currentGeneration) {
        return layoutActive && generation == currentGeneration;
    }

    private static float clamp(float coordinate, int size) {
        return Math.max(0f, Math.min(coordinate, size - 1f));
    }
}

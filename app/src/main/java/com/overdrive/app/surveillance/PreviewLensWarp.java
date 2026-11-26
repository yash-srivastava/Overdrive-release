package com.overdrive.app.surveillance;

/** Immutable radial sampling geometry shared by raw-clip still previews. */
final class PreviewLensWarp {
    // At most 1 MiB of double coordinates retained, independent of clip count.
    static final int MAX_CACHED_PIXELS = 64 * 1024;
    private static final Object CACHE_LOCK = new Object();
    private static volatile PreviewLensWarp cached;

    private final int w, h, strength;
    private final double aspect, k1, k2, zoom;
    private final double[] coordinates;

    private PreviewLensWarp(int w, int h, double aspect, int strength, boolean cacheCoordinates) {
        this.w = w;
        this.h = h;
        this.aspect = aspect;
        this.strength = strength;
        double t = strength / 100.0;
        k1 = 0.30 * t;
        k2 = 0.10 * t;
        double a2 = aspect * aspect;
        zoom = 1 + k1 * a2 + k2 * a2 * a2;
        if (cacheCoordinates) {
            coordinates = new double[w * h * 2];
            for (int y = 0; y < h; y++) projectRow(coordinates, y * w * 2, y);
        } else {
            coordinates = null;
        }
    }

    static PreviewLensWarp forTile(int w, int h, double aspect, int strength) {
        int effectiveStrength = Math.min(100, strength);
        if ((long) w * h > MAX_CACHED_PIXELS) {
            // Large stills keep the original per-pixel math, with only a row of scratch.
            return new PreviewLensWarp(w, h, aspect, effectiveStrength, false);
        }
        PreviewLensWarp hit = cached;
        if (hit != null && hit.matches(w, h, aspect, effectiveStrength)) return hit;
        synchronized (CACHE_LOCK) {
            hit = cached;
            if (hit == null || !hit.matches(w, h, aspect, effectiveStrength)) {
                try {
                    hit = new PreviewLensWarp(w, h, aspect, effectiveStrength, true);
                } catch (OutOfMemoryError lowMemory) {
                    // A cache allocation is optional; still attempt the original transform.
                    return new PreviewLensWarp(w, h, aspect, effectiveStrength, false);
                }
                cached = hit;
            }
            return hit;
        }
    }

    private boolean matches(int w, int h, double aspect, int strength) {
        return this.w == w && this.h == h && this.aspect == aspect && this.strength == strength;
    }

    private void projectRow(double[] target, int offset, int y) {
        double ny = ((y + 0.5) / h) * 2 - 1;
        double ay = ny * aspect;
        for (int x = 0; x < w; x++) {
            double nx = ((x + 0.5) / w) * 2 - 1;
            double r2 = nx * nx + ay * ay;
            double inv = zoom / (1 + k1 * r2 + k2 * r2 * r2);
            target[offset++] = Math.max(0, Math.min(1, nx * inv * 0.5 + 0.5)) * w - 0.5;
            target[offset++] = Math.max(0, Math.min(1, ny * inv * 0.5 + 0.5)) * h - 0.5;
        }
    }

    /** Apply to caller-owned tile arrays; strip is deliberately not part of the geometry key. */
    void apply(int[] in, int[] out, int strip) {
        double[] samples = coordinates != null ? coordinates : new double[w * 2];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            if (y < strip) {
                System.arraycopy(in, row, out, row, w);
                continue;
            }
            int offset = coordinates != null ? row * 2 : 0;
            if (coordinates == null) projectRow(samples, 0, y);
            for (int x = 0; x < w; x++) {
                double u = samples[offset++];
                double v = samples[offset++];
                out[row + x] = bilinear(in, w, h, u, Math.max(v, strip));
            }
        }
    }

    private static int bilinear(int[] p, int w, int h, double u, double v) {
        u = Math.max(0, Math.min(w - 1, u));
        v = Math.max(0, Math.min(h - 1, v));
        int x0 = (int) u, y0 = (int) v;
        int x1 = Math.min(x0 + 1, w - 1), y1 = Math.min(y0 + 1, h - 1);
        double fx = u - x0, fy = v - y0;
        int c00 = p[y0 * w + x0], c10 = p[y0 * w + x1], c01 = p[y1 * w + x0], c11 = p[y1 * w + x1];
        int r = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            double top = ((c00 >>> shift) & 0xff) * (1 - fx) + ((c10 >>> shift) & 0xff) * fx;
            double bot = ((c01 >>> shift) & 0xff) * (1 - fx) + ((c11 >>> shift) & 0xff) * fx;
            r |= ((int) Math.round(top * (1 - fy) + bot * fy) & 0xff) << shift;
        }
        return r;
    }
}

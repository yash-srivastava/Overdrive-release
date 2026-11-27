package com.overdrive.app.parking;

/** Pixel-only metrics for the privately decoded parking images. */
final class ParkingFrameMetrics {
    private ParkingFrameMetrics() {}

    private static int luma(int argb) {
        return (((argb >> 16) & 0xFF) * 77 + ((argb >> 8) & 0xFF) * 150
                + (argb & 0xFF) * 29) >> 8;
    }

    static boolean isBlank(int[] pixels, int meanThreshold, int maxThreshold) {
        long sum = 0;
        for (int pixel : pixels) {
            int l = luma(pixel);
            // One sufficiently bright pixel makes the whole frame nonblank.
            if (l >= maxThreshold) return false;
            sum += l;
        }
        return pixels.length > 0 && sum / pixels.length < meanThreshold;
    }

    /** Consumes the caller's private, tightly packed ARGB scratch as a luma plane. */
    static double scoreInPlace(int[] pixels, int width, int height) {
        int clipped = 0;
        for (int i = 0; i < pixels.length; i++) {
            int l = luma(pixels[i]);
            pixels[i] = l;
            if (l < 16 || l > 239) clipped++;
        }
        // Each gradient is an integer in [0, 510]. Even the largest Java array
        // cannot make the sum exceed 2^53, so integer accumulation is exact.
        long energy = 0;
        long n = 0;
        for (int y = 1; y < height - 1; y++) {
            int row = y * width;
            for (int x = 1; x < width - 1; x++) {
                int idx = row + x;
                energy += Math.abs(pixels[idx + 1] - pixels[idx - 1])
                        + Math.abs(pixels[idx + width] - pixels[idx - width]);
                n++;
            }
        }
        if (n == 0) return 0.0;
        double sharp = (double) energy / n / 255.0;
        double exposure = 1.0 - (double) clipped / pixels.length;
        return sharp * exposure * 100.0;
    }
}

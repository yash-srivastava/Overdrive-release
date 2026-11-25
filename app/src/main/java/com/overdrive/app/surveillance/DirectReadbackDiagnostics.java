package com.overdrive.app.surveillance;

import com.overdrive.app.logging.DaemonLogger;

import java.util.Arrays;
import java.util.Locale;

/**
 * Opt-in, per-lane timings; no frame bytes or per-frame timing logs.
 * DaemonLogger output is retained in debug/Braveheart builds, not normal release builds.
 */
final class DirectReadbackDiagnostics {
    private static final DaemonLogger logger = DaemonLogger.getInstance("GpuDownscaler");
    private static final String PROPERTY = "debug.overdrive.rgb_readback";
    private static final int WARMUP = 60;
    private static final int WINDOW = 300;
    private final DirectRgbReadback.Mode mode;
    private final boolean pbo;
    private final int width;
    private final int height;
    private final long[] frames = new long[WINDOW];
    private final long[] empty = new long[WINDOW];
    private int warmup;
    private int frameCount;
    private int emptyCount;
    private int nativeCount;
    private int compared;
    private int mismatches;

    static DirectRgbReadback.Mode readMode() {
        try {
            String value = (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class)
                    .invoke(null, PROPERTY, "off");
            switch (value) {
                case "java": return DirectRgbReadback.Mode.JAVA;
                case "native": return DirectRgbReadback.Mode.NATIVE;
                case "verify": return DirectRgbReadback.Mode.VERIFY;
                default: return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** The Camera probe setting: NATIVE unless the user switched fast copy off. */
    static DirectRgbReadback.Mode configuredMode() {
        return com.overdrive.app.camera.CameraConfigResolver.isFastAiFrameCopyEnabled()
                ? DirectRgbReadback.Mode.NATIVE : DirectRgbReadback.Mode.JAVA;
    }

    DirectReadbackDiagnostics(DirectRgbReadback.Mode mode, boolean pbo, int width, int height) {
        this.mode = mode;
        this.pbo = pbo;
        this.width = width;
        this.height = height;
        logger.info("RGB-READBACK mode=" + mode + " pbo=" + pbo + " size=" + width + "x" + height);
    }

    void record(long nanos, boolean hasFrame, DirectRgbReadback readback) {
        // No-frame polls may retain the last completed pack's flags; ignore them.
        if (hasFrame && readback.lastMismatch()) {
            logger.warn("RGB-READBACK mismatch: using Java reference; native disabled for this lane");
        }
        if (warmup < WARMUP) {
            warmup++;
            return;
        }
        if (hasFrame) {
            frames[frameCount++] = nanos;
            if (readback.lastNative()) nativeCount++;
            if (readback.lastCompared()) compared++;
            if (readback.lastMismatch()) mismatches++;
        } else {
            empty[emptyCount++] = nanos;
        }
        if (frameCount + emptyCount != WINDOW) return;
        logger.info("RGB-READBACK mode=" + mode + " pbo=" + pbo + " size=" + width + "x" + height
                + " calls=" + WINDOW + " frames=" + frameCount + " null=" + emptyCount
                + " native=" + nativeCount + " compared=" + compared + " mismatches=" + mismatches
                + " frame_us=" + percentiles(frames, frameCount)
                + " null_us=" + percentiles(empty, emptyCount));
        frameCount = emptyCount = nativeCount = compared = mismatches = 0;
    }

    private static String percentiles(long[] samples, int count) {
        if (count == 0) return "n/a";
        Arrays.sort(samples, 0, count);
        return String.format(Locale.US, "p50:%.1f,p95:%.1f",
                samples[(count - 1) / 2] / 1000.0,
                samples[(int) Math.ceil(count * 0.95) - 1] / 1000.0);
    }
}

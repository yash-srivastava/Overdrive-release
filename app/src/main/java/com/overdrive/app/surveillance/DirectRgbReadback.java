package com.overdrive.app.surveillance;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** One GL lane: prepare from mapped memory, publish only after a valid unmap. */
final class DirectRgbReadback {
    enum Mode { JAVA, NATIVE, VERIFY }

    // NATIVE compares its first frames with Java; a mismatch falls back for this lane.
    static final int NATIVE_SELF_CHECK_FRAMES = 30;

    interface NativePacker {
        boolean pack(ByteBuffer rgba, byte[] rgb);
    }

    private final RgbaReadback packer;
    private final NativePacker nativePacker;
    private final Mode mode;
    private byte[] published;
    private byte[] staging;
    // Native mode can fall back without allocating or retaining a full RGBA frame.
    private final byte[] fallbackRow;
    private boolean packedFallback;
    private boolean copied;
    private boolean packed;
    private boolean nativeDisabled;
    private int selfCheckFrames;
    private boolean lastNative;
    private boolean lastCompared;
    private boolean lastMismatch;

    DirectRgbReadback(int width, int height, Mode mode) {
        this(width, height, mode, null);
    }

    // Injectable native call lets JVM tests exercise the publication contract.
    DirectRgbReadback(int width, int height, Mode mode, NativePacker nativePacker) {
        packer = new RgbaReadback(width, height);
        this.nativePacker = nativePacker == null ? packer::tryNativePack : nativePacker;
        this.mode = mode == null ? Mode.JAVA : mode;
        selfCheckFrames = this.mode == Mode.NATIVE ? NATIVE_SELF_CHECK_FRAMES : 0;
        published = new byte[width * height * 3];
        packer.reserveScratch();
        if (this.mode != Mode.JAVA) staging = new byte[published.length];
        fallbackRow = this.mode == Mode.NATIVE ? new byte[width * 4] : null;
    }

    void prepare(ByteBuffer rgba) {
        copied = false;
        packed = false;
        packedFallback = false;
        if ((mode == Mode.VERIFY || selfCheckFrames > 0) && !nativeDisabled) {
            copied = packer.copyRgba(rgba);
            if (!copied) return;
        }
        if (mode != Mode.JAVA && !nativeDisabled) {
            packed = nativePacker.pack(rgba, staging);
        }
        if (!packed && !copied) {
            if (mode == Mode.NATIVE && !nativeDisabled && selfCheckFrames == 0) {
                // The full scratch was released after warmup. Only the rare fallback
                // packs while mapped; stage it so a failed unmap cannot publish it.
                packedFallback = packer.packRows(rgba, staging, fallbackRow);
            } else {
                copied = packer.copyRgba(rgba);
            }
        }
    }

    byte[] finish(boolean valid) {
        lastNative = false;
        lastCompared = false;
        lastMismatch = false;
        try {
            if (!valid || (!packed && !copied && !packedFallback)) return null;
            if (packed || packedFallback) {
                lastNative = packed;
                if (packed && copied) {
                    packer.packCopied(published);
                    lastCompared = true;
                    if (selfCheckFrames > 0) selfCheckFrames--;
                    if (!Arrays.equals(published, staging)) {
                        // Publish the reference, not the bad native result.
                        lastMismatch = true;
                        nativeDisabled = true;
                        staging = null;
                        return published;
                    }
                    // Only release after a successful comparison and valid unmap.
                    if (mode == Mode.NATIVE && selfCheckFrames == 0) packer.clear();
                }
                byte[] previous = published;
                published = staging;
                staging = previous;
            } else {
                packer.packCopied(published);
            }
            return published;
        } finally {
            packed = false;
            packedFallback = false;
            copied = false;
        }
    }

    boolean lastNative() { return lastNative; }
    boolean lastCompared() { return lastCompared; }
    boolean lastMismatch() { return lastMismatch; }
}

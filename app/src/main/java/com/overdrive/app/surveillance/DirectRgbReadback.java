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
    }

    void prepare(ByteBuffer rgba) {
        copied = false;
        packed = false;
        if ((mode == Mode.VERIFY || selfCheckFrames > 0) && !nativeDisabled) {
            copied = packer.copyRgba(rgba);
            if (!copied) return;
        }
        if (mode != Mode.JAVA && !nativeDisabled) {
            packed = nativePacker.pack(rgba, staging);
        }
        if (!packed && !copied) copied = packer.copyRgba(rgba);
    }

    byte[] finish(boolean valid) {
        lastNative = false;
        lastCompared = false;
        lastMismatch = false;
        try {
            if (!valid || (!packed && !copied)) return null;
            if (packed) {
                lastNative = true;
                if (copied) {
                    packer.packCopied(published);
                    lastCompared = true;
                    if (selfCheckFrames > 0) selfCheckFrames--;
                    if (!Arrays.equals(published, staging)) {
                        // Publish the reference, not the bad native result.
                        lastMismatch = true;
                        nativeDisabled = true;
                        return published;
                    }
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
            copied = false;
        }
    }

    boolean lastNative() { return lastNative; }
    boolean lastCompared() { return lastCompared; }
    boolean lastMismatch() { return lastMismatch; }
}

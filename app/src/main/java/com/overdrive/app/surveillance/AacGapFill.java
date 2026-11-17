package com.overdrive.app.surveillance;

import android.media.MediaCodec;
import android.media.MediaMuxer;

import java.nio.ByteBuffer;

/**
 * Keeps a muxed AAC track contiguous: Chromium plays AAC back to back whatever its timestamps, so
 * any hole moves all later audio ahead of the picture. Holes take recovered AAC, then silence.
 */
final class AacGapFill {
    /** AAC-LC mono frame with no spectral data; decodes to digital silence. */
    private static final byte[] SILENT_LC_MONO = {0x00, (byte) 0xC8, 0x00, (byte) 0x80, 0x23, (byte) 0x80};
    private static final int AAC_LC = 2;
    private static final int SAMPLES_PER_FRAME = 1024;

    private final MediaMuxer mux;
    private final int track;
    private final byte[] silence;
    private final double frameUs;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    long lastPtsUs;
    int silentFrames;
    int recoveredFrames;

    /** {@code lastPtsUs} is the track's last written PTS, or -1 when nothing is written yet. */
    AacGapFill(MediaMuxer mux, int track, byte[] silence, int sampleRate, long lastPtsUs) {
        this.mux = mux;
        this.track = track;
        this.silence = silence;
        this.frameUs = frameUs(sampleRate);
        this.lastPtsUs = lastPtsUs;
    }

    /** The silent frame for this AudioSpecificConfig, or null when one can't be synthesized. */
    static byte[] silentFrame(byte[] csd0, int channelCount) {
        if (csd0 == null || csd0.length < 2 || channelCount != 1) return null;
        int objectType = (csd0[0] & 0xFF) >> 3;
        int channelConfig = ((csd0[1] & 0xFF) >> 3) & 0x0F;
        return objectType == AAC_LC && channelConfig == 1 ? SILENT_LC_MONO : null;
    }

    static double frameUs(int sampleRate) {
        return sampleRate > 0 ? SAMPLES_PER_FRAME * 1_000_000.0 / sampleRate : 0.0;
    }

    /** True when at least one whole frame is missing before {@code nextPtsUs}. */
    static boolean hasGap(long lastPtsUs, long nextPtsUs, int sampleRate) {
        double frame = frameUs(sampleRate);
        if (frame <= 0.0) return false;
        return lastPtsUs < 0 ? nextPtsUs >= frame : nextPtsUs - lastPtsUs >= 1.5 * frame;
    }

    /** Writes a packet the caller recovered; skipped unless it lands strictly after the last write. */
    boolean writeRecovered(byte[] data, long ptsUs) {
        if (ptsUs < 0 || ptsUs <= lastPtsUs) return false;
        fillSilenceBefore(ptsUs);
        info.set(0, data.length, ptsUs, 0);
        mux.writeSampleData(track, ByteBuffer.wrap(data), info);
        lastPtsUs = ptsUs;
        recoveredFrames++;
        return true;
    }

    /** Fills silence up to one frame before {@code nextPtsUs}, aligned to that packet. */
    void fillSilenceBefore(long nextPtsUs) {
        if (silence == null || frameUs <= 0.0) return;
        int missing = lastPtsUs < 0
                ? (int) Math.floor(nextPtsUs / frameUs)
                : (int) Math.round((nextPtsUs - lastPtsUs) / frameUs) - 1;
        for (int k = missing; k >= 1; k--) {
            long pts = nextPtsUs - Math.round(k * frameUs);
            if (pts < 0 || pts <= lastPtsUs) continue;
            info.set(0, silence.length, pts, 0);
            mux.writeSampleData(track, ByteBuffer.wrap(silence), info);
            lastPtsUs = pts;
            silentFrames++;
        }
    }
}

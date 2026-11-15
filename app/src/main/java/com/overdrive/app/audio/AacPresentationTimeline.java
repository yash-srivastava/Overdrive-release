package com.overdrive.app.audio;

/**
 * Generates a continuous, sample-accurate presentation timeline for fixed-size AAC access units.
 * Scheduling delays in the encoder/drain thread do not change media timestamps.
 */
final class AacPresentationTimeline {
    private final long basePtsUs;
    private final int sampleRate;
    private final int samplesPerFrame;
    private long emittedSamples;

    AacPresentationTimeline(long basePtsUs, int sampleRate, int samplesPerFrame) {
        if (sampleRate <= 0) throw new IllegalArgumentException("sampleRate must be positive");
        if (samplesPerFrame <= 0) {
            throw new IllegalArgumentException("samplesPerFrame must be positive");
        }
        this.basePtsUs = basePtsUs;
        this.sampleRate = sampleRate;
        this.samplesPerFrame = samplesPerFrame;
    }

    long nextPtsUs() {
        long ptsUs = basePtsUs + emittedSamples * 1_000_000L / sampleRate;
        emittedSamples += samplesPerFrame;
        return ptsUs;
    }
}

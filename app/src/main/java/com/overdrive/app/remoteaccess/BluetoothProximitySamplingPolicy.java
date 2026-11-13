package com.overdrive.app.remoteaccess;

/**
 * Adaptive RSSI sampling for phone proximity. Keep the fast cadence near the
 * vehicle so arrival confirmation stays responsive; reduce native reads and
 * local relay traffic only after repeated, clearly-away observations.
 *
 * <p>The slow cadence remains inside the default departure sample window and
 * signal freshness lease. A missing RSSI uses the same bounded retry cadence.
 */
final class BluetoothProximitySamplingPolicy {
    static final long FAST_INTERVAL_MS = 1_000L;
    static final long LOW_ACTIVITY_INTERVAL_MS = 3_000L;
    static final int FAR_MARGIN_DB = 8;
    static final int FAR_CONFIRMING_SAMPLES = 2;

    private int consecutiveFarSamples;

    long nextIntervalMs(Integer rawRssi, int awayThreshold) {
        if (rawRssi == null) {
            consecutiveFarSamples = 0;
            return LOW_ACTIVITY_INTERVAL_MS;
        }
        int farCutoff = Math.max(-127, awayThreshold - FAR_MARGIN_DB);
        if (rawRssi <= farCutoff) {
            consecutiveFarSamples++;
        } else {
            // RSSI is noisy. One strong or transitional sample immediately
            // restores the fast cadence; two weak samples are required to slow.
            consecutiveFarSamples = 0;
        }
        return consecutiveFarSamples >= FAR_CONFIRMING_SAMPLES
                ? LOW_ACTIVITY_INTERVAL_MS : FAST_INTERVAL_MS;
    }

    void reset() {
        consecutiveFarSamples = 0;
    }
}

package com.overdrive.app.surveillance;

import com.overdrive.app.logging.DaemonLogger;

/** Applies explicit runtime bitrate changes to the current recording encoder. */
public class AdaptiveBitrateController {
    private static final String TAG = "AdaptiveBitrate";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private final HardwareEventRecorderGpu encoder;
    private int currentBitrate;
    // The constructor seed is not necessarily the encoder's actual bitrate.
    private boolean pushedOnce = false;

    /**
     * @param encoder Hardware encoder to control
     * @param initialBitrate Initial bitrate in bps (unverified until the first push)
     */
    public AdaptiveBitrateController(HardwareEventRecorderGpu encoder, int initialBitrate) {
        this.encoder = encoder;
        this.currentBitrate = initialBitrate;
    }

    /**
     * Sets the bitrate immediately, avoiding repeat writes after the first push.
     *
     * @param bitrate Bitrate in bps
     */
    public void setImmediateBitrate(int bitrate) {
        if (pushedOnce && bitrate == currentBitrate) {
            return;
        }

        encoder.setBitrate(bitrate);
        currentBitrate = bitrate;
        pushedOnce = true;

        logger.info("Bitrate set immediately: " + (bitrate / 1_000_000) + " Mbps");
    }
}

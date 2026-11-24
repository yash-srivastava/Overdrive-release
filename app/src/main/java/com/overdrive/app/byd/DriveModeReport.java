package com.overdrive.app.byd;

/** Decodes the base driving mode from a complete BYD vehicle-data callback. */
final class DriveModeReport {
    static final int UNRELATED = -2;
    static final int UNKNOWN = -1;

    private DriveModeReport() {}

    static int decode(byte[] frame) {
        // The six-byte header contains the big-endian report ID and two selectors.
        if (frame == null || frame.length < 6
                || frame[0] != 0 || frame[1] != 0 || frame[2] != 3
                || frame[3] != 0x42 || frame[4] != 0 || frame[5] != 0) {
            return UNRELATED;
        }
        if (frame.length < 14) return UNKNOWN;
        switch ((frame[8] & 0xff) >>> 4) {
            case 1: return 2; // Eco
            case 2: return 3; // Sport
            case 3:
            case 4: return 1; // Normal
            default: return UNKNOWN;
        }
    }
}

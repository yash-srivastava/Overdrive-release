package com.overdrive.app.daemon;

/**
 * Decides when AccSentryDaemon may replace the bodywork HAL snapshot with
 * AccMonitor's ACC-OFF state.
 */
final class AccPowerLevelPolicy {

    private AccPowerLevelPolicy() {}

    static boolean shouldOverrideBodyworkWithOff(
            boolean accStateAuthoritative,
            boolean accOn,
            boolean dilink5Platform) {
        return !accOn && (accStateAuthoritative || dilink5Platform);
    }
}

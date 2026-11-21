package com.overdrive.app.monitor;

/** Android WifiConfiguration.isMetered ordering, with unknown AP evidence refused. */
final class WifiMeteringPolicy {
    private WifiMeteringPolicy() {}

    static boolean isUnmetered(int ownerOverride, Boolean apHint, boolean savedHint) {
        // Explicit owner choice overrides both hints (including a metered hotspot).
        if (ownerOverride == 1) return false; // METERED_OVERRIDE_METERED
        if (ownerOverride == 2) return true;  // METERED_OVERRIDE_NOT_METERED
        if (ownerOverride != 0) return false;
        // Automatic: require positive evidence; a missing AP hint is not free data.
        return Boolean.FALSE.equals(apHint) && !savedHint;
    }
}

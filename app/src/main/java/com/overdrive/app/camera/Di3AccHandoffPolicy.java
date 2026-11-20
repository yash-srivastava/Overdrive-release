package com.overdrive.app.camera;

/** Limits the ACC-on retry controller to the legacy DiLink 3 path. */
final class Di3AccHandoffPolicy {
    static boolean eligible(boolean legacyOpen, boolean byd100f,
            boolean diLink4Selected, boolean diLink3HeadUnit) {
        return legacyOpen && !byd100f && !diLink4Selected && diLink3HeadUnit;
    }

    private Di3AccHandoffPolicy() { }
}

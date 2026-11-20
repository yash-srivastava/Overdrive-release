package com.overdrive.app.camera;

import java.util.Locale;

/** Positive firmware evidence is required to use the DiLink 3 ACC retry path. */
final class Di3AccHandoffPolicy {
    static boolean eligible(boolean legacyOpen, boolean byd100f,
            boolean diLink4Selected, String product, String device, String fingerprint) {
        if (!legacyOpen || byd100f || diLink4Selected) return false;
        int fingerprintVersion = fingerprintCore(fingerprint);
        if (fingerprintVersion != -1) return fingerprintVersion == 3;
        int productVersion = coreVersion(product);
        int deviceVersion = coreVersion(device);
        return (productVersion == 3 || deviceVersion == 3)
                && productVersion != 4 && deviceVersion != 4;
    }

    private static int fingerprintCore(String fingerprint) {
        if (fingerprint == null) return -1;
        String[] components = fingerprint.split("/", 4);
        if (components.length < 3) return -1;
        int productVersion = coreVersion(components[1]);
        int deviceVersion = coreVersion(components[2].split(":", 2)[0]);
        if (productVersion > 0 && deviceVersion > 0
                && productVersion != deviceVersion) return 0;
        return productVersion > 0 ? productVersion : deviceVersion;
    }

    private static int coreVersion(String value) {
        if (value == null) return -1;
        String name = value.toLowerCase(Locale.ROOT);
        if (name.startsWith("dilink3.")) return 3;
        if (name.startsWith("dilink4.")) return 4;
        if (name.startsWith("dilink5.")) return 5;
        return -1;
    }

    private Di3AccHandoffPolicy() { }
}

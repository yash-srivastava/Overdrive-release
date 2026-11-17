package com.overdrive.app.setup;

import java.util.Locale;

/**
 * Classifies the head unit from hardware evidence only; configured state is
 * never an input. Display-only: nothing here selects or writes a setting.
 */
public final class VehicleSetupDetector {

    public enum Platform { DILINK100, DILINK5, LEGACY, UNKNOWN }

    public enum Confidence { HIGH, LOW }

    public static final String MODE_CAMERA2 = "camera2";
    public static final String MODE_DILINK5 = "dilink5";

    private VehicleSetupDetector() {}

    public static final class Evidence {
        /** {@code ro.vehicle.type}; null when unreadable or empty. */
        public final String vehicleType;
        public final String buildModel;
        public final String buildProduct;
        public final String buildDevice;
        /** {@code /system/lib64/libais_test_util.so} is present. */
        public final boolean di5CameraLibrary;

        public Evidence(String vehicleType, String buildModel, String buildProduct,
                String buildDevice, boolean di5CameraLibrary) {
            this.vehicleType = trimToNull(vehicleType);
            this.buildModel = buildModel != null ? buildModel : "";
            this.buildProduct = buildProduct != null ? buildProduct : "";
            this.buildDevice = buildDevice != null ? buildDevice : "";
            this.di5CameraLibrary = di5CameraLibrary;
        }
    }

    public static final class Detection {
        public final Platform platform;
        public final Confidence confidence;
        /** Camera mode this head unit needs; null unless the platform is certain. */
        public final String recommendedCameraMode;
        /** Manifest model id named by the build identity, or null. */
        public final String modelHint;
        /** Firmware family reported by the Shark 6 and by other DiLink 5 models. */
        public final boolean sharedFirmwareFamily;

        Detection(Platform platform, Confidence confidence, String recommendedCameraMode,
                String modelHint, boolean sharedFirmwareFamily) {
            this.platform = platform;
            this.confidence = confidence;
            this.recommendedCameraMode = recommendedCameraMode;
            this.modelHint = modelHint;
            this.sharedFirmwareFamily = sharedFirmwareFamily;
        }
    }

    public static Detection detect(Evidence evidence) {
        // ro.vehicle.type can name only the UI version (a DiLink 3.0 Seal reports
        // "DiLink50_5.0UI"), so the core version in the build identity decides first.
        int core = coreVersion(evidence);
        if (core == 3 || core == 4) {
            return new Detection(Platform.LEGACY, Confidence.HIGH, null, null, false);
        }
        String type = evidence.vehicleType != null
                ? evidence.vehicleType.toLowerCase(Locale.ROOT) : null;
        if (type != null && type.startsWith("dilink100") && namesByd100f(evidence)) {
            // BYD100F units (Atto 3 EVO, Sealion 8, New E2) refuse every Camera2 client; Default
            // mode opens the 360 camera through BYD's AVMCamera instead (#741).
            return new Detection(Platform.DILINK100, Confidence.HIGH, VehicleSetupPlan.MODE_DEFAULT, null, false);
        }
        if (type != null && type.startsWith("dilink100")) {
            return new Detection(Platform.DILINK100, Confidence.HIGH, MODE_CAMERA2, null, false);
        }
        if (version(type, "di") == 5 || core == 5) {
            // Only the field-proven "Di5.0_" family earns a suggestion.
            boolean certain = evidence.di5CameraLibrary && type != null && type.startsWith("di5.0_");
            String hint = certain && namesShark(evidence) ? "shark" : null;
            boolean shared = hint == null && type != null && type.startsWith("di5.0_dxf");
            return new Detection(Platform.DILINK5,
                    certain ? Confidence.HIGH : Confidence.LOW,
                    certain ? MODE_DILINK5 : null, hint, shared);
        }
        // The DiLink 100 units carry the DiLink 5 library too, so it alone names no platform.
        if (evidence.di5CameraLibrary) {
            return new Detection(Platform.UNKNOWN, Confidence.LOW, null, null, false);
        }
        if (type == null) {
            return new Detection(Platform.LEGACY, Confidence.LOW, null, null, false);
        }
        int typeVersion = Math.max(version(type, "dilink"), version(type, "di"));
        boolean legacyType = typeVersion == 3 || typeVersion == 4;
        return legacyType
                ? new Detection(Platform.LEGACY, Confidence.HIGH, null, null, false)
                : new Detection(Platform.UNKNOWN, Confidence.LOW, null, null, false);
    }

    public enum Verdict { NONE, STORAGE_SPLIT, SERVICE_OFFLINE, SUGGEST, MATCH }

    /**
     * Card status. {@code configuredMode} is the mode the next camera-service start uses, so a
     * wrong one outranks a stopped service; a split storage root makes saved values unreliable.
     */
    public static Verdict verdict(boolean storageSplit, boolean serviceReachable,
            String recommendedMode, String configuredMode, String runningMode) {
        if (storageSplit) return Verdict.STORAGE_SPLIT;
        if (recommendedMode != null && !recommendedMode.equals(configuredMode)) {
            return Verdict.SUGGEST;
        }
        if (!serviceReachable) return Verdict.SERVICE_OFFLINE;
        if (recommendedMode == null) return Verdict.NONE;
        return recommendedMode.equals(runningMode) ? Verdict.MATCH : Verdict.NONE;
    }

    /**
     * Shark firmware locks the legacy root, so a stopped camera service there usually means
     * alternate storage is off. Never shown once the owner picked a non-Shark model.
     */
    public static boolean sharkStorageHint(Detection detection, String configuredModelId,
            boolean alternateStorage, boolean storageSplit, boolean serviceReachable) {
        if (detection.platform != Platform.DILINK5) return false;
        if (alternateStorage || storageSplit || serviceReachable) return false;
        if (configuredModelId != null) return "shark".equalsIgnoreCase(configuredModelId.trim());
        return detection.sharedFirmwareFamily || "shark".equals(detection.modelHint);
    }

    /** Same identity rule DiLink5CameraMapping.forPlatform uses for the Shark camera order. */
    private static boolean namesShark(Evidence evidence) {
        String identity = (evidence.buildModel + " " + evidence.buildProduct)
                .toLowerCase(Locale.ROOT);
        return identity.contains("shark") || identity.contains("dmo");
    }

    /** A DiLink 100 head unit whose build identity is BYD100F. */
    public static boolean isByd100f(Evidence evidence) {
        return evidence != null && detect(evidence).platform == Platform.DILINK100 && namesByd100f(evidence);
    }

    private static boolean namesByd100f(Evidence evidence) {
        String identity = (evidence.buildModel + " " + evidence.buildProduct + " " + evidence.buildDevice)
                .toLowerCase(Locale.ROOT);
        return identity.contains("byd100f");
    }

    /** Core version from the build identity ("DiLink3.0" → 3); -1 when it names none. */
    private static int coreVersion(Evidence evidence) {
        int product = version(evidence.buildProduct.toLowerCase(Locale.ROOT), "dilink");
        return product > 0 ? product : version(evidence.buildDevice.toLowerCase(Locale.ROOT), "dilink");
    }

    /** One-digit version right after {@code prefix} ("dilink3.0" → 3); -1 for tiers like "dilink50". */
    private static int version(String value, String prefix) {
        if (value == null || !value.startsWith(prefix)) return -1;
        int at = prefix.length();
        if (at >= value.length() || !isAsciiDigit(value.charAt(at))) return -1;
        if (at + 1 < value.length() && isAsciiDigit(value.charAt(at + 1))) return -1;
        return value.charAt(at) - '0';
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

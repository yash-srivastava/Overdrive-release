package com.overdrive.app.camera.dilink5;

import android.os.Build;

import com.overdrive.app.config.UnifiedConfigManager;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Selects which OEM cluster-projection transport this process drives.
 *
 * <p>Supported transports:
 * <ul>
 *   <li>{@link #LEGACY}: the DiLink 3/4 {@code IAutoContainer} opcode protocol
 *       (size profile → 16 → 35 open, 18 → 0 → profile close) owned by
 *       {@code ClusterProjectionController}.</li>
 *   <li>{@link #DILINK5}: the DiLink 5 container protocol (opcodes 0/16/18 via
 *       {@code service call}, XDJA container bootstrap, OEM shared projection
 *       display discovery) owned by {@code DiLink5ClusterCast}.</li>
 *   <li>{@link #AAOS_HDMI}: the Android 11 DX_BYD_AUTO's existing factory navigation
 *       presentation, using a hosted window on the external HDMI display.</li>
 * </ul>
 *
 * <p>Historically the transport followed the camera/vehicle mode selector
 * ({@link DiLink5Platform#isSelected()}), which is only ever {@code dilink5} on
 * units exposing the QCarCam camera library. DiLink 5.0 head units WITHOUT that
 * library (no {@code libais_test_util.so}) therefore stayed in {@code default}
 * mode and were driven with the DiLink 3/4 protocol, which their AutoContainer
 * never accepts — every cast failed and the legacy boot recovery wedged.
 *
 * <p>The Android 11 DX_BYD_AUTO uses its HDMI transport. For other products the
 * {@code dilink5} camera mode selects the DI5 transport. Remaining cases use:
 * <ol>
 *   <li>{@code projection.clusterTransport} in the unified config:
 *       {@code "legacy"} or {@code "dilink5"} force that transport.</li>
 *   <li>{@code "auto"} (default / absent): DI5 on DiLink 5.0 firmware
 *       (Build product / device / fingerprint identifies the DiLink 5 family),
 *       legacy everywhere else.</li>
 * </ol>
 * The decision is resolved once per process (like the mode marker) and logged
 * at daemon boot; changing the config key takes effect on the next camera
 * service restart.
 */
public final class ClusterTransport {
    public static final String CONFIG_SECTION = "projection";
    public static final String CONFIG_KEY = "clusterTransport";
    public static final String AUTO = "auto";
    public static final String LEGACY = "legacy";
    public static final String DILINK5 = "dilink5";
    /** The AAOS navigation presentation on the external HDMI display. */
    public static final String AAOS_HDMI = "aaos_hdmi";

    /** Why the current transport was chosen (for logs / status endpoint). */
    public static final String SOURCE_MODE = "mode";
    public static final String SOURCE_CONFIG = "config";
    public static final String SOURCE_FIRMWARE = "firmware";
    public static final String SOURCE_DEFAULT = "default";

    private static final String[] DILINK5_FIRMWARE_MARKERS = {
            "dilink5", "trinket", "d50f"
    };

    private static final Object LOCK = new Object();
    private static volatile Resolution resolvedForNonDiLink5Mode;

    private ClusterTransport() {}

    /** Immutable transport decision plus the reason it was taken. */
    public static final class Resolution {
        public final String transport;
        public final String source;
        public final String detail;

        Resolution(String transport, String source, String detail) {
            this.transport = transport;
            this.source = source;
            this.detail = detail == null ? "" : detail;
        }

        public boolean isDiLink5() {
            return DILINK5.equals(transport);
        }

        @Override
        public String toString() {
            return transport + " (" + source
                    + (detail.isEmpty() ? "" : ": " + detail) + ")";
        }
    }

    /**
     * True when cluster projection / cast / mirror must use the DI5 container
     * + OEM shared-display path. Cheap after the first call.
     */
    public static boolean isDiLink5() {
        return current().isDiLink5();
    }

    public static boolean isAaosHdmi() {
        return AAOS_HDMI.equals(current().transport);
    }

    /** Both non-legacy families use the same app/map session owner and cleanup journal. */
    public static boolean usesHostedProjectionService() {
        return !LEGACY.equals(current().transport);
    }

    /** {@link #LEGACY}, {@link #DILINK5}, or {@link #AAOS_HDMI}. */
    public static String currentTransport() {
        return current().transport;
    }

    public static Resolution current() {
        if (DiLink5Platform.isSelected() && !isAaosHdmiFirmware(
                Build.PRODUCT, Build.VERSION.SDK_INT)) {
            // The dilink5 camera mode is the explicit opt-in for the whole DI5
            // runtime; it can never be re-routed onto the legacy opcode path.
            return new Resolution(DILINK5, SOURCE_MODE, "camera mode dilink5");
        }
        Resolution cached = resolvedForNonDiLink5Mode;
        if (cached != null) return cached;
        synchronized (LOCK) {
            cached = resolvedForNonDiLink5Mode;
            if (cached == null) {
                cached = resolveFromEnvironment();
                resolvedForNonDiLink5Mode = cached;
            }
            return cached;
        }
    }

    /**
     * Re-read the config override and firmware identity. Called once by the
     * camera daemon after the vehicle mode has been committed so the boot log
     * carries the decision that the rest of the process will use.
     */
    public static Resolution refresh() {
        synchronized (LOCK) {
            Resolution fresh = resolveFromEnvironment();
            resolvedForNonDiLink5Mode = fresh;
        }
        return current();
    }

    private static Resolution resolveFromEnvironment() {
        String configured = readConfiguredTransport();
        return resolve(
                configured,
                false,
                Build.VERSION.SDK_INT,
                readBuildField("PRODUCT"),
                readBuildField("DEVICE"),
                readBuildField("FINGERPRINT"));
    }

    /**
     * Pure decision function (unit-tested).
     *
     * @param configured raw {@code projection.clusterTransport} value or null
     * @param diLink5ModeSelected whether the camera mode is {@code dilink5}
     */
    static Resolution resolve(
            String configured,
            boolean diLink5ModeSelected,
            int sdk,
            String product,
            String device,
            String fingerprint) {
        if (isAaosHdmiFirmware(product, sdk)) {
            return new Resolution(AAOS_HDMI, SOURCE_FIRMWARE, "Android 11 DX_BYD_AUTO");
        }
        if (diLink5ModeSelected) {
            return new Resolution(DILINK5, SOURCE_MODE, "camera mode dilink5");
        }
        String normalized = normalizeConfigured(configured);
        if (LEGACY.equals(normalized)) {
            return new Resolution(LEGACY, SOURCE_CONFIG,
                    CONFIG_SECTION + "." + CONFIG_KEY + "=legacy");
        }
        if (DILINK5.equals(normalized)) {
            return new Resolution(DILINK5, SOURCE_CONFIG,
                    CONFIG_SECTION + "." + CONFIG_KEY + "=dilink5");
        }
        String marker = diLink5FirmwareMarker(product, device, fingerprint);
        if (marker != null) {
            return new Resolution(DILINK5, SOURCE_FIRMWARE,
                    "DiLink 5 firmware identity '" + marker + "'");
        }
        return new Resolution(LEGACY, SOURCE_DEFAULT,
                "no DiLink 5 firmware identity");
    }

    /** The display and factory navigation presentation are checked separately for every cast. */
    public static boolean isAaosHdmiFirmware(String product, int sdk) {
        return sdk == Build.VERSION_CODES.R && "DX_BYD_AUTO".equalsIgnoreCase(product);
    }

    /** True when the build identity belongs to the DiLink 5 firmware family. */
    static boolean isDiLink5Firmware(
            String product, String device, String fingerprint) {
        return diLink5FirmwareMarker(product, device, fingerprint) != null;
    }

    private static String diLink5FirmwareMarker(
            String product, String device, String fingerprint) {
        for (String value : new String[] {product, device, fingerprint}) {
            if (value == null) continue;
            String normalized = value.toLowerCase(Locale.US);
            for (String marker : DILINK5_FIRMWARE_MARKERS) {
                if (normalized.contains(marker)) return marker;
            }
        }
        return null;
    }

    /** {@link #AUTO}, {@link #LEGACY}, {@link #DILINK5}, or null when unrecognized. */
    public static String normalizeConfigured(String raw) {
        if (raw == null) return null;
        String value = raw.trim().toLowerCase(Locale.US);
        if (value.isEmpty()) return null;
        switch (value) {
            case AUTO:
            case LEGACY:
            case DILINK5:
                return value;
            default:
                return null;
        }
    }

    private static String readConfiguredTransport() {
        try {
            JSONObject root = UnifiedConfigManager.loadConfig();
            JSONObject section = root == null
                    ? null
                    : root.optJSONObject(CONFIG_SECTION);
            if (section == null || !section.has(CONFIG_KEY)
                    || section.isNull(CONFIG_KEY)) {
                return null;
            }
            return section.optString(CONFIG_KEY, "");
        } catch (Throwable ignored) {
            // A transient config failure must never change the transport
            // silently; fall back to firmware detection (the auto default).
            return null;
        }
    }

    private static String readBuildField(String name) {
        try {
            Object value = android.os.Build.class.getField(name).get(null);
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}

package com.overdrive.app.surveillance;

import java.util.Locale;

/**
 * Which engine builds the recording grid: the GPU mosaic shader, or the CPU
 * copy straight into the encoder's YUV input (camera.recordingEngine).
 * Unset = platform default: CPU on DiLink 3 head units, GPU elsewhere.
 */
public final class RecordingEngine {

    public static final String CONFIG_KEY = "recordingEngine";
    // "<why>@<versionCode>": a failure that keeps this build on the GPU across restarts.
    public static final String BLOCK_KEY = "recordingEngineBlock";
    public static final String CPU = "cpu";
    public static final String GPU = "gpu";

    // Why recording stays on the GPU (reported to the Camera probe dialog).
    public static final String WHY_GPU_SELECTED = "gpu_selected";
    public static final String WHY_CAMERA_MODE = "camera_mode";
    public static final String WHY_DECOUPLED_LANE = "decoupled_lane";
    public static final String WHY_NATIVE_MISSING = "native_missing";
    public static final String WHY_CODEC_REJECTED = "codec_rejected";
    public static final String WHY_FRAMES_UNREADABLE = "frames_unreadable";
    public static final String WHY_RESTART_PENDING = "restart_pending";
    // Car off: sentry records on the GPU so its AI keeps the CPU.
    public static final String WHY_ACC_OFF = "acc_off";

    // A runtime failure keeps this process on the GPU until the daemon restarts.
    private static volatile String sessionBlock;

    // Set by the pipeline before it builds the camera: true when the CPU engine
    // can run on it (car on). The CPU engine never stalls the camera on the
    // encoder, so the decoupled lane steps aside, and the camera keeps a
    // CPU-readable reader for both ACC states.
    private static volatile boolean cpuEngineEnabled;

    private RecordingEngine() {}

    /** "cpu", "gpu", or "" when unset. */
    public static String configured() {
        try {
            org.json.JSONObject cam = com.overdrive.app.config.UnifiedConfigManager
                .loadConfig().optJSONObject("camera");
            return normalize(cam != null ? cam.optString(CONFIG_KEY, "") : "");
        } catch (Throwable t) {
            return "";
        }
    }

    public static boolean defaultIsCpu() {
        try {
            return isDiLink3HeadUnit(android.os.Build.PRODUCT, android.os.Build.DEVICE);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean prefersCpu() {
        return prefersCpu(configured(), defaultIsCpu());
    }

    static boolean prefersCpu(String configured, boolean defaultCpu) {
        String v = normalize(configured);
        if (CPU.equals(v)) return true;
        if (GPU.equals(v)) return false;
        return defaultCpu;
    }

    public static String normalize(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return CPU.equals(v) || GPU.equals(v) ? v : "";
    }

    /** Core version from the build identity ("DiLink3.0"), product first, then device. */
    public static boolean isDiLink3HeadUnit(String product, String device) {
        int v = coreVersion(product);
        if (v <= 0) v = coreVersion(device);
        return v == 3;
    }

    private static int coreVersion(String value) {
        if (value == null) return -1;
        String s = value.toLowerCase(Locale.ROOT);
        int at = "dilink".length();
        if (!s.startsWith("dilink") || at >= s.length()) return -1;
        char c = s.charAt(at);
        if (c < '0' || c > '9') return -1;
        if (at + 1 < s.length() && Character.isDigit(s.charAt(at + 1))) return -1;
        return c - '0';
    }

    public static boolean isCpuEngineEnabled() {
        return cpuEngineEnabled;
    }

    static void setCpuEngineEnabled(boolean enabled) {
        cpuEngineEnabled = enabled;
    }

    static void blockForSession(String why) {
        if (why != null) sessionBlock = why;
    }

    static String sessionBlock() {
        String s = sessionBlock;
        return s != null ? s : persistedBlock();
    }

    /** Stored failure for this app build, or null (an update retries the CPU). */
    static String persistedBlock() {
        try {
            org.json.JSONObject cam = com.overdrive.app.config.UnifiedConfigManager
                .loadConfig().optJSONObject("camera");
            String v = cam != null ? cam.optString(BLOCK_KEY, "") : "";
            int at = v.lastIndexOf('@');
            if (at <= 0) return null;
            return v.substring(at + 1).equals(String.valueOf(com.overdrive.app.BuildConfig.VERSION_CODE))
                ? v.substring(0, at) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public static String blockEntry(String why) {
        return why + "@" + com.overdrive.app.BuildConfig.VERSION_CODE;
    }
}

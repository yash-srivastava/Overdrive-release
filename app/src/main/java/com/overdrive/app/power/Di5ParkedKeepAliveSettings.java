package com.overdrive.app.power;

import org.json.JSONObject;

/**
 * Immutable snapshot of the DiLink 5 parked keep-alive settings.
 *
 * <p>Parsed from the flat {@code surveillance.*} keys seeded by
 * {@code UnifiedConfigManager.applyDefaults} (flat, not nested, because
 * {@code updateValues} replaces whole top-level keys). Only {@link #enabled}
 * is a user-visible setting; every lever below it is a config-only diagnostic
 * and is effective ONLY while the master switch is on and the runtime kill
 * switch ({@code persist.overdrive.di5_keepalive=0}) is not set.
 *
 * <p>Pure Java, no Android dependencies, so the parsing and gating rules are
 * unit-testable on the JVM.
 */
public final class Di5ParkedKeepAliveSettings {

    /** Runtime kill switch property; value {@code 0} forces the feature off. */
    public static final String KILL_SWITCH_PROPERTY = "persist.overdrive.di5_keepalive";

    public static final String KEY_ENABLED = "di5ParkedKeepAlive";
    public static final String KEY_PARKED_LINK =
            "di5ParkedKeepAliveLink";
    public static final String KEY_MCU_HOLD = "di5ParkedKeepAliveMcuHold";
    public static final String KEY_MCU_POWER_HOLD = "di5ParkedKeepAliveMcuPowerHold";
    public static final String KEY_CAMERA_HEARTBEAT = "di5ParkedKeepAliveCameraHeartbeat";
    public static final String KEY_AP_HOLD = "di5ParkedKeepAliveApHold";
    public static final String KEY_AP_HOLD_PREFLIGHT_PASSED =
            "di5ParkedKeepAliveApHoldPreflightPassed";
    public static final String KEY_REASSERT_SECONDS = "di5ParkedKeepAliveReassertSeconds";
    public static final String KEY_CUTOFF_VOLTAGE = "di5ParkedKeepAliveCutoffVoltage";
    public static final String KEY_CUTOFF_SAMPLES = "di5ParkedKeepAliveCutoffSamples";
    public static final String KEY_VOLTAGE_MAX_AGE_SECONDS =
            "di5ParkedKeepAliveVoltageMaxAgeSeconds";

    // Defaults mirror UnifiedConfigManager.applyDefaults.
    public static final boolean DEFAULT_ENABLED = false;
    public static final boolean DEFAULT_PARKED_LINK = true;
    public static final boolean DEFAULT_MCU_HOLD = true;
    public static final boolean DEFAULT_MCU_POWER_HOLD = true;  // OEM-app parity (V1 default)
    public static final boolean DEFAULT_CAMERA_HEARTBEAT = false;
    public static final boolean DEFAULT_AP_HOLD = false;
    public static final int DEFAULT_REASSERT_SECONDS = 30;      // reference mode-3 parity
    public static final double DEFAULT_CUTOFF_VOLTAGE = 11.8;
    public static final int DEFAULT_CUTOFF_SAMPLES = 3;
    public static final int DEFAULT_VOLTAGE_MAX_AGE_SECONDS = 120;

    // Clamps keep a hand-edited config from producing a runaway cadence or a
    // cutoff that could never trigger.
    static final int MIN_REASSERT_SECONDS = 5;
    static final int MAX_REASSERT_SECONDS = 600;
    static final double MIN_CUTOFF_VOLTAGE = 10.5;
    static final double MAX_CUTOFF_VOLTAGE = 12.5;
    static final int MIN_CUTOFF_SAMPLES = 1;
    static final int MAX_CUTOFF_SAMPLES = 10;
    static final int MIN_VOLTAGE_MAX_AGE_SECONDS = 30;
    static final int MAX_VOLTAGE_MAX_AGE_SECONDS = 600;

    /** Master switch as persisted (before the kill switch is applied). */
    public final boolean enabledInConfig;
    /** Kill switch observed at snapshot time. */
    public final boolean killSwitchActive;
    public final boolean parkedLinkInConfig;
    public final boolean mcuHoldInConfig;
    public final boolean mcuPowerHoldInConfig;
    public final boolean cameraHeartbeatInConfig;
    public final boolean apHoldInConfig;
    public final boolean apHoldPreflightPassed;
    public final int reassertSeconds;
    public final double cutoffVoltage;
    public final int cutoffSamples;
    public final int voltageMaxAgeSeconds;

    private Di5ParkedKeepAliveSettings(
            boolean enabledInConfig,
            boolean killSwitchActive,
            boolean parkedLinkInConfig,
            boolean mcuHoldInConfig,
            boolean mcuPowerHoldInConfig,
            boolean cameraHeartbeatInConfig,
            boolean apHoldInConfig,
            boolean apHoldPreflightPassed,
            int reassertSeconds,
            double cutoffVoltage,
            int cutoffSamples,
            int voltageMaxAgeSeconds) {
        this.enabledInConfig = enabledInConfig;
        this.killSwitchActive = killSwitchActive;
        this.parkedLinkInConfig = parkedLinkInConfig;
        this.mcuHoldInConfig = mcuHoldInConfig;
        this.mcuPowerHoldInConfig = mcuPowerHoldInConfig;
        this.cameraHeartbeatInConfig = cameraHeartbeatInConfig;
        this.apHoldInConfig = apHoldInConfig;
        this.apHoldPreflightPassed = apHoldPreflightPassed;
        this.reassertSeconds = reassertSeconds;
        this.cutoffVoltage = cutoffVoltage;
        this.cutoffSamples = cutoffSamples;
        this.voltageMaxAgeSeconds = voltageMaxAgeSeconds;
    }

    /** All-defaults snapshot: feature off, nothing effective. */
    public static Di5ParkedKeepAliveSettings disabled() {
        return fromSurveillance(null, false);
    }

    /**
     * Parse from the {@code surveillance} section. A {@code null} section or any
     * missing/malformed key falls back to the seeded default, so a partial config
     * can never widen the write set.
     *
     * @param surveillance     the {@code surveillance} JSON section (may be null)
     * @param killSwitchActive true when {@code persist.overdrive.di5_keepalive} reads "0"
     */
    public static Di5ParkedKeepAliveSettings fromSurveillance(
            JSONObject surveillance, boolean killSwitchActive) {
        JSONObject s = surveillance == null ? new JSONObject() : surveillance;
        return new Di5ParkedKeepAliveSettings(
                optBoolean(s, KEY_ENABLED, DEFAULT_ENABLED),
                killSwitchActive,
                optBoolean(s, KEY_PARKED_LINK,
                        optBoolean(s, ParkedLinkContract.LEGACY_CONFIG_KEY,
                                DEFAULT_PARKED_LINK)),
                optBoolean(s, KEY_MCU_HOLD, DEFAULT_MCU_HOLD),
                optBoolean(s, KEY_MCU_POWER_HOLD, DEFAULT_MCU_POWER_HOLD),
                optBoolean(s, KEY_CAMERA_HEARTBEAT, DEFAULT_CAMERA_HEARTBEAT),
                optBoolean(s, KEY_AP_HOLD, DEFAULT_AP_HOLD),
                optBoolean(s, KEY_AP_HOLD_PREFLIGHT_PASSED, false),
                clamp(optInt(s, KEY_REASSERT_SECONDS, DEFAULT_REASSERT_SECONDS),
                        MIN_REASSERT_SECONDS, MAX_REASSERT_SECONDS),
                clamp(optDouble(s, KEY_CUTOFF_VOLTAGE, DEFAULT_CUTOFF_VOLTAGE),
                        MIN_CUTOFF_VOLTAGE, MAX_CUTOFF_VOLTAGE),
                clamp(optInt(s, KEY_CUTOFF_SAMPLES, DEFAULT_CUTOFF_SAMPLES),
                        MIN_CUTOFF_SAMPLES, MAX_CUTOFF_SAMPLES),
                clamp(optInt(s, KEY_VOLTAGE_MAX_AGE_SECONDS, DEFAULT_VOLTAGE_MAX_AGE_SECONDS),
                        MIN_VOLTAGE_MAX_AGE_SECONDS, MAX_VOLTAGE_MAX_AGE_SECONDS));
    }

    /** True when the kill-switch property value means "force off". */
    public static boolean isKillSwitchValue(String propertyValue) {
        if (propertyValue == null) return false;
        String v = propertyValue.trim();
        return v.equals("0") || v.equalsIgnoreCase("false") || v.equalsIgnoreCase("off");
    }

    /** Master switch, after the kill switch. Nothing is effective when false. */
    public boolean isEnabled() {
        return enabledInConfig && !killSwitchActive;
    }

    /** Optional local transport, independent of cloud connectivity. */
    public boolean isParkedLinkEffective() {
        return isEnabled() && parkedLinkInConfig;
    }

    /** Seed the renamed diagnostic key without erasing an existing opt-out. */
    public static void ensureLinkConfig(JSONObject surveillance) {
        if (surveillance == null || surveillance.has(KEY_PARKED_LINK)) return;
        try {
            surveillance.put(KEY_PARKED_LINK,
                    optBoolean(surveillance, ParkedLinkContract.LEGACY_CONFIG_KEY,
                            DEFAULT_PARKED_LINK));
        } catch (Exception ignored) {
        }
    }

    /** Sentry flags + bounded MCU wake. */
    public boolean isMcuHoldEffective() {
        return isEnabled() && mcuHoldInConfig;
    }

    /**
     * BYDAutoPowerDevice MCU power hold ({@code -1442840502 ← 1}), written
     * together with the sentry flags. OEM-app parity: its default (V1) mode
     * writes exactly this, unconditionally, on ACC OFF. Rides the MCU-hold
     * lever; {@link #KEY_MCU_POWER_HOLD} is the escape hatch for a unit where
     * the extra write misbehaves.
     */
    public boolean isMcuPowerHoldEffective() {
        return isMcuHoldEffective() && mcuPowerHoldInConfig;
    }

    /** PANORAMA_WORK_MODE_SET heartbeat. Off until the on-car T4 check. */
    public boolean isCameraHeartbeatEffective() {
        return isEnabled() && cameraHeartbeatInConfig;
    }

    /**
     * vendor.peripheral shutdown-critical token. Experimental: requires BOTH the
     * lever and a recorded pre-flight pass — the reference app only issues this in its hidden
     * Debug Mode 2, so it is unproven and stays capability-gated.
     */
    public boolean isApHoldEffective() {
        return isEnabled() && apHoldInConfig && apHoldPreflightPassed;
    }

    /** True when at least one lever would actually do something. */
    public boolean hasAnyEffectiveLever() {
        return isParkedLinkEffective()
                || isMcuHoldEffective()
                || isCameraHeartbeatEffective()
                || isApHoldEffective();
    }

    public long reassertMillis() {
        return reassertSeconds * 1000L;
    }

    public long voltageMaxAgeMillis() {
        return voltageMaxAgeSeconds * 1000L;
    }

    /** Diagnostic snapshot for the status file / API. */
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("enabled", isEnabled());
            o.put("enabledInConfig", enabledInConfig);
            o.put("killSwitchActive", killSwitchActive);
            o.put("parkedLink",
                    isParkedLinkEffective());
            // Read-only compatibility for existing diagnostic consumers.
            o.put(ParkedLinkContract.LEGACY_SETTINGS_STATUS_KEY,
                    isParkedLinkEffective());
            o.put("mcuHold", isMcuHoldEffective());
            o.put("mcuPowerHold", isMcuPowerHoldEffective());
            o.put("cameraHeartbeat", isCameraHeartbeatEffective());
            o.put("apHold", isApHoldEffective());
            o.put("apHoldInConfig", apHoldInConfig);
            o.put("apHoldPreflightPassed", apHoldPreflightPassed);
            o.put("reassertSeconds", reassertSeconds);
            o.put("cutoffVoltage", cutoffVoltage);
            o.put("cutoffSamples", cutoffSamples);
            o.put("voltageMaxAgeSeconds", voltageMaxAgeSeconds);
        } catch (Exception ignored) {
            // JSONObject.put only throws for NaN/infinite numbers, which the
            // clamps above exclude.
        }
        return o;
    }

    @Override
    public String toString() {
        return toJson().toString();
    }

    private static boolean optBoolean(JSONObject o, String key, boolean fallback) {
        try {
            return o.optBoolean(key, fallback);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static int optInt(JSONObject o, String key, int fallback) {
        try {
            return o.optInt(key, fallback);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static double optDouble(JSONObject o, String key, double fallback) {
        try {
            double v = o.optDouble(key, fallback);
            return Double.isNaN(v) || Double.isInfinite(v) ? fallback : v;
        } catch (Throwable t) {
            return fallback;
        }
    }

    static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    static double clamp(double v, double min, double max) {
        return v < min ? min : (v > max ? max : v);
    }
}

package com.overdrive.app.byd;

/**
 * All BYD feature IDs decoded from the DiLink APK.
 * Used with AbsBYDAutoDevice.get(int[], Class) for generic property reads.
 * 
 * Naming: DEVICE_PROPERTY with fallback numeric value in comment.
 */
public final class BydFeatureIds {

    // ==================== BODYWORK ====================
    /** Battery metric from bodywork (doubleValue, no transform) */
    public static final int BODYWORK_BATTERY_METRIC = 300941320;
    /** Battery range from bodywork (intValue, valid 0-1016 km) */
    public static final int BODYWORK_BATTERY_RANGE = 300941336;
    /** Emergency alarm state */
    public static final int BODYWORK_EMERGENCY_ALARM = resolveOrFallback(
        "BODYWORK_EMERGENCY_ALARM_STATE", 692060190);

    // ==================== ENGINE ====================
    /** Engine power kW */
    public static final int ENGINE_POWER = resolveOrFallback("ENGINE_POWER", 339738656);
    /** Front motor speed (int, negated) */
    public static final int ENGINE_FRONT_MOTOR_SPEED = resolveOrFallback(
        "ENGINE_FRONT_MOTOR_SPEED", 1141899272);
    /** Rear motor speed (int) */
    public static final int ENGINE_REAR_MOTOR_SPEED = resolveOrFallback(
        "ENGINE_REAR_MOTOR_SPEED", 621805576);
    /** Front motor torque (double, negated) */
    public static final int ENGINE_FRONT_MOTOR_TORQUE = resolveOrFallback(
        "ENGINE_FRONT_MOTOR_TORQUE", 1141899288);
    /** Alternative engine speed signal */
    public static final int ENGINE_SPEED_ALT = 282066952;

    // ==================== STATISTIC (BATTERY HEALTH) ====================
    /** Highest battery cell temp (intValue - 40 = °C) */
    public static final int STAT_HIGHEST_BATTERY_TEMP = resolveOrFallback(
        "STATISTIC_HIGHEST_BATTERY_TEMP", 1148190752);
    /** Average battery pack temp (intValue - 40 = °C) */
    public static final int STAT_AVERAGE_BATTERY_TEMP = resolveOrFallback(
        "STATISTIC_AVERAGE_BATTERY_TEMP", 1148190776);
    /** Lowest battery cell temp (intValue - 40 = °C) */
    public static final int STAT_LOWEST_BATTERY_TEMP = resolveOrFallback(
        "STATISTIC_LOWEST_BATTERY_TEMP", 1148190736);
    /** Highest cell voltage (intValue / 1000.0 = V) */
    public static final int STAT_HIGHEST_BATTERY_VOLTAGE = resolveOrFallback(
        "STATISTIC_HIGHEST_BATTERY_VOLTAGE", 1147142192);
    /** Lowest cell voltage (intValue / 1000.0 = V) */
    public static final int STAT_LOWEST_BATTERY_VOLTAGE = resolveOrFallback(
        "STATISTIC_LOWEST_BATTERY_VOLTAGE", 1147142160);

    // ==================== SETTING / ADAS ====================
    public static final int SETTING_LANE_CURVATURE = resolveOrFallback(
        "Setting.SET_LANE_LINE_CURVATURE", 883949604);
    public static final int SETTING_RAIN_WIPER_SPEED = resolveOrFallback(
        "Setting.SETTING_FRONT_RAIN_WIPER_SPEED", 1196425250);

    // ==================== AC ====================
    /** Inside cabin temperature */
    public static final int AC_TEMP_INSIDE = resolveOrFallback("Ac.AC_TEMP_INSIDE", 1031798832);

    // ==================== SENTINEL VALUES ====================
    /** BYD SDK returns this when no SDK is present */
    public static final double SDK_NOT_AVAILABLE = -2.147482624E9d;
    /** BMS sleeping / data unavailable */
    public static final int BMS_UNAVAILABLE = -10011;
    /** Generic invalid value */
    public static final int INVALID_VALUE = -2147482645;
    /** Invalid value from get(int[], Class) signature */
    public static final int INVALID_VALUE_2 = -2147482648;

    /**
     * Try to resolve a feature ID from the real BYDAutoFeatureIds class on the device.
     * Falls back to the numeric literal if the field doesn't exist.
     */
    private static int resolveOrFallback(String fieldName, int fallback) {
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds");
            // Handle nested class names like "Setting.SET_LANE_LINE_CURVATURE"
            if (fieldName.contains(".")) {
                String[] parts = fieldName.split("\\.");
                for (Class<?> inner : cls.getDeclaredClasses()) {
                    if (inner.getSimpleName().equals(parts[0])) {
                        return inner.getField(parts[1]).getInt(null);
                    }
                }
            } else {
                return cls.getField(fieldName).getInt(null);
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    private BydFeatureIds() {}
}

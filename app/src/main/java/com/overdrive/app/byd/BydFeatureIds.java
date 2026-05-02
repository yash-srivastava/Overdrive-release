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

    // ==========================================================================
    // NEW CONSTANTS FROM DECOMPILED BYDAutoFeatureIds NESTED CLASSES
    // Each uses resolveOrFallback("NestedClass.FIELD_NAME", numericLiteral)
    // ==========================================================================

    // ==================== SETTING ====================
    public static final int SETTING_ITAC_CONFIG = resolveOrFallback("Setting.SET_ITAC_CONFIG", 656408614);
    public static final int SETTING_ITAC_STATE = resolveOrFallback("Setting.SET_ITAC_STATE", 656408615);
    public static final int SETTING_ITAC_STATE_SET = resolveOrFallback("Setting.SET_ITAC_STATE_SET", 1324376094);
    public static final int SETTING_ITAC_INTELLIGENT_TORQUE_CONTROL_FLAG = resolveOrFallback("Setting.SET_ITAC_INTELLIGENT_TORQUE_CONTROL_FLAG", 656408620);
    public static final int SETTING_LEFT_REAR_DOOR_CHILD_LOCK_STATUS = resolveOrFallback("Setting.LEFT_REAR_DOOR_CHILD_LOCK_STATUS", 957350032);
    public static final int SETTING_RIGHT_REAR_DOOR_CHILD_LOCK_STATUS = resolveOrFallback("Setting.RIGHT_REAR_DOOR_CHILD_LOCK_STATUS", 957350034);
    public static final int SETTING_IAL_COLOR_CONFIG = resolveOrFallback("Setting.SET_IAL_COLOR_CONFIG", 1072693258);
    public static final int SETTING_LF_MEMORY_LOCATION_SET = resolveOrFallback("Setting.SET_LF_MEMORY_LOCATION_SET", 1276186678);
    public static final int SETTING_LF_MEMORY_LOCATION_WAKE_SET = resolveOrFallback("Setting.SET_LF_MEMORY_LOCATION_WAKE_SET", 1276186686);
    public static final int SETTING_RF_MEMORY_LOCATION_SET = resolveOrFallback("Setting.SET_RF_MEMORY_LOCATION_SET", 1276186680);
    public static final int SETTING_RF_MEMORY_LOCATION_WAKE_SET = resolveOrFallback("Setting.SET_RF_MEMORY_LOCATION_WAKE_SET", 1276186688);
    public static final int SETTING_PAD_ROTATION_SET = resolveOrFallback("Setting.SET_PAD_ROTATION_SET", 1330643005);
    public static final int SETTING_BRIGHTNESS_GEAR_SET = resolveOrFallback("Setting.SET_BRIGHTNESS_GEAR_SET", 1276174360);
    public static final int SETTING_VOICE_CTRL_BACK_DOOR_SET = resolveOrFallback("Setting.SET_VOICE_CTRL_BACK_DOOR_SET", 1125122080);
    public static final int SETTING_DRAG_MODE_CURRENT_STATE = resolveOrFallback("Setting.SETTING_DRAG_MODE_CURRENT_STATE", 875560989);
    public static final int SETTING_TARGET_DRIVING_MODE = resolveOrFallback("Setting.SETTING_TARGET_DRIVING_MODE", 1272971280);
    public static final int SETTING_TARGET_DRIVING_MODE_ALT = resolveOrFallback("Setting.SETTING_TARGET_DRIVING_MODE_ALT", 255852712);

    // ==================== BODY ====================
    public static final int BODY_ATMOSPHERE_LIGHT_SWITCH = resolveOrFallback("Body.ATMOSPHERE_LIGHT_SWITCH_EXECUTE", 489701406);
    public static final int BODY_ATMOSPHERE_LIGHT_MUSIC = resolveOrFallback("Body.ATMOSPHERE_LIGHT_MUSIC_EXECUTE", 489701407);
    public static final int BODY_SMART_ENTRY_BLUETOOTH_STATUS = resolveOrFallback("Body.SMART_ENTRY_BLUETOOTH_STATUS", 602931221);
    public static final int BODY_BACK_DOOR_TRIGGER = resolveOrFallback("Body.BACK_DOOR_TRIGGER_ATOM", 489689126);
    public static final int BODY_BACK_DOOR_STATUS = resolveOrFallback("Body.BACK_DOOR_OPEN_CLOSE_STATUS", 365953067);
    public static final int BODY_BACK_DOOR_OPENING_STATUS = resolveOrFallback("Body.BACK_DOOR_OPENING_STATUS_FEEDBACK", 365953060);
    public static final int BODY_BACK_DOOR_OPEN_CONFIRM = resolveOrFallback("Body.BACK_DOOR_OPEN_STATUS_CONFIRM", 654311456);
    public static final int BODY_BACK_DOOR_MAINTENANCE = resolveOrFallback("Body.BACK_DOOR_MAINTENANCE_STATUS", 654311438);
    public static final int BODY_BACK_DOOR_POSITION = resolveOrFallback("Body.BACK_DOOR_POSITION_FEEDBACK", 365953048);
    public static final int BODY_BACK_DOOR_ACTUATOR_COMMAND = resolveOrFallback("Body.BACK_DOOR_ACTUATOR_COMMAND", 1125122080);
    public static final int BODY_INSIDE_LIGHT_STATE_SET = resolveOrFallback("Body.INSIDE_LIGHT_STATE_SET", 1330643002);
    public static final int BODY_SUNSHADE_PANEL_PERCENT = resolveOrFallback("Body.BODYWORK_SUNSHADE_PANEL_PERCENT", 1101004816);
    public static final int BODY_SUNSHADE_PANEL_PERCENT_SET = resolveOrFallback("Body.BODYWORK_SUNSHADE_PANEL_PERCENT_SET", 1330642984);
    public static final int BODY_LF_DOOR_STATUS = resolveOrFallback("Body.LF_DOOR_STATUS_FLAG", 692060176);
    public static final int BODY_RF_DOOR_STATUS = resolveOrFallback("Body.RF_DOOR_STATUS_FLAG", 692060177);
    public static final int BODY_LR_DOOR_STATUS = resolveOrFallback("Body.LR_DOOR_STATUS_FLAG", 692060178);
    public static final int BODY_RR_DOOR_STATUS = resolveOrFallback("Body.RR_DOOR_STATUS_FLAG", 692060179);

    // ==================== LIGHT ====================
    public static final int LIGHT_HIGH_BEAM = resolveOrFallback("Light.LIGHT_HIGH_BEAM_LIGHT", 950009868);
    public static final int LIGHT_LOW_BEAM = resolveOrFallback("Light.LIGHT_LOW_BEAM_LIGHT", 950009866);
    public static final int LIGHT_PIXEL_HEADLIGHT_ALS_STATE = resolveOrFallback("Light.LIGHT_PIXEL_HEADLIGHT_ALS_STATE", 984612932);
    public static final int LIGHT_AMBIENT_FRONT_BRIGHTNESS = resolveOrFallback("Light.AMBIENT_FRONT_BRIGHTNESS", 1121976328);
    public static final int LIGHT_AMBIENT_FRONT_BRIGHTNESS_ALT = resolveOrFallback("Light.AMBIENT_FRONT_BRIGHTNESS_ALT", 521142688);
    public static final int LIGHT_AMBIENT_REAR_BRIGHTNESS = resolveOrFallback("Light.AMBIENT_REAR_BRIGHTNESS", 1121976332);
    public static final int LIGHT_AMBIENT_REAR_BRIGHTNESS_ALT = resolveOrFallback("Light.AMBIENT_REAR_BRIGHTNESS_ALT", 521142696);
    public static final int LIGHT_AMBIENT_FRONT_COLOR = resolveOrFallback("Light.AMBIENT_FRONT_COLOR", 1121976336);
    public static final int LIGHT_AMBIENT_REAR_COLOR = resolveOrFallback("Light.AMBIENT_REAR_COLOR", 1121976343);
    public static final int LIGHT_ATMOSPHERE_MAIN_SWITCH_STATUS = resolveOrFallback("Light.ATMOSPHERE_MAIN_SWITCH_STATUS", 1060110406);
    public static final int LIGHT_ATMOSPHERE_MAIN_SWITCH_SET = resolveOrFallback("Light.ATMOSPHERE_MAIN_SWITCH_SET", 1276153924);
    public static final int LIGHT_ATMOSPHERE_ADJUST_AREA_SET = resolveOrFallback("Light.ATMOSPHERE_ADJUST_AREA_SET", 1896873992);
    public static final int LIGHT_ATMOSPHERE_VEHICLE_BRIGHTNESS_SET = resolveOrFallback("Light.ATMOSPHERE_VEHICLE_BRIGHTNESS_SET", 1896874032);
    public static final int LIGHT_ATMOSPHERE_CUSTOM_BRIGHTNESS = resolveOrFallback("Light.ATMOSPHERE_CUSTOM_BRIGHTNESS", 657457175);
    public static final int LIGHT_ATMOSPHERE_CUSTOM_BRIGHTNESS_SET = resolveOrFallback("Light.ATMOSPHERE_CUSTOM_BRIGHTNESS_SET", 1276194858);
    public static final int LIGHT_ATMOSPHERE_CUSTOM_COLOR = resolveOrFallback("Light.ATMOSPHERE_CUSTOM_COLOR", 657457168);
    public static final int LIGHT_ATMOSPHERE_CUSTOM_COLOR_SET = resolveOrFallback("Light.ATMOSPHERE_CUSTOM_COLOR_SET", 1276194864);

    // ==================== INSTRUMENT ====================
    public static final int INSTRUMENT_DD_MAIN_SAFETYBELT_STATE = resolveOrFallback("Instrument.INSTRUMENT_DD_MAIN_SAFETYBELT_STATE", 692060184);
    public static final int INSTRUMENT_DD_DEPUTY_SAFETYBELT_STATE = resolveOrFallback("Instrument.INSTRUMENT_DD_DEPUTY_SAFETYBELT_STATE", 638582811);
    public static final int INSTRUMENT_MUSIC_STATE_SET = resolveOrFallback("Instrument.MUSIC_STATE_SET", 1138753546);
    public static final int INSTRUMENT_MUSIC_SOURCE_SET = resolveOrFallback("Instrument.MUSIC_SOURCE_SET", 871366704);
    public static final int INSTRUMENT_MUSIC_PLAYBACK_PROGRESS_SET = resolveOrFallback("Instrument.MUSIC_PLAYBACK_PROGRESS_SET", 1138753552);
    public static final int INSTRUMENT_NAVIGATION_ACTIVATED_SET = resolveOrFallback("Instrument.NAVIGATION_ACTIVATED_SET", 1086373917);
    public static final int INSTRUMENT_NAVI_ESTIMATED_TIME_SET = resolveOrFallback("Instrument.NAVI_ESTIMATED_TIME_SET", 1277239312);
    public static final int INSTRUMENT_NAVI_ESTIMATED_MILEAGE_SET = resolveOrFallback("Instrument.NAVI_ESTIMATED_MILEAGE_SET", 1277239328);
    public static final int INSTRUMENT_NAVI_TYPE_SET = resolveOrFallback("Instrument.NAVI_TYPE_SET", 1276157976);
    public static final int INSTRUMENT_2IN1_CURRENT_JOURNEY_DRIVE_MILEAGE = resolveOrFallback("Instrument.INSTRUMENT_2IN1_CURRENT_JOURNEY_DRIVE_MILEAGE", 1246801948);
    public static final int INSTRUMENT_2IN1_CURRENT_JOURNEY_DRIVE_TIME = resolveOrFallback("Instrument.INSTRUMENT_2IN1_CURRENT_JOURNEY_DRIVE_TIME", 1246801938);
    public static final int INSTRUMENT_LF_TYRE_TEMPERATURE = resolveOrFallback("Instrument.LF_TYRE_TEMPERATURE", 1246797848);
    public static final int INSTRUMENT_RF_TYRE_TEMPERATURE = resolveOrFallback("Instrument.RF_TYRE_TEMPERATURE", 1246797860);
    public static final int INSTRUMENT_LB_TYRE_TEMPERATURE = resolveOrFallback("Instrument.LB_TYRE_TEMPERATURE", 1246797872);
    public static final int INSTRUMENT_RB_TYRE_TEMPERATURE = resolveOrFallback("Instrument.RB_TYRE_TEMPERATURE", 1246797884);
    public static final int INSTRUMENT_CHARGING_CHARGE_PERCENT_DD = resolveOrFallback("Instrument.CHARGING_CHARGE_PERCENT_DD", 842006544);
    public static final int INSTRUMENT_CHARGING_CHARGE_POWER_DD = resolveOrFallback("Instrument.CHARGING_CHARGE_POWER_DD", 842006552);
    public static final int INSTRUMENT_CHARGING_CHARGE_REST_HOUR_DD = resolveOrFallback("Instrument.CHARGING_CHARGE_REST_HOUR_DD", 842006568);
    public static final int INSTRUMENT_CHARGING_CHARGE_REST_MINUTE_DD = resolveOrFallback("Instrument.CHARGING_CHARGE_REST_MINUTE_DD", 842006576);

    // ==================== CHARGING ====================
    public static final int CHARGING_WIRELESS_STATE = resolveOrFallback("Charging.WIRELESS_CHARGING_STATE", 1105199113);
    public static final int CHARGING_WIRELESS_LEFT_SWITCH_DIRECT = resolveOrFallback("Charging.WIRELESS_CHARGING_LEFT_SWITCH_DIRECT", 1276182564);
    public static final int CHARGING_WIRELESS_RIGHT_SWITCH_DIRECT = resolveOrFallback("Charging.WIRELESS_CHARGING_RIGHT_SWITCH_DIRECT", 1276182566);
    public static final int CHARGING_WIRELESS_LEFT_SWITCH_SET = resolveOrFallback("Charging.WIRELESS_CHARGING_LEFT_SWITCH_SET", 1276182576);
    public static final int CHARGING_WIRELESS_RIGHT_SWITCH_SET = resolveOrFallback("Charging.WIRELESS_CHARGING_RIGHT_SWITCH_SET", 1276182578);
    public static final int CHARGING_WIRELESS_SWITCH_SET = resolveOrFallback("Charging.WIRELESS_CHARGING_SWITCH_SET", 1312817218);
    public static final int CHARGING_WIRELESS_LEFT_STATE = resolveOrFallback("Charging.WIRELESS_CHARGING_LEFT_STATE", 890241048);
    public static final int CHARGING_WIRELESS_RIGHT_STATE = resolveOrFallback("Charging.WIRELESS_CHARGING_RIGHT_STATE", 890241052);
    public static final int CHARGING_CHARGER_WORK_STATE = resolveOrFallback("Charging.CHARGING_CHARGER_WORK_STATE", 666894346);
    public static final int CHARGING_BATTERY_DEVICE_STATE = resolveOrFallback("Charging.CHARGING_BATTERRY_DEVICE_STATE", 876609560);

    // ==================== ENGINE (EXTENDED) ====================
    public static final int ENGINE_SPEED = resolveOrFallback("Engine.ENGINE_SPEED", 339738642);
    public static final int ENGINE_SPEED_GB = resolveOrFallback("Engine.ENGINE_SPEED_GB", 282066952);
    public static final int ENGINE_DRIFT_MODE_SWITCH_STATUS = resolveOrFallback("Engine.DRIFT_MODE_SWITCH_STATUS", 681574694);
    public static final int ENGINE_DRIFT_MODE_SWITCH_CONFIG = resolveOrFallback("Engine.DRIFT_MODE_SWITCH_CONFIG", 681574763);

    // ==================== WIPER ====================
    public static final int WIPER_REAR_AREA_STATE = resolveOrFallback("Wiper.REAR_AREA_STATE", 1196425226);
    public static final int WIPER_FRONT_LEVEL = resolveOrFallback("Wiper.FRONT_WIPER_LEVEL", 321912848);

    // ==================== DOORLOCK ====================
    public static final int DOORLOCK_CHILDLOCK_LEFT_SET = resolveOrFallback("DoorLock.COMMAND_AREA_CHILDLOCK_LEFT_SET", 1276141584);
    public static final int DOORLOCK_CHILDLOCK_RIGHT_SET = resolveOrFallback("DoorLock.COMMAND_AREA_CHILDLOCK_RIGHT_SET", 1276141586);

    // ==================== TAILGATE ====================
    public static final int TAILGATE_BACK_DOOR_STATUS = resolveOrFallback("Tailgate.BACK_DOOR_STATUS_FLAG", 692060181);
    public static final int TAILGATE_BACKDOOR_POSITION = resolveOrFallback("Tailgate.BACKDOOR_POSITION_FEEDBACK", 1074790456);
    public static final int TAILGATE_DOWN_BACK_DOOR_POSITION = resolveOrFallback("Tailgate.DOWN_BACK_DOOR_CURRENT_POSITION", 365953044);

    // ==================== MIRROR ====================
    public static final int MIRROR_HAVE_AUTO_FOLD = resolveOrFallback("Mirror.HAVE_REARVIEW_MIRROR_AUTO_FOLD", 1081081882);
    public static final int MIRROR_REARVIEW_SET = resolveOrFallback("Mirror.BODYWORK_REARVIEW_MIRROR_SET", 1324556304);

    // ==================== PM25 ====================
    public static final int PM25_ANION_STATE = resolveOrFallback("Pm25.ANION_STATE", 1033895958);
    public static final int PM25_ANION_STATE_SET = resolveOrFallback("Pm25.ANION_STATE_SET", 1337982994);

    // ==================== SAFETYBELT ====================
    public static final int SAFETYBELT_REMINDER_MASK = resolveOrFallback("SafetyBelt.REMINDER_MASK", 89129027);

    // ==================== STATISTIC (EXTENDED) ====================
    public static final int STAT_EV_DRIVING_MILEAGE = resolveOrFallback("Statistic.STATISTIC_EV_DRIVING_MILEAGE", 1146093608);
    public static final int STAT_MILEAGE_EV = resolveOrFallback("Statistic.STATISTIC_MILEAGE_EV", 1246773284);
    public static final int STAT_THIS_TRIP_ELEC_CONSUMPTION = resolveOrFallback("Statistic.STATISTIC_THIS_TRIP_TOTAL_ELEC_CONSUMPTION", 1246801976);
    public static final int STAT_BATTERY_HEALTHY_INDEX = resolveOrFallback("Statistic.STATISTIC_BATTERY_HEALTHY_INDEX", 1145045032);
    public static final int STAT_MILEAGE_AFTER_ZEROING = resolveOrFallback("Statistic.STATISTICS_MILEAGE_AFTER_ZEROING", 1245753360);
    public static final int STAT_MILEAGE_HEV = resolveOrFallback("Statistic.STATISTIC_MILEAGE_HEV", 1246773264);
    public static final int STAT_TOTAL_MILEAGE = resolveOrFallback("Statistic.STATISTIC_TOTAL_MILEAGE", 1246765072);
    public static final int STAT_ELEC_PERCENTAGE = resolveOrFallback("Statistic.STATISTIC_ELEC_PERCENTAGE", 1246777400);
    public static final int STAT_FUEL_PERCENTAGE = resolveOrFallback("Statistic.STATISTIC_FUEL_PERCENTAGE", 1246785600);
    public static final int STAT_ELEC_DRIVING_RANGE = resolveOrFallback("Statistic.STATISTIC_ELEC_DRIVING_RANGE", 1246765118);
    public static final int STAT_FUEL_DRIVING_RANGE = resolveOrFallback("Statistic.STATISTIC_FUEL_DRIVING_RANGE", 1246773304);

    // ==================== AC (EXTENDED) ====================
    public static final int AC_AUTO_MODE_SET = resolveOrFallback("Ac.AUTO_MODE_SET", 1324355606);
    public static final int AC_DEFROST_FRONT_SET = resolveOrFallback("Ac.DEFROST_FRONT_SET", 501219362);
    public static final int AC_DEFROST_REAR_SET = resolveOrFallback("Ac.DEFROST_REAR_SET", 501219357);
    public static final int AC_DEFROST_REAR_STATUS = resolveOrFallback("Ac.DEFROST_REAR_STATUS", 1128267825);
    public static final int AC_CYCLE_MODE_SET = resolveOrFallback("Ac.CYCLE_MODE_SET", 501219355);
    public static final int AC_DEFROST_FRONT_STATUS = resolveOrFallback("Ac.DEFROST_FRONT_STATUS", 1128267832);

    // ==================== ADAS ====================
    public static final int ADAS_OMS_DRIVER_DETECTION = resolveOrFallback("Adas.OMS_DRIVER_DETECTION_RESULT", 834666600);
    public static final int ADAS_OMS_PASSENGER_DETECTION = resolveOrFallback("Adas.OMS_PASSENGER_DETECTION_RESULT", 834666605);
    public static final int ADAS_SLR_STATUS_SET = resolveOrFallback("Adas.ADAS_SLR_STATUS_SET", 944767040);
    public static final int ADAS_ISLA_SWITCH_SET = resolveOrFallback("Adas.ADAS_ISLA_SWITCH_SET", 944767044);
    public static final int ADAS_ISLA_SWITCH_STATUS = resolveOrFallback("Adas.ADAS_ISLA_SWITCH_STATUS_5R13V", 760217615);
    public static final int ADAS_ISLC_SWITCH_SET = resolveOrFallback("Adas.ADAS_ISLC_SWITCH_SET", 1324560408);
    public static final int ADAS_ISLC_SWITCH_STATUS = resolveOrFallback("Adas.ADAS_ISLC_SWITCH_STATUS_5R13V", 760217611);
    public static final int ADAS_ELKA_SWITCH_SET = resolveOrFallback("Adas.ADAS_ELKA_SWITCH_SET", 944767046);
    public static final int ADAS_ELKA_SWITCH_STATE = resolveOrFallback("Adas.ADAS_ELKA_SWITCH_STATE", 854589482);
    public static final int ADAS_FCW_LEVEL_SET = resolveOrFallback("Adas.ADAS_FCW_LEVEL_SET", 1324560420);
    public static final int ADAS_FCW_LEVEL_STATUS = resolveOrFallback("Adas.ADAS_FCW_LEVEL_STATUS", 327155720);
    public static final int ADAS_RCTA_STATE_SET = resolveOrFallback("Adas.ADAS_RCTA_STATE_SET", 944766990);
    public static final int ADAS_RTCA_SWITCH_STATE = resolveOrFallback("Adas.ADAS_RTCA_SWITCH_STATE", 1098907674);
    public static final int ADAS_RTCB_SWITCH_STATE = resolveOrFallback("Adas.ADAS_RTCB_SWITCH_STATE", 1098907688);
    public static final int ADAS_ECTB_STATE_SET = resolveOrFallback("Adas.ADAS_ECTB_STATE_SET", 944767006);
    public static final int ADAS_FCTA_SWITCH_STATUS = resolveOrFallback("Adas.ADAS_FCTA_SWITCH_STATUS", 748683276);
    public static final int ADAS_FCTA_SWITCH_SET = resolveOrFallback("Adas.ADAS_FCTA_SWITCH_SET", 1324560400);
    public static final int ADAS_FCTB_SWITCH_STATUS = resolveOrFallback("Adas.ADAS_FCTB_SWITCH_STATUS", 748683278);
    public static final int ADAS_FCTB_SWITCH_SET = resolveOrFallback("Adas.ADAS_FCTB_SWITCH_SET", 1324560402);
    public static final int ADAS_TLA_SWITCH_SET = resolveOrFallback("Adas.ADAS_TLA_SWITCH_SET", 1324560410);
    public static final int ADAS_TLA_SWITCH_STATUS = resolveOrFallback("Adas.ADAS_TLA_SWITCH_STATUS_5R13V", 760217640);
    public static final int ADAS_DOW_STATE_SET = resolveOrFallback("Adas.ADAS_DOW_STATE_SET", 944766994);
    public static final int ADAS_DOW_SWITCH_STATE = resolveOrFallback("Adas.ADAS_DOW_SWITCH_STATE", 1098907678);
    public static final int ADAS_RCW_SWITCH_STATE = resolveOrFallback("Adas.ADAS_RCW_SWITCH_STATE", 1098907676);
    public static final int ADAS_RCW_STATE_SET = resolveOrFallback("Adas.ADAS_RCW_STATE_SET", 944766992);
    public static final int ADAS_ESP_STATE = resolveOrFallback("Adas.ADAS_ESP_STATE", 305135676);
    public static final int ADAS_ESP_STATE_SET = resolveOrFallback("Adas.ADAS_ESP_STATE_SET", 944766984);

    // ==================== SENSOR ====================
    public static final int SENSOR_AUTO_SLOPE = resolveOrFallback("Sensor.SENSOR_AUTO_SLOPE", 573571116);

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

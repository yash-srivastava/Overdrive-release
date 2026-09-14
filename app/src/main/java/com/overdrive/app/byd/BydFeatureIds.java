package com.overdrive.app.byd;

/**
 * All BYD feature IDs decoded from the DiLink APK.
 * Used with AbsBYDAutoDevice.get(int[], Class) for generic property reads.
 * 
 * Naming: DEVICE_PROPERTY with fallback numeric value in comment.
 */
public final class BydFeatureIds {

    /**
     * Fallback for a register this SDK does not publish at all. Not a valid feature id — every
     * read/write helper must SKIP an id equal to this rather than send it, because the HAL would
     * answer an unavailable sentinel that a magnitude test could mistake for real data.
     *
     * <p>Better than inventing a plausible-looking literal: an invented id is indistinguishable at
     * runtime from a correct one, so the feature silently reads as "nothing happening" forever.
     */
    public static final int UNRESOLVED_ID = Integer.MIN_VALUE;

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
    public static final int SET_DRIVER_SEAT_HEATING_STATE = resolveOrFallback("Setting.SET_DRIVER_SEAT_HEATING_STATE", 1335885835);
    public static final int SET_DRIVER_SEAT_VENTILATING_STATE = resolveOrFallback("Setting.SET_DRIVER_SEAT_VENTILATING_STATE", 1335885832);
    public static final int SET_PASSENGER_SEAT_HEATING_STATE = resolveOrFallback("Setting.SET_PASSENGER_SEAT_HEATING_STATE", 1335885843);
    public static final int SET_PASSENGER_SEAT_VENTILATING_STATE = resolveOrFallback("Setting.SET_PASSENGER_SEAT_VENTILATING_STATE", 1335885840);
    public static final int SETTING_CPD_SWITCH_STATUS  = resolveOrFallback("Setting.SETTING_CPD_SWITCH_STATUS", 376438818);
    public static final int SETTING_CPD_SWITCH_STATUS_SET  = resolveOrFallback("Setting.SETTING_CPD_SWITCH_STATUS_SET", 1324617778);
    /**
     * AC inlet current-limit controls used by the OEM ElectricLimit screen.
     *
     * <p>The runtime framework remains authoritative when it publishes these fields. Older DiLink 3
     * frameworks expose the same setting-device contract without exporting the field names, so the
     * connected-platform IDs are retained as fallbacks. Callers MUST prove the read side first
     * (config state 2 or a current state in 1..5) before sending the write ID; numeric resolution
     * alone is never capability evidence.
     */
    public static final int SETTING_AC_CHARGING_CURRENT_LIMIT_CONFIG_STATUS =
            resolveOrFallback("Setting.SETTING_AC_CHARGING_CURRENT_LIMIT_CONFIG_STATUS",
                    0x18500843);
    public static final int SETTING_AC_CHARGING_CURRENT_LIMIT_STATUS =
            resolveOrFallback("Setting.SETTING_AC_CHARGING_CURRENT_LIMIT_STATUS",
                    0x18500845);
    public static final int SETTING_AC_CHARGING_CURRENT_LIMIT_STATUS_SET =
            resolveOrFallback("Setting.SETTING_AC_CHARGING_CURRENT_LIMIT_STATUS_SET",
                    0x4EF06044);

    // ==================== ENERGY ====================
    /** Live road-surface selector: 1=common, 2=snow on the connected DiCar profile. */
    public static final int ENERGY_ROAD_SURFACE_MODE =
            resolveOrFallback("Energy.ENERGY_ROAD_SURFACE_MODE", 0x24000033);

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
    public static final int BODY_INSIDE_LIGHT_STATE_SET = resolveOrFallback("Body.INSIDE_LIGHT_STATE_SET", 1330643002);
    public static final int BODY_SUNSHADE_PANEL_PERCENT = resolveOrFallback("Body.BODYWORK_SUNSHADE_PANEL_PERCENT", 1101004816);
    public static final int BODY_SUNSHADE_PANEL_PERCENT_SET = resolveOrFallback("Body.BODYWORK_SUNSHADE_PANEL_PERCENT_SET", 1330642984);
    public static final int BODY_LF_DOOR_STATUS = resolveOrFallback("Body.LF_DOOR_STATUS_FLAG", 692060176);
    public static final int BODY_RF_DOOR_STATUS = resolveOrFallback("Body.RF_DOOR_STATUS_FLAG", 692060177);
    public static final int BODY_LR_DOOR_STATUS = resolveOrFallback("Body.LR_DOOR_STATUS_FLAG", 692060178);
    public static final int BODY_RR_DOOR_STATUS = resolveOrFallback("Body.RR_DOOR_STATUS_FLAG", 692060179);

    // ==================== BODYWORK DOOR / LID OPEN STATE ====================
    //
    // Open/closed state for each door and lid. Read via the manager tier
    // (BydManagerChannel.getInt), NOT the per-device BYDAutoBodyworkDevice.getDoorState:
    // that named getter runs enforceCallingOrSelfPermission(BYDAUTO_BODYWORK_GET) in the
    // caller, and GET is protectionLevel=signature / ungrantable, so it throws for every
    // non-system app and the old poll swallowed it in catch(Exception ignored). The manager
    // path delegates the read to the BYD system service, which holds the permission — the same
    // route battery/window already use.
    //
    // These are the "Bodywork" group ids, verified LIVE on a Sealion 6 DM-i (Di 3.0) with a
    // trunk open/close round-trip: 0=closed, 1=open, 65535=unavailable. deviceType 1001.
    // The BODY_*_DOOR_STATUS ids above are a DIFFERENT ("Body") group that reads
    // -10011/unavailable on this trim — kept for reference but not used for open-state.
    public static final int BODYWORK_DOOR_LF = resolveOrFallback("Bodywork.BODYWORK_LEFT_HAND_FRONT_DOOR", 0x29400008);
    public static final int BODYWORK_DOOR_RF = resolveOrFallback("Bodywork.BODYWORK_RIGHT_HAND_FRONT_DOOR", 0x2940000A);
    public static final int BODYWORK_DOOR_LR = resolveOrFallback("Bodywork.BODYWORK_LEFT_HAND_REAR_DOOR", 0x2940000C);
    public static final int BODYWORK_DOOR_RR = resolveOrFallback("Bodywork.BODYWORK_RIGHT_HAND_REAR_DOOR", 0x2940000E);
    public static final int BODYWORK_HOOD = resolveOrFallback("Bodywork.BODYWORK_HOOD", 0x2940001C);
    public static final int BODYWORK_TRUNK = resolveOrFallback("Bodywork.BODYWORK_LUGGAGE_DOOR", 0x2940001A);
    public static final int BODYWORK_FUEL_CAP = resolveOrFallback("Bodywork.BODYWORK_FUEL_TANK_CAP", 0x4FB00016);

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
    public static final int LIGHT_DAY_RUNNING_LIGHT_AUTO_STATE = resolveOrFallback("Light.LIGHT_DAY_RUNNING_LIGHT_AUTO_STATE", 985661476);

    // ==================== INSTRUMENT ====================
    public static final int INSTRUMENT_DD_MAIN_SAFETYBELT_STATE = resolveOrFallback("Instrument.INSTRUMENT_DD_MAIN_SAFETYBELT_STATE", 692060184);
    public static final int INSTRUMENT_DD_DEPUTY_SAFETYBELT_STATE = resolveOrFallback("Instrument.INSTRUMENT_DD_DEPUTY_SAFETYBELT_STATE", 638582811);
    /** OEM four-position exterior-light selector feedback: 1=off, 2=auto, 3=parking, 4=low beam. */
    public static final int INSTRUMENT_HEADLIGHT_CONTROL_FEEDBACK = resolveOrFallback(
        "Instrument.INSTRUMENT_HEADLIGHT_CONTROL_FEEDBACK", 1011875880);
    /** OEM four-position exterior-light selector command: 1=off, 2=auto, 3=parking, 4=low beam. */
    public static final int INSTRUMENT_HEADLIGHT_CONTROL_SET = resolveOrFallback(
        "Instrument.INSTRUMENT_HEADLIGHT_CONTROL_SET", 1276153912);
    public static final int INSTRUMENT_MUSIC_STATE_SET = resolveOrFallback("Instrument.MUSIC_STATE_SET", 1138753546);
    public static final int INSTRUMENT_MUSIC_SOURCE_SET = resolveOrFallback("Instrument.MUSIC_SOURCE_SET", 871366704);
    public static final int INSTRUMENT_MUSIC_PLAYBACK_PROGRESS_SET = resolveOrFallback("Instrument.MUSIC_PLAYBACK_PROGRESS_SET", 1138753552);
    public static final int INSTRUMENT_NAVIGATION_ACTIVATED_SET = resolveOrFallback("Instrument.NAVIGATION_ACTIVATED_SET", 1086373917);
    public static final int INSTRUMENT_NAVI_ESTIMATED_TIME_SET = resolveOrFallback("Instrument.NAVI_ESTIMATED_TIME_SET", 1277239312);
    public static final int INSTRUMENT_NAVI_ESTIMATED_MILEAGE_SET = resolveOrFallback("Instrument.NAVI_ESTIMATED_MILEAGE_SET", 1277239328);
    public static final int INSTRUMENT_NAVI_TYPE_SET = resolveOrFallback("Instrument.NAVI_TYPE_SET", 1276157976);
    public static final int INSTRUMENT_2IN1_CURRENT_JOURNEY_DRIVE_MILEAGE = resolveOrFallback("Instrument.INSTRUMENT_2IN1_CURRENT_JOURNEY_DRIVE_MILEAGE", 1246801948);
    public static final int INSTRUMENT_2IN1_CURRENT_JOURNEY_DRIVE_TIME = resolveOrFallback("Instrument.INSTRUMENT_2IN1_CURRENT_JOURNEY_DRIVE_TIME", 1246801938);
    // Instrument tyre temperature feature IDs — used for two-arg listener
    // registration and polled get() calls. May return null on some firmwares
    // but are the correct channel for TPMS temperature on others.
    public static final int INSTRUMENT_LF_TYRE_TEMPERATURE = resolveOrFallback(
        "Instrument.LF_TYRE_TEMPERATURE", 1246797848);
    public static final int INSTRUMENT_RF_TYRE_TEMPERATURE = resolveOrFallback(
        "Instrument.RF_TYRE_TEMPERATURE", 1246797860);
    public static final int INSTRUMENT_LB_TYRE_TEMPERATURE = resolveOrFallback(
        "Instrument.LB_TYRE_TEMPERATURE", 1246797872);
    public static final int INSTRUMENT_RB_TYRE_TEMPERATURE = resolveOrFallback(
        "Instrument.RB_TYRE_TEMPERATURE", 1246797884);

    /** All instrument tyre temperature feature IDs for listener subscription */
    public static final int[] INSTRUMENT_TYRE_TEMP_IDS = {
        INSTRUMENT_LF_TYRE_TEMPERATURE,
        INSTRUMENT_RF_TYRE_TEMPERATURE,
        INSTRUMENT_LB_TYRE_TEMPERATURE,
        INSTRUMENT_RB_TYRE_TEMPERATURE
    };
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
    /**
     * Per-session charged-energy counter, in kWh, on the charging device. Rises monotonically
     * while a charge is delivering and resets when a new session starts; the BMS keeps it across
     * a head-unit power cycle, so it is not tied to daemon uptime.
     *
     * <p>Read as a DOUBLE (the HAL's float accessor width). The connected SDK declares
     * {@code CHARGING_CAPACITY_MIN/MAX} = [0.0, 131.07] kWh.
     */
    public static final int CHARGING_CHARGE_CAPACITY = resolveOrFallback("Charging.CHARGING_CHARGE_CAPACITY", 666894360);

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
    // Command ids exposed by the reference controller SDK.
    public static final int MIRROR_HAVE_AUTO_FOLD = resolveOrFallback("Mirror.HAVE_REARVIEW_MIRROR_AUTO_FOLD", 1081081882);
    public static final int MIRROR_REARVIEW_SET = resolveOrFallback("Mirror.BODYWORK_REARVIEW_MIRROR_SET", 1324556304);
    // The connected framework's dedicated rear-view-mirror device reads this state feature.
    public static final int MIRROR_REARVIEW_STATE =
            resolveOrFallback("Rear.REAR_VIEW_MIRROR_STATE", 960495624);
    // The connected DiCar config_2.bin profile maps this write-only command to interface 1
    // (BYDAutoSettingDevice / device type 1023). It is distinct from the legacy bodywork command.
    public static final int SETTING_OUTSIDE_REARVIEW_MIRROR_FOLD_SET =
            resolveOrFallback("Setting.SET_OUTSIDE_REARVIEW_MIRROR_FOLD_SET", 1276157992);
    // ==================== HUD ====================
    // HUD on/off is a DEDICATED Setting feature-id write on the newer OEM firmware —
    // separate from HUD brightness. It is written as a BYDAutoEventValue via
    // BYDAutoSettingDevice.set(int[], EventValue) (the same sendSetCommand path as every
    // other setting write), from a real app process. An earlier build wrongly concluded
    // "no HUD switch exists" (that was true only on an older firmware that exposed only
    // setHUDBrightness) and even a prior guess fabricated 0x0780E026 on the instrument
    // device — both removed. The real ids, confirmed against the OEM SDK:
    //   SET_HUD_SWITCH_SET             0x4C10E023 (1276174371) — WRITE 1=on / 2=off (NOT 0!)
    //   SET_HUD_CONFIG                 0x38B00015 (951058453)  — READ: HUD is fitted iff 1 or 2
    //   SET_HUD_SWITCH_STATUS_FEEDBACK 0x38B0001C (951058460)  — READ: HUD is on iff 1
    // Note: the switch id lives in the 0x4C10E block a prior comment called "dead" for the
    // brightness-gear id — that was id-specific; this HUD switch id is live. Brightness
    // stays the named setHUDBrightness(int) 0..100 method. Both actuate from the app
    // process (VehicleActuatorService); the UID-2000 daemon's setting writes silently no-op.
    public static final int SETTING_HUD_SWITCH_SET = resolveOrFallback("Setting.SET_HUD_SWITCH_SET", 1276174371);
    public static final int SETTING_HUD_CONFIG = resolveOrFallback("Setting.SET_HUD_CONFIG", 951058453);
    public static final int SETTING_HUD_SWITCH_STATUS_FEEDBACK = resolveOrFallback("Setting.SET_HUD_SWITCH_STATUS_FEEDBACK", 951058460);

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
    // Wind/fan level SET via the generic set(1000, id, level) path. On some
    // DiLink 3.0 firmware the named setAcWindLevel() is a no-op; the generic
    // feature write works (verified in wheregoes/byd-apps research on Dolphin).
    // 0x1DE0000C sits in the same 0x1DE000xx AC-write family as CYCLE_MODE_SET
    // (0x1DE0001B) and DEFROST_FRONT_SET (0x1DE00022).
    public static final int AC_WIND_LEVEL_SET = resolveOrFallback("Ac.WIND_LEVEL_SET", 0x1DE0000C);

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
    public static final int ADAS_SLW_FUNC_SWITCH_STATE = resolveOrFallback("Adas.ADAS_SLW_FUNC_SWITCH_STATE", 535834664);
    public static final int ADAS_SLW_FUNC_SWITCH_STATE_SET = resolveOrFallback("Adas.ADAS_SLW_FUNC_SWITCH_STATE_SET", 850452531);

    // ── Blind-spot / lane-change / cross-traffic WARNINGS ────────────────
    // These are the radar ALERT signals (is a vehicle in the blind spot right
    // now), NOT the BSD on/off switch (ADAS_BSD_STATE below).
    //
    // Two DIFFERENT value encodings — mixing them up breaks the feature:
    //   • The dedicated FL/FR alarms are LEVELS: >= 1 means alerting.
    //   • LCA / RCTA / DOW are monotonically-increasing COUNTERS: an alert is an
    //     INCREASE over the last seen value, not a non-zero value. Reading one as
    //     a level either never fires (if it rests non-zero) or latches forever.
    public static final int ADAS_FL_BLIND_SPOT_ALARM = resolveOrFallback("Adas.ADAS_FL_BLIND_SPOT_ALARM_ACTIVE_SIGNAL_STATUS", 1098907692);
    public static final int ADAS_FR_BLIND_SPOT_ALARM = resolveOrFallback("Adas.ADAS_FR_BLIND_SPOT_ALARM_ACTIVE_SIGNAL_STATUS", 1098907694);
    public static final int ADAS_LCA_WARNING_LEFT = resolveOrFallback("Adas.ADAS_RIGHT_RADAR_LCA_WARNINGLEFT", 1098907664);
    public static final int ADAS_LCA_WARNING_RIGHT = resolveOrFallback("Adas.ADAS_RIGHT_RADAR_LCA_WARNINGRIGHT", 1098907666);
    public static final int ADAS_RCTA_WARNING_LEFT = resolveOrFallback("Adas.ADAS_RIGHT_RADAR_RCTA_WARNINGLEFT", 1098907668);
    public static final int ADAS_RCTA_WARNING_RIGHT = resolveOrFallback("Adas.ADAS_RIGHT_RADAR_RCTA_WARNINGRIGHT", 1098907669);
    public static final int ADAS_DOW_WARN_LEFT = resolveOrFallback("Adas.ADAS_DOW_WARN_LEFT", 1098907680);
    public static final int ADAS_DOW_WARN_RIGHT = resolveOrFallback("Adas.ADAS_DOW_WARN_RIGHT", 1098907682);
    /** Capability flag ("does this trim have BSD hardware"). Encoding unverified
     *  on-device, so it is NOT used as a gate — a missing/odd value must never
     *  suppress a real alert. Declared for probing only. */
    public static final int ADAS_HAS_BSD = resolveOrFallback("Adas.ADAS_HAS_BSD", 1098907648);

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

    /** Whether {@code id} is a real, sendable feature id. */
    public static boolean isResolved(int id) {
        return id != UNRESOLVED_ID;
    }

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

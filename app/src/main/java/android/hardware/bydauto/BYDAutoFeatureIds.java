package android.hardware.bydauto;

public final class BYDAutoFeatureIds {
    public static final int CHARGING_DISCHARGE_VEHICLE_OUTPUT_VOLTAGE = 4112;
    public static final int ENGINE_FRONT_MOTOR_SPEED = 4098;
    public static final int ENGINE_REAR_MOTOR_SPEED = 4097;
    public static final int ENGINE_SPEED = 4105;
    public static final int ENGINE_POWER = 4106;
    public static final int INSTRUMENT_DD_MILEAGE_UNIT = 4099;
    public static final int SPEED_ACCELERATOR_S = 4100;
    public static final int SPEED_BRAKE_S = 4101;
    public static final int STATISTIC_FUEL_PERCENTAGE = 4102;
    public static final int STATISTIC_MILEAGE_EV = 4103;
    public static final int STATISTIC_MILEAGE_HEV = 4104;
    public static final int STATISTIC_TOTAL_MILEAGE = 4096;

    // HV battery thermal property IDs
    // Raw values use CAN bus offset: actualTempC = rawValue - 40
    public static final int STATISTIC_HIGHEST_BATTERY_TEMP = 1148190752;
    public static final int STATISTIC_AVERAGE_BATTERY_TEMP = 1148190776;
    public static final int STATISTIC_LOWEST_BATTERY_TEMP = 1148190736;

    // HV battery cell voltage property IDs
    public static final int STATISTIC_HIGHEST_BATTERY_VOLTAGE = 1147142192;
    public static final int STATISTIC_LOWEST_BATTERY_VOLTAGE = 1147142160;

    public static final int BODYWORK_EMERGENCY_ALARM_STATE = 0;

    public static final class Setting {
        public static final int SET_LF_MEMORY_LOCATION_WAKE_SET = 8192;

        private Setting() {
        }
    }

    private BYDAutoFeatureIds() {
    }
}

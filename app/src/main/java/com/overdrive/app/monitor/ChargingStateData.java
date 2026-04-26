package com.overdrive.app.monitor;

/**
 * Data model for battery charging state and power.
 * 
 * Represents the charging state and power as reported by BYDAutoChargingDevice.
 * Includes error detection for breakdown states and discharging detection.
 */
public class ChargingStateData {
    
    // Charging state constants from BYDAutoChargingDevice
    public static final int CHARGING_BATTERY_STATE_READY = 0;
    public static final int CHARGING_BATTERY_STATE_CHARGING = 1;
    public static final int CHARGING_BATTERY_STATE_CHARG_FINISH = 2;
    public static final int CHARGING_BATTERY_STATE_DISCHARG = 3;
    public static final int CHARGING_BATTERY_STATE_CHARG_TERMINATE = 4;
    public static final int CHARGING_BATTERY_STATE_BREAKDOWN_C10 = 5;
    public static final int CHARGING_BATTERY_STATE_BREAKDOWN_CHARGING_GUN = 6;
    public static final int CHARGING_BATTERY_STATE_BREAKDOWN_AC = 7;
    public static final int CHARGING_BATTERY_STATE_BREAKDOWN_CHARGER = 8;
    public static final int CHARGING_BATTERY_STATE_SCHEDULE = 9;
    public static final int CHARGING_BATTERY_STATE_TIMEOUT = 10;
    public static final int CHARGING_BATTERY_STATE_DISCHARG_CBU = 11;
    public static final int CHARGING_BATTERY_STATE_DISCHARG_FINISH = 12;
    // Additional states observed in newer BYD firmware (undocumented)
    public static final int CHARGING_BATTERY_STATE_IDLE = 15;           // Idle - not plugged in
    
    public enum ChargingStatus {
        READY, CHARGING, FINISHED, TERMINATED, 
        DISCHARGING, SCHEDULED, TIMEOUT, ERROR,
        IDLE, UNKNOWN
    }
    
    public final int stateCode;          // Raw state code
    public final String stateName;       // Human-readable name
    public final ChargingStatus status;  // Enum status
    public final boolean isError;        // true for breakdown states
    public final String errorType;       // "AC", "CHARGER", "GUN", "C10", null
    public double chargingPowerKW;       // Current charging power in KW (negative = discharging)
    public boolean isDischarging;        // true if power < 0
    public boolean isEstimated;          // true if power is computed from SOC rate (not from BYD API)
    public final long timestamp;
    
    /**
     * Create charging state data from BYD API state code.
     * 
     * @param stateCode The state code from BYDAutoChargingDevice
     */
    public ChargingStateData(int stateCode) {
        this.stateCode = stateCode;
        this.stateName = interpretStateName(stateCode);
        this.status = interpretStatus(stateCode);
        this.isError = isBreakdownState(stateCode);
        this.errorType = getErrorType(stateCode);
        this.chargingPowerKW = 0.0;
        this.isDischarging = false;
        this.timestamp = System.currentTimeMillis();
    }
    
    /**
     * Update charging power and discharging flag.
     * 
     * @param powerKW The charging power in kilowatts
     */
    public void updateChargingPower(double powerKW) {
        this.chargingPowerKW = powerKW;
        updateDischargingFlag();
    }
    
    /**
     * Update discharging flag based on power value.
     */
    public void updateDischargingFlag() {
        this.isDischarging = (chargingPowerKW < 0);
    }
    
    /**
     * Interpret state code to human-readable name.
     */
    private static String interpretStateName(int stateCode) {
        switch (stateCode) {
            case CHARGING_BATTERY_STATE_READY:
                return "Ready";
            case CHARGING_BATTERY_STATE_CHARGING:
                return "Charging";
            case CHARGING_BATTERY_STATE_CHARG_FINISH:
                return "Charge Finished";
            case CHARGING_BATTERY_STATE_DISCHARG:
                return "Discharging";
            case CHARGING_BATTERY_STATE_CHARG_TERMINATE:
                return "Charge Terminated";
            case CHARGING_BATTERY_STATE_BREAKDOWN_C10:
                return "Breakdown: C10";
            case CHARGING_BATTERY_STATE_BREAKDOWN_CHARGING_GUN:
                return "Breakdown: Charging Gun";
            case CHARGING_BATTERY_STATE_BREAKDOWN_AC:
                return "Breakdown: AC";
            case CHARGING_BATTERY_STATE_BREAKDOWN_CHARGER:
                return "Breakdown: Charger";
            case CHARGING_BATTERY_STATE_SCHEDULE:
                return "Scheduled";
            case CHARGING_BATTERY_STATE_TIMEOUT:
                return "Timeout";
            case CHARGING_BATTERY_STATE_DISCHARG_CBU:
                return "Discharging CBU";
            case CHARGING_BATTERY_STATE_DISCHARG_FINISH:
                return "Discharge Finished";
            case CHARGING_BATTERY_STATE_IDLE:
                return "Idle";
            default:
                return "Unknown (" + stateCode + ")";
        }
    }
    
    /**
     * Interpret state code to enum status.
     */
    private static ChargingStatus interpretStatus(int stateCode) {
        switch (stateCode) {
            case CHARGING_BATTERY_STATE_READY:
                return ChargingStatus.READY;
            case CHARGING_BATTERY_STATE_CHARGING:
                return ChargingStatus.CHARGING;
            case CHARGING_BATTERY_STATE_CHARG_FINISH:
                return ChargingStatus.FINISHED;
            case CHARGING_BATTERY_STATE_CHARG_TERMINATE:
                return ChargingStatus.TERMINATED;
            case CHARGING_BATTERY_STATE_DISCHARG:
            case CHARGING_BATTERY_STATE_DISCHARG_CBU:
            case CHARGING_BATTERY_STATE_DISCHARG_FINISH:
                return ChargingStatus.DISCHARGING;
            case CHARGING_BATTERY_STATE_SCHEDULE:
                return ChargingStatus.SCHEDULED;
            case CHARGING_BATTERY_STATE_TIMEOUT:
                return ChargingStatus.TIMEOUT;
            case CHARGING_BATTERY_STATE_BREAKDOWN_C10:
            case CHARGING_BATTERY_STATE_BREAKDOWN_CHARGING_GUN:
            case CHARGING_BATTERY_STATE_BREAKDOWN_AC:
            case CHARGING_BATTERY_STATE_BREAKDOWN_CHARGER:
                return ChargingStatus.ERROR;
            case CHARGING_BATTERY_STATE_IDLE:
                return ChargingStatus.IDLE;
            default:
                return ChargingStatus.UNKNOWN;
        }
    }
    
    /**
     * Check if state code represents a breakdown condition.
     */
    private static boolean isBreakdownState(int stateCode) {
        return stateCode == CHARGING_BATTERY_STATE_BREAKDOWN_C10 ||
               stateCode == CHARGING_BATTERY_STATE_BREAKDOWN_CHARGING_GUN ||
               stateCode == CHARGING_BATTERY_STATE_BREAKDOWN_AC ||
               stateCode == CHARGING_BATTERY_STATE_BREAKDOWN_CHARGER;
    }
    
    /**
     * Get error type for breakdown states.
     */
    private static String getErrorType(int stateCode) {
        switch (stateCode) {
            case CHARGING_BATTERY_STATE_BREAKDOWN_AC:
                return "AC";
            case CHARGING_BATTERY_STATE_BREAKDOWN_CHARGER:
                return "CHARGER";
            case CHARGING_BATTERY_STATE_BREAKDOWN_CHARGING_GUN:
                return "GUN";
            case CHARGING_BATTERY_STATE_BREAKDOWN_C10:
                return "C10";
            default:
                return null;
        }
    }
    
    @Override
    public String toString() {
        return "ChargingStateData{" +
                "stateCode=" + stateCode +
                ", stateName='" + stateName + '\'' +
                ", status=" + status +
                ", isError=" + isError +
                ", errorType='" + errorType + '\'' +
                ", chargingPowerKW=" + chargingPowerKW +
                ", isDischarging=" + isDischarging +
                ", timestamp=" + timestamp +
                '}';
    }
}

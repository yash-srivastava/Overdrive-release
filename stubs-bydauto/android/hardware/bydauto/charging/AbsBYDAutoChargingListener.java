package android.hardware.bydauto.charging;

import android.hardware.bydauto.BYDAutoEventValue;
import android.hardware.bydauto.IBYDAutoEvent;

public abstract class AbsBYDAutoChargingListener {
    public void onBatteryManagementDeviceStateChanged(int state) {}
    public void onBatteryTypeChanged(int type) {}
    public void onCapStateChanged(int state) {}
    public void onCarDischargeLowWarnChanged(int state) {}
    public void onCarDischargeStateChanged(int state) {}
    public void onChargeStopCapacityStateChanged(int state) {}
    public void onChargeStopSwitchStateChanged(int state) {}
    public void onChargePreheatStateChanged(int state) {}
    public void onChargeTempCtlStateChanged(int state) {}
    public void onChargerFaultStateChanged(int state) {}
    public void onChargerStateChanged(int state) {}
    public void onChargerWorkStateChanged(int state) {}
    public void onChargingCapStateChanged(int state, int value) {}
    public void onChargingCapacityChanged(float capacity) {}
    public void onChargingCapacityChanged(double capacity) {}
    public void onChargingGunNotInsertedStateChanged(int state) {}
    public void onChargingGunStateChanged(int state) {}
    public void onChargingModeChanged(int mode) {}
    public void onChargingPortLockRebackStateChanged(int state) {}
    public void onChargingPowerChanged(double power) {}
    public void onChargingPowerChanged(float power) {}
    public void onChargingRestTimeChanged(int minutes, int seconds) {}
    public void onChargingScheduleEnableStateChanged(int state) {}
    public void onChargingScheduleStateChanged(int state) {}
    public void onChargingScheduleTimeChanged(int startMinutes, int endMinutes) {}
    public void onChargingStateChanged(int state) {}
    public void onChargingTimerInfoChanged(ChargingTimerInfo info) {}
    public void onChargingTypeChanged(int type) {}
    public void onDataChanged(IBYDAutoEvent event) {}
    public void onDataEventChanged(int eventId, BYDAutoEventValue value) {}
    public void onDisChargeWarningStateChanged(int state) {}
    public void onDischargeRequestStateChanged(int state) {}
    public void onDischargeStateChanged(int state, int reason) {}
    public void onError(int code, String msg) {}
    public void onFeatureChanged(String feature, int value) {}
    public void onSmartChargingStateChanged(int state) {}
    public void onSocSaveSwitchChanged(int state) {}
    public void onVtovDischargeConnectStateChanged(int state) {}
    public void onVtovDischargeLimitValChanged(int value) {}
    public void onVtovDischargeLowestValChanged(int value) {}
    public void onVtovDischargeQuantityChanged(double quantity) {}
    public void onWeatherAndTimeRequestChanged(int state) {}
    public void onWirelessChargingOnline5sStateChanged(int state) {}
    public void onWirelessChargingSwitchStateChanged(int state) {}
    public void onWirlessChargingStateChanged(int state) {}
}

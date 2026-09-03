package android.hardware.bydauto.energy;
// DiLink-5 compile-only stub. Real class (abstract) from the OEM SDK at runtime.
// energy.getEnergyFeedback() (CarBodyManager path) confirmed DEAD on-car — constant 0
// through a parked High/Standard toggle test. The real regen
// mode getter is setting.getEnergyFeedback() (see Dilink5Client.registerSettingListener).
// Probed here anyway (compat-report only, not wired into app telemetry) in case another
// vehicle's firmware wires this device instead.
public abstract class AbsBYDAutoEnergyListener {
    public AbsBYDAutoEnergyListener() {}
    public void onEnergyFeedbackLevelChanged(int level) {}
    public void onEnergyModeChanged(int mode) {}
    public void onOperationModeChanged(int mode) {}
    public void onRoadSurfaceChanged(int mode) {}
    public void onError(int code, String msg) {}
}

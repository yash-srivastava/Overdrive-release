package com.overdrive.app.trips;

/** The driver's own range pick, stored as a consumption so it holds at any state of charge. */
public final class RangeCalibration {

    /** kWh/km at the temperature curve's 1.0 point; what the model blends with. */
    public final double normRateKwhPerKm;
    /** kWh/km the pick meant at the moment it was made. */
    public final double pickedRateKwhPerKm;
    public final double pickedRangeKm;
    public final double pickedSocPercent;
    public final long setAtMs;

    public RangeCalibration(double normRateKwhPerKm, double pickedRateKwhPerKm,
                            double pickedRangeKm, double pickedSocPercent, long setAtMs) {
        this.normRateKwhPerKm = normRateKwhPerKm;
        this.pickedRateKwhPerKm = pickedRateKwhPerKm;
        this.pickedRangeKm = pickedRangeKm;
        this.pickedSocPercent = pickedSocPercent;
        this.setAtMs = setAtMs;
    }

    /** A row the model can use: a positive finite rate and a set time. */
    public boolean isUsable() {
        return Double.isFinite(normRateKwhPerKm) && normRateKwhPerKm > 0 && setAtMs > 0;
    }
}

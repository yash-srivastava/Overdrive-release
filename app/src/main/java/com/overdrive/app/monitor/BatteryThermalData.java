package com.overdrive.app.monitor;

/**
 * Immutable data class for HV battery thermal readings.
 * 
 * Values are in degrees Celsius, derived from BYD's CAN bus encoding:
 *   actualTempC = rawValue - 40
 * 
 * Three sensors from BYDAutoStatisticDevice:
 *   - Highest battery cell temperature
 *   - Lowest battery cell temperature
 *   - Average pack / coolant temperature
 */
public class BatteryThermalData {

    /** Max cell temperature in °C (NaN if unavailable) */
    public final double highestTempC;

    /** Min cell temperature in °C (NaN if unavailable) */
    public final double lowestTempC;

    /** Average pack temperature in °C (NaN if unavailable) */
    public final double averageTempC;

    /** Temperature delta between max and min cells (thermal imbalance indicator) */
    public final double deltaC;

    /** True if any temperature exceeds 45°C (BYD thermal warning threshold) */
    public final boolean isWarning;

    /** True if any temperature exceeds 55°C (critical — BMS will derate) */
    public final boolean isCritical;

    /** Timestamp of this reading */
    public final long timestamp;

    public BatteryThermalData(double highestTempC, double lowestTempC, double averageTempC, long timestamp) {
        this.highestTempC = highestTempC;
        this.lowestTempC = lowestTempC;
        this.averageTempC = averageTempC;
        this.timestamp = timestamp;

        if (!Double.isNaN(highestTempC) && !Double.isNaN(lowestTempC)) {
            this.deltaC = highestTempC - lowestTempC;
        } else {
            this.deltaC = Double.NaN;
        }

        double maxTemp = Double.NaN;
        if (!Double.isNaN(highestTempC)) maxTemp = highestTempC;
        else if (!Double.isNaN(averageTempC)) maxTemp = averageTempC;

        this.isWarning = !Double.isNaN(maxTemp) && maxTemp > 45.0;
        this.isCritical = !Double.isNaN(maxTemp) && maxTemp > 55.0;
    }

    /**
     * Get the best available temperature for ABRP batt_temp field.
     * Prefers average, falls back to highest, then lowest.
     */
    public double getBestTemperature() {
        if (!Double.isNaN(averageTempC)) return averageTempC;
        if (!Double.isNaN(highestTempC)) return highestTempC;
        return lowestTempC;
    }

    /**
     * Returns true if at least one temperature reading is available.
     */
    public boolean hasData() {
        return !Double.isNaN(highestTempC) || !Double.isNaN(lowestTempC) || !Double.isNaN(averageTempC);
    }

    public String getStatus() {
        if (isCritical) return "CRITICAL";
        if (isWarning) return "WARNING";
        if (hasData()) return "NORMAL";
        return "UNAVAILABLE";
    }

    @Override
    public String toString() {
        if (!hasData()) return "BatteryThermal[unavailable]";
        return String.format("BatteryThermal[hi=%.1f°C lo=%.1f°C avg=%.1f°C Δ=%.1f°C %s]",
                highestTempC, lowestTempC, averageTempC, deltaC, getStatus());
    }
}

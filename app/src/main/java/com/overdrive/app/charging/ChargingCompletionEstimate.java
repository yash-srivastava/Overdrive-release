package com.overdrive.app.charging;

import com.overdrive.app.abrp.SohEstimator;
import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.monitor.BatterySocData;
import com.overdrive.app.monitor.ChargingStateData;
import com.overdrive.app.monitor.SocHistoryDatabase;
import com.overdrive.app.monitor.VehicleDataMonitor;
import org.json.JSONObject;

/**
 * Canonical live charging-completion estimate shared by every API and UI.
 *
 * <p>The BYD countdown displayed by the vehicle wins whenever it is available.
 * A calculated fallback is emitted only from a real measured charging-power
 * sample plus the selected pack capacity; placeholder/estimated power is never
 * promoted into a confident-looking completion time.</p>
 */
public final class ChargingCompletionEstimate {

    public static final String SOURCE_VEHICLE = "vehicle";
    public static final String SOURCE_CALCULATED = "calculated";
    private static final int MAX_MINUTES = 48 * 60;

    public final int minutes;
    public final String source;
    public final long completionEpochMs;
    public final int targetPercent;

    private ChargingCompletionEstimate(
            int minutes, String source, long completionEpochMs, int targetPercent) {
        this.minutes = minutes;
        this.source = source;
        this.completionEpochMs = completionEpochMs;
        this.targetPercent = targetPercent;
    }

    public static ChargingCompletionEstimate unavailable() {
        return new ChargingCompletionEstimate(-1, null, -1L, -1);
    }

    public boolean isAvailable() {
        return minutes > 0 && source != null;
    }

    /** Resolve directly from current vehicle/monitor state. */
    public static ChargingCompletionEstimate resolveLive(boolean charging, boolean full) {
        int restHours = BydVehicleData.UNAVAILABLE;
        int restMinutes = BydVehicleData.UNAVAILABLE;
        double socPercent = Double.NaN;
        double targetPercent = 100.0;
        double powerKw = Double.NaN;
        boolean powerEstimated = true;
        double nominalKwh = Double.NaN;
        double sohPercent = Double.NaN;

        try {
            BydDataCollector collector = BydDataCollector.getInstance();
            if (collector != null && collector.isInitialized()) {
                BydVehicleData data = collector.getData();
                if (data != null) {
                    restHours = data.chargingRestTimeHours;
                    restMinutes = data.chargingRestTimeMinutes;
                }
                int cap = collector.getChargeCapPercent();
                if (cap >= 15 && cap <= 100) targetPercent = cap;
            }
        } catch (Throwable ignored) {}

        try {
            VehicleDataMonitor monitor = VehicleDataMonitor.getInstance();
            if (monitor != null) {
                BatterySocData soc = monitor.getBatterySoc();
                if (soc != null) socPercent = soc.socPercent;
                ChargingStateData state = monitor.getChargingState();
                if (state != null) {
                    powerKw = state.chargingPowerKW;
                    powerEstimated = state.isEstimated;
                }
            }
        } catch (Throwable ignored) {}

        try {
            SohEstimator soh = SocHistoryDatabase.getInstance().getSohEstimator();
            if (soh != null) {
                nominalKwh = soh.getNominalCapacityKwh();
                if (soh.hasDisplaySoh()) sohPercent = soh.getDisplaySoh();
            }
        } catch (Throwable ignored) {}

        return resolve(
                charging,
                full,
                restHours,
                restMinutes,
                targetPercent,
                socPercent,
                nominalKwh,
                sohPercent,
                powerKw,
                powerEstimated,
                System.currentTimeMillis());
    }

    /** Pure resolver kept public for focused JVM tests. */
    public static ChargingCompletionEstimate resolve(
            boolean charging,
            boolean full,
            int restHours,
            int restMinutes,
            double targetPercent,
            double socPercent,
            double nominalKwh,
            double sohPercent,
            double powerKw,
            boolean powerEstimated,
            long nowMs) {
        if (!charging || full) return unavailable();

        int directMinutes = directVehicleMinutes(restHours, restMinutes);
        if (directMinutes > 0) {
            return available(directMinutes, SOURCE_VEHICLE, nowMs, targetPercent);
        }

        if (powerEstimated
                || !Double.isFinite(powerKw) || powerKw <= 0.1
                || !Double.isFinite(targetPercent) || targetPercent <= 0 || targetPercent > 100
                || !Double.isFinite(socPercent) || socPercent < 0 || socPercent >= targetPercent
                || !Double.isFinite(nominalKwh) || nominalKwh <= 0) {
            return unavailable();
        }

        double sohFraction = Double.isFinite(sohPercent) && sohPercent > 0
                ? Math.min(sohPercent, 100.0) / 100.0
                : 1.0;
        double remainingKwh = ((targetPercent - socPercent) / 100.0) * nominalKwh * sohFraction;
        int calculatedMinutes = (int) Math.round(remainingKwh / powerKw * 60.0);
        if (calculatedMinutes <= 0 || calculatedMinutes > MAX_MINUTES) return unavailable();
        return available(calculatedMinutes, SOURCE_CALCULATED, nowMs, targetPercent);
    }

    private static int directVehicleMinutes(int hours, int minutes) {
        int unavailable = BydVehicleData.UNAVAILABLE;
        if (hours == unavailable && minutes == unavailable) return -1;
        int safeHours = hours == unavailable ? 0 : hours;
        int safeMinutes = minutes == unavailable ? 0 : minutes;
        if (safeHours < 0 || safeMinutes < 0) return -1;
        long total = safeHours * 60L + safeMinutes;
        return total > 0 && total <= MAX_MINUTES ? (int) total : -1;
    }

    private static ChargingCompletionEstimate available(
            int minutes, String source, long nowMs, double targetPercent) {
        long safeNow = Math.max(0L, nowMs);
        return new ChargingCompletionEstimate(
                minutes,
                source,
                safeNow + minutes * 60_000L,
                (int) Math.round(Math.max(1.0, Math.min(100.0, targetPercent))));
    }

    /** Add the canonical wire fields without overwriting charging state flags. */
    public void putInto(JSONObject target) throws org.json.JSONException {
        if (target == null) return;
        if (!isAvailable()) {
            target.put("timeToFullMin", JSONObject.NULL);
            target.put("timeToFullSource", JSONObject.NULL);
            target.put("completionEpochMs", JSONObject.NULL);
            target.put("completionTargetPercent", JSONObject.NULL);
            return;
        }
        target.put("timeToFullMin", minutes);
        target.put("timeToFullSource", source);
        target.put("completionEpochMs", completionEpochMs);
        target.put("completionTargetPercent", targetPercent);
    }
}

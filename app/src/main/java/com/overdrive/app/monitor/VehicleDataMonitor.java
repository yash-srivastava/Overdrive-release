package com.overdrive.app.monitor;

import android.content.Context;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Singleton coordinator for BYD vehicle data.
 * 
 * Phase 3: Thin wrapper around BydDataCollector.
 * All data reads delegate to the collector. Keeps the same API surface
 * so existing consumers (HttpServer, SurveillanceIpcServer, TripDetector, etc.)
 * don't need changes.
 * 
 * The BatteryPowerMonitor is kept for AccSentryDaemon's voltage-based MCU control
 * (it needs listener callbacks for real-time voltage changes).
 */
public class VehicleDataMonitor {
    
    private static final String TAG = "VehicleDataMonitor";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    
    private static VehicleDataMonitor instance;
    private static final Object lock = new Object();
    
    // Only BatteryPowerMonitor kept — AccSentryDaemon needs its listener for voltage-based MCU control
    private final BatteryPowerMonitor batteryPowerMonitor;
    
    private final CopyOnWriteArrayList<VehicleDataListener> listeners = new CopyOnWriteArrayList<>();
    private boolean isRunning = false;
    private Context context;
    
    private VehicleDataMonitor() {
        this.batteryPowerMonitor = new BatteryPowerMonitor();
    }
    
    public static VehicleDataMonitor getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) instance = new VehicleDataMonitor();
            }
        }
        return instance;
    }

    // ==================== LIFECYCLE ====================
    
    public void init(Context context) {
        this.context = context;
        logger.info("Initializing VehicleDataMonitor (BydDataCollector mode)");
        
        // Only init battery power monitor (for AccSentryDaemon voltage listener)
        try {
            batteryPowerMonitor.init(context);
        } catch (Exception e) {
            logger.error("Failed to init BatteryPowerMonitor", e);
        }
        
        logger.info("Initialization complete (data from BydDataCollector)");
    }
    
    public void initBatteryPowerOnly(Context context) {
        this.context = context;
        try {
            batteryPowerMonitor.init(context);
        } catch (Exception e) {
            logger.error("Failed to init BatteryPowerMonitor", e);
        }
    }
    
    public synchronized void start() {
        if (isRunning) return;
        try { batteryPowerMonitor.start(); } catch (Exception e) { logger.error("BatteryPowerMonitor start failed", e); }
        isRunning = true;
        logger.info("VehicleDataMonitor started");
    }
    
    public synchronized void startBatteryPowerOnly() {
        if (isRunning) return;
        try { batteryPowerMonitor.start(); } catch (Exception e) { logger.error("BatteryPowerMonitor start failed", e); }
        isRunning = true;
    }
    
    public synchronized void stop() {
        if (!isRunning) return;
        try { batteryPowerMonitor.stop(); } catch (Exception ignored) {}
        isRunning = false;
        logger.info("VehicleDataMonitor stopped");
    }
    
    public synchronized void stopBatteryPowerOnly() {
        if (!isRunning) return;
        try { batteryPowerMonitor.stop(); } catch (Exception ignored) {}
        isRunning = false;
    }
    
    public boolean isRunning() { return isRunning; }
    
    // ==================== DATA ACCESS (delegates to BydDataCollector) ====================
    
    public BydVehicleData getVd() {
        try {
            BydDataCollector c = BydDataCollector.getInstance();
            if (!c.isInitialized() && context != null) {
                // BydDataCollector not yet initialized — init it now
                // This handles the race where VehicleDataMonitor is queried
                // before CameraDaemon finishes BydDataCollector.init()
                logger.info("BydDataCollector not initialized — initializing from VehicleDataMonitor");
                c.init(context);
            }
            return c.isInitialized() ? c.getData() : null;
        } catch (Exception e) { return null; }
    }
    
    public BatteryVoltageData getBatteryVoltage() {
        BydVehicleData vd = getVd();
        if (vd != null && vd.voltageLevelRaw != BydVehicleData.UNAVAILABLE) {
            return new BatteryVoltageData(vd.voltageLevelRaw);
        }
        return null;
    }
    
    public BatteryPowerData getBatteryPower() {
        // Try collector first, fallback to monitor (for AccSentryDaemon compatibility)
        BydVehicleData vd = getVd();
        if (vd != null && !Double.isNaN(vd.voltage12v)) {
            return new BatteryPowerData(vd.voltage12v);
        }
        return batteryPowerMonitor.getCurrentValue();
    }
    
    public BatterySocData getBatterySoc() {
        BydVehicleData vd = getVd();
        if (vd != null && !Double.isNaN(vd.socPercent)) {
            return new BatterySocData(vd.socPercent);
        }
        return null;
    }
    
    public ChargingStateData getChargingState() {
        BydVehicleData vd = getVd();
        if (vd != null && vd.chargingState != BydVehicleData.UNAVAILABLE) {
            ChargingStateData data = new ChargingStateData(vd.chargingState);
            // Prefer externalChargingPowerKw (from InstrumentDevice) — this is the real
            // charger-reported power. ChargingDevice.getChargingPower() is broken on most
            // BYD models and always returns 0.
            if (!Double.isNaN(vd.externalChargingPowerKw) && vd.externalChargingPowerKw > 0) {
                data.updateChargingPower(vd.externalChargingPowerKw);
            } else if (!Double.isNaN(vd.chargingPowerKw) && vd.chargingPowerKw > 0) {
                data.updateChargingPower(vd.chargingPowerKw);
            } else if (data.status == ChargingStateData.ChargingStatus.CHARGING) {
                // Both power sources returned 0 but charging is active.
                // Don't hardcode a power value — it's misleading (e.g., showing 7 kW
                // when the car is actually drawing 4.6 kW). Instead, try to compute
                // from SOC change rate, and if that's not possible, mark as estimated.
                try {
                    com.overdrive.app.abrp.SohEstimator soh =
                        com.overdrive.app.monitor.SocHistoryDatabase.getInstance().getSohEstimator();
                    if (soh != null && soh.getNominalCapacityKwh() > 0) {
                        double nominal = soh.getNominalCapacityKwh();
                        // Try to compute from SOC change rate
                        double ratePerHour = com.overdrive.app.monitor.SocHistoryDatabase.getInstance()
                            .getSocChangeRatePerHour();
                        if (ratePerHour > 0.5) {
                            // SOC is rising — compute power from rate
                            // power = (rate% / 100) * nominalKwh
                            double estimatedPower = (ratePerHour / 100.0) * nominal;
                            // Clamp to reasonable range (0.5 - 150 kW)
                            estimatedPower = Math.max(0.5, Math.min(150, estimatedPower));
                            data.updateChargingPower(estimatedPower);
                            data.isEstimated = true;
                        }
                        // If rate is too low or negative, leave power as 0
                        // (UI will show "Charging" without a kW number)
                    }
                } catch (Exception e) { /* leave as 0 */ }
            }
            return data;
        }
        return null;
    }
    
    public DrivingRangeData getDrivingRange() {
        BydVehicleData vd = getVd();
        if (vd != null && vd.elecRangeKm != BydVehicleData.UNAVAILABLE) {
            return new DrivingRangeData(
                vd.elecRangeKm,
                vd.fuelRangeKm != BydVehicleData.UNAVAILABLE ? vd.fuelRangeKm : 0
            );
        }
        return null;
    }
    
    public BatteryThermalData getBatteryThermal() {
        BydVehicleData vd = getVd();
        if (vd != null) {
            double hi = vd.highCellTempC;
            double lo = vd.lowCellTempC;
            double avg = vd.avgCellTempC;
            if (!Double.isNaN(hi) || !Double.isNaN(lo) || !Double.isNaN(avg)) {
                return new BatteryThermalData(hi, lo, avg, System.currentTimeMillis());
            }
        }
        return null;
    }
    
    public double getBatteryRemainPowerKwh() {
        BydVehicleData vd = getVd();
        if (vd == null) return 0.0;

        double soc = Double.isNaN(vd.socPercent) ? 0 : vd.socPercent;
        double rawKwh = Double.isNaN(vd.remainKwh) ? 0 : vd.remainKwh;

        try {
            com.overdrive.app.abrp.SohEstimator soh =
                com.overdrive.app.monitor.SocHistoryDatabase.getInstance().getSohEstimator();
            if (soh != null && soh.getNominalCapacityKwh() > 0 && soc > 0) {
                double nominal = soh.getNominalCapacityKwh();
                double sohPercent = soh.hasEstimate() ? soh.getCurrentSoh() : 100.0;
                double computedKwh = (soc / 100.0) * nominal * (sohPercent / 100.0);
                
                // Validate raw BMS value: if implied capacity is wildly off from nominal,
                // the BMS is returning garbage (common on Seal, Han EV when ACC is off).
                // Use computed value instead.
                if (rawKwh > 0 && soc > 5) {
                    double impliedCap = rawKwh / (soc / 100.0);
                    double ratio = impliedCap / nominal;
                    if (ratio < 0.5 || ratio > 1.5) {
                        // Raw value is garbage — use computed
                        return computedKwh;
                    }
                }
                
                boolean isPhev = nominal < 30.0;
                if (isPhev) {
                    return computedKwh;
                }
                // BEV with valid raw value: use it
                if (rawKwh > 0) return rawKwh;
                // BEV with no raw value: use computed
                return computedKwh;
            }
        } catch (Exception e) { /* fall through to raw */ }

        // SohEstimator not ready: use raw BMS value if available
        if (rawKwh > 0) return rawKwh;

        return 0.0;
    }
    
    public JSONObject getAllData() {
        JSONObject json = new JSONObject();
        BydVehicleData vd = getVd();
        
        try {
            // Battery voltage (old format for BatteryMonitor compatibility)
            if (vd != null && vd.voltageLevelRaw != BydVehicleData.UNAVAILABLE) {
                JSONObject bvJson = new JSONObject();
                bvJson.put("level", vd.voltageLevelRaw);
                bvJson.put("levelName", vd.voltageLevelRaw == 1 ? "NORMAL" : vd.voltageLevelRaw == 0 ? "LOW" : "INVALID");
                json.put("batteryVoltage", bvJson);
            }
            
            // Battery power (old format)
            if (vd != null && !Double.isNaN(vd.voltage12v)) {
                JSONObject bpJson = new JSONObject();
                bpJson.put("voltageVolts", vd.voltage12v);
                bpJson.put("isWarning", vd.voltage12v < 11.5);
                bpJson.put("isCritical", vd.voltage12v < 10.5);
                bpJson.put("healthStatus", vd.voltage12v < 10.5 ? "CRITICAL" : vd.voltage12v < 11.5 ? "WARNING" : "NORMAL");
                json.put("batteryPower", bpJson);
            }
            
            // Battery SOC (old format)
            if (vd != null && !Double.isNaN(vd.socPercent)) {
                JSONObject bsJson = new JSONObject();
                bsJson.put("socPercent", vd.socPercent);
                bsJson.put("isLow", vd.socPercent < 20);
                bsJson.put("isCritical", vd.socPercent < 10);
                json.put("batterySoc", bsJson);
            }
            
            // Charging state (old format)
            if (vd != null && vd.chargingState != BydVehicleData.UNAVAILABLE) {
                ChargingStateData cs = new ChargingStateData(vd.chargingState);
                // Prefer externalChargingPowerKw (InstrumentDevice) over chargingPowerKw (ChargingDevice)
                if (!Double.isNaN(vd.externalChargingPowerKw) && vd.externalChargingPowerKw > 0) {
                    cs.updateChargingPower(vd.externalChargingPowerKw);
                } else if (!Double.isNaN(vd.chargingPowerKw) && vd.chargingPowerKw > 0) {
                    cs.updateChargingPower(vd.chargingPowerKw);
                }
                JSONObject csJson = new JSONObject();
                csJson.put("stateCode", cs.stateCode);
                csJson.put("stateName", cs.stateName);
                csJson.put("status", cs.status.name());
                csJson.put("isError", cs.isError);
                csJson.put("chargingPowerKW", cs.chargingPowerKW);
                csJson.put("isDischarging", cs.isDischarging);
                csJson.put("isEstimated", cs.isEstimated);
                json.put("chargingState", csJson);
            }
            
            // Driving range (old format)
            if (vd != null && vd.elecRangeKm != BydVehicleData.UNAVAILABLE) {
                JSONObject drJson = new JSONObject();
                drJson.put("elecRangeKm", vd.elecRangeKm);
                drJson.put("fuelRangeKm", vd.fuelRangeKm != BydVehicleData.UNAVAILABLE ? vd.fuelRangeKm : 0);
                drJson.put("totalRangeKm", vd.elecRangeKm + (vd.fuelRangeKm != BydVehicleData.UNAVAILABLE ? vd.fuelRangeKm : 0));
                json.put("drivingRange", drJson);
            }
            
            // Battery thermal (old format)
            if (vd != null && (!Double.isNaN(vd.highCellTempC) || !Double.isNaN(vd.avgCellTempC))) {
                JSONObject btJson = new JSONObject();
                if (!Double.isNaN(vd.highCellTempC)) btJson.put("highestTempC", vd.highCellTempC);
                if (!Double.isNaN(vd.lowCellTempC)) btJson.put("lowestTempC", vd.lowCellTempC);
                if (!Double.isNaN(vd.avgCellTempC)) btJson.put("averageTempC", vd.avgCellTempC);
                json.put("batteryThermal", btJson);
            }
            
            json.put("timestamp", System.currentTimeMillis());
        } catch (Exception e) {
            logger.error("Failed to create JSON", e);
        }
        
        return json;
    }
    
    public Map<String, Boolean> getAvailability() {
        Map<String, Boolean> availability = new HashMap<>();
        BydDataCollector c = BydDataCollector.getInstance();
        boolean ready = c.isInitialized();
        availability.put("batteryVoltage", ready);
        availability.put("batteryPower", ready || batteryPowerMonitor.isAvailable());
        availability.put("batterySoc", ready);
        availability.put("chargingState", ready);
        availability.put("drivingRange", ready);
        availability.put("batteryThermal", ready);
        return availability;
    }
    
    // ==================== MONITOR ACCESS (kept for backward compat) ====================
    
    public BatteryPowerMonitor getBatteryPowerMonitor() { return batteryPowerMonitor; }
    
    // These return null now — consumers should use the data access methods above
    public BatteryVoltageMonitor getBatteryVoltageMonitor() { return null; }
    public DrivingRangeMonitor getDrivingRangeMonitor() { return null; }
    public ChargingStateMonitor getChargingStateMonitor() { return null; }
    
    // ==================== LISTENER MANAGEMENT ====================
    
    public void addListener(VehicleDataListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }
    
    public void removeListener(VehicleDataListener listener) {
        if (listener != null) listeners.remove(listener);
    }
    
    public void notifyBatteryVoltageChanged(BatteryVoltageData data) {
        for (VehicleDataListener l : listeners) { try { l.onBatteryVoltageChanged(data); } catch (Exception ignored) {} }
    }
    
    public void notifyBatteryPowerChanged(BatteryPowerData data) {
        for (VehicleDataListener l : listeners) { try { l.onBatteryPowerChanged(data); } catch (Exception ignored) {} }
    }
    
    public void notifyChargingStateChanged(ChargingStateData data) {
        for (VehicleDataListener l : listeners) { try { l.onChargingStateChanged(data); } catch (Exception ignored) {} }
    }
    
    public void notifyChargingPowerChanged(double powerKW) {
        for (VehicleDataListener l : listeners) { try { l.onChargingPowerChanged(powerKW); } catch (Exception ignored) {} }
    }
    
    public void notifyDataUnavailable(String monitorName, String reason) {
        for (VehicleDataListener l : listeners) { try { l.onDataUnavailable(monitorName, reason); } catch (Exception ignored) {} }
    }
}

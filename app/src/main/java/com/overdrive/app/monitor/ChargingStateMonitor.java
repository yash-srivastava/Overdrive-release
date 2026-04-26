package com.overdrive.app.monitor;

import android.content.Context;
import android.hardware.bydauto.charging.AbsBYDAutoChargingListener;
import android.hardware.bydauto.instrument.AbsBYDAutoInstrumentListener;

import java.lang.reflect.Method;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Monitor for battery charging state and power.
 * 
 * Power source priority (all require PermissionBypassContext):
 * 1. BYDAutoEngineDevice.getEnginePower() — direct HV battery power (-6 kW = 6 kW charging)
 * 2. BYDAutoInstrumentDevice.getExternalChargingPower() — charger-reported power
 * 3. BYDAutoChargingDevice.getChargingPower() — often broken (returns 0)
 * 4. V×I from ChargingDevice voltage/current
 * 5. ΔCapacity/ΔTime calculation with hotel load compensation
 */
public class ChargingStateMonitor extends BaseDeviceMonitor<ChargingStateData> {
    
    private Object chargingDevice;
    private Object instrumentDevice;
    private Object engineDevice;  // For direct charging power via getEnginePower()
    private Object acDevice;  // For hotel load detection
    private final AtomicReference<ChargingStateData> cachedData = new AtomicReference<>();
    private final ChargingListener chargingListener = new ChargingListener();
    private final InstrumentListener instrumentListener = new InstrumentListener();
    private VehicleDataMonitor vehicleDataMonitor;
    
    // SOTA: Longer window for more accurate power calculation
    private static final long SMOOTHING_WINDOW_MS = 30000; // 30 second window
    private final LinkedList<CapacityPoint> history = new LinkedList<>();
    
    // SOTA: Hotel Load Compensation
    // Dashboard shows Total Wall Power = Battery Power + OBC Losses + Hotel Load
    private static final double BASE_SYSTEM_LOAD_KW = 1.5;    // Car ON: screen + computers + pumps + HVAC standby
    private static final double EFFICIENCY_AC = 0.89;
    private static final double EFFICIENCY_DC = 0.95;
    private static final double AC_COMPRESSOR_LOAD_KW = 1.0;  // Additional when A/C compressor active
    
    // Track when we last received a real power update from InstrumentDevice
    private volatile long lastInstrumentPowerUpdateMs = 0;
    private static final long INSTRUMENT_POWER_TIMEOUT_MS = 10000; // 10 seconds
    
    /**
     * Data point for capacity history.
     */
    private static class CapacityPoint {
        final long time;
        final double capacity;
        
        CapacityPoint(long time, double capacity) {
            this.time = time;
            this.capacity = capacity;
        }
    }
    
    public ChargingStateMonitor() {
        super("ChargingStateMonitor");
    }
    
    /**
     * Set reference to VehicleDataMonitor for accessing other vehicle data.
     */
    public void setVehicleDataMonitor(VehicleDataMonitor monitor) {
        this.vehicleDataMonitor = monitor;
    }
    
    @Override
    public void init(Context context) {
        // Wrap context with PermissionBypassContext for BYD hardware access
        // Without this, BYD device APIs (e.g. getEnginePower) return 0
        Context permissiveContext;
        try {
            permissiveContext = new com.overdrive.app.daemon.DaemonBootstrap.PermissionBypassContext(context);
        } catch (Exception e) {
            log("PermissionBypassContext failed, using raw context: " + e.getMessage());
            permissiveContext = context;
        }
        this.context = permissiveContext;
        
        try {
            log("Initializing BYDAutoChargingDevice via reflection...");
            
            Class<?> chargingDeviceClass = Class.forName("android.hardware.bydauto.charging.BYDAutoChargingDevice");
            Method getChargingInstance = chargingDeviceClass.getMethod("getInstance", Context.class);
            chargingDevice = getChargingInstance.invoke(null, context);
            
            if (chargingDevice == null) {
                logError("BYDAutoChargingDevice.getInstance returned null", null);
                markUnavailable();
                return;
            }
            
            log("Got real BYDAutoChargingDevice: " + chargingDevice.getClass().getName());
            
            // Also get InstrumentDevice for charging power
            log("Initializing BYDAutoInstrumentDevice for charging power...");
            Class<?> instrumentDeviceClass = Class.forName("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice");
            Method getInstrumentInstance = instrumentDeviceClass.getMethod("getInstance", Context.class);
            instrumentDevice = getInstrumentInstance.invoke(null, context);
            
            if (instrumentDevice != null) {
                log("Got real BYDAutoInstrumentDevice: " + instrumentDevice.getClass().getName());
                
                // Get initial charging power
                try {
                    Method getExternalChargingPower = instrumentDeviceClass.getMethod("getExternalChargingPower");
                    double initialPower = (Double) getExternalChargingPower.invoke(instrumentDevice);
                    // BYD returns 104857.5 as sentinel — reject > 500 KW
                    if (initialPower > 0 && initialPower < 500) {
                        log("Initial charging power: " + String.format("%.2f", initialPower) + " KW");
                    } else {
                        log("Initial charging power invalid (" + String.format("%.2f", initialPower) + " KW) — will use fallback");
                    }
                } catch (Exception e) {
                    log("Could not get initial charging power: " + e.getMessage());
                }
            } else {
                log("WARNING: BYDAutoInstrumentDevice unavailable - will use capacity-based power calculation");
            }
            
            // Init AC device for hotel load detection
            try {
                Class<?> acDeviceClass = Class.forName("android.hardware.bydauto.ac.BYDAutoAcDevice");
                Method getAcInstance = acDeviceClass.getMethod("getInstance", Context.class);
                acDevice = getAcInstance.invoke(null, context);
                if (acDevice != null) {
                    log("BYDAutoAcDevice initialized for hotel load detection");
                }
            } catch (Exception e) {
                log("BYDAutoAcDevice unavailable: " + e.getMessage());
            }
            
            // Init EngineDevice for direct charging power
            // getEnginePower() returns -6 kW during charging with PermissionBypassContext
            try {
                Class<?> engineClass = Class.forName("android.hardware.bydauto.engine.BYDAutoEngineDevice");
                Method getEngineInstance = engineClass.getMethod("getInstance", Context.class);
                engineDevice = getEngineInstance.invoke(null, context);
                if (engineDevice != null) {
                    log("BYDAutoEngineDevice initialized for charging power");
                }
            } catch (Exception e) {
                log("BYDAutoEngineDevice unavailable: " + e.getMessage());
            }
            
            // Get initial state
            try {
                Method getBatteryManagementDeviceState = chargingDeviceClass.getMethod("getBatteryManagementDeviceState");
                int initialState = (Integer) getBatteryManagementDeviceState.invoke(chargingDevice);
                ChargingStateData data = new ChargingStateData(initialState);
                data.updateChargingPower(0.0); // Start with 0 power
                log("Initial charging state: " + initialState);
                cachedData.set(data);
                
            } catch (Exception e) {
                logError("Failed to get initial charging state", e);
            }
            
            markAvailable();
            log("Initialized successfully");
            
        } catch (Exception e) {
            logError("Initialization failed", e);
            markUnavailable();
        }
    }
    
    @Override
    public void start() {
        if (chargingDevice == null) {
            logError("Cannot start - charging device not initialized", null);
            return;
        }
        
        if (isRunning.getAndSet(true)) {
            log("Already running");
            return;
        }
        
        try {
            // Register charging listener
            log("Registering charging listener via reflection...");
            Class<?> chargingDeviceClass = chargingDevice.getClass();
            Class<?> chargingListenerClass = Class.forName("android.hardware.bydauto.charging.AbsBYDAutoChargingListener");
            Method registerChargingListener = chargingDeviceClass.getMethod("registerListener", chargingListenerClass);
            registerChargingListener.invoke(chargingDevice, chargingListener);
            log("Charging listener registered");
            
            // Register instrument listener for charging power
            if (instrumentDevice != null) {
                log("Registering instrument listener for charging power...");
                Class<?> instrumentDeviceClass = instrumentDevice.getClass();
                Class<?> instrumentListenerClass = Class.forName("android.hardware.bydauto.instrument.AbsBYDAutoInstrumentListener");
                Method registerInstrumentListener = instrumentDeviceClass.getMethod("registerListener", instrumentListenerClass);
                registerInstrumentListener.invoke(instrumentDevice, instrumentListener);
                log("Instrument listener registered");
            }
            
            log("Started successfully");
        } catch (Exception e) {
            logError("Failed to register listener", e);
            isRunning.set(false);
            markUnavailable();
        }
    }

    @Override
    public void stop() {
        if (!isRunning.getAndSet(false)) {
            log("Already stopped");
            return;
        }
        
        cancelRetries();
        
        if (chargingDevice != null) {
            try {
                log("Unregistering charging listener via reflection...");
                Class<?> chargingDeviceClass = chargingDevice.getClass();
                Class<?> chargingListenerClass = Class.forName("android.hardware.bydauto.charging.AbsBYDAutoChargingListener");
                Method unregisterChargingListener = chargingDeviceClass.getMethod("unregisterListener", chargingListenerClass);
                unregisterChargingListener.invoke(chargingDevice, chargingListener);
                log("Charging listener unregistered");
            } catch (Exception e) {
                logError("Failed to unregister charging listener", e);
            }
        }
        
        if (instrumentDevice != null) {
            try {
                log("Unregistering instrument listener...");
                Class<?> instrumentDeviceClass = instrumentDevice.getClass();
                Class<?> instrumentListenerClass = Class.forName("android.hardware.bydauto.instrument.AbsBYDAutoInstrumentListener");
                Method unregisterInstrumentListener = instrumentDeviceClass.getMethod("unregisterListener", instrumentListenerClass);
                unregisterInstrumentListener.invoke(instrumentDevice, instrumentListener);
                log("Instrument listener unregistered");
            } catch (Exception e) {
                logError("Failed to unregister instrument listener", e);
            }
        }
        
        log("Stopped successfully");
    }
    
    @Override
    public ChargingStateData getCurrentValue() {
        return cachedData.get();
    }
    
    @Override
    public long getLastUpdateTime() {
        ChargingStateData data = cachedData.get();
        return data != null ? data.timestamp : 0;
    }
    
    /**
     * Update the cached power value.
     */
    private void updateCachedPower(double powerKW) {
        ChargingStateData currentData = cachedData.get();
        if (currentData != null) {
            ChargingStateData newData = new ChargingStateData(currentData.stateCode);
            newData.updateChargingPower(powerKW);
            cachedData.set(newData);
        }
    }
    
    /**
     * Fallback: Power Calculation using rolling window (if InstrumentDevice unavailable).
     * Power (KW) = ΔCapacity (KWH) / ΔTime (hours)
     */
    private void updatePowerFromCapacity(double currentCapacityKWH) {
        // PRIORITY 1: BYDAutoEngineDevice.getEnginePower()
        // Returns -6 kW during charging (negative = into battery). Use absolute value.
        if (engineDevice != null) {
            try {
                Method getEnginePower = engineDevice.getClass().getMethod("getEnginePower");
                Object result = getEnginePower.invoke(engineDevice);
                double power = (result instanceof Number) ? ((Number) result).doubleValue() : 0;
                if (Math.abs(power) > 0.1 && Math.abs(power) < 500) {
                    updateCachedPower(Math.abs(power));
                    if (System.currentTimeMillis() % 30000 < 2000) {
                        log("Charging power (EngineDevice): " + String.format("%.2f", Math.abs(power)) + " KW (raw: " + power + ")");
                    }
                    return;
                }
            } catch (Exception e) { /* not available */ }
        }
        
        // Skip remaining fallbacks if we have recent real power from InstrumentDevice
        if (instrumentDevice != null && 
            (System.currentTimeMillis() - lastInstrumentPowerUpdateMs) < INSTRUMENT_POWER_TIMEOUT_MS &&
            lastInstrumentPowerUpdateMs > 0) {
            return;
        }
        
        // PRIORITY 1: Try polling InstrumentDevice directly (callback may not fire on some models)
        if (instrumentDevice != null && lastInstrumentPowerUpdateMs == 0) {
            try {
                Method getExternalChargingPower = instrumentDevice.getClass().getMethod("getExternalChargingPower");
                double polledPower = (Double) getExternalChargingPower.invoke(instrumentDevice);
                if (polledPower > 0.1 && polledPower < 500) {
                    lastInstrumentPowerUpdateMs = System.currentTimeMillis();
                    updateCachedPower(Math.abs(polledPower));
                    log("Charging power (InstrumentDevice polled): " + String.format("%.2f", polledPower) + " KW");
                    return;
                }
            } catch (Exception e) { /* not available */ }
        }
        
        // Try ChargingDevice.getChargingPower() directly
        // The callback onChargingPowerChanged is broken (always 0) but polling may work
        if (chargingDevice != null) {
            try {
                Method getChargingPower = chargingDevice.getClass().getMethod("getChargingPower");
                Object result = getChargingPower.invoke(chargingDevice);
                double power = (result instanceof Number) ? ((Number) result).doubleValue() : 0;
                if (System.currentTimeMillis() % 60000 < 2000) {
                    log("ChargingDevice.getChargingPower() raw: " + power);
                }
                if (power > 0.1 && power < 500) {
                    updateCachedPower(power);
                    if (System.currentTimeMillis() % 30000 < 2000) {
                        log("Charging power (ChargingDevice): " + String.format("%.2f", power) + " KW");
                    }
                    return;
                }
            } catch (Exception e) { /* not available */ }
        }
        
        // Try ChargingDevice.getChargingCapacity() as total energy (not delta)
        // If this returns total KWh charged in session, we can diff it
        if (chargingDevice != null) {
            try {
                Method getChargingCapacity = chargingDevice.getClass().getMethod("getChargingCapacity");
                Object result = getChargingCapacity.invoke(chargingDevice);
                double capacity = (result instanceof Number) ? ((Number) result).doubleValue() : 0;
                if (System.currentTimeMillis() % 60000 < 2000) {
                    log("ChargingDevice.getChargingCapacity() raw: " + String.format("%.4f", capacity));
                }
            } catch (Exception e) { /* not available */ }
        }
        
        // Fallback: V×I from ChargingDevice
        if (chargingDevice != null) {
            try {
                Class<?> cls = chargingDevice.getClass();
                // Try multiple method names — BYD SDK varies by model
                double voltage = 0, current = 0;
                
                // Try getChargingVoltage/getChargingCurrent
                try {
                    Method getVoltage = cls.getMethod("getChargingVoltage");
                    Method getCurrent = cls.getMethod("getChargingCurrent");
                    voltage = ((Number) getVoltage.invoke(chargingDevice)).doubleValue();
                    current = ((Number) getCurrent.invoke(chargingDevice)).doubleValue();
                } catch (NoSuchMethodException e1) {
                    // Try getBatteryVoltage/getBatteryCurrent
                    try {
                        Method getVoltage = cls.getMethod("getBatteryVoltage");
                        Method getCurrent = cls.getMethod("getBatteryCurrent");
                        voltage = ((Number) getVoltage.invoke(chargingDevice)).doubleValue();
                        current = ((Number) getCurrent.invoke(chargingDevice)).doubleValue();
                    } catch (NoSuchMethodException e2) {
                        // Try getChargeVoltage/getChargeCurrent
                        try {
                            Method getVoltage = cls.getMethod("getChargeVoltage");
                            Method getCurrent = cls.getMethod("getChargeCurrent");
                            voltage = ((Number) getVoltage.invoke(chargingDevice)).doubleValue();
                            current = ((Number) getCurrent.invoke(chargingDevice)).doubleValue();
                        } catch (NoSuchMethodException e3) {
                            // None available
                        }
                    }
                }
                
                // Log raw values for debugging (once per 30s)
                if (System.currentTimeMillis() % 30000 < 2000) {
                    log("V×I raw: voltage=" + String.format("%.1f", voltage) + "V, current=" + String.format("%.2f", current) + "A");
                }
                
                if (voltage > 10 && current > 0.1) {
                    double powerKW = (voltage * current) / 1000.0;
                    // Sanity: reject > 500 KW
                    if (powerKW < 500) {
                        updateCachedPower(powerKW);
                        if (System.currentTimeMillis() % 30000 < 2000) {
                            log("Charging power (V×I): " + String.format("%.2f", powerKW) + " KW");
                        }
                        return;
                    }
                }
            } catch (Exception e) {
                if (System.currentTimeMillis() % 60000 < 2000) {
                    log("V×I failed: " + e.getMessage());
                }
            }
        }
        
        long now = System.currentTimeMillis();
        
        // 1. Add to window
        history.addLast(new CapacityPoint(now, currentCapacityKWH));
        
        // 2. Prune old data (Keep only last SMOOTHING_WINDOW_MS)
        while (!history.isEmpty() && (now - history.getFirst().time > SMOOTHING_WINDOW_MS)) {
            history.removeFirst();
        }
        
        // 3. Calculate Average Power over the window
        if (history.size() > 1) {
            CapacityPoint start = history.getFirst();
            CapacityPoint end = history.getLast();
            
            long deltaTimeMs = end.time - start.time;
            
            // Guard: Ignore if total window is too short (< 1 sec) to prevent startup spikes
            if (deltaTimeMs > 1000) {
                double deltaCapacityKWH = end.capacity - start.capacity;
                double deltaTimeHours = deltaTimeMs / 3600000.0;
                
                // Calculate power: KW = KWH / hours
                double powerKW = deltaCapacityKWH / deltaTimeHours;
                
                // Sanity check: power should be reasonable (0-150 KW for most EVs)
                if (Math.abs(powerKW) <= 150) {
                    // SOTA: Compensate for OBC efficiency + hotel load
                    double wallPower = estimateWallPower(Math.abs(powerKW));
                    updateCachedPower(wallPower);
                    if (now % 30000 < 2000) {
                        log("Charging power: " + String.format("%.2f", wallPower) + " KW" +
                            " (battery: " + String.format("%.2f", Math.abs(powerKW)) + " KW)");
                    }
                }
            }
        }
    }
    
    // ==================== HOTEL LOAD COMPENSATION ====================
    
    /**
     * Convert net battery power → gross wall power.
     * Adds OBC efficiency loss + hotel load (AC, TMS, systems).
     */
    private double estimateWallPower(double batteryPowerKw) {
        if (batteryPowerKw <= 0) return 0;
        
        // 1. OBC efficiency (AC < 22kW, DC above)
        double efficiency = (batteryPowerKw < 22.0) ? EFFICIENCY_AC : EFFICIENCY_DC;
        double grossPower = batteryPowerKw / efficiency;
        
        // 2. Hotel load — base includes HVAC standby + systems
        double hotelLoad = BASE_SYSTEM_LOAD_KW;
        
        // Additional if A/C compressor is actively running
        if (isAcOn()) {
            hotelLoad += AC_COMPRESSOR_LOAD_KW;
        }
        
        return grossPower + hotelLoad;
    }
    
    /**
     * Check if A/C compressor is running via BYDAutoAcDevice.
     */
    private boolean isAcOn() {
        if (acDevice == null) return false;
        try {
            Method getComp = acDevice.getClass().getMethod("getCompressorSwitch");
            int state = ((Number) getComp.invoke(acDevice)).intValue();
            return state == 1;
        } catch (Exception e) {
            // Try alternative method
            try {
                Method getAcState = acDevice.getClass().getMethod("getAirConditionerState");
                int state = ((Number) getAcState.invoke(acDevice)).intValue();
                return state == 1;
            } catch (Exception e2) {
                return false;
            }
        }
    }
    
    /**
     * Listener for instrument device - provides real charging power.
     */
    private class InstrumentListener extends AbsBYDAutoInstrumentListener {
        @Override
        public void onExternalChargingPowerChanged(double powerKW) {
            // BYD returns 104857.5 as sentinel — reject > 500 KW
            if (Math.abs(powerKW) > 500) return;
            log("Charging power changed: " + String.format("%.2f", powerKW) + " KW");
            lastInstrumentPowerUpdateMs = System.currentTimeMillis();
            updateCachedPower(Math.abs(powerKW));
        }
    }
    
    /**
     * Listener for charging state and capacity changes.
     */
    private class ChargingListener extends AbsBYDAutoChargingListener {
        @Override
        public void onBatteryManagementDeviceStateChanged(int state) {
            log("Charging state changed: " + state);
            
            try {
                ChargingStateData newData = new ChargingStateData(state);
                
                // FIX: If we are NOT charging, force power to 0 immediately
                // Do NOT copy old power values
                // State 1 = CHARGING_BATTERY_STATE_CHARGING
                if (state != 1) {
                    newData.updateChargingPower(0.0);
                    history.clear(); // Clear history so next charge starts fresh
                } else {
                    // If we just started charging, keep 0 until we get capacity updates
                    newData.updateChargingPower(0.0);
                    history.clear(); // Start fresh
                }
                
                cachedData.set(newData);
                
                if (newData.isError) {
                    log("ERROR: Charging breakdown detected - " + newData.errorType);
                }
                
            } catch (Exception e) {
                logError("Failed to process charging state change", e);
            }
        }
        
        @Override
        public void onChargingCapacityChanged(double capacityKWH) {
            // Debug: log raw value periodically
            if (System.currentTimeMillis() % 60000 < 2000) {
                log("Capacity from SDK: " + String.format("%.4f", capacityKWH) + " KWh");
            }
            // Fallback: Calculate power from capacity change rate (if InstrumentDevice unavailable)
            updatePowerFromCapacity(capacityKWH);
        }
        
        @Override
        public void onChargingPowerChanged(double power) {
            // This callback from ChargingDevice is broken (always 0), so we ignore it
            // Real power comes from InstrumentDevice.onExternalChargingPowerChanged()
        }
    }
}

package com.overdrive.app.monitor;

import android.content.Context;
import android.hardware.bydauto.statistic.AbsBYDAutoStatisticListener;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Monitor for driving range (electric and fuel).
 * 
 * Uses BYDAutoStatisticDevice with listener-based updates for real-time range changes.
 */
public class DrivingRangeMonitor extends BaseDeviceMonitor<DrivingRangeData> {
    
    private Object device;  // Real BYDAutoStatisticDevice from system
    private final AtomicReference<DrivingRangeData> cachedData = new AtomicReference<>();
    private final RangeListener listener = new RangeListener();
    
    public DrivingRangeMonitor() {
        super("DrivingRangeMonitor");
    }
    
    @Override
    public void init(Context context) {
        this.context = context;
        
        try {
            log("Initializing BYDAutoStatisticDevice via reflection...");
            
            // Context is already PermissionBypassContext
            Class<?> deviceClass = Class.forName("android.hardware.bydauto.statistic.BYDAutoStatisticDevice");
            Method getInstance = deviceClass.getMethod("getInstance", Context.class);
            device = getInstance.invoke(null, context);
            
            if (device == null) {
                logError("BYDAutoStatisticDevice.getInstance returned null", null);
                markUnavailable();
                return;
            }
            
            log("Got real BYDAutoStatisticDevice: " + device.getClass().getName());
            
            // Get initial values
            try {
                int elecRange = getElecRangeFromDevice();
                int fuelRange = getFuelRangeFromDevice();
                
                DrivingRangeData data = new DrivingRangeData(elecRange, fuelRange);
                cachedData.set(data);
                
                log("Initial range - Electric: " + elecRange + " km, Fuel: " + fuelRange + " km, Total: " + data.totalRangeKm + " km");
                
            } catch (Exception e) {
                logError("Failed to get initial range values", e);
            }
            
            markAvailable();
            log("Initialized successfully");
            
        } catch (Exception e) {
            logError("Initialization failed", e);
            markUnavailable();
        }
    }
    
    private int getElecRangeFromDevice() {
        try {
            Class<?> deviceClass = device.getClass();
            Method getElecDrivingRangeValue = deviceClass.getMethod("getElecDrivingRangeValue");
            return (Integer) getElecDrivingRangeValue.invoke(device);
        } catch (Exception e) {
            logError("Failed to get electric range", e);
            return 0;
        }
    }
    
    private int getFuelRangeFromDevice() {
        try {
            Class<?> deviceClass = device.getClass();
            Method getFuelDrivingRangeValue = deviceClass.getMethod("getFuelDrivingRangeValue");
            return (Integer) getFuelDrivingRangeValue.invoke(device);
        } catch (Exception e) {
            // Pure EV may not have this method
            return 0;
        }
    }
    
    @Override
    public void start() {
        if (device == null) {
            logError("Cannot start - device not initialized", null);
            return;
        }
        
        if (isRunning.getAndSet(true)) {
            log("Already running");
            return;
        }
        
        try {
            log("Registering listener via reflection...");
            Class<?> deviceClass = device.getClass();
            Class<?> listenerClass = Class.forName("android.hardware.bydauto.statistic.AbsBYDAutoStatisticListener");
            Method registerListener = deviceClass.getMethod("registerListener", listenerClass);
            registerListener.invoke(device, listener);
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
        
        if (device != null) {
            try {
                log("Unregistering listener via reflection...");
                Class<?> deviceClass = device.getClass();
                Class<?> listenerClass = Class.forName("android.hardware.bydauto.statistic.AbsBYDAutoStatisticListener");
                Method unregisterListener = deviceClass.getMethod("unregisterListener", listenerClass);
                unregisterListener.invoke(device, listener);
                log("Stopped successfully");
            } catch (Exception e) {
                logError("Failed to unregister listener", e);
            }
        }
    }
    
    @Override
    public DrivingRangeData getCurrentValue() {
        return cachedData.get();
    }
    
    @Override
    public long getLastUpdateTime() {
        DrivingRangeData data = cachedData.get();
        return data != null ? data.timestamp : 0;
    }
    
    /**
     * Listener for range changes from BYD statistic device.
     */
    private class RangeListener extends AbsBYDAutoStatisticListener {
        
        @Override
        public void onElecDrivingRangeChanged(int range) {
            log("Electric range changed: " + range + " km");
            
            try {
                DrivingRangeData currentData = cachedData.get();
                int fuelRange = currentData != null ? currentData.fuelRangeKm : 0;
                
                DrivingRangeData newData = new DrivingRangeData(range, fuelRange);
                cachedData.set(newData);
                
                if (newData.isCritical) {
                    log("CRITICAL: Range is critically low (" + newData.totalRangeKm + " km)!");
                } else if (newData.isLow) {
                    log("WARNING: Range is low (" + newData.totalRangeKm + " km)");
                }
                
            } catch (Exception e) {
                logError("Failed to process electric range change", e);
            }
        }
        
        @Override
        public void onFuelDrivingRangeChanged(int range) {
            log("Fuel range changed: " + range + " km");
            
            try {
                DrivingRangeData currentData = cachedData.get();
                int elecRange = currentData != null ? currentData.elecRangeKm : 0;
                
                DrivingRangeData newData = new DrivingRangeData(elecRange, range);
                cachedData.set(newData);
                
            } catch (Exception e) {
                logError("Failed to process fuel range change", e);
            }
        }
        
        @Override
        public void onElecPercentageChanged(double percentage) {
            // SOC change - logged for debugging
            log("SOC changed: " + percentage + "%");
        }
    }
}

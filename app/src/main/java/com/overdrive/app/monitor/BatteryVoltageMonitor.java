package com.overdrive.app.monitor;

import android.content.Context;
import android.hardware.bydauto.bodywork.AbsBYDAutoBodyworkListener;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Monitor for 12V battery voltage level status.
 * 
 * Monitors the battery voltage level (LOW/NORMAL/INVALID) via BYDAutoBodyworkDevice.
 * Uses reflection and PermissionBypassContext to access real BYD hardware API.
 */
public class BatteryVoltageMonitor extends BaseDeviceMonitor<BatteryVoltageData> {
    
    private Object device;  // Real BYDAutoBodyworkDevice from system
    private final AtomicReference<BatteryVoltageData> cachedData = new AtomicReference<>();
    private final BodyworkListener listener = new BodyworkListener();
    
    public BatteryVoltageMonitor() {
        super("BatteryVoltageMonitor");
    }
    
    @Override
    public void init(Context context) {
        this.context = context;
        
        try {
            log("Initializing BYDAutoBodyworkDevice via reflection...");
            
            // Context is already PermissionBypassContext from AccSentryDaemon
            // Get real device via reflection
            Class<?> deviceClass = Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice");
            Method getInstance = deviceClass.getMethod("getInstance", Context.class);
            device = getInstance.invoke(null, context);
            
            if (device == null) {
                logError("BYDAutoBodyworkDevice.getInstance returned null", null);
                markUnavailable();
                return;
            }
            
            log("Got real BYDAutoBodyworkDevice: " + device.getClass().getName());
            
            // Get initial value
            try {
                Method getBatteryVoltageLevel = deviceClass.getMethod("getBatteryVoltageLevel");
                int initialLevel = (Integer) getBatteryVoltageLevel.invoke(device);
                cachedData.set(new BatteryVoltageData(initialLevel));
                log("Initial battery voltage level: " + initialLevel);
            } catch (Exception e) {
                logError("Failed to get initial battery voltage level", e);
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
            Class<?> listenerClass = Class.forName("android.hardware.bydauto.bodywork.AbsBYDAutoBodyworkListener");
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
                Class<?> listenerClass = Class.forName("android.hardware.bydauto.bodywork.AbsBYDAutoBodyworkListener");
                Method unregisterListener = deviceClass.getMethod("unregisterListener", listenerClass);
                unregisterListener.invoke(device, listener);
                log("Stopped successfully");
            } catch (Exception e) {
                logError("Failed to unregister listener", e);
            }
        }
    }
    
    @Override
    public BatteryVoltageData getCurrentValue() {
        return cachedData.get();
    }
    
    @Override
    public long getLastUpdateTime() {
        BatteryVoltageData data = cachedData.get();
        return data != null ? data.timestamp : 0;
    }
    
    /**
     * Listener for battery voltage level changes.
     */
    private class BodyworkListener extends AbsBYDAutoBodyworkListener {
        @Override
        public void onBatteryVoltageLevelChanged(int level) {
            log("Battery voltage level changed: " + level);
            
            try {
                BatteryVoltageData newData = new BatteryVoltageData(level);
                cachedData.set(newData);
                
                if (newData.isWarning) {
                    log("WARNING: Battery voltage level is LOW!");
                }
                
            } catch (Exception e) {
                logError("Failed to process voltage level change", e);
            }
        }
    }
}

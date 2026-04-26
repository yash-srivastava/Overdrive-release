package com.overdrive.app.monitor;

import android.content.Context;
import android.hardware.bydauto.statistic.AbsBYDAutoStatisticListener;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Monitor for battery State of Charge (SOC).
 * 
 * Monitors the remaining battery power percentage via BYDAutoStatisticDevice.
 * Uses listener-based updates for real-time SOC changes.
 */
public class BatterySocMonitor extends BaseDeviceMonitor<BatterySocData> {
    
    private Object device;  // Real BYDAutoStatisticDevice from system
    private final AtomicReference<BatterySocData> cachedData = new AtomicReference<>();
    private final SocListener listener = new SocListener();
    
    public BatterySocMonitor() {
        super("BatterySocMonitor");
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
            
            // Get initial value using getElecPercentageValue() for display SOC
            try {
                Method getElecPercentageValue = deviceClass.getMethod("getElecPercentageValue");
                double initialSoc = (Double) getElecPercentageValue.invoke(device);
                cachedData.set(new BatterySocData(initialSoc));
                log("Initial battery SOC: " + initialSoc + "%");
            } catch (Exception e) {
                logError("Failed to get initial battery SOC", e);
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
            log("Registering SOC listener via reflection...");
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
                log("Unregistering SOC listener via reflection...");
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
    public BatterySocData getCurrentValue() {
        return cachedData.get();
    }
    
    @Override
    public long getLastUpdateTime() {
        BatterySocData data = cachedData.get();
        return data != null ? data.timestamp : 0;
    }
    
    /**
     * Listener for SOC changes from BYD statistic device.
     */
    private class SocListener extends AbsBYDAutoStatisticListener {
        
        @Override
        public void onElecPercentageChanged(double percentage) {
            log("SOC changed: " + percentage + "%");
            
            try {
                BatterySocData oldData = cachedData.get();
                BatterySocData newData = new BatterySocData(percentage);
                
                cachedData.set(newData);
                
                if (newData.isCritical) {
                    log("CRITICAL: Battery SOC is critically low (" + percentage + "%)!");
                } else if (newData.isLow && (oldData == null || !oldData.isLow)) {
                    log("WARNING: Battery SOC is low (" + percentage + "%)");
                }
                
            } catch (Exception e) {
                logError("Failed to process SOC change", e);
            }
        }
    }
}

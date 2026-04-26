package com.overdrive.app.monitor;

import android.content.Context;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Monitor for 12V battery power voltage.
 * 
 * Monitors the actual battery voltage in volts via BYDAutoOtaDevice.
 * Uses reflection and PermissionBypassContext to access real BYD hardware API.
 * Uses polling since OTA device may not support listeners reliably.
 */
public class BatteryPowerMonitor extends BaseDeviceMonitor<BatteryPowerData> {
    
    private Object device;  // Real BYDAutoOtaDevice from system
    private final AtomicReference<BatteryPowerData> cachedData = new AtomicReference<>();
    private Thread pollThread;
    private static final long POLL_INTERVAL_MS = 300000; // Poll every 5 min (collector handles frequent reads)
    
    public BatteryPowerMonitor() {
        super("BatteryPowerMonitor");
    }
    
    @Override
    public void init(Context context) {
        this.context = context;
        
        try {
            log("Initializing BYDAutoOtaDevice via reflection...");
            
            // Context is already PermissionBypassContext from AccSentryDaemon
            // Get real device via reflection
            Class<?> deviceClass = Class.forName("android.hardware.bydauto.ota.BYDAutoOtaDevice");
            Method getInstance = deviceClass.getMethod("getInstance", Context.class);
            device = getInstance.invoke(null, context);
            
            if (device == null) {
                logError("BYDAutoOtaDevice.getInstance returned null", null);
                markUnavailable();
                return;
            }
            
            log("Got real BYDAutoOtaDevice: " + device.getClass().getName());
            
            // Get initial value
            try {
                Method getBatteryPowerVoltage = deviceClass.getMethod("getBatteryPowerVoltage");
                double initialVoltage = (Double) getBatteryPowerVoltage.invoke(device);
                cachedData.set(new BatteryPowerData(initialVoltage));
                log("Initial battery power voltage: " + initialVoltage + "V");
            } catch (Exception e) {
                logError("Failed to get initial battery power voltage", e);
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
        
        // Start polling thread
        pollThread = new Thread(() -> {
            log("Polling thread started");
            
            while (isRunning.get()) {
                try {
                    // Poll voltage via reflection
                    Class<?> deviceClass = device.getClass();
                    Method getBatteryPowerVoltage = deviceClass.getMethod("getBatteryPowerVoltage");
                    double voltage = (Double) getBatteryPowerVoltage.invoke(device);
                    
                    BatteryPowerData oldData = cachedData.get();
                    BatteryPowerData newData = new BatteryPowerData(voltage);
                    
                    // Only log if value changed significantly (> 0.1V)
                    if (oldData == null || Math.abs(voltage - oldData.voltageVolts) > 0.1) {
                        log("Battery power voltage: " + voltage + "V");
                        
                        if (newData.isCritical) {
                            log("CRITICAL: Battery voltage is critically low (" + voltage + "V < 10.5V)!");
                        } else if (newData.isWarning) {
                            log("WARNING: Battery voltage is low (" + voltage + "V < 11.5V)");
                        }
                        
                        if (!newData.isValidRange()) {
                            log("WARNING: Battery voltage out of valid range (9.0-16.0V): " + voltage + "V");
                        }
                    }
                    
                    cachedData.set(newData);
                    
                    Thread.sleep(POLL_INTERVAL_MS);
                    
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    logError("Polling error", e);
                    try {
                        Thread.sleep(POLL_INTERVAL_MS);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
            
            log("Polling thread stopped");
        }, "BatteryPowerPoll");
        
        pollThread.start();
        log("Started successfully (polling mode)");
    }
    
    @Override
    public void stop() {
        if (!isRunning.getAndSet(false)) {
            log("Already stopped");
            return;
        }
        
        cancelRetries();
        
        if (pollThread != null) {
            pollThread.interrupt();
            try {
                pollThread.join(1000);
            } catch (InterruptedException e) {
                // Ignore
            }
            pollThread = null;
        }
        
        log("Stopped successfully");
    }
    
    @Override
    public BatteryPowerData getCurrentValue() {
        return cachedData.get();
    }
    
    @Override
    public long getLastUpdateTime() {
        BatteryPowerData data = cachedData.get();
        return data != null ? data.timestamp : 0;
    }
}

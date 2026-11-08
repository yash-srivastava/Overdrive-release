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
    // Poll every 60s. Was 5 min — too coarse now that BatteryVoltageMonitorV2
    // consumes voltage from the same poll loop for MCU sleep/wake hysteresis,
    // and V2's hysteresis windows are minute-scale.
    private static final long POLL_INTERVAL_MS = 60_000;
    // A DiLink 5 daemon (non-app UID) is refused the OTA service bind with a SecurityException
    // that never clears; reconnecting every 30 s only floods the log.
    private static final long DI5_DAEMON_DENIED_RECONNECT_MS = 10 * 60_000L;
    private volatile boolean bindDeniedOnDi5Daemon = false;
    
    public BatteryPowerMonitor() {
        super("BatteryPowerMonitor");
    }
    
    @Override
    public void init(Context context) {
        this.context = context;
        bindDeniedOnDi5Daemon = false;
        
        try {
            log("Initializing BYDAutoOtaDevice via reflection...");
            
            // Context is already PermissionBypassContext from AccSentryDaemon
            // Get real device via reflection
            Class<?> deviceClass = Class.forName("android.hardware.bydauto.ota.BYDAutoOtaDevice");
            device = com.overdrive.app.byd.BydDeviceHelper.invokeGetInstance(deviceClass, context);
            
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
            bindDeniedOnDi5Daemon = isSecurityDenial(e)
                    && com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()
                    && android.os.Process.myUid() < android.os.Process.FIRST_APPLICATION_UID;
            markUnavailable(!bindDeniedOnDi5Daemon);
        }
    }

    @Override
    protected long reconnectIntervalMs() {
        return bindDeniedOnDi5Daemon ? DI5_DAEMON_DENIED_RECONNECT_MS : super.reconnectIntervalMs();
    }

    private static boolean isSecurityDenial(Throwable t) {
        for (int depth = 0; t != null && depth < 8; t = t.getCause(), depth++) {
            if (t instanceof SecurityException) return true;
        }
        return false;
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

                    // Fan out to BatteryVoltageMonitorV2 (acc_sentry-process MCU
                    // sleep/wake hysteresis). V2 lives in this same process,
                    // so the cross-process gap that affects the cam_daemon
                    // fan-out doesn't apply here. No-op when V2 is not running.
                    try {
                        com.overdrive.app.power.BatteryVoltageMonitorV2
                                .notifyBatteryPowerVoltage(voltage);
                    } catch (Throwable ignored) {}

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

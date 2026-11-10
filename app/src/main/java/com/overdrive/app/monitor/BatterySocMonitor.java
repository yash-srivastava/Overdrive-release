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
    
    private static final long CARSVC_POLL_INTERVAL_MS = 60_000L;
    private volatile boolean carSvcPollingMode = false;
    private Thread pollThread;
    
    public BatterySocMonitor() {
        super("BatterySocMonitor");
    }
    
    @Override
    public void init(Context context) {
        this.context = context;
        carSvcPollingMode = false;
        
        try {
            if (com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()
                    && android.os.Process.myUid() < android.os.Process.FIRST_APPLICATION_UID) {
                carSvcPollingMode = true;
                pollCarSvcSocOnce();
                markAvailable();
                log("Initialized in DiLink 5 CarSvcTelemetry VHAL polling mode");
                return;
            }

            log("Initializing BYDAutoStatisticDevice via reflection...");
            
            // Context is already PermissionBypassContext
            Class<?> deviceClass;
            if (com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) {
                // DiLink 5's getInstance() dereferences getApplicationContext(), which is null in a
                // daemon; BydDeviceHelper supplies a context that returns itself there.
                device = com.overdrive.app.byd.BydDeviceHelper.getDevice(
                        "android.hardware.bydauto.statistic.BYDAutoStatisticDevice", context);
                deviceClass = device != null ? device.getClass() : null;
            } else {
                deviceClass = Class.forName("android.hardware.bydauto.statistic.BYDAutoStatisticDevice");
                Method getInstance = deviceClass.getMethod("getInstance", Context.class);
                device = getInstance.invoke(null, context);
            }

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
        if (carSvcPollingMode) {
            if (isRunning.getAndSet(true)) {
                log("Already running");
                return;
            }
            pollThread = new Thread(() -> {
                log("CarSvcTelemetry SOC polling thread started");
                while (isRunning.get()) {
                    try {
                        pollCarSvcSocOnce();
                        Thread.sleep(CARSVC_POLL_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        break;
                    } catch (Throwable t) {
                        logError("CarSvcTelemetry SOC poll error", t);
                        try {
                            Thread.sleep(CARSVC_POLL_INTERVAL_MS);
                        } catch (InterruptedException ie) {
                            break;
                        }
                    }
                }
                log("CarSvcTelemetry SOC polling thread stopped");
            }, "BatterySocPoll");
            pollThread.setDaemon(true);
            pollThread.start();
            log("Started successfully (CarSvcTelemetry polling mode)");
            return;
        }

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
            if (com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) {
                registerOnDiLink5(deviceClass, listenerClass);
            } else {
                Method registerListener = deviceClass.getMethod("registerListener", listenerClass);
                registerListener.invoke(device, listener);
            }
            log("Started successfully");
        } catch (Exception e) {
            logError("Failed to register listener", e);
            isRunning.set(false);
            // On DiLink 5 init succeeds (resetting the backoff), so a retry here would spin every second.
            if (com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()) return;
            markUnavailable();
        }
    }
    
    /** DiLink 5 builds differ; fall back to the subscribe-all registerListener(listener, int[]). */
    private void registerOnDiLink5(Class<?> deviceClass, Class<?> listenerClass) throws Exception {
        try {
            deviceClass.getMethod("registerListener", listenerClass).invoke(device, listener);
        } catch (NoSuchMethodException | java.lang.reflect.InvocationTargetException e) {
            deviceClass.getMethod("registerListener", listenerClass, int[].class)
                    .invoke(device, listener, new int[0]);
        }
    }

    @Override
    public void stop() {
        if (!isRunning.getAndSet(false)) {
            // A monitor that never started can still have its retry loop running.
            cancelRetries();
            log("Already stopped");
            return;
        }
        
        cancelRetries();

        if (pollThread != null) {
            pollThread.interrupt();
            try {
                pollThread.join(1000);
            } catch (InterruptedException ignored) {
            }
            pollThread = null;
        }
        
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

    private void pollCarSvcSocOnce() {
        double percentage = com.overdrive.app.byd.CarSvcTelemetry.INSTANCE.socPercentValue();
        if (Double.isNaN(percentage) || percentage < 0.0 || percentage > 100.0) {
            return;
        }
        BatterySocData oldData = cachedData.get();
        BatterySocData newData = new BatterySocData(percentage);
        cachedData.set(newData);
        if (oldData == null || Math.abs(percentage - oldData.socPercent) >= 1.0) {
            log("SOC (CarSvcTelemetry): " + percentage + "%");
        }
        if (newData.isCritical) {
            log("CRITICAL: Battery SOC is critically low (" + percentage + "%)!");
        } else if (newData.isLow && (oldData == null || !oldData.isLow)) {
            log("WARNING: Battery SOC is low (" + percentage + "%)");
        }
        try {
            com.overdrive.app.power.SocCutoffMonitor.notifyElecPercentage(percentage);
        } catch (Throwable ignored) {}
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

                // Fan out to SocCutoffMonitor (acc_sentry-process voluntary
                // cutoff). Same-process hand-off; the listener that hangs off
                // BYDAutoStatisticDevice properly subclasses AbsBYDAutoStatisticListener
                // (this class), so we get true callback granularity here unlike
                // the broken Proxy path. No-op when SocCutoffMonitor is not running.
                try {
                    com.overdrive.app.power.SocCutoffMonitor
                            .notifyElecPercentage(percentage);
                } catch (Throwable ignored) {}

            } catch (Exception e) {
                logError("Failed to process SOC change", e);
            }
        }
    }
}

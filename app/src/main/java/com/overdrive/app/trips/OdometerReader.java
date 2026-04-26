package com.overdrive.app.trips;

import com.overdrive.app.logging.DaemonLogger;
import java.lang.reflect.Method;

/**
 * Reads the vehicle odometer from BYDAutoStatisticDevice via reflection.
 * Singleton — initialized once with a Context, then provides odometer readings.
 * 
 * Primary distance source for trip analytics (exact hardware reading).
 * GPS haversine is used as fallback when odometer is unavailable.
 */
public class OdometerReader {
    private static final DaemonLogger logger = DaemonLogger.getInstance("OdometerReader");
    
    private static OdometerReader instance;
    private Object statisticDevice;
    private Method getTotalMileageValueMethod;
    private boolean initialized = false;
    
    private OdometerReader() {}
    
    public static synchronized OdometerReader getInstance() {
        if (instance == null) instance = new OdometerReader();
        return instance;
    }
    
    /**
     * Initialize with a Context (PermissionBypassContext preferred).
     * Call once during daemon startup.
     */
    public void init(android.content.Context context) {
        if (initialized) return;
        try {
            Class<?> deviceClass = Class.forName("android.hardware.bydauto.statistic.BYDAutoStatisticDevice");
            Method getInstance = deviceClass.getMethod("getInstance", android.content.Context.class);
            statisticDevice = getInstance.invoke(null, context);
            getTotalMileageValueMethod = deviceClass.getMethod("getTotalMileageValue");
            initialized = true;
            logger.info("OdometerReader initialized");
        } catch (Exception e) {
            logger.warn("OdometerReader init failed (odometer unavailable): " + e.getMessage());
        }
    }
    
    /**
     * Read the current odometer value in km.
     * Returns -1 if unavailable.
     * 
     * BYD getTotalMileageValue() returns int — some models return km directly,
     * some return 0.1 km units. Auto-detect based on magnitude (>1,000,000 = 0.1 km units).
     */
    public double readOdometerKm() {
        if (!initialized || statisticDevice == null || getTotalMileageValueMethod == null) {
            return -1;
        }
        try {
            int rawOdometer = (Integer) getTotalMileageValueMethod.invoke(statisticDevice);
            if (rawOdometer <= 0) return -1;
            
            // Auto-detect unit: if raw value > 1,000,000, it's in 0.1 km (or 0.1 miles)
            double value;
            if (rawOdometer > 1_000_000) {
                value = rawOdometer / 10.0;
            } else {
                value = rawOdometer;
            }
            
            // Convert miles to km if the instrument cluster is set to miles
            double factor = com.overdrive.app.byd.BydDataCollector.getInstance().getDistanceToKmFactor();
            return value * factor;
        } catch (Exception e) {
            logger.debug("Failed to read odometer: " + e.getMessage());
            return -1;
        }
    }
    
    public boolean isAvailable() {
        return initialized && statisticDevice != null;
    }
}

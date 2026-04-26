package com.overdrive.app.trips;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.BatterySocData;
import com.overdrive.app.monitor.GearMonitor;
import com.overdrive.app.monitor.GpsMonitor;
import com.overdrive.app.monitor.VehicleDataMonitor;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Detects trip boundaries using a gear-based state machine.
 *
 * State machine: IDLE → ACTIVE → PARK_PENDING → IDLE
 *
 * Transitions:
 * - IDLE + gear ∈ {D, R, S, M, N} → create TripRecord, notify listener → ACTIVE
 * - ACTIVE + gear == P + speed == 0 → start 120s debounce timer → PARK_PENDING
 * - PARK_PENDING + gear ∈ {D, R, S, M, N} (within 120s) → cancel timer → ACTIVE
 * - PARK_PENDING + 120s elapsed → finalize trip, notify listener → IDLE
 *
 * Called from CameraDaemon.onGearChanged() when gear transitions occur.
 */
public class TripDetector {

    private static final DaemonLogger logger = DaemonLogger.getInstance("TripDetector");

    // Constants
    static final long PARK_DEBOUNCE_MS = 120_000;    // 2 minutes
    static final long MIN_TRIP_DURATION_MS = 60_000;  // 1 minute
    static final double MIN_TRIP_DISTANCE_KM = 0.2;   // 200 meters

    // State machine
    enum State { IDLE, ACTIVE, PARK_PENDING }

    private volatile State state = State.IDLE;
    private volatile TripRecord activeTrip;
    private TripListener listener;

    // Odometer reading at trip start (km), -1 if unavailable
    private double startOdometerKm = -1;

    // Time when gear first went to P (for accurate end time, excluding debounce)
    private long parkStartTime = 0;

    // Debounce timer
    private final ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?> parkDebounceTask;

    public TripDetector() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TripDetector-Debounce");
            t.setDaemon(true);
            return t;
        });
        logger.info("TripDetector created");
        checkForOrphanedTrips();
    }

    // ==================== LISTENER ====================

    /**
     * Listener interface for trip lifecycle events.
     */
    public interface TripListener {
        void onTripStarted(TripRecord trip);
        void onTripEnded(TripRecord trip);
        void onTripDiscarded(TripRecord trip, String reason);
        /** Called before finalization to get the GPS distance from the recorder. */
        default double getRecordedDistanceKm() { return 0; }
    }

    /**
     * Set the callback listener for trip events.
     */
    public void setListener(TripListener listener) {
        this.listener = listener;
    }

    // ==================== GEAR MONITOR REGISTRATION ====================

    /**
     * Register with GearMonitor for gear change callbacks.
     * Currently a no-op — CameraDaemon forwards gear changes directly via onGearChanged().
     */
    public void registerWithGearMonitor() {
        logger.info("registerWithGearMonitor (no-op: CameraDaemon forwards gear changes)");
    }

    /**
     * Unregister from GearMonitor.
     * Currently a no-op — CameraDaemon forwards gear changes directly.
     */
    public void unregisterFromGearMonitor() {
        logger.info("unregisterFromGearMonitor (no-op)");
    }

    // ==================== GEAR CHANGE HANDLER ====================

    /**
     * Called from CameraDaemon when gear changes.
     * This is the main entry point for the state machine.
     *
     * @param newGear The new gear position (1=P, 2=R, 3=N, 4=D, 5=M, 6=S)
     */
    public synchronized void onGearChanged(int newGear) {
        logger.info("onGearChanged: gear=" + GearMonitor.gearToString(newGear) + " state=" + state);

        switch (state) {
            case IDLE:
                handleIdleGearChange(newGear);
                break;
            case ACTIVE:
                handleActiveGearChange(newGear);
                break;
            case PARK_PENDING:
                handleParkPendingGearChange(newGear);
                break;
        }
    }

    /**
     * IDLE state: waiting for a driving gear to start a trip.
     */
    private void handleIdleGearChange(int newGear) {
        if (isDrivingGear(newGear)) {
            startTrip();
        }
    }

    /**
     * ACTIVE state: trip in progress, watching for Park gear.
     */
    private void handleActiveGearChange(int newGear) {
        if (newGear == GearMonitor.GEAR_P) {
            // Check speed — only start debounce if speed is 0
            float speed = GpsMonitor.getInstance().getSpeed();
            if (speed <= 0.5f) {
                // Start park debounce timer
                logger.info("Gear P + speed=0 → starting " + (PARK_DEBOUNCE_MS / 1000) + "s debounce");
                state = State.PARK_PENDING;
                parkStartTime = System.currentTimeMillis();
                startParkDebounceTimer();
            } else {
                logger.info("Gear P but speed=" + speed + " m/s → staying ACTIVE (moving)");
            }
        }
        // Other gear changes while active are normal driving (D→R, D→N, etc.)
    }

    /**
     * PARK_PENDING state: debounce timer running, watching for driving gear to cancel.
     */
    private void handleParkPendingGearChange(int newGear) {
        if (isDrivingGear(newGear)) {
            // Driver resumed — cancel debounce, back to ACTIVE
            logger.info("Gear resumed to " + GearMonitor.gearToString(newGear) + " → cancelling debounce, back to ACTIVE");
            cancelParkDebounceTimer();
            parkStartTime = 0;
            state = State.ACTIVE;
        }
    }

    // ==================== TRIP LIFECYCLE ====================

    /**
     * Start a new trip. Creates a TripRecord and notifies the listener.
     */
    private void startTrip() {
        long now = System.currentTimeMillis();
        activeTrip = new TripRecord();
        activeTrip.startTime = now;

        // Read start SoC
        try {
            BatterySocData socData = VehicleDataMonitor.getInstance().getBatterySoc();
            if (socData != null) {
                activeTrip.socStart = socData.socPercent;
            }
        } catch (Exception e) {
            logger.error("Failed to read start SoC: " + e.getMessage());
        }

        // Read start kWh (remaining energy from BMS)
        try {
            double kwhRemaining = VehicleDataMonitor.getInstance().getBatteryRemainPowerKwh();
            if (kwhRemaining > 0) {
                activeTrip.kwhStart = kwhRemaining;
            }
        } catch (Exception e) {
            logger.error("Failed to read start kWh: " + e.getMessage());
        }

        // Read start GPS
        try {
            GpsMonitor gps = GpsMonitor.getInstance();
            if (gps.hasLocation()) {
                activeTrip.startLat = gps.getLatitude();
                activeTrip.startLon = gps.getLongitude();
            }
        } catch (Exception e) {
            logger.error("Failed to read start GPS: " + e.getMessage());
        }

        // Odometer: store 0 for now, compute distance from GPS later if needed
        activeTrip.distanceKm = 0;

        // Read start odometer
        try {
            startOdometerKm = OdometerReader.getInstance().readOdometerKm();
            if (startOdometerKm > 0) {
                logger.info("Start odometer: " + startOdometerKm + " km");
            }
        } catch (Exception e) {
            logger.warn("Failed to read start odometer: " + e.getMessage());
            startOdometerKm = -1;
        }

        // Read external temperature
        activeTrip.extTempC = 0;
        try {
            Class<?> instrumentClass = Class.forName("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice");
            java.lang.reflect.Method getInst = instrumentClass.getMethod("getInstance", android.content.Context.class);
            Object instrumentDevice = getInst.invoke(null, (android.content.Context) null);
            if (instrumentDevice != null) {
                java.lang.reflect.Method getTemp = instrumentClass.getMethod("getOutCarTemperature");
                int rawTemp = (Integer) getTemp.invoke(instrumentDevice);
                if (rawTemp >= -50 && rawTemp <= 60) {
                    activeTrip.extTempC = rawTemp;
                }
            }
        } catch (Exception e) {
            // Temperature unavailable — leave as 0
        }

        state = State.ACTIVE;
        logger.info("Trip started at " + now + " (SoC=" + activeTrip.socStart
                + "%, GPS=" + activeTrip.startLat + "," + activeTrip.startLon + ")");

        if (listener != null) {
            try {
                listener.onTripStarted(activeTrip);
            } catch (Exception e) {
                logger.error("Listener.onTripStarted failed: " + e.getMessage());
            }
        }
    }

    /**
     * Finalize the active trip. Called when debounce timer expires or on shutdown.
     * Populates end fields, checks minimum thresholds, and notifies listener.
     */
    public synchronized void finalizeActiveTrip() {
        if (activeTrip == null || state == State.IDLE) {
            logger.info("finalizeActiveTrip: no active trip");
            return;
        }

        cancelParkDebounceTimer();

        long now = System.currentTimeMillis();
        // Use the time when gear first went to P as the actual trip end time
        // (not the current time, which includes the 120s debounce wait)
        activeTrip.endTime = (parkStartTime > 0) ? parkStartTime : now;
        activeTrip.durationSeconds = (int) ((activeTrip.endTime - activeTrip.startTime) / 1000);

        // Read end SoC
        try {
            BatterySocData socData = VehicleDataMonitor.getInstance().getBatterySoc();
            if (socData != null) {
                activeTrip.socEnd = socData.socPercent;
            }
        } catch (Exception e) {
            logger.error("Failed to read end SoC: " + e.getMessage());
        }

        // Read end kWh (remaining energy from BMS)
        try {
            double kwhRemaining = VehicleDataMonitor.getInstance().getBatteryRemainPowerKwh();
            if (kwhRemaining > 0) {
                activeTrip.kwhEnd = kwhRemaining;
            }
        } catch (Exception e) {
            logger.error("Failed to read end kWh: " + e.getMessage());
        }

        // Read end GPS
        try {
            GpsMonitor gps = GpsMonitor.getInstance();
            if (gps.hasLocation()) {
                activeTrip.endLat = gps.getLatitude();
                activeTrip.endLon = gps.getLongitude();
            }
        } catch (Exception e) {
            logger.error("Failed to read end GPS: " + e.getMessage());
        }

        // Compute distance: prefer odometer delta (exact), fallback to GPS haversine
        double endOdometerKm = -1;
        try {
            endOdometerKm = OdometerReader.getInstance().readOdometerKm();
        } catch (Exception e) {
            logger.warn("Failed to read end odometer: " + e.getMessage());
        }

        if (startOdometerKm > 0 && endOdometerKm > startOdometerKm) {
            activeTrip.distanceKm = endOdometerKm - startOdometerKm;
            logger.info("Distance from odometer: " + String.format("%.2f", activeTrip.distanceKm) + " km");
        }

        // Fallback: GPS haversine distance from recorder
        if (activeTrip.distanceKm <= 0 && listener != null) {
            try {
                double recordedDist = listener.getRecordedDistanceKm();
                if (recordedDist > 0) {
                    activeTrip.distanceKm = recordedDist;
                    logger.info("Distance from GPS (fallback): " + String.format("%.2f", recordedDist) + " km");
                }
            } catch (Exception e) {
                logger.warn("Failed to get GPS distance: " + e.getMessage());
            }
        }

        // Last resort: straight-line haversine from start to end GPS coordinates
        // This underestimates actual distance but prevents valid trips from being
        // discarded when both odometer and recorder distance are unavailable
        if (activeTrip.distanceKm <= 0
                && activeTrip.startLat != 0 && activeTrip.startLon != 0
                && activeTrip.endLat != 0 && activeTrip.endLon != 0) {
            double straightLine = haversineKm(
                    activeTrip.startLat, activeTrip.startLon,
                    activeTrip.endLat, activeTrip.endLon);
            if (straightLine > 0) {
                // Apply 1.3x multiplier to approximate road distance from straight-line
                activeTrip.distanceKm = straightLine * 1.3;
                logger.info("Distance from straight-line GPS (last resort): "
                        + String.format("%.2f", activeTrip.distanceKm) + " km"
                        + " (straight=" + String.format("%.2f", straightLine) + " km)");
            }
        }

        // Compute efficiency if we have distance
        if (activeTrip.distanceKm > 0) {
            // Prefer kWh-based efficiency (direct BMS measurement)
            double energyUsed = activeTrip.getEnergyUsedKwh();
            if (energyUsed > 0) {
                activeTrip.energyPerKm = energyUsed / activeTrip.distanceKm;
            }
            // Also compute SoC-based efficiency (legacy / fallback)
            if (activeTrip.socStart > activeTrip.socEnd) {
                double socDelta = activeTrip.socStart - activeTrip.socEnd;
                activeTrip.efficiencySocPerKm = socDelta / activeTrip.distanceKm;
            }
            // If kWh not available, derive energyPerKm from SoC (backward compat)
            if (activeTrip.energyPerKm <= 0 && activeTrip.efficiencySocPerKm > 0) {
                // Use SoC-based estimate: (socDelta/100) * nominalCapacity / distance
                // This is less accurate but works when kWh readings aren't available
                logger.debug("kWh not available, using SoC-based energyPerKm estimate");
            }
        }

        logger.info("Trip finalized: duration=" + activeTrip.durationSeconds + "s, distance="
                + activeTrip.distanceKm + "km, SoC=" + activeTrip.socStart + "→" + activeTrip.socEnd + "%"
                + ", kWh=" + String.format("%.2f", activeTrip.kwhStart) + "→" + String.format("%.2f", activeTrip.kwhEnd)
                + " (used=" + String.format("%.2f", activeTrip.getEnergyUsedKwh()) + " kWh)");

        // Check minimum thresholds
        long durationMs = activeTrip.endTime - activeTrip.startTime;
        if (durationMs < MIN_TRIP_DURATION_MS) {
            String reason = "Duration " + (durationMs / 1000) + "s < minimum " + (MIN_TRIP_DURATION_MS / 1000) + "s";
            logger.info("Trip discarded: " + reason);
            discardTrip(reason);
            return;
        }

        if (activeTrip.distanceKm < MIN_TRIP_DISTANCE_KM) {
            String reason = "Distance " + activeTrip.distanceKm + "km < minimum " + MIN_TRIP_DISTANCE_KM + "km";
            logger.info("Trip discarded: " + reason);
            discardTrip(reason);
            return;
        }

        // Trip is valid — notify listener
        TripRecord completedTrip = activeTrip;
        activeTrip = null;
        startOdometerKm = -1;
        parkStartTime = 0;
        state = State.IDLE;

        if (listener != null) {
            try {
                listener.onTripEnded(completedTrip);
            } catch (Exception e) {
                logger.error("Listener.onTripEnded failed: " + e.getMessage());
            }
        }
    }

    /**
     * Discard a trip that doesn't meet minimum thresholds.
     */
    private void discardTrip(String reason) {
        TripRecord discardedTrip = activeTrip;
        activeTrip = null;
        startOdometerKm = -1;
        parkStartTime = 0;
        state = State.IDLE;

        if (listener != null && discardedTrip != null) {
            try {
                listener.onTripDiscarded(discardedTrip, reason);
            } catch (Exception e) {
                logger.error("Listener.onTripDiscarded failed: " + e.getMessage());
            }
        }
    }

    // ==================== DEBOUNCE TIMER ====================

    /**
     * Start the 120s park debounce timer.
     * When it fires, the trip is finalized.
     */
    private void startParkDebounceTimer() {
        cancelParkDebounceTimer();
        parkDebounceTask = scheduler.schedule(() -> {
            synchronized (TripDetector.this) {
                if (state == State.PARK_PENDING) {
                    logger.info("Park debounce timer expired → finalizing trip");
                    finalizeActiveTrip();
                }
            }
        }, PARK_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Cancel the park debounce timer if running.
     */
    private void cancelParkDebounceTimer() {
        if (parkDebounceTask != null && !parkDebounceTask.isDone()) {
            parkDebounceTask.cancel(false);
            parkDebounceTask = null;
        }
    }

    // ==================== ORPHANED TRIP CHECK ====================

    /**
     * Check for orphaned trips on init (trip with startTime but no endTime).
     * If the daemon crashed mid-trip, the DB may have a trip with endTime == 0.
     * Finalize it using the last recorded telemetry timestamp or current time.
     */
    private void checkForOrphanedTrips() {
        logger.info("Checking for orphaned trips...");
        // Actual DB recovery is done in TripAnalyticsManager.initComponents()
        // since TripDatabase isn't available at TripDetector construction time.
    }

    // ==================== UTILITY ====================

    /**
     * Check if a gear value represents a driving gear (not Park).
     */
    private static boolean isDrivingGear(int gear) {
        return gear == GearMonitor.GEAR_D
                || gear == GearMonitor.GEAR_R
                || gear == GearMonitor.GEAR_N
                || gear == GearMonitor.GEAR_M
                || gear == GearMonitor.GEAR_S;
    }

    /**
     * Haversine distance between two GPS coordinates in km.
     * Used as last-resort distance estimate when odometer and recorder both fail.
     */
    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371.0; // Earth radius in km
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    // ==================== GETTERS ====================

    /**
     * Check if a trip is currently active (ACTIVE or PARK_PENDING state).
     */
    public boolean isTripActive() {
        return state != State.IDLE && activeTrip != null;
    }

    /**
     * Get the currently active trip record, or null if no trip is active.
     */
    public TripRecord getActiveTrip() {
        return activeTrip;
    }

    /**
     * Get the current state machine state. Package-private for testing.
     */
    State getState() {
        return state;
    }

    // ==================== SHUTDOWN ====================

    /**
     * Shut down the detector. Finalizes any active trip and stops the scheduler.
     */
    public void shutdown() {
        logger.info("Shutting down TripDetector");
        finalizeActiveTrip();
        scheduler.shutdownNow();
    }
}

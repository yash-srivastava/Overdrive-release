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
    /** Max age of a GPS fix for its speed to veto the park transition. Beyond
     *  this, gear P wins — a cached/disk-restored speed must not wedge the
     *  detector in ACTIVE. 10s is generous vs the ~1s fix cadence. */
    static final long GPS_SPEED_MAX_AGE_MS = 10_000;
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
    // PHEV-only: integrates seconds where engineSpeedRpm > 600 across the
    // active trip. Used by the cost-breakdown UI to label the trip's HEV
    // mode share. Idle on BEVs (sampler is started only when isPhev=true).
    private volatile ScheduledFuture<?> iceSamplerTask;
    private static final int ICE_RPM_THRESHOLD = 600;

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
            // Check speed — only start debounce if speed is 0.
            //
            // STALENESS GUARD: GpsMonitor.getSpeed() is a cached field that is also
            // restored from disk at startup, so it can report a non-zero speed from
            // a fix minutes or hours old (e.g. GPS lost in a garage, or the value
            // reloaded after a restart). Gear P is a far more reliable "the car
            // stopped" signal than a stale speed sample. Without this guard the
            // detector never leaves ACTIVE, the trip only closes on ACC-off, and its
            // recorded duration is inflated by the whole parked interval.
            float speed = GpsMonitor.getInstance().getSpeed();
            long fixAgeMs = System.currentTimeMillis() - GpsMonitor.getInstance().getLastUpdate();
            boolean speedTrustworthy = fixAgeMs >= 0 && fixAgeMs <= GPS_SPEED_MAX_AGE_MS;
            if (speed <= 0.5f || !speedTrustworthy) {
                // Start park debounce timer
                logger.info("Gear P + speed=" + speed + "m/s"
                    + (speedTrustworthy ? " (stopped)" : " IGNORED (fix " + fixAgeMs + "ms old)")
                    + " → starting " + (PARK_DEBOUNCE_MS / 1000) + "s debounce");
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
        // DiLink5-exclusive car_service override, unconditional (not just a
        // fallback for a null/missing stock read) — same "DiLink5 always
        // wins" rule already applied to 12V/SOC elsewhere in this app
        // (CarSvcTelemetry.socPercent doc comment). An earlier version of
        // this fix only overrode when the stock reading was exactly 0.0,
        // which would silently keep a stale-but-nonzero stock value instead
        // of the better source; deliberately widened to always-preferred.
        try {
            if (com.overdrive.app.byd.DiLink5Platform.isActive()) {
                int carSvcSoc = com.overdrive.app.byd.CarSvcTelemetry.INSTANCE.socPercent();
                if (carSvcSoc >= 0 && carSvcSoc <= 100) {
                    activeTrip.socStart = carSvcSoc;
                }
            }
        } catch (Exception e) {
            logger.error("Failed to read car_service start SoC: " + e.getMessage());
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

        // Cumulative HAL electricity counter (kWh). The end delta is the metered
        // energy drawn — the only electric source fine enough to measure a short
        // trip, since remaining-kWh is derived from an integer SoC. Captured for
        // BEV and PHEV alike (unlike the fuel counter, which is PHEV-only).
        //
        // Require strictly > 0: a lifetime counter on any car that has ever
        // driven is positive, so an exact 0 means this trim doesn't populate it.
        // Leaving the -1 sentinel makes the energy cascade fall back to the
        // remaining-kWh delta rather than booking a false 0 kWh.
        try {
            double elecConStart = VehicleDataMonitor.getInstance().getTotalElecCon();
            if (!Double.isNaN(elecConStart) && elecConStart > 0) {
                activeTrip.elecConStart = elecConStart;
            }
        } catch (Exception e) {
            logger.warn("Failed to read start elec accumulator: " + e.getMessage());
        }

        // PHEV-only: snapshot drivetrain + tank level. computeIsPhev caches
        // its verdict for 60s so this is one reflection call worst-case.
        // BEVs leave fuelPctStart at -1 (sentinel for "no fuel data") and
        // isPhev=false — downstream cost math treats this as electric-only,
        // identical to the pre-PHEV behaviour.
        try {
            VehicleDataMonitor vdm = VehicleDataMonitor.getInstance();
            if (vdm.isPhev()) {
                activeTrip.isPhev = true;
                com.overdrive.app.monitor.DrivingRangeData dr = vdm.getDrivingRange();
                if (dr != null && dr.hasFuelPercent()) {
                    activeTrip.fuelPctStart = dr.fuelPercent;
                }
                // Cumulative HAL fuel counter (litres). Captured independently
                // of the range/pct snapshot so a missing elec-range can't drop
                // it. The end delta is the metered litres burned this trip.
                //
                // Require strictly > 0: a lifetime fuel counter on any PHEV
                // that has ever run its engine is positive, so an exact 0 means
                // the trim doesn't populate this accumulator. Leaving it at the
                // -1 sentinel makes the analytics cascade fall back to the
                // fuelPct×tank estimate rather than silently reporting 0 L.
                double fuelConStart = vdm.getTotalFuelCon();
                if (!Double.isNaN(fuelConStart) && fuelConStart > 0) {
                    activeTrip.fuelConStart = fuelConStart;
                }
                startIceSampler();
            }
        } catch (Exception e) {
            logger.warn("Failed to read PHEV start state: " + e.getMessage());
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
                // Persist the absolute reading for display (distanceKm is the
                // delta; this is the odometer value shown on the trip card).
                activeTrip.odometerStartKm = startOdometerKm;
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
        stopIceSampler();

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
        // DiLink5-exclusive car_service override — see the matching comment
        // in startTrip() above; same always-preferred (not fallback-only)
        // rule applies symmetrically to the end-of-trip reading.
        try {
            if (com.overdrive.app.byd.DiLink5Platform.isActive()) {
                int carSvcSoc = com.overdrive.app.byd.CarSvcTelemetry.INSTANCE.socPercent();
                if (carSvcSoc >= 0 && carSvcSoc <= 100) {
                    activeTrip.socEnd = carSvcSoc;
                }
            }
        } catch (Exception e) {
            logger.error("Failed to read car_service end SoC: " + e.getMessage());
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

        // End of the cumulative electricity counter. Strictly > 0 for the same
        // reason as the start read; the analytics cascade additionally requires
        // end >= start so a firmware counter reset falls through to the coarser
        // tiers instead of yielding a negative energy figure.
        try {
            double elecConEnd = VehicleDataMonitor.getInstance().getTotalElecCon();
            if (!Double.isNaN(elecConEnd) && elecConEnd > 0) {
                activeTrip.elecConEnd = elecConEnd;
            }
        } catch (Exception e) {
            logger.warn("Failed to read end elec accumulator: " + e.getMessage());
        }

        // PHEV end-of-trip fuel snapshot. We re-check isPhev here so that a
        // vehicle reclassified mid-trip (e.g. computeIsPhev cache TTL flipped
        // the verdict) still records a consistent end value.
        //
        // Sticky once: we DO NOT flip isPhev back to false at end. Reason —
        // if the sampler ran during the trip and accumulated iceSeconds, or
        // if fuelPctStart was captured at start, those readings are real
        // PHEV-side data and the trip should remain classified as PHEV. The
        // cost-math path is gated on fuelPctStart/End >= 0, so a missing
        // end reading correctly suppresses the fuel leg without mis-labeling
        // the trip.
        try {
            VehicleDataMonitor vdm = VehicleDataMonitor.getInstance();
            boolean isPhevNow = vdm.isPhev();
            if (isPhevNow || activeTrip.isPhev) {
                activeTrip.isPhev = true;
                com.overdrive.app.monitor.DrivingRangeData dr = vdm.getDrivingRange();
                if (dr != null && dr.hasFuelPercent()) {
                    activeTrip.fuelPctEnd = dr.fuelPercent;
                }
                // Strictly > 0: see startTrip — an exact 0 means the trim
                // doesn't populate the accumulator, so leave the -1 sentinel
                // and let the analytics cascade use the pct×tank fallback.
                double fuelConEnd = vdm.getTotalFuelCon();
                if (!Double.isNaN(fuelConEnd) && fuelConEnd > 0) {
                    activeTrip.fuelConEnd = fuelConEnd;
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to read PHEV end state: " + e.getMessage());
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
            if (endOdometerKm > 0) {
                // Persist the absolute end reading for display (paired with the
                // start snapshot; the card shows start→end, distanceKm the delta).
                activeTrip.odometerEndKm = endOdometerKm;
            }
        } catch (Exception e) {
            logger.warn("Failed to read end odometer: " + e.getMessage());
        }

        // Whether the start/end pair is self-consistent enough to display.
        boolean odometerPairTrusted = false;
        if (startOdometerKm > 0 && endOdometerKm > startOdometerKm) {
            double odoDelta = endOdometerKm - startOdometerKm;
            // Sanity-gate the delta against what the elapsed time physically allows
            // (200 km/h ceiling, +1 km of slack so rounding never rejects a short
            // trip) before booking it as the exact hardware distance. Failing it
            // falls through to the GPS paths below.
            //
            // Register and scale consistency across the two edges is guaranteed at the
            // source — OdometerReader decides ONCE which register it trusts and never
            // mixes them — so what remains to catch here is a wrong UNIT factor,
            // which is self-consistent and therefore invisible to any internal check.
            double maxPlausibleKm = 1.0 + (200.0 * activeTrip.durationSeconds / 3600.0);

            // NOT cross-checked against the recorder's distance, in either direction.
            // It looks like an independent witness but it is not: its primary source
            // is CAN wheel speed scaled by the SAME unit factor this odometer uses
            // (BydDataCollector.getSpeedToKmhFactor), so a wrong unit factor skews
            // both by the identical ratio and the comparison silently always passes.
            // Where the two DO diverge — the recorder's GPS-haversine fallback, used
            // when the speed channel is stale — the recorder is the less trustworthy
            // one (parked jitter accretes phantom distance), so acting on a
            // disagreement would discard the good reading for the bad one.
            //
            // A wrong unit factor therefore cannot be detected here at all, and is
            // deliberately left to be fixed at its source rather than papered over
            // with a check that cannot see it.
            if (odoDelta > maxPlausibleKm) {
                logger.warn("Odometer delta implausible (" + String.format("%.2f", odoDelta)
                        + " km in " + activeTrip.durationSeconds + "s, max "
                        + String.format("%.2f", maxPlausibleKm) + ") — ignoring, falling back to GPS");
            } else {
                activeTrip.distanceKm = odoDelta;
                odometerPairTrusted = true;
                logger.info("Distance from odometer: " + String.format("%.2f", activeTrip.distanceKm) + " km");
            }
        }

        // The absolute start/end readings are shown on the trip card. Publish them
        // only when the delta between them was actually trusted above: otherwise the
        // card would display a pair whose own difference contradicts the distance
        // shown beside it (and, if the two reads disagreed, an odometer that appears
        // to run backwards). Suppressed means the tiles hide, which reads as
        // "unavailable" rather than as confidently wrong numbers.
        if (!odometerPairTrusted) {
            activeTrip.odometerStartKm = 0;
            activeTrip.odometerEndKm = 0;
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
                + ", elecCon=" + String.format("%.3f", activeTrip.elecConStart) + "→" + String.format("%.3f", activeTrip.elecConEnd)
                + " (used=" + String.format("%.3f", activeTrip.getEnergyUsedKwh()) + " kWh"
                + (activeTrip.hasMeteredEnergy() ? ", metered" : ", derived") + ")");

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

    /**
     * 1 Hz sampler that integrates seconds where the ICE is running. Cheap:
     * one BydVehicleData snapshot read per tick, no reflection (the snapshot
     * is already maintained by BydDataCollector). Started only on PHEV
     * trips so BEV vehicles incur zero overhead.
     */
    private void startIceSampler() {
        stopIceSampler();
        iceSamplerTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                TripRecord t = activeTrip;
                if (t == null || state != State.ACTIVE) return;
                com.overdrive.app.byd.BydVehicleData vd =
                        VehicleDataMonitor.getInstance().getVd();
                if (vd == null) return;
                if (vd.engineSpeedRpm != com.overdrive.app.byd.BydVehicleData.UNAVAILABLE
                        && vd.engineSpeedRpm > ICE_RPM_THRESHOLD) {
                    t.iceSecondsAtomic.incrementAndGet();
                }
            } catch (Throwable th) {
                // Don't let a transient failure kill the scheduler.
                logger.debug("ICE sampler tick failed: " + th.getMessage());
            }
        }, 1, 1, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void stopIceSampler() {
        if (iceSamplerTask != null && !iceSamplerTask.isDone()) {
            iceSamplerTask.cancel(false);
            iceSamplerTask = null;
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
        // Recovery from orphaned .jsonl.gz files (surviving telemetry from a
        // mid-drive daemon crash with no DB row) is handled by the auto-recovery
        // hook in TripAnalyticsManager.initComponents() which calls
        // TripDatabase.recoverTripsFromDisk() on a background thread at startup.
        // TripDatabase isn't available at TripDetector construction time, and the
        // scan involves FUSE I/O that shouldn't block detector readiness.
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

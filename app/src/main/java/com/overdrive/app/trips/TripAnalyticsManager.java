package com.overdrive.app.trips;

import android.content.Context;

import com.overdrive.app.abrp.SohEstimator;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.GearMonitor;
import com.overdrive.app.storage.StorageManager;
import com.overdrive.app.telemetry.TelemetryDataCollector;

import java.io.File;
import java.util.List;

/**
 * Top-level coordinator for Trip Analytics & Driving DNA.
 * Single entry point for CameraDaemon integration.
 *
 * Lifecycle:
 *   CameraDaemon.main() → init(context, telemetryDataCollector, sohEstimator)
 *   CameraDaemon.shutdown() → shutdown()
 *   GearMonitor callback → onGearChanged(newGear)
 */
public class TripAnalyticsManager {

    private static final DaemonLogger logger = DaemonLogger.getInstance("TripAnalyticsManager");

    /**
     * How far SoC must fall before it is allowed to override a metered reading of
     * zero energy (see {@code resolveTripEnergyKwh}). SoC is integer-resolution on
     * this HAL, so anything at or below one step is noise; this sits above it.
     */
    static final double SOC_OVERRIDE_MIN_DROP_PCT = 1.0;

    /**
     * How far back to look for the charge whose tariff prices a trip. Beyond
     * this, a stale tariff is less trustworthy than the current configured rate
     * (a car parked for a season, or charging analytics switched off for months).
     */
    private static final int LAST_CHARGE_RATE_MAX_AGE_DAYS = 60;

    private TripConfig config;
    private TripDatabase database;
    private TripDetector detector;
    private TripTelemetryRecorder recorder;
    private TripScoreEngine scoreEngine;
    private RangeEstimator rangeEstimator;

    private TelemetryDataCollector telemetryDataCollector;
    private SohEstimator sohEstimator;

    private volatile boolean enabled = false;
    private volatile boolean initialized = false;

    // ==================== LIFECYCLE ====================

    /**
     * Initialize trip analytics. Called from CameraDaemon.main() after ABRP init.
     *
     * 1. Load TripConfig from properties file
     * 2. If enabled: initialize TripDatabase, TripDetector, TripTelemetryRecorder,
     *    TripScoreEngine, RangeEstimator
     * 3. Set TripDetector listener to handle trip start/end events
     * 4. Ensure StorageManager.getInstance().getTripsDir() exists
     * 5. Log initialization status
     */
    public void init(Context context, TelemetryDataCollector telemetryDataCollector,
                     SohEstimator sohEstimator) {
        this.telemetryDataCollector = telemetryDataCollector;
        this.sohEstimator = sohEstimator;

        // 1. Load config
        config = new TripConfig();
        config.load();

        // 4. Ensure trips directory exists
        File tripsDir = StorageManager.getInstance().getTripsDir();
        if (tripsDir != null && !tripsDir.exists()) {
            boolean created = tripsDir.mkdirs();
            logger.info("Trips directory created: " + tripsDir.getAbsolutePath()
                    + " (success=" + created + ")");
        }

        // 2. If enabled, initialize all components.
        //
        // CONTAINED. Previously an uncaught throw in here (H2 open, a thread
        // factory, RangeEstimator) propagated out of init(), so CameraDaemon never
        // assigned tripAnalyticsManager and every trips endpoint answered "not
        // initialized" — including the one the user would need to turn the feature
        // OFF. That was near-unreachable while the feature defaulted off; now that
        // it defaults ON it would run on every unit at boot, so a single bad H2
        // file could brick the trips UI with no way out. Degrade instead: keep the
        // manager alive and reachable with enabled=false, so the API and toggle
        // still work and the daemon still boots.
        if (config.isEnabled()) {
            try {
                initComponents();
            } catch (Throwable t) {
                enabled = false;
                logger.error("Trip components failed to initialize — trips DISABLED for this "
                        + "session (manager stays reachable so the API/toggle still work): "
                        + t, t);
            }
        }

        initialized = true;

        // 5. Log status
        logger.info("TripAnalyticsManager initialized — enabled=" + config.isEnabled());
    }

    /**
     * Shut down trip analytics. Called from CameraDaemon.shutdown().
     *
     * 1. Finalize active trip via TripDetector
     * 2. Close TripDatabase
     * 3. Log shutdown
     */
    public void shutdown() {
        if (!initialized) return;  // already shut down or never started
        logger.info("Shutting down TripAnalyticsManager");

        // 1. Finalize active trip
        if (detector != null) {
            detector.shutdown();
        }

        // 2. Close database
        if (database != null) {
            database.close();
        }

        enabled = false;
        initialized = false;

        // 3. Log shutdown
        logger.info("TripAnalyticsManager shut down");
    }

    // ==================== GEAR FORWARDING ====================

    /**
     * Forward gear change to TripDetector if enabled.
     * Called from CameraDaemon.onGearChanged().
     */
    public void onGearChanged(int newGear) {
        if (enabled && detector != null) {
            detector.onGearChanged(newGear);
        }
    }

    // ==================== ACC LIFECYCLE ====================

    /**
     * Called when ACC goes OFF (car powering down / entering sentry mode).
     * Finalizes any active trip immediately — the gear change to P may not
     * fire reliably during power-down, so this is a safety net.
     */
    public void onAccOff() {
        if (!enabled || detector == null) return;
        if (detector.isTripActive()) {
            logger.info("ACC OFF — finalizing active trip");
            detector.finalizeActiveTrip();
        }
    }

    /**
     * Called when ACC comes ON (car powering up).
     * Probe current gear and auto-start trip if already in a driving gear.
     * This handles the case where gear changed to D before the GearMonitor
     * listener was re-registered, or where the gear event was lost during
     * the ACC transition.
     */
    public void onAccOn() {
        if (!enabled) return;
        logger.info("ACC ON — trip detection ready (gear D/R or GPS/CAN motion)");

        // Safety net: probe current gear in case we missed the gear change event
        // during the ACC OFF→ON transition
        try {
            int currentGear = resolveLiveGear();
            if (currentGear != GearMonitor.GEAR_P && detector != null && !detector.isTripActive()) {
                logger.info("ACC ON + gear already " + GearMonitor.gearToString(currentGear)
                        + " — auto-starting trip");
                detector.onGearChanged(currentGear);
            }
        } catch (Exception e) {
            logger.warn("ACC ON gear probe failed: " + e.getMessage());
        }
    }

    private static int resolveLiveGear() {
        try {
            if (com.overdrive.app.byd.DiLink5Platform.isActive()) {
                int carSvc = com.overdrive.app.byd.CarSvcTelemetry.INSTANCE.gearValue();
                if (carSvc >= 1 && carSvc <= 6) return carSvc;
            }
        } catch (Throwable ignored) {}
        return GearMonitor.getInstance().getCurrentGear();
    }

    // ==================== RUNTIME CONFIG ====================

    /**
     * Enable or disable trip analytics at runtime.
     *
     * If disabling while a trip is active, finalize the trip first.
     * If enabling while gear != P, start trip detection immediately.
     */
    public void onConfigChanged(boolean newEnabled) {
        logger.info("onConfigChanged: " + enabled + " → " + newEnabled);

        if (newEnabled == enabled) {
            return; // No change
        }

        if (!newEnabled) {
            // Disabling — finalize any active trip and stop the 1 Hz motion watch
            if (detector != null) {
                detector.shutdown();
                detector = null;
            }
            enabled = false;
            config.setEnabled(false);
            config.save();
            logger.info("Trip analytics disabled");
        } else {
            // Enabling
            config.setEnabled(true);
            config.save();

            if (!enabled) {
                initComponents();
            }

            // If gear is not P, trigger trip detection
            int currentGear = resolveLiveGear();
            if (currentGear != GearMonitor.GEAR_P && detector != null) {
                logger.info("Enabling while gear=" + GearMonitor.gearToString(currentGear)
                        + " — forwarding gear to detector");
                detector.onGearChanged(currentGear);
            }

            logger.info("Trip analytics enabled");
        }
    }

    // ==================== ACCESSORS ====================

    public TripDatabase getDatabase() {
        return database;
    }

    public RangeEstimator getRangeEstimator() {
        return rangeEstimator;
    }

    public TripConfig getConfig() {
        return config;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Check if a trip is currently being tracked (ACTIVE or PARK_PENDING).
     */
    public boolean isTripActive() {
        return enabled && detector != null && detector.isTripActive();
    }

    /**
     * Durably checkpoint an in-progress trip without ending it.
     *
     * <p>Call this before a process kill that is NOT a trip end — specifically
     * the UI's {@code prepare-restart} + {@code killall -9} flow, which bypasses
     * the JVM shutdown hook. It flushes buffered telemetry so the
     * {@code .jsonl.gz} on disk covers everything sampled so far, leaving
     * next-boot {@code recoverTripsFromDisk} able to rebuild the row.
     *
     * <p>Deliberately NOT {@link #shutdown()}. shutdown() calls
     * finalizeActiveTrip(), which applies the 60s / 0.2km floors and — on a
     * short leg — routes to discardTrip(), which DELETES the telemetry file. A
     * restart mid-drive would therefore destroy a trip that used to survive as a
     * recoverable file. shutdown() also flips {@code initialized}/{@code enabled}
     * to false and closes the H2 store, which strands trips dead for the rest of
     * the process if the caller's SIGKILL then fails (see abort-restart).
     *
     * <p>Safe to call when nothing is recording; it is then a no-op.
     */
    public void checkpointActiveTrip() {
        if (!enabled || !initialized) return;
        try {
            TripTelemetryRecorder rec = recorder;
            if (rec != null && detector != null && detector.isTripActive()) {
                rec.flushNow();
                logger.info("Active trip checkpointed to disk (restart-safe, trip left open)");
            }
        } catch (Throwable t) {
            logger.warn("checkpointActiveTrip failed: " + t.getMessage());
        }
    }

    /**
     * Get the active trip record, or null if no trip is active.
     */
    public TripRecord getActiveTrip() {
        return (detector != null) ? detector.getActiveTrip() : null;
    }

    /**
     * Update the TelemetryDataCollector reference after late initialization.
     * Called by CameraDaemon once TelemetryDataCollector is ready (after GPU init delay).
     */
    public void setTelemetryDataCollector(TelemetryDataCollector collector) {
        this.telemetryDataCollector = collector;
        if (recorder != null) {
            recorder.setTelemetryDataCollector(collector);
        }
    }

    // ==================== PRIVATE ====================

    /**
     * Initialize all trip analytics components and wire up the TripDetector listener.
     */
    private void initComponents() {
        // Database
        database = new TripDatabase();
        database.init();
        
        // Clean up orphaned trips from previous daemon crashes
        // (trips with no end_time that are older than 24 hours)
        try {
            long cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L;
            database.deleteOrphanedTrips(cutoff);
        } catch (Exception e) {
            logger.warn("Orphaned trip cleanup failed: " + e.getMessage());
        }

        // FIX: Auto-recover trips from surviving .jsonl.gz files on disk whose
        // DB row was lost to a mid-drive daemon crash. The crash kills the process
        // before TripDetector.finalizeActiveTrip() can insert the row, but the
        // telemetry file survives on USB/SD. recoverTripsFromDisk() is idempotent
        // (dedup by basename, start-time, signature), skips the currently-active
        // trip file, and respects minimum-trip thresholds — safe to run at every
        // startup. Runs on a background thread so it doesn't delay ACC-ON
        // responsiveness (FUSE listing can take seconds on large trip dirs).
        try {
            final StorageManager sm = StorageManager.getInstance();
            final java.util.List<File> tripsDirs = sm.getAllTripsDirs();
            boolean haveAnyDir = false;
            if (tripsDirs != null) {
                for (File d : tripsDirs) {
                    if (d != null && d.isDirectory()) { haveAnyDir = true; break; }
                }
            }
            if (haveAnyDir) {
                final TripDatabase db = database;
                Thread recoveryThread = new Thread(() -> {
                    try {
                        TripDatabase.RecoveryResult r = db.recoverTripsFromDisk(tripsDirs);
                        // A surviving checkpoint alongside a just-recovered row means the
                        // crash happened mid-trip after at least one checkpoint tick — fill
                        // in the real SoC/energy data the GPS-only recovery above can't see.
                        try {
                            db.enrichRecoveredTripsFromCheckpoints();
                        } catch (Throwable t) {
                            logger.warn("Checkpoint enrichment failed: " + t.getMessage());
                        }
                        if (r.recovered > 0) {
                            logger.info("Auto-recovery: recovered " + r.recovered
                                + " orphaned trips from disk (scanned=" + r.scanned
                                + ", skipped=" + r.skipped + ")");
                            // Re-enforce storage limit after recovering trips
                            try { sm.ensureTripsSpace(0); }
                            catch (Exception ex) {
                                logger.warn("Post-recovery trips cleanup failed: " + ex.getMessage());
                            }
                        }
                    } catch (Throwable t) {
                        logger.warn("Auto-recovery failed: " + t.getMessage());
                    }
                }, "TripAutoRecover");
                recoveryThread.setDaemon(true);
                recoveryThread.start();
            }
        } catch (Throwable t) {
            logger.warn("Trip auto-recovery setup failed: " + t.getMessage());
        }

        // Backfill route_id for existing trips (idempotent — skips already-assigned trips)
        database.backfillRouteIds();

        // Detector
        detector = new TripDetector();
        detector.setListener(new TripDetector.TripListener() {
            @Override
            public void onTripStarted(TripRecord trip) {
                handleTripStarted(trip);
            }

            @Override
            public void onTripEnded(TripRecord trip) {
                handleTripEnded(trip);
            }

            @Override
            public void onTripDiscarded(TripRecord trip, String reason) {
                handleTripDiscarded(trip, reason);
            }

            @Override
            public double getRecordedDistanceKm() {
                return recorder != null ? recorder.getTotalDistanceKm() : 0;
            }

            @Override
            public void onTripCheckpoint(TripRecord trip) {
                handleTripCheckpoint(trip);
            }
        });
        detector.startMotionWatch();

        // Recorder
        recorder = new TripTelemetryRecorder(telemetryDataCollector);

        // Score engine
        scoreEngine = new TripScoreEngine();

        // Range estimator
        rangeEstimator = new RangeEstimator(database, sohEstimator);

        enabled = true;
        // Surface a dead store LOUDLY. `enabled` is set regardless — detection and
        // telemetry are still worth running, because the .jsonl.gz files let
        // recoverTripsFromDisk rebuild rows on a later start once the store is
        // healthy. But without this line a failed database.init() produced a
        // pipeline that detects trips, writes telemetry, and silently inserts
        // nothing: the Trips page stays empty with no error anywhere.
        try {
            if (database == null || !database.isAvailable()) {
                logger.error("Trip database is NOT available after init — trips will be"
                        + " detected and telemetry written, but rows cannot be inserted."
                        + " Recovery from .jsonl.gz will backfill them on a later start.");
            }
        } catch (Throwable ignored) {}
        logger.info("Trip analytics components initialized");
    }

    /**
     * Handle trip started event from TripDetector.
     * Start the TripTelemetryRecorder using startTime as the trip ID.
     */
    private void handleTripStarted(TripRecord trip) {
        logger.info("Trip started at " + trip.startTime);

        // Ensure TelemetryDataCollector is polling so we get fresh data
        // It may not be polling if no recording/overlay is active
        if (telemetryDataCollector != null) {
            try {
                telemetryDataCollector.startPolling();
                logger.info("TelemetryDataCollector polling ensured for trip recording");
            } catch (Exception e) {
                logger.warn("Failed to start TelemetryDataCollector polling: " + e.getMessage());
            }
        }

        if (recorder != null) {
            // Use startTime as the unique trip identifier for the recorder
            // (before DB insert gives us the auto-increment ID)
            recorder.startRecording(trip.startTime);
        }
    }

    /**
     * Handle a periodic live-trip checkpoint from TripDetector (see
     * TripDetector.checkpointNow / TripListener.onTripCheckpoint). Persists
     * just the SoC/energy readings — real crash-recovery insurance for the gap
     * this system had before: a mid-drive daemon crash left NOTHING durable
     * except the GPS telemetry file, so recovery could only ever reconstruct
     * distance/speed/elevation, never energy or SoC.
     */
    private void handleTripCheckpoint(TripRecord trip) {
        if (database == null || trip == null || trip.startTime <= 0) return;
        try {
            database.upsertTripCheckpoint(trip.startTime, trip.socStart, trip.kwhStart,
                    trip.elecConStart, trip.socEnd, trip.kwhEnd, trip.elecConEnd);
        } catch (Throwable t) {
            logger.debug("handleTripCheckpoint failed: " + t.getMessage());
        }
    }

    /**
     * Handle trip ended event from TripDetector.
     *
     * 1. Stop recorder, get samples
     * 2. Compute scores via TripScoreEngine
     * 3. Populate TripRecord with recorder stats (maxSpeed, avgSpeed)
     * 4. Insert into TripDatabase
     * 5. Update rollups
     * 6. Update range estimator
     * 7. Update telemetry file path in the record
     */
    private void handleTripEnded(TripRecord trip) {
        logger.info("Trip ended — duration=" + trip.durationSeconds + "s, distance="
                + trip.distanceKm + "km");
        // NOTE: the checkpoint is deliberately NOT cleared here. Scoring, the
        // last-charge-rate lookup, and cost math all happen below, well before
        // insertTrip() actually runs — a crash anywhere in that stretch used to
        // leave the trip with NEITHER a real row NOR a checkpoint to recover
        // from (confirmed live, 2026-09-05: a 12-minute trip crashed during
        // this window and came back with distance/duration only, zero SoC/
        // energy, because the checkpoint had already been wiped up here before
        // the row existed). It's cleared only once insertTrip() actually
        // succeeds, below.

        // Release telemetry polling ref (acquired in handleTripStarted)
        if (telemetryDataCollector != null) {
            telemetryDataCollector.stopPolling();
        }

        String telemetryPath = null;

        // 1. Stop recorder, get samples
        List<TelemetrySample> samples = null;
        if (recorder != null) {
            telemetryPath = recorder.stopRecording();
            samples = recorder.getSamplesForScoring();
        }

        // Trim the park-debounce tail from the SCORING stream. The recorder keeps
        // sampling for the full ~120s park debounce (gear=P, speed=0) after the
        // trip really ended; TripDetector already excludes that tail from
        // trip.endTime (= parkStartTime), and the RECOVERY path trims it too —
        // but these samples still reached the score engine, feeding ~2 minutes
        // of parked GPS-altitude noise and idle dwell into every trip's scores.
        // Mirror the row's own end-time semantics here.
        if (samples != null && trip.endTime > 0) {
            final long scoringEndMs = trip.endTime + 1_000; // 1s slack for clock skew
            samples.removeIf(s -> s.timestampMs > scoringEndMs);
        }

        // stopRecording() clears the in-flight marker, but the DB row for this
        // trip isn't inserted until step 4 below. In that gap the <startTime>
        // .jsonl.gz file is on disk with NO row and NO active-file marker, so a
        // concurrent /api/trips/recover would rebuild a phantom duplicate.
        // Re-assert the marker over the file until the row exists; cleared in
        // the finally after insert+rename.
        boolean reArmedMarker = false;
        if (telemetryPath != null) {
            try {
                com.overdrive.app.storage.StorageManager.getInstance()
                        .setActiveTripFile(new File(telemetryPath));
                reArmedMarker = true;
            } catch (Exception ignored) {}
        }
        try {

        // 2. Resolve trip energy (kWh) BEFORE scoring.
        //    The efficiency axis scores against a single kWh/km band, so
        //    trip.energyPerKm must be populated first — either from direct BMS
        //    kWh readings or, when those are absent (the common case), estimated
        //    from the SoC delta via the SohEstimator's calibrated capacity. This
        //    ordering is the fix for efficiency/consistency scoring on a
        //    different unit axis than stored history. Used again below for cost.
        double energyUsed = resolveTripEnergyKwh(trip);
        if (trip.distanceKm > 0) {
            // Assign unconditionally, including 0. This resolution is authoritative
            // and supersedes the provisional rate the detector computed at finalize
            // time. Leaving a stale value in place when this resolves to 0 was a
            // real leak: a reading REJECTED by the plausibility gate below had
            // already been divided into energyPerKm upstream, and that figure then
            // survived into the rollup totals and the efficiency score — the exact
            // corruption the gate exists to stop.
            trip.energyPerKm = energyUsed / trip.distanceKm;
        }

        // 3. Compute scores — all five DNA axes in a single pass. Consistency is
        //    now an intra-trip behavioral-uniformity metric computed inside
        //    computeSummary (no DB lookup, no unit confusion).
        if (scoreEngine != null && samples != null && !samples.isEmpty()) {
            scoreEngine.computeSummary(trip, samples);
        }

        // 4. Populate recorder stats (recorder is authoritative for avg/max speed)
        if (recorder != null) {
            trip.maxSpeedKmh = recorder.getMaxSpeedKmh();
            trip.avgSpeedKmh = recorder.getAvgSpeedKmh();
        }

        // Snapshot electricity rate and compute trip cost.
        //
        // PHEV vs BEV cost math
        // ─────────────────────
        //   electric leg  = energyUsedKwh × electricityRate
        //   fuel leg      = (Δfuel% / 100) × tankCapacityL × fuelPricePerL   (PHEV only)
        //   tripCost      = electric leg + fuel leg
        //
        // Floors:
        //   - fuel leg requires Δfuel% ≥ 1 (sensor resolution is 1%).
        //   - fuel leg requires tankCapacityL > 0 AND fuelPricePerL > 0 from
        //     user config; otherwise the trip simply doesn't charge a fuel
        //     leg (UI surfaces this with a "Set tank capacity" hint).
        //
        // Regression-safety: BEV trips have isPhev=false and fuelPctStart/End
        // at -1, so the entire fuel branch is skipped — behaviour is bit-for-
        // bit identical to the pre-PHEV implementation.
        if (config != null) {
            // Electricity price for this trip: the rate the LAST CHARGE was
            // actually billed at, falling back to the configured global rate.
            //
            // Why the last charge and not the config value: the kWh a trip burns
            // were bought at the previous charge's tariff. A driver who charges at
            // home for 0.08 and then DC-fasts at 0.55 on a road trip should see
            // the road-trip legs costed at 0.55 — pricing everything at one global
            // number makes per-trip cost meaningless the moment more than one
            // tariff is in play. The charge tariff is itself location-aware (see
            // TariffManager), so this inherits "same place ⇒ same rate" for free.
            //
            // The lookup is capped at 60 days so a car parked for a season doesn't
            // price today's drive at an ancient tariff. No priced charge in that
            // window (fresh install, analytics off, or petrol-only PHEV use) ⇒
            // rateSource "config" and the exact pre-existing behaviour.
            trip.electricityRate = config.getElectricityRate();
            trip.currency = config.getCurrency();
            trip.rateSource = "config";
            trip.rateLabel = "";
            // Currency of the charge that priced the electric leg, applied below
            // only if the fuel leg (always in the config currency) is absent.
            String chargeCurrency = "";
            try {
                org.json.JSONObject lastCharge = com.overdrive.app.monitor.SocHistoryDatabase
                        .getInstance().getLastChargeRate(LAST_CHARGE_RATE_MAX_AGE_DAYS);
                if (lastCharge != null) {
                    double r = lastCharge.optDouble("rate", 0);
                    if (r > 0) {
                        trip.electricityRate = r;
                        trip.rateSource = "charge";
                        trip.rateLabel = lastCharge.optString("tariffLabel", "");
                        // Currency follows the rate — a charge on a foreign tariff
                        // carries its own symbol, and showing that price under the
                        // home symbol would be a lie. But DON'T adopt it yet: the
                        // petrol leg below is priced in the CONFIG currency, and
                        // tripCost sums the two. Stash it and apply it only once we
                        // know there is no fuel leg to disagree with.
                        String c = lastCharge.optString("currency", "");
                        if (c != null && !c.isEmpty()) chargeCurrency = c;
                    }
                }
            } catch (Throwable t) {
                // Charging analytics unavailable — keep the config rate.
                logger.debug("Last-charge rate lookup skipped: " + t.getMessage());
            }

            // energyUsed (kWh) and trip.energyPerKm were already resolved above,
            // before scoring — reuse them here for the cost math.

            // Electric leg
            double electricCost = 0;
            if (energyUsed > 0 && trip.electricityRate > 0) {
                electricCost = energyUsed * trip.electricityRate;
            }
            trip.electricCost = electricCost;

            // Fuel leg (PHEV only).
            //
            // PRIMARY — hardware cumulative-fuel accumulator delta. The BYD
            // statistic HAL exposes getTotalFuelConValue(), a lifetime
            // litres-burned counter; (end - start) is the vehicle's own metered
            // burn for this trip. This is independent of tank size and free of
            // the 1%-resolution gauge quantisation, and it captures idle /
            // charge-sustain burn the gauge barely moves on. Guarded on
            // end >= start so a counter reset/rollover falls through to the
            // estimate rather than emitting a negative volume. (This is the
            // approach the OEM firmware uses; we add the reset guard.)
            //
            // FALLBACK — legacy fuelPct×tank estimate, for trips logged before
            // the accumulator was captured, or trims that don't report it.
            // Δfuel% < 1 ⇒ below sensor resolution, floored to 0 to avoid
            // phantom costs from integer flicker; requires user-set tankL.
            double fuelCost = 0;
            if (trip.isPhev) {
                double pricePerL = config.getFuelPricePerL();
                trip.fuelPricePerL = pricePerL;

                double litres = 0;
                if (trip.fuelConStart >= 0 && trip.fuelConEnd >= 0
                        && trip.fuelConEnd >= trip.fuelConStart) {
                    // Metered burn — preferred. A flat counter (EV-only leg)
                    // correctly yields 0 litres.
                    litres = trip.fuelConEnd - trip.fuelConStart;
                } else {
                    double tankL = config.getTankCapacityL();
                    if (trip.fuelPctStart >= 0 && trip.fuelPctEnd >= 0
                            && trip.fuelPctStart >= trip.fuelPctEnd
                            && (trip.fuelPctStart - trip.fuelPctEnd) >= 1.0
                            && tankL > 0) {
                        litres = ((trip.fuelPctStart - trip.fuelPctEnd) / 100.0) * tankL;
                    }
                }

                if (litres > 0) {
                    trip.litresUsed = litres;
                    if (pricePerL > 0) {
                        fuelCost = litres * pricePerL;
                    }
                }
            }
            trip.fuelCost = fuelCost;
            trip.tripCost = electricCost + fuelCost;

            // Resolve the currency clash properly. Relabelling alone was not enough:
            // tripCost ADDS the electric leg (priced in the charge's currency) to the
            // fuel leg (always priced in the config currency), so a foreign charge
            // currency made the total a sum of two currencies whatever symbol we
            // printed. There is no FX layer, so when they disagree AND both legs
            // exist, fall the electric leg back to the CONFIG rate: one currency
            // throughout, at the cost of not using the foreign tariff for that trip.
            if (!chargeCurrency.isEmpty()) {
                boolean clash = fuelCost > 0 && !chargeCurrency.equals(config.getCurrency());
                if (clash) {
                    trip.electricityRate = config.getElectricityRate();
                    trip.rateSource = "config";
                    trip.rateLabel = "";
                    double reCost = (energyUsed > 0 && trip.electricityRate > 0)
                            ? energyUsed * trip.electricityRate : 0;
                    // Reassign the LOCAL too, not just the field: the cost log below
                    // prints `electricCost`, so leaving the local at the foreign-rate
                    // product made the log contradict its own factors and total.
                    electricCost = reCost;
                    trip.electricCost = electricCost;
                    trip.tripCost = electricCost + fuelCost;
                    logger.info("Trip cost: charge currency " + chargeCurrency
                            + " != config " + config.getCurrency()
                            + " and a petrol leg exists — electric leg re-priced at the config rate");
                } else {
                    trip.currency = chargeCurrency;
                }
            }

            if (trip.tripCost > 0) {
                if (trip.isPhev && fuelCost > 0) {
                    logger.info(String.format(
                            "Trip cost: electric %.2f kWh × %s%.2f = %s%.2f + petrol %.2f L × %s%.2f = %s%.2f → %s%.2f total",
                            energyUsed, trip.currency, trip.electricityRate, trip.currency, electricCost,
                            trip.litresUsed, trip.currency, trip.fuelPricePerL, trip.currency, fuelCost,
                            trip.currency, trip.tripCost));
                } else {
                    logger.info(String.format("Trip cost: %.2f kWh × %s%.2f = %s%.2f (rate from %s%s)",
                            energyUsed, trip.currency, trip.electricityRate, trip.currency, trip.tripCost,
                            trip.rateSource,
                            (trip.rateLabel != null && !trip.rateLabel.isEmpty()) ? " · " + trip.rateLabel : ""));
                }
            }
        }

        // Set telemetry file path (using startTime-based filename)
        trip.telemetryFilePath = telemetryPath;

        // Stat the finalized .jsonl.gz so we can store its size on the
        // row at insert time. This single local-FS stat is what lets
        // StorageManager.getTripsSize() answer via SUM(size_bytes)
        // instead of walking every trips dir on every page load. The
        // file isn't renamed yet (the dbId-based rename happens after
        // insertTrip below) but the byte count is identical, so we stat
        // the startTime-named file here. Errors leave sizeBytes at 0 —
        // the backfill thread will catch it on the next daemon start.
        if (telemetryPath != null) {
            try {
                File f = new File(telemetryPath);
                if (f.exists() && f.isFile()) {
                    trip.sizeBytes = f.length();
                }
            } catch (Throwable e) {
                logger.warn("Failed to stat telemetry file for size accounting: " + e.getMessage());
            }
        }
        // No sidecars in current builds; sidecarSizeBytes stays 0.

        // 4. Insert into database
        if (database != null) {
            long dbId = database.insertTrip(trip);

            // ONE retry. insertTrip's own catch calls reconnect() before
            // returning -1, so by the time we get here a fresh H2 connection may
            // already be in place — the classic interrupted-MVStore case recovers
            // on a second attempt, in-process, instead of deferring to next-boot
            // .jsonl.gz recovery (which re-derives distance from GPS and re-applies
            // the discard floors, so it can silently drop the trip entirely).
            if (dbId <= 0) {
                logger.warn("insertTrip returned " + dbId + " — retrying once after"
                        + " the failure path's reconnect()");
                dbId = database.insertTrip(trip);
                if (dbId > 0) {
                    logger.info("Trip insert succeeded on retry — id=" + dbId);
                }
            }

            if (dbId > 0) {
                // After DB insert, rename telemetry file to use the DB ID
                // and update the record's telemetry file path
                String newPath = recorder != null
                        ? recorder.getTelemetryFilePath(dbId) : null;

                if (newPath != null && telemetryPath != null) {
                    File oldFile = new File(telemetryPath);
                    File newFile = new File(newPath);
                    if (oldFile.exists() && !oldFile.getAbsolutePath().equals(newFile.getAbsolutePath())) {
                        if (oldFile.renameTo(newFile)) {
                            trip.telemetryFilePath = newPath;
                            database.updateTrip(trip);
                            logger.info("Telemetry file renamed: " + oldFile.getName()
                                    + " → " + newFile.getName());
                        } else {
                            logger.warn("Failed to rename telemetry file to " + newFile.getName());
                        }
                    }
                }

                // 5. Update rollups
                database.updateWeeklyRollup(trip);
                database.updateMonthlyRollup(trip);

                // 6. Assign route_id for O(1) similar-trip lookups
                if (trip.startLat != 0 && trip.startLon != 0) {
                    long routeId = database.findOrCreateRoute(
                            trip.startLat, trip.startLon, trip.endLat, trip.endLon, trip.distanceKm);
                    if (routeId > 0) {
                        trip.routeId = routeId;
                        database.updateTrip(trip);
                        logger.info("Trip assigned to route " + routeId);
                    }
                }

                logger.info("Trip saved — id=" + dbId
                        + " scores=[A=" + trip.anticipationScore
                        + " S=" + trip.smoothnessScore
                        + " SD=" + trip.speedDisciplineScore
                        + " E=" + trip.efficiencyScore
                        + " C=" + trip.consistencyScore + "]");

                // The row is durably in place now — the checkpoint's only job
                // (crash-recovery insurance) is done. Left alone until now so a
                // crash during scoring/insert above still has it available.
                try {
                    if (database != null) database.clearTripCheckpoint(trip.startTime);
                } catch (Throwable ignored) {}
            } else {
                // insertTrip failed — deliberately leave the checkpoint in place.
                // Next-boot disk recovery will rebuild a row from the .jsonl.gz
                // (distance/speed/elevation only); enrichRecoveredTripsFromCheckpoints()
                // needs this checkpoint to still exist to fill in SoC/energy for it.
                // Previously there was no else branch, so a failed insert
                // produced NO log line at all here — the trip simply vanished
                // and the only trace was a lower-level "Failed to insert trip".
                // Say it loudly, and name the artifact that survives, because
                // the .jsonl.gz is what next-boot recovery rebuilds the row from.
                logger.error("Trip NOT saved — insertTrip returned " + dbId
                        + " (start=" + trip.startTime + ", distance=" + trip.distanceKm
                        + "km). Telemetry file "
                        + (telemetryPath != null ? telemetryPath : "(none)")
                        + " is left on disk for recovery on next daemon start.");
            }
        }

        // 6. Update range estimator
        if (rangeEstimator != null) {
            rangeEstimator.onTripCompleted(trip);
        }
        } finally {
            // Row now exists (or insert failed) — drop the in-flight marker so
            // the file is reapable again and recovery treats it normally.
            if (reArmedMarker) {
                try {
                    com.overdrive.app.storage.StorageManager.getInstance().setActiveTripFile(null);
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Resolve the trip's electrical energy use in kWh.
     *
     * <p>Three tiers, most to least accurate:
     * <ol>
     *   <li><b>Metered</b> — delta of the HAL's cumulative electricity counter.
     *       The only tier with the resolution to measure a short trip.</li>
     *   <li><b>Remaining-energy delta</b> — derived from an integer-resolution
     *       SoC, so it reads a flat 0 below roughly 4 km.</li>
     *   <li><b>SoC estimate</b> — SoC delta × calibrated pack capacity.</li>
     * </ol>
     * The first two live in {@link TripRecord#getEnergyUsedKwh()}. Returns 0 when
     * no source is usable (e.g. SoC flat or rose, no capacity estimate).
     *
     * <p>Called BEFORE scoring so the efficiency axis and the cost math both see
     * the same kWh figure on a single unit axis.
     */
    private double resolveTripEnergyKwh(TripRecord trip) {
        // Reject a metered delta that no battery could have supplied over this
        // distance (generous 100 kWh/100km ceiling, plus 1 kWh of slack for very
        // short trips). A counter reset or a unit change between the two reads
        // would otherwise be booked as a huge, confidently-wrong measurement.
        // Clearing the snapshots makes every downstream tier — here and in
        // TripRecord — fall through consistently instead of disagreeing.
        if (trip.hasMeteredEnergy() && trip.distanceKm > 0) {
            double maxPlausibleKwh = 1.0 + trip.distanceKm;
            if (trip.getMeteredEnergyKwh() > maxPlausibleKwh) {
                logger.warn(String.format(
                        "Metered energy implausible (%.2f kWh over %.2f km) — discarding accumulator, using SoC path",
                        trip.getMeteredEnergyKwh(), trip.distanceKm));
                trip.elecConStart = -1;
                trip.elecConEnd = -1;
            }
        }
        double energyUsed = trip.getEnergyUsedKwh();
        if (energyUsed > 0) {
            return energyUsed;
        }
        // The meter reported a true zero AND the remaining-energy delta agreed, so
        // 0 is a measurement rather than a missing value — estimating from SoC
        // would manufacture consumption the vehicle says did not happen. This is
        // the normal reading for a PHEV leg driven entirely on the engine.
        //
        // Unless SoC disagrees CLEARLY: a counter stuck at a fixed non-zero value
        // isn't tracking on this trim, and if SoC really fell then energy was used.
        // The threshold matters — SoC is integer-resolution here, so a 1% step is
        // indistinguishable from quantisation noise or from parasitic/HVAC draw on
        // an engine-driven leg, and treating it as propulsion energy would invent
        // roughly 0.6 kWh of cost the meter says was never drawn. Requiring a
        // margin above one step means only an unmistakable drop overrides the
        // meter, while a genuinely stuck counter over any real drive still does.
        double socDrop = (trip.socStart > 0 && trip.socEnd > 0) ? trip.socStart - trip.socEnd : 0;
        boolean socFellClearly = socDrop > SOC_OVERRIDE_MIN_DROP_PCT;
        if (trip.hasMeteredEnergy() && !socFellClearly) {
            return 0;
        }

        // Estimate from SoC delta via SohEstimator's calibrated nominal capacity.
        if (trip.socStart > 0 && trip.socEnd > 0 && trip.socStart > trip.socEnd) {
            try {
                com.overdrive.app.abrp.SohEstimator soh =
                    com.overdrive.app.monitor.SocHistoryDatabase.getInstance().getSohEstimator();
                if (soh != null && soh.getNominalCapacityKwh() > 0) {
                    double nominalKwh = soh.getNominalCapacityKwh();
                    // BYD packs are LFP (Blade) and hold ≥98% SOH for the first
                    // ~1500 cycles, so 100% is a fair default until a live
                    // estimate seeds. Log when SOH is unseeded so a wrong number
                    // can be traced back to this branch.
                    // Use the DISPLAYED SOH (capped, anchored) so trip energy is in
                    // the same frame as the live remaining-kWh / SOH the user sees.
                    boolean hasSoh = soh.hasDisplaySoh();
                    double sohPercent = hasSoh ? soh.getDisplaySoh() : 100.0;
                    double usableKwh = nominalKwh * (sohPercent / 100.0);
                    energyUsed = ((trip.socStart - trip.socEnd) / 100.0) * usableKwh;
                    logger.info(String.format("Energy estimated from SoC: %.1f%% → %.1f%% = %.2f kWh (nominal=%.1f, SOH=%.1f%%%s)",
                            trip.socStart, trip.socEnd, energyUsed, nominalKwh, sohPercent,
                            hasSoh ? "" : ", default — no SOH seeded yet (LFP)"));
                }
            } catch (Exception e) {
                logger.warn("SohEstimator not available for energy estimation: " + e.getMessage());
            }
        }
        return energyUsed;
    }

    /**
     * Handle trip discarded event from TripDetector.
     * Stop recorder and clean up the telemetry file.
     */
    private void handleTripDiscarded(TripRecord trip, String reason) {
        logger.info("Trip discarded: " + reason);
        // A discarded trip (below min duration/distance) never gets a `trips`
        // row either — no crash to recover from, so no reason to keep insurance.
        try {
            if (database != null && trip != null) database.clearTripCheckpoint(trip.startTime);
        } catch (Throwable ignored) {}

        // Release telemetry polling ref (acquired in handleTripStarted)
        if (telemetryDataCollector != null) {
            telemetryDataCollector.stopPolling();
        }

        if (recorder != null) {
            String telemetryPath = recorder.stopRecording();

            // Clean up telemetry file
            if (telemetryPath != null) {
                File telemetryFile = new File(telemetryPath);
                if (telemetryFile.exists()) {
                    if (telemetryFile.delete()) {
                        logger.info("Discarded telemetry file: " + telemetryFile.getName());
                    } else {
                        logger.warn("Failed to delete discarded telemetry file: "
                                + telemetryFile.getName());
                    }
                }
            }
        }
    }
}

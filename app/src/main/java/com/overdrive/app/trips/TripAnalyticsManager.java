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
            detector.finalizeActiveTrip();
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
        logger.info("ACC ON — trip detection ready (waiting for gear D/R)");

        // Safety net: probe current gear in case we missed the gear change event
        // during the ACC OFF→ON transition
        try {
            int currentGear = GearMonitor.getInstance().getCurrentGear();
            if (GearMonitor.isValidGearMode(currentGear)
                    && currentGear != GearMonitor.GEAR_P
                    && detector != null) {
                if (!detector.isTripActive()) {
                    logger.info("ACC ON + gear already " + GearMonitor.gearToString(currentGear)
                            + " — auto-starting trip");
                }
                // ACTIVE ignores a driving gear; PARK_PENDING needs it to cancel its park debounce.
                detector.onGearChanged(currentGear);
            }
        } catch (Exception e) {
            logger.warn("ACC ON gear probe failed: " + e.getMessage());
        }
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
            // Disabling — finalize active trip first
            if (detector != null && detector.isTripActive()) {
                logger.info("Disabling while trip active — finalizing trip");
                detector.finalizeActiveTrip();
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
            int currentGear = GearMonitor.getInstance().getCurrentGear();
            if (GearMonitor.isValidGearMode(currentGear)
                    && currentGear != GearMonitor.GEAR_P
                    && detector != null) {
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
     * {@code .jsonl.gz} on disk covers everything sampled so far. The next
     * process RESUMES the trip from that journal when it starts within
     * {@link #RESUME_MAX_GAP_MS} (see {@link #tryResumeInterruptedTrip});
     * otherwise {@code recoverTripsFromDisk} finalizes the row from it.
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
     * Display copy of the active trip with its progress so far (distance, duration, energy/km),
     * or null when no trip is active. The active record itself only gains these at finalize.
     */
    public TripRecord getLiveTripSnapshot() {
        TripDetector d = detector;
        TripRecord active = d != null ? d.getActiveTrip() : null;
        if (!isTripActive() || active == null || active.startTime <= 0) return null;
        TripRecord live = new TripRecord();
        live.id = active.id;
        live.startTime = active.startTime;
        long parkedAt = d.getParkStartTime();
        long endMs = parkedAt > 0 ? parkedAt : System.currentTimeMillis();
        live.durationSeconds = (int) Math.max(0L, (endMs - active.startTime) / 1000L);
        TripTelemetryRecorder rec = recorder;
        // until the recorder starts on this trip it still holds the last trip's totals
        boolean ours = rec != null && rec.getStatsTripKey() == active.startTime;
        double km = ours ? rec.getTotalDistanceKm() : 0;
        live.distanceKm = Double.isFinite(km) && km > 0 ? km : 0;
        live.maxSpeedKmh = ours ? Math.max(0, rec.getMaxSpeedKmh()) : 0;
        live.avgSpeedKmh = ours ? Math.max(0.0, rec.getAvgSpeedKmh()) : 0;
        live.iceSecondsAtomic.set(active.iceSeconds());
        // whether a hybrid's engine use was captured: a trip started before the powertrain was known has none
        live.fuelPctStart = active.fuelPctStart;
        live.fuelConStart = active.fuelConStart;
        try {
            com.overdrive.app.monitor.VehicleDataMonitor vdm = com.overdrive.app.monitor.VehicleDataMonitor.getInstance();
            live.isPhev = active.isPhev || vdm.isPhev();
            com.overdrive.app.monitor.DrivingRangeData dr = live.isPhev ? vdm.getDrivingRange() : null;
            if (dr != null && dr.hasFuelPercent()) live.fuelPctEnd = dr.fuelPercent;
        } catch (Exception ignored) {}
        // the charge the trip has taken so far: its start against the pack now
        live.socStart = active.socStart;
        try {
            com.overdrive.app.monitor.BatterySocData soc = com.overdrive.app.monitor.VehicleDataMonitor.getInstance().getBatterySoc();
            if (soc != null && soc.socPercent > 0) live.socEnd = soc.socPercent;
        } catch (Exception ignored) {}
        // Below 1 km a single counter/SoC step dominates the ratio.
        if (live.distanceKm >= 1.0) {
            try {
                com.overdrive.app.monitor.VehicleDataMonitor vdm =
                        com.overdrive.app.monitor.VehicleDataMonitor.getInstance();
                double used = -1;
                double elecConNow = vdm.getTotalElecCon();
                // The metered counter first: the net remaining-energy delta moves in whole SoC steps.
                if (active.elecConStart > 0 && Double.isFinite(elecConNow)
                        && elecConNow >= active.elecConStart
                        && elecConNow - active.elecConStart <= 1.0 + live.distanceKm) {
                    used = elecConNow - active.elecConStart;
                } else {
                    live.kwhStart = active.kwhStart;
                    live.kwhEnd = vdm.getBatteryRemainPowerKwh();
                    used = live.getEnergyUsedKwh();
                }
                if (used > 0) live.energyPerKm = used / live.distanceKm;
            } catch (Exception e) {
                logger.debug("Live trip energy unavailable: " + e.getMessage());
            }
        }
        return live;
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
        // A trip resumed at init (before the collector existed) still needs the
        // 5 Hz dynamics poll for its scoring stream — acquire the polling ref
        // now, exactly as handleTripStarted would have. Released by the trip
        // end/discard handlers like any other trip.
        if (collector != null && !tripPollingHeld && isTripActive()) {
            try {
                collector.startPolling();
                tripPollingHeld = true;
                logger.info("TelemetryDataCollector polling acquired late for the resumed trip");
            } catch (Exception e) {
                logger.warn("Late polling start for resumed trip failed: " + e.getMessage());
            }
        }
    }

    /**
     * True while this manager holds a TelemetryDataCollector polling ref on
     * behalf of the active trip (acquired at trip start / resume, released at
     * trip end / discard). Keeps start/stop calls paired when the collector is
     * bound after the trip began.
     */
    private volatile boolean tripPollingHeld = false;

    /** Release the trip's polling ref if (and only if) this manager acquired one. */
    private void releaseTripPolling() {
        if (!tripPollingHeld) return;
        tripPollingHeld = false;
        TelemetryDataCollector collector = telemetryDataCollector;
        if (collector != null) {
            try {
                collector.stopPolling();
            } catch (Exception e) {
                logger.warn("Failed to stop TelemetryDataCollector polling: " + e.getMessage());
            }
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

        // NOTE (insert-at-start): the orphaned-row janitor used to run HERE,
        // synchronously, BEFORE recovery. Trip rows are now inserted at trip
        // START (end_time=0 until finalize), and a mid-drive SIGKILL leaves
        // exactly such a row — which recovery FINALIZES in place from the
        // surviving telemetry, preserving the live start-side snapshots that
        // telemetry cannot rebuild (SoC, kWh, accumulators, PHEV fields).
        // Deleting end_time=0 rows before that pass would destroy precisely
        // the rows recovery exists to complete, so the janitor now runs
        // INSIDE the recovery thread, strictly AFTER recoverTripsFromDisk.

        // Auto-recover trips from surviving .jsonl.gz files on disk. Two cases:
        //  1. A file matching an end_time=0 row (crash mid-trip after the
        //     start-time insert) → the row is FINALIZED in place, same id.
        //  2. A file with no row at all (legacy crash, or the start-time
        //     insert itself failed) → a row is reconstructed and inserted, as
        //     before. recoverTripsFromDisk() is idempotent (dedup by basename,
        //     start-time, signature), skips the currently-active trip file,
        //     and respects minimum-trip thresholds — safe to run at every
        //     startup. Runs on a background thread so it doesn't delay ACC-ON
        //     responsiveness (FUSE listing can take seconds on large trip dirs).
        // Detector + recorder + engines are built BEFORE the recovery thread is
        // spawned so a same-session resume (below) can re-adopt an interrupted
        // drive before recovery gets a chance to finalize its row from
        // telemetry alone.
        detector = new TripDetector();
        detector.setListener(new TripDetector.TripListener() {
            @Override
            public void onTripStarted(TripRecord trip) {
                handleTripStarted(trip);
            }

            @Override
            public void onTripEnded(TripRecord trip) {
                handleTripEnded(trip);
                try {
                    com.overdrive.app.briefing.daemon.TripBriefingService.get().onTripEnded();
                } catch (Throwable ignored) {}
            }

            @Override
            public void onTripDiscarded(TripRecord trip, String reason) {
                handleTripDiscarded(trip, reason);
            }

            @Override
            public double getRecordedDistanceKm() {
                return recorder != null ? recorder.getTotalDistanceKm() : 0;
            }
        });

        // Recorder
        recorder = new TripTelemetryRecorder(telemetryDataCollector);

        // Score engine
        scoreEngine = new TripScoreEngine();

        // Range estimator
        rangeEstimator = new RangeEstimator(database, sohEstimator);

        // SAME-SESSION RESUME (field incident log_DG87KWQX): a GL-watchdog
        // process restart landed 6 s into a trip's park debounce. The old
        // "trip-safe restart" only flushed the journal; this process then let
        // recovery FINALIZE the row from telemetry — no end SoC/kWh, no
        // odometer end, no DNA scores, no cost — while the car was still ACC
        // ON in P for another minute and a half. If the interrupted drive's
        // journal is fresh, re-adopt it as the ACTIVE trip instead, so the
        // normal live finalize runs with every end-side read and the engine.
        // Must run BEFORE the recovery thread starts: it also arms the
        // in-flight file marker recovery honours.
        try {
            tryResumeInterruptedTrip();
        } catch (Throwable t) {
            logger.warn("Same-session trip resume failed: " + t.getMessage());
        }

        try {
            final StorageManager sm = StorageManager.getInstance();
            final java.util.List<File> tripsDirs = new java.util.ArrayList<>();
            java.util.List<File> configured = sm.getAllTripsDirs();
            if (configured != null) tripsDirs.addAll(configured);
            // The in-flight journal now lives on internal storage; a crash
            // leaves its file THERE, so recovery must scan that dir too.
            final File journalDir = sm.getTripJournalDir();
            if (journalDir != null && !tripsDirs.contains(journalDir)) tripsDirs.add(journalDir);
            boolean anyDir = false;
            for (File d : tripsDirs) {
                if (d != null && d.isDirectory()) { anyDir = true; break; }
            }
            final boolean haveAnyDir = anyDir;
            final TripDatabase db = database;
            Thread recoveryThread = new Thread(() -> {
                boolean recoveryRan = false;
                try {
                    if (haveAnyDir) {
                        TripDatabase.RecoveryResult r = db.recoverTripsFromDisk(tripsDirs);
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
                    }
                    recoveryRan = db.isAvailable();
                } catch (Throwable t) {
                    logger.warn("Auto-recovery failed: " + t.getMessage());
                }
                // Janitor strictly LAST: any end_time=0 row still standing
                // after the finalize pass has no recoverable telemetry — reap
                // it once older than 24 hours (never the current drive's row,
                // whose start_time is minutes old).
                try {
                    long cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L;
                    db.deleteOrphanedTrips(cutoff);
                } catch (Throwable t) {
                    logger.warn("Orphaned trip cleanup failed: " + t.getMessage());
                }
                // Journal-dir janitor: a journal that recovery could neither
                // finalize nor insert (below the trip floors with no row,
                // unreadable) would otherwise sit on /data forever. Anything
                // older than a week there is dead; the active trip's file is
                // protected by the in-flight marker regardless of age.
                // Journals recovery never got to process are unsaved trips, not dead ones.
                if (!recoveryRan) {
                    logger.warn("Trip journal cleanup skipped: trip recovery did not run");
                    return;
                }
                try {
                    reapStaleJournals(journalDir, System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000L);
                } catch (Throwable t) {
                    logger.warn("Trip journal cleanup failed: " + t.getMessage());
                }
            }, "TripAutoRecover");
            recoveryThread.setDaemon(true);
            recoveryThread.start();
        } catch (Throwable t) {
            logger.warn("Trip auto-recovery setup failed: " + t.getMessage());
        }

        // Backfill route_id for existing trips (idempotent — skips already-assigned trips)
        database.backfillRouteIds();

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

    // ==================== SAME-SESSION RESUME ====================

    /** A journal whose last sample is older than this belongs to recovery, not to a resume. */
    static final long RESUME_MAX_GAP_MS = 10L * 60L * 1000L;
    /** Sanity bound on the age of a resumable row (a genuine drive, not a stale straggler). */
    static final long RESUME_MAX_TRIP_AGE_MS = 12L * 60L * 60L * 1000L;

    /**
     * Re-adopt the most recent half-open trip row if its telemetry journal is
     * fresh enough to be the SAME drive this process was restarted in the
     * middle of. Arms the in-flight file marker (so the recovery thread skips
     * the journal), re-attaches the recorder to the journal, and puts the
     * detector into ACTIVE or PARK_PENDING based on the journal tail / live
     * gear. Returns true when a trip was resumed.
     */
    private boolean tryResumeInterruptedTrip() {
        if (database == null || detector == null || recorder == null) return false;
        List<TripRecord> open = database.getUnfinalizedTrips();
        if (open == null || open.isEmpty()) return false;
        TripRecord row = open.get(0);   // newest first
        final long now = System.currentTimeMillis();
        if (row.startTime <= 0 || row.startTime > now + 60_000L
                || now - row.startTime > RESUME_MAX_TRIP_AGE_MS) {
            logger.info("Resume: half-open row id=" + row.id + " is too old ("
                    + row.startTime + ") — leaving it to recovery");
            return false;
        }

        File journal = locateJournal(row.startTime);
        if (journal == null) {
            logger.info("Resume: no journal found for half-open row id=" + row.id
                    + " (start=" + row.startTime + ") — leaving it to recovery");
            return false;
        }
        List<TelemetrySample> history = TelemetryStore.readFromFile(journal);
        if (history == null || history.isEmpty()) {
            logger.info("Resume: journal " + journal.getName() + " is empty — leaving row id="
                    + row.id + " to recovery");
            return false;
        }
        long lastSampleMs = history.get(history.size() - 1).timestampMs;
        long gapMs = now - lastSampleMs;
        if (gapMs < 0 || gapMs > RESUME_MAX_GAP_MS) {
            logger.info("Resume: journal for row id=" + row.id + " last sampled " + (gapMs / 1000)
                    + "s ago (> " + (RESUME_MAX_GAP_MS / 1000) + "s) — leaving it to recovery");
            return false;
        }

        // Where did the car stop, if it did? Same heuristic recovery trims on, except that a stale DI5 gear
        // can't show the car parked: only a driving gear would end the resumed debounce.
        int lastMoving = TripDatabase.lastMovingSampleIndex(history, true);
        boolean parked = lastMoving < history.size() - 1;
        long parkStartMs = parked
                ? history.get(Math.min(lastMoving + 1, history.size() - 1)).timestampMs
                : 0L;
        // The live gear wins over the journal tail when GearMonitor already
        // has a real reading: a driver who kept going through the restart is
        // still ACTIVE even if the journal ended on a red light in P.
        try {
            int liveGear = GearMonitor.getInstance().getCurrentGear();
            if (GearMonitor.isValidGearMode(liveGear) && liveGear != GearMonitor.GEAR_P) {
                parked = false;
                parkStartMs = 0L;
            }
        } catch (Throwable ignored) {}

        // Arm the in-flight marker FIRST so the recovery thread (started right
        // after this) skips the journal instead of finalizing/deleting it.
        try {
            StorageManager.getInstance().setActiveTripFile(journal);
        } catch (Throwable t) {
            logger.warn("Resume: could not arm the active trip marker: " + t.getMessage());
        }

        // Polling ref, mirroring handleTripStarted (released in handleTripEnded).
        // The collector is often still null this early in daemon init;
        // setTelemetryDataCollector acquires the ref late in that case.
        if (telemetryDataCollector != null) {
            try {
                telemetryDataCollector.startPolling();
                tripPollingHeld = true;
            } catch (Exception e) {
                logger.warn("Resume: failed to start TelemetryDataCollector polling: " + e.getMessage());
            }
        }

        recorder.resumeRecording(row.startTime, journal, history);
        long remainingDebounce = parked
                ? TripDetector.PARK_DEBOUNCE_MS - (now - parkStartMs)
                : TripDetector.PARK_DEBOUNCE_MS;
        detector.resumeTrip(row, parked, parkStartMs, remainingDebounce);
        logger.info("Resumed interrupted trip id=" + row.id + " from " + journal.getAbsolutePath()
                + " (" + history.size() + " journaled samples, last " + (gapMs / 1000) + "s ago, "
                + (parked ? "parked" : "driving") + ")");
        return true;
    }

    /**
     * Find {@code <startTime>.jsonl.gz} for a half-open row: the internal
     * journal dir first, then every mounted trips dir (journals written before
     * the journal dir existed). Exact-path probes only — no directory listing,
     * which can hang on a FUSE-bridged card.
     */
    private File locateJournal(long startTime) {
        String name = startTime + ".jsonl.gz";
        java.util.List<File> candidates = new java.util.ArrayList<>();
        try {
            StorageManager sm = StorageManager.getInstance();
            File journalDir = sm.getTripJournalDir();
            if (journalDir != null) candidates.add(journalDir);
            java.util.List<File> dirs = sm.getAllTripsDirs();
            if (dirs != null) candidates.addAll(dirs);
        } catch (Throwable ignored) {}
        for (File dir : candidates) {
            if (dir == null) continue;
            File f = new File(dir, name);
            try {
                if (f.isFile() && f.length() > 0) return f;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** Delete journal-dir files older than {@code olderThanMs}, sparing the in-flight one. */
    private static void reapStaleJournals(File journalDir, long olderThanMs) {
        if (journalDir == null || !journalDir.isDirectory()) return;
        File[] files = journalDir.listFiles();
        if (files == null) return;
        String active = null;
        try { active = StorageManager.getInstance().getActiveTripFilePath(); } catch (Throwable ignored) {}
        int reaped = 0;
        for (File f : files) {
            if (!f.isFile() || !f.getName().endsWith(".jsonl.gz")) continue;
            if (active != null && active.equals(f.getAbsolutePath())) continue;
            if (f.lastModified() < olderThanMs && f.delete()) reaped++;
        }
        if (reaped > 0) logger.info("Reaped " + reaped + " stale trip journal(s)");
    }

    /**
     * Handle trip started event from TripDetector.
     * Start the TripTelemetryRecorder using startTime as the trip ID.
     */
    private void handleTripStarted(TripRecord trip) {
        logger.info("Trip started at " + trip.startTime);

        // Trip map is rendered from telemetry la/lo, which are copied from
        // GpsMonitor on each 5 Hz tick. Distance can still come from CAN speed
        // with no GPS fix, producing trips that look real but have a blank map.
        try {
            com.overdrive.app.monitor.GpsMonitor gps =
                    com.overdrive.app.monitor.GpsMonitor.getInstance();
            if (!gps.getFixSnapshot().hasLocation()) {
                logger.warn("Trip started with no GPS fix in GpsMonitor — "
                        + "LocationSidecar may be down or location permission missing; "
                        + "telemetry will record 0,0 until a fix arrives");
            }
        } catch (Throwable t) {
            logger.warn("Failed to ensure GPS pipeline for trip: " + t.getMessage());
        }

        // Ensure TelemetryDataCollector is polling so we get fresh data
        // It may not be polling if no recording/overlay is active
        if (telemetryDataCollector != null) {
            try {
                telemetryDataCollector.startPolling();
                tripPollingHeld = true;
                logger.info("TelemetryDataCollector polling ensured for trip recording");
            } catch (Exception e) {
                logger.warn("Failed to start TelemetryDataCollector polling: " + e.getMessage());
            }
        }

        // Persist an ACTIVE row NOW (end_time=0, duration=0, distance=0),
        // finalized in handleTripEnded via UPDATE. Before this, the row was
        // inserted only at trip end, so a mid-drive process SIGKILL (adbd
        // cgroup kill, watchdog, OOM) lost the row outright and next-boot
        // recovery re-derived a degraded duplicate from telemetry under a
        // fresh id — the field incident's "recovered trip 3302". With the
        // row durable at start, recovery FINALIZES this same row in place,
        // keeping its identity and the live start-side snapshots (SoC, kWh,
        // accumulators, odometer, PHEV flags) that telemetry cannot rebuild.
        //
        // insertTrip sets trip.id on success. On failure we log and fall
        // back to the legacy insert-at-end flow (trip.id stays 0) — trips
        // must never be blocked on a sick store.
        if (database != null) {
            try {
                long id = database.insertTrip(trip);
                if (id > 0) {
                    logger.info("Active trip row inserted at start — id=" + id);
                } else {
                    logger.warn("Active-row insert failed at trip start — will fall back"
                            + " to insert-at-end for this trip");
                }
            } catch (Throwable t) {
                logger.warn("Active-row insert threw at trip start: " + t.getMessage());
            }
        }

        if (recorder != null) {
            // The recorder keys its file by startTime regardless of the DB id
            // (<startTime>.jsonl.gz, renamed to <dbId>.jsonl.gz at finalize) —
            // unchanged, so recovery's basename matching keeps working for
            // both the finalize-in-place and the legacy reconstruction case.
            recorder.startRecording(trip.startTime);
        }
    }

    /** DI5: elevation gain/loss [m] over the trip's journal up to its end, or null if unreadable. */
    private double[] readJournalElevation(File journal, long tripEndMs) {
        try {
            List<TelemetrySample> rows = TelemetryStore.readFromFile(journal);
            if (rows == null || rows.isEmpty()) return null;
            return TripScoreEngine.journalElevation(rows, tripEndMs > 0 ? tripEndMs + 1_000 : Long.MAX_VALUE);
        } catch (Throwable t) {
            logger.warn("Journal elevation failed: " + t.getMessage());
            return null;
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
    // a frozen copy of the trip that ended last; the widgets show it until its row is finished
    private volatile TripRecord lastEnded;

    /** The trip that ended last as it stood when it ended (no cost or scores yet), or null. */
    public TripRecord getLastEndedSnapshot() {
        return lastEnded;
    }

    // taken on the trip thread before anything finishes the trip, so readers never see it half-updated
    private TripRecord endedCopy(TripRecord t) {
        TripTelemetryRecorder rec = recorder;
        boolean ours = rec != null && rec.getStatsTripKey() == t.startTime;
        TripRecord s = new TripRecord();
        s.id = t.id;
        s.startTime = t.startTime;
        s.endTime = t.endTime;
        s.distanceKm = t.distanceKm;
        s.durationSeconds = t.durationSeconds;
        s.maxSpeedKmh = t.maxSpeedKmh > 0 ? t.maxSpeedKmh : ours ? Math.max(0, rec.getMaxSpeedKmh()) : 0;
        s.avgSpeedKmh = t.avgSpeedKmh > 0 ? t.avgSpeedKmh : ours ? Math.max(0.0, rec.getAvgSpeedKmh()) : 0;
        s.socStart = t.socStart;
        s.socEnd = t.socEnd;
        s.kwhStart = t.kwhStart;
        s.kwhEnd = t.kwhEnd;
        s.elecConStart = t.elecConStart;
        s.elecConEnd = t.elecConEnd;
        s.energyPerKm = t.energyPerKm;
        s.isPhev = t.isPhev;
        s.fuelPctStart = t.fuelPctStart;
        s.fuelPctEnd = t.fuelPctEnd;
        s.fuelConStart = t.fuelConStart;
        s.iceSecondsAtomic.set(t.iceSeconds());
        return s;
    }

    private void handleTripEnded(TripRecord trip) {
        lastEnded = endedCopy(trip);
        logger.info("Trip ended — duration=" + trip.durationSeconds + "s, distance="
                + trip.distanceKm + "km");

        // Release telemetry polling ref (acquired in handleTripStarted / resume)
        releaseTripPolling();

        String telemetryPath = null;

        // 1. Stop recorder, get samples. keepActiveMarker=true: the in-flight
        //    file marker must stay up CONTINUOUSLY from startRecording until
        //    the row is finalized + the file renamed (cleared in the finally
        //    below). The old stop-clear/re-arm sequence left a window — brief
        //    on this thread, but recovery runs on OTHER threads (startup scan,
        //    POST /api/trips/recover) — in which the half-open row plus the
        //    unprotected file could be finalized, duplicated, or deleted
        //    underneath this finalize.
        List<TelemetrySample> samples = null;
        if (recorder != null) {
            telemetryPath = recorder.stopRecording(true);
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

        // The marker was KEPT by stopRecording(true) — no cleared window
        // exists. This re-assert is idempotent belt-and-braces (covers the
        // recorder having lost the marker to a mid-trip volume-migration
        // hiccup); the finally clears it once the row is complete.
        boolean reArmedMarker = false;
        if (telemetryPath != null) {
            try {
                com.overdrive.app.storage.StorageManager.getInstance()
                        .setActiveTripFile(new File(telemetryPath));
            } catch (Exception ignored) {}
            reArmedMarker = true;
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
        // DI5: elevation from every journaled row, as recovery takes it, since the scoring stream lacks
        // the ticks without a vehicle speed; too little scoreable data stays unscored (the UI's "no data").
        final boolean diLink5 = com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected();
        double[] journalElevation = diLink5 && telemetryPath != null
                ? readJournalElevation(new File(telemetryPath), trip.endTime) : null;
        boolean diLink5Scoreable = !diLink5 || (samples != null && TripScoreEngine.hasDiLink5ScoringCoverage(
                TripScoreEngine.scoreableSeconds(samples), trip.durationSeconds));
        if (scoreEngine != null && samples != null && !samples.isEmpty() && diLink5Scoreable) {
            if (journalElevation != null) {
                scoreEngine.computeSummary(trip, samples, journalElevation[0], journalElevation[1]);
            } else {
                scoreEngine.computeSummary(trip, samples);
            }
        } else if (journalElevation != null) {
            TripScoreEngine.applyElevation(trip, journalElevation[0], journalElevation[1]);
        }

        // 4. Populate recorder stats (recorder is authoritative for avg/max speed)
        if (recorder != null) {
            trip.maxSpeedKmh = recorder.getMaxSpeedKmh();
            trip.avgSpeedKmh = recorder.getAvgSpeedKmh();
            // DI5: measured distance over time, as recovery does; ticks without a vehicle speed are
            // not in the mean. The trusted odometer when set, else the recorder, never the straight line.
            // A restart gap no live fix measured has no distance, so its time in the trip is left out too.
            boolean odometerDistance = trip.odometerStartKm > 0 && trip.odometerEndKm > 0;
            double measuredKm = odometerDistance ? trip.distanceKm : recorder.getTotalDistanceKm();
            double measuredSeconds = trip.durationSeconds - (odometerDistance ? 0
                    : recorder.getUnmeasuredGapMs(trip.startTime, trip.endTime) / 1000.0);
            if (measuredSeconds <= 0) measuredSeconds = trip.durationSeconds;
            // Nothing measured at all (no live fix, no speed): the trip's own estimate, not a 0 average.
            if (diLink5 && measuredKm <= 0 && trip.distanceKm > 0) {
                measuredKm = trip.distanceKm;
                measuredSeconds = trip.durationSeconds;
            }
            if (diLink5 && measuredKm > 0 && measuredSeconds > 0) {
                trip.avgSpeedKmh = measuredKm / (measuredSeconds / 3600.0);
                // A peak no speed reading caught (distance by position only) is at least the average.
                if (trip.maxSpeedKmh < trip.avgSpeedKmh) trip.maxSpeedKmh = (int) Math.ceil(trip.avgSpeedKmh);
            }
        } else if (diLink5 && trip.avgSpeedKmh <= 0 && trip.distanceKm > 0 && trip.durationSeconds > 0) {
            // DI5 with no recorder: the trip's own distance over its time, not a 0 average.
            trip.avgSpeedKmh = trip.distanceKm / (trip.durationSeconds / 3600.0);
            if (trip.maxSpeedKmh < trip.avgSpeedKmh) trip.maxSpeedKmh = (int) Math.ceil(trip.avgSpeedKmh);
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

        // 4. Finalize (or insert) the database row
        if (database != null) {
            long dbId;
            if (trip.id > 0) {
                // Row was inserted at trip START — finalize it in place with a
                // full-row UPDATE. ONE retry: updateTrip's catch already ran
                // reconnect(), so a second attempt lands on a fresh H2
                // connection (same rationale as the legacy insert retry).
                boolean finalized = database.updateTrip(trip);
                if (!finalized) {
                    logger.warn("Trip finalize UPDATE failed for id=" + trip.id
                            + " — retrying once after the failure path's reconnect()");
                    finalized = database.updateTrip(trip);
                }
                // If the UPDATE cannot land, deliberately DO NOT fall back to
                // insertTrip: the active row exists, so an insert would create
                // the exact start-time duplicate the dedup keys guard against.
                // The end_time=0 row + surviving .jsonl.gz are precisely what
                // next-boot recovery finalizes.
                dbId = finalized ? trip.id : -1;
            } else {
                // Legacy path (active-row insert failed at trip start).
                dbId = database.insertTrip(trip);

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
            }

            if (dbId > 0) {
                // a legacy insert gives the row its id only now; the widgets' copy needs it to notice a delete
                TripRecord ended = lastEnded;
                if (ended != null && ended.id <= 0 && ended.startTime == trip.startTime) lastEnded = endedCopy(trip);

                // After DB insert, rename telemetry file to use the DB ID
                // and update the record's telemetry file path
                String newPath = recorder != null
                        ? recorder.getTelemetryFilePath(dbId) : null;

                if (newPath != null && telemetryPath != null) {
                    File oldFile = new File(telemetryPath);
                    File newFile = new File(newPath);
                    if (oldFile.exists() && !oldFile.getAbsolutePath().equals(newFile.getAbsolutePath())) {
                        // Marker flip + rename run ATOMICALLY under the trips
                        // cleanup lock (renameActiveTripFile): every reaper
                        // pass holds that lock and protects the marker's exact
                        // path, so no pass can observe the mixed state (marker
                        // moved, file not yet renamed) and delete the source.
                        // Marker restore on failure lives inside the helper.
                        // moveActiveTripFile (not rename): the journal lives on
                        // internal storage and the trips dir is usually the SD
                        // card, so this is a cross-filesystem move (copy+delete
                        // fallback) with the same marker atomicity.
                        boolean renamed = false;
                        try {
                            StorageManager sm = StorageManager.getInstance();
                            renamed = sm.moveActiveTripFile(oldFile, newFile);
                            // The trips card refuses writes: its dir now resolves to internal.
                            if (!renamed && sm.reportWriteFault(newFile.getParentFile())) {
                                String internalPath = recorder.getTelemetryFilePath(dbId);
                                if (!internalPath.equals(newPath)) {
                                    logger.warn("Trip file could not be saved to " + newFile.getParent()
                                            + " (card refuses writes) — saving it to internal storage");
                                    newPath = internalPath;
                                    newFile = new File(newPath);
                                    renamed = sm.moveActiveTripFile(oldFile, newFile);
                                }
                            }
                        } catch (Exception e) {
                            logger.warn("moveActiveTripFile threw: " + e.getMessage());
                        }
                        if (renamed) {
                            trip.telemetryFilePath = newPath;
                            database.updateTrip(trip);
                            logger.info("Telemetry file renamed: " + oldFile.getName()
                                    + " → " + newFile.getAbsolutePath());
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

                try {
                    com.overdrive.app.safekeep.SafeKeep.onTripSaved(dbId);
                } catch (Throwable ignored) {
                }

                logger.info("Trip saved — id=" + dbId
                        + " scores=[A=" + trip.anticipationScore
                        + " S=" + trip.smoothnessScore
                        + " SD=" + trip.speedDisciplineScore
                        + " E=" + trip.efficiencyScore
                        + " C=" + trip.consistencyScore + "]");
            } else {
                // Say it loudly, and name the artifacts that survive: the
                // .jsonl.gz (and, on the finalize path, the still-active row
                // id) are what next-boot recovery completes the trip from.
                logger.error("Trip NOT saved — "
                        + (trip.id > 0
                            ? "finalize UPDATE failed for active row id=" + trip.id
                            : "insertTrip returned " + dbId)
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

        // Release telemetry polling ref (acquired in handleTripStarted / resume)
        releaseTripPolling();

        // Remove the active row inserted at trip start — a below-threshold
        // trip must leave no history. If the delete fails, the end_time=0 row
        // is reaped by the >24h janitor on a later start (its telemetry file
        // is deleted just below, so the recovery finalize pass skips it).
        if (database != null && trip.id > 0) {
            try {
                boolean deleted = database.deleteTrip(trip.id);
                if (!deleted) {
                    logger.warn("Failed to delete discarded active trip row id=" + trip.id
                            + " — the >24h orphan janitor will reap it");
                }
            } catch (Throwable t) {
                logger.warn("Discarded-trip row delete threw: " + t.getMessage());
            }
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

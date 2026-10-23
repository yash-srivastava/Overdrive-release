package com.overdrive.app.trips;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.GearMonitor;
import com.overdrive.app.monitor.GpsMonitor;
import com.overdrive.app.storage.StorageManager;
import com.overdrive.app.telemetry.TelemetryDataCollector;
import com.overdrive.app.telemetry.TelemetrySnapshot;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

/**
 * Captures 5Hz telemetry by reading from existing singleton monitors.
 * Buffers samples in memory, flushes 1Hz-downsampled gzipped JSON-lines to disk.
 *
 * Does NOT create its own BYD device handles — reads from TelemetryDataCollector,
 * GpsMonitor, VehicleDataMonitor, and GearMonitor which are already running.
 */
public class TripTelemetryRecorder {

    private static final DaemonLogger logger = DaemonLogger.getInstance("TripTelemetryRecorder");

    private static final long SAMPLE_INTERVAL_MS = 200;       // 5Hz
    private static final long FLUSH_INTERVAL_MS = 60_000;     // 60s
    // DI5 has no odometer: rows a process death loses can only be bridged by one straight line.
    private static final long DILINK5_FLUSH_INTERVAL_MS = 15_000;
    /** Delay before the FIRST flush. Short on purpose — see startRecording:
     *  until a flush lands there is no on-disk trace of the trip, so a process
     *  death before it is unrecoverable. 5s ≈ 25 samples: cheap, and it closes
     *  a 60s data-loss window at the start of every trip. */
    private static final long FIRST_FLUSH_DELAY_MS = 5_000;
    private static final long MAX_BUFFER_BYTES = 10 * 1024 * 1024; // 10MB

    // Distance fusion tunables.
    // Reject a GPS fallback segment whose reported horizontal accuracy is worse
    // than this — a 50 m-error fix can wander metres between ticks while parked.
    private static final float GPS_ACCURACY_GATE_M = 50.0f;
    // Floor below which a GPS fallback segment is treated as stationary jitter
    // rather than travel (~2 m random walk at the typical fix noise level).
    private static final double MIN_GPS_SEGMENT_KM = 0.002;
    // DI5: a GPS 0 is a stop when the positions agree; parked jitter stays under it.
    private static final double DILINK5_GPS_STOP_MAX_KMH = 20.0;
    // DI5: under this a GPS speed is a standstill's Doppler noise.
    private static final int DILINK5_GPS_NOISE_KMH = 3;
    // Cap dt for speed integration so a scheduler stall / process resume can't
    // turn one tick into a kilometre. Normal cadence is 200 ms.
    private static final long MAX_INTEGRATION_DT_MS = 2_000;

    // Dependencies: existing singleton monitors
    private volatile TelemetryDataCollector telemetryDataCollector;

    // Executor for 5Hz sampling
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> sampleFuture;
    private ScheduledFuture<?> flushFuture;

    // Buffer (guarded by bufferLock)
    private final Object bufferLock = new Object();
    private ArrayList<TelemetrySample> buffer = new ArrayList<>();
    private long estimatedBufferBytes = 0;

    // All captured 5Hz samples for scoring (not cleared on flush)
    private final Object allSamplesLock = new Object();
    private ArrayList<TelemetrySample> allSamples = new ArrayList<>();

    // Trip state
    private volatile boolean recording = false;
    // volatile: the live trip readout checks it from the HTTP thread (see getStatsTripKey);
    // beginRecording sets it only once the stats are reset
    private volatile long currentTripId = -1;
    private File outputFile;

    // Stats tracking (maxSpeedKmh is also read by the live readout's HTTP thread)
    private volatile int maxSpeedKmh = 0;
    private long speedSumKmh = 0;
    private long speedSampleCount = 0;
    
    // Live distance tracking (fused).
    // volatile: written only on the 5Hz sampler thread but read on the
    // detector/finalize thread via getTotalDistanceKm() (the live-distance
    // fallback when the hardware odometer is unavailable). Single writer, so
    // volatile fully resolves the cross-thread visibility — the finalize read
    // sees the latest accumulated distance.
    //
    // Fusion strategy (SOTA for a vehicle with a wheel-derived speed bus):
    //   • PRIMARY  = integrate CAN/wheel speed over dt (speedKmh × dt). This is
    //     immune to GPS jitter while parked, multipath in urban canyons, and
    //     signal loss in tunnels/garages — the classic failure modes of a pure
    //     fix-to-fix haversine sum. Wheel speed reads ~0 when stopped, so idle
    //     dwell contributes nothing instead of accreting drift.
    //   • FALLBACK = accuracy-gated GPS haversine, used only for ticks where we
    //     had no fresh dynamics snapshot (speed unknown). Gated on reported
    //     horizontal accuracy and a minimum-segment floor so a noisy fix can't
    //     manufacture phantom travel.
    // The hardware odometer delta still overrides this entirely at finalize
    // (TripDetector); this value is the live readout and the odometer-absent
    // fallback.
    private volatile double totalDistanceKm = 0;
    private double lastLat = 0;
    private double lastLon = 0;
    private boolean hasLastGps = false;
    private long lastSampleMs = 0;

    // True once the CAN/wheel speed channel has produced ANY non-zero reading in
    // this trip — i.e. proof the channel is actually wired, as opposed to a fresh
    // snapshot whose speedKmh is simply the int 0 initialiser because the
    // BYDAutoSpeedDevice bind failed. Distance integration trusts the speed
    // channel only after that proof; until then GPS carries the distance. Reset
    // per trip in startRecording so one bad trip can't poison the next.
    private boolean speedChannelEverLive = false;
    // DI5 only: the latest distinct GPS fix: its own trusted speed, position and checked speed (-1 unknown).
    private long diLink5FixId = 0L;
    private int diLink5FixRawKmh = -1;
    private double diLink5FixLat = 0, diLink5FixLon = 0;
    private int diLink5FixCheckedKmh = -1;
    // DI5 only: the speed the current fix contributes to distance (its own, unless it jumped).
    private int diLink5FixDistanceKmh = -1;
    // DI5 only: recent live fixes, oldest first, spanning at most 5 s.
    private final java.util.ArrayDeque<RecentFix> diLink5RecentFixes = new java.util.ArrayDeque<>();
    // DI5 only: where a GPS-only stretch under 10 km/h began (its fix and accuracy), and Doppler km since.
    private boolean diLink5SlowAnchored = false;
    private double diLink5SlowLat = 0, diLink5SlowLon = 0;
    private float diLink5SlowAccuracyM = 0f;
    private double diLink5SlowDopplerKm = 0;
    // DI5 only: what position-measured closes already added during the slow stretch.
    private double diLink5SlowCountedKm = 0;
    // DI5 only: distance a slow stretch lands at this tick's new fix (0 if none).
    private double diLink5LandKm = 0;
    // DI5 only: restart gaps [from, to] no live fix could measure (their distance is missing), and
    // those in the interval still open. Finalize reads them once the sampler has stopped.
    private final Object diLink5GapLock = new Object();
    private final ArrayList<long[]> diLink5UnmeasuredGaps = new ArrayList<>();
    private final ArrayList<long[]> diLink5PendingGaps = new ArrayList<>();
    // DI5 only: a resume's restart gap, which ends at the first live tick as the journal's next row does.
    private long[] diLink5SeedGap = null;
    // DI5 only: distance integrated since the GPS anchor (a live fix) and when it was taken; whether
    // a tick since then had no speed, so the move to the next live fix is measured by position.
    private double diLink5KmSinceAnchor = 0;
    private long diLink5AnchorAtMs = 0L;
    private boolean diLink5AnchorUnmeasured = false;
    private boolean diLink5AnchorStill = false;
    private boolean diLink5AnchorParked = false;
    private float diLink5AnchorAccuracyM = 0f;
    // DI5 only: the open interval waited at its anchor for a move the car's state could not explain.
    private boolean diLink5AnchorHeld = false;
    private boolean diLink5PrevParked = false;

    // GPS coverage tracking — how many samples landed valid lat/lon. Logged
    // at trip end so the daemon log alone tells us why a trip's map is blank
    // (no GPS during recording vs. file write failure vs. UI bug).
    private long sampleCountTotal = 0;
    private long sampleCountWithGps = 0;

    /**
     * Constructor takes TelemetryDataCollector as parameter (injected from TripAnalyticsManager).
     * May be null if TelemetryDataCollector hasn't been initialized yet (GPU init delay).
     */
    public TripTelemetryRecorder(TelemetryDataCollector telemetryDataCollector) {
        this.telemetryDataCollector = telemetryDataCollector;
    }

    /**
     * Update the TelemetryDataCollector reference after late initialization.
     * Called by CameraDaemon once TelemetryDataCollector is ready (after GPU init delay).
     */
    public void setTelemetryDataCollector(TelemetryDataCollector collector) {
        this.telemetryDataCollector = collector;
    }

    /**
     * Start recording telemetry for the given trip.
     * Starts the 5Hz sampling timer and periodic flush timer.
     */
    public void startRecording(long tripId) {
        // JOURNAL ON INTERNAL STORAGE: the in-flight file used to live in the
        // user-selected trips dir (usually the SD card). It is the ONLY recovery
        // source for a trip if the process dies mid-drive, and the field
        // incident (log_DG87KWQX) showed that same card stalling and dropping
        // off the bus during a drive — a failed flush there is logged and
        // dropped (see flushBuffer). The journal now lives next to the trip H2
        // database on /data/local/tmp and is MOVED to the trips dir as
        // <dbId>.jsonl.gz when the row is finalized. Falls back to the trips
        // dir if the journal dir cannot be created.
        beginRecording(tripId, new File(resolveJournalDir(), tripId + ".jsonl.gz"), null);
    }

    /**
     * Re-attach to the journal of a trip that survived a daemon process
     * restart (same-session resume). Appends to {@code existingFile} — each
     * flush is an independent gzip member, so the reader
     * ({@link TelemetryStore#readFromFile}) sees one continuous timeline —
     * and seeds the live stats/scoring stream from the already-journaled
     * {@code history} (1 Hz on disk) so the trip-end distance fallback,
     * max/avg speed and scores cover the WHOLE trip, not just the part
     * recorded after the restart.
     */
    public void resumeRecording(long tripId, File existingFile, List<TelemetrySample> history) {
        beginRecording(tripId, existingFile, history);
    }

    /**
     * Directory the in-flight journal is written to: the internal journal dir
     * when available, else the trips dir (legacy behaviour).
     */
    public static File resolveJournalDir() {
        try {
            File journal = StorageManager.getInstance().getTripJournalDir();
            if (journal != null) return journal;
        } catch (Throwable ignored) {}
        return StorageManager.getInstance().getTripsWriteDir();
    }

    private void beginRecording(long tripId, File file, List<TelemetrySample> history) {
        if (recording) {
            logger.warn("Already recording trip " + currentTripId + ", ignoring start for " + tripId);
            return;
        }

        this.outputFile = file;
        this.maxSpeedKmh = 0;
        this.speedSumKmh = 0;
        this.speedSampleCount = 0;
        this.totalDistanceKm = 0;
        this.lastLat = 0;
        this.lastLon = 0;
        this.hasLastGps = false;
        this.lastSampleMs = 0;
        this.sampleCountTotal = 0;
        this.sampleCountWithGps = 0;
        this.speedChannelEverLive = false;
        this.diLink5FixId = 0L;
        this.diLink5FixRawKmh = -1;
        this.diLink5FixCheckedKmh = -1;
        this.diLink5RecentFixes.clear();
        this.diLink5SlowAnchored = false;
        synchronized (diLink5GapLock) {
            diLink5UnmeasuredGaps.clear();
            diLink5PendingGaps.clear();
            diLink5SeedGap = null;
        }
        this.diLink5KmSinceAnchor = 0;
        this.diLink5AnchorAtMs = 0L;
        this.diLink5AnchorUnmeasured = false;
        this.diLink5AnchorStill = false;
        this.diLink5AnchorParked = false;
        this.diLink5AnchorAccuracyM = 0f;
        this.diLink5AnchorHeld = false;
        this.diLink5PrevParked = false;
        this.diLink5FixDistanceKmh = -1;

        synchronized (bufferLock) {
            buffer.clear();
            estimatedBufferBytes = 0;
        }
        synchronized (allSamplesLock) {
            allSamples.clear();
        }

        if (history != null && !history.isEmpty()) {
            seedFromHistory(history);
        }

        // set after the reset and the seed: the live readout trusts the stats once this names its trip
        this.currentTripId = tripId;
        recording = true;

        // Mark this file as in-flight so StorageManager.ensureTripsSpace
        // won't unlink it during a limit-change cleanup mid-trip.
        try {
            StorageManager.getInstance().setActiveTripFile(outputFile);
        } catch (Exception e) {
            logger.warn("Failed to mark active trip file: " + e.getMessage());
        }

        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TripTelemetry-" + tripId);
            t.setDaemon(true);
            return t;
        });

        // 5Hz sampling
        sampleFuture = executor.scheduleAtFixedRate(
                this::sample, 0, SAMPLE_INTERVAL_MS, TimeUnit.MILLISECONDS);

        // Periodic flush every 60s, but with a SHORT first flush.
        //
        // The DB row for a trip is written only at trip end, so until the first
        // telemetry flush lands there is NO artifact on disk and a process death
        // loses the drive outright — next-boot recovery reconstructs rows from
        // these .jsonl.gz files, and cannot recover what was never written.
        // With initialDelay == FLUSH_INTERVAL_MS that blind window was a full
        // 60s at the start of EVERY trip; on units that restart often (watchdog
        // kills, settings-change restarts) a large share of drives died inside
        // it. FIRST_FLUSH_DELAY_MS makes the trip recoverable within seconds.
        // Steady-state cadence is unchanged, so there is no extra I/O on a
        // long drive beyond one early small write.
        flushFuture = executor.scheduleAtFixedRate(
                this::flushBuffer, FIRST_FLUSH_DELAY_MS,
                com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()
                        ? DILINK5_FLUSH_INTERVAL_MS : FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);

        logger.info((history != null && !history.isEmpty() ? "Resumed" : "Started")
                + " recording trip " + tripId + " → " + outputFile.getAbsolutePath()
                + (history != null && !history.isEmpty()
                        ? " (seeded from " + history.size() + " journaled samples, "
                            + String.format("%.2f", totalDistanceKm) + " km)"
                        : ""));
    }

    /**
     * Seed the live accumulators from already-journaled samples (same-session
     * resume). Distance is GPS-integrated with the recovery path's plausibility
     * gate — the journal has no dt-integrated CAN distance — and stats mirror
     * {@link #sample}'s rules as far as the 1 Hz file allows: DI5 flags its
     * rows without a vehicle speed, but elsewhere synthetic stale-zero samples
     * are indistinguishable on disk, so a resumed trip's average speed may read
     * marginally low. The hardware odometer delta still
     * overrides this distance at finalize whenever both edges are available.
     */
    private void seedFromHistory(List<TelemetrySample> history) {
        final boolean diLink5 = com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected();
        double prevLat = 0, prevLon = 0;
        long prevTs = 0;
        boolean havePrev = false;
        double dist = 0;
        for (TelemetrySample s : history) {
            sampleCountTotal++;
            boolean haveGps = s.lat != 0 || s.lon != 0;
            if (haveGps) {
                sampleCountWithGps++;
                if (havePrev) {
                    double seg = haversineKm(prevLat, prevLon, s.lat, s.lon);
                    long dtMs = s.timestampMs - prevTs;
                    double dtHr = dtMs > 0 ? dtMs / 3_600_000.0 : (1.0 / 3600.0);
                    // Same 250 km/h implied-speed gate as TripDatabase's
                    // reconstruction: rejects teleport glitches, keeps a long
                    // GPS-dropout leg.
                    if (seg / dtHr <= 250.0) dist += seg;
                }
                prevLat = s.lat; prevLon = s.lon; prevTs = s.timestampMs; havePrev = true;
            }
            // DI5 rows without a vehicle speed: a GPS speed only feeds the peak, as it did live; an
            // older DI5 journal's unreported zero is no reading at all.
            if (!s.hasVehicleSpeed() || (diLink5 && !s.hasDiLink5Speed())) {
                if (s.speedSource == TelemetrySample.SPEED_GPS && s.peakKmh() > maxSpeedKmh
                        && !s.isDiLink5Parked()) {
                    maxSpeedKmh = s.peakKmh();
                }
                continue;
            }
            if (s.speedKmh > 0) speedChannelEverLive = true;
            // DI5: a parked row's speed (frozen, or GPS noise) is not the trip's peak.
            if (s.speedKmh > maxSpeedKmh && !(diLink5 && s.isDiLink5Parked())) maxSpeedKmh = s.speedKmh;
            // DI5: a 1 Hz row stands for the five 5 Hz ticks live adds to the running mean.
            int weight = diLink5 ? 5 : 1;
            speedSumKmh += (long) s.speedKmh * weight;
            speedSampleCount += weight;
        }
        totalDistanceKm = dist;
        if (havePrev) {
            lastLat = prevLat;
            lastLon = prevLon;
            hasLastGps = true;
        }
        // DI5 has no odometer: measure the journal exactly as live does, then carry its open interval
        // on; this restart's gap stays unmeasured until the first live fix closes it.
        if (diLink5) {
            JournalDistance d = diLink5JournalDistance(history);
            totalDistanceKm = d.km;
            hasLastGps = d.anchorAtMs > 0L;
            lastLat = d.anchorLat;
            lastLon = d.anchorLon;
            diLink5AnchorAtMs = d.anchorAtMs;
            diLink5KmSinceAnchor = d.kmSinceAnchor;
            diLink5AnchorStill = d.anchorStill;
            diLink5AnchorParked = d.anchorParked;
            diLink5AnchorAccuracyM = d.anchorAccuracyM;
            diLink5AnchorHeld = d.anchorHeld;
            diLink5AnchorUnmeasured = true;
            synchronized (diLink5GapLock) {
                diLink5UnmeasuredGaps.addAll(d.unmeasuredGaps);
                diLink5PendingGaps.addAll(d.pendingGaps);
                diLink5SeedGap = new long[] {
                        history.get(history.size() - 1).timestampMs, System.currentTimeMillis()};
                diLink5PendingGaps.add(diLink5SeedGap);
            }
        }
        // lastSampleMs stays 0 so the first live tick integrates no dt across
        // the restart gap.
        synchronized (allSamplesLock) {
            if (diLink5) {
                // Journal rows are 1 Hz; the score engine counts 5 Hz samples, as live ticks are.
                List<TelemetrySample> eligible = new ArrayList<>();
                for (TelemetrySample s : history) {
                    if (s.isDiLink5ScoringEligible()) eligible.add(s);
                }
                allSamples.addAll(TripScoreEngine.diLink5At5Hz(eligible));
            } else {
                for (TelemetrySample s : history) {
                    if (s.isScoringEligible()) allSamples.add(s);
                }
            }
        }
    }

    /**
     * Stop recording. Flushes remaining buffer, closes file.
     * @return the telemetry file path, or null if not recording
     */
    public String stopRecording() {
        return stopRecording(false);
    }

    /**
     * @param keepActiveMarker true to leave the in-flight file marker SET
     *   after stopping. The trip-end flow needs continuous protection: rows
     *   are finalized (or inserted) only AFTER this returns, and a marker
     *   cleared here — even for the microseconds until the caller re-asserts
     *   it — is a window in which a concurrent recovery scan (startup thread
     *   or POST /api/trips/recover) sees a half-open row plus an unprotected
     *   file and may finalize, duplicate, or (below-floor branch) DELETE it.
     *   The caller owns clearing the marker once the row is complete. The
     *   discard path keeps the default (clear immediately: the file is about
     *   to be deleted, there is nothing to protect).
     */
    public String stopRecording(boolean keepActiveMarker) {
        if (!recording) {
            logger.warn("Not recording, ignoring stop");
            return null;
        }

        recording = false;
        logger.info("Stopping recording for trip " + currentTripId);

        // Cancel scheduled tasks
        if (sampleFuture != null) sampleFuture.cancel(false);
        if (flushFuture != null) flushFuture.cancel(false);

        // Final flush of remaining buffer
        flushBuffer();

        // Shutdown executor
        if (executor != null) {
            executor.shutdown();
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            executor = null;
        }

        // Notify StorageManager. Default path clears the in-flight marker
        // first so the post-save cleanup it triggers can reap this file if a
        // downward limit change made it the oldest over-limit file. The
        // trip-end flow passes keepActiveMarker=true instead: the marker must
        // stay up CONTINUOUSLY until the DB row is finalized and the file
        // renamed (the caller clears it in its finally) — the cleanup simply
        // can't reap this one file until then, which is the point.
        try {
            if (!keepActiveMarker) {
                StorageManager.getInstance().setActiveTripFile(null);
            }
            StorageManager.getInstance().onTripFileSaved();
        } catch (Exception e) {
            logger.warn("Failed to notify StorageManager: " + e.getMessage());
        }

        String path = outputFile != null ? outputFile.getAbsolutePath() : null;
        long fileBytes = (outputFile != null && outputFile.exists()) ? outputFile.length() : -1;
        long gpsPct = sampleCountTotal == 0
                ? 0
                : Math.round(100.0 * sampleCountWithGps / sampleCountTotal);
        logger.info("Stopped recording trip " + currentTripId +
                " (samples=" + speedSampleCount +
                ", maxSpeed=" + maxSpeedKmh +
                ", gps=" + sampleCountWithGps + "/" + sampleCountTotal + " (" + gpsPct + "%)" +
                ", fileBytes=" + (fileBytes < 0 ? "missing" : Long.toString(fileBytes)) +
                ", file=" + path + ")");

        return path;
    }

    /**
     * Returns the file path for a given trip ID.
     */
    public String getTelemetryFilePath(long tripId) {
        return new File(StorageManager.getInstance().getTripsWriteDir(),
                tripId + ".jsonl.gz").getAbsolutePath();
    }

    /**
     * Returns all captured 5Hz samples for score computation (before downsampling).
     */
    public List<TelemetrySample> getSamplesForScoring() {
        synchronized (allSamplesLock) {
            return new ArrayList<>(allSamples);
        }
    }

    /**
     * Key (start time) of the trip the stats belong to. They outlive a stop until the next start,
     * so at a trip's start, before recording begins, they are still the last trip's. -1 before any.
     */
    public long getStatsTripKey() {
        return currentTripId;
    }

    /**
     * Get the maximum speed recorded during this trip.
     */
    public int getMaxSpeedKmh() {
        return maxSpeedKmh;
    }

    /**
     * Get the average speed recorded during this trip.
     */
    public double getAvgSpeedKmh() {
        return speedSampleCount > 0 ? (double) speedSumKmh / speedSampleCount : 0.0;
    }

    /** DI5: time within [fromMs, toMs] of a restart gap no live fix measured (its distance is missing). */
    public long getUnmeasuredGapMs(long fromMs, long toMs) {
        synchronized (diLink5GapLock) {
            return gapOverlapMs(diLink5UnmeasuredGaps, fromMs, toMs)
                    + gapOverlapMs(diLink5PendingGaps, fromMs, toMs);
        }
    }

    /**
     * Get the total live distance recorded during this trip (km).
     * Fused: CAN/wheel-speed integration primary, accuracy-gated GPS fallback.
     * Used as the live readout and as the finalize-time fallback when the
     * hardware odometer delta is unavailable.
     */
    public double getTotalDistanceKm() {
        return totalDistanceKm;
    }

    /**
     * Haversine formula: distance between two GPS coordinates in km.
     */
    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371.0; // Earth radius in km
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                   Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                   Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    private static final class RecentFix {
        final long id;
        final double lat, lon;

        RecentFix(long id, double lat, double lon) {
            this.id = id;
            this.lat = lat;
            this.lon = lon;
        }
    }

    /** A journal's DI5 distance; its restart gaps no fix measured; and the interval still open at its
     *  end: anchor fix, when, km integrated since, and the restart gaps inside it. */
    static final class JournalDistance {
        double km;
        double anchorLat, anchorLon;
        long anchorAtMs;
        boolean anchorStill;
        boolean anchorParked;
        float anchorAccuracyM;
        boolean anchorHeld;
        double kmSinceAnchor;
        final List<long[]> unmeasuredGaps = new ArrayList<>();
        final List<long[]> pendingGaps = new ArrayList<>();

        /** Time within [fromMs, toMs] of restart gaps no fix measured, as the live trip leaves out of its average. */
        long unmeasuredMs(long fromMs, long toMs) {
            return gapOverlapMs(unmeasuredGaps, fromMs, toMs) + gapOverlapMs(pendingGaps, fromMs, toMs);
        }
    }

    static long gapOverlapMs(List<long[]> gaps, long fromMs, long toMs) {
        long total = 0L;
        long until = toMs > 0 ? toMs : Long.MAX_VALUE;
        for (long[] gap : gaps) {
            long start = Math.max(gap[0], fromMs);
            long end = Math.min(gap[1], until);
            if (end > start) total += end - start;
        }
        return total;
    }

    /**
     * DI5: a journal measured as live measures it. Rows' speeds (vehicle or checked GPS) are
     * integrated over steps of up to 2 s, nothing in P. An interval between live fixes that had a
     * row without a speed, a longer step, or a gap over 5 s (a restart, whatever the gear) is
     * measured by position when the next live fix lands, less what was integrated, under a 250 km/h
     * gate; standing at both ends, a move within the two fixes' accuracies is their jitter, and a gap
     * waits (up to 10 s) for a row where the car's state is known while its move could be jitter.
     */
    private static boolean diLink5Parked(TelemetrySample s) {
        return s.isDiLink5Parked();
    }

    private static boolean diLink5Still(TelemetrySample s) {
        return diLink5Parked(s) || s.isDiLink5Stopped();
    }

    /** Standing at both ends: a move within the jitter bound is the fixes' jitter, not a drive. */
    private static boolean isStandingJitter(boolean fromStill, boolean toStill, boolean bothParked,
                                            double segKm, float fromAccuracyM, float toAccuracyM) {
        return fromStill && toStill && segKm < jitterBoundKm(bothParked, fromAccuracyM, toAccuracyM);
    }

    /**
     * Two fixes' combined accuracy in km (an unreported one counts as the 50 m gate). Parked in P at
     * both ends, any move within two gates is jitter: multipath in a car park is far likelier than a
     * re-park during a gap.
     */
    private static double jitterBoundKm(boolean bothParked, float fromAccuracyM, float toAccuracyM) {
        float a = fromAccuracyM > 0 ? fromAccuracyM : GPS_ACCURACY_GATE_M;
        float b = toAccuracyM > 0 ? toAccuracyM : GPS_ACCURACY_GATE_M;
        double km = (a + b) / 1000.0;
        return bothParked ? Math.max(km, 2 * GPS_ACCURACY_GATE_M / 1000.0) : km;
    }

    /** A fix's accuracy as the journal keeps it: whole metres, at least 1 (0 = unreported). */
    private static float wholeMetres(float accuracyM) {
        return accuracyM > 0 ? Math.max(1f, (float) Math.ceil(accuracyM)) : 0f;
    }

    static JournalDistance diLink5JournalDistance(List<TelemetrySample> rows) {
        JournalDistance d = new JournalDistance();
        boolean unmeasured = false;
        TelemetrySample prev = null;
        for (TelemetrySample s : rows) {
            if (prev != null) {
                long dtMs = s.timestampMs - prev.timestampMs;
                if (dtMs > 5_000L) {
                    unmeasured = true;
                    d.pendingGaps.add(new long[] {prev.timestampMs, s.timestampMs});
                } else if (dtMs > 0) {
                    if (!s.hasDiLink5Speed() || !prev.hasDiLink5Speed()) {
                        // No speed, in P too (the gear is unchecked): the next live fix measures the move.
                        unmeasured = true;
                    } else if (!diLink5Parked(s) && !diLink5Parked(prev)) {
                        double km = (prev.speedKmh + s.speedKmh) / 2.0
                                * (Math.min(dtMs, MAX_INTEGRATION_DT_MS) / 3_600_000.0);
                        d.km += km;
                        d.kmSinceAnchor += km;
                        if (dtMs > MAX_INTEGRATION_DT_MS) unmeasured = true;
                    }
                }
            }
            // A slow GPS stretch's distance, landed live at this row.
            if (s.distanceLandedM > 0) {
                d.km += s.distanceLandedM / 1000.0;
                d.kmSinceAnchor += s.distanceLandedM / 1000.0;
            }
            boolean liveFix = (s.lat != 0 || s.lon != 0) && !s.gpsNotLive;
            boolean moved = d.anchorAtMs == 0L || s.lat != d.anchorLat || s.lon != d.anchorLon;
            long sinceGapMs = d.pendingGaps.isEmpty() ? -1L
                    : s.timestampMs - d.pendingGaps.get(d.pendingGaps.size() - 1)[1];
            double boundKm = jitterBoundKm(d.anchorParked && diLink5Parked(s), d.anchorAccuracyM,
                    s.horizontalAccuracyM);
            boolean hold = unmeasured && !s.hasDiLink5Speed() && d.anchorAtMs > 0L
                    && haversineKm(d.anchorLat, d.anchorLon, s.lat, s.lon) < 2 * boundKm;
            if (hold && liveFix) {
                d.anchorHeld = true;
                if (sinceGapMs >= 10_000L) d.pendingGaps.clear();
            }
            if (liveFix && !moved) {
                d.anchorStill |= diLink5Still(s);
                d.anchorParked |= diLink5Parked(s);
                // As live: known to stand where the interval began, it had no move.
                if (unmeasured && s.hasDiLink5Speed() && diLink5Still(s)) {
                    d.pendingGaps.clear();
                    unmeasured = false;
                    d.anchorHeld = false;
                    d.kmSinceAnchor = 0;
                    d.anchorAtMs = s.timestampMs;
                }
            }
            if (liveFix && moved && !hold) {
                double hr = (s.timestampMs - d.anchorAtMs) / 3_600_000.0;
                boolean measured = !unmeasured;
                if (unmeasured && d.anchorAtMs > 0L && hr > 0) {
                    double seg = haversineKm(d.anchorLat, d.anchorLon, s.lat, s.lon);
                    if (seg / hr <= 250.0) {
                        if (seg - d.kmSinceAnchor >= MIN_GPS_SEGMENT_KM
                                && !(d.anchorHeld && diLink5Still(s)
                                        && seg < (d.anchorParked && diLink5Parked(s) ? 1 : 2) * boundKm)
                                && !isStandingJitter(d.anchorStill, diLink5Still(s),
                                        d.anchorParked && diLink5Parked(s), seg,
                                        d.anchorAccuracyM, s.horizontalAccuracyM)) {
                            d.km += seg - d.kmSinceAnchor;
                        }
                        measured = true;
                    }
                }
                if (!measured) d.unmeasuredGaps.addAll(d.pendingGaps);
                d.pendingGaps.clear();
                unmeasured = false;
                d.anchorHeld = false;
                d.kmSinceAnchor = 0;
                d.anchorLat = s.lat;
                d.anchorLon = s.lon;
                d.anchorAtMs = s.timestampMs;
                d.anchorStill = diLink5Still(s);
                d.anchorParked = diLink5Parked(s);
                d.anchorAccuracyM = s.horizontalAccuracyM;
            }
            prev = s;
        }
        return d;
    }

    /**
     * DI5: speed of the current GPS fix in km/h, or -1. A live fix counts only next to the fix
     * before it (at most 5 s older, as long as a fix is trusted): the lower of the two is taken, so
     * a one-fix spike never lands, and a 0 must agree with the positions, as the sidecar also
     * reports 0 for a fix without speed.
     */
    private int checkedDiLink5GpsSpeedKmh(GpsMonitor.GpsFixSnapshot fix, long nowEpochMs) {
        diLink5LandKm = 0;
        long fixId = fix.fixElapsedMs > 0L ? fix.fixElapsedMs : fix.lastUpdate;
        // GpsMonitor keeps the last fix when GPS goes quiet: its speed stands for 2.5 s, not the 5 s a
        // position stays live, so braking as GPS drops is measured by position instead.
        if (fixId == diLink5FixId) {
            return trustworthyGpsSpeedKmh(fix, nowEpochMs) >= 0 && fixAgeMs(fix, nowEpochMs) <= 2_500L
                    ? diLink5FixCheckedKmh : -1;
        }
        int raw = trustworthyGpsSpeedKmh(fix, nowEpochMs);
        if (raw > 0 && raw < DILINK5_GPS_NOISE_KMH) raw = 0;
        long dtMs = fixId - diLink5FixId;
        int checked = -1;
        int forDistance = -1;
        if (raw >= 0 && diLink5FixRawKmh >= 0 && diLink5FixId > 0L && dtMs > 0L && dtMs <= 5_000L) {
            // Positions over the longest span back to a recent live fix: jitter shrinks as it grows.
            RecentFix from = null;
            for (RecentFix r : diLink5RecentFixes) {
                if (r.id < fixId && fixId - r.id <= 5_000L) {
                    from = r;
                    break;
                }
            }
            long spanMs = from != null ? fixId - from.id : dtMs;
            double impliedKmh = (from != null
                    ? haversineKm(from.lat, from.lon, fix.latitude, fix.longitude)
                    : haversineKm(diLink5FixLat, diLink5FixLon, fix.latitude, fix.longitude))
                    / (spanMs / 3_600_000.0);
            if (raw > 0 && diLink5FixRawKmh > 0) {
                checked = Math.min(raw, diLink5FixRawKmh);
                // Distance takes this fix's own speed unless it changed faster than a car can (20 km/h/s).
                forDistance = Math.abs(raw - diLink5FixRawKmh) <= Math.max(10.0, 20.0 * dtMs / 1000.0)
                        ? raw : checked;
                // Multipath reports motion at a standstill: positions over 3 s or more must show half of
                // it (jitter swamps a shorter span). Under 10 km/h what fails is a stop.
                if (impliedKmh < checked / 2.0 || (checked >= 10 && spanMs < 3_000L)) {
                    if (checked >= 10) {
                        checked = -1;
                        forDistance = -1;
                    } else if (spanMs >= 3_000L) {
                        checked = 0;
                        forDistance = 0;
                    }
                }
            } else if (impliedKmh < (spanMs >= 3_000L ? 10.0 : DILINK5_GPS_STOP_MAX_KMH)) {
                checked = 0;
                forDistance = 0;
            }
        }
        while (!diLink5RecentFixes.isEmpty() && diLink5RecentFixes.peekLast().id >= fixId) {
            diLink5RecentFixes.pollLast();
        }
        while (!diLink5RecentFixes.isEmpty() && fixId - diLink5RecentFixes.peekFirst().id > 5_000L) {
            diLink5RecentFixes.pollFirst();
        }
        if (raw >= 0) diLink5RecentFixes.addLast(new RecentFix(fixId, fix.latitude, fix.longitude));
        // Under 10 km/h Doppler and fix-to-fix moves are noise-level, so distance waits until the car is
        // clear of jitter from where the slow stretch began: 4x the fixes' accuracy (40 m at least) by
        // positions alone, or 2x (20 m) with the stretch's Doppler backing half of it. It then lands at
        // once; a stretch that ends at a checked 10+ lands what it moved so far.
        float accuracyM = wholeMetres(fix.accuracy);
        if (diLink5SlowAnchored && checked < 10 && raw >= DILINK5_GPS_NOISE_KMH && dtMs > 0L && dtMs <= 5_000L) {
            diLink5SlowDopplerKm += raw * (dtMs / 3_600_000.0);
        }
        if (checked >= 0 && checked < 10) {
            if (diLink5SlowAnchored) {
                double movedKm = haversineKm(diLink5SlowLat, diLink5SlowLon, fix.latitude, fix.longitude);
                double boundKm = jitterBoundKm(false, diLink5SlowAccuracyM, accuracyM);
                if (movedKm >= Math.max(4 * boundKm, 0.040)
                        || (movedKm >= Math.max(2 * boundKm, 0.020) && diLink5SlowDopplerKm >= movedKm / 2.0)) {
                    diLink5LandKm = Math.max(0, movedKm - diLink5SlowCountedKm);
                    diLink5SlowAnchored = false;
                }
            }
            if (!diLink5SlowAnchored) {
                diLink5SlowAnchored = true;
                diLink5SlowLat = fix.latitude;
                diLink5SlowLon = fix.longitude;
                diLink5SlowAccuracyM = accuracyM;
                diLink5SlowDopplerKm = 0;
                diLink5SlowCountedKm = 0;
            }
            forDistance = 0;
        } else if (checked >= 10) {
            if (diLink5SlowAnchored) {
                diLink5LandKm = Math.max(0, haversineKm(diLink5SlowLat, diLink5SlowLon,
                        fix.latitude, fix.longitude) - diLink5SlowCountedKm);
            }
            diLink5SlowAnchored = false;
        }
        diLink5FixId = fixId;
        diLink5FixRawKmh = raw;
        diLink5FixLat = fix.latitude;
        diLink5FixLon = fix.longitude;
        diLink5FixCheckedKmh = checked;
        diLink5FixDistanceKmh = forDistance;
        return checked;
    }

    /** DI5: live GPS speed in km/h, or -1; the same accuracy gate as GPS distance. */
    private static int trustworthyGpsSpeedKmh(GpsMonitor.GpsFixSnapshot fix, long nowEpochMs) {
        if (!isLiveFix(fix, nowEpochMs, GPS_ACCURACY_GATE_M)
                || !(fix.speed >= 0f) || Float.isInfinite(fix.speed)) {
            return -1;
        }
        double kmh = fix.speed * 3.6;
        return kmh <= 300.0 ? (int) Math.round(kmh) : -1;
    }

    /** How old a fix is, or -1 when it carries no time. */
    private static long fixAgeMs(GpsMonitor.GpsFixSnapshot fix, long nowEpochMs) {
        return fix.fixElapsedMs > 0L
                ? android.os.SystemClock.elapsedRealtime() - fix.fixElapsedMs
                : fix.lastUpdate > 0L ? nowEpochMs - fix.lastUpdate : -1L;
    }

    /** A fix from the live sidecar, not the disk cache, at most 5 s old and within the accuracy gate. */
    private static boolean isLiveFix(GpsMonitor.GpsFixSnapshot fix, long nowEpochMs, float maxAccuracyM) {
        if (fix == null || fix.loadedFromCache
                || !(fix.accuracy > 0f) || fix.accuracy > maxAccuracyM) {
            return false;
        }
        long ageMs = fix.fixElapsedMs > 0L
                ? android.os.SystemClock.elapsedRealtime() - fix.fixElapsedMs
                : fix.lastUpdate > 0L ? nowEpochMs - fix.lastUpdate : -1L;
        return ageMs >= 0L && ageMs <= 5_000L;
    }

    // ==================== PRIVATE: Sampling ====================

    /**
     * Called at 5Hz. Reads from existing singleton monitors and buffers a sample.
     */
    private void sample() {
        if (!recording) return;

        try {
            long now = System.currentTimeMillis();

            // Read speed/accel/brake/brakePedalPressed from TelemetryDataCollector
            TelemetryDataCollector collector = telemetryDataCollector;
            TelemetrySnapshot snapshot = collector != null ? collector.getLatestSnapshot() : null;
            int speedKmh = 0;
            int accelPedal = 0;
            int brakePedal = 0;
            boolean brakePedalPressed = false;
            // True when we couldn't read a fresh dynamics snapshot this tick.
            // Such a sample carries synthetic zeros — fine to persist in the raw
            // .jsonl.gz for timeline continuity, but it must NOT feed the scoring
            // buffer or the speed stats, where a fabricated 0 km/h would
            // manufacture a phantom stop / launch / coast and dilute the jerk and
            // consistency windows.
            boolean dynamicsStale = true;
            boolean diLink5PedalsUnknown = false;
            final boolean diLink5 = com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected();
            if (snapshot != null) {
                // Check if snapshot is stale (older than 2 seconds means poller may have died)
                long snapshotAge = now - snapshot.timestampMs;
                if (snapshotAge < 2000) {
                    boolean pedalsValid = snapshot.accelPedalValid && snapshot.brakePedalValid;
                    // DI5: a snapshot without a valid speed is no reading, nor are pedals it marks invalid.
                    if (!diLink5 || snapshot.speedValid) {
                        speedKmh = snapshot.speedKmh;
                        accelPedal = snapshot.accelPedalPercent;
                        brakePedal = snapshot.brakePedalPercent;
                        brakePedalPressed = snapshot.brakePedalPressed;
                        dynamicsStale = false;
                        diLink5PedalsUnknown = diLink5 && !pedalsValid;
                    }
                } else {
                    // Stale snapshot — record zeros instead of frozen values
                    if (snapshotAge < 5000) {
                        // Only log once per staleness episode (within first 5s)
                        logger.warn("Telemetry snapshot stale (" + snapshotAge + "ms old), recording zeros");
                    }
                }
            }

            // Read GPS from GpsMonitor — ONE immutable snapshot so all fields
            // (position, altitude, both accuracies, source flag) belong to the
            // SAME fix rather than mixing two IPC publications.
            GpsMonitor gps = GpsMonitor.getInstance();
            GpsMonitor.GpsFixSnapshot fix = gps.getFixSnapshot();
            double lat = fix.latitude;
            double lon = fix.longitude;
            double altitude = fix.altitude;
            float gpsAccuracy = fix.accuracy;

            // Read gear from GearMonitor
            int gearMode = GearMonitor.getInstance().getCurrentGear();

            // DI5 without a vehicle speed: the timeline and the trip's peak take live GPS speed,
            // flagged so stats, scoring and a resume never count it as a reading.
            int recordedSpeedKmh = speedKmh;
            int speedSource = TelemetrySample.SPEED_VEHICLE;
            int peakSpeedKmh = -1;
            int diLink5GpsKmh = -1;
            if (diLink5) {
                diLink5GpsKmh = checkedDiLink5GpsSpeedKmh(fix, now);
                // A slow GPS stretch only spans ticks without a vehicle speed, or it would re-count CAN km;
                // one the returning speed shows the car moving through was motion, not jitter.
                if (!dynamicsStale) {
                    if (diLink5SlowAnchored && speedKmh > 0 && isLiveFix(fix, now, GPS_ACCURACY_GATE_M)) {
                        diLink5LandKm += Math.max(0, haversineKm(diLink5SlowLat, diLink5SlowLon, lat, lon)
                                - diLink5SlowCountedKm);
                    }
                    diLink5SlowAnchored = false;
                }
                if (dynamicsStale) {
                    // The row keeps the speed distance integrates, and the peak's when it differs.
                    recordedSpeedKmh = diLink5GpsKmh >= 0 ? diLink5FixDistanceKmh : 0;
                    if (diLink5GpsKmh >= 0 && diLink5GpsKmh != recordedSpeedKmh) peakSpeedKmh = diLink5GpsKmh;
                    speedSource = diLink5GpsKmh >= 0 ? TelemetrySample.SPEED_GPS : TelemetrySample.SPEED_NONE;
                }
            }

            // DI5: in P the car is parked unless a speed shows it moving: any valid vehicle speed (the
            // collector quarantines one frozen in P), or 10+ km/h of checked GPS. The gear signal is not
            // status-checked, so an error can read as P.
            boolean diLink5Moving = !dynamicsStale ? speedKmh > 0 : diLink5GpsKmh >= 10;
            boolean diLink5Parked = diLink5 && gearMode == 1 && !diLink5Moving;
            // DI5: a step is parked if either end is, as the journal reads its rows.
            boolean diLink5StepParked = diLink5Parked || (diLink5 && diLink5PrevParked);
            // A slow stretch's landed distance counts unless the car is parked now (moving at the landing
            // fix, it really moved, whatever the tick before read); the row carries it.
            float landedM = 0f;
            if (diLink5LandKm > 0 && !diLink5Parked) {
                totalDistanceKm += diLink5LandKm;
                diLink5KmSinceAnchor += diLink5LandKm;
                landedM = (float) (diLink5LandKm * 1000.0);
            }
            diLink5LandKm = 0;

            TelemetrySample sample = new TelemetrySample(
                    now, recordedSpeedKmh, accelPedal, brakePedal,
                    brakePedalPressed, gearMode, lat, lon, altitude,
                    fix.verticalAccuracy, fix.altitudeIsMsl,
                    speedSource, diLink5PedalsUnknown,
                    diLink5 && (lat != 0 || lon != 0) && !isLiveFix(fix, now, GPS_ACCURACY_GATE_M),
                    diLink5 && !dynamicsStale && speedKmh == 0, peakSpeedKmh,
                    diLink5 && (lat != 0 || lon != 0) ? wholeMetres(fix.accuracy) : 0f, landedM,
                    fix.fixTimeUtc);

            // ── Distance fusion (CAN-speed primary, accuracy-gated GPS fallback) ──
            // dt since the previous sample, clamped so a scheduler stall or a
            // process resume can't integrate one tick into a huge jump.
            long dtMs = lastSampleMs > 0 ? (now - lastSampleMs) : 0;
            if (dtMs < 0) dtMs = 0;
            // DI5: a stall over 5 s is a gap as a restart's is, the same as the journal reads it:
            // nothing integrated across it, its move measured by position at the next live fix.
            boolean diLink5StallGap = diLink5 && dtMs > 5_000L;
            if (diLink5StallGap) {
                synchronized (diLink5GapLock) {
                    diLink5PendingGaps.add(new long[] {lastSampleMs, now});
                }
                dtMs = 0;
            }
            boolean dtCapped = dtMs > MAX_INTEGRATION_DT_MS;
            if (dtCapped) dtMs = MAX_INTEGRATION_DT_MS;

            sampleCountTotal++;
            boolean haveGps = lat != 0 && lon != 0;
            if (haveGps) sampleCountWithGps++;

            // Does the CAN/wheel speed channel look USABLE, not merely fresh?
            //
            // `dynamicsStale` only tests snapshot AGE. On a trim where the
            // BYDAutoSpeedDevice bind failed, TelemetryDataCollector still
            // publishes a heartbeat snapshot every 750ms with speedKmh left at its
            // 0 initialiser — so the snapshot is FRESH and the primary branch adds
            // `0 * dt` forever. Because the GPS haversine used to be an `else if`,
            // it was then structurally unreachable, distanceKm stayed 0.0, every
            // trip failed MIN_TRIP_DISTANCE_KM (0.2), and handleTripDiscarded
            // DELETED the telemetry file — so nothing survived for recovery either.
            // That is a permanent "no trips ever recorded" on such a unit, and it
            // is independent of the enable flag.
            //
            // Fix: treat a fresh-but-flat speed channel as unusable for THIS tick
            // and let GPS carry the distance.
            //
            // CRITICAL: the test is "has this channel EVER produced a non-zero
            // reading in this trip", NOT "is it non-zero right now". `speedKmh` is
            // an int, so a genuinely stopped car also reads 0 — gating on the
            // instantaneous value would route EVERY standstill tick into the GPS
            // haversine on every unit, healthy or not. Parked jitter (~3 m/fix,
            // over the 2 m MIN_GPS_SEGMENT_KM floor, with a loose 50 m accuracy
            // gate) then integrates at ~0.18 km/min, so ~2 minutes of idling in
            // gear — or the 120s park debounce alone, which is inside the
            // recording window — would clear MIN_TRIP_DISTANCE_KM and fabricate a
            // phantom trip with a garbage score, folded into the rollups. The old
            // `!dynamicsStale` test prevented that by accident; this preserves the
            // protection deliberately while still rescuing a dead speed channel.
            if (!dynamicsStale && speedKmh > 0) speedChannelEverLive = true;
            // DI5 reports validity, so a valid 0 is a reading from the first tick (a stuck 0 is quarantined).
            boolean speedUsable = !dynamicsStale && (speedChannelEverLive || diLink5);
            // Known to be standing: parked, a valid vehicle 0, a GPS 0 the positions confirmed.
            boolean diLink5Still = diLink5Parked || (!dynamicsStale ? speedKmh == 0 : diLink5GpsKmh == 0);
            // A parked tick's speed (frozen, or GPS noise) is not the trip's peak.
            if (diLink5 && dynamicsStale && diLink5GpsKmh > maxSpeedKmh && !diLink5Parked) {
                maxSpeedKmh = diLink5GpsKmh;
            }
            boolean diLink5GpsSpeedUsable = diLink5 && dynamicsStale && diLink5GpsKmh >= 0;
            if (speedUsable && dtMs > 0 && !diLink5StepParked) {
                // PRIMARY: integrate wheel/CAN speed. Reads ~0 km/h when stopped,
                // so idle dwell adds nothing and GPS jitter is irrelevant. Robust
                // through tunnels/garages where GPS drops out entirely.
                //   km = (km/h) × (hours)
                double km = speedKmh * (dtMs / 3_600_000.0);
                totalDistanceKm += km;
                if (diLink5) diLink5KmSinceAnchor += km;
            } else if (diLink5GpsSpeedUsable) {
                // DI5 without a vehicle speed: integrate the checked GPS speed, which, unlike
                // fix-to-fix positions, reads 0 at a stop instead of accreting jitter.
                if (dtMs > 0 && !diLink5StepParked) {
                    double km = diLink5FixDistanceKmh * (dtMs / 3_600_000.0);
                    totalDistanceKm += km;
                    diLink5KmSinceAnchor += km;
                }
            } else if (diLink5) {
                // DI5 with no speed at all: the move to the next live fix is measured when it lands.
                // In P too: the gear is unchecked, so the next live fix measures the move (parked jitter
                // stays under the parked bound).
                if (!speedUsable && dtMs > 0) diLink5AnchorUnmeasured = true;
            } else if (haveGps && hasLastGps && lastLat != 0 && lastLon != 0) {
                // FALLBACK: no fresh dynamics this tick (speed unknown). Use GPS
                // haversine, but only when the fix is trustworthy and the segment
                // is above the stationary-jitter floor.
                // Require a positive, trustworthy accuracy. A live fix always
                // carries a real horizontal accuracy (the sidecar populates it,
                // same field RoadSense gates on); accuracy <= 0 means "unreported"
                // — typically a cache-loaded fix with no live update yet — and is
                // rejected rather than trusted as a perfect 0 m fix.
                boolean accuracyOk = gpsAccuracy > 0 && gpsAccuracy <= GPS_ACCURACY_GATE_M;
                double dist = haversineKm(lastLat, lastLon, lat, lon);
                // Reject impossible jumps (>500m/tick) and sub-jitter wiggle.
                //
                // ALSO reject when a speed channel we TRUST says we are stopped.
                //
                // Both halves matter. `speedChannelEverLive` means the channel has
                // produced a non-zero reading at some point this trip, so a 0 now
                // genuinely means stopped — reject the jitter. Without that half we
                // would reject on a DEAD channel too (which also reads a fresh 0
                // while the car is moving), re-breaking the very defect this fix
                // exists for. Without the freshness half, a stale snapshot's
                // synthetic 0 would suppress real GPS distance.
                //
                // Net effect per case:
                //   healthy + moving      -> primary branch, never here
                //   healthy + stopped     -> here, rejected (no phantom distance)
                //   dead channel + moving -> here, ACCEPTED (GPS carries the trip)
                //   stale snapshot        -> here, accepted (pre-existing behaviour)
                boolean trustedStop = !dynamicsStale && speedChannelEverLive && speedKmh == 0;
                if (accuracyOk && !trustedStop
                        && dist >= MIN_GPS_SEGMENT_KM && dist < 0.5) {
                    totalDistanceKm += dist;
                }
            }

            // DI5: a stall past the cap leaves the rest of its move to the interval's positions.
            if (diLink5 && ((dtCapped && !diLink5StepParked) || diLink5StallGap)) diLink5AnchorUnmeasured = true;

            // Always advance the GPS anchor on a valid fix so the next fallback
            // segment measures from here, even on ticks where CAN speed drove the
            // accumulation (keeps the fallback honest if dynamics later go stale).
            if (diLink5) {
                // DI5 anchors on live fixes only. A new one closes the interval since the last: what the
                // speed integration did not cover is measured by position, under the seed's 250 km/h gate.
                // A gap (restart or stall) closes only at a fix where the car's state is known, so jitter
                // at a standstill is told from a drive.
                // A gap waits for a known state only while its move could be jitter, for 10 s at most.
                boolean liveFix = haveGps && isLiveFix(fix, now, GPS_ACCURACY_GATE_M);
                double boundKm = jitterBoundKm(diLink5AnchorParked && diLink5Parked, diLink5AnchorAccuracyM,
                        wholeMetres(fix.accuracy));
                boolean holdAnchor;
                synchronized (diLink5GapLock) {
                    if (diLink5SeedGap != null) {
                        diLink5SeedGap[1] = now;
                        diLink5SeedGap = null;
                    }
                    long sinceGapMs = diLink5PendingGaps.isEmpty() ? -1L
                            : now - diLink5PendingGaps.get(diLink5PendingGaps.size() - 1)[1];
                    // An unmeasured move without any speed to explain it (in P too: the gear is unchecked)
                    // waits at the anchor while within twice the fixes' jitter: jitter never adds up, a slow
                    // move lands once clear of it, and a restart gap held 10 s that way is measured.
                    holdAnchor = diLink5AnchorUnmeasured && dynamicsStale && diLink5GpsKmh < 0 && hasLastGps
                            && haversineKm(lastLat, lastLon, lat, lon) < 2 * boundKm;
                    if (holdAnchor && liveFix) {
                        diLink5AnchorHeld = true;
                        if (sinceGapMs >= 10_000L) diLink5PendingGaps.clear();
                    }
                }
                if (liveFix && !holdAnchor) {
                    if (!hasLastGps || lat != lastLat || lon != lastLon) {
                        double hr = (now - diLink5AnchorAtMs) / 3_600_000.0;
                        boolean measured = !diLink5AnchorUnmeasured;
                        synchronized (diLink5GapLock) {
                            if (diLink5AnchorUnmeasured && hasLastGps && hr > 0) {
                                double seg = haversineKm(lastLat, lastLon, lat, lon);
                                if (seg / hr <= 250.0) {
                                    // Held for jitter and now known to stand: still jitter (parked at both
                                    // ends, the parked bound already allows for it).
                                    boolean bothParked = diLink5AnchorParked && diLink5Parked;
                                    if (seg - diLink5KmSinceAnchor >= MIN_GPS_SEGMENT_KM
                                            && !(diLink5AnchorHeld && diLink5Still
                                                    && seg < (bothParked ? 1 : 2) * boundKm)
                                            && !isStandingJitter(diLink5AnchorStill, diLink5Still,
                                                    diLink5AnchorParked && diLink5Parked, seg,
                                                    diLink5AnchorAccuracyM, wholeMetres(fix.accuracy))) {
                                        totalDistanceKm += seg - diLink5KmSinceAnchor;
                                        // An open slow stretch's later landing leaves this part out (not
                                        // one opened at this fix: the move before it is not its own).
                                        if (diLink5SlowAnchored && (diLink5SlowLat != lat || diLink5SlowLon != lon)) {
                                            diLink5SlowCountedKm += seg - diLink5KmSinceAnchor;
                                        }
                                    }
                                    measured = true;
                                }
                            }
                            // A restart gap's time leaves the average only if no fix could measure it.
                            if (!measured) diLink5UnmeasuredGaps.addAll(diLink5PendingGaps);
                            diLink5PendingGaps.clear();
                        }
                        diLink5KmSinceAnchor = 0;
                        diLink5AnchorUnmeasured = false;
                        diLink5AnchorHeld = false;
                        diLink5AnchorAtMs = now;
                        diLink5AnchorStill = diLink5Still;
                        diLink5AnchorParked = diLink5Parked;
                        diLink5AnchorAccuracyM = wholeMetres(fix.accuracy);
                    } else {
                        // Standing (or parked) at any moment at the anchor's position makes it such a point.
                        diLink5AnchorStill |= diLink5Still;
                        diLink5AnchorParked |= diLink5Parked;
                        // Known to stand where the interval began: it had no move, whatever it lacked a speed for.
                        if (diLink5AnchorUnmeasured && diLink5Still && (!dynamicsStale || diLink5GpsKmh >= 0)) {
                            synchronized (diLink5GapLock) {
                                diLink5PendingGaps.clear();
                            }
                            diLink5AnchorUnmeasured = false;
                            diLink5AnchorHeld = false;
                            diLink5KmSinceAnchor = 0;
                            diLink5AnchorAtMs = now;
                        }
                    }
                    lastLat = lat;
                    lastLon = lon;
                    hasLastGps = true;
                }
            } else if (haveGps) {
                lastLat = lat;
                lastLon = lon;
                hasLastGps = true;
            }
            lastSampleMs = now;
            diLink5PrevParked = diLink5Parked;

            // Track stats — only from real readings; synthetic stale zeros would
            // drag the average down and never affect max anyway.
            if (!dynamicsStale) {
                if (speedKmh > maxSpeedKmh && !diLink5Parked) {
                    maxSpeedKmh = speedKmh;
                }
                speedSumKmh += speedKmh;
                speedSampleCount++;
            }

            // Add to scoring buffer (real-dynamics 5Hz samples only). Stale
            // synthetic-zero samples are still written to the flush buffer below
            // for raw-timeline continuity, but are kept out of the single-pass
            // scoring stream so they can't fabricate stop/launch/coast events.
            // On DI5 the same holds for a tick whose pedals it marks invalid.
            if (!dynamicsStale && !diLink5PedalsUnknown) {
                synchronized (allSamplesLock) {
                    allSamples.add(sample);
                }
            }

            // Add to flush buffer
            synchronized (bufferLock) {
                buffer.add(sample);
                // Rough estimate: ~100 bytes per sample
                estimatedBufferBytes += 100;

                // Force flush if buffer exceeds 10MB threshold
                if (estimatedBufferBytes >= MAX_BUFFER_BYTES) {
                    logger.info("Buffer exceeded 10MB threshold, force-flushing");
                    executor.execute(this::flushBuffer);
                }
            }
        } catch (Throwable e) {
            // Catch Throwable to prevent ScheduledExecutorService from silently stopping
            logger.warn("Sample error: " + e.getMessage());
        }
    }

    // ==================== PRIVATE: Flush Pipeline ====================

    /**
     * Flush pipeline:
     * 1. Copy buffer to local list, clear buffer
     * 2. Downsample 5Hz → 1Hz: group by second, pick closest to each whole-second boundary
     * 3. Serialize each 1Hz sample as JSON line using TelemetrySample.toJson()
     * 4. Write gzipped chunk and append to the output file
     */
    /**
     * Flush buffered samples to disk WITHOUT stopping the recording.
     *
     * <p>For use immediately before a process kill that is not a trip end (the
     * UI's prepare-restart + {@code killall -9}). Guarantees the on-disk
     * {@code .jsonl.gz} covers everything sampled so far, so next-boot recovery
     * can reconstruct the trip — while leaving the trip OPEN, so no discard
     * threshold is applied and the telemetry file is not deleted.
     */
    public void flushNow() {
        flushBuffer();
    }

    private void flushBuffer() {
        List<TelemetrySample> toFlush;
        synchronized (bufferLock) {
            if (buffer.isEmpty()) return;
            toFlush = new ArrayList<>(buffer);
            buffer.clear();
            estimatedBufferBytes = 0;
        }

        // Downsample 5Hz → 1Hz
        List<TelemetrySample> downsampled = downsampleTo1Hz(toFlush);

        if (downsampled.isEmpty()) return;

        // Serialize and write gzipped chunk
        try {
            writeGzippedChunk(downsampled);
        } catch (IOException e) {
            logger.error("Failed to write telemetry chunk: " + e.getMessage());
            // Per requirement 2.7: log error and continue, don't crash
        }
    }

    /**
     * 1Hz downsampling: for each whole-second boundary present in the data,
     * select the sample with timestamp closest to that boundary.
     */
    static List<TelemetrySample> downsampleTo1Hz(List<TelemetrySample> samples) {
        if (samples == null || samples.isEmpty()) return new ArrayList<>();

        // Group samples by their whole-second (floor to nearest second)
        Map<Long, List<TelemetrySample>> bySecond = new HashMap<>();
        for (TelemetrySample s : samples) {
            long secondBoundary = (s.timestampMs / 1000) * 1000;
            List<TelemetrySample> group = bySecond.get(secondBoundary);
            if (group == null) {
                group = new ArrayList<>();
                bySecond.put(secondBoundary, group);
            }
            group.add(s);
        }

        // For each second boundary, pick the sample closest to that boundary
        List<Long> sortedSeconds = new ArrayList<>(bySecond.keySet());
        java.util.Collections.sort(sortedSeconds);

        List<TelemetrySample> result = new ArrayList<>(sortedSeconds.size());
        for (long boundary : sortedSeconds) {
            List<TelemetrySample> group = bySecond.get(boundary);
            TelemetrySample closest = null;
            long closestDist = Long.MAX_VALUE;
            for (TelemetrySample s : group) {
                long dist = Math.abs(s.timestampMs - boundary);
                if (dist < closestDist) {
                    closestDist = dist;
                    closest = s;
                }
            }
            if (closest != null) {
                // A landed distance is an event, not a state: the second's kept row carries all of it.
                float landedM = 0f;
                for (TelemetrySample s : group) landedM += s.distanceLandedM;
                result.add(landedM != closest.distanceLandedM ? closest.withDistanceLandedM(landedM) : closest);
            }
        }

        return result;
    }

    /**
     * Write a list of 1Hz samples as a gzipped JSON-lines chunk appended to the output file.
     * Per-chunk approach: each flush creates a temp buffer, gzips it, and appends to the file.
     *
     * <p>Re-resolves the output directory against StorageManager.getTripsWriteDir() on each
     * flush. The directory pointer can flip mid-trip when the SD card unmounts/remounts
     * (the watchdog rebinds tripsDir to internal storage and back). Without re-resolution,
     * we'd keep appending to a path under a now-absent volume, every flush would IOException,
     * and the trip would end with an empty file even though the recorder logs "ok".
     */
    private void writeGzippedChunk(List<TelemetrySample> samples) throws IOException {
        if (outputFile == null) return;

        // Build JSON-lines content
        StringBuilder sb = new StringBuilder();
        for (TelemetrySample sample : samples) {
            sb.append(sample.toJson().toString()).append('\n');
        }

        // Gzip the content
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(sb.toString().getBytes("UTF-8"));
        }

        // Re-resolve target file if its directory has gone away mid-trip.
        // We migrate any partial bytes from the stale path to the live one so
        // a chunk-by-chunk flush stays as one continuous file post-trip.
        File target = resolveOutputFileForFlush();

        // Append gzipped bytes to the (possibly relocated) output file.
        try (OutputStream fos = new BufferedOutputStream(
                new FileOutputStream(target, true))) {
            fos.write(baos.toByteArray());
        }

        logger.info("Flushed " + samples.size() + " 1Hz samples to " + target.getName() +
                " (" + baos.size() + " bytes gzipped)");
    }

    /**
     * Re-resolve the output file against the live {@link StorageManager#getTripsWriteDir()}
     * before each flush. Two distinct cases trigger a migration:
     * <ol>
     *   <li>The original parent volume is gone (SD unmounted mid-trip).</li>
     *   <li>The user explicitly switched the trips storage type via the UI;
     *       the original volume may still be writable, but continuing on it
     *       silently ignores the user's intent. We honor the new selection
     *       on the next flush boundary.</li>
     * </ol>
     *
     * <p>When the live dir differs from the current parent, any prior chunk
     * bytes are copied to the new path before continuing, the source file is
     * removed (otherwise it would survive on the old volume as an orphan),
     * and {@link #outputFile} plus the cleanup-protection marker are
     * rebound to the new path so subsequent flushes and stopRecording's
     * path report see the live location.
     */
    private File resolveOutputFileForFlush() {
        if (outputFile == null) return outputFile;
        File parent = outputFile.getParentFile();
        // A journal on the internal journal dir is deliberately NOT migrated
        // mid-trip: that dir exists precisely so the in-flight file is immune
        // to the trips volume's mount state. The trip-end flow moves it to
        // the trips dir once the row is finalized.
        try {
            File journal = StorageManager.getInstance().getTripJournalDir();
            if (journal != null && parent != null && journal.equals(parent)) {
                return outputFile;
            }
        } catch (Throwable ignored) {}
        File liveDir = StorageManager.getInstance().getTripsWriteDir();
        if (liveDir == null) return outputFile;

        // Same volume: nothing to do. The canWrite() check covers the rare
        // case where the live dir == current parent but has gone read-only
        // (FUSE-bridged SD under heavy GL contention occasionally drops to
        // RO until vold catches up); we let the next flush retry.
        if (liveDir.equals(parent)) {
            return outputFile;
        }

        File newPath = new File(liveDir, outputFile.getName());
        if (newPath.equals(outputFile)) {
            return outputFile;
        }

        File oldPath = outputFile;
        boolean migrated = false;
        if (oldPath.exists() && oldPath.length() > 0
                && parent != null && parent.exists()) {
            try {
                java.nio.file.Files.copy(
                        oldPath.toPath(),
                        newPath.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                migrated = true;
                logger.info("Trip telemetry migrated: " + oldPath.getAbsolutePath()
                        + " -> " + newPath.getAbsolutePath());
            } catch (Exception e) {
                logger.warn("Trip telemetry migration failed: " + e.getMessage()
                        + " — continuing at " + newPath.getAbsolutePath());
            }
        } else {
            // Silent-skip cases (old volume unmounted, file not yet created).
            // Log so operators can correlate "trip ended on a different
            // path than it started" with a known volume event.
            logger.info("Trip telemetry rebinding without copy ("
                    + (oldPath.exists() ? "empty file" : "old parent gone")
                    + "): " + (parent == null ? "<no parent>" : parent.getAbsolutePath())
                    + " -> " + liveDir.getAbsolutePath());
        }

        // Update the cleanup-protection marker BEFORE rebinding outputFile.
        // Order matters: between assigning outputFile=newPath and calling
        // setActiveTripFile(newPath), a concurrent ensureTripsSpace would
        // see activeTripFilePath still pointing at oldPath, leaving newPath
        // unprotected. Cleanup runs from the 30s periodic tick so the race
        // window was sub-µs in practice, but the simpler invariant is to
        // mark the destination protected first; only after that commit do
        // we point outputFile at it.
        boolean markerUpdated = false;
        try {
            StorageManager.getInstance().setActiveTripFile(newPath);
            markerUpdated = true;
        } catch (Exception e) {
            logger.warn("Failed to update active trip file marker after migration: "
                    + e.getMessage());
        }
        outputFile = newPath;

        // Remove the source so the old volume doesn't accumulate an orphan
        // .jsonl.gz that would only get reaped when ensureTripsSpace next
        // walks the inactive volume. Skipped when:
        //   - copy failed → source is the only surviving copy of those bytes.
        //   - marker update failed → cleanup still sees oldPath as the
        //     protected file, so newPath is unprotected. Deleting the source
        //     here would trade one corruption window for another. Leave both
        //     files until the next flush retries the marker update.
        if (migrated && markerUpdated && oldPath.exists()) {
            if (!oldPath.delete()) {
                logger.warn("Failed to remove migrated source: " + oldPath.getAbsolutePath());
            }
        }

        return outputFile;
    }
}

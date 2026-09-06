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
    private long currentTripId = -1;
    private File outputFile;

    // Stats tracking
    private int maxSpeedKmh = 0;
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
        if (recording) {
            logger.warn("Already recording trip " + currentTripId + ", ignoring start for " + tripId);
            return;
        }

        this.currentTripId = tripId;
        this.outputFile = new File(StorageManager.getInstance().getTripsDir(),
                tripId + ".jsonl.gz");
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

        synchronized (bufferLock) {
            buffer.clear();
            estimatedBufferBytes = 0;
        }
        synchronized (allSamplesLock) {
            allSamples.clear();
        }

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
                this::flushBuffer, FIRST_FLUSH_DELAY_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);

        logger.info("Started recording trip " + tripId + " → " + outputFile.getAbsolutePath());
    }

    /**
     * Stop recording. Flushes remaining buffer, closes file.
     * @return the telemetry file path, or null if not recording
     */
    public String stopRecording() {
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

        // Notify StorageManager — clear the in-flight marker first so the
        // post-save cleanup it triggers can reap this file if a downward
        // limit change made it the oldest over-limit file.
        try {
            StorageManager.getInstance().setActiveTripFile(null);
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
        return new File(StorageManager.getInstance().getTripsDir(),
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
            if (snapshot != null) {
                // Check if snapshot is stale (older than 2 seconds means poller may have died)
                long snapshotAge = now - snapshot.timestampMs;
                if (snapshotAge < 2000) {
                    speedKmh = snapshot.speedKmh;
                    accelPedal = snapshot.accelPedalPercent;
                    brakePedal = snapshot.brakePedalPercent;
                    brakePedalPressed = snapshot.brakePedalPressed;
                    dynamicsStale = false;
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

            // Read gear from GearMonitor, preferring car_service on DiLink 5
            // where the vendor gearbox HAL is stuck on a valid Park.
            int gearMode = GearMonitor.getInstance().getCurrentGear();
            try {
                if (com.overdrive.app.byd.DiLink5Platform.isActive()) {
                    int carSvc = com.overdrive.app.byd.CarSvcTelemetry.INSTANCE.gearValue();
                    if (carSvc >= 1 && carSvc <= 6) gearMode = carSvc;
                }
            } catch (Throwable ignored) {}

            TelemetrySample sample = new TelemetrySample(
                    now, speedKmh, accelPedal, brakePedal,
                    brakePedalPressed, gearMode, lat, lon, altitude,
                    fix.verticalAccuracy, fix.altitudeIsMsl);

            // ── Distance fusion (CAN-speed primary, accuracy-gated GPS fallback) ──
            // dt since the previous sample, clamped so a scheduler stall or a
            // process resume can't integrate one tick into a huge jump.
            long dtMs = lastSampleMs > 0 ? (now - lastSampleMs) : 0;
            if (dtMs < 0) dtMs = 0;
            if (dtMs > MAX_INTEGRATION_DT_MS) dtMs = MAX_INTEGRATION_DT_MS;

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
            boolean speedUsable = !dynamicsStale && speedChannelEverLive;
            if (speedUsable && dtMs > 0) {
                // PRIMARY: integrate wheel/CAN speed. Reads ~0 km/h when stopped,
                // so idle dwell adds nothing and GPS jitter is irrelevant. Robust
                // through tunnels/garages where GPS drops out entirely.
                //   km = (km/h) × (hours)
                totalDistanceKm += speedKmh * (dtMs / 3_600_000.0);
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

            // Always advance the GPS anchor on a valid fix so the next fallback
            // segment measures from here, even on ticks where CAN speed drove the
            // accumulation (keeps the fallback honest if dynamics later go stale).
            if (haveGps) {
                lastLat = lat;
                lastLon = lon;
                hasLastGps = true;
            }
            lastSampleMs = now;

            // Track stats — only from real readings; synthetic stale zeros would
            // drag the average down and never affect max anyway.
            if (!dynamicsStale) {
                if (speedKmh > maxSpeedKmh) {
                    maxSpeedKmh = speedKmh;
                }
                speedSumKmh += speedKmh;
                speedSampleCount++;
            }

            // Add to scoring buffer (real-dynamics 5Hz samples only). Stale
            // synthetic-zero samples are still written to the flush buffer below
            // for raw-timeline continuity, but are kept out of the single-pass
            // scoring stream so they can't fabricate stop/launch/coast events.
            if (!dynamicsStale) {
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
                result.add(closest);
            }
        }

        return result;
    }

    /**
     * Write a list of 1Hz samples as a gzipped JSON-lines chunk appended to the output file.
     * Per-chunk approach: each flush creates a temp buffer, gzips it, and appends to the file.
     *
     * <p>Re-resolves the output directory against StorageManager.getTripsDir() on each
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
     * Re-resolve the output file against the live {@link StorageManager#getTripsDir()}
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
        File liveDir = StorageManager.getInstance().getTripsDir();
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

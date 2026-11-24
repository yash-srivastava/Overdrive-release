package com.overdrive.app.monitor;

import android.os.SystemClock;

import com.overdrive.app.daemon.CameraDaemon;
import com.overdrive.app.trips.OdometerReader;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * Where the parked car stands, for the safe-zone gate when live GPS is unusable. Tightest first:
 * <ul>
 *   <li>the newest accurate fix taken standing in P, held until the gearbox leaves P, a fix shows
 *   movement, or the car is switched on while the gearbox is not watched;</li>
 *   <li>the newest accurate fix of any kind, widened by the odometer distance since a reading taken
 *   no later than that fix (in a covered car park the last fix is the entrance). Saved at every
 *   switch-off, so a restarted daemon keeps it.</li>
 * </ul>
 * Fed from {@link GpsMonitor}, {@link CameraDaemon#onGearChanged} and the ACC edge in {@link AccMonitor}.
 */
public final class ParkedPosition {

    private static final class Holder {
        static final ParkedPosition INSTANCE =
                new ParkedPosition(com.overdrive.app.util.ScratchRoot.path("parked_position.json"));
    }

    public static ParkedPosition get() { return Holder.INSTANCE; }

    /** Same accuracy limit as the safe-zone gate. */
    static final double MAX_ACCURACY_M = 50.0;
    /** A gearbox first seen later than this after switch-on may have moved unseen. */
    static final long FIRST_GEAR_MAX_DELAY_MS = 10_000;
    /** The gear poller counts as watching while its last reading is at most this old. */
    static final long GEAR_WATCH_MAX_AGE_MS = 10_000;
    /** Odometer readings are taken at most this often while fixes arrive, and reused this long. */
    static final long ODOMETER_PERIOD_MS = 10_000;
    /** A margin wider than this decides nothing. */
    static final double MAX_DRIFT_KM = 5.0;
    static final double MILES_TO_KM = 1.60934;

    /** A position; driftM widens its uncertainty for movement after the fix. */
    public static final class Fix {
        public final double latitude;
        public final double longitude;
        public final float accuracyM;
        // provider monotonic fix time; 0 for a fix restored from an earlier daemon run
        public final long fixElapsedMs;
        public final long fixWallMs;
        public final double driftM;

        Fix(double latitude, double longitude, float accuracyM, long fixElapsedMs, long fixWallMs,
            double driftM) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.accuracyM = accuracyM;
            this.fixElapsedMs = fixElapsedMs;
            this.fixWallMs = fixWallMs;
            this.driftM = driftM;
        }

        Fix withDrift(double driftM) {
            return new Fix(latitude, longitude, accuracyM, fixElapsedMs, fixWallMs, driftM);
        }

        public long ageMs(long nowElapsedMs, long nowWallMs) {
            return fixElapsedMs > 0L ? Math.max(0L, nowElapsedMs - fixElapsedMs)
                    : Math.max(0L, nowWallMs - fixWallMs);
        }
    }

    /** An odometer reading; atMs is when it completed (elapsedRealtime, 0 for an earlier run). */
    static final class Odometer {
        final double km;
        final double stepKm;
        final long atMs;

        Odometer(double km, double stepKm, long atMs) {
            this.km = km;
            this.stepKm = stepKm;
            this.atMs = atMs;
        }
    }

    private final String file;
    private boolean inPark;
    private long parkedSinceMs;
    // elapsedRealtime of the current switch-on edge; 0 while switched off
    private long onSinceMs;
    private boolean gearSeenSinceOn;
    private Fix held;
    private Fix lastKnown;
    // read no later than lastKnown was taken
    private Odometer lastKnownOdometer;
    private Odometer sample, previousSample, latestReading;
    private boolean sampling, restoreAttempted;
    // Throttle attempts too: an unavailable odometer must not cost a binder call per GPS fix.
    private long lastOdometerAttemptMs;
    private long savedFixElapsedMs;
    private String unavailable = "no GPS fix recorded since OverDrive started";
    private java.util.concurrent.ExecutorService sampler;

    ParkedPosition(String file) {
        this.file = file;
    }

    /** The parked position, or null when none bounds where the car is; see {@link #unavailableReason}. */
    public Fix current() {
        long nowMs = SystemClock.elapsedRealtime();
        Fix standing = standing();
        if (standing != null) return standing;
        return lastKnown(odometerFor(nowMs), unitSafety());
    }

    public synchronized String unavailableReason() {
        return unavailable;
    }

    /** Stamped live fixes only; a replayed or cache-loaded position is no evidence either way. */
    public void onFix(GpsMonitor.GpsFixSnapshot f) {
        if (f == null || f.loadedFromCache || f.fixElapsedMs <= 0L || !f.hasLocation()) return;
        long nowMs = SystemClock.elapsedRealtime();
        long wallMs = System.currentTimeMillis() - Math.max(0L, nowMs - f.fixElapsedMs);
        log(fix(new Fix(f.latitude, f.longitude, f.accuracy, f.fixElapsedMs, wallMs, 0.0), f.isMoving()));
        if (samplingDue(nowMs)) sampleOdometer();
    }

    public void onGear(int gear) {
        log(gear(gear == GearMonitor.GEAR_P, GearMonitor.gearToString(gear),
                SystemClock.elapsedRealtime()));
    }

    public void onAccEdge(boolean accOn) {
        long now = SystemClock.elapsedRealtime();
        GearMonitor gearbox = GearMonitor.getInstance();
        boolean watching = gearbox.isRunning()
                && now - gearbox.getLastUpdateTime() <= GEAR_WATCH_MAX_AGE_MS;
        log(accEdge(accOn, watching, now));
        if (!accOn) save();
    }

    synchronized Fix standing() {
        return onSinceMs > 0L && !gearSeenSinceOn ? null : held;
    }

    synchronized Fix lastKnown(Odometer now, double unitSafety) {
        restoreLocked();
        if (lastKnown == null) {
            unavailable = "no GPS fix recorded since OverDrive started";
            return null;
        }
        if (lastKnownOdometer == null) {
            unavailable = "no odometer reading before the last GPS fix";
            return null;
        }
        if (now == null) {
            unavailable = "odometer unavailable";
            return null;
        }
        double step = Math.max(now.stepKm, lastKnownOdometer.stepKm);
        double driven = now.km - lastKnownOdometer.km;
        if (driven < -step) {
            unavailable = "odometer reads lower than at the last GPS fix";
            return null;
        }
        double driftKm = unitSafety * (Math.max(0.0, driven) + step);
        if (driftKm > MAX_DRIFT_KM) {
            unavailable = "car has driven " + String.format(java.util.Locale.US, "%.1f", driven)
                    + " km since its last GPS fix";
            return null;
        }
        return lastKnown.withDrift(driftKm * 1000.0);
    }

    synchronized String fix(Fix f, boolean moving) {
        restoreLocked();
        boolean accurate = f.accuracyM > 0f && f.accuracyM <= MAX_ACCURACY_M;
        if (accurate && (lastKnown == null || lastKnown.fixElapsedMs <= 0L
                || f.fixElapsedMs > lastKnown.fixElapsedMs)) {
            Odometer before = sample != null && sample.atMs <= f.fixElapsedMs ? sample
                    : previousSample != null && previousSample.atMs <= f.fixElapsedMs ? previousSample
                    : lastKnownOdometer;
            lastKnown = f;
            lastKnownOdometer = before;
        }
        if (moving) return release("moving fix");
        if (!inPark || f.fixElapsedMs < parkedSinceMs || (onSinceMs > 0L && !gearSeenSinceOn)
                || !accurate) return null;
        boolean first = held == null;
        held = f;
        return first ? "holding fix ±" + Math.round(f.accuracyM) + " m" : null;
    }

    synchronized String gear(boolean park, String name, long nowMs) {
        String late = null;
        if (onSinceMs > 0L && !gearSeenSinceOn) {
            gearSeenSinceOn = true;
            if (nowMs - onSinceMs > FIRST_GEAR_MAX_DELAY_MS) {
                inPark = false;
                late = release("gearbox first seen " + (nowMs - onSinceMs) / 1000 + " s after switch-on");
            }
        }
        if (!park) {
            inPark = false;
            String released = release("gear " + name);
            return released != null ? released : late;
        }
        if (!inPark) {
            inPark = true;
            parkedSinceMs = nowMs;
        }
        return late;
    }

    synchronized String accEdge(boolean on, boolean gearboxWatching, long nowMs) {
        if (on) {
            onSinceMs = nowMs;
            gearSeenSinceOn = gearboxWatching;
            return null;
        }
        boolean watched = onSinceMs <= 0L || (gearSeenSinceOn && gearboxWatching);
        onSinceMs = 0L;
        if (watched) return null;
        inPark = false;
        return release("gearbox not watched while switched on");
    }

    synchronized boolean samplingDue(long nowMs) {
        if (sampling || (lastOdometerAttemptMs != 0
                && nowMs - lastOdometerAttemptMs < ODOMETER_PERIOD_MS)) return false;
        sampling = true;
        lastOdometerAttemptMs = nowMs;
        return true;
    }

    synchronized void sampled(Odometer reading) {
        sampling = false;
        if (reading == null) return;
        previousSample = sample;
        sample = reading;
        latestReading = reading;
    }

    private Odometer odometerFor(long nowMs) {
        synchronized (this) {
            if (latestReading != null && nowMs - latestReading.atMs <= ODOMETER_PERIOD_MS
                    && (lastKnownOdometer == null || latestReading.atMs >= lastKnownOdometer.atMs)) {
                return latestReading;
            }
        }
        Odometer reading = readOdometer();
        synchronized (this) {
            if (reading != null) latestReading = reading;
        }
        return reading;
    }

    private void sampleOdometer() {
        try {
            java.util.concurrent.ExecutorService executor;
            synchronized (this) {
                if (sampler == null) {
                    sampler = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "ParkedPositionOdometer");
                        t.setDaemon(true);
                        return t;
                    });
                }
                executor = sampler;
            }
            executor.execute(() -> sampled(readOdometer()));
        } catch (Throwable t) {
            sampled(null);
        }
    }

    private static Odometer readOdometer() {
        try {
            OdometerReader.Reading reading = OdometerReader.getInstance().readOdometer();
            if (reading == null || !(reading.km > 0) || !(reading.stepKm > 0)) return null;
            return new Odometer(reading.km, reading.stepKm, SystemClock.elapsedRealtime());
        } catch (Throwable t) {
            return null;
        }
    }

    /** The cluster's unit is unconfirmed, so km readings may be miles. */
    private static double unitSafety() {
        try {
            return com.overdrive.app.byd.BydDataCollector.getInstance().isRawDistanceUnitDetected()
                    ? 1.0 : MILES_TO_KM;
        } catch (Throwable t) {
            return MILES_TO_KM;
        }
    }

    private void save() {
        Fix fix;
        Odometer odometer;
        synchronized (this) {
            if (lastKnown == null || lastKnownOdometer == null || lastKnown.fixElapsedMs <= 0L
                    || lastKnown.fixElapsedMs == savedFixElapsedMs) return;
            fix = lastKnown;
            odometer = lastKnownOdometer;
        }
        try {
            String json = new JSONObject().put("lat", fix.latitude).put("lng", fix.longitude)
                    .put("accuracyM", (double) fix.accuracyM).put("fixWallMs", fix.fixWallMs)
                    .put("odometerKm", odometer.km).put("odometerStepKm", odometer.stepKm).toString();
            File target = new File(file);
            File tmp = new File(file + ".tmp");
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                writer.write(json);
            }
            if (!tmp.renameTo(target)) {
                try (OutputStreamWriter writer = new OutputStreamWriter(
                        new FileOutputStream(target), StandardCharsets.UTF_8)) {
                    writer.write(json);
                }
                tmp.delete();
            }
            // Only acknowledge a successful write. A full/unmounted storage
            // device must not suppress retries on the next ACC-off edge.
            synchronized (this) {
                savedFixElapsedMs = fix.fixElapsedMs;
            }
            log("saved last fix ±" + Math.round(fix.accuracyM) + " m at odometer "
                    + String.format(java.util.Locale.US, "%.1f", odometer.km) + " km");
        } catch (Throwable t) {
            log("save failed: " + t.getMessage());
        }
    }

    private void restoreLocked() {
        if (restoreAttempted) return;
        restoreAttempted = true;
        if (lastKnown != null) return;
        try {
            File source = new File(file);
            if (!source.isFile()) return;
            StringBuilder text = new StringBuilder();
            try (InputStreamReader reader = new InputStreamReader(
                    new FileInputStream(source), StandardCharsets.UTF_8)) {
                char[] buffer = new char[512];
                int n;
                // Bound the actual read, not just the loop condition: an
                // oversized or corrupt sidecar must not grow the buffer.
                while (text.length() < 4096 && (n = reader.read(buffer, 0,
                        Math.min(buffer.length, 4096 - text.length()))) > 0) {
                    text.append(buffer, 0, n);
                }
            }
            JSONObject json = new JSONObject(text.toString());
            double lat = json.optDouble("lat", Double.NaN), lng = json.optDouble("lng", Double.NaN);
            double accuracy = json.optDouble("accuracyM", Double.NaN);
            double km = json.optDouble("odometerKm", Double.NaN), step = json.optDouble("odometerStepKm", Double.NaN);
            long wallMs = json.optLong("fixWallMs", 0L);
            if (!(Math.abs(lat) <= 90) || !(Math.abs(lng) <= 180) || (lat == 0 && lng == 0)
                    || !(accuracy > 0 && accuracy <= MAX_ACCURACY_M) || !(km > 0) || !(step > 0) || wallMs <= 0L) {
                return;
            }
            lastKnown = new Fix(lat, lng, (float) accuracy, 0L, wallMs, 0.0);
            lastKnownOdometer = new Odometer(km, step, 0L);
            log("restored last fix ±" + Math.round(accuracy)
                    + " m at odometer " + String.format(java.util.Locale.US, "%.1f", km) + " km");
        } catch (Throwable t) {
            log("restore failed: " + t.getMessage());
        }
    }

    private String release(String reason) {
        if (held == null) return null;
        held = null;
        return "released (" + reason + ")";
    }

    private static void log(String line) {
        if (line == null) return;
        try {
            CameraDaemon.log("ParkedPosition: " + line);
        } catch (Throwable ignored) {}
    }
}

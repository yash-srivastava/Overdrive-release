package com.overdrive.app.parking;

import com.overdrive.app.notifications.NotificationEvent;
import com.overdrive.app.surveillance.Actor;
import com.overdrive.app.surveillance.DetectionBaseline;

import java.io.File;
import java.util.List;

/**
 * Everything the {@link ParkingController} needs from the daemon, behind one
 * interface so the controller's lifecycle and session logic run in plain JVM
 * unit tests with a fake. {@link DaemonParkingEnvironment} is the production
 * implementation.
 */
public interface ParkingEnvironment {

    /** Immutable GPS reading; all fields passed through from the daemon's monitor. */
    final class GpsFix {
        public final double lat;
        public final double lng;
        public final float accuracyM;
        /** Monotonic (elapsedRealtime) ms of the fix, 0 when unknown. */
        public final long fixElapsedMs;
        /** Wall-clock send time of the fix, 0 when unknown. */
        public final long lastUpdateMs;
        public final boolean loadedFromCache;

        public GpsFix(double lat, double lng, float accuracyM, long fixElapsedMs,
                      long lastUpdateMs, boolean loadedFromCache) {
            this.lat = lat;
            this.lng = lng;
            this.accuracyM = accuracyM;
            this.fixElapsedMs = fixElapsedMs;
            this.lastUpdateMs = lastUpdateMs;
            this.loadedFromCache = loadedFromCache;
        }

        public boolean hasLocation() {
            return !(lat == 0.0 && lng == 0.0) && !Double.isNaN(lat) && !Double.isNaN(lng);
        }
    }

    /** Resolved place label. */
    final class Place {
        public final String shortLabel;
        public final String displayName;
        public final String source;

        public Place(String shortLabel, String displayName, String source) {
            this.shortLabel = shortLabel;
            this.displayName = displayName;
            this.source = source;
        }
    }

    /** Image operations that need the Android graphics stack. May be null in tests. */
    interface ImageOps {
        /** Compose four quadrant JPEGs (front, right, rear, left; nulls allowed) into a 2×2 JPEG. */
        byte[] composeMosaic(byte[][] quadrantJpegs, int tileW, int tileH, int quality);
        /**
         * Sharpness × exposure score of a JPEG, optionally restricted to a
         * normalised region (nx, ny, nw, nh in [0,1]; pass null for whole frame).
         * Higher is better; 0 on decode failure.
         */
        double frameScore(byte[] jpeg, float[] regionNorm);

        /**
         * True when the JPEG is (near-)uniformly black — what the GL sampler
         * produces when it renders before the camera texture is valid in its
         * shared context. A real night scene has streetlight / IR highlights
         * and never decodes to a flat black plane. False on decode failure.
         */
        default boolean isBlank(byte[] jpeg) { return false; }
    }

    long nowMs();
    /** Monotonic clock in ms (elapsedRealtime on Android). */
    long nowElapsedMs();

    GpsFix readGpsFix();

    /**
     * Resolve a place for the parking flow. The callback fires at most once and
     * only on success (mirrors GeocodingResolver.resolveAsync semantics).
     */
    void resolvePlaceAsync(double lat, double lng, java.util.function.Consumer<Place> callback);

    /** Name of the safe zone the car is in, or null. */
    String currentSafeZoneName();

    /** One of the {@link ParkingSession}{@code .SENTRY_*} constants. */
    String sentryState();

    boolean isPipelineRunning();
    boolean isSentryArmed();
    boolean isEventRecording();
    boolean isAccOn();
    /**
     * False while the daemon's ACC reading is still the boot default (no real
     * reading yet). {@link #isAccOn()} must not be trusted until this is true.
     */
    default boolean isAccStateAuthoritative() { return true; }
    boolean isCharging();

    /**
     * HV battery state of charge right now, percent in (0, 100], or NaN when no
     * trustworthy reading exists (collector not initialized yet, value out of
     * range). Session energy bookends only; must never block.
     */
    default double readSocPercent() { return Double.NaN; }

    /**
     * BMS remaining energy right now, kWh, or NaN when no trustworthy reading
     * exists. The direct measurement the trips page books energy from
     * ({@code kwhStart/kwhEnd}); preferred over the SoC estimate, which is
     * quantized to whole percents on most trims. Must never block.
     */
    default double readRemainKwh() { return Double.NaN; }

    /**
     * Convert a SoC amount (percent, magnitude) into usable energy (kWh) using
     * the pack's nominal capacity and SOH, or NaN when the capacity is unknown.
     */
    default double estimateEnergyKwh(double socPercent) { return Double.NaN; }

    /**
     * Whether the gearbox is in P right now — {@code null} when no FRESH
     * reading exists (gear poller not running, stale DiLink5 bridge value, no
     * gearbox device). Drive-away end-trigger only; a cold or stale reading
     * must surface as {@code null}, never as a guess in either direction.
     */
    default Boolean gearInPark() { return null; }

    /** Lock-free live actor snapshot from the engine (empty when unavailable). */
    List<Actor> lastActors();

    /**
     * One camera tile at the best resolution the platform offers (legacy:
     * 1280×960). Quadrant order 0=front,1=right,2=rear,3=left. May return null.
     * Blocks the caller for up to ~2.5 s; only call from the parking worker.
     */
    byte[] captureQuadrantJpeg(int quadrant);

    int rectifyStrength();

    /** Base directory for parking assets (session sub-directories are created under it). */
    File parkingBaseDir();

    /** Door edge delivered while ACC is off. */
    interface DoorListener {
        /** @param open true = a door opened, false = a door closed. */
        void onDoor(boolean open);
    }

    /** Door open/close edges while ACC is off. Single listener; null clears. */
    void setDoorListener(DoorListener listener);

    /** Baseline observer registration (static, process-wide). null clears. */
    void setBaselineListener(DetectionBaseline.Listener listener);

    void publish(NotificationEvent event);

    /**
     * Number of sentry recordings whose timestamp falls in [fromMs, toMs];
     * {@code criticalOnly} narrows to CRITICAL-severity events. Returns -1 when
     * the recordings index is not available (unknown, not zero).
     */
    int countSentryEvents(long fromMs, long toMs, boolean criticalOnly);

    /** Signed token for an unauthenticated asset fetch (push banners), or null. */
    String signAssetToken(String subject, long ttlSec);

    ImageOps imageOps();

    // ==================== v2 SIGNAGE ====================

    /**
     * The scene-text OCR backend for garage signage, or null when the optional
     * models are not installed. Creating it may take a second (mmap + tensor
     * allocation); the controller keeps it only for the duration of a queue
     * run and closes it afterwards.
     */
    com.overdrive.app.parking.signage.TextOcrBackend openSignageOcr();

    /**
     * The most recent drive-mode or proximity recording that started shortly
     * BEFORE the session began (the approach into the garage), or null. Only
     * the file is returned; frame extraction is {@link #extractTailFrames}.
     */
    File recentDriveClip(long sessionStartMs);

    /**
     * Decode up to {@code count} frames evenly spread over the last
     * {@code tailMs} of an MP4, as JPEG camera views at native resolution (a
     * 2×2 grid recording gives four per frame). Empty when the platform cannot
     * decode.
     */
    List<byte[]> extractTailFrames(File mp4, int count, long tailMs);

    void log(String message);
}

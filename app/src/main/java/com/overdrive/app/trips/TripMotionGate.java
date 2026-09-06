package com.overdrive.app.trips;

/**
 * Pure motion policy for trip start/stop when gear is missing or stuck in
 * Park (DiLink 5 car_service GEAR_R lastEvent). Kept free of Android types
 * so the thresholds are unit-testable.
 *
 * <p>Start is harder than stop: GPS jitter at a standstill is typically
 * well under 8 km/h, and a 3-tick confirm (~3 s at 1 Hz) is enough to
 * ignore a single bad fix. Stop uses 2 km/h so a real halt still ends the
 * trip when gear never leaves P.
 */
public final class TripMotionGate {

    private TripMotionGate() {}

    /** Fused speed must stay at or above this for {@link #START_STREAK} ticks. */
    public static final double START_KMH = 8.0;

    /** Fused speed at or below this counts as stopped. */
    public static final double STOP_KMH = 2.0;

    public static final int START_STREAK = 3;
    public static final int STOP_STREAK = 2;

    public static final long GPS_FIX_MAX_AGE_MS = 5_000L;

    public static double gpsSpeedKmh(float speedMps) {
        if (speedMps < 0f || Float.isNaN(speedMps)) return Double.NaN;
        return speedMps * 3.6;
    }

    /**
     * GPS speed is usable only for a live, non-cached fix. Cached/stale
     * coordinates must never start a trip after a restart in a garage.
     */
    public static boolean gpsSpeedUsable(
            boolean hasLocation, boolean loadedFromCache, long fixAgeMs) {
        if (!hasLocation || loadedFromCache) return false;
        return fixAgeMs >= 0L && fixAgeMs <= GPS_FIX_MAX_AGE_MS;
    }

    /** Prefer the faster of CAN and GPS when both exist, so a dead channel cannot hide motion. */
    public static double fuse(double canKmh, double gpsKmh) {
        boolean canOk = !Double.isNaN(canKmh) && canKmh >= 0 && canKmh <= 300;
        boolean gpsOk = !Double.isNaN(gpsKmh) && gpsKmh >= 0 && gpsKmh <= 300;
        if (canOk && gpsOk) return Math.max(canKmh, gpsKmh);
        if (canOk) return canKmh;
        if (gpsOk) return gpsKmh;
        return Double.NaN;
    }

    public static boolean isMovingEnoughToStart(double fusedKmh) {
        return !Double.isNaN(fusedKmh) && fusedKmh >= START_KMH;
    }

    public static boolean isStopped(double fusedKmh) {
        return Double.isNaN(fusedKmh) || fusedKmh <= STOP_KMH;
    }

    /**
     * GPS/speed may start a trip only while we are idle, ACC is known on,
     * and we are not fused-charging (that path already pins gear to P).
     */
    public static boolean mayStartFromMotion(
            boolean idle, boolean accOn, boolean accAuthoritative, boolean charging,
            double fusedKmh) {
        if (!idle || charging) return false;
        if (!accAuthoritative || !accOn) return false;
        return isMovingEnoughToStart(fusedKmh);
    }

    /**
     * Motion-based park: only when gear is Park or unknown. A real Drive
     * gear still ends via the existing P edge so a red-light stop in D
     * does not close the trip.
     */
    public static boolean mayStopFromMotion(int gear, double fusedKmh) {
        if (gear == 2 || gear == 3 || gear == 4 || gear == 5 || gear == 6) {
            return false;
        }
        return isStopped(fusedKmh);
    }

    /** Resume a park-debounce only on start-grade motion, not GPS jitter. */
    public static boolean mayCancelParkDebounce(double fusedKmh) {
        return isMovingEnoughToStart(fusedKmh);
    }
}

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
    // getMileageNumber(int) reports in 0.1 km units — 10x finer than
    // getTotalMileageValue()'s whole km. Preferred when present: a whole-km
    // reading cannot resolve a sub-km trip at all (both edges land on the same
    // integer, so the delta is 0). Null when this trim lacks the method.
    private Method getMileageNumberMethod;
    private boolean initialized = false;
    // Logged once each, so a trim that lacks the fine getter (or that falls back
    // per-read) doesn't spam the log on every trip edge. volatile because the
    // trip-start and trip-end reads can arrive on different threads; a stale read
    // would only duplicate a log line, but volatile makes that deterministic.
    private volatile boolean loggedFineSource = false;
    private volatile boolean loggedFallback = false;
    // Sticky verdict on whether the fine register tracks the authoritative
    // odometer: null = not yet decided, TRUE/FALSE = decided for this process.
    // Deliberately NOT re-decided per read — see readFineRaw.
    private volatile Boolean fineUsable = null;

    // Argument to getMileageNumber() believed to select the cumulative
    // total-distance register, and its unit scale (0.1 km per count). Both are
    // treated as UNVERIFIED and are validated at runtime — see readFineRaw.
    private static final int MILEAGE_KIND_TOTAL = 2;
    private static final double MILEAGE_NUMBER_SCALE = 0.1;
    // How far the fine reading may sit from the authoritative odometer, in COARSE
    // COUNTS, and still be taken as the same register. One count is the
    // quantisation of the value being compared against, so anything inside that is
    // agreement and anything outside it is a different quantity. Counted in raw
    // cluster units, never converted km — see readFineRaw.
    private static final double FINE_AGREEMENT_TOLERANCE_COUNTS = 1.0;
    // A raw total-distance register above this is being reported in a finer unit
    // than whole km (no production odometer legitimately reaches 1,000,000 km).
    private static final int COARSE_UNIT_THRESHOLD = 1_000_000;

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
            // Optional finer-resolution getter. Resolved separately so a trim
            // without it still gets the whole-km path rather than failing init.
            try {
                getMileageNumberMethod = deviceClass.getMethod("getMileageNumber", int.class);
            } catch (Exception e) {
                logger.info("getMileageNumber(int) unavailable — odometer limited to whole-km resolution");
            }
            initialized = true;
            logger.info("OdometerReader initialized (fine="
                    + (getMileageNumberMethod != null) + ")");
        } catch (Exception e) {
            logger.warn("OdometerReader init failed (odometer unavailable): " + e.getMessage());
        }
    }

    /**
     * Read the current odometer value in km, or -1 if unavailable.
     *
     * <p>Sources, in the order they are consulted:
     * <ol>
     *   <li>DiLink 5 {@code dumpsys car_service} {@code STATISTIC_TOTAL_MILEAGE},
     *       including the last-known cache when the parked dump has no lastEvent.</li>
     *   <li>{@code getTotalMileageValue()} — the authoritative total-distance
     *       register, one whole cluster unit per count. A value at or above
     *       {@link #COARSE_UNIT_THRESHOLD} indicates a finer raw unit and is
     *       rescaled.</li>
     *   <li>{@code getMileageNumber(2)} — a REFINEMENT of the above to 0.1 units,
     *       which is what makes a sub-km trip measurable. Applied only once it has
     *       been calibrated against the authoritative reading, because its identity
     *       and scale are not guaranteed — see {@link #readFineRaw}.</li>
     *   <li>The live telemetry snapshot, which has its own feature-ID fallback.
     *       Without this a single failed reflection call blanked the trip's
     *       odometer even though the value was already being collected.</li>
     * </ol>
     */
    public double readOdometerKm() {
        if (com.overdrive.app.byd.DiLink5Platform.isActive()) {
            try {
                int carSvc = com.overdrive.app.byd.CarSvcTelemetry.INSTANCE.totalMileageKm();
                if (carSvc > 0) return carSvc;
            } catch (Exception ignored) {}
        }
        if (!initialized || statisticDevice == null) {
            return snapshotOdometerKm();
        }

        // Tier 1 — the authoritative total-distance register, in RAW cluster units
        // (km or miles, whichever the cluster counts in). Kept unconverted so the
        // fine register can be compared against it on its own scale.
        double coarseRaw = -1;
        if (getTotalMileageValueMethod != null) {
            try {
                Object raw = getTotalMileageValueMethod.invoke(statisticDevice);
                if (raw instanceof Number) {
                    double value = ((Number) raw).doubleValue();
                    if (value > 0) {
                        if (value >= COARSE_UNIT_THRESHOLD) value = value / 10.0;
                        coarseRaw = value;
                    }
                }
            } catch (Exception e) {
                logger.debug("Failed to read odometer: " + e.getMessage());
            }
        }

        // Refinement — the fine register, used only while it agrees with the
        // authoritative one. Until then the coarse value stands, so a wrong
        // assumption about which register this is can never corrupt a reading.
        if (coarseRaw > 0) {
            double fineRaw = readFineRaw(coarseRaw);
            if (fineRaw > 0) {
                if (!loggedFineSource) {
                    loggedFineSource = true;
                    logger.info("Odometer refined via getMileageNumber("
                            + MILEAGE_KIND_TOTAL + ") — 0.1 unit resolution");
                }
                return fineRaw * unitFactor();
            }
            if (!loggedFallback) {
                loggedFallback = true;
                logger.info("Odometer source: getTotalMileageValue (whole-unit resolution)");
            }
            return coarseRaw * unitFactor();
        }

        // Tier 2 — already-collected snapshot value (has its own feature-ID fallback).
        return snapshotOdometerKm();
    }

    /**
     * Read the fine register in RAW cluster units, or -1 when it cannot be trusted.
     *
     * <p>The fine register is only a REFINEMENT of the authoritative reading, never
     * a substitute. Its identity is genuinely ambiguous on this HAL: the same
     * accessor is used elsewhere both as an EV-only distance (scaled by 0.1) and as
     * a total-distance fallback (unscaled). Guessing wrong would be silently
     * catastrophic — an EV-only register on a PHEV, or a 10x scale error, is
     * self-consistent across both trip edges, so it would sail past every
     * downstream sanity check while looking like exact hardware data.
     *
     * <p>So instead of assuming, we CALIBRATE: accept it only if it agrees with the
     * authoritative reading to within that reading's own quantisation. One test
     * rejects a wrong scale, a wrong register, and a resettable trip meter alike.
     *
     * <p>Two details this depends on:
     * <ul>
     *   <li><b>Compared in raw units.</b> The tolerance is one coarse COUNT, which
     *       is one cluster unit — a mile on a miles cluster. Comparing after the
     *       km conversion would test a 1 km window against a 1.61 km quantum, so a
     *       correct reading would be rejected for ~38% of odometer values.</li>
     *   <li><b>The verdict is STICKY.</b> Whether the fine register is usable is a
     *       property of the vehicle, not of the moment. Re-deciding per read means
     *       the answer can flip as the coarse value rolls over between two reads —
     *       and a trip whose start came from one register and end from the other
     *       produces a delta that is pure garbage yet passes as exact. Decided
     *       once, then honoured for the life of the process.</li>
     * </ul>
     *
     * <p>Synchronized so the check-then-set on the sticky verdict cannot be run
     * concurrently by the trip-start and trip-end reads: two threads deciding
     * independently could reach opposite conclusions, which is the very tier split
     * this is meant to prevent. Contention is nil — two calls per trip.
     */
    private synchronized double readFineRaw(double coarseRaw) {
        if (getMileageNumberMethod == null || fineUsable == Boolean.FALSE) return -1;
        try {
            Object raw = getMileageNumberMethod.invoke(statisticDevice, MILEAGE_KIND_TOTAL);
            if (!(raw instanceof Number)) return -1;
            double counts = ((Number) raw).doubleValue();
            if (counts <= 0) return -1;
            double candidate = counts * MILEAGE_NUMBER_SCALE;

            if (fineUsable == null) {
                // First read decides, once, for this process.
                boolean agrees = Math.abs(candidate - coarseRaw) <= FINE_AGREEMENT_TOLERANCE_COUNTS;
                fineUsable = agrees;
                if (!agrees) {
                    logger.info("Ignoring getMileageNumber(" + MILEAGE_KIND_TOTAL + ")="
                            + String.format("%.1f", candidate) + " — disagrees with odometer "
                            + String.format("%.1f", coarseRaw)
                            + " (raw units), so it is not the same register");
                    return -1;
                }
            }
            // Trusted register: a later disagreement means the coarse value simply
            // rolled over, which is exactly the sub-unit precision we want.
            return candidate;
        } catch (Exception e) {
            logger.debug("getMileageNumber read failed: " + e.getMessage());
        }
        return -1;
    }

    /**
     * Odometer from the live telemetry snapshot (km), or -1. The collector reads
     * the same register with its own feature-ID fallback, which is why this tier
     * can answer when the direct reflection above fails.
     *
     * <p>The collector scaled that value by the DISPLAY-preference factor, which
     * is not necessarily the raw register's unit — selecting a miles display on a
     * km cluster inflates it ~1.6x. Tiers 1-2 use the hardware factor, so the
     * snapshot is re-based here onto the same footing; otherwise a start read from
     * one tier and an end read from another would not share a scale, and (worse) a
     * trip served entirely from this tier would book an inflated distance as the
     * exact hardware figure.
     */
    private double snapshotOdometerKm() {
        try {
            com.overdrive.app.byd.BydDataCollector collector =
                    com.overdrive.app.byd.BydDataCollector.getInstance();
            com.overdrive.app.byd.BydVehicleData vd = collector.getData();
            if (vd != null && vd.totalMileageKm != com.overdrive.app.byd.BydVehicleData.UNAVAILABLE
                    && vd.totalMileageKm > 0) {
                double applied = collector.getDistanceToKmFactor();
                // Undo the display-preference factor, then apply the hardware one.
                // Guard the divisor so a pathological 0 can't produce Infinity.
                if (applied > 0) {
                    return vd.totalMileageKm / applied * unitFactor();
                }
                return vd.totalMileageKm;
            }
        } catch (Exception e) {
            logger.debug("Snapshot odometer unavailable: " + e.getMessage());
        }
        return -1;
    }

    /**
     * Unit factor for a RAW register read.
     *
     * <p>The raw reading's unit is fixed by the cluster HARDWARE, so this uses the
     * hardware-detected factor. The display-preference factor is wrong here: it is
     * driven by the user's km/mi choice, which says nothing about the raw unit, so
     * a km cluster with a miles display preference inflated the odometer ~1.6x.
     *
     * <p><b>Known limit.</b> When hardware detection never succeeded (no instrument
     * device, or {@code getMileageUnit} returned nothing usable), this necessarily
     * falls back to the display preference — there is no other evidence of the raw
     * unit available. On a miles cluster that defaults to km, distances then read
     * ~38% short until the user selects miles in Trip Settings, which routes through
     * {@code setDistanceUnitOverride} and corrects this path too.
     *
     * <p>Deliberately NOT cross-checked against the trip recorder to detect that
     * case: the recorder's own speed is scaled by this very factor, so it is skewed
     * identically and cannot witness the error (see the note in
     * {@code TripDetector.finalizeActiveTrip}).
     */
    private static double unitFactor() {
        try {
            return com.overdrive.app.byd.BydDataCollector.getInstance().getSpeedToKmhFactor();
        } catch (Exception e) {
            return 1.0;
        }
    }

    public boolean isAvailable() {
        return initialized && statisticDevice != null;
    }
}

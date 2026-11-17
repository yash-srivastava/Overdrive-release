package com.overdrive.app.trips;

import com.overdrive.app.abrp.SohEstimator;
import com.overdrive.app.logging.DaemonLogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Calibrated range from the driver's own trips ({@link RangeModel}), their calibration, and the
 * drive in progress.
 */
public class RangeEstimator {

    private static final String TAG = "RangeEstimator";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    // BMS reserves the bottom ~2% SoC as a buffer — usable energy tapers off
    private static final double BMS_CUTOFF_SOC = 2.0;
    // Two years, so the temperature curve keeps last winter; the level itself weights recent trips.
    private static final int HISTORY_DAYS = 730;
    private static final int HISTORY_LIMIT = 3000;
    private static final long HISTORY_TTL_MS = 30L * 60_000L;
    /** A pick may sit this far past the band: the band moves a little between showing and saving. */
    private static final double PICK_TOLERANCE = 0.03;

    public enum CalibrationOutcome { SAVED, OUT_OF_BAND, NO_MODEL, INVALID, NOT_SAVED }

    private final TripDatabase database;
    private final SohEstimator sohEstimator;

    private String historyKey;
    private long historyLoadedAtMs;
    private List<RangeModel.Sample> evHistory = Collections.emptyList();
    private List<RangeModel.Sample> fuelHistory = Collections.emptyList();
    private RangeModel.Curves evCurves = RangeModel.curves(null);
    private RangeModel.Curves fuelCurves = RangeModel.curves(null);

    public RangeEstimator(TripDatabase database, SohEstimator sohEstimator) {
        this.database = database;
        this.sohEstimator = sohEstimator;
    }

    /** A finished trip changes the history; the next estimate reloads it. */
    public void onTripCompleted(TripRecord trip) {
        invalidate();
    }

    public synchronized void invalidate() {
        historyKey = null;
    }

    /**
     * Electric range now, or null until enough trips are logged.
     *
     * @param socPercent state of charge (0-100)
     * @param tempC      outside temperature, NaN when unknown
     * @param liveTrip   the drive in progress ({@link TripAnalyticsManager#getLiveTripSnapshot()}), or null
     */
    public RangeEstimate estimate(double socPercent, double tempC, TripRecord liveTrip) {
        double capacity = capacityKwh();
        double usable = usableEnergy(socPercent, capacity);
        if (!(usable > 0)) {
            logger.debug("No usable energy remaining (SoC=" + socPercent + "%)");
            return null;
        }
        long now = System.currentTimeMillis();
        RangeCalibration calibration = database.getRangeCalibration();
        RangeModel.Fit fit = fit(capacity, true, now, calibration);
        if (fit == null) {
            logger.debug("Not enough consumption data for range estimate");
            return null;
        }
        boolean driving = RangeModel.isElectricDrive(liveTrip);
        double liveKm = driving ? liveTrip.distanceKm : Double.NaN;
        double liveSpeed = driving ? liveTrip.avgSpeedKmh : Double.NaN;
        double liveRate = RangeModel.liveRate(liveTrip, capacity);
        RangeModel.Band shown = RangeModel.band(fit, tempC, true, liveKm, liveSpeed, liveRate, usable);
        RangeModel.Band trips = RangeModel.band(fit, tempC, false, Double.NaN, Double.NaN,
                Double.NaN, usable);
        if (shown == null || trips == null) return null;

        RangeEstimate estimate = new RangeEstimate();
        estimate.predictedRangeKm = usable / shown.rate;
        estimate.lowerBoundKm = usable / shown.highRate;
        estimate.upperBoundKm = usable / shown.lowRate;
        estimate.bucketKey = "";
        estimate.sampleCount = fit.trips;
        estimate.basisKm = fit.km;
        estimate.rate = shown.rate;
        estimate.tempC = tempC;
        estimate.tempFactor = shown.tempFactor;
        estimate.usable = usable;
        estimate.socPercent = socPercent;
        estimate.liveShare = shown.liveShare;
        if (fit.anchored) {
            estimate.calibration = calibration;
            estimate.calibrationShare = fit.anchorShare;
        }
        estimate.tripsRate = trips.rate;
        estimate.tripsLowRate = trips.lowRate;
        estimate.tripsHighRate = trips.highRate;
        estimate.tripsRangeKm = usable / trips.rate;
        estimate.tripsLowKm = usable / trips.highRate;
        estimate.tripsHighKm = usable / trips.lowRate;

        logger.debug(String.format(java.util.Locale.ROOT,
                "Range %.0f km [%.0f-%.0f] rate=%.4f trips=%d km=%.0f temp=%.1f tf=%.3f cal=%.2f live=%.2f energy=%.1fkWh",
                estimate.predictedRangeKm, estimate.lowerBoundKm, estimate.upperBoundKm, shown.rate,
                fit.trips, fit.km, tempC, shown.tempFactor, estimate.calibrationShare,
                shown.liveShare, usable));
        return estimate;
    }

    /**
     * Petrol range of a hybrid from its fuel-mode trips, or null without enough of them.
     *
     * @param fuelPercent   current fuel-tank reading (0-100)
     * @param tankCapacityL user-configured tank capacity in litres
     * @param tempC         outside temperature, NaN when unknown
     */
    public RangeEstimate estimateFuelRange(double fuelPercent, double tankCapacityL, double tempC) {
        if (Double.isNaN(fuelPercent) || fuelPercent < 0 || fuelPercent > 100) return null;
        if (!(tankCapacityL > 0)) return null;
        // BYD's fuel sender already reads relative to drivable fuel, so no reserve is held back.
        double litres = (fuelPercent / 100.0) * tankCapacityL;
        if (!(litres > 0)) return null;
        RangeModel.Fit fit = fit(capacityKwh(), false, System.currentTimeMillis(), null);
        RangeModel.Band band = RangeModel.band(fit, tempC, false, Double.NaN, Double.NaN,
                Double.NaN, litres);
        if (band == null) return null;

        RangeEstimate estimate = new RangeEstimate();
        estimate.fuel = true;
        estimate.predictedRangeKm = litres / band.rate;
        estimate.lowerBoundKm = litres / band.highRate;
        estimate.upperBoundKm = litres / band.lowRate;
        estimate.bucketKey = "";
        estimate.sampleCount = fit.trips;
        estimate.basisKm = fit.km;
        estimate.rate = band.rate;
        estimate.tempC = tempC;
        estimate.tempFactor = band.tempFactor;
        estimate.usable = litres;
        return estimate;
    }

    /**
     * Saves the driver's pick of their real range, given as the consumption it implies.
     * The pick must fall inside the trips-only band the slider showed.
     */
    public synchronized CalibrationOutcome calibrate(double kwhPer100Km, double pickedRangeKm,
                                                     double socPercent, double tempC) {
        if (!Double.isFinite(kwhPer100Km) || kwhPer100Km < 3 || kwhPer100Km > 80) {
            return CalibrationOutcome.INVALID;
        }
        double capacity = capacityKwh();
        double usable = usableEnergy(socPercent, capacity);
        if (!(usable > 0)) return CalibrationOutcome.NO_MODEL;
        long now = System.currentTimeMillis();
        RangeModel.Fit fit = fit(capacity, true, now, null);
        RangeModel.Band trips = RangeModel.band(fit, tempC, false, Double.NaN, Double.NaN,
                Double.NaN, usable);
        if (trips == null) return CalibrationOutcome.NO_MODEL;
        double rate = kwhPer100Km / 100.0;
        if (rate < trips.lowRate * (1 - PICK_TOLERANCE) || rate > trips.highRate * (1 + PICK_TOLERANCE)) {
            return CalibrationOutcome.OUT_OF_BAND;
        }
        double rangeKm = Double.isFinite(pickedRangeKm) && pickedRangeKm > 0 && pickedRangeKm < 5000
                ? pickedRangeKm : usable / rate;
        RangeCalibration calibration = new RangeCalibration(
                rate / (trips.tempFactor * trips.speedFactor), rate, rangeKm, socPercent, now);
        if (!database.setRangeCalibration(calibration)) return CalibrationOutcome.NOT_SAVED;
        logger.info(String.format(java.util.Locale.ROOT,
                "Range calibrated: %.1f kWh/100km (%.0f km at %.0f%%), trips said %.1f",
                kwhPer100Km, rangeKm, socPercent, trips.rate * 100.0));
        return CalibrationOutcome.SAVED;
    }

    public boolean clearCalibration() {
        boolean cleared = database.clearRangeCalibration();
        if (cleared) logger.info("Range calibration removed");
        return cleared;
    }

    // ==================== History ====================

    private synchronized RangeModel.Fit fit(double capacityKwh, boolean ev, long nowMs,
                                            RangeCalibration calibration) {
        List<RangeModel.Sample> samples = history(capacityKwh, ev);
        return RangeModel.fit(samples, ev ? evCurves : fuelCurves, nowMs, calibration);
    }

    /** Completed trips as model samples, reloaded when the trips table or the pack capacity changes. */
    private synchronized List<RangeModel.Sample> history(double capacityKwh, boolean ev) {
        long now = System.currentTimeMillis();
        String fingerprint = database.getTripsFingerprint(now - HISTORY_DAYS * 86_400_000L);
        boolean livePhev = isLivePhev();
        String key = fingerprint == null ? null
                : fingerprint + "|" + Math.round(capacityKwh * 100) + "|" + livePhev;
        boolean cached = historyKey != null;
        boolean fresh = cached && now >= historyLoadedAtMs && now - historyLoadedAtMs < HISTORY_TTL_MS;
        // An unreadable fingerprint is a DB hiccup: keep what was loaded rather than blank the range.
        if (cached && (key == null || (fresh && key.equals(historyKey)))) {
            return ev ? evHistory : fuelHistory;
        }

        List<TripRecord> trips = database.getTripsWithoutMoments(HISTORY_DAYS, HISTORY_LIMIT, 0);
        if (trips.isEmpty() && cached && fingerprintCount(fingerprint) > 0) {
            return ev ? evHistory : fuelHistory;
        }
        List<RangeModel.Sample> evSamples = new ArrayList<>();
        List<RangeModel.Sample> fuelSamples = new ArrayList<>();
        int skipped = 0;
        for (TripRecord trip : trips) {
            if (livePhev && RangeModel.isLegacyPhevRow(trip)) {
                skipped++;
                continue;
            }
            RangeModel.Mode mode = RangeModel.mode(trip);
            RangeModel.Sample sample = mode == RangeModel.Mode.EV
                    ? RangeModel.evSample(trip, capacityKwh)
                    : mode == RangeModel.Mode.FUEL ? RangeModel.fuelSample(trip) : null;
            if (sample == null) {
                skipped++;
            } else if (mode == RangeModel.Mode.EV) {
                evSamples.add(sample);
            } else {
                fuelSamples.add(sample);
            }
        }
        evHistory = evSamples;
        fuelHistory = fuelSamples;
        evCurves = RangeModel.curves(evSamples);
        fuelCurves = RangeModel.curves(fuelSamples);
        historyKey = key != null ? key : "unread";
        historyLoadedAtMs = now;
        logger.debug("Range history: ev=" + evSamples.size() + " fuel=" + fuelSamples.size()
                + " skipped=" + skipped + " of " + trips.size());
        return ev ? evHistory : fuelHistory;
    }

    private static long fingerprintCount(String fingerprint) {
        if (fingerprint == null) return 0;
        int colon = fingerprint.indexOf(':');
        try {
            return Long.parseLong(colon > 0 ? fingerprint.substring(0, colon) : fingerprint);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean isLivePhev() {
        try {
            return com.overdrive.app.monitor.VehicleDataMonitor.getInstance().isPhev();
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================== Energy ====================

    /** Pack capacity in the displayed SoH frame, 0 when unknown. */
    private double capacityKwh() {
        if (sohEstimator == null) return 0;
        try {
            double nominalKwh = sohEstimator.getNominalCapacityKwh();
            if (!(nominalKwh > 0)) return 0;
            // the DISPLAYED (capped, anchored) SOH, so range math matches the UI
            return sohEstimator.hasDisplaySoh()
                    ? nominalKwh * sohEstimator.getDisplaySoh() / 100.0 : nominalKwh;
        } catch (Throwable t) {
            logger.debug("Capacity unavailable: " + t.getMessage());
            return 0;
        }
    }

    /**
     * Usable energy above the BMS cutoff; below 5% SoC it tapers to zero at the cutoff, as the BMS
     * limits discharge.
     */
    private static double usableEnergy(double socPercent, double capacityKwh) {
        if (!(capacityKwh > 0) || !Double.isFinite(socPercent)) return 0;
        double usableSocPercent = Math.max(0, socPercent - BMS_CUTOFF_SOC);
        double taperFactor = socPercent < 5.0
                ? Math.max(0, (socPercent - BMS_CUTOFF_SOC) / 3.0) : 1.0;
        return capacityKwh * (usableSocPercent / 100.0) * taperFactor;
    }
}

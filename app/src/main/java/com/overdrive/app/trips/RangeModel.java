package com.overdrive.app.trips;

import java.util.Arrays;
import java.util.List;

/**
 * The personal consumption model behind the calibrated range. Pure: no I/O, no Android.
 *
 * <p>Consumption is Σused / Σkm over completed trips, weighted toward recent ones, with
 * long-memory outside-temperature and average-speed curves (backfitted, empirical-Bayes
 * shrinkage) factored out. The band is an 80%
 * interval for the average over the predicted distance. A calibration counts as a virtual
 * {@link #ANCHOR_KM} trip at the picked rate; trips driven after it outweigh it as they add up.
 * During a drive, its average speed and then its measured consumption pull the rate toward it.
 */
final class RangeModel {

    static final double HALF_LIFE_DAYS = 45.0;
    static final double TEMP_SIGMA_C = 5.0;
    static final double SPEED_SIGMA_KMH = 10.0;
    /** Bounds on the kilometres of evidence a curve point needs to move halfway off 1.0. */
    static final double MIN_CURVE_PRIOR_KM = 20.0;
    static final double MAX_CURVE_PRIOR_KM = 1000.0;
    static final double ANCHOR_KM = 300.0;
    /** Relative uncertainty of the driver's own pick. */
    static final double ANCHOR_REL_SD = 0.06;
    static final double SPEED_CONTEXT_MIN_KM = 2.0;
    static final double SPEED_CONTEXT_PRIOR_KM = 20.0;
    static final double LIVE_MIN_KM = 10.0;
    static final double LIVE_PRIOR_KM = 50.0;
    static final double SYSTEMATIC = 0.08;
    static final double Z80 = 1.2816;
    static final int MIN_TRIPS = 3;
    static final double MIN_KM = 10.0;
    static final double MIN_HORIZON_KM = 30.0;
    static final double MIN_TRIP_KM = 0.5;
    static final double MAX_TRIP_KM = 1500.0;

    static final double EV_MODE_MAX_ICE_FRACTION = 0.05;
    static final double ICE_MODE_MIN_ICE_FRACTION = 0.50;

    private static final double DAY_MS = 86_400_000.0;
    private static final int T_MIN = -50;
    private static final int T_MAX = 60;
    private static final int V_MAX = 200;
    /** Net descent energy a regenerating car can bank, kWh per metre. */
    private static final double REGEN_KWH_PER_M = 0.0035;
    /** SD of the difference of two readings quantised to one step: step·√(2/12). */
    private static final double QUANT_SD_PER_STEP = 0.408;
    private static final double METERED_SD_KWH = 0.05;
    private static final double FUEL_SD_LITRES = 0.2;
    /** One-sided 90% Student-t quantiles for 1..30 degrees of freedom. */
    private static final double[] T90 = {
            3.078, 1.886, 1.638, 1.533, 1.476, 1.440, 1.415, 1.397, 1.383, 1.372,
            1.363, 1.356, 1.350, 1.345, 1.341, 1.337, 1.333, 1.330, 1.328, 1.325,
            1.323, 1.321, 1.319, 1.318, 1.316, 1.315, 1.314, 1.313, 1.311, 1.310};

    private RangeModel() {}

    enum Mode { EV, FUEL, MIXED }

    /**
     * One completed trip; {@code used} is kWh or litres and {@code resolution} its measurement SD.
     * {@code tempC} and {@code speedKmh} are NaN when unknown.
     */
    static final class Sample {
        final long endMs;
        final double km;
        final double used;
        final double resolution;
        final double tempC;
        final double speedKmh;

        Sample(long endMs, double km, double used, double resolution, double tempC, double speedKmh) {
            this.endMs = endMs;
            this.km = km;
            this.used = used;
            this.resolution = resolution;
            this.tempC = tempC;
            this.speedKmh = speedKmh;
        }
    }

    /** Temperature and speed response, relative to the trips' mean; 1.0 where nothing is known. */
    static final class Curves {
        private final double[] temp;
        private final double[] speed;

        Curves(double[] temp, double[] speed) {
            this.temp = temp;
            this.speed = speed;
        }

        double temp(double tempC, double fallback) {
            if (Double.isNaN(tempC)) return fallback;
            long t = Math.round(tempC);
            return t < T_MIN || t > T_MAX ? fallback : temp[(int) t - T_MIN];
        }

        double speed(double kmh, double fallback) {
            if (!(kmh > 0)) return fallback;
            long v = Math.round(kmh);
            return v > V_MAX ? fallback : speed[(int) v];
        }
    }

    static final class Fit {
        final int trips;
        final double km;
        /** Normalised rate from trips alone. */
        final double level;
        /** With the calibration blended in; equals {@link #level} without one. */
        final double anchoredLevel;
        final boolean anchored;
        final double anchorShare;
        final double recentTempFactor;
        final double recentSpeedFactor;
        /** Window term of the band: divide by the predicted distance in km. */
        final double relVarPerKm;
        final double relVarLevel;
        final double relVarAnchored;
        final double effectiveTrips;
        final Curves curves;

        Fit(int trips, double km, double level, double anchoredLevel, boolean anchored,
            double anchorShare, double recentTempFactor, double recentSpeedFactor,
            double relVarPerKm, double relVarLevel, double relVarAnchored,
            double effectiveTrips, Curves curves) {
            this.trips = trips;
            this.km = km;
            this.level = level;
            this.anchoredLevel = anchoredLevel;
            this.anchored = anchored;
            this.anchorShare = anchorShare;
            this.recentTempFactor = recentTempFactor;
            this.recentSpeedFactor = recentSpeedFactor;
            this.relVarPerKm = relVarPerKm;
            this.relVarLevel = relVarLevel;
            this.relVarAnchored = relVarAnchored;
            this.effectiveTrips = effectiveTrips;
            this.curves = curves;
        }

        /** Curve factor at {@code tempC}; an unknown temperature takes the recent trips' mix. */
        double tempFactor(double tempC) {
            return curves.temp(tempC, recentTempFactor);
        }
    }

    static final class Band {
        final double rate;
        final double lowRate;
        final double highRate;
        final double tempFactor;
        final double speedFactor;
        final double liveShare;

        Band(double rate, double lowRate, double highRate, double tempFactor, double speedFactor,
             double liveShare) {
            this.rate = rate;
            this.lowRate = lowRate;
            this.highRate = highRate;
            this.tempFactor = tempFactor;
            this.speedFactor = speedFactor;
            this.liveShare = liveShare;
        }
    }

    /** Temperature and speed curves by two rounds of backfitting over every sample. */
    static Curves curves(List<Sample> samples) {
        int tSpan = T_MAX - T_MIN + 1;
        int vSpan = V_MAX + 1;
        Curves current = new Curves(ones(tSpan), ones(vSpan));
        if (samples == null || samples.isEmpty()) return current;
        int n = samples.size();
        double[] tKernel = kernel(tSpan, TEMP_SIGMA_C);
        double[] vKernel = kernel(vSpan, SPEED_SIGMA_KMH);
        int[] tBin = new int[n];
        int[] vBin = new int[n];
        for (int i = 0; i < n; i++) {
            Sample s = samples.get(i);
            long t = Double.isNaN(s.tempC) ? Long.MIN_VALUE : Math.round(s.tempC) - T_MIN;
            tBin[i] = t >= 0 && t < tSpan ? (int) t : -1;
            long v = s.speedKmh > 0 ? Math.round(s.speedKmh) : -1;
            vBin[i] = v >= 0 && v <= V_MAX ? (int) v : -1;
        }
        double[] adjusted = new double[n];
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < n; i++) {
                adjusted[i] = samples.get(i).used / current.speed(samples.get(i).speedKmh, 1.0);
            }
            double[] temp = fitCurve(samples, tBin, adjusted, tSpan, tKernel);
            current = new Curves(temp, current.speed);
            for (int i = 0; i < n; i++) {
                adjusted[i] = samples.get(i).used / current.temp(samples.get(i).tempC, 1.0);
            }
            double[] speed = fitCurve(samples, vBin, adjusted, vSpan, vKernel);
            current = new Curves(current.temp, speed);
        }
        return current;
    }

    /**
     * One response curve over per-sample bins. The pull toward 1.0 is empirical Bayes: the
     * within-bin noise over the spread between bins, so a strong, well-measured effect (winter,
     * motorway) stands while a faint one is flattened.
     */
    private static double[] fitCurve(List<Sample> samples, int[] bin, double[] adjusted, int span,
                                     double[] kernel) {
        double[] used = new double[span];
        double[] km = new double[span];
        double usedSum = 0, kmSum = 0, sumSqKm = 0;
        for (int i = 0; i < samples.size(); i++) {
            if (bin[i] < 0) continue;
            double d = samples.get(i).km;
            used[bin[i]] += adjusted[i];
            km[bin[i]] += d;
            usedSum += adjusted[i];
            kmSum += d;
            sumSqKm += d * d;
        }
        if (!(kmSum > 0) || !(usedSum > 0)) return ones(span);
        double mean = usedSum / kmSum;
        double within = 0;
        for (int i = 0; i < samples.size(); i++) {
            int b = bin[i];
            if (b < 0) continue;
            double d = samples.get(i).km;
            double dev = adjusted[i] / d / mean - used[b] / km[b] / mean;
            within += d * dev * dev;
        }
        double noisePerKm = within / kmSum * (sumSqKm / kmSum);
        double between = 0;
        int bins = 0;
        for (int b = 0; b < span; b++) {
            if (km[b] == 0) continue;
            double dev = used[b] / km[b] / mean - 1;
            between += km[b] * dev * dev;
            bins++;
        }
        double signal = between / kmSum - noisePerKm * bins / kmSum;
        double priorKm = signal > 1e-6
                ? Math.max(MIN_CURVE_PRIOR_KM, Math.min(MAX_CURVE_PRIOR_KM, noisePerKm / signal))
                : MAX_CURVE_PRIOR_KM;
        return smooth(used, km, kernel, mean, priorKm);
    }

    /** Null below {@link #MIN_TRIPS} trips or {@link #MIN_KM} km, or when nothing usable remains. */
    static Fit fit(List<Sample> samples, Curves curves, long nowMs, RangeCalibration anchor) {
        if (samples == null || samples.size() < MIN_TRIPS || curves == null) return null;
        int n = samples.size();
        double totalKm = 0;
        for (int i = 0; i < n; i++) totalKm += samples.get(i).km;
        if (!(totalKm >= MIN_KM)) return null;

        double halfLifeMs = HALF_LIFE_DAYS * DAY_MS;
        double[] w = new double[n];
        double[] f = new double[n];
        double weightKm = 0, normUsed = 0, tempMix = 0, speedMix = 0, sumSqWeightKm = 0, resSq = 0;
        for (int i = 0; i < n; i++) {
            Sample s = samples.get(i);
            w[i] = Math.pow(0.5, Math.max(0, nowMs - s.endMs) / halfLifeMs);
            double tf = curves.temp(s.tempC, 1.0);
            double sf = curves.speed(s.speedKmh, 1.0);
            f[i] = tf * sf;
            double wd = w[i] * s.km;
            weightKm += wd;
            normUsed += w[i] * s.used / f[i];
            tempMix += wd * tf;
            speedMix += wd * sf;
            sumSqWeightKm += wd * wd;
            double r = w[i] * s.resolution / f[i];
            resSq += r * r;
        }
        if (!(weightKm > 1e-9)) return null;
        double level = normUsed / weightKm;
        if (!(level > 0) || !Double.isFinite(level)) return null;

        double var = 0;
        for (int i = 0; i < n; i++) {
            Sample s = samples.get(i);
            double dev = s.used / (s.km * f[i]) - level;
            var += w[i] * s.km * dev * dev;
        }
        var /= weightKm;
        double levelSq = level * level;
        double relVarPerKm = var * (sumSqWeightKm / weightKm) / levelSq;
        // A handful of coarse readings can agree by chance; their resolution still bounds the level.
        double relVarLevel = Math.max(var * sumSqWeightKm / (weightKm * weightKm),
                resSq / (weightKm * weightKm)) / levelSq;

        double anchoredLevel = level;
        double share = 0;
        double relVarAnchored = relVarLevel;
        boolean anchored = false;
        if (anchor != null && anchor.isUsable()) {
            double k = ANCHOR_KM * Math.pow(0.5, Math.max(0, nowMs - anchor.setAtMs) / halfLifeMs);
            double postKm = 0, postUsed = 0, postSq = 0, postRes = 0;
            for (int i = 0; i < n; i++) {
                Sample s = samples.get(i);
                if (s.endMs <= anchor.setAtMs) continue;
                double wd = w[i] * s.km;
                postKm += wd;
                postUsed += w[i] * s.used / f[i];
                postSq += wd * wd;
                double r = w[i] * s.resolution / f[i];
                postRes += r * r;
            }
            double support = k + postKm;
            double blended = support > 1e-12
                    ? (k * anchor.normRateKwhPerKm + postUsed) / support : Double.NaN;
            if (blended > 0 && Double.isFinite(blended)) {
                anchored = true;
                anchoredLevel = blended;
                share = k / support;
                double anchorSd = share * anchor.normRateKwhPerKm * ANCHOR_REL_SD;
                double dataVar = Math.max(var * postSq, postRes) / (support * support);
                relVarAnchored = (anchorSd * anchorSd + dataVar) / (blended * blended);
            }
        }
        return new Fit(n, totalKm, level, anchoredLevel, anchored, share, tempMix / weightKm,
                speedMix / weightKm, relVarPerKm, relVarLevel, relVarAnchored,
                weightKm * weightKm / sumSqWeightKm, curves);
    }

    /**
     * The rate at {@code tempC} with its 80% band for the distance {@code usable} lasts.
     * {@code shown} adds the calibration and the drive in progress (its distance, average speed
     * and measured kWh/km, each NaN or 0 when unknown); otherwise trips only, typical driving.
     */
    static Band band(Fit fit, double tempC, boolean shown, double liveKm, double liveSpeedKmh,
                     double liveRate, double usable) {
        if (fit == null) return null;
        double tf = fit.tempFactor(tempC);
        double sf = fit.recentSpeedFactor;
        boolean live = shown && Double.isFinite(liveKm);
        if (live && liveKm >= SPEED_CONTEXT_MIN_KM && liveSpeedKmh > 0) {
            double mu = liveKm / (liveKm + SPEED_CONTEXT_PRIOR_KM);
            sf = (1 - mu) * sf + mu * fit.curves.speed(liveSpeedKmh, sf);
        }
        boolean anchored = shown && fit.anchored;
        double rate = (anchored ? fit.anchoredLevel : fit.level) * tf * sf;
        double liveShare = 0;
        if (live && liveKm >= LIVE_MIN_KM && liveRate > 0 && Double.isFinite(liveRate)) {
            liveShare = liveKm / (liveKm + LIVE_PRIOR_KM);
            rate = (1 - liveShare) * rate + liveShare * liveRate;
        }
        if (!(rate > 0) || !Double.isFinite(rate)) return null;
        double horizonKm = usable > 0 ? Math.max(MIN_HORIZON_KM, usable / rate) : MIN_HORIZON_KM;
        double relVar = fit.relVarPerKm / horizonKm
                + (anchored ? fit.relVarAnchored : fit.relVarLevel)
                + SYSTEMATIC * SYSTEMATIC;
        double rel = Math.sqrt(relVar) * t90(fit.effectiveTrips - 1) / Z80;
        if (!Double.isFinite(rel)) return null;
        return new Band(rate, rate * Math.max(0.3, 1 - Z80 * rel), rate * (1 + Z80 * rel),
                tf, sf, liveShare);
    }

    // ==================== Trip samples ====================

    static Mode mode(TripRecord t) {
        if (!t.isPhev) return Mode.EV;
        double frac = iceFraction(t);
        if (frac <= EV_MODE_MAX_ICE_FRACTION) return Mode.EV;
        if (frac >= ICE_MODE_MIN_ICE_FRACTION) return Mode.FUEL;
        return Mode.MIXED;
    }

    /** A hybrid row written before the hybrid columns existed; its mode can't be told. */
    static boolean isLegacyPhevRow(TripRecord t) {
        return !t.isPhev && t.iceSeconds() == 0 && t.fuelPctStart < 0 && t.fuelPctEnd < 0
                && t.litresUsed == 0;
    }

    /**
     * The kWh a completed trip used, or null when it can't be measured. The net remaining-energy
     * delta is signed (regen counts); a source that disagrees with the state-of-charge delta is
     * skipped, and equal readings over a distance that must have moved them mean no data.
     */
    static Sample evSample(TripRecord t, double capacityKwh) {
        if (t == null || t.endTime <= 0) return null;
        double km = t.distanceKm;
        if (!(km > MIN_TRIP_KM) || km > MAX_TRIP_KM) return null;
        double step = capacityKwh > 0 ? capacityKwh / 100.0 : 0.8;
        boolean frozen = km * 0.08 > 1.5 * step;
        boolean haveSoc = t.socStart > 0 && t.socEnd > 0 && capacityKwh > 0;
        double socKwh = haveSoc ? (t.socStart - t.socEnd) / 100.0 * capacityKwh : Double.NaN;
        boolean haveNet = t.kwhStart > 0 && t.kwhEnd > 0;
        double metered = t.hasMeteredEnergy() ? t.getMeteredEnergyKwh() : Double.NaN;
        if (metered > 1.0 + km) metered = Double.NaN;

        double used;
        double slack;
        double resolution;
        if (haveNet && t.kwhStart != t.kwhEnd && agrees(t.kwhStart - t.kwhEnd, socKwh, step)) {
            used = t.kwhStart - t.kwhEnd;
            slack = 2 * step;
            resolution = QUANT_SD_PER_STEP * step;
        } else if (Double.isFinite(metered) && agrees(metered, socKwh, step)) {
            used = metered;
            slack = 0.3;
            resolution = METERED_SD_KWH;
        } else if (haveSoc) {
            if (t.socStart == t.socEnd && frozen) return null;
            used = socKwh;
            slack = 2 * step;
            resolution = QUANT_SD_PER_STEP * step;
        } else if (haveNet && t.kwhStart == t.kwhEnd) {
            if (frozen) return null;
            used = 0;
            slack = 2 * step;
            resolution = QUANT_SD_PER_STEP * step;
        } else {
            return null;
        }
        if (!plausible(used, km, slack, t)) return null;
        return new Sample(t.endTime, km, used, resolution, tempOf(t.extTempC), speedOf(t.avgSpeedKmh));
    }

    static Sample fuelSample(TripRecord t) {
        if (t == null || t.endTime <= 0) return null;
        double km = t.distanceKm;
        if (!(km > MIN_TRIP_KM) || km > MAX_TRIP_KM || !(t.litresUsed > 0)) return null;
        double perKm = t.litresUsed / km;
        if (perKm < 0.02 || perKm > 0.20) return null;
        return new Sample(t.endTime, km, t.litresUsed, FUEL_SD_LITRES, tempOf(t.extTempC),
                speedOf(t.avgSpeedKmh));
    }

    /**
     * kWh/km of the drive in progress, or NaN while it is too short, burning fuel, or not
     * measured. {@code live} is {@link TripAnalyticsManager#getLiveTripSnapshot()}.
     */
    static double liveRate(TripRecord live, double capacityKwh) {
        if (!isElectricDrive(live)) return Double.NaN;
        double km = live.distanceKm;
        if (!(km >= LIVE_MIN_KM)) return Double.NaN;
        double step = capacityKwh > 0 ? capacityKwh / 100.0 : 0.8;
        boolean haveSoc = live.socStart > 0 && live.socEnd > 0 && capacityKwh > 0;
        double socKwh = haveSoc ? (live.socStart - live.socEnd) / 100.0 * capacityKwh : Double.NaN;
        double used = live.energyPerKm > 0 && Double.isFinite(live.energyPerKm)
                ? live.energyPerKm * km : Double.NaN;
        double rate;
        if (Double.isFinite(used) && agrees(used, socKwh, step)) {
            rate = used / km;
        } else if (haveSoc && live.socStart - live.socEnd >= 2) {
            rate = socKwh / km;
        } else {
            return Double.NaN;
        }
        return rate >= 0.03 && rate <= 0.6 ? rate : Double.NaN;
    }

    /** The drive in progress runs on the battery and has a usable distance. */
    static boolean isElectricDrive(TripRecord live) {
        if (live == null) return false;
        double km = live.distanceKm;
        if (!(km > 0) || km > MAX_TRIP_KM) return false;
        return !live.isPhev
                || (live.durationSeconds > 0 && iceFraction(live) <= EV_MODE_MAX_ICE_FRACTION);
    }

    // ==================== Helpers ====================

    private static boolean agrees(double kwh, double socKwh, double step) {
        if (Double.isNaN(socKwh)) return true;
        return Math.abs(kwh - socKwh) <= Math.max(2.5 * step, 0.4 * Math.abs(socKwh));
    }

    private static boolean plausible(double used, double km, double slack, TripRecord t) {
        if (!Double.isFinite(used)) return false;
        double descentM = t.elevationLossM - t.elevationGainM;
        double regen = Double.isFinite(descentM) && descentM > 0 ? REGEN_KWH_PER_M * descentM : 0;
        return used >= 0.02 * km - slack - regen && used <= 0.6 * km + slack;
    }

    private static double iceFraction(TripRecord t) {
        if (t.durationSeconds <= 0) return 0;
        return Math.min(1.0, (double) t.iceSeconds() / t.durationSeconds);
    }

    /** Trips store 0 when the outside temperature wasn't read. */
    static double tempOf(int tempC) {
        return tempC == 0 || tempC < T_MIN || tempC > T_MAX ? Double.NaN : tempC;
    }

    static double speedOf(double kmh) {
        return kmh > 0 && kmh <= V_MAX ? kmh : Double.NaN;
    }

    private static double t90(double degreesOfFreedom) {
        if (!(degreesOfFreedom >= 1)) return T90[0];
        if (degreesOfFreedom >= T90.length) {
            return Z80 + (T90[T90.length - 1] - Z80) * T90.length / degreesOfFreedom;
        }
        int lo = (int) Math.floor(degreesOfFreedom);
        double frac = degreesOfFreedom - lo;
        return T90[lo - 1] + (T90[Math.min(lo, T90.length - 1)] - T90[lo - 1]) * frac;
    }

    private static double[] ones(int n) {
        double[] a = new double[n];
        Arrays.fill(a, 1.0);
        return a;
    }

    private static double[] kernel(int n, double sigma) {
        double[] k = new double[n];
        for (int d = 0; d < n; d++) k[d] = Math.exp(-(double) d * d / (2 * sigma * sigma));
        return k;
    }

    /** Kernel-smoothed rate per bin relative to {@code mean}, shrunk to 1 by {@code priorKm}. */
    private static double[] smooth(double[] used, double[] km, double[] kernel, double mean,
                                   double priorKm) {
        int n = used.length;
        double[] curve = ones(n);
        for (int t = 0; t < n; t++) {
            double num = 0, den = 0;
            for (int b = 0; b < n; b++) {
                if (km[b] == 0) continue;
                double k = kernel[Math.abs(b - t)];
                num += k * used[b];
                den += k * km[b];
            }
            double f = ((num + priorKm * mean) / (den + priorKm)) / mean;
            curve[t] = Math.max(0.5, Math.min(2.0, f));
        }
        return curve;
    }
}

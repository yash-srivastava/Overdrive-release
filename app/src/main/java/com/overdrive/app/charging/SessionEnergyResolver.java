package com.overdrive.app.charging;

import com.overdrive.app.logging.DaemonLogger;

/**
 * Decides a charging session's energy-added figure from several disagreeing sources, and records
 * WHICH one it used.
 *
 * <p>Three independent estimates can exist for one session, in descending trustworthiness:
 * <ol>
 *   <li><b>Metered</b> — the vehicle's own charged-energy counter, wrap-corrected by
 *       {@link ChargeCounterAccumulator}. No unit guess, no pack-capacity divisor, no SOC
 *       quantisation. Best when alive and unsaturated.</li>
 *   <li><b>Integrated</b> — the time integral of the sampled charge rate. Only as good as the
 *       rate readings and the sampling continuity, so it degrades badly across gaps.</li>
 *   <li><b>SOC-derived</b> — SOC delta x usable pack capacity. Always available, always
 *       monotonic, but coarse: on a 1%-resolution gauge one step is ~0.2 kWh on a small pack and
 *       ~1 kWh on a large one, and it inherits any error in the capacity figure.</li>
 * </ol>
 *
 * <p><b>Why the best source is still cross-checked.</b> The counter can be wrong in ways it does
 * not announce: it resets across BMS pause/resume sub-phases, so a long session can lose a whole
 * segment and still present a confident number. A metered figure is therefore only accepted when
 * it agrees with the SOC-derived estimate within a ratio band. Divergence means the counter is
 * broken for this session, and the coarse-but-honest estimate wins. Neither source is trusted
 * alone, because this number is priced.
 *
 * <p>The band is deliberately asymmetric-tolerant on the low side: charging losses mean metered
 * energy at the wall is HIGHER than energy into the pack, while an aged pack makes the SOC
 * estimate lower. Both are ordinary, so the band has to admit real disagreement while still
 * catching a lost segment.
 */
public final class SessionEnergyResolver {

    private static final DaemonLogger logger = DaemonLogger.getInstance("SessionEnergyResolver");

    /**
     * Floor applied to a reported SOH before it scales the SOC estimate, percent.
     *
     * <p>Matches the floor the SOH estimator already enforces on its own outputs, so the two agree
     * on what counts as an implausible health figure. Flooring rather than discarding keeps the
     * estimate continuous — see {@link #socEstimateKwh}.
     */
    public static final double MIN_PLAUSIBLE_SOH_PERCENT = 60.0;

    /**
     * Accept a metered figure when {@code metered / socEstimate} lands in {@code [LOW, HIGH]}.
     * Outside the band the counter is treated as broken for this session.
     *
     * <p>LOW is tied to {@link #MIN_PLAUSIBLE_SOH_PERCENT}, not chosen independently.
     *
     * <p>A metered figure below the SOC estimate is the signature of a pack holding less than the
     * estimate assumes — i.e. degradation. Rejecting at 0.70 while the same file (and the SOH
     * estimator) both treat 60% health as plausible was self-contradictory: a pack at 62% health
     * would have its CORRECT measured energy called "diverged". Deriving the bound from the health
     * floor keeps the two consistent, so a pack anywhere in the range the project considers credible
     * has its measurement believed.
     */
    public static final double RATIO_LOW = MIN_PLAUSIBLE_SOH_PERCENT / 100.0;
    public static final double RATIO_HIGH = 1.30;

    /**
     * At or below this ratio, a low metered figure is read as a SCALE fault rather than degradation.
     *
     * <p>Set just above an exact half so a half-scale counter is caught with a little tolerance for
     * quantisation and for the SOC estimate's own error, while staying clearly below
     * {@link #RATIO_LOW} — so the region this claims is one the health interpretation had already
     * given up on. Nothing in between is reinterpreted: a ratio of 0.65 is still treated as a
     * possibly-degraded pack whose measurement is believed.
     */
    public static final double HALF_SCALE_MAX_RATIO = 0.55;
    /**
     * Wider counter/integral contradiction admitted only after an independent register has already
     * identified the counter scale as suspect. This catches quantisation around an exact half while
     * still requiring the measured integral to be materially larger than the faulty counter.
     */
    public static final double SUSPECT_COUNTER_INTEGRAL_MAX_RATIO = RATIO_LOW;

    /** Below this, a session is noise rather than a charge. */
    public static final double MIN_SESSION_KWH = 0.05;
    /**
     * Maximum physically admissible session total.
     *
     * <p>Matches the widest charging-energy register envelope used by this app. Keeping the bound
     * here prevents finite framework sentinels such as 104857.5 from becoming billable energy when
     * an older row or accessor bypasses a lower-level domain check.
     */
    public static final double MAX_SESSION_KWH = 500.0;


    /** Where a resolved figure came from. Persisted for support, so do not rename. */
    public static final String SRC_METERED = "metered_counter";
    public static final String SRC_METERED_GAP = "metered_counter_gap";
    public static final String SRC_INTEGRATED = "integrated_rate";
    public static final String SRC_SOC = "soc_estimate";
    public static final String SRC_SOC_FALLBACK = "soc_fallback_counter_diverged";
    public static final String SRC_NONE = "none";
    /**
     * Session energy recomputed from CarSvcTelemetry's car_service (dumpsys)
     * power samples: avgPowerKw * sample-window duration. Used ONLY for the
     * currently-open session, as a deliberate bypass of the normal
     * SRC_INTEGRATED/SRC_METERED/SRC_SOC arbitration above, on platforms
     * (e.g. DiLink 5 / Sealion 7) where the normal C1-C4 HAL power cascade
     * this resolver's other inputs depend on is unavailable. See
     * SocHistoryDatabase#recomputeOpenSessionEnergyKwh.
     */
    public static final String SRC_CARSVC = "carsvc_power_time";

    /** Resolved outcome. */
    public static final class Result {
        /** Chosen energy in kWh, or NaN when nothing credible exists. */
        public final double energyKwh;
        /** One of the {@code SRC_*} constants. */
        public final String source;
        /** The SOC-derived estimate, kept for the row so both figures are inspectable. */
        public final double socEstimateKwh;
        /** True when the figure is known to be missing a segment (counter reset/saturated). */
        public final boolean incomplete;
        /**
         * The best SOC-INDEPENDENT energy figure available, kWh, or NaN.
         *
         * <p>Separate from {@link #energyKwh} on purpose. Display should use the ranked winner, but
         * SOH calibration must not: it divides energy by SOC delta, so any SOC-derived figure makes
         * SOH calibrate against itself. Since {@code SRC_SOC} outranks {@code SRC_INTEGRATED}
         * whenever a SOC estimate exists, a calibration gated on {@code source} alone would almost
         * never fire — costing counter-less trims the SOH tracking they previously had. This field
         * carries the metered or integrated value even when the SOC figure won the display.
         */
        public final double socIndependentKwh;

        Result(double energyKwh, String source, double socEstimateKwh, boolean incomplete) {
            this(energyKwh, source, socEstimateKwh, incomplete, Double.NaN);
        }

        Result(double energyKwh, String source, double socEstimateKwh, boolean incomplete,
               double socIndependentKwh) {
            this.energyKwh = energyKwh;
            this.source = source;
            this.socEstimateKwh = socEstimateKwh;
            this.incomplete = incomplete;
            this.socIndependentKwh = socIndependentKwh;
        }

        public boolean isUsable() {
            return isUsableEnergy(energyKwh);
        }

        /**
         * True when a SOC-independent figure exists and may safely calibrate SOH.
         *
         * <p>Deliberately does NOT consult {@link #incomplete}. That flag describes the COUNTER (it
         * reset, saturated, or has an unreconciled gap), whereas {@code socIndependentKwh} is only
         * ever populated with a figure already proven fit for calibration: a clean metered total, or
         * an integral that passed its own completeness check. Gating on {@code incomplete} therefore
         * discarded a perfectly good integral whenever the unrelated counter had faulted — losing a
         * calibration the previous implementation would have performed, on exactly the trims that
         * most need SOH tracking. The resolver already refuses to populate this field when the
         * figure is unfit, so re-checking a different source's health here only subtracts.
         */
        public boolean canCalibrateSoh() {
            return isUsableEnergy(socIndependentKwh);
        }
    }

    private SessionEnergyResolver() {}

    /**
     * SOC-derived energy for a session, using the USABLE capacity rather than nameplate.
     *
     * @param socDeltaPercent end SOC - start SOC, percentage points
     * @param nominalKwh      pack nominal capacity, kWh (0/NaN when unknown)
     * @param sohPercent      state of health, percent (0/NaN when unknown -> treated as 100)
     * @return kWh, or NaN when it cannot be computed
     */
    public static double socEstimateKwh(double socDeltaPercent, double nominalKwh, double sohPercent) {
        if (!Double.isFinite(socDeltaPercent) || socDeltaPercent <= 0) return Double.NaN;
        if (!Double.isFinite(nominalKwh) || nominalKwh <= 0) return Double.NaN;
        // A percent of SOC holds nominal x SOH/100, not nominal.
        //
        // A low-but-real SOH is FLOORED rather than discarded. Snapping an out-of-band value to 1.0
        // ("assume healthy") inflates the estimate by 1/SOH, and because the estimate then arbitrates
        // whether to trust the metered counter, a genuinely aged pack could see its CORRECT measured
        // energy rejected as "diverged" and the inflated estimate priced instead — at SOH 45 on a
        // 21.5 kWh pack over 80% SOC that is 17.2 kWh billed for a measured 7.74 kWh. A floor keeps
        // the estimate continuous (no doubling as SOH crosses a threshold) and errs toward believing
        // the measurement. Only a value that is not a percentage at all falls back to 1.0.
        double sohFactor = 1.0;
        if (Double.isFinite(sohPercent) && sohPercent > 0 && sohPercent <= 100.0) {
            sohFactor = Math.max(sohPercent, MIN_PLAUSIBLE_SOH_PERCENT) / 100.0;
        }
        double estimate = (socDeltaPercent / 100.0) * nominalKwh * sohFactor;
        // Preserve sub-noise estimates for wrap/gap arbitration; only the final resolver applies
        // MIN_SESSION_KWH when deciding whether a session total is displayable.
        return Double.isFinite(estimate)
                && estimate > 0.0
                && estimate <= MAX_SESSION_KWH
                ? estimate : Double.NaN;
    }

    /**
     * Resolve counter energy delivered across an unobserved continuation gap.
     *
     * <p>Both a plain delta and one full counter cycle are arithmetically possible even when the
     * current endpoint is above the previous one. The SOC estimate selects the candidate on the same
     * accounting frame; without it the accumulator's conservative smallest candidate is retained.
     */
    public static double continuationCounterEnergyKwh(
            double previousKwh, double currentKwh, double fullScaleKwh, double socEstimateKwh) {
        double[] candidates = ChargeCounterAccumulator.gapCandidatesKwh(
                previousKwh, currentKwh, fullScaleKwh);
        return ChargeCounterAccumulator.chooseCandidate(
                candidates, socEstimateKwh, RATIO_LOW, RATIO_HIGH);
    }

    /**
     * Resolve the session energy.
     *
     * @param meteredKwh    wrap-corrected counter energy, or NaN when the counter is unavailable
     * @param meteredIsGap  true when the metered figure was reconstructed across an observation
     *                      gap (daemon restart) rather than integrated live
     * @param meteredIncomplete true when the counter reset or saturated during the session
     * @param integratedKwh time-integral of the sampled rate, or NaN/0 when unavailable
     * @param socEstimate   from {@link #socEstimateKwh}, or NaN
     */
    public static Result resolve(double meteredKwh, boolean meteredIsGap, boolean meteredIncomplete,
                                 double integratedKwh, double socEstimate) {
        return resolve(meteredKwh, meteredIsGap, meteredIncomplete, integratedKwh, socEstimate, false);
    }

    /**
     * @param integratedTruncated true when the integrator had to DROP an interval (a mid-charge
     *                            daemon restart). A truncated integral is a FLOOR, not a total, so it
     *                            must never be presented as a complete figure nor calibrate SOH —
     *                            energy divided by a full SOC delta would read as false degradation.
     */
    public static Result resolve(double meteredKwh, boolean meteredIsGap, boolean meteredIncomplete,
                                 double integratedKwh, double socEstimate,
                                 boolean integratedTruncated) {
        // A caller with no scale evidence to offer gets the previous behaviour exactly: the ratio
        // tests below are self-validating and need no extra input to stay safe.
        return resolve(meteredKwh, meteredIsGap, meteredIncomplete, integratedKwh, socEstimate,
                integratedTruncated, false);
    }

    /**
     * @param counterScaleSuspect true when an independent register has indicated this counter's unit is
     *                            wrong. Decided behaviourally by {@link CounterScaleCalibrator}, whose
     *                            reference the SOH does not touch — so unlike the ratio band below it
     *                            cannot be walked out of range by the very under-reporting it exists
     *                            to catch.
     */
    public static Result resolve(double meteredKwh, boolean meteredIsGap, boolean meteredIncomplete,
                                 double integratedKwh, double socEstimate,
                                 boolean integratedTruncated,
                                 boolean counterScaleSuspect) {
        boolean meteredUsable = isUsableEnergy(meteredKwh);
        boolean socUsable = isUsableEnergy(socEstimate);
        boolean intUsable = isUsableEnergy(integratedKwh);

        // The best SOC-INDEPENDENT figure, computed once and carried on every Result regardless of
        // which figure wins the display. A gap-reconstructed metered value does NOT qualify: its
        // value was chosen by comparing candidates against the SOC estimate, so it inherits the same
        // circularity. The integral is independent of SOC by construction.
        //
        // The INTEGRAL additionally has to pass a completeness check before it may calibrate SOH.
        // It is a sum over observed samples, so a sampling gap silently TRUNCATES it — and a
        // truncated energy divided by a full SOC delta reads as a smaller pack, i.e. false
        // degradation that then persists as the calibration anchor. The SOC estimate is the only
        // independent scale available to notice that, so require rough agreement before trusting the
        // integral for calibration. (This is a calibration-only gate: the integral is still fine for
        // DISPLAY, where being low is merely unhelpful rather than wrong about pack health.)
        double socIndependent = Double.NaN;
        if (meteredUsable && !meteredIsGap && !meteredIncomplete) {
            socIndependent = meteredKwh;
        }
        // Computed once and reused: a truncated integral must not calibrate SOH from ANY path.
        // A reported truncation is decisive: it is direct knowledge that samples were dropped, whereas
        // the ratio test below is only an inference about the same thing.
        boolean integralLooksComplete = intUsable && !integratedTruncated
                && (!socUsable || ((integratedKwh / socEstimate) >= RATIO_LOW
                        && (integratedKwh / socEstimate) <= RATIO_HIGH));
        if (Double.isNaN(socIndependent) && integralLooksComplete) {
            socIndependent = integratedKwh;
        }

        // A complete measured-power integral is also an independent unit cross-check on the
        // counter. Apply it even when SOC is available: SOC moves in whole-percent quanta, so its
        // ratio can drift just above the narrow half-scale band and make the same faulty counter
        // alternate between accepted and rejected as the gauge ticks. A counter at no more than
        // roughly half of a continuous integral cannot be explained by ordinary AC/DC losses.
        if (meteredUsable && !meteredIncomplete && intUsable && !integratedTruncated) {
            double intRatio = meteredKwh / integratedKwh;
            if (intRatio <= HALF_SCALE_MAX_RATIO) {
                logger.warn(String.format(java.util.Locale.US,
                        "Metered energy %.3f kWh is %.2fx the complete integrated %.3f kWh —"
                        + " treating the counter as under-scale and using the integral",
                        meteredKwh, intRatio, integratedKwh));
                return new Result(integratedKwh, SRC_INTEGRATED, socEstimate, false,
                        integralLooksComplete ? integratedKwh : Double.NaN);
            }
        }

        // A behavioural scale verdict is stronger than agreement with the SOC estimate: the estimate
        // inherits SOH and whole-percent gauge quantisation, while the calibrator compares this counter
        // with a separate full-scale energy register. Keep the raw counter available here only so a
        // continuous measured-power integral can disprove it; never accept the suspect counter as a
        // confident total.
        if (counterScaleSuspect && meteredUsable) {
            boolean integralDisprovesCounter = intUsable && !integratedTruncated
                    && meteredKwh / integratedKwh
                            <= SUSPECT_COUNTER_INTEGRAL_MAX_RATIO;
            if (integralDisprovesCounter) {
                logger.warn(String.format(java.util.Locale.US,
                        "Suspect metered energy %.3f kWh is materially below the complete integrated"
                        + " %.3f kWh — using the measured-power integral",
                        meteredKwh, integratedKwh));
                return new Result(integratedKwh, SRC_INTEGRATED, socEstimate, false,
                        integralLooksComplete ? integratedKwh : Double.NaN);
            }
            if (socUsable) {
                logger.warn(String.format(java.util.Locale.US,
                        "Metered energy %.3f kWh comes from a counter with an independently suspect"
                        + " unit and no complete integral disproves it — using the SOC estimate",
                        meteredKwh));
                return new Result(socEstimate, SRC_SOC_FALLBACK, socEstimate, false,
                        integralLooksComplete ? integratedKwh : Double.NaN);
            }
            // No independent total can replace it. Preserve the best lower bound, but make the
            // uncertainty explicit and prevent SOH calibration.
            return new Result(meteredKwh, meteredIsGap ? SRC_METERED_GAP : SRC_METERED,
                    socEstimate, true, Double.NaN);
        }

        if (meteredUsable && !meteredIncomplete) {
            if (!socUsable) {
                // No SOC estimate — but the INTEGRAL can be a second measurement, and this counter is
                // known to run at ~half scale on some trims. A short or no-SOC-change session
                // (exactly where socUsable is false) would otherwise price a halved total with
                // nothing having questioned it.
                //
                // THE RATIO TEST IS SELF-VALIDATING, which is why it needs no independence flag. The
                // integral sums the PUBLISHED power samples, and the counter's own slope is one of the
                // sources that can win the power cascade — so on a half-scale trim the integral may
                // carry the identical fault. But then the two figures AGREE (ratio ~1.0) and this
                // branch simply does not fire. Reaching ratio <= HALF_SCALE_MAX_RATIO requires the
                // integral to be roughly twice the counter, which a contaminated integral cannot be.
                // Contamination therefore costs a MISS, never a false correction — and a miss is now
                // covered by counterScaleSuspect below, which does not depend on the samples at all.
                if (intUsable) {
                    double intRatio = meteredKwh / integratedKwh;
                    if (intRatio <= HALF_SCALE_MAX_RATIO) {
                        logger.warn(String.format(java.util.Locale.US,
                                "Metered energy %.3f kWh is %.2fx the integrated %.3f kWh with no SOC"
                                + " cross-check — treating as a half-scale counter and using the"
                                + " integral",
                                meteredKwh, intRatio, integratedKwh));
                        return new Result(integratedKwh, SRC_INTEGRATED, socEstimate,
                                integratedTruncated,
                                integralLooksComplete ? integratedKwh : Double.NaN);
                    }
                }
                // Otherwise the counter is still the best measurement we have, and refusing it here
                // would discard a real reading in favour of nothing.
                return new Result(meteredKwh, meteredIsGap ? SRC_METERED_GAP : SRC_METERED,
                        socEstimate, false, socIndependent);
            }
            double ratio = meteredKwh / socEstimate;
            if (ratio >= RATIO_LOW && ratio <= RATIO_HIGH) {
                return new Result(meteredKwh, meteredIsGap ? SRC_METERED_GAP : SRC_METERED,
                        socEstimate, false, socIndependent);
            }
            // DIVERGENCE IS ASYMMETRIC — the two directions have different explanations.
            //
            // metered ABOVE the estimate (ratio > HIGH): the counter claims more energy than the
            //   gauge can account for. A counter cannot over-read a pack, so this is a stale
            //   baseline or an over-run — the counter is the suspect, and the estimate wins.
            //
            // metered BELOW the estimate (ratio < LOW): the counter measured less than a
            //   nominal-capacity-and-SOH calculation predicts. That is exactly what a DEGRADED PACK
            //   looks like — and also what a lost counter segment looks like. Preferring the
            //   estimate here is self-perpetuating: the estimate is inflated BY the stale SOH, the
            //   inflated figure is priced, and because the counter was rejected it never calibrates
            //   the SOH that caused the rejection. A genuinely degraded pack would be overcharged
            //   forever with no path out.
            //
            // So a low reading keeps the MEASUREMENT (a counter under-reading is still a measured
            // lower bound, and it is the cheaper figure for the user), and — critically — it is
            // still offered for calibration so the SOH can move and the divergence can resolve
            // itself. A lost segment is already caught separately by meteredIncomplete.
            // HALF-SCALE IS NOT DEGRADATION. The counter accessor is known to rise at ~half the true
            // energy rate on at least one trim (the BYD half-scale getter pattern), and a half-scale
            // reading lands at ratio ~0.5 — inside the "looks like a degraded pack" region. Keeping it
            // there under-reports the session by half and prices it that way, and worse, offers it for
            // SOH calibration where it reads as ~50% pack health.
            //
            // The two cases are separable: real degradation is bounded (a pack below
            // MIN_PLAUSIBLE_SOH_PERCENT of nominal is beyond anything this fleet sees, which is why
            // RATIO_LOW is derived from it), whereas a scale fault clusters near an exact submultiple.
            // A ratio at or under the half-scale band is therefore a UNIT fault, not a health signal:
            // withhold it from both pricing and calibration rather than believe it.
            // THIS THRESHOLD ALONE IS NOT ENOUGH, because its denominator is not independent of the
            // fault. socEstimate is socDelta x nominal x SOH, and every halved session drags the SOH
            // down, which shrinks the estimate and moves the ratio UP — away from the band that would
            // have caught it. A field capture (log AL37RNJ9) read 0.58 for a counter proven by an
            // independent register to be running at exactly half: past the 0.55 band, so the session
            // was priced at half and offered for calibration, where it computed 55.8% pack health.
            // That is a ratchet, so the band is now the FALLBACK and a behavioural verdict is
            // preferred: CounterScaleCalibrator compares the counter against a register the SOH does
            // not touch, so it cannot be walked out of range by its own consequences.
            boolean looksHalfScale = ratio <= HALF_SCALE_MAX_RATIO;
            if (looksHalfScale) {
                logger.warn(String.format(java.util.Locale.US,
                        "Metered energy %.3f kWh is %.2fx the SOC estimate %.3f kWh — consistent with a"
                        + " half-scale counter rather than pack degradation; using the estimate and"
                        + " withholding this session from SOH calibration",
                        meteredKwh, ratio, socEstimate));
                return new Result(socEstimate, SRC_SOC_FALLBACK, socEstimate, false,
                        integralLooksComplete ? integratedKwh : Double.NaN);
            }
            boolean meteredBelow = ratio < RATIO_LOW;
            logger.warn(String.format(java.util.Locale.US,
                    "Metered energy %.3f kWh diverged from SOC estimate %.3f kWh (ratio %.2f) — %s",
                    meteredKwh, socEstimate, ratio,
                    meteredBelow
                        ? "keeping the measurement (a low reading is what degradation looks like;"
                          + " preferring the estimate would lock in a stale SOH)"
                        : "counter over-reads, using the SOC estimate"));
            if (meteredBelow) {
                return new Result(meteredKwh, meteredIsGap ? SRC_METERED_GAP : SRC_METERED,
                        socEstimate, false, socIndependent);
            }
            // Counter over-read: not trustworthy for THIS session, so withhold it from calibration
            // too, leaving only the integral — and only if the integral itself passes the same
            // completeness check. Passing the raw integral here would let a sampling-truncated figure
            // calibrate SOH as false degradation, which is exactly what that check exists to stop.
            return new Result(socEstimate, SRC_SOC_FALLBACK, socEstimate, false,
                    integralLooksComplete ? integratedKwh : Double.NaN);
        }

        // Counter absent, reset mid-session, or saturated. Prefer the SOC estimate over the
        // integral for DISPLAY: the integral is only trustworthy when sampling was continuous, and
        // the situations that break the counter (long parked charges, restarts) break continuity
        // too. Calibration still receives the integral via socIndependentKwh.
        if (socUsable) {
            return new Result(socEstimate, SRC_SOC, socEstimate, meteredIncomplete, socIndependent);
        }
        if (intUsable) {
            // This is the branch where a truncated integral would be PRICED with nothing else to
            // check it (no counter, no SOC). It is a floor, so flag the row incomplete — the figure is
            // still the best available, but it must not be presented as a measured total.
            return new Result(integratedKwh, SRC_INTEGRATED, socEstimate,
                    meteredIncomplete || integratedTruncated, socIndependent);
        }
        // A partial metered figure still beats nothing, as long as it is flagged incomplete.
        if (meteredUsable) {
            return new Result(meteredKwh, meteredIsGap ? SRC_METERED_GAP : SRC_METERED,
                    socEstimate, true, Double.NaN);
        }
        return new Result(Double.NaN, SRC_NONE, socEstimate, meteredIncomplete);
    }

    private static boolean isUsableEnergy(double energyKwh) {
        return Double.isFinite(energyKwh)
                && energyKwh >= MIN_SESSION_KWH
                && energyKwh <= MAX_SESSION_KWH;
    }
}

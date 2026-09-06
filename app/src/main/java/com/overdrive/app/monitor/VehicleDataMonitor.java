package com.overdrive.app.monitor;

import android.content.Context;

import com.overdrive.app.byd.BydDataCollector;
import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Singleton coordinator for BYD vehicle data.
 * 
 * Phase 3: Thin wrapper around BydDataCollector.
 * All data reads delegate to the collector. Keeps the same API surface
 * so existing consumers (HttpServer, SurveillanceIpcServer, TripDetector, etc.)
 * don't need changes.
 * 
 * The BatteryPowerMonitor is kept for AccSentryDaemon's voltage-based MCU control
 * (it needs listener callbacks for real-time voltage changes).
 */
public class VehicleDataMonitor {
    
    private static final String TAG = "VehicleDataMonitor";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    
    private static VehicleDataMonitor instance;
    private static final Object lock = new Object();
    
    // Only BatteryPowerMonitor kept — AccSentryDaemon needs its listener for voltage-based MCU control
    private final BatteryPowerMonitor batteryPowerMonitor;
    
    private final CopyOnWriteArrayList<VehicleDataListener> listeners = new CopyOnWriteArrayList<>();
    private boolean isRunning = false;
    private Context context;

    // Throttle for the charging-power resolution diagnostic (see getChargingState).
    private volatile long lastChargePowerLogMs = 0L;
    /** FINISHED epoch whose bounded taper-admission window has closed. */
    private volatile long closedTaperFinishedAtMs = 0L;

    /** A co-observed motor-bus flow is live only inside the detector's own freshness window. */
    private static final long TAPER_PACK_FLOW_FRESHNESS_MS = 15_000L;
    /**
     * Direct charge-rate observations may bridge one 90-second parked poll plus scheduler margin.
     * This ages the hardware observation, not value movement: a steady PHEV rate remains valid when
     * the collector successfully observes the same value again.
     */
    static final long CHARGING_POWER_OBSERVATION_MAX_AGE_MS = 120_000L;

    /** Compact formatter for a possibly-NaN candidate value in the diag line. */
    private static String fmt(double v) {
        return Double.isNaN(v) ? "NaN" : String.format("%.2f", v);
    }
    
    private VehicleDataMonitor() {
        this.batteryPowerMonitor = new BatteryPowerMonitor();
    }
    
    public static VehicleDataMonitor getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) instance = new VehicleDataMonitor();
            }
        }
        return instance;
    }

    // ==================== LIFECYCLE ====================
    
    public void init(Context context) {
        this.context = context;
        logger.info("Initializing VehicleDataMonitor (BydDataCollector mode)");
        
        // Only init battery power monitor (for AccSentryDaemon voltage listener)
        try {
            batteryPowerMonitor.init(context);
        } catch (Exception e) {
            logger.error("Failed to init BatteryPowerMonitor", e);
        }
        
        logger.info("Initialization complete (data from BydDataCollector)");
    }
    
    public void initBatteryPowerOnly(Context context) {
        this.context = context;
        try {
            batteryPowerMonitor.init(context);
        } catch (Exception e) {
            logger.error("Failed to init BatteryPowerMonitor", e);
        }
    }
    
    public synchronized void start() {
        if (isRunning) return;
        try { batteryPowerMonitor.start(); } catch (Exception e) { logger.error("BatteryPowerMonitor start failed", e); }
        isRunning = true;
        logger.info("VehicleDataMonitor started");
    }
    
    public synchronized void startBatteryPowerOnly() {
        if (isRunning) return;
        try { batteryPowerMonitor.start(); } catch (Exception e) { logger.error("BatteryPowerMonitor start failed", e); }
        isRunning = true;
    }
    
    public synchronized void stop() {
        if (!isRunning) return;
        try { batteryPowerMonitor.stop(); } catch (Exception ignored) {}
        isRunning = false;
        logger.info("VehicleDataMonitor stopped");
    }
    
    public synchronized void stopBatteryPowerOnly() {
        if (!isRunning) return;
        try { batteryPowerMonitor.stop(); } catch (Exception ignored) {}
        isRunning = false;
    }
    
    public boolean isRunning() { return isRunning; }
    
    // ==================== DATA ACCESS (delegates to BydDataCollector) ====================
    
    public BydVehicleData getVd() {
        try {
            BydDataCollector c = BydDataCollector.getInstance();
            return c.isInitialized() ? c.getData() : null;
        } catch (Exception e) { return null; }
    }

    /** One stable charging publication: the exact vehicle snapshot used to derive its state. */
    public static final class ChargingSnapshot {
        private final BydVehicleData vehicleData;
        private final ChargingStateData chargingState;

        private ChargingSnapshot(BydVehicleData vehicleData, ChargingStateData chargingState) {
            this.vehicleData = vehicleData;
            this.chargingState = chargingState;
        }

        public BydVehicleData getVehicleData() {
            return vehicleData;
        }

        public ChargingStateData getChargingState() {
            return chargingState;
        }
    }

    /**
     * Fence the current FINISHED epoch after its taper tail ends (or fails to appear during the
     * bounded probe window). Later stale cluster/flow callbacks cannot reopen charging presentation.
     */
    public void closeTaperAdmissionForCurrentFinishedState() {
        BydVehicleData vd = getVd();
        if (vd != null
                && vd.chargingState == ChargingStateData.CHARGING_BATTERY_STATE_CHARG_FINISH
                && vd.chargingStateAtMs > 0
                && vd.chargingStateAtMs != closedTaperFinishedAtMs) {
            try (ChargingDetector.PublicationMutation ignored =
                         ChargingDetector.beginPublicationMutation()) {
                closedTaperFinishedAtMs = vd.chargingStateAtMs;
            }
        }
    }
    
    public BatteryVoltageData getBatteryVoltage() {
        BydVehicleData vd = getVd();
        if (vd != null && vd.voltageLevelRaw != BydVehicleData.UNAVAILABLE) {
            return new BatteryVoltageData(vd.voltageLevelRaw);
        }
        return null;
    }
    
    public BatteryPowerData getBatteryPower() {
        // Try collector first, fallback to monitor (for AccSentryDaemon compatibility)
        BydVehicleData vd = getVd();
        if (vd != null && !Double.isNaN(vd.voltage12v)) {
            return new BatteryPowerData(vd.voltage12v);
        }
        return batteryPowerMonitor.getCurrentValue();
    }
    
    public BatterySocData getBatterySoc() {
        if (com.overdrive.app.byd.DiLink5Platform.isActive()) {
            try {
                double soc = com.overdrive.app.byd.CarSvcTelemetry.INSTANCE.socPercentValue();
                if (!Double.isNaN(soc) && soc >= 0 && soc <= 100) {
                    return new BatterySocData(soc);
                }
            } catch (Throwable ignored) {}
        }
        BydVehicleData vd = getVd();
        if (vd != null && !Double.isNaN(vd.socPercent)) {
            return new BatterySocData(vd.socPercent);
        }
        return null;
    }

    /**
     * Drivetrain probe — true on PHEV/HEV vehicles where {@code fuelPercent}
     * and {@code fuelRangeKm} carry real readings. Trips code uses this to
     * decide whether to populate fuel-cost fields and whether the per-trip UI
     * should render the petrol-leg breakdown.
     */
    public boolean isPhev() {
        try {
            BydDataCollector c = BydDataCollector.getInstance();
            return c != null && c.isInitialized() && c.isPhevPublic();
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Charging state derivation — fused detector.
     *
     * The "is charging?" decision is owned by {@link ChargingDetector},
     * which fuses three independent signals and two edge inputs:
     *
     *   L1. BMS state edge (chargingState == 1) via the typed
     *       AbsBYDAutoChargingListener registered in BydDataCollector.
     *   L2. BYDAutoPowerDevice.isCharging() polled once per cycle as a
     *       cross-check that catches the PHEV "BMS stuck at 15 IDLE while
     *       charging" firmware bug.
     *   L3. Power-flow inference with hysteresis and a positive AC/DC gun
     *       assertion (gun==2/3/4; V2L gun==5 is authoritative OFF).
     *   E1. ACTION_POWER_CONNECTED — biases fusion toward charging during
     *       the ramp-up window before BMS reports.
     *   E2. ACTION_POWER_DISCONNECTED — latches NOT_CHARGING until a physical
     *       reconnect, so delayed BMS/L2 callbacks cannot resurrect the session.
     *
     * This method is now a thin presentation wrapper: ask the detector
     * for the verdict, choose an effective state code (CHARGING when the
     * detector says yes, else the BMS state if known, else null), and
     * resolve power magnitude.
     *
     * Power magnitude resolution (when fused says CHARGING) is DRIVETRAIN-INDEPENDENT. The dedicated
     * charging-device signal has a fixed framework kW contract; ambiguous fallback accessors are
     * classified and scaled at runtime. The same cascade serves PHEV and BEV. Order is by DIRECTNESS:
     * chargingDevice -> cluster -> externalCharger -> energy-counter slope -> pack-side chargePower ->
     * ring estimator (flagged) -> engine power (flagged) -> nominal hint (flagged).
     *
     * <p><b>A classified rate is not necessarily the PACK's rate.</b> Classification establishes that a
     * value behaves like an instantaneous rate rather than a cumulative counter; it says nothing about
     * what that rate measures. Two documented hazards survive it, because both are numerically
     * well-behaved:
     * <ul>
     *   <li><b>EVSE-rated, not delivered.</b> getExternalChargingPower has been captured reporting a
     *       flat 7.13 kW throughout a real ~1.7 kW charge — the wallbox's RATED capacity. A constant is
     *       a perfectly valid-looking rate, so no behavioural test rejects it.</li>
     *   <li><b>AC-side, not pack-side.</b> A wall-side figure includes onboard-charger conversion loss,
     *       so it over-reads pack energy by roughly 10-15%.</li>
     * </ul>
     * Both are mitigated rather than detected: {@link #crossCheckAgainstCounter} compares the winning
     * rate against the vehicle's own charged-energy counter slope — the one kWh-grounded measurement
     * available — and flags the reading as an estimate when they disagree materially, which keeps it out
     * of the persisted curve, the cost and the outbound feeds. Where no counter exists there is no
     * independent yardstick, and a wrong-but-plausible rate cannot be distinguished from a right one.
     *
     * @return ChargingStateData populated from the fused detector, or
     *         null when no state signal is available at all.
     */
    /**
     * How far a published rate may exceed the counter-derived reference before it is demoted to an
     * estimate. The counter measures energy actually accumulated, so a rate materially above it is not
     * what the pack is taking — the documented cases being an EVSE RATED capacity (a flat 7.13 kW on a
     * ~1.7 kW charge) and an AC-side figure carrying onboard-charger conversion loss.
     *
     * <p>Asymmetric and generous. Only the HIGH side is policed, because the failure modes all
     * over-report and over-reporting is what gets over-billed; a rate below the reference is either
     * correct or conservative. 1.35 clears genuine AC/DC conversion loss (~10-15%) plus the reference's
     * own quantisation without clearing a 4x rated-capacity error.
     */
    private static final double RATE_VS_COUNTER_MAX_RATIO = 1.35;
    /** Uncorroborated high raw direct values are ambiguous on PHEV and must not reach the display. */
    private static final double PHEV_UNVERIFIED_DIRECT_MAX_KW = 22.0;
    /**
     * Largest permitted skew between the cluster rate and pack-flow observations used to verify it.
     *
     * <p>The parked collector can be sparse, so the individual observations need not be recent at
     * read time. They must, however, come from the same collection round: a prior drive's negative
     * flow must never verify a later cluster rate.
     */
    private static final long CLUSTER_PACK_FLOW_MAX_SKEW_MS = 15_000L;

    /**
     * Marker for the pack-side direct read, which is not classifier-managed but must still be
     * cross-checked for scale and accuracy. It owns a separate session-scoped divisor because it is
     * not a {@code ChargeSourceClassifier} key.
     */
    private static final String SRC_PACK_SIDE_DIRECT = "__packSideDirect";



    /**
     * Demote a rate that the vehicle's own energy counter contradicts.
     *
     * <p>Classification proves a value behaves like a rate, not that it measures pack draw. This is the
     * only independent check available for that, and it applies to every source equally — the counter
     * itself is excluded because comparing it with its own slope is vacuous.
     *
     * @return true when the reading should be treated as an estimate
     */
    private static boolean contradictedByCounter(String source, double kw) {
        return contradictedByCounter(source, kw, Double.NaN, false);
    }

    /**
     * @param packFlowKw an independent pack-side rate (magnitude) for this poll, or NaN.
     */
    private static boolean contradictedByCounter(String source, double kw, double packFlowKw,
                                                boolean phev) {
        if (Double.isNaN(kw) || kw <= 0) return false;
        if (com.overdrive.app.byd.ChargeSourceClassifier.SRC_DEVICE.equals(source)) return false;
        if (com.overdrive.app.byd.ChargeSourceClassifier.SRC_CAPACITY.equals(source)) return false;
        // This is a current, same-session observation supplied by the caller. It describes "now";
        // the counter derivative below may legally be held for minutes between quanta. Once current
        // pack flow exists, an older counter slope must not veto a candidate that agrees with it.
        if (!Double.isNaN(packFlowKw) && packFlowKw > 0.1) {
            return isMaterialRateMismatch(kw, packFlowKw);
        }
        // Bridge the parked-poll gap only while the value remains near a recent same-session
        // pack-flow proof. A stale or materially changed rate falls back to the counter gate below.
        if (ChargeRateResolver.isSessionRateCorroborated(source, kw)) return false;
        double ref = ChargeRateResolver.referenceRateKw();
        if (Double.isNaN(ref) || ref <= 0.1) {
            // NO COUNTER YARDSTICK YET. Fall back to the pack-side flow when one is available: this is
            // the window in which the documented EVSE-rated behaviour does its damage (a flat 7.13 kW
            // published against a real ~1.7 kW charge), because the counter needs minutes of charging
            // before its slope exists and the first minutes are priced too.
            if (!Double.isNaN(packFlowKw) && packFlowKw > 0.1) {
                return isMaterialRateMismatch(kw, packFlowKw);
            }
            // NEITHER YARDSTICK, ON PHEV. This is the case that cannot be resolved by any threshold:
            // the documented failure is an EVSE RATED capacity (a flat 7.13 kW throughout a real
            // ~1.7 kW charge), and 7.13 kW is a perfectly ordinary reading for a PHEV onboard charger.
            // No magnitude test separates the two — that is the same mistake as the /100 heuristic this
            // subsystem removed.
            //
            // So do not try. On PHEV, with no independent reference of any kind, the value is simply
            // NOT VERIFIED, and the honest thing is to say so rather than to guess. It still gets
            // published (blanking the card would be worse), but flagged — which keeps it out of the
            // persisted curve, the cost and the outbound feeds. The flag clears by itself the moment
            // either yardstick appears, which on a working trim is the first counter slope.
            //
            // BEV is left publishing unflagged: the EVSE-rated behaviour is documented on PHEV, and a
            // BEV DC session has no comparable observation against it.
            if (phev) return true;
            return false;
        }
        return isCandidateContradictedByReference(source, kw, ref);
    }

    /**
     * A PHEV external accessor may expose the EVSE's rated capacity rather than delivered power.
     * When its scale or accuracy is not independently proved, a kWh-grounded ring estimate is the
     * more truthful display source even though both remain excluded from priced energy.
     */
    static boolean shouldDeferExternalToGroundedEstimate(
            double externalKw, double packFlowKw, boolean phev, boolean estimateUsable) {
        if (!phev || !estimateUsable || Double.isNaN(externalKw)) return false;
        String source = com.overdrive.app.byd.ChargeSourceClassifier.SRC_EXTERNAL;
        return !ChargeRateResolver.isScaleVerified(source, externalKw, packFlowKw)
                || contradictedByCounter(source, externalKw, packFlowKw, true);
    }

    /**
     * True for the pack-side accessor's documented idle junk.
     *
     * <p>{@code getChargePower} emits ~359.x when nothing is charging on the trims where this has
     * been captured. The value is inside every plausibility bound (it looks like a legitimate DC
     * rate), so only its characteristic magnitude distinguishes it. Deliberately a NARROW window
     * rather than a range: a real charge could plausibly sit at 350 or 370 kW on a future vehicle,
     * and rejecting a genuine rate is its own defect.
     */
    private static boolean isChargePowerIdleGarbage(double kw) {
        return kw >= 359.0 && kw < 360.0;
    }

    /**
     * The instrument cluster shares the direct getter's known ~359.x idle value on PHEV/AC.
     *
     * <p>A missing gun sample must not make this known PHEV sentinel admissible. BEV DC remains
     * exempt because a future fast charger can legitimately deliver roughly 359 kW.
     */
    static boolean isClusterChargePowerIdleGarbage(double rawKw, boolean phev, int gunState) {
        boolean dcConnection = gunState == 3 || gunState == 4;
        return ChargeRateResolver.isKnownPhevRawPowerJunk(rawKw, phev)
                || (isChargePowerIdleGarbage(rawKw) && !dcConnection);
    }

    /**
     * The captured PHEV signatures and BEV/AC 359.x are always junk. BEV/DC 359.x remains a
     * candidate here, but {@link #resolveDirectChargePower} requires independent corroboration.
     */
    static boolean isDirectChargePowerIdleGarbage(double rawKw, boolean phev, int gunState) {
        boolean dcConnection = gunState == 3 || gunState == 4;
        return ChargeRateResolver.isKnownPhevRawPowerJunk(rawKw, phev)
                || (isChargePowerIdleGarbage(rawKw) && !dcConnection);
    }

    /**
     * Detect the documented half-scale session-energy counter without treating an ordinary taper
     * mismatch as a unit correction.
     *
     * <p>The ring estimator is independent here: on a BEV it prefers the full-scale remaining-energy
     * slope, and if it had fallen back to this capacity counter both values would be equal rather than
     * near a 1:2 ratio. Keep the band tight around one half so a held estimator cannot hide a normal
     * taper, and leave PHEV untouched because its estimator can be SOC-derived.
     */
    static boolean isLikelyHalfScaleCapacityRate(double capacityKw, double estimatorKw,
                                                  boolean phev) {
        if (phev || Double.isNaN(capacityKw) || Double.isNaN(estimatorKw)
                || capacityKw <= 0 || estimatorKw <= 0) {
            return false;
        }
        double ratio = capacityKw / estimatorKw;
        return ratio >= 0.45 && ratio <= 0.55;
    }

    /**
     * Keep a behaviourally-proven half-scale verdict effective when the instantaneous counter slope
     * is withheld or temporarily misses the tight 1:2 band.
     *
     * <p>The calibrator's suspicion is accumulated across the physical session from paired energy
     * registers. It therefore survives counter quantisation and remaining-energy step timing that can
     * make a one-minute slope alternate between roughly one-half and three-fifths of the reference.
     */
    static boolean shouldWithholdCapacityRate(double capacityKw, double estimatorKw,
                                              boolean phev, boolean scaleSuspect) {
        return !phev && (scaleSuspect
                || isLikelyHalfScaleCapacityRate(capacityKw, estimatorKw, false));
    }

    /**
     * A PHEV cluster rate is scale-verified when the independently sampled pack flow agrees.
     *
     * <p>The PHEV charged-energy counter is zero on some trims, so waiting for its slope leaves a
     * correct 2-7 kW cluster reading permanently display-only. Pack flow is measured on a separate
     * path and is sufficient to resolve the remaining 10x unit ambiguity when the two rates agree.
     * Keep the check symmetric: a value that is materially lower can also be unit-scaled wrong.
     */
    static boolean isPhevClusterRateCorroboratedByPackFlow(double clusterKw, long clusterAtMs,
                                                             double packFlowKw, long packFlowAtMs,
                                                             boolean phev) {
        return isPhevClusterRateCorroboratedByPackFlow(
                clusterKw, clusterAtMs, packFlowKw, packFlowAtMs, phev, 0L);
    }

    static boolean isPhevClusterRateCorroboratedByPackFlow(double clusterKw, long clusterAtMs,
                                                             double packFlowKw, long packFlowAtMs,
                                                             boolean phev,
                                                             long sessionStartedAtMs) {
        if (!phev || Double.isNaN(clusterKw) || Double.isNaN(packFlowKw)
                || clusterKw <= 0.1 || packFlowKw <= 0.1
                || clusterAtMs <= 0 || packFlowAtMs <= 0
                || (sessionStartedAtMs > 0
                    && (clusterAtMs < sessionStartedAtMs || packFlowAtMs < sessionStartedAtMs))
                || Math.abs(clusterAtMs - packFlowAtMs) > CLUSTER_PACK_FLOW_MAX_SKEW_MS) {
            return false;
        }
        double ratio = clusterKw / packFlowKw;
        return ratio >= 1.0 / RATE_VS_COUNTER_MAX_RATIO
                && ratio <= RATE_VS_COUNTER_MAX_RATIO;
    }

    static boolean hasComparablePhevPackFlow(double clusterKw, long clusterAtMs,
                                               double packFlowKw, long packFlowAtMs,
                                               boolean phev, long sessionStartedAtMs) {
        return phev && !Double.isNaN(clusterKw) && !Double.isNaN(packFlowKw)
                && clusterKw > 0.1 && packFlowKw > 0.1
                && clusterAtMs > 0 && packFlowAtMs > 0
                && (sessionStartedAtMs <= 0
                    || (clusterAtMs >= sessionStartedAtMs && packFlowAtMs >= sessionStartedAtMs))
                && Math.abs(clusterAtMs - packFlowAtMs) <= CLUSTER_PACK_FLOW_MAX_SKEW_MS;
    }

    static boolean isPhevClusterPackFlowMismatch(double clusterKw, long clusterAtMs,
                                                   double packFlowKw, long packFlowAtMs,
                                                   boolean phev, long sessionStartedAtMs) {
        return hasComparablePhevPackFlow(clusterKw, clusterAtMs, packFlowKw, packFlowAtMs,
                        phev, sessionStartedAtMs)
                && !isPhevClusterRateCorroboratedByPackFlow(
                        clusterKw, clusterAtMs, packFlowKw, packFlowAtMs,
                        phev, sessionStartedAtMs);
    }

    static boolean isCandidateContradictedByFreshPackFlow(double candidateKw,
                                                           double packFlowKw) {
        if (Double.isNaN(candidateKw) || Double.isNaN(packFlowKw)
                || candidateKw <= 0.1 || packFlowKw <= 0.1) {
            return false;
        }
        return isMaterialRateMismatch(candidateKw, packFlowKw);
    }

    static boolean isCandidateContradictedByReference(String source,
                                                       double candidateKw,
                                                       double referenceKw) {
        return !com.overdrive.app.byd.ChargeSourceClassifier.SRC_CAPACITY.equals(source)
                && !Double.isNaN(candidateKw) && candidateKw > 0.1
                && !Double.isNaN(referenceKw) && referenceKw > 0.1
                // A counter slope describes the preceding interval. It can disprove a materially
                // higher EVSE-rated value, but a lower current reading may be legitimate taper and
                // must not be replaced by the older slope.
                && candidateKw / referenceKw > RATE_VS_COUNTER_MAX_RATIO;
    }

    private static boolean isMaterialRateMismatch(double candidateKw, double referenceKw) {
        double ratio = candidateKw / referenceKw;
        return ratio < 1.0 / RATE_VS_COUNTER_MAX_RATIO
                || ratio > RATE_VS_COUNTER_MAX_RATIO;
    }

    static boolean shouldRejectCandidateBeforeSelection(String source,
                                                        double candidateKw,
                                                        double packFlowKw) {
        if (Double.isNaN(candidateKw)) return false;
        if (com.overdrive.app.byd.ChargeSourceClassifier.SRC_DEVICE.equals(source)) return false;
        if (!Double.isNaN(packFlowKw) && packFlowKw > 0.1) {
            return isCandidateContradictedByFreshPackFlow(candidateKw, packFlowKw);
        }
        double ref = ChargeRateResolver.referenceRateKw();
        // The counter is an interval average and can legitimately lag an instantaneous source during
        // a ramp. Once that source's UNIT is independently proven, keep it selectable for display;
        // the post-selection accuracy gate still marks an above-counter rate estimated, excluding it
        // from persistence, pricing, and outbound telemetry until the counter catches up.
        return !ChargeRateResolver.hasProvenUnitScale(source)
                && isCandidateContradictedByReference(source, candidateKw, ref);
    }

    /** A power value without an age is not allowed to cross a charging-session boundary. */
    static boolean isCurrentSessionPowerObservation(long observedAtMs, long sessionStartedAtMs) {
        return sessionStartedAtMs > 0 && observedAtMs >= sessionStartedAtMs;
    }

    static boolean isFreshPowerObservation(long observedAtMs, long sessionStartedAtMs,
                                           long nowMs) {
        return isCurrentSessionPowerObservation(observedAtMs, sessionStartedAtMs)
                && nowMs >= observedAtMs
                && nowMs - observedAtMs <= CHARGING_POWER_OBSERVATION_MAX_AGE_MS;
    }

    static double freshNegativeEnginePackFlow(double enginePowerKw, long enginePowerAtMs,
                                              long sessionBoundaryMs, long nowMs) {
        return !Double.isNaN(enginePowerKw) && enginePowerKw < -0.3
                && enginePowerAtMs > 0 && nowMs >= enginePowerAtMs
                && nowMs - enginePowerAtMs <= TAPER_PACK_FLOW_FRESHNESS_MS
                && isCurrentSessionPowerObservation(enginePowerAtMs, sessionBoundaryMs)
                ? Math.abs(enginePowerKw) : Double.NaN;
    }

    /**
     * Return the current pack-flow reference only when the candidate and reference were co-observed
     * in this session. The caller has already applied the pack-flow wall-clock freshness bound.
     */
    static double packFlowReferenceForSource(double raw, long rawAtMs,
                                             double packFlowKw, long packFlowAtMs,
                                             long sessionStartedAtMs) {
        return !Double.isNaN(raw) && !Double.isNaN(packFlowKw)
                && raw > 0.1 && packFlowKw > 0.1
                && rawAtMs > 0 && packFlowAtMs > 0
                && (sessionStartedAtMs <= 0
                    || (rawAtMs >= sessionStartedAtMs && packFlowAtMs >= sessionStartedAtMs))
                && Math.abs(rawAtMs - packFlowAtMs) <= CLUSTER_PACK_FLOW_MAX_SKEW_MS
                ? packFlowKw : Double.NaN;
    }

    /**
     * Resolve the direct instrument getter without inferring its unit from magnitude. A current
     * independent reference can prove either an in-band raw register (320 -> 3.2 kW) or an out-of-band
     * one (650 -> 6.5 kW); that divisor is reusable only until the current physical session ends.
     */
    static double resolveDirectChargePower(double raw, double packFlowReferenceKw) {
        if (Double.isNaN(raw) || raw <= 0.1) {
            return Double.NaN;
        }
        // 359.x is both a captured idle signature and a plausible future BEV DC rate. Drivetrain/gun
        // context establishes only possibility, not validity: require an independent same-scale
        // observation from this physical session before this exact signature can publish.
        if (isChargePowerIdleGarbage(raw)
                && !ChargeRateResolver.hasSameSessionDirectCorroboration(
                        SRC_PACK_SIDE_DIRECT, raw, packFlowReferenceKw)) {
            return Double.NaN;
        }
        return ChargeRateResolver.resolveSessionRateValue(
                SRC_PACK_SIDE_DIRECT, raw, packFlowReferenceKw);
    }

    static double selectTaperRate(double clusterKw, double deviceKw,
                                  double externalKw, double directKw) {
        return !Double.isNaN(deviceKw) ? deviceKw
                : !Double.isNaN(clusterKw) ? clusterKw
                : !Double.isNaN(externalKw) ? externalKw : directKw;
    }

    static boolean shouldWithholdUnverifiedDirectRate(double resolvedKw,
                                                      double packFlowReferenceKw,
                                                      boolean phev) {
        return phev && resolvedKw > PHEV_UNVERIFIED_DIRECT_MAX_KW
                && !ChargeRateResolver.isScaleVerified(
                        SRC_PACK_SIDE_DIRECT, resolvedKw, packFlowReferenceKw);
    }

    /**
     * Capture source scale and delivered-rate proof while both sides of a parked poll are still fresh.
     *
     * <p>Power presentation and persistence are read on independent schedules. Deferring this comparison
     * until an HTTP or 12-second sampler read made correctness depend on phase: the 15-second pack-flow
     * sample could expire before the first consumer ran, then remain unavailable for the rest of the
     * 90-second parked interval.
     */
    public static void observePhevSessionRateProofs(BydVehicleData vd,
                                                    long sessionStartedAtMs,
                                                    long nowMs) {
        if (vd == null || sessionStartedAtMs <= 0
                || Double.isNaN(vd.enginePowerKw) || vd.enginePowerKw >= -0.3
                || vd.enginePowerAtMs <= 0 || nowMs < vd.enginePowerAtMs
                || nowMs - vd.enginePowerAtMs > TAPER_PACK_FLOW_FRESHNESS_MS
                || vd.enginePowerAtMs < sessionStartedAtMs) {
            return;
        }
        double packFlowKw = Math.abs(vd.enginePowerKw);
        observePhevSessionRateProof(
                com.overdrive.app.byd.ChargeSourceClassifier.SRC_CLUSTER,
                isClusterChargePowerIdleGarbage(
                        vd.clusterChargePowerKw, true, vd.chargingGunState)
                        ? Double.NaN : vd.clusterChargePowerKw,
                vd.clusterChargePowerAtMs, packFlowKw, vd.enginePowerAtMs,
                sessionStartedAtMs);
        observePhevSessionRateProof(
                com.overdrive.app.byd.ChargeSourceClassifier.SRC_DEVICE,
                vd.chargingPowerKw,
                vd.chargingPowerAtMs,
                packFlowKw, vd.enginePowerAtMs, sessionStartedAtMs);
        observePhevSessionRateProof(
                com.overdrive.app.byd.ChargeSourceClassifier.SRC_EXTERNAL,
                ChargeRateResolver.isKnownPhevRawPowerJunk(
                        vd.externalChargingPowerKw, true)
                        ? Double.NaN : vd.externalChargingPowerKw,
                vd.externalChargingPowerAtMs,
                packFlowKw, vd.enginePowerAtMs, sessionStartedAtMs);
        observePhevSessionRateProof(
                SRC_PACK_SIDE_DIRECT,
                isDirectChargePowerIdleGarbage(
                        vd.chargePowerKw, true, vd.chargingGunState)
                        ? Double.NaN : vd.chargePowerKw,
                vd.chargePowerAtMs, packFlowKw, vd.enginePowerAtMs,
                sessionStartedAtMs);
    }

    private static void observePhevSessionRateProof(String source, double raw, long rawAtMs,
                                                    double packFlowKw, long packFlowAtMs,
                                                    long sessionStartedAtMs) {
        double reference = packFlowReferenceForSource(
                raw, rawAtMs, packFlowKw, packFlowAtMs, sessionStartedAtMs);
        if (!Double.isNaN(reference)) {
            ChargeRateResolver.resolveSessionRateValue(source, raw, reference);
        }
    }

    static boolean hasFreshPostFinishIndependentEvidence(
            long sourceObservedAtMs,
            double packFlowKw, long packFlowAtMs,
            long finishedAtMs, long nowMs, boolean phev) {
        return phev && finishedAtMs > 0
                && sourceObservedAtMs > finishedAtMs
                && packFlowAtMs > finishedAtMs
                && !Double.isNaN(packFlowKw) && packFlowKw > 0.1
                && Math.abs(sourceObservedAtMs - packFlowAtMs)
                        <= CLUSTER_PACK_FLOW_MAX_SKEW_MS
                && nowMs >= sourceObservedAtMs
                && nowMs >= packFlowAtMs
                && nowMs - packFlowAtMs <= TAPER_PACK_FLOW_FRESHNESS_MS;
    }

    static boolean hasFreshPostFinishRateMovement(
            long sourceChangedAtMs, long finishedAtMs, long nowMs) {
        return finishedAtMs > 0
                && sourceChangedAtMs > finishedAtMs
                && nowMs >= sourceChangedAtMs
                && nowMs - sourceChangedAtMs <= CHARGING_POWER_OBSERVATION_MAX_AGE_MS;
    }

    static boolean isFreshPostFinishPackFlow(double clusterKw, long clusterAtMs,
                                               double packFlowKw, long packFlowAtMs,
                                               long finishedAtMs, long nowMs,
                                               boolean phev) {
        return isPhevClusterRateCorroboratedByPackFlow(
                        clusterKw, clusterAtMs, packFlowKw, packFlowAtMs, phev, finishedAtMs)
                && nowMs >= packFlowAtMs
                && nowMs - packFlowAtMs <= TAPER_PACK_FLOW_FRESHNESS_MS;
    }

    public ChargingStateData getChargingState() {
        ChargingSnapshot snapshot = getChargingSnapshot();
        return snapshot != null ? snapshot.getChargingState() : null;
    }

    public ChargingSnapshot getChargingSnapshot() {
        ChargingDetector detector = ChargingDetector.getInstance();
        return readStableChargingComponent(detector, () -> {
            BydVehicleData vd = getVd();
            if (vd == null) return null;
            return new ChargingSnapshot(vd, buildChargingState(detector, vd));
        });
    }

    static <T> T readStableChargingComponent(
            ChargingDetector detector, java.util.function.Supplier<T> builder) {
        if (detector == null || builder == null) return null;
        if (ChargingDetector.isCurrentThreadPublicationMutationActive()) {
            return builder.get();
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            ChargingDetector.StateSnapshot before = detector.getStateSnapshot();
            ChargeRateResolver.beginEvidenceTransaction();
            try {
                T state = builder.get();
                boolean committed = detector.commitIfComponentPublicationWindowStable(
                        before, ChargeRateResolver::commitEvidenceTransaction);
                ChargeRateResolver.flushCommittedEvidenceLog();
                if (committed) return state;
            } finally {
                ChargeRateResolver.discardEvidenceTransaction();
            }
        }
        return null;
    }

    private ChargingStateData buildChargingState(
            ChargingDetector detector, BydVehicleData vd) {
        boolean exporting = vd.vtolCharging || vd.chargingGunState == 5;
        boolean fusedCharging = detector.isCharging() && !exporting;
        long sessionStartedAtMs = detector.getLastSessionStartedAtMs();

        // TAPER OVERRIDE — BMS says FINISHED but the cluster still reports a real rate through a
        // connected gun. On BYD PHEV firmware "FINISHED" means bulk-charge complete, NOT cable
        // removed: the CV taper continues and the dash keeps showing kW. Field capture
        // (log_X5RRX996): at 07:01:28 the BMS flipped to 2 at 100% SOC with gunState=2 and the
        // pack still drawing, and the detector fused ON->OFF on `l1-bms-negative`.
        //
        // Without this, relaxing the collector-side suppression of clusterChargePowerKw was
        // INERT: the whole power block below is gated on effectiveState==CHARGING, which only
        // fusedCharging can produce, so a stored cluster value could never be read in exactly the
        // scenario the relaxation targeted. Requires a live cluster reading (the collector already
        // refuses to store one on a terminal state or a disconnected/V2L gun), so this cannot
        // resurrect a finished session on its own — when the taper truly ends the cluster read
        // goes out of band, the collector stores NaN, and this override stops applying.
        boolean gunCharging = vd.chargingGunState == 2
                || vd.chargingGunState == 3 || vd.chargingGunState == 4;
        // PHEV-ONLY. clusterChargePowerKw's unit scale is a magnitude GUESS
        // (scaleClusterChargePowerKw divides anything >22 by 100), and that guess is only safe
        // where the ambiguous band is physically unreachable — i.e. below a PHEV onboard
        // charger's ~7 kW ceiling. Consuming it on BEV would let a genuine 150 kW DC session
        // read as 1.5 kW, which is squarely in-band and would satisfy this test (invariant I1 +
        // asymmetry 1). Computed here rather than reusing the cascade's own `phev` local, which
        // is not resolved until inside the power block below.
        boolean phevForTaper;
        try { phevForTaper = isPhev(); } catch (Throwable t) { phevForTaper = false; }
        // A cluster value cannot establish liveness by itself: the accessor is known to retain its
        // last positive value and small post-stop jitter is indistinguishable from a real taper.
        // Require an independently sampled, charging-direction pack flow observed after the exact
        // FINISHED transition. This is intentionally fail-closed; missing a tiny unobservable tail is
        // preferable to reopening a stopped session and billing phantom energy.
        double taperPackFlowKw = !Double.isNaN(vd.enginePowerKw) && vd.enginePowerKw < -0.3
                ? Math.abs(vd.enginePowerKw) : Double.NaN;
        // Observation freshness alone is insufficient here: retained getters and delayed callbacks
        // can repeatedly restamp an unchanged terminal value. The rate source must move materially
        // after this exact FINISHED epoch, while an independent charging-direction pack flow is live.
        boolean postFinishPackFlowObservation = vd.chargingStateAtMs > 0
                && vd.enginePowerAtMs > vd.chargingStateAtMs;
        long taperNowMs = System.currentTimeMillis();
        boolean clusterHasIndependentTaperEvidence = postFinishPackFlowObservation
                && hasFreshPostFinishRateMovement(
                        vd.clusterChargePowerChangedAtMs,
                        vd.chargingStateAtMs, taperNowMs)
                && hasFreshPostFinishIndependentEvidence(
                        vd.clusterChargePowerAtMs,
                        taperPackFlowKw, vd.enginePowerAtMs,
                        vd.chargingStateAtMs, taperNowMs, phevForTaper);
        boolean deviceHasIndependentTaperEvidence = postFinishPackFlowObservation
                && hasFreshPostFinishRateMovement(
                        vd.chargingPowerChangedAtMs,
                        vd.chargingStateAtMs, taperNowMs)
                && hasFreshPostFinishIndependentEvidence(
                        vd.chargingPowerAtMs,
                        taperPackFlowKw, vd.enginePowerAtMs,
                        vd.chargingStateAtMs, taperNowMs, phevForTaper);
        boolean externalHasIndependentTaperEvidence = postFinishPackFlowObservation
                && hasFreshPostFinishRateMovement(
                        vd.externalChargingPowerChangedAtMs,
                        vd.chargingStateAtMs, taperNowMs)
                && hasFreshPostFinishIndependentEvidence(
                        vd.externalChargingPowerAtMs,
                        taperPackFlowKw, vd.enginePowerAtMs,
                        vd.chargingStateAtMs, taperNowMs, phevForTaper);
        boolean directHasIndependentTaperEvidence = postFinishPackFlowObservation
                && hasFreshPostFinishRateMovement(
                        vd.chargePowerChangedAtMs,
                        vd.chargingStateAtMs, taperNowMs)
                && hasFreshPostFinishIndependentEvidence(
                        vd.chargePowerAtMs,
                        taperPackFlowKw, vd.enginePowerAtMs,
                        vd.chargingStateAtMs, taperNowMs, phevForTaper);
        boolean taperClusterIdleGarbage = isClusterChargePowerIdleGarbage(
                vd.clusterChargePowerKw, phevForTaper, vd.chargingGunState);
        double taperClusterKw = taperClusterIdleGarbage
                || !clusterHasIndependentTaperEvidence ? Double.NaN
                : ChargeRateResolver.rateKw(
                        com.overdrive.app.byd.ChargeSourceClassifier.SRC_CLUSTER,
                        vd.clusterChargePowerKw, taperPackFlowKw);
        double taperDeviceKw = ChargeRateResolver.isKnownPhevRawPowerJunk(
                        vd.chargingPowerKw, phevForTaper)
                || !deviceHasIndependentTaperEvidence ? Double.NaN
                : ChargeRateResolver.rateKw(
                        com.overdrive.app.byd.ChargeSourceClassifier.SRC_DEVICE,
                        vd.chargingPowerKw, taperPackFlowKw);
        double taperExternalKw = ChargeRateResolver.isKnownPhevRawPowerJunk(
                        vd.externalChargingPowerKw, phevForTaper)
                || !externalHasIndependentTaperEvidence ? Double.NaN
                : ChargeRateResolver.rateKw(
                        com.overdrive.app.byd.ChargeSourceClassifier.SRC_EXTERNAL,
                        vd.externalChargingPowerKw, taperPackFlowKw);
        boolean taperDirectIdleGarbage = isDirectChargePowerIdleGarbage(
                vd.chargePowerKw, phevForTaper, vd.chargingGunState);
        double taperDirectKw = taperDirectIdleGarbage || !directHasIndependentTaperEvidence
                ? Double.NaN
                : resolveDirectChargePower(vd.chargePowerKw, taperPackFlowKw);
        if (shouldWithholdUnverifiedDirectRate(
                taperDirectKw, taperPackFlowKw, phevForTaper)) {
            taperDirectKw = Double.NaN;
        }
        boolean clusterLive = clusterHasIndependentTaperEvidence
                && isFreshPostFinishPackFlow(
                taperClusterKw, vd.clusterChargePowerAtMs, taperPackFlowKw, vd.enginePowerAtMs,
                vd.chargingStateAtMs, taperNowMs, phevForTaper);
        boolean deviceLive = deviceHasIndependentTaperEvidence
                && isFreshPostFinishPackFlow(
                taperDeviceKw, vd.chargingPowerAtMs, taperPackFlowKw, vd.enginePowerAtMs,
                vd.chargingStateAtMs, taperNowMs, phevForTaper);
        boolean externalLive = externalHasIndependentTaperEvidence
                && isFreshPostFinishPackFlow(
                taperExternalKw, vd.externalChargingPowerAtMs, taperPackFlowKw, vd.enginePowerAtMs,
                vd.chargingStateAtMs, taperNowMs, phevForTaper);
        boolean directLive = directHasIndependentTaperEvidence
                && isFreshPostFinishPackFlow(
                taperDirectKw, vd.chargePowerAtMs, taperPackFlowKw, vd.enginePowerAtMs,
                vd.chargingStateAtMs, taperNowMs, phevForTaper);
        double taperRateKw = selectTaperRate(
                clusterLive ? taperClusterKw : Double.NaN,
                deviceLive ? taperDeviceKw : Double.NaN,
                externalLive ? taperExternalKw : Double.NaN,
                directLive ? taperDirectKw : Double.NaN);
        boolean taperCharging = !fusedCharging
                && !exporting
                && phevForTaper
                && vd.chargingState == ChargingStateData.CHARGING_BATTERY_STATE_CHARG_FINISH
                && vd.chargingStateAtMs != closedTaperFinishedAtMs
                && gunCharging
                && !Double.isNaN(taperRateKw);

        int effectiveState;
        if (exporting) {
            effectiveState = ChargingStateData.CHARGING_BATTERY_STATE_DISCHARG;
        } else if (fusedCharging) {
            effectiveState = ChargingStateData.CHARGING_BATTERY_STATE_CHARGING;
        } else if (vd.chargingState != BydVehicleData.UNAVAILABLE
                && vd.chargingState != ChargingStateData.CHARGING_BATTERY_STATE_CHARGING) {
            // Pass through whatever the BMS reports (READY, FINISHED, IDLE, error...)
            effectiveState = vd.chargingState;
        } else {
            // No trustworthy state. In particular, never bypass an authoritative detector OFF with
            // a delayed raw BMS CHARGING value.
            return null;
        }

        ChargingStateData data = new ChargingStateData(effectiveState);
        data.isTaperCharging = taperCharging;

        // ---- Power magnitude ----
        // Entered when the fused detector says CHARGING, OR during a PHEV CV taper that the BMS
        // has already called FINISHED (see taperCharging). The taper case keeps the FINISHED state
        // code — only the POWER block opens — so `full`/`plugged`/session-close all keep seeing
        // the truth (asymmetry 8: a C1 relaxation needs a matching C3 admission, but it must not
        // be bought by lying about the state).
        if (effectiveState == ChargingStateData.CHARGING_BATTERY_STATE_CHARGING || taperCharging) {
            String powerSource;  // which cascade branch won — surfaced in the diag log below
            double estKw = ChargingPowerEstimator.getInstance().estimatePowerKw();
            boolean estUsable = !Double.isNaN(estKw);
            // Drivetrain is NOT consulted for power any more. Every raw accessor's unit is decided
            // at runtime by ChargeSourceClassifier from how the value MOVES, and ChargeRateResolver
            // turns whatever it is into kW — the reading itself when the source is a RATE, its
            // slope when the source is a cumulative COUNTER, and nothing at all while the source is
            // still UNKNOWN. That makes the cascade identical on PHEV and BEV.
            //
            // The old cascade had to branch by drivetrain because each accessor's unit was a GUESS:
            // the cluster reading was divided by 100 above a threshold, which is right for one
            // firmware family and a 100x error for another, so it was confined to the drivetrain
            // that could not reach the ambiguous band. The cost was that the other drivetrain lost
            // access to the vehicle's own dash figure entirely, and a trim that reported a
            // cumulative counter had it published as an instantaneous rate.
            //
            // Order is by DIRECTNESS, not by drivetrain: the charger's own figures first, then the
            // pack-side figure, then derived estimates. Each returns NaN unless it can currently
            // express a real rate, so the cascade falls through cleanly.
            boolean phev;
            try { phev = isPhev(); } catch (Throwable t) { phev = false; }

            // Same-session pack flow is the only immediate independent rate reference on PHEV.
            // Validate candidates before applying source precedence; demoting the winner afterwards
            // left a valid lower-priority measured source (notably the capacity slope) unused.
            long powerObservationBoundaryMs = taperCharging
                    ? vd.chargingStateAtMs : sessionStartedAtMs;
            long powerResolutionNowMs = System.currentTimeMillis();
            double packFlowKw = freshNegativeEnginePackFlow(
                    vd.enginePowerKw, vd.enginePowerAtMs,
                    powerObservationBoundaryMs, powerResolutionNowMs);
            boolean clusterIdleGarbage = isClusterChargePowerIdleGarbage(
                    vd.clusterChargePowerKw, phev, vd.chargingGunState);
            boolean clusterFromCurrentSession = isFreshPowerObservation(
                    vd.clusterChargePowerAtMs, powerObservationBoundaryMs,
                    powerResolutionNowMs);
            double clusterPackFlowRef = packFlowReferenceForSource(
                    vd.clusterChargePowerKw, vd.clusterChargePowerAtMs,
                    packFlowKw, vd.enginePowerAtMs, powerObservationBoundaryMs);
            double clusterResolvedKw = clusterIdleGarbage || !clusterFromCurrentSession
                    ? Double.NaN : ChargeRateResolver.rateKw(
                        com.overdrive.app.byd.ChargeSourceClassifier.SRC_CLUSTER,
                        vd.clusterChargePowerKw, clusterPackFlowRef);
            boolean clusterPackFlowMismatch = isPhevClusterPackFlowMismatch(
                    clusterResolvedKw, vd.clusterChargePowerAtMs,
                    packFlowKw, vd.enginePowerAtMs, phev, powerObservationBoundaryMs);
            double clusterKw = clusterPackFlowMismatch
                    || shouldRejectCandidateBeforeSelection(
                            com.overdrive.app.byd.ChargeSourceClassifier.SRC_CLUSTER,
                            clusterResolvedKw, clusterPackFlowRef)
                    ? Double.NaN : clusterResolvedKw;
            boolean extFromCurrentSession = isFreshPowerObservation(
                    vd.externalChargingPowerAtMs, powerObservationBoundaryMs,
                    powerResolutionNowMs);
            boolean extIdleGarbage = ChargeRateResolver.isKnownPhevRawPowerJunk(
                    vd.externalChargingPowerKw, phev);
            double extPackFlowRef = packFlowReferenceForSource(
                    vd.externalChargingPowerKw, vd.externalChargingPowerAtMs,
                    packFlowKw, vd.enginePowerAtMs, powerObservationBoundaryMs);
            double extResolvedKw = extIdleGarbage || !extFromCurrentSession ? Double.NaN
                    : ChargeRateResolver.rateKw(
                    com.overdrive.app.byd.ChargeSourceClassifier.SRC_EXTERNAL,
                    vd.externalChargingPowerKw, extPackFlowRef);
            double extKw = shouldRejectCandidateBeforeSelection(
                    com.overdrive.app.byd.ChargeSourceClassifier.SRC_EXTERNAL,
                    extResolvedKw, extPackFlowRef) ? Double.NaN : extResolvedKw;
            boolean deferExternalToEstimator = shouldDeferExternalToGroundedEstimate(
                    extKw, extPackFlowRef, phev, estUsable);
            boolean devFromCurrentSession = isFreshPowerObservation(
                    vd.chargingPowerAtMs, powerObservationBoundaryMs,
                    powerResolutionNowMs);
            double devPackFlowRef = packFlowReferenceForSource(
                    vd.chargingPowerKw, vd.chargingPowerAtMs,
                    packFlowKw, vd.enginePowerAtMs, powerObservationBoundaryMs);
            double devResolvedKw = !devFromCurrentSession ? Double.NaN
                    : ChargeRateResolver.rateKw(
                    com.overdrive.app.byd.ChargeSourceClassifier.SRC_DEVICE,
                    vd.chargingPowerKw, devPackFlowRef);
            double devKw = shouldRejectCandidateBeforeSelection(
                    com.overdrive.app.byd.ChargeSourceClassifier.SRC_DEVICE,
                    devResolvedKw, devPackFlowRef) ? Double.NaN : devResolvedKw;
            // The per-session charged-energy counter is a known-cumulative kWh meter, so its slope
            // is a genuine measured rate. Available on trims where every rate accessor is dead.
            double capKw = ChargeRateResolver.rateKw(
                    com.overdrive.app.byd.ChargeSourceClassifier.SRC_CAPACITY, vd.chargingCapacityKwh);
            boolean capacityScaleSuspect = com.overdrive.app.charging.CounterScaleCalibrator
                    .isScaleSuspect(
                            com.overdrive.app.byd.ChargeSourceClassifier.SRC_CAPACITY);
            boolean capacityHalfScale = isLikelyHalfScaleCapacityRate(
                    capKw, estKw, phev);
            boolean capacityRateUntrusted = shouldWithholdCapacityRate(
                    capKw, estKw, phev, capacityScaleSuspect);
            boolean directFromCurrentSession = isFreshPowerObservation(
                    vd.chargePowerAtMs, powerObservationBoundaryMs,
                    powerResolutionNowMs);
            double directPackFlowRef = packFlowReferenceForSource(
                    vd.chargePowerKw, vd.chargePowerAtMs,
                    packFlowKw, vd.enginePowerAtMs, powerObservationBoundaryMs);
            double directScaleRef = ChargeRateResolver.preferredScaleReference(
                    directPackFlowRef, capKw);
            boolean directIdleGarbage = isDirectChargePowerIdleGarbage(
                    vd.chargePowerKw, phev, vd.chargingGunState);
            double directResolvedKw = directFromCurrentSession && !directIdleGarbage
                    ? resolveDirectChargePower(vd.chargePowerKw, directScaleRef)
                    : Double.NaN;
            double directKw = shouldRejectCandidateBeforeSelection(
                    SRC_PACK_SIDE_DIRECT, directResolvedKw, directPackFlowRef)
                    || shouldWithholdUnverifiedDirectRate(
                            directResolvedKw, directScaleRef, phev)
                    ? Double.NaN : directResolvedKw;
            boolean directOutranksCapacity = !Double.isNaN(directKw)
                    && (!phev || Double.isNaN(capKw) || capacityRateUntrusted);

            // A classifier-managed rate whose SCALE could not be corroborated is publishable for
            // DISPLAY but must not be integrated into priced energy: in the 2.2-22 kW band a
            // hectowatt-scaled firmware is wrong by 10x. Marking it estimated is what keeps it out of
            // the persisted curve, the cost and the outbound feeds, which all gate on isEstimated.
            String scaleSrc = null;
            double winningPackFlowRef = Double.NaN;
            if (!Double.isNaN(devKw)) {
                // Framework-defined signed kW property. Voltage/current changes trigger its callback,
                // but the framework supplies the already-normalized power value directly.
                data.updateChargingPower(
                        devKw, "chargingDevice", vd.chargingPowerAtMs,
                        ChargingStateData.PowerQuality.MEASURED, 1.0);
                powerSource = "chargingDevice";
                scaleSrc = com.overdrive.app.byd.ChargeSourceClassifier.SRC_DEVICE;
                winningPackFlowRef = devPackFlowRef;
            } else if (!Double.isNaN(clusterKw)) {
                // The instrument field is firmware-dependent and can retain a plausible idle value,
                // so it remains a guarded fallback behind the dedicated charging-device rate.
                data.updateChargingPower(
                        clusterKw, "cluster", vd.clusterChargePowerAtMs,
                        ChargingStateData.PowerQuality.MEASURED, 1.0);
                powerSource = "cluster";
                scaleSrc = com.overdrive.app.byd.ChargeSourceClassifier.SRC_CLUSTER;
                winningPackFlowRef = clusterPackFlowRef;
            } else if (!Double.isNaN(extKw) && !deferExternalToEstimator) {
                data.updateChargingPower(
                        extKw, "externalCharger",
                        vd.externalChargingPowerAtMs,
                        ChargingStateData.PowerQuality.MEASURED, 1.0);
                powerSource = "externalCharger";
                scaleSrc = com.overdrive.app.byd.ChargeSourceClassifier.SRC_EXTERNAL;
                winningPackFlowRef = extPackFlowRef;
            } else if (directOutranksCapacity) {
                // Pack-side charge rate. Not classifier-managed: it is read through a different
                // accessor that has only ever been observed as an instantaneous kW, and it is the
                // long-standing source on trims where it works, so it keeps its direct read.
                //
                // The idle-garbage filter matters because this tier is reached routinely: it sits
                // below four classifier-managed sources, all of which return NaN while their verdict
                // is still UNKNOWN. So early in a charge this is often the FIRST tier that can
                // answer — and it is the one accessor documented to emit a ~359 junk value when
                // idle, which is in-band for the 500 ceiling and would publish unflagged as 359 kW.
                data.updateChargingPower(
                        directKw, "chargePowerKw", vd.chargePowerAtMs,
                        ChargingStateData.PowerQuality.MEASURED, 1.0);
                powerSource = "chargePowerKw";
                // Subject to both scale and accuracy checks like every other measured tier. It is not
                // classifier-managed, so the sentinel below owns a separate session-scoped divisor.
                scaleSrc = SRC_PACK_SIDE_DIRECT;
                winningPackFlowRef = directScaleRef;
            } else if (!Double.isNaN(capKw) && !capacityRateUntrusted) {
                // Differentiated from the metered energy counter. Measured, not inferred.
                data.updateChargingPower(
                        capKw, "energyCounterSlope", 0L,
                        ChargingStateData.PowerQuality.MEASURED, 0.9);
                powerSource = "energyCounterSlope";
                scaleSrc = com.overdrive.app.byd.ChargeSourceClassifier.SRC_CAPACITY;
            } else if (estUsable) {
                // The generic ring estimate is flagged: a derivative of a quantised gauge is not a
                // direct charger reading. The narrowly-detected half-scale case is different: on a BEV
                // the estimator's only possible non-capacity input is the full-scale remainKwh slope,
                // and it disagrees with the capacity slope by the documented 1:2 fault signature.
                // That is an independent, kWh-grounded measured rate, so publish it to the card and
                // session curve instead of hiding a known-good 6-7 kW value as a generic estimate.
                data.updateChargingPower(
                        estKw,
                        capacityHalfScale
                                ? "remainEnergySlope(capacityHalfScale)"
                                : "ringEstimator",
                        0L,
                        capacityHalfScale
                                ? ChargingStateData.PowerQuality.MEASURED
                                : ChargingStateData.PowerQuality.ESTIMATED,
                        capacityHalfScale ? 0.9 : 0.5);
                if (!capacityHalfScale) data.isEstimated = true;
                powerSource = capacityHalfScale
                        ? "remainEnergySlope(capacityHalfScale)"
                        : "ringEstimator";
            } else if (!Double.isNaN(packFlowKw)) {
                // Current flowing into the pack on the motor bus. An INFERENCE — it is the motor's
                // own figure, not a charger-side measurement — so it is flagged and therefore
                // excluded from the persisted curve and the price.
                data.updateChargingPower(
                        packFlowKw, "enginePower", vd.enginePowerAtMs,
                        ChargingStateData.PowerQuality.ESTIMATED, 0.5);
                data.isEstimated = true;
                powerSource = "enginePower";
            } else {
                // Detector says CHARGING but nothing can express a rate yet. Show a nominal-based
                // hint so the card does not read "0 kW", flagged so it cannot be persisted or priced.
                powerSource = "none";
                try {
                    com.overdrive.app.abrp.SohEstimator soh =
                        com.overdrive.app.monitor.SocHistoryDatabase.getInstance().getSohEstimator();
                    com.overdrive.app.abrp.SohEstimator.CapacitySohSnapshot capacitySoh =
                            soh != null ? soh.getCapacitySohSnapshot() : null;
                    if (capacitySoh != null
                            && capacitySoh.getNominalCapacityKwh() > 0) {
                        double nominal = capacitySoh.getNominalCapacityKwh();
                        data.updateChargingPower(
                                nominal < 30 ? 3.3 : 7.0,
                                "nominalPlaceholder", 0L,
                                ChargingStateData.PowerQuality.ESTIMATED, 0.0);
                        data.isEstimated = true;
                        powerSource = "nominalPlaceholder";
                    }
                } catch (Exception ignored) { /* leave power at 0 */ }
            }
            boolean clusterScaleCorroborated = com.overdrive.app.byd.ChargeSourceClassifier
                    .SRC_CLUSTER.equals(scaleSrc)
                    && isPhevClusterRateCorroboratedByPackFlow(
                            data.chargingPowerKW, vd.clusterChargePowerAtMs,
                            packFlowKw, vd.enginePowerAtMs, phev, sessionStartedAtMs);
            // Scale gate on the winning classifier-managed source. The resolver publishes an
            // uncorroborated reading below its safe ceiling so AC charging is not blank on trims with
            // no kWh yardstick, but an unverified figure in that band could be 10x out — so it is
            // marked estimated here and thereby kept out of the persisted curve, the price and the
            // outbound feeds. Once any yardstick exists the flag clears by itself.
            // PHEV-contained per I1, like every sibling gate: the unit ambiguity is a documented
            // PHEV/AC observation, and on BEV no yardstick can ever appear (the pack-flow reference
            // is phev-gated), so the flag would never clear and would zero the peak, the curve and
            // the DC verdict for the whole session.
            if (scaleSrc != null && phev && !data.isEstimated
                    && !clusterScaleCorroborated
                    && !ChargeRateResolver.isScaleVerified(
                            scaleSrc, data.chargingPowerKW, winningPackFlowRef)) {
                data.isEstimated = true;
                data.updatePowerQuality(
                        ChargingStateData.PowerQuality.UNVERIFIED, 0.25);
                powerSource = powerSource + "(scaleUnverified)";
            }
            // ACCURACY gate, distinct from the SCALE gate above. A source can be in kW and still not be
            // the pack's rate — an EVSE rated capacity or an AC-side figure both are. The energy
            // counter measures what actually accumulated, so a rate materially above it is demoted to
            // an estimate rather than persisted and priced.
            if (scaleSrc != null && !data.isEstimated
                    && contradictedByCounter(
                            scaleSrc, data.chargingPowerKW, winningPackFlowRef, phev)) {
                data.isEstimated = true;
                data.updatePowerQuality(
                        ChargingStateData.PowerQuality.UNVERIFIED, 0.25);
                powerSource = powerSource + "(aboveCounterRate)";
            }
            data.powerSource = powerSource;
            // Diagnostic (throttled 1/min). Prints the resolved rate, which branch won, and BOTH
            // the raw stored value and the resolver's kW for every source alongside its classified
            // kind — so one charge log shows whether a source was refused because it is still
            // UNKNOWN, or because its slope was not yet computable, rather than leaving "why is
            // this blank" to inference. INFO so it survives a default-level capture.
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastChargePowerLogMs > 60_000L) {
                lastChargePowerLogMs = nowMs;
                logger.info(String.format(
                    "ChargingPower resolved=%.2fkW source=%s estimated=%s phev=%s | "
                    + "cluster raw=%s kw=%s kind=%s idleGarbage=%s | extChg raw=%s kw=%s kind=%s | "
                    + "chgDev raw=%s kw=%s kind=%s | energyCounter raw=%s kw=%s kind=%s "
                    + "unitFactor=%s suspect=%s | "
                    + "capacityHalfScale=%s chargePowerKw=%s engineKw=%s ringEstimator=%s",
                    data.chargingPowerKW, powerSource, data.isEstimated, phev,
                    fmt(vd.clusterChargePowerKw), fmt(clusterResolvedKw),
                    com.overdrive.app.byd.ChargeSourceClassifier.kindOf(
                        com.overdrive.app.byd.ChargeSourceClassifier.SRC_CLUSTER),
                    clusterIdleGarbage,
                    fmt(vd.externalChargingPowerKw), fmt(extResolvedKw),
                    com.overdrive.app.byd.ChargeSourceClassifier.kindOf(
                        com.overdrive.app.byd.ChargeSourceClassifier.SRC_EXTERNAL),
                    fmt(vd.chargingPowerKw), fmt(devResolvedKw),
                    com.overdrive.app.byd.ChargeSourceClassifier.kindOf(
                        com.overdrive.app.byd.ChargeSourceClassifier.SRC_DEVICE),
                    fmt(vd.chargingCapacityKwh), fmt(capKw),
                    com.overdrive.app.byd.ChargeSourceClassifier.kindOf(
                        com.overdrive.app.byd.ChargeSourceClassifier.SRC_CAPACITY),
                    // The register-width verdict and its pre-verdict suspicion. Without these a
                    // capture cannot distinguish "counter refused because its unit is wrong" from
                    // "counter had no slope yet", which was the whole difficulty in log AL37RNJ9.
                    com.overdrive.app.charging.CounterScaleCalibrator.factorFor(
                        com.overdrive.app.byd.ChargeSourceClassifier.SRC_CAPACITY),
                    com.overdrive.app.charging.CounterScaleCalibrator.isScaleSuspect(
                        com.overdrive.app.byd.ChargeSourceClassifier.SRC_CAPACITY),
                    capacityHalfScale,
                    fmt(vd.chargePowerKw), fmt(vd.enginePowerKw), fmt(estKw)));
            }
        }
        return data;
    }
    
    public DrivingRangeData getDrivingRange() {
        BydVehicleData vd = getVd();
        if (vd != null && vd.elecRangeKm != BydVehicleData.UNAVAILABLE) {
            return new DrivingRangeData(
                vd.elecRangeKm,
                vd.fuelRangeKm != BydVehicleData.UNAVAILABLE ? vd.fuelRangeKm : 0,
                vd.fuelPercent  // NaN on BEVs (BydDataCollector only sets it on PHEVs)
            );
        }
        return null;
    }

    /**
     * PHEV cumulative liquid-fuel consumption counter, in litres, straight from
     * the BYD statistic HAL ({@code getTotalFuelConValue}). This is the vehicle's
     * own metered lifetime fuel-burned accumulator — a delta between two reads
     * gives the true litres consumed over an interval, independent of tank size
     * and free of the 1%-resolution gauge quantisation.
     *
     * <p>Intentionally NOT routed through {@link #getDrivingRange()}: that helper
     * returns null whenever {@code elecRangeKm} is momentarily unavailable, which
     * would silently drop the fuel snapshot. Trip code reads this directly so the
     * accumulator capture is decoupled from the elec-range gate.
     *
     * @return cumulative litres consumed, or {@code NaN} when the HAL doesn't
     *         report it (pure BEV, or trim without the accumulator).
     */
    public double getTotalFuelCon() {
        BydVehicleData vd = getVd();
        return vd != null ? vd.totalFuelCon : Double.NaN;
    }

    /**
     * Cumulative electricity-consumption counter, in kWh, straight from the BYD
     * statistic HAL ({@code getTotalElecConValue}) — the electric twin of
     * {@link #getTotalFuelCon()}.
     *
     * <p>A delta between two reads is the metered kWh drawn over the interval.
     * This matters most on SHORT trips: {@link #getBatteryRemainPowerKwh()} is
     * derived from SoC, which is integer-resolution on this trim (~0.6 kWh on a
     * 60 kWh pack ≈ 4 km of driving), so any trip below that shows zero energy.
     * This counter keeps advancing regardless, and needs neither the SoC
     * resolution nor a pack-capacity estimate.
     *
     * <p>Read directly rather than via a composite helper so the snapshot can't
     * be dropped when an unrelated field is momentarily unavailable.
     *
     * @return cumulative kWh consumed, or {@code NaN} when the HAL doesn't
     *         report it.
     */
    public double getTotalElecCon() {
        BydVehicleData vd = getVd();
        return vd != null ? vd.totalElecCon : Double.NaN;
    }

    /**
     * The vehicle's own lifetime average petrol consumption in L/100km, from the
     * BYD statistic HAL ({@code getTotalFuelConPHMValue}).
     *
     * <p>Preferred over deriving L/100km ourselves when showing a lifetime figure:
     * it is the same number the instrument cluster shows, so the two can't
     * disagree. Per-trip consumption is still computed from the litres delta over
     * the trip's distance — this accumulator is lifetime-wide and can't answer
     * "what did THIS drive use".
     *
     * @return L/100km, or {@code NaN} when unreported (BEV, or trim without it)
     */
    public double getAvgFuelConPer100Km() {
        BydVehicleData vd = getVd();
        return vd != null ? vd.avgFuelConPer100Km : Double.NaN;
    }
    
    public BatteryThermalData getBatteryThermal() {
        BydVehicleData vd = getVd();
        if (vd != null) {
            double hi = vd.highCellTempC;
            double lo = vd.lowCellTempC;
            double avg = vd.avgCellTempC;
            if (!Double.isNaN(hi) || !Double.isNaN(lo) || !Double.isNaN(avg)) {
                return new BatteryThermalData(hi, lo, avg, System.currentTimeMillis());
            }
        }
        return null;
    }
    
    public double getBatteryRemainPowerKwh() {
        BydVehicleData vd = getVd();
        if (vd == null) return 0.0;

        double soc = Double.isNaN(vd.socPercent) ? 0 : vd.socPercent;
        double rawKwh = Double.isNaN(vd.remainKwh) ? 0 : vd.remainKwh;

        try {
            com.overdrive.app.abrp.SohEstimator soh =
                com.overdrive.app.monitor.SocHistoryDatabase.getInstance().getSohEstimator();
            com.overdrive.app.abrp.SohEstimator.CapacitySohSnapshot capacitySoh =
                    soh != null ? soh.getCapacitySohSnapshot() : null;
            if (capacitySoh != null
                    && capacitySoh.getNominalCapacityKwh() > 0 && soc > 0) {
                double nominal = capacitySoh.getNominalCapacityKwh();
                // SINGLE SOURCE OF TRUTH for remaining energy. SOH is the
                // displayed (capped ≤100, independently-anchored) value so this
                // number always agrees with the SOH chip/card. Default 100 until
                // a real measurement exists.
                double sohPercent = capacitySoh.hasDisplaySoh()
                        ? capacitySoh.getDisplaySoh() : 100.0;
                if (sohPercent <= 0) sohPercent = 100.0;
                double computedKwh = (soc / 100.0) * nominal * (sohPercent / 100.0);

                // PHEV: the BYD HAL remaining-energy getters are unreliable —
                // half-scale on some firmwares, STALE/FROZEN when the ICE is
                // running, and frame-ambiguous (no single sample can tell half
                // from gross). We therefore do NOT trust the raw getter for
                // display or accounting on PHEV: remaining is ALWAYS synthesized
                // from the reliable SOC + the user's nominal + the capped SOH.
                // This is the one value every surface (dash, MQTT, ABRP, trips,
                // history) reads, so they agree by construction and it tracks SOC
                // live — eliminating the frozen / doubled / halved / divergent
                // symptoms at the root. (The raw getter still feeds the INDEPENDENT
                // SOH anchors — capacity-Ah coulomb count, calibration — never this
                // display path, so there is no self-reference loop.)
                if (isPhev()) {
                    return computedKwh;
                }

                // BEV: getBatteryRemainPowerEV is authoritative. Trust it within a
                // plausible band (a pack can't exceed nameplate → 1.12 ceiling;
                // a degraded pack reads below → 0.5 floor); else synthesize.
                if (rawKwh > 0) {
                    double impliedCap = rawKwh / (soc / 100.0);
                    double ratio = impliedCap / nominal;
                    if (ratio < 0.5 || ratio > 1.12) {
                        return computedKwh;
                    }
                    return rawKwh;
                }

                // No raw reading: synthesize from SOC × nominal × SOH.
                return computedKwh;
            }
        } catch (Exception e) { /* fall through to raw */ }

        // SohEstimator not ready: use raw BMS value if available
        if (rawKwh > 0) return rawKwh;

        return 0.0;
    }
    
    public JSONObject getAllData() {
        JSONObject json = new JSONObject();
        BydVehicleData vd = getVd();
        
        try {
            // Battery voltage (old format for BatteryMonitor compatibility)
            if (vd != null && vd.voltageLevelRaw != BydVehicleData.UNAVAILABLE) {
                JSONObject bvJson = new JSONObject();
                bvJson.put("level", vd.voltageLevelRaw);
                bvJson.put("levelName", vd.voltageLevelRaw == 1 ? "NORMAL" : vd.voltageLevelRaw == 0 ? "LOW" : "INVALID");
                json.put("batteryVoltage", bvJson);
            }
            
            // Battery power (old format)
            if (vd != null && !Double.isNaN(vd.voltage12v)) {
                JSONObject bpJson = new JSONObject();
                bpJson.put("voltageVolts", vd.voltage12v);
                if (vd.voltage12vAtMs > 0L) {
                    bpJson.put("observedAtMs", vd.voltage12vAtMs);
                }
                bpJson.put("isWarning", vd.voltage12v < 11.5);
                bpJson.put("isCritical", vd.voltage12v < 10.5);
                bpJson.put("healthStatus", vd.voltage12v < 10.5 ? "CRITICAL" : vd.voltage12v < 11.5 ? "WARNING" : "NORMAL");
                json.put("batteryPower", bpJson);
            }
            
            // Battery SOC (old format)
            if (vd != null && !Double.isNaN(vd.socPercent)) {
                JSONObject bsJson = new JSONObject();
                bsJson.put("socPercent", vd.socPercent);
                bsJson.put("isLow", vd.socPercent < 20);
                bsJson.put("isCritical", vd.socPercent < 10);
                json.put("batterySoc", bsJson);
            }
            
            // Charging state — single source of truth via getChargingState()
            // so this JSON dump matches what SOC graph / ABRP / MQTT see. The
            // raw BMS field (vd.chargingState) is no longer surfaced standalone
            // because it's known to lag and to misreport on PHEVs.
            ChargingStateData cs = getChargingState();
            if (cs != null) {
                JSONObject csJson = new JSONObject();
                csJson.put("stateCode", cs.stateCode);
                csJson.put("stateName", cs.stateName);
                csJson.put("status", cs.status.name());
                csJson.put("isError", cs.isError);
                csJson.put("chargingPowerKW", cs.chargingPowerKW);
                csJson.put("isDischarging", cs.isDischarging);
                csJson.put("isEstimated", cs.isEstimated);
                csJson.put("powerSource", cs.powerSource);
                csJson.put("powerObservedAtMs", cs.powerObservedAtMs);
                csJson.put("powerQuality", cs.powerQuality.name());
                csJson.put("powerConfidence", cs.powerConfidence);
                json.put("chargingState", csJson);
            }
            
            // Driving range (old format)
            if (vd != null && vd.elecRangeKm != BydVehicleData.UNAVAILABLE) {
                JSONObject drJson = new JSONObject();
                drJson.put("elecRangeKm", vd.elecRangeKm);
                drJson.put("fuelRangeKm", vd.fuelRangeKm != BydVehicleData.UNAVAILABLE ? vd.fuelRangeKm : 0);
                drJson.put("totalRangeKm", vd.elecRangeKm + (vd.fuelRangeKm != BydVehicleData.UNAVAILABLE ? vd.fuelRangeKm : 0));
                json.put("drivingRange", drJson);
            }
            
            // Battery thermal (old format)
            if (vd != null && (!Double.isNaN(vd.highCellTempC) || !Double.isNaN(vd.avgCellTempC))) {
                JSONObject btJson = new JSONObject();
                if (!Double.isNaN(vd.highCellTempC)) btJson.put("highestTempC", vd.highCellTempC);
                if (!Double.isNaN(vd.lowCellTempC)) btJson.put("lowestTempC", vd.lowCellTempC);
                if (!Double.isNaN(vd.avgCellTempC)) btJson.put("averageTempC", vd.avgCellTempC);
                json.put("batteryThermal", btJson);
            }
            
            json.put("timestamp", System.currentTimeMillis());
        } catch (Exception e) {
            logger.error("Failed to create JSON", e);
        }
        
        return json;
    }
    
    public Map<String, Boolean> getAvailability() {
        Map<String, Boolean> availability = new HashMap<>();
        BydDataCollector c = BydDataCollector.getInstance();
        boolean ready = c.isInitialized();
        availability.put("batteryVoltage", ready);
        availability.put("batteryPower", ready || batteryPowerMonitor.isAvailable());
        availability.put("batterySoc", ready);
        availability.put("chargingState", ready);
        availability.put("drivingRange", ready);
        availability.put("batteryThermal", ready);
        return availability;
    }
    
    // ==================== MONITOR ACCESS (kept for backward compat) ====================
    
    public BatteryPowerMonitor getBatteryPowerMonitor() { return batteryPowerMonitor; }
    
    // These return null now — consumers should use the data access methods above
    public BatteryVoltageMonitor getBatteryVoltageMonitor() { return null; }
    public DrivingRangeMonitor getDrivingRangeMonitor() { return null; }
    
    // ==================== LISTENER MANAGEMENT ====================
    
    public void addListener(VehicleDataListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }
    
    public void removeListener(VehicleDataListener listener) {
        if (listener != null) listeners.remove(listener);
    }
    
    public void notifyBatteryVoltageChanged(BatteryVoltageData data) {
        for (VehicleDataListener l : listeners) { try { l.onBatteryVoltageChanged(data); } catch (Exception ignored) {} }
    }
    
    public void notifyBatteryPowerChanged(BatteryPowerData data) {
        for (VehicleDataListener l : listeners) { try { l.onBatteryPowerChanged(data); } catch (Exception ignored) {} }
    }
    
    public void notifyChargingStateChanged(ChargingStateData data) {
        for (VehicleDataListener l : listeners) { try { l.onChargingStateChanged(data); } catch (Exception ignored) {} }
    }
    
    public void notifyChargingPowerChanged(double powerKW) {
        for (VehicleDataListener l : listeners) { try { l.onChargingPowerChanged(powerKW); } catch (Exception ignored) {} }
    }
    
    public void notifyDataUnavailable(String monitorName, String reason) {
        for (VehicleDataListener l : listeners) { try { l.onDataUnavailable(monitorName, reason); } catch (Exception ignored) {} }
    }
}

package com.overdrive.app.abrp;

import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.BatterySocData;
import com.overdrive.app.monitor.VehicleDataMonitor;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.Properties;

/**
 * Shape B SOH estimator.
 *
 * Live SOH = 100 × (remainKwh / SOC%) ÷ nominalKwh.
 * Calibration is recorded as a SEPARATE displayed-only anchor (not blended).
 *
 * Nominal capacity precedence (read at init() and on every getStatus()):
 *   1. User-set kWh from UnifiedConfigManager.vehicle.nominalKwh
 *   2. Auto-detected kWh persisted to /data/local/tmp/abrp_soh_estimate.properties
 *   3. Auto-detection probes (BMS-Ah → SOC heuristic → model string → pack voltage)
 */
public class SohEstimator {

    private static final String TAG = "SohEstimator";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    private double nominalCapacityKwh = 0;
    private String nominalSource = "unset"; // "user" | "auto" | "unset"

    private static final String SOH_FILE = "/data/local/tmp/abrp_soh_estimate.properties";

    private static final String PROP_SOH_PERCENT = "soh_percent";
    private static final String PROP_LAST_UPDATED = "last_updated";
    private static final String PROP_NOMINAL_CAPACITY = "nominal_capacity_kwh";
    private static final String PROP_NOMINAL_SOURCE = "nominal_source";
    private static final String PROP_CALIBRATION_SOH = "calibration_soh";
    private static final String PROP_CALIBRATION_TIMESTAMP = "calibration_timestamp_ms";
    private static final String PROP_CAPACITY_AH_SOH = "capacity_ah_soh";
    private static final String PROP_CAPACITY_AH_TIMESTAMP = "capacity_ah_timestamp_ms";
    private static final String PROP_CAPACITY_AH_DISABLED = "capacity_ah_disabled";
    private static final String PROP_LIVE_HISTORY = "live_history";
    private static final String PROP_PEAK_REMAIN_KWH = "peak_remain_kwh";
    private static final String PROP_PEAK_REMAIN_KWH_SAMPLES = "peak_remain_kwh_samples";
    private static final String PROP_PEAK_REMAIN_KWH_TS = "peak_remain_kwh_ts";
    private static final String PROP_PEAK_REMAIN_KWH_NOTIFIED = "peak_remain_kwh_notified";
    private static final String PROP_SCHEMA_VERSION = "schema_version";
    // v3: PHEV peak-charge anchor — derives full-charge kWh by tracking max
    // remainKwh observed at SOC≥99%, giving a noise-robust SOH source on small
    // packs. remainKwh is gross-framed (corrected at the HAL read boundary), so
    // a healthy pack's peak ≈ gross nominal (ratio ≈ 1.0); the legacy
    // frame-mismatch flag effectively no longer fires for the old half-scale
    // reason but is retained as a guard against a genuinely wrong user nominal.
    private static final int CURRENT_SCHEMA_VERSION = 3;

    private static final int LIVE_HISTORY_SIZE = 10;
    private final java.util.ArrayDeque<Double> liveHistory = new java.util.ArrayDeque<>(LIVE_HISTORY_SIZE);

    // SOH bounds. A battery's State of Health is, by definition, capacity
    // relative to a NEW pack — it CANNOT exceed 100%. Earlier code railed at
    // 110% to allow measurement slop, but that 10% headroom let a BMS that
    // reads slightly above the user-entered nominal at full charge (e.g. 22.4
    // kWh measured vs 21.5 entered → 104%, or a stale full-charge getter held
    // while SOC dropped → railed 110%) report >100% SOH. That impossible value
    // then multiplied into BOTH the SOH display AND the synthesized remaining
    // kWh / per-trip consumption (computedKwh = SOC × nominal × SOH/100),
    // inflating every downstream number by up to 10%. Capping at 100% is the
    // physically-correct ceiling and removes that inflation: a healthy pack
    // reads exactly 100%, a degraded one reads below. The 60% floor stays —
    // genuine degradation lives below nominal. Values above the cap are clamped
    // to 100, not discarded, so a slightly-high reading still yields a usable
    // (healthy) estimate instead of none.
    private static final double MAX_SOH = 100.0;
    private static final double MIN_SOH = 60.0;

    private double currentSoh = -1;
    private double calibrationSoh = -1;
    private long calibrationTimestampMs = 0;

    // Capacity-Ah anchor — PHEV-only secondary SOH source. The BMS reports
    // its full-charge Ah counter directly via getBatteryCapacity(); on PHEVs
    // this gives a parallel SOH read that's independent of the noisier
    // remainKwh / SOC live formula. Stored as an anchor (like calibrationSoh)
    // — never blends into currentSoh, displayed separately in /api/performance/soh.
    //
    // Disabled when the BMS is observed returning the static nameplate Ah
    // for several consecutive readings (firmware not coulomb-counting).
    private double capacityAhSoh = -1;
    private long capacityAhTimestampMs = 0;
    private double lastCapacityAhReading = -1;
    private int capacityAhNameplateMatchCount = 0;
    private boolean capacityAhDisabled = false;
    private static final int CAPACITY_AH_NAMEPLATE_TRIPS = 5;
    private static final double CAPACITY_AH_NAMEPLATE_TOLERANCE_AH = 0.5;

    // SOC-coupling detector. If the BMS reports getBatteryCapacity() in
    // 0.1-kWh-remaining units (older firmware semantic), the value walks with
    // SOC instead of staying flat at the full-charge Ah counter. Track the
    // first-seen Ah reading + SOC; if subsequent ticks show |Δah| moving with
    // |Δsoc|, latch the source off — it's not coulomb-counting.
    private double capacityAhFirstSocSeen = -1;
    private double capacityAhFirstAhSeen = -1;
    private int capacityAhSocCoupledCount = 0;
    private static final int CAPACITY_AH_SOC_COUPLED_TRIPS = 3;

    // Throttling state for the SOH-rail-saturation warning. Bumped on every
    // computeLiveSoh() that hits the 60% / 110% clamp; reset when a value lands
    // inside the rails. We log once per SATURATION_WARN_PERIOD consecutive
    // saturated samples so a wrong nominal pick is surfaced without flooding.
    private int saturationStreak = 0;
    private static final int SATURATION_WARN_PERIOD = 30;

    // True when fuel signals (getFuelPercentageValue / getFuelDrivingRangeValue)
    // are at BEV sentinels. Set by autoDetectCarModel before the SOC heuristic
    // runs so we can suppress the PHEV-kWh-bug detector on real BEVs whose
    // remainKwh happens to be numerically close to SOC% by coincidence.
    private boolean fuelSignalsLookBev = false;

    // Plausible BYD pack range. Smallest BEV-side is Sealion 6 DM-i PHEV at
    // 18.3 kWh; largest is Tang at 108.8 kWh. PHEV packs whose users want to
    // enter a usable-frame value (e.g. Tang DM-i ~12.9 kWh out of 21.5 nominal)
    // need a lower floor — the BMS-reported remainKwh + display SOC live in
    // the usable frame on those models, and the live SOH formula only matches
    // when nominalCapacityKwh is in the same frame.
    private static final double MIN_PLAUSIBLE_KWH = 15.0;
    private static final double MIN_PLAUSIBLE_KWH_PHEV = 8.0;
    private static final double MAX_PLAUSIBLE_KWH = 120.0;

    // PHEV-only peak-charge frame anchor. Tracks max remainKwh observed at
    // SOC≥99% across N samples. peakRemainKwhAtFull / nominalCapacityKwh × 100
    // yields a frame-aware SOH that survives the "user enters nameplate but
    // BMS reports usable" mismatch — when ratio is < 0.85 the dialog shows
    // a frame-mismatch warning so the user can correct the input.
    private static final int PEAK_REMAIN_KWH_REQUIRED_SAMPLES = 3;
    private static final double PEAK_REMAIN_KWH_FULL_SOC_THRESHOLD = 99.0;
    private double peakRemainKwhAtFull = -1;
    private int peakRemainKwhSamples = 0;
    private long peakRemainKwhTimestampMs = 0;
    // One-shot notified flag — flipped true the first time we publish the
    // frame-mismatch notification, cleared on every event that wipes the
    // anchor (reset / clearUserNominal / nominal change). Without this the
    // notification would re-fire every daemon restart.
    private boolean peakMismatchNotified = false;

    // BYD Blade LFP reference cell voltage. 3.22 V derived from BYD's
    // published kWh / Ah / cellCount specs.
    private static final double BYD_BLADE_REFERENCE_CELL_VOLTAGE = 3.22;

    public void setNominalCapacityKwh(double capacityKwh) {
        synchronized (autoDetectLock) {
            // Drivetrain-aware floor. The flat BEV floor (15 kWh) made it IMPOSSIBLE for
            // auto-detect to land a real PHEV pack: this is the setter every auto path
            // funnels through (model table, pack-voltage estimate, BMS exact capacity), so a
            // legitimate DM-i value below 15 was rejected and a stale/wrong nominal stayed
            // in place. PHEV packs start at ~8 kWh, hence MIN_PLAUSIBLE_KWH_PHEV. On BEV the
            // strict floor is retained — there it genuinely means "this detect is junk".
            double floor = isPhevForCapacityFloor() ? MIN_PLAUSIBLE_KWH_PHEV : MIN_PLAUSIBLE_KWH;
            if (capacityKwh >= floor && capacityKwh <= MAX_PLAUSIBLE_KWH) {
                this.nominalCapacityKwh = capacityKwh;
                // Only mark "auto" if a user override isn't currently active. The
                // auto-detect path otherwise overwrites a user pick when it runs
                // after a config change.
                if (!"user".equals(nominalSource)) {
                    this.nominalSource = "auto";
                }
                logger.info("Nominal capacity set to " + capacityKwh + " KWh (source="
                    + nominalSource + ")");
                persistEstimate();
            } else {
                logger.warn("Rejecting implausible nominal capacity: " + capacityKwh
                    + " kWh (valid range: " + floor + "-" + MAX_PLAUSIBLE_KWH + ")");
            }
        }
    }

    /**
     * Drivetrain probe for the nominal-capacity floor only. PHEV packs are far smaller than
     * any BEV pack, so the plausibility floor has to differ or a valid DM-i capacity looks
     * like junk. Best-effort: an unknown drivetrain falls back to the STRICTER BEV floor, so
     * a failed probe can never widen the accepted range.
     *
     * <p>Deliberately NOT called while holding a collector lock — {@code isPhevPublic()} can
     * reach back into other subsystems (same reasoning as {@link #getDisplaySoh()}). It is
     * called under {@code autoDetectLock}, which the collector never acquires.
     */
    private static boolean isPhevForCapacityFloor() {
        try {
            com.overdrive.app.byd.BydDataCollector col =
                com.overdrive.app.byd.BydDataCollector.getInstance();
            return col != null && col.isInitialized() && col.isPhevPublic();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * User-driven override. Persists to UnifiedConfigManager so it survives
     * across daemon restarts AND across re-runs of autoDetectCarModel — only
     * clearUserNominal() can demote this back to "auto" / "unset".
     *
     * <p>The plausible floor is drivetrain-aware: BEV uses {@link #MIN_PLAUSIBLE_KWH}
     * (15 kWh), PHEV uses {@link #MIN_PLAUSIBLE_KWH_PHEV} (8 kWh) so users on
     * small Blade DM-i packs can enter usable-frame values like ~12.9 kWh.
     * The drivetrain hint is read from {@code BydDataCollector.isPhevPublic()};
     * if that probe fails the conservative BEV floor wins.
     */
    public void setNominalCapacityKwhFromUser(double capacityKwh) {
        synchronized (autoDetectLock) {
            boolean isPhev = false;
            try {
                com.overdrive.app.byd.BydDataCollector col =
                    com.overdrive.app.byd.BydDataCollector.getInstance();
                if (col != null && col.isInitialized()) {
                    isPhev = col.isPhevPublic();
                }
            } catch (Throwable ignored) { /* default isPhev=false → BEV floor */ }
            double floor = isPhev ? MIN_PLAUSIBLE_KWH_PHEV : MIN_PLAUSIBLE_KWH;
            if (capacityKwh < floor || capacityKwh > MAX_PLAUSIBLE_KWH) {
                logger.warn("Rejecting user nominal " + capacityKwh + " kWh — outside "
                    + floor + "-" + MAX_PLAUSIBLE_KWH + " range (drivetrain="
                    + (isPhev ? "PHEV" : "BEV") + ")");
                return;
            }
            double previous = this.nominalCapacityKwh;
            this.nominalCapacityKwh = capacityKwh;
            this.nominalSource = "user";
            // SOH was computed against the previous nominal. Carrying it
            // forward would make getBatteryRemainPowerKwh / SoC-fallback
            // energy math drift until live SOH re-converges. Drop it so
            // consumers see a clean slate and re-seed against the new pack.
            if (Math.abs(previous - capacityKwh) > 0.01) {
                this.currentSoh = -1;
                this.calibrationSoh = -1;
                this.calibrationTimestampMs = 0;
                // Capacity-Ah anchor was computed against the old nominal Ah —
                // carrying it forward would mismatch the new pack. Wipe along
                // with currentSoh so the next BMS Ah read re-anchors cleanly.
                this.capacityAhSoh = -1;
                this.capacityAhTimestampMs = 0;
                this.lastCapacityAhReading = -1;
                this.capacityAhNameplateMatchCount = 0;
                this.capacityAhDisabled = false;
                this.capacityAhFirstSocSeen = -1;
                this.capacityAhFirstAhSeen = -1;
                this.capacityAhSocCoupledCount = 0;
                this.liveHistory.clear();
                this.saturationStreak = 0;
                invalidateActiveTripKwhBaseline("user nominal changed " +
                    String.format("%.1f", previous) + "→" + String.format("%.1f", capacityKwh) + " kWh");
            }
            try {
                UnifiedConfigManager.updateValues("vehicle",
                    java.util.Collections.singletonMap("nominalKwh", (Object) capacityKwh));
            } catch (Throwable t) {
                logger.warn("Failed to persist user nominalKwh to UnifiedConfig: " + t.getMessage());
            }
            persistEstimate();
            logger.info("User-set nominal capacity: " + capacityKwh + " kWh");
            try {
                seedInitialEstimate();
            } catch (Throwable t) {
                logger.debug("seedInitialEstimate after user override failed: " + t.getMessage());
            }
        }
    }

    /**
     * Clear the user override. Drops the unified-config key and re-runs
     * auto-detection so the estimate falls back to whichever pack we can
     * identify from BMS / SOC / model / voltage.
     */
    public void clearUserNominal() {
        synchronized (autoDetectLock) {
            try {
                JSONObject vehicle = UnifiedConfigManager.getVehicle();
                if (vehicle.has("nominalKwh")) {
                    vehicle.remove("nominalKwh");
                    UnifiedConfigManager.setVehicle(vehicle);
                }
            } catch (Throwable t) {
                logger.warn("Failed to clear user nominalKwh: " + t.getMessage());
            }
            double previous = this.nominalCapacityKwh;
            this.nominalCapacityKwh = 0;
            this.nominalSource = "unset";
            this.currentSoh = -1;
            this.calibrationSoh = -1;
            this.calibrationTimestampMs = 0;
            this.capacityAhSoh = -1;
            this.capacityAhTimestampMs = 0;
            this.lastCapacityAhReading = -1;
            this.capacityAhNameplateMatchCount = 0;
            this.capacityAhDisabled = false;
            this.capacityAhFirstSocSeen = -1;
            this.capacityAhFirstAhSeen = -1;
            this.capacityAhSocCoupledCount = 0;
            this.liveHistory.clear();
            this.saturationStreak = 0;
            // Peak frame anchor is an empirical BMS observation, independent
            // of nominal — but clearUserNominal is a deliberate "start over"
            // entrypoint, so wipe it too to match user expectation.
            this.peakRemainKwhAtFull = -1;
            this.peakRemainKwhSamples = 0;
            this.peakRemainKwhTimestampMs = 0;
            this.peakMismatchNotified = false;
            if (previous > 0) {
                invalidateActiveTripKwhBaseline("user nominal cleared (was "
                    + String.format("%.1f", previous) + " kWh)");
            }
            // persistEstimate() early-returns when both currentSoh and nominalCapacityKwh
            // are <= 0, so the stale keys would survive on disk. Strip them explicitly.
            try {
                File f = new File(SOH_FILE);
                if (f.exists()) {
                    Properties p = new Properties();
                    try (FileInputStream fis = new FileInputStream(f)) { p.load(fis); }
                    p.remove(PROP_NOMINAL_CAPACITY);
                    p.remove(PROP_NOMINAL_SOURCE);
                    try (FileOutputStream fos = new FileOutputStream(f)) { p.store(fos, "ABRP SOH Estimate"); }
                }
            } catch (Exception ignored) {}
            persistEstimate();
            try {
                android.content.Context ctx = com.overdrive.app.daemon.CameraDaemon.getAppContext();
                autoDetectCarModel(ctx);
            } catch (Throwable t) {
                logger.warn("Re-detect after clearUserNominal failed: " + t.getMessage());
            }
        }
    }

    public double getNominalCapacityKwh() {
        return nominalCapacityKwh;
    }

    public String getNominalSource() {
        return nominalSource;
    }

    /**
     * Detect capacity from pack voltage (called by BydDataCollector on first HV voltage event).
     * Skips entirely when the user has set a nominal explicitly OR a value has
     * already been detected — pack voltage is the least reliable source.
     */
    public void autoDetectFromPackVoltage(double packVoltage, BydVehicleData vd) {
        if (packVoltage < 200 || packVoltage > 900) return;
        if ("user".equals(nominalSource)) return;
        if (nominalCapacityKwh > 0) {
            logger.debug("Pack voltage " + String.format("%.1f", packVoltage) +
                "V ignored — capacity already detected: " + nominalCapacityKwh + " kWh");
            return;
        }
        double cellVoltage = 3.2;
        int cellCount = (int) Math.round(packVoltage / cellVoltage);
        double capacity = mapCellCountToCapacity(cellCount);
        if (capacity > 0) {
            setNominalCapacityKwh(capacity);
            logger.info("Pack Voltage Capacity: " + capacity + " kWh (voltage=" +
                String.format("%.1f", packVoltage) + "V, nominal cellV=3.2V" +
                ", cells≈" + cellCount + "s)");
        } else {
            logger.debug("Pack voltage " + String.format("%.1f", packVoltage) + "V → " +
                cellCount + " cells — no matching BYD pack");
        }
    }

    // ==================== AUTO-DETECT ====================

    private static boolean isSentinelInt(int v) {
        return v == 255 || v == 254
            || v == 511 || v == 1023
            || v == 2046 || v == 2047
            || v == 4095
            || v == 65534 || v == 65535;
    }
    private static boolean isSentinelInt(Object o) {
        return (o instanceof Number) && isSentinelInt(((Number) o).intValue());
    }

    private static String describeException(Throwable e) {
        if (e == null) return "null";
        String msg = e.getMessage();
        if (msg != null && !msg.trim().isEmpty()) {
            return e.getClass().getSimpleName() + ": " + msg;
        }
        Throwable cause = e.getCause();
        if (cause != null && cause.getMessage() != null && !cause.getMessage().trim().isEmpty()) {
            return e.getClass().getSimpleName() + " (cause: "
                + cause.getClass().getSimpleName() + ": " + cause.getMessage() + ")";
        }
        return e.getClass().getSimpleName() + " (no message)";
    }

    private void dumpPhevDiagnostics(android.content.Context context) {
        fuelSignalsLookBev = false;
        boolean fuelPctSentinel = false;
        boolean fuelRangeSentinel = false;
        boolean fuelPctProbed = false;
        boolean fuelRangeProbed = false;
        try {
            logger.info("=== POWERTRAIN DIAGNOSTICS ===");

            try {
                String model = (String) Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class)
                    .invoke(null, "ro.product.model", "");
                logger.info("[diag] ro.product.model = \"" + model + "\"");
            } catch (Exception e) {
                logger.info("[diag] ro.product.model: failed (" + describeException(e) + ")");
            }

            if (context == null) {
                logger.info("[diag] context==null — skipping HAL probes");
                logger.info("=== POWERTRAIN DIAGNOSTICS END ===");
                return;
            }

            try {
                Class<?> energyCls = Class.forName("android.hardware.bydauto.energy.BYDAutoEnergyDevice");
                Object energyDev = energyCls.getMethod("getInstance", android.content.Context.class)
                    .invoke(null, context);
                if (energyDev != null) {
                    try {
                        Object em = energyCls.getMethod("getEnergyMode").invoke(energyDev);
                        String hint;
                        if (Integer.valueOf(1).equals(em)) hint = " (commonly EV — not authoritative)";
                        else if (Integer.valueOf(3).equals(em)) hint = " (commonly HEV — not authoritative; observed on BEV too)";
                        else hint = " (unknown code)";
                        logger.info("[diag] BYDAutoEnergyDevice.getEnergyMode = " + em + hint);
                    } catch (Exception e) {
                        logger.info("[diag] getEnergyMode failed: " + describeException(e));
                    }
                    try {
                        Object om = energyCls.getMethod("getOperationMode").invoke(energyDev);
                        logger.info("[diag] BYDAutoEnergyDevice.getOperationMode = " + om);
                    } catch (Exception e) {
                        logger.info("[diag] getOperationMode failed: " + describeException(e));
                    }
                } else {
                    logger.info("[diag] BYDAutoEnergyDevice getInstance returned null");
                }
            } catch (ClassNotFoundException e) {
                logger.info("[diag] BYDAutoEnergyDevice not on this firmware");
            } catch (Exception e) {
                logger.info("[diag] BYDAutoEnergyDevice probe failed: " + describeException(e));
            }

            try {
                Class<?> chargingCls = Class.forName("android.hardware.bydauto.charging.BYDAutoChargingDevice");
                Object chargingDev = chargingCls.getMethod("getInstance", android.content.Context.class)
                    .invoke(null, context);
                if (chargingDev != null) {
                    try {
                        Object cc = chargingCls.getMethod("getChargingCapacity").invoke(chargingDev);
                        logger.info("[diag] BYDAutoChargingDevice.getChargingCapacity = " + cc
                            + " (not used — observed 0.0 on every probed vehicle)");
                    } catch (Exception e) {
                        logger.info("[diag] getChargingCapacity failed: " + describeException(e));
                    }
                    try {
                        Object ct = chargingCls.getMethod("getChargingType").invoke(chargingDev);
                        logger.info("[diag] BYDAutoChargingDevice.getChargingType = " + ct);
                    } catch (Exception e) {
                        logger.info("[diag] getChargingType failed: " + describeException(e));
                    }
                } else {
                    logger.info("[diag] BYDAutoChargingDevice getInstance returned null");
                }
            } catch (ClassNotFoundException e) {
                logger.info("[diag] BYDAutoChargingDevice not on this firmware");
            } catch (Exception e) {
                logger.info("[diag] BYDAutoChargingDevice probe failed: " + describeException(e));
            }

            try {
                Class<?> statCls = Class.forName("android.hardware.bydauto.statistic.BYDAutoStatisticDevice");
                Object statDev = statCls.getMethod("getInstance", android.content.Context.class)
                    .invoke(null, context);
                if (statDev != null) {
                    try {
                        Object fp = statCls.getMethod("getFuelPercentageValue").invoke(statDev);
                        fuelPctProbed = true;
                        String hint;
                        if (isSentinelInt(fp)) {
                            hint = " (sentinel — BEV / fuel unavailable)";
                            fuelPctSentinel = true;
                        } else if (fp instanceof Number) {
                            int v = ((Number) fp).intValue();
                            if (v >= 0 && v <= 100) hint = " (in 0..100 range — PHEV fuel level)";
                            else hint = " (out of expected 0..100 range — ignore)";
                        } else hint = "";
                        logger.info("[diag] BYDAutoStatisticDevice.getFuelPercentageValue = " + fp + hint);
                    } catch (Exception e) {
                        logger.info("[diag] getFuelPercentageValue failed: " + describeException(e));
                    }
                    try {
                        Object fr = statCls.getMethod("getFuelDrivingRangeValue").invoke(statDev);
                        fuelRangeProbed = true;
                        String hint;
                        if (isSentinelInt(fr)) {
                            hint = " (sentinel — BEV / range unavailable)";
                            fuelRangeSentinel = true;
                        } else if (fr instanceof Number) {
                            int v = ((Number) fr).intValue();
                            if (v > 0 && v < 1500) hint = " km (real PHEV fuel range)";
                            else hint = " (out of expected 0..1500 km range)";
                        } else hint = "";
                        logger.info("[diag] BYDAutoStatisticDevice.getFuelDrivingRangeValue = " + fr + hint);
                    } catch (Exception e) {
                        logger.info("[diag] getFuelDrivingRangeValue failed: " + describeException(e));
                    }
                    try {
                        Object sohi = statCls.getMethod("getStatisticBatteryHealthyIndex").invoke(statDev);
                        logger.info("[diag] BYDAutoStatisticDevice.getStatisticBatteryHealthyIndex = " + sohi);
                    } catch (Exception e) {
                        logger.info("[diag] getStatisticBatteryHealthyIndex failed: " + describeException(e));
                    }
                    try {
                        Object remPwr = statCls.getMethod("getRemainingBatteryPower").invoke(statDev);
                        logger.info("[diag] BYDAutoStatisticDevice.getRemainingBatteryPower = " + remPwr
                            + " (raw — divide by 10 if reported in 0.1 kWh units)");
                    } catch (Exception e) {
                        logger.info("[diag] getRemainingBatteryPower failed: " + describeException(e));
                    }
                } else {
                    logger.info("[diag] BYDAutoStatisticDevice getInstance returned null");
                }
            } catch (ClassNotFoundException e) {
                logger.info("[diag] BYDAutoStatisticDevice not on this firmware");
            } catch (Exception e) {
                logger.info("[diag] BYDAutoStatisticDevice probe failed: " + describeException(e));
            }

            try {
                Class<?> bodyCls = Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice");
                Object bodyDev = bodyCls.getMethod("getInstance", android.content.Context.class)
                    .invoke(null, context);
                if (bodyDev != null) {
                    try {
                        Object cap = bodyCls.getMethod("getBatteryCapacity").invoke(bodyDev);
                        int rawCap = (cap instanceof Number) ? ((Number) cap).intValue() : -1;
                        String semHint;
                        if (isSentinelInt(rawCap)) semHint = " (sentinel — unavailable)";
                        else if (rawCap >= 50 && rawCap <= 350) semHint = " (likely Ah rating)";
                        else if (rawCap > 350 && rawCap < 60000) semHint = " (likely 0.1 kWh units → " + (rawCap / 10.0) + " kWh)";
                        else semHint = " (unknown semantics)";
                        logger.info("[diag] BYDAutoBodyworkDevice.getBatteryCapacity = " + cap + semHint);
                    } catch (Exception e) {
                        logger.info("[diag] getBatteryCapacity failed: " + describeException(e));
                    }
                    // getBatteryPowerHEV is the PHEV-priority remainKwh source in
                    // collectBodywork — yet it was the ONE battery getter this dump
                    // never logged. Field reports show it reading ~half the true
                    // remaining energy at full charge (9.1 on an 18.3 kWh pack,
                    // ratio≈0.497, constant across pack sizes). Log it raw next to
                    // SOC so a single ACC-on capture at a known SOC confirms whether
                    // the halving is in this getter specifically and whether it is a
                    // clean 2.0× vs a per-string / tenths artifact.
                    try {
                        Object hev = bodyCls.getMethod("getBatteryPowerHEV").invoke(bodyDev);
                        String hevHint = "";
                        if (hev instanceof Number) {
                            double hevVal = ((Number) hev).doubleValue();
                            try {
                                VehicleDataMonitor vdm = VehicleDataMonitor.getInstance();
                                BatterySocData sd = vdm != null ? vdm.getBatterySoc() : null;
                                if (sd != null && sd.socPercent > 0) {
                                    double impliedFull = hevVal / (sd.socPercent / 100.0);
                                    hevHint = " (raw kWh; soc=" + String.format("%.0f", sd.socPercent)
                                        + "% → impliedFull=" + String.format("%.1f", impliedFull)
                                        + " kWh, ×2=" + String.format("%.1f", hevVal * 2)
                                        + "; compare to gross nominal " + nominalCapacityKwh + " kWh)";
                                }
                            } catch (Exception ignored) {}
                        }
                        logger.info("[diag] BYDAutoBodyworkDevice.getBatteryPowerHEV = " + hev + hevHint);
                    } catch (Exception e) {
                        logger.info("[diag] getBatteryPowerHEV failed: " + describeException(e));
                    }
                }
            } catch (ClassNotFoundException e) {
                logger.info("[diag] BYDAutoBodyworkDevice not on this firmware");
            } catch (Exception e) {
                logger.info("[diag] BYDAutoBodyworkDevice probe failed: " + describeException(e));
            }

            try {
                Class<?> pwrCls = Class.forName("android.hardware.bydauto.power.BYDAutoPowerDevice");
                Object pwrDev = pwrCls.getMethod("getInstance", android.content.Context.class)
                    .invoke(null, context);
                if (pwrDev != null) {
                    try {
                        Object rp = pwrCls.getMethod("getBatteryRemainPowerEV").invoke(pwrDev);
                        logger.info("[diag] BYDAutoPowerDevice.getBatteryRemainPowerEV = " + rp);
                    } catch (Exception e) {
                        logger.info("[diag] getBatteryRemainPowerEV failed: " + describeException(e));
                    }
                }
            } catch (ClassNotFoundException e) {
                logger.info("[diag] BYDAutoPowerDevice not on this firmware");
            } catch (Exception e) {
                logger.info("[diag] BYDAutoPowerDevice probe failed: " + describeException(e));
            }

            try {
                VehicleDataMonitor vdm = VehicleDataMonitor.getInstance();
                BydVehicleData vd = vdm != null ? vdm.getVd() : null;
                BatterySocData socData = vdm != null ? vdm.getBatterySoc() : null;
                double remKwh = vdm != null ? vdm.getBatteryRemainPowerKwh() : 0;
                logger.info("[diag] internal: socPercent="
                    + (socData != null ? socData.socPercent : "null")
                    + ", getBatteryRemainPowerKwh=" + remKwh
                    + ", vd.remainKwh=" + (vd != null ? vd.remainKwh : "null")
                    + ", vd.hvPackVoltage=" + (vd != null ? vd.hvPackVoltage : "null")
                    + ", vd.fuelPercent=" + (vd != null ? vd.fuelPercent : "null")
                    + ", currentNominalKwh=" + nominalCapacityKwh);
            } catch (Exception e) {
                logger.info("[diag] internal snapshot probe failed: " + describeException(e));
            }

            if (fuelPctProbed && fuelRangeProbed && fuelPctSentinel && fuelRangeSentinel) {
                fuelSignalsLookBev = true;
                logger.info("[diag] Inferred drivetrain: BEV (both fuel signals at sentinel — getEnergyType ignored)");
            }

            logger.info("=== POWERTRAIN DIAGNOSTICS END ===");
        } catch (Throwable t) {
            logger.warn("dumpPhevDiagnostics: unexpected error: " + describeException(t));
        }
    }

    private final Object autoDetectLock = new Object();

    public void autoDetectCarModel(android.content.Context context) {
        synchronized (autoDetectLock) {
            autoDetectCarModelInternal(context);
        }
    }

    private void autoDetectCarModelInternal(android.content.Context context) {
        // User override always wins — never let auto-detect demote it.
        if ("user".equals(nominalSource) && nominalCapacityKwh > 0) {
            logger.info("autoDetectCarModel skipped — user override active ("
                + nominalCapacityKwh + " kWh)");
            return;
        }

        // User-selected vehicle model (set via the model picker) maps to a
        // canonical pack capacity in the manifest. This sits between the
        // explicit user kWh override (above) and the SOC heuristic (below)
        // — it's stronger than heuristics because the user told us which
        // car they have, but weaker than an explicit kWh value because
        // model variants exist (Seal Standard 61.4 kWh vs Premium 82.5 kWh).
        try {
            double modelKwh = readModelNominalFromManifest();
            if (modelKwh >= MIN_PLAUSIBLE_KWH && modelKwh <= MAX_PLAUSIBLE_KWH) {
                nominalCapacityKwh = modelKwh;
                nominalSource = "user_model";
                logger.info("autoDetectCarModel: nominal " + modelKwh
                    + " kWh from user-selected model");
                return;
            }
        } catch (Throwable t) {
            logger.debug("Model-manifest nominalKwh lookup failed: " + t.getMessage());
        }

        if (context == null) {
            try {
                context = com.overdrive.app.daemon.CameraDaemon.getAppContext();
                if (context != null) {
                    logger.warn("autoDetectCarModel called with null context — recovered via CameraDaemon.getAppContext()");
                } else {
                    logger.warn("autoDetectCarModel: null context AND no app context available — HAL probes will be skipped");
                }
            } catch (Exception e) {
                logger.warn("autoDetectCarModel: failed to recover null context: " + describeException(e));
            }
        }

        dumpPhevDiagnostics(context);

        if (context != null) {
            double exactKwh = tryBmsExactCapacity(context);
            if (exactKwh > 0 && !contradictedBySocRatio(exactKwh)) {
                setNominalCapacityKwh(exactKwh);
                return;
            }
        }

        try {
            VehicleDataMonitor vdm = VehicleDataMonitor.getInstance();
            double remainingKwh = vdm.getBatteryRemainPowerKwh();
            BatterySocData socData = vdm.getBatterySoc();
            if (remainingKwh > 1.5 && socData != null && socData.socPercent >= 10) {
                double estimatedCapacity = remainingKwh / (socData.socPercent / 100.0);
                boolean likelyPhevKwhBug = !fuelSignalsLookBev
                        && Math.abs(remainingKwh - socData.socPercent) < 5.0;
                if (!fuelSignalsLookBev && !likelyPhevKwhBug
                        && remainingKwh > 40 && estimatedCapacity > 40
                        && nominalCapacityKwh <= 0) {
                    double socKwhRatio = remainingKwh / socData.socPercent;
                    if (socKwhRatio > 0.85 && socKwhRatio < 1.15) {
                        likelyPhevKwhBug = true;
                    }
                }
                if (likelyPhevKwhBug) {
                    logger.info("SOC heuristic skipped: remainKwh (" +
                        String.format("%.1f", remainingKwh) + ") ≈ socPercent (" +
                        String.format("%.1f", socData.socPercent) + ") — likely SOC-as-kWh firmware bug");
                } else if (nominalCapacityKwh > 0 && nominalCapacityKwh < 30 && estimatedCapacity > 40) {
                    logger.info("SOC heuristic skipped: estimated " + String.format("%.1f", estimatedCapacity) +
                        " kWh but nominal already detected as " + String.format("%.1f", nominalCapacityKwh) +
                        " kWh — PHEV remainKwh unreliable");
                } else {
                    double packV = Double.NaN;
                    BydVehicleData vd = vdm.getVd();
                    if (vd != null && !Double.isNaN(vd.hvPackVoltage)
                            && vd.hvPackVoltage > 200) {
                        packV = vd.hvPackVoltage;
                    }
                    double matched = matchNearestCapacity(
                        estimatedCapacity, packV, socData.socPercent);
                    if (matched > 0) {
                        setNominalCapacityKwh(matched);
                        double snapDelta = Math.abs(estimatedCapacity - matched);
                        boolean snapped = snapDelta > 0.5;
                        logger.info("SOC-derived nominal capacity: " + matched + " kWh"
                            + (snapped
                                ? " (estimated " + String.format("%.1f", estimatedCapacity)
                                  + " kWh, snapped to nearest known pack)"
                                : "")
                            + " [SOC=" + String.format("%.1f", socData.socPercent) + "%, remain="
                            + String.format("%.1f", remainingKwh) + " kWh]");
                        return;
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("SOC heuristic failed: " + e.getMessage());
        }

        try {
            String carType = (String) Class.forName("android.os.SystemProperties")
                .getMethod("get", String.class, String.class)
                .invoke(null, "ro.product.model", "");
            if (carType != null && !carType.isEmpty()) {
                double mapped = mapCarTypeToCapacity(carType);
                if (mapped > 0) {
                    setNominalCapacityKwh(mapped);
                    logger.info("Model-Mapped Capacity (" + carType + "): " + mapped + " kWh");
                    return;
                }
            }
        } catch (Exception e) { /* ignore */ }

        if (context != null) {
            double fuzzyKwh = tryBmsFuzzyCapacity(context);
            if (fuzzyKwh > 0 && !contradictedBySocRatio(fuzzyKwh)) {
                setNominalCapacityKwh(fuzzyKwh);
                return;
            }
        }

        try {
            VehicleDataMonitor vdm = VehicleDataMonitor.getInstance();
            BydVehicleData vd = vdm != null ? vdm.getVd() : null;
            if (vd != null && !Double.isNaN(vd.hvPackVoltage) && vd.hvPackVoltage > 200) {
                double voltage = vd.hvPackVoltage;
                double cellVoltage = 3.2;
                int cellCount = (int) Math.round(voltage / cellVoltage);
                double capacity = mapCellCountToCapacity(cellCount);
                if (capacity > 0) {
                    setNominalCapacityKwh(capacity);
                    logger.info("Pack Voltage Capacity: " + capacity + " kWh (voltage=" +
                        String.format("%.1f", voltage) + "V, nominal cellV=3.2V" +
                        ", cells≈" + cellCount + "s)");
                    return;
                }
            }
        } catch (Exception e) {
            logger.debug("Pack voltage capacity lookup failed: " + e.getMessage());
        }

        if (nominalCapacityKwh > 0 && contradictedBySocRatio(nominalCapacityKwh)) {
            logger.warn("Persisted nominal " + nominalCapacityKwh
                + " kWh contradicted by current SOC ratio — clearing for re-detection on next cycle");
            nominalCapacityKwh = 0;
            nominalSource = "unset";
            currentSoh = -1;
            persistEstimate();
        }

        logger.warn("Capacity detection failed" +
            (nominalCapacityKwh > 0 ? " — using previously saved capacity: " + nominalCapacityKwh + " kWh"
                                    : " — SOH estimation disabled until capacity is identified"));
    }

    /**
     * Look up the canonical nominal kWh for the user-selected vehicle model
     * from the bundled/cached manifest. 0 if no selection, no value, or
     * manifest unavailable. Delegated to ModelsApiHandler so the manifest
     * cache/precedence rules stay in one place.
     */
    private double readModelNominalFromManifest() {
        return com.overdrive.app.server.ModelsApiHandler.nominalKwhForSelectedModel();
    }

    private boolean contradictedBySocRatio(double bmsKwh) {
        try {
            VehicleDataMonitor vdm = VehicleDataMonitor.getInstance();
            if (vdm == null) return false;
            double remainKwh = vdm.getBatteryRemainPowerKwh();
            BatterySocData socData = vdm.getBatterySoc();
            if (socData == null) return false;
            double soc = socData.socPercent;
            if (remainKwh < 1.5 || soc < 10 || soc > 100) return false;
            double impliedKwh = remainKwh / (soc / 100.0);
            if (!fuelSignalsLookBev && Math.abs(remainKwh - soc) < 5.0) return false;
            double relativeDelta = Math.abs(impliedKwh - bmsKwh) / bmsKwh;
            if (relativeDelta > 0.25) {
                logger.warn("BMS exact-Ah result " + bmsKwh + " kWh contradicted by SOC ratio: "
                    + String.format("%.1f", impliedKwh) + " kWh (remain="
                    + String.format("%.1f", remainKwh) + ", SOC="
                    + String.format("%.0f", soc) + "%) — falling through to SOC heuristic");
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private double tryBmsExactCapacity(android.content.Context context) {
        Integer rawOrNull = readBatteryCapacityRaw(context);
        if (rawOrNull == null) return 0;
        int raw = rawOrNull;
        if (raw > 1000 && raw < 60000) {
            double kwh = raw / 1000.0;
            if (kwh >= MIN_PLAUSIBLE_KWH && kwh <= MAX_PLAUSIBLE_KWH) {
                logger.info("BMS Capacity (exact, 0.001 kWh): " + kwh + " kWh (raw=" + raw + ")");
                return kwh;
            }
            return 0;
        }
        if (raw > 0 && raw <= 1000) {
            double kwh = mapAhToKwh(raw);
            if (kwh >= MIN_PLAUSIBLE_KWH && kwh <= MAX_PLAUSIBLE_KWH) {
                logger.info("BMS Capacity (exact, Ah=" + raw + "): " + kwh + " kWh");
                return kwh;
            }
        }
        return 0;
    }

    private double tryBmsFuzzyCapacity(android.content.Context context) {
        Integer rawOrNull = readBatteryCapacityRaw(context);
        if (rawOrNull == null) return 0;
        int raw = rawOrNull;
        if (raw <= 0 || raw > 1000) return 0;
        if (mapAhToKwh(raw) > 0) return 0;

        int snappedAh = nearestKnownAh(raw, 3);
        if (snappedAh <= 0) return 0;
        double kwh = mapAhToKwh(snappedAh);
        if (kwh < MIN_PLAUSIBLE_KWH || kwh > MAX_PLAUSIBLE_KWH) return 0;

        logger.info("BMS Capacity (fuzzy): " + kwh + " kWh (raw Ah=" + raw
            + " → snapped to " + snappedAh + " Ah)");
        return kwh;
    }

    private Integer readBatteryCapacityRaw(android.content.Context context) {
        try {
            Class<?> cls = Class.forName("android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice");
            Object device = cls.getMethod("getInstance", android.content.Context.class).invoke(null, context);
            if (device == null) return null;
            Method getBatteryCapacity = cls.getMethod("getBatteryCapacity");
            Number capNum = (Number) getBatteryCapacity.invoke(device);
            if (capNum == null) return null;
            int raw = capNum.intValue();
            if (raw <= 0 || raw == 255 || raw == 254 || raw == 65534 || raw == 65535) {
                return null;
            }
            return raw;
        } catch (Exception e) {
            logger.debug("readBatteryCapacityRaw failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Drop the active trip's kwhStart/kwhEnd baseline. Both readings are taken
     * via getBatteryRemainPowerKwh, which is computed against currentSoh ×
     * nominalCapacityKwh — so any nominal/SOH change mid-trip leaves the two
     * endpoints in different unit systems. Wiping them forces the trip to
     * fall through to TripAnalyticsManager's SoC-delta estimate, which uses a
     * single (post-change) nominal × SOH for both ends.
     */
    private void invalidateActiveTripKwhBaseline(String reason) {
        try {
            com.overdrive.app.trips.TripAnalyticsManager mgr =
                com.overdrive.app.daemon.CameraDaemon.getTripAnalyticsManager();
            if (mgr == null || !mgr.isTripActive()) return;
            com.overdrive.app.trips.TripRecord active = mgr.getActiveTrip();
            if (active == null) return;
            if (active.kwhStart == 0 && active.kwhEnd == 0) return;
            logger.info("Invalidating active trip kWh baseline (" + reason + ") — "
                + "kwhStart=" + String.format("%.2f", active.kwhStart)
                + " kwhEnd=" + String.format("%.2f", active.kwhEnd));
            active.kwhStart = 0;
            active.kwhEnd = 0;
        } catch (Throwable t) {
            logger.debug("invalidateActiveTripKwhBaseline noop: " + t.getMessage());
        }
    }

    /**
     * Compute live SOH from one tick of BMS data WITHOUT side effects.
     * Used by both updateFromEnergy() and any read-only consumer.
     */
    public double computeLiveSoh(double remainKwh, double socPercent, double highCellVoltage) {
        if (nominalCapacityKwh <= 0) return -1;
        if (socPercent <= 0 || socPercent > 100) return -1;
        if (remainKwh <= 0) return -1;
        double scale = displayToAbsoluteSocScale(highCellVoltage);
        double absSoc = scaleDisplaySoc(socPercent, scale);
        double impliedTotalCap = remainKwh / (absSoc / 100.0);
        double rawSoh = (impliedTotalCap / nominalCapacityKwh) * 100.0;
        boolean saturated = (rawSoh < MIN_SOH || rawSoh > MAX_SOH);
        if (saturated) {
            saturationStreak++;
            if (saturationStreak == 1 || saturationStreak % SATURATION_WARN_PERIOD == 0) {
                logger.warn("SOH saturated at " + (rawSoh < MIN_SOH ? "60%" : "100%")
                    + " rail (raw=" + String.format("%.1f", rawSoh)
                    + "%, nominal=" + String.format("%.1f", nominalCapacityKwh) + " kWh"
                    + ", source=" + nominalSource + ", streak=" + saturationStreak
                    + (rawSoh > MAX_SOH
                        ? ") — BMS reads above nominal (impossible SOH>100%); nominal may be set too low"
                        : ") — likely wrong nominal capacity selected"));
            }
        } else {
            saturationStreak = 0;
        }
        if (rawSoh < MIN_SOH) return MIN_SOH;
        if (rawSoh > MAX_SOH) return MAX_SOH;
        return rawSoh;
    }

    /**
     * Seed an initial estimate immediately after capacity detection so the UI
     * isn't blank waiting for the first SocHistoryDatabase tick.
     */
    public void seedInitialEstimate() {
        if (hasEstimate()) return;
        if (nominalCapacityKwh <= 0) return;
        try {
            VehicleDataMonitor vdm = VehicleDataMonitor.getInstance();
            BatterySocData socData = vdm.getBatterySoc();
            BydVehicleData vd = vdm.getVd();
            // Try the energy-based seed first. Read RAW vd.remainKwh, never
            // getBatteryRemainPowerKwh() — the helper synthesizes from
            // currentSoh on PHEV / bad-BMS paths and would loop SOH back
            // into itself, locking the estimate at the seed value forever.
            // PHEV: skip the energy-based seed entirely — the raw getter can't be
            // trusted for SOH (half/stale/frame-ambiguous), so we seed at the
            // honest 100% default (tail below) and let only the independent
            // capacity-Ah / calibration anchors lower it. BEV seeds from energy.
            boolean isPhevForSeed = false;
            try {
                com.overdrive.app.byd.BydDataCollector col =
                    com.overdrive.app.byd.BydDataCollector.getInstance();
                if (col != null && col.isInitialized()) isPhevForSeed = col.isPhevPublic();
            } catch (Throwable ignored) {}

            boolean energySeedFired = false;
            if (!isPhevForSeed
                    && vd != null && !Double.isNaN(vd.remainKwh) && vd.remainKwh > 0
                    && socData != null
                    && socData.socPercent >= 10 && socData.socPercent <= 100) {
                double rawRemainKwh = vd.remainKwh;
                double impliedCap = rawRemainKwh / (socData.socPercent / 100.0);
                double ratio = impliedCap / nominalCapacityKwh;
                // Refuse junk BMS readings (ratio outside plausible band).
                if (ratio >= 0.5 && ratio <= 1.12) {
                    double highCellV = Double.isNaN(vd.highCellVoltage) ? Double.NaN : vd.highCellVoltage;
                    double soh = computeLiveSoh(rawRemainKwh, socData.socPercent, highCellV);
                    if (soh > 0) {
                        currentSoh = soh;
                        persistEstimate();
                        energySeedFired = true;
                        logger.info("Initial SOH seeded: " + String.format("%.1f", soh) + "%");
                    }
                }
            }

            // Always-persist tail. v15.6 had this and v17 lost it during the
            // SohEstimator rewrite — without it, vehicles whose first poll
            // can't satisfy the energy seed (PHEV firmware bug, SOC out of
            // range, junk BMS readings) never write a SOH line to disk and
            // /api/performance/soh stays empty forever. Falling back to a
            // 100% baseline is safe: the calibration anchor refines it as
            // soon as the user does a real charging session, and live SOH
            // updateFromEnergy() takes over once vd.remainKwh becomes
            // trustworthy (e.g. when getBatteryPowerHEV starts reporting).
            if (!hasEstimate()) {
                String why;
                if (socData == null) {
                    why = "no SOC data";
                } else if (socData.socPercent < 10) {
                    why = "SOC " + String.format("%.0f", socData.socPercent) + "% below seed threshold";
                } else if (socData.socPercent > 100) {
                    why = "SOC out of range";
                } else if (vd == null || Double.isNaN(vd.remainKwh) || vd.remainKwh <= 0) {
                    why = "no remainKwh from BMS";
                } else {
                    why = "energy reading rejected by ratio gate";
                }
                logger.info("Seeding SOH at 100% baseline (" + why + ") — nominal="
                    + String.format("%.2f", nominalCapacityKwh) + " kWh");
                currentSoh = 100.0;
                persistEstimate();
            }
        } catch (Exception e) {
            logger.debug("Initial SOH seed failed: " + e.getMessage());
        }
    }

    // ==================== LIFECYCLE ====================

    public void init() {
        // 1. User override from UnifiedConfigManager. Restore floor matches
        //    setNominalCapacityKwhFromUser: PHEV-aware. We can't always probe
        //    BydDataCollector here (init runs before drivetrain classification
        //    has settled), so we accept the wider PHEV floor at restore-time
        //    — the value already passed strict per-drivetrain validation when
        //    it was originally set, and a stale value would still need
        //    affirmative user action to persist.
        try {
            JSONObject vehicle = UnifiedConfigManager.getVehicle();
            double userKwh = vehicle.optDouble("nominalKwh", 0);
            if (userKwh >= MIN_PLAUSIBLE_KWH_PHEV && userKwh <= MAX_PLAUSIBLE_KWH) {
                nominalCapacityKwh = userKwh;
                nominalSource = "user";
                logger.info("Restored user nominal capacity: " + userKwh + " kWh");
            }
        } catch (Throwable t) {
            logger.debug("UnifiedConfig vehicle.nominalKwh read failed: " + t.getMessage());
        }

        // 2. Properties-file restore (auto-detected nominal + currentSoh + calibration).
        try {
            File sohFile = new File(SOH_FILE);
            if (!sohFile.exists()) return;

            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(sohFile)) {
                props.load(fis);
            }

            int persistedVersion = 0;
            try {
                persistedVersion = Integer.parseInt(props.getProperty(PROP_SCHEMA_VERSION, "0"));
            } catch (NumberFormatException ignored) {
                persistedVersion = 0;
            }

            // Migration tiers:
            //   < v2 → harsh migration: SOH semantics changed in v2, drop state.
            //   v2 → soft upgrade to v3: peak frame anchor is a NEW additive
            //        field, existing SOH/calibration semantics unchanged.
            //        Just fall through to the normal load path; persistEstimate
            //        will rewrite with schema_version=3 on the next update.
            if (persistedVersion < 2) {
                // Legacy file: preserve nominal so auto-detect doesn't restart cold,
                // but drop SOH state — its semantics may have shifted across versions.
                if (!"user".equals(nominalSource)) {
                    String capStr = props.getProperty(PROP_NOMINAL_CAPACITY);
                    if (capStr != null) {
                        try {
                            double savedCap = Double.parseDouble(capStr);
                            if (savedCap >= MIN_PLAUSIBLE_KWH && savedCap <= MAX_PLAUSIBLE_KWH) {
                                nominalCapacityKwh = savedCap;
                                // Legacy "user" sources are not authoritative — the
                                // source-of-truth for user overrides is now
                                // UnifiedConfigManager (read earlier). Downgrade to
                                // "auto" so a stray legacy "user" string can't pin
                                // a nominal that has no corresponding override.
                                String savedSrc = props.getProperty(PROP_NOMINAL_SOURCE);
                                nominalSource = (savedSrc != null && !savedSrc.isEmpty() && !"user".equals(savedSrc))
                                    ? savedSrc : "auto";
                            }
                        } catch (NumberFormatException ignored2) {}
                    }
                }
                currentSoh = -1;
                calibrationSoh = -1;
                calibrationTimestampMs = 0;
                liveHistory.clear();
                logger.info("SOH file migrated from legacy schema (v" + persistedVersion
                    + " → v" + CURRENT_SCHEMA_VERSION
                    + ") — currentSoh/calibration cleared, will re-seed from BMS data");
                // persistEstimate() short-circuits when every field is empty,
                // which would leave schema_version unwritten and re-trigger
                // migration every boot. Stamp the new schema unconditionally.
                writeSchemaStamp();
                return;
            }
            if (persistedVersion == 2 && CURRENT_SCHEMA_VERSION >= 3) {
                logger.info("SOH file soft-upgraded v2 → v" + CURRENT_SCHEMA_VERSION
                    + " (peak frame anchor added, existing state preserved)");
                // Fall through to the normal load path below.
            }

            String sohStr = props.getProperty(PROP_SOH_PERCENT);
            if (sohStr != null) {
                double persistedSoh = Double.parseDouble(sohStr);
                // Accept up to the old 110 rail (older builds persisted up to 110),
                // but re-cap to MAX_SOH (100) on restore so a value written under
                // the old >100% behaviour doesn't keep inflating the display.
                if (persistedSoh >= MIN_SOH && persistedSoh <= 110) {
                    currentSoh = Math.min(persistedSoh, MAX_SOH);
                    logger.info("Restored SOH: " + currentSoh + "%"
                        + (persistedSoh > MAX_SOH ? " (capped from " + persistedSoh + "%)" : ""));
                } else {
                    logger.info("Discarding persisted SOH " + persistedSoh + " — out of valid range " + MIN_SOH + "-110");
                    sohFile.delete();
                }
            }

            String calStr = props.getProperty(PROP_CALIBRATION_SOH);
            if (calStr != null) {
                double cal = Double.parseDouble(calStr);
                if (cal >= MIN_SOH && cal <= 110) {
                    calibrationSoh = Math.min(cal, MAX_SOH);
                }
            }
            String calTsStr = props.getProperty(PROP_CALIBRATION_TIMESTAMP);
            if (calTsStr != null) {
                try {
                    calibrationTimestampMs = Long.parseLong(calTsStr);
                } catch (NumberFormatException ignored) {}
            }

            String capAhStr = props.getProperty(PROP_CAPACITY_AH_SOH);
            if (capAhStr != null) {
                try {
                    double cah = Double.parseDouble(capAhStr);
                    if (cah >= MIN_SOH && cah <= 110) capacityAhSoh = Math.min(cah, MAX_SOH);
                } catch (NumberFormatException ignored) {}
            }
            String capAhTsStr = props.getProperty(PROP_CAPACITY_AH_TIMESTAMP);
            if (capAhTsStr != null) {
                try {
                    capacityAhTimestampMs = Long.parseLong(capAhTsStr);
                } catch (NumberFormatException ignored) {}
            }
            // Restore the latched-off flag so a firmware confirmed
            // not-coulomb-counting in a previous session doesn't re-trigger
            // the same detection cycle on every reboot.
            String capAhDisStr = props.getProperty(PROP_CAPACITY_AH_DISABLED);
            if ("true".equalsIgnoreCase(capAhDisStr)) {
                capacityAhDisabled = true;
                logger.info("Capacity-Ah anchor restored as disabled (persisted)");
            }

            String liveHistStr = props.getProperty(PROP_LIVE_HISTORY);
            if (liveHistStr != null && !liveHistStr.isEmpty()) {
                try {
                    String[] parts = liveHistStr.split(",");
                    for (String p : parts) {
                        String trimmed = p.trim();
                        if (trimmed.isEmpty()) continue;
                        // Re-cap on restore: a pre-cap (≤110) properties file could
                        // otherwise reintroduce >100 samples into the median.
                        double v = Double.parseDouble(trimmed);
                        if (v >= MIN_SOH && v <= 110) liveHistory.addLast(Math.min(v, MAX_SOH));
                    }
                    while (liveHistory.size() > LIVE_HISTORY_SIZE) {
                        liveHistory.pollFirst();
                    }
                } catch (Exception ignored) {
                    liveHistory.clear();
                }
            }

            // Peak frame anchor restore (v3+). Missing on v2 files — fields stay -1/0
            // and observePeakAtFullCharge will re-seed on the next SOC≥99% tick.
            String peakStr = props.getProperty(PROP_PEAK_REMAIN_KWH);
            if (peakStr != null) {
                try {
                    double peak = Double.parseDouble(peakStr);
                    if (peak > 0 && peak <= MAX_PLAUSIBLE_KWH) {
                        peakRemainKwhAtFull = peak;
                    }
                } catch (NumberFormatException ignored) {}
            }
            String peakSamplesStr = props.getProperty(PROP_PEAK_REMAIN_KWH_SAMPLES);
            if (peakSamplesStr != null) {
                try {
                    peakRemainKwhSamples = Math.min(
                        Integer.parseInt(peakSamplesStr), PEAK_REMAIN_KWH_REQUIRED_SAMPLES);
                } catch (NumberFormatException ignored) {}
            }
            String peakTsStr = props.getProperty(PROP_PEAK_REMAIN_KWH_TS);
            if (peakTsStr != null) {
                try {
                    peakRemainKwhTimestampMs = Long.parseLong(peakTsStr);
                } catch (NumberFormatException ignored) {}
            }
            String peakNotifiedStr = props.getProperty(PROP_PEAK_REMAIN_KWH_NOTIFIED);
            if ("true".equalsIgnoreCase(peakNotifiedStr)) {
                peakMismatchNotified = true;
            }
            if (peakRemainKwhAtFull > 0) {
                logger.info("Restored peak frame anchor: "
                    + String.format("%.2f", peakRemainKwhAtFull) + " kWh ("
                    + peakRemainKwhSamples + "/"
                    + PEAK_REMAIN_KWH_REQUIRED_SAMPLES + " samples)"
                    + (peakMismatchNotified ? " [mismatch already notified]" : ""));
            }

            if (!"user".equals(nominalSource)) {
                String capStr = props.getProperty(PROP_NOMINAL_CAPACITY);
                if (capStr != null) {
                    double savedCap = Double.parseDouble(capStr);
                    // Floor must match whatever gate ACCEPTED this value in the first place,
                    // or a legitimately-stored nominal is destroyed on the next boot. A
                    // user-entered PHEV pack (e.g. 12.9 kWh) passes the PHEV floor at entry
                    // (setNominalCapacityKwhFromUser) but was then discarded here by the
                    // stricter BEV floor — so the setting silently reverted on every restart
                    // and every capacity-derived number went wrong again. Use the PHEV floor
                    // for a persisted "user" value; keep the strict BEV floor for "auto",
                    // which is what actually guards against a bad auto-detect.
                    String savedSrcForFloor = props.getProperty(PROP_NOMINAL_SOURCE);
                    double restoreFloor = "user".equals(savedSrcForFloor)
                            ? MIN_PLAUSIBLE_KWH_PHEV : MIN_PLAUSIBLE_KWH;
                    if (savedCap >= restoreFloor && savedCap <= MAX_PLAUSIBLE_KWH) {
                        nominalCapacityKwh = savedCap;
                        String savedSrc = props.getProperty(PROP_NOMINAL_SOURCE);
                        nominalSource = (savedSrc != null && !savedSrc.isEmpty()) ? savedSrc : "auto";
                        logger.info("Restored nominal capacity: " + savedCap + " kWh (source="
                            + nominalSource + ")");
                    } else if (savedCap > 0) {
                        logger.warn("Discarding persisted nominal " + savedCap
                            + " kWh — outside plausible range");
                        if (currentSoh > 0) {
                            currentSoh = -1;
                        }
                    }
                }
            }

            if (currentSoh > 0) {
                logger.info("SOH init complete: " + currentSoh + "%");
            }
        } catch (Exception e) {
            logger.error("Failed to load SOH: " + e.getMessage());
        }
    }

    // ==================== UPDATES ====================

    /**
     * Live update from one tick of BMS data. Direct assignment — no EMA, no
     * gating beyond the formula's plausibility clamps. The caller decides
     * whether the conditions are right to feed an update; we just compute.
     *
     * `atRest` is preserved for ABI compatibility but ignored.
     */
    public void updateFromEnergy(double remainingKwh, double displaySocPercent,
                                 double highCellVoltage, boolean atRest) {
        synchronized (autoDetectLock) {
            double soh = computeLiveSoh(remainingKwh, displaySocPercent, highCellVoltage);
            if (soh <= 0) return;
            liveHistory.addLast(soh);
            while (liveHistory.size() > LIVE_HISTORY_SIZE) {
                liveHistory.pollFirst();
            }
            currentSoh = median(liveHistory);
            persistEstimate();
        }
    }

    private static double median(java.util.Collection<Double> values) {
        if (values.isEmpty()) return -1;
        java.util.ArrayList<Double> sorted = new java.util.ArrayList<>(values);
        java.util.Collections.sort(sorted);
        int n = sorted.size();
        if (n % 2 == 1) return sorted.get(n / 2);
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    public void updateFromCalibration(double energyEnteredBatteryKwh, double socDelta,
                                      double packTempCelsius, boolean isAcCharge) {
        updateFromCalibration(energyEnteredBatteryKwh, socDelta, packTempCelsius, isAcCharge, Double.NaN);
    }

    public void updateFromCalibration(double energyEnteredBatteryKwh, double socDelta,
                                      double packTempCelsius, boolean isAcCharge,
                                      double highCellVoltage) {
        synchronized (autoDetectLock) {
            if (nominalCapacityKwh <= 0) {
                logger.debug("Calibration rejected: nominal capacity not yet detected");
                return;
            }
            // DC charging is accepted now; cluster-displayed energy/SOC remains accurate enough for SOH math, the AC-only gate was over-cautious.
            if (packTempCelsius < 15.0 || packTempCelsius > 35.0) {
                logger.debug("Calibration rejected: Pack temperature (" +
                    String.format("%.1f", packTempCelsius) + "°C) outside optimal SOH window (15-35°C).");
                return;
            }
            if (socDelta < 25.0) {
                logger.debug("Calibration rejected: SOC delta " + String.format("%.1f", socDelta) +
                    "% < 25% minimum for LFP accuracy");
                return;
            }

            double scale = displayToAbsoluteSocScale(highCellVoltage);
            double absSocDelta = socDelta * scale;
            double actualCapacity = energyEnteredBatteryKwh / (absSocDelta / 100.0);
            double calibratedSoh = (actualCapacity / nominalCapacityKwh) * 100.0;

            // Reject genuinely-implausible calibrations; clamp a slightly-high one
            // to the 100% ceiling (a full charge can measure a touch above nominal).
            if (calibratedSoh < MIN_SOH || calibratedSoh > 130.0) {
                logger.warn("Calibration SOH out of range: " + String.format("%.1f", calibratedSoh) + "% — rejected");
                return;
            }
            if (calibratedSoh > MAX_SOH) calibratedSoh = MAX_SOH;

            // Anchor only — never blends into currentSoh.
            calibrationSoh = calibratedSoh;
            calibrationTimestampMs = System.currentTimeMillis();
            persistEstimate();

            logger.info("Calibration anchor: " + String.format("%.1f", calibratedSoh) + "% (temp=" +
                String.format("%.1f", packTempCelsius) + "°C, " +
                String.format("%.1f", energyEnteredBatteryKwh) + " kWh / " +
                String.format("%.1f", socDelta) + "% display delta)");
        }
    }

    public void updateFromCalibration(double energyEnteredBatteryKwh, double socDelta) {
        updateFromCalibration(energyEnteredBatteryKwh, socDelta, 25.0, true);
    }

    /**
     * PHEV-only capacity-Ah anchor. The BMS reports its current full-charge
     * Ah counter via {@code BYDAutoBodyworkDevice.getBatteryCapacity()} on
     * many firmwares; on PHEVs this is the most stable SOH read because
     * (a) the live remainKwh / SOC formula is noisy at PHEV pack scale (1
     * decimal place over a 9–18 kWh range), and (b) coulomb-count Ah is
     * computed continuously by the BMS regardless of SOC range. BEVs already
     * get reliable live SOH from their wider remainKwh range, so we deliberately
     * gate this source to PHEV — keeping a single source of truth on BEV.
     *
     * <p>Behaves like {@link #updateFromCalibration}: it's an <em>anchor</em>,
     * not a blender. Sets {@link #capacityAhSoh} for display (and as the
     * fall-back in {@code getStatus} when the live source is unavailable),
     * but never modifies {@link #currentSoh}. The live median window stays
     * the source of truth for the live readout.
     *
     * <p>Skips when the BMS returns the nameplate Ah for {@link #CAPACITY_AH_NAMEPLATE_TRIPS}
     * consecutive ticks — that signals a firmware that returns the static
     * factory rating, not a live coulomb count, and would otherwise pin the
     * anchor at 100% forever.
     *
     * @param bmsReportedAh BMS full-charge capacity in Ah (live coulomb count)
     * @param cellCount Series cell count for the pack (derived from voltage)
     * @param isPhev True when the drivetrain has been classified as PHEV.
     *               BEV calls early-return — keep BEV behavior unchanged.
     */
    public void updateFromCapacityAh(double bmsReportedAh, int cellCount, boolean isPhev,
                                     double currentSocPercent) {
        // grossNominalKwh<=0 → derive factory Ah from the nominalCapacityKwh field
        // (correct when that field is already gross, e.g. BEV / PHEV auto-detect).
        updateFromCapacityAh(bmsReportedAh, cellCount, isPhev, currentSocPercent, 0);
    }

    /**
     * As {@link #updateFromCapacityAh(double, int, boolean, double)} but with an
     * explicit gross-frame nameplate kWh for the factory-Ah derivation.
     *
     * <p>The BMS coulomb-count Ah ({@code bmsReportedAh}) is a PHYSICAL gross-frame
     * measurement (~71 Ah on an 18.3 kWh gross Blade pack). The factory Ah it is
     * compared against must therefore also be GROSS. The nominal field is gross on
     * every drivetrain now (PHEV remainKwh is corrected to gross at the HAL read
     * boundary), so {@code grossNominalKwh} and the nominal field agree; the param
     * is kept to let callers pass the explicit model nameplate, and {@code 0} falls
     * back to the nominal field.
     */
    public void updateFromCapacityAh(double bmsReportedAh, int cellCount, boolean isPhev,
                                     double currentSocPercent, double grossNominalKwh) {
        synchronized (autoDetectLock) {
            if (!isPhev) return;                 // BEV: live formula is enough.
            if (capacityAhDisabled) return;
            if (nominalCapacityKwh <= 0) return;
            if (bmsReportedAh <= 0 || cellCount <= 0) return;

            // Derive factory Ah first — needed by both the nameplate detector
            // and the SOC-coupling detector to interpret bmsReportedAh. Use the
            // gross-frame nameplate (see method contract) so the comparison
            // against the physical BMS Ah counter is frame-consistent.
            double grossKwh = (grossNominalKwh > 0) ? grossNominalKwh : nominalCapacityKwh;
            double nominalAh = (grossKwh * 1000.0)
                / (cellCount * BYD_BLADE_REFERENCE_CELL_VOLTAGE);
            if (nominalAh < 30 || nominalAh > 350) {
                logger.debug("Capacity-Ah anchor rejected: derived nominal "
                    + String.format("%.1f", nominalAh) + " Ah outside expected range");
                return;
            }

            // Stuck-at-nameplate detector. Runs BEFORE dedup so a firmware
            // that always returns the static factory rating advances the
            // counter on every tick instead of dedup-skipping after the
            // first one — without that ordering the source latched at the
            // nameplate value would emit 100% on tick 1 and never disable.
            if (Math.abs(bmsReportedAh - nominalAh) <= CAPACITY_AH_NAMEPLATE_TOLERANCE_AH) {
                capacityAhNameplateMatchCount++;
                if (capacityAhNameplateMatchCount >= CAPACITY_AH_NAMEPLATE_TRIPS) {
                    capacityAhDisabled = true;
                    persistEstimate();   // survive daemon restart
                    logger.warn("Capacity-Ah anchor disabled: BMS Ah ("
                        + String.format("%.1f", bmsReportedAh) + ") matches nameplate ("
                        + String.format("%.1f", nominalAh) + ") for "
                        + capacityAhNameplateMatchCount
                        + " consecutive ticks — firmware not coulomb-counting");
                }
                return;
            }
            capacityAhNameplateMatchCount = 0;

            // SOC-coupling detector: some PHEV firmwares return getBatteryCapacity()
            // as 0.1-kWh-remaining (walks with SOC) instead of full-charge Ah
            // (stays flat). Sample at first call; if subsequent ticks show the
            // Ah reading tracking SOC, latch the source off. We require the
            // SOC delta to be non-trivial (>5%) so a normal 1% jiggle doesn't
            // false-positive on a healthy coulomb counter.
            if (currentSocPercent > 0 && currentSocPercent <= 100) {
                if (capacityAhFirstSocSeen < 0) {
                    capacityAhFirstSocSeen = currentSocPercent;
                    capacityAhFirstAhSeen = bmsReportedAh;
                } else {
                    double socDelta = Math.abs(currentSocPercent - capacityAhFirstSocSeen);
                    if (socDelta > 5.0) {
                        double ahDelta = Math.abs(bmsReportedAh - capacityAhFirstAhSeen);
                        // Coulomb-count Ah varies <1% even after substantial
                        // SOC swings (it's the FULL-charge capacity, not
                        // remaining). If Δah > 5% of starting reading on a
                        // >5% SOC swing, it's tracking SOC.
                        if (ahDelta > capacityAhFirstAhSeen * 0.05) {
                            capacityAhSocCoupledCount++;
                            if (capacityAhSocCoupledCount >= CAPACITY_AH_SOC_COUPLED_TRIPS) {
                                capacityAhDisabled = true;
                                persistEstimate();   // survive daemon restart
                                logger.warn("Capacity-Ah anchor disabled: BMS reading"
                                    + " tracks SOC (firmware returns 0.1-kWh-remaining,"
                                    + " not coulomb-count Ah)");
                                return;
                            }
                        } else {
                            // Decoupled — reset the streak and re-anchor the
                            // baseline so a later coupling event has a fresh
                            // reference.
                            capacityAhSocCoupledCount = 0;
                            capacityAhFirstSocSeen = currentSocPercent;
                            capacityAhFirstAhSeen = bmsReportedAh;
                        }
                    }
                }
            }

            // Skip duplicate readings — log clutter and pointless rewrites.
            if (Math.abs(bmsReportedAh - lastCapacityAhReading) < 0.05) return;
            lastCapacityAhReading = bmsReportedAh;

            double soh = (bmsReportedAh / nominalAh) * 100.0;
            // Reject only genuinely-implausible reads (gross frame error). A
            // reading a little above nominal (BMS Ah slightly > factory rating, or
            // nominal entered a touch low) is clamped to the 100% ceiling, not
            // discarded — SOH cannot exceed 100%, but the pack is clearly healthy.
            if (soh < MIN_SOH || soh > 130.0) {
                logger.debug("Capacity-Ah anchor rejected: " + String.format("%.1f", soh)
                    + "% outside " + MIN_SOH + "-130 range (reported=" + String.format("%.1f", bmsReportedAh)
                    + " Ah, nominal=" + String.format("%.1f", nominalAh) + " Ah)");
                return;
            }
            if (soh > MAX_SOH) soh = MAX_SOH;

            capacityAhSoh = soh;
            capacityAhTimestampMs = System.currentTimeMillis();
            persistEstimate();

            logger.info("Capacity-Ah anchor: " + String.format("%.1f", soh)
                + "% (reported=" + String.format("%.1f", bmsReportedAh) + " Ah, nominal="
                + String.format("%.1f", nominalAh) + " Ah, " + cellCount + "s cells)");
        }
    }

    /**
     * PHEV-only peak-charge frame anchor. Track the maximum {@code remainKwh}
     * the BMS reports while SOC is at or above {@link #PEAK_REMAIN_KWH_FULL_SOC_THRESHOLD}.
     *
     * <p>Why: on Blade DM-i PHEVs the BMS reports {@code remainKwh} in the
     * usable frame (e.g. ~12.9 kWh full on a Tang DM-i with 21.5 kWh nameplate)
     * while users tend to enter the nameplate value as nominal. The live SOH
     * formula {@code remainKwh / (SOC/100) / nominal × 100} then computes
     * something like 12.9 / 21.5 × 100 ≈ 60% indistinguishable from a
     * genuinely-degraded battery. The peak observed at full charge is exactly
     * the BMS's view of "100% SOC" — comparing it to nominal lets us a) emit
     * a frame-aware SOH that doesn't mistake unit-mismatch for degradation,
     * and b) surface the mismatch in the UI so the user can correct nominal.
     *
     * <p>BEV calls early-return — BEV {@code remainKwh} is already in the
     * nominal frame and the live formula is sufficient.
     *
     * <p>Anchor stabilizes after {@link #PEAK_REMAIN_KWH_REQUIRED_SAMPLES}
     * observations to avoid pinning to a single tick of HAL noise. Each
     * higher-than-current observation replaces the anchor (true peak); equal
     * or lower observations only bump the sample counter.
     */
    public void observePeakAtFullCharge(double remainKwh, double socPercent, boolean isPhev) {
        synchronized (autoDetectLock) {
            if (!isPhev) return;
            if (nominalCapacityKwh <= 0) return;
            if (socPercent < PEAK_REMAIN_KWH_FULL_SOC_THRESHOLD) return;
            if (remainKwh <= 0 || remainKwh > MAX_PLAUSIBLE_KWH) return;
            // SOC-as-kWh PHEV firmware bug: HAL echoes SOC% (≈100) into the
            // remainKwh field at full charge. Reject when remainKwh is suspiciously
            // close to socPercent — that's the bug pattern, not a real reading.
            if (Math.abs(remainKwh - socPercent) < 3.0) return;

            boolean firstObservation = (peakRemainKwhAtFull <= 0);
            if (remainKwh > peakRemainKwhAtFull) {
                double prev = peakRemainKwhAtFull;
                peakRemainKwhAtFull = remainKwh;
                peakRemainKwhTimestampMs = System.currentTimeMillis();
                peakRemainKwhSamples = Math.min(peakRemainKwhSamples + 1, PEAK_REMAIN_KWH_REQUIRED_SAMPLES);
                if (firstObservation) {
                    double ratio = remainKwh / nominalCapacityKwh;
                    logger.info("Peak frame anchor seeded: " + String.format("%.2f", remainKwh)
                        + " kWh at " + String.format("%.0f", socPercent) + "% SOC (nominal="
                        + String.format("%.1f", nominalCapacityKwh) + " kWh, ratio="
                        + String.format("%.2f", ratio) + ")");
                    if (ratio < 0.85) {
                        logger.warn("Frame mismatch: peak observed kWh ("
                            + String.format("%.2f", remainKwh) + ") is "
                            + String.format("%.0f", ratio * 100)
                            + "% of user nominal (" + String.format("%.1f", nominalCapacityKwh)
                            + ") — user may have entered nameplate vs usable. SOH dialog will flag.");
                    }
                } else {
                    logger.debug("Peak frame anchor raised: " + String.format("%.2f", prev)
                        + " → " + String.format("%.2f", remainKwh) + " kWh");
                }
                persistEstimate();
            } else if (peakRemainKwhSamples < PEAK_REMAIN_KWH_REQUIRED_SAMPLES) {
                peakRemainKwhSamples++;
                if (peakRemainKwhSamples == PEAK_REMAIN_KWH_REQUIRED_SAMPLES) {
                    logger.info("Peak frame anchor stabilized at " + String.format("%.2f", peakRemainKwhAtFull)
                        + " kWh after " + peakRemainKwhSamples + " samples");
                    persistEstimate();
                }
            }

            // Notification gate. We only fire after the anchor has stabilized
            // AND the ratio is below threshold AND we haven't already notified
            // this anchor lifetime. The flag is wiped along with peak* fields
            // on every reset / nominal change, so re-detection re-notifies.
            maybeFireFrameMismatchNotification();
        }
    }

    /**
     * Publish the frame-mismatch notification once per anchor lifetime.
     * Called from observePeakAtFullCharge after every state mutation; the
     * persisted flag {@link #peakMismatchNotified} keeps it single-shot.
     */
    private void maybeFireFrameMismatchNotification() {
        if (peakMismatchNotified) return;
        if (peakRemainKwhSamples < PEAK_REMAIN_KWH_REQUIRED_SAMPLES) return;
        if (peakRemainKwhAtFull <= 0 || nominalCapacityKwh <= 0) return;
        double ratio = peakRemainKwhAtFull / nominalCapacityKwh;
        if (ratio >= 0.85) return;
        try {
            JSONObject data = new JSONObject();
            data.put("peakKwh", Math.round(peakRemainKwhAtFull * 100) / 100.0);
            data.put("nominalKwh", Math.round(nominalCapacityKwh * 10) / 10.0);
            data.put("ratio", Math.round(ratio * 100) / 100.0);
            String peakStr = String.format(java.util.Locale.US, "%.1f", peakRemainKwhAtFull);
            String nomStr = String.format(java.util.Locale.US, "%.1f", nominalCapacityKwh);
            String title = com.overdrive.app.server.Messages.get(
                "notifications.soh_frame_mismatch_title");
            String body = com.overdrive.app.server.Messages.get(
                "notifications.soh_frame_mismatch_body", peakStr, nomStr);
            com.overdrive.app.notifications.NotificationBus.get().publish(
                new com.overdrive.app.notifications.NotificationEvent(
                    "vehicle.health.soh.frame_mismatch",
                    com.overdrive.app.notifications.NotificationEvent.Severity.WARN,
                    title,
                    body,
                    "soh-frame-mismatch",
                    null,   // category default click URL
                    data));
            peakMismatchNotified = true;
            persistEstimate();
            logger.info("Published frame-mismatch notification (peak="
                + peakStr + " kWh, nominal=" + nomStr + " kWh, ratio="
                + String.format("%.2f", ratio) + ")");
        } catch (Throwable t) {
            // Bus failure must not break the SOH pipeline. Leave flag false
            // so a later observation retries.
            logger.debug("Frame-mismatch notification publish failed: " + t.getMessage());
        }
    }

    /**
     * SOH derived from the peak frame anchor. {@code -1} until the anchor has
     * stabilized at {@link #PEAK_REMAIN_KWH_REQUIRED_SAMPLES} samples; this
     * gate prevents a single noisy peak from displaying a misleading SOH
     * before enough confirming observations exist.
     */
    public double getFrameAnchorSoh() {
        if (peakRemainKwhAtFull <= 0 || nominalCapacityKwh <= 0) return -1;
        if (peakRemainKwhSamples < PEAK_REMAIN_KWH_REQUIRED_SAMPLES) return -1;
        double soh = (peakRemainKwhAtFull / nominalCapacityKwh) * 100.0;
        if (soh < MIN_SOH) return MIN_SOH;
        if (soh > MAX_SOH) return MAX_SOH;
        return soh;
    }

    // ==================== GETTERS ====================

    /** One immutable result from the canonical SOH priority chain. */
    public static final class ResolvedSoh {
        private final double percent;
        private final String source;
        private final double oemPercent;

        ResolvedSoh(double percent, String source, double oemPercent) {
            this.percent = percent;
            this.source = source;
            this.oemPercent = oemPercent;
        }

        public double getPercent() { return percent; }
        public String getSource() { return source; }
        public double getOemPercent() { return oemPercent; }
    }

    /**
     * Pure, unit-testable SOH resolver used by every public presentation.
     *
     * <p>The vehicle/BMS health index is authoritative on both BEV and PHEV. It is the
     * same slowly-changing battery-health value exposed to diagnostic tools and does not
     * depend on instantaneous SOC, remaining-energy quantisation, temperature, or the
     * configured nominal capacity. Capacity-derived estimates remain useful fallbacks on
     * vehicles that do not expose the OEM index, but must never override it.
     */
    static ResolvedSoh resolveDisplaySoh(boolean phev, double oemSoh, double frameSoh,
                                         double currentSoh, double calibrationSoh) {
        double validOem = isValidSohPercent(oemSoh) ? oemSoh : -1;
        if (validOem > 0) return new ResolvedSoh(validOem, "oem", validOem);
        if (phev && isValidSohPercent(frameSoh)) {
            return new ResolvedSoh(frameSoh, "frame_anchor", -1);
        }
        if (phev && isValidSohPercent(calibrationSoh)) {
            return new ResolvedSoh(calibrationSoh, "calibration", -1);
        }
        if (isValidSohPercent(currentSoh)) {
            return new ResolvedSoh(currentSoh, "live", -1);
        }
        if (isValidSohPercent(calibrationSoh)) {
            return new ResolvedSoh(calibrationSoh, "calibration", -1);
        }
        return new ResolvedSoh(-1, "unavailable", -1);
    }

    private static boolean isValidSohPercent(double value) {
        return !Double.isNaN(value) && value > 0 && value <= MAX_SOH;
    }

    public ResolvedSoh getResolvedSoh() {
        // Collector calls stay outside autoDetectLock: its own locks can call back into
        // estimator consumers, so nesting them would invite a cross-subsystem deadlock.
        boolean phev = false;
        try {
            com.overdrive.app.byd.BydDataCollector col =
                com.overdrive.app.byd.BydDataCollector.getInstance();
            if (col != null && col.isInitialized()) phev = col.isPhevPublic();
        } catch (Throwable ignored) {}
        double oemSoh = readOemSohPercent();

        double frameSoh, curSoh, calSoh;
        synchronized (autoDetectLock) {
            frameSoh = getFrameAnchorSohLocked();
            curSoh = currentSoh;
            calSoh = calibrationSoh;
        }
        return resolveDisplaySoh(phev, oemSoh, frameSoh, curSoh, calSoh);
    }

    /** Canonical SOH percent for UI, APIs, history, automations and integrations. */
    public double getDisplaySoh() { return getResolvedSoh().percent; }

    /**
     * The vehicle's OWN state-of-health index, as reported by the OEM
     * ({@code STATISTIC_BATTERY_HEALTHY_INDEX}) and surfaced on the snapshot as
     * {@code sohPercent}. Already a plain 0..100 percent — the collector validates the
     * range before publishing — so it needs no scaling, no nominal capacity, and no
     * usable-vs-gross frame assumption. It is authoritative for both BEV and PHEV;
     * capacity-derived values are fallbacks only when this index is unavailable.
     *
     * @return the OEM percent in (0,100], or -1 when the trim doesn't report it.
     */
    private double readOemSohPercent() {
        try {
            com.overdrive.app.byd.BydDataCollector col =
                com.overdrive.app.byd.BydDataCollector.getInstance();
            if (col == null || !col.isInitialized()) return -1;
            com.overdrive.app.byd.BydVehicleData vd = col.getData();
            if (vd == null) return -1;
            double soh = vd.sohPercent;
            // Reject the unset sentinel, junk, and the meaningless 0 — a real pack is
            // never 0% healthy, so 0 means "not reported" on this HAL.
            if (Double.isNaN(soh) || soh <= 0 || soh > 100) return -1;
            return soh;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /**
     * Lock-held variant of {@link #getFrameAnchorSoh()} for callers that have
     * already acquired {@code autoDetectLock} and need a consistent snapshot
     * with sibling fields. Identical formula — see that method for behavior.
     */
    private double getFrameAnchorSohLocked() {
        if (peakRemainKwhAtFull <= 0 || nominalCapacityKwh <= 0) return -1;
        if (peakRemainKwhSamples < PEAK_REMAIN_KWH_REQUIRED_SAMPLES) return -1;
        double soh = (peakRemainKwhAtFull / nominalCapacityKwh) * 100.0;
        if (soh < MIN_SOH) return MIN_SOH;
        if (soh > MAX_SOH) return MAX_SOH;
        return soh;
    }

    /** True when {@link #getDisplaySoh()} would return a real value. */
    public boolean hasDisplaySoh() { return getDisplaySoh() > 0; }

    public double getCalibrationSoh() { return calibrationSoh; }
    public long getCalibrationTimestampMs() { return calibrationTimestampMs; }
    public double getCapacityAhSoh() { return capacityAhSoh; }
    public long getCapacityAhTimestampMs() { return capacityAhTimestampMs; }
    public double getPeakRemainKwhAtFull() { return peakRemainKwhAtFull; }
    public long getPeakRemainKwhTimestampMs() { return peakRemainKwhTimestampMs; }
    public int getPeakRemainKwhSamples() { return peakRemainKwhSamples; }
    public boolean hasEstimate() { return currentSoh > 0; }

    public double getEstimatedCapacityKwh() {
        if (nominalCapacityKwh <= 0) return -1;
        // Use the DISPLAYED SOH (capped, anchored) so the estimated-capacity kWh
        // on the health card agrees with the SOH percent shown right beside it —
        // previously this used currentSoh (live median) while the percent used
        // getDisplaySoh, so the card could read "100% / 20.0 kWh of 21.5" (=93%).
        double displaySoh = getDisplaySoh();
        double s = displaySoh > 0 ? displaySoh : currentSoh;
        if (s <= 0) return -1;
        return (s / 100.0) * nominalCapacityKwh;
    }

    // ==================== RESET ====================

    public void reset() {
        synchronized (autoDetectLock) {
            double previous = nominalCapacityKwh;
            currentSoh = -1;
            calibrationSoh = -1;
            calibrationTimestampMs = 0;
            capacityAhSoh = -1;
            capacityAhTimestampMs = 0;
            lastCapacityAhReading = -1;
            capacityAhNameplateMatchCount = 0;
            capacityAhDisabled = false;
            capacityAhFirstSocSeen = -1;
            capacityAhFirstAhSeen = -1;
            capacityAhSocCoupledCount = 0;
            nominalCapacityKwh = 0;
            nominalSource = "unset";
            liveHistory.clear();
            saturationStreak = 0;
            // Peak frame anchor wiped on reset — see clearUserNominal note.
            peakRemainKwhAtFull = -1;
            peakRemainKwhSamples = 0;
            peakRemainKwhTimestampMs = 0;
            peakMismatchNotified = false;
            if (previous > 0) {
                invalidateActiveTripKwhBaseline("SohEstimator.reset()");
            }

            File sohFile = new File(SOH_FILE);
            if (sohFile.exists()) {
                sohFile.delete();
            }

            // Restore the user's persisted nominal from UnifiedConfig. The
            // properties file got wiped above, but UnifiedConfig holds the
            // user's manual override — autoDetectCarModel checks the
            // in-memory nominalSource field, so without re-reading here it
            // falls through to heuristics and produces a different (often
            // wrong) capacity even though the user explicitly set one.
            try {
                JSONObject vehicle = UnifiedConfigManager.getVehicle();
                double userKwh = vehicle.optDouble("nominalKwh", 0);
                // PHEV-aware floor on restore — see note in init().
                if (userKwh >= MIN_PLAUSIBLE_KWH_PHEV && userKwh <= MAX_PLAUSIBLE_KWH) {
                    nominalCapacityKwh = userKwh;
                    nominalSource = "user";
                    logger.info("SOH estimation RESET — local data cleared, user nominal " +
                        userKwh + " kWh restored from UnifiedConfig.");
                    return;
                }
            } catch (Throwable t) {
                logger.debug("Reset: UnifiedConfig user nominal read failed: " + t.getMessage());
            }
            logger.info("SOH estimation RESET — local data cleared (no user nominal set).");
        }
    }

    // ==================== STATUS ====================

    public org.json.JSONObject getStatus() {
        org.json.JSONObject status = new org.json.JSONObject();
        final ResolvedSoh resolvedSoh = getResolvedSoh();
        try {
            // `soh` is the legacy headline field, so keep it canonical too. The moving
            // capacity/SOC estimate remains available explicitly for diagnostics.
            status.put("soh", resolvedSoh.percent > 0
                ? Math.round(resolvedSoh.percent * 10) / 10.0 : -1);
            status.put("estimatedSoh", currentSoh > 0
                ? Math.round(currentSoh * 10) / 10.0 : -1);
            status.put("nominalCapacityKwh", nominalCapacityKwh);
            double estCap = getEstimatedCapacityKwh();
            status.put("estimatedCapacityKwh", estCap > 0 ? Math.round(estCap * 10) / 10.0 : -1);
            status.put("hasEstimate", resolvedSoh.percent > 0);
            status.put("nominalSource", nominalSource);

            org.json.JSONObject calibration = new org.json.JSONObject();
            calibration.put("soh", calibrationSoh > 0 ? Math.round(calibrationSoh * 10) / 10.0 : -1);
            calibration.put("timestampMs", calibrationTimestampMs);
            status.put("calibration", calibration);

            // Capacity-Ah anchor — RETIRED as a SOH source (getBatteryCapacity is
            // a static nameplate on DM-i, not a coulomb count). Key kept in the
            // response for shape stability, but soh is always -1 / disabled=true so
            // no consumer or debugger sees a stale persisted anchor value (e.g. a
            // 76% left on disk from before retirement) next to a 100% headline.
            org.json.JSONObject capAh = new org.json.JSONObject();
            capAh.put("soh", -1);
            capAh.put("timestampMs", 0);
            capAh.put("disabled", true);
            status.put("capacityAh", capAh);

            // Frame anchor (PHEV-only). Empirically captures the BMS's view
            // of "100% SOC" by tracking peak remainKwh observed at SOC≥99%.
            // ratio = peakRemainKwhAtFull / nominalCapacityKwh tells us
            // whether the user's nominal entry matches the BMS's frame
            // (~1.0 = match) or differs (~0.6 on Tang DM-i nameplate vs usable).
            org.json.JSONObject frameAnchor = new org.json.JSONObject();
            double frameSoh = getFrameAnchorSoh();
            frameAnchor.put("soh", frameSoh > 0 ? Math.round(frameSoh * 10) / 10.0 : -1);
            frameAnchor.put("peakKwh", peakRemainKwhAtFull > 0
                ? Math.round(peakRemainKwhAtFull * 100) / 100.0 : -1);
            frameAnchor.put("samples", peakRemainKwhSamples);
            frameAnchor.put("requiredSamples", PEAK_REMAIN_KWH_REQUIRED_SAMPLES);
            frameAnchor.put("timestampMs", peakRemainKwhTimestampMs);
            double frameRatio = (peakRemainKwhAtFull > 0 && nominalCapacityKwh > 0)
                ? peakRemainKwhAtFull / nominalCapacityKwh : -1;
            frameAnchor.put("ratio", frameRatio > 0 ? Math.round(frameRatio * 100) / 100.0 : -1);
            // Frame mismatch threshold: < 0.85 means user nominal is ~17%+
            // larger than BMS's full-charge reading. The most common cause is
            // user-entered nameplate (e.g. 21.5 kWh Tang DM-i) vs BMS-reported
            // usable (~12.9 kWh). Set when the anchor has stabilized.
            frameAnchor.put("mismatch",
                frameRatio > 0 && frameRatio < 0.85
                    && peakRemainKwhSamples >= PEAK_REMAIN_KWH_REQUIRED_SAMPLES);
            status.put("frameAnchor", frameAnchor);

            // The detail API consumes the exact same resolver as every headline surface.
            status.put("displaySoh", resolvedSoh.percent > 0
                ? Math.round(resolvedSoh.percent * 10) / 10.0 : -1);
            status.put("displaySource", resolvedSoh.source);
            status.put("oemSoh", resolvedSoh.oemPercent > 0
                ? Math.round(resolvedSoh.oemPercent * 10) / 10.0 : -1);

            // Read fresh — model can change without touching SOH state.
            try {
                String modelId = UnifiedConfigManager.getSelectedVehicleModelId();
                if (modelId != null && !modelId.isEmpty()) {
                    status.put("modelId", modelId);
                } else {
                    status.put("modelId", JSONObject.NULL);
                }
            } catch (Throwable t) {
                status.put("modelId", JSONObject.NULL);
            }

            File sohFile = new File(SOH_FILE);
            if (sohFile.exists()) {
                Properties props = new Properties();
                try (FileInputStream fis = new FileInputStream(sohFile)) {
                    props.load(fis);
                }
                String lastUpdated = props.getProperty(PROP_LAST_UPDATED);
                if (lastUpdated != null) {
                    status.put("lastUpdated", Long.parseLong(lastUpdated));
                }
            }
        } catch (Exception e) {
            logger.error("Failed to build SOH status: " + e.getMessage());
        }
        return status;
    }

    // ==================== PERSISTENCE ====================

    /**
     * Write a minimal properties file with just schema_version + last_updated.
     * Used by the migration path so the schema bump persists even when no
     * SOH/calibration/nominal data survived the migration.
     */
    private void writeSchemaStamp() {
        try {
            Properties props = new Properties();
            File f = new File(SOH_FILE);
            if (f.exists()) {
                try (FileInputStream fis = new FileInputStream(f)) { props.load(fis); }
            }
            props.setProperty(PROP_SCHEMA_VERSION, String.valueOf(CURRENT_SCHEMA_VERSION));
            props.setProperty(PROP_LAST_UPDATED, String.valueOf(System.currentTimeMillis()));
            // Drop legacy keys that no longer have meaning under v2 — currentSoh
            // and calibration were already cleared in memory and persistEstimate
            // would simply omit them on its next call, but on this path we may
            // never get to a normal persistEstimate before another reboot.
            props.remove(PROP_SOH_PERCENT);
            props.remove(PROP_CALIBRATION_SOH);
            props.remove(PROP_CALIBRATION_TIMESTAMP);
            props.remove(PROP_CAPACITY_AH_SOH);
            props.remove(PROP_CAPACITY_AH_TIMESTAMP);
            props.remove(PROP_CAPACITY_AH_DISABLED);
            props.remove(PROP_LIVE_HISTORY);
            if (nominalCapacityKwh > 0) {
                props.setProperty(PROP_NOMINAL_CAPACITY, String.valueOf(nominalCapacityKwh));
                props.setProperty(PROP_NOMINAL_SOURCE, nominalSource);
            }
            try (FileOutputStream fos = new FileOutputStream(SOH_FILE)) {
                props.store(fos, "ABRP SOH Estimate");
            }
            new File(SOH_FILE).setReadable(true, false);
        } catch (Exception e) {
            logger.error("Failed to stamp schema version: " + e.getMessage());
        }
    }

    private void persistEstimate() {
        synchronized (autoDetectLock) {
            if (currentSoh <= 0 && nominalCapacityKwh <= 0
                    && calibrationSoh <= 0 && calibrationTimestampMs <= 0
                    && capacityAhSoh <= 0 && capacityAhTimestampMs <= 0
                    && !capacityAhDisabled
                    && peakRemainKwhAtFull <= 0 && peakRemainKwhSamples == 0
                    && !peakMismatchNotified) {
                return;
            }
            try {
                Properties props = new Properties();
                props.setProperty(PROP_SCHEMA_VERSION, String.valueOf(CURRENT_SCHEMA_VERSION));
                if (currentSoh > 0 && currentSoh <= 110) {
                    props.setProperty(PROP_SOH_PERCENT, String.valueOf(currentSoh));
                }
                props.setProperty(PROP_LAST_UPDATED, String.valueOf(System.currentTimeMillis()));
                if (nominalCapacityKwh > 0) {
                    props.setProperty(PROP_NOMINAL_CAPACITY, String.valueOf(nominalCapacityKwh));
                    props.setProperty(PROP_NOMINAL_SOURCE, nominalSource);
                }
                if (calibrationSoh > 0) {
                    props.setProperty(PROP_CALIBRATION_SOH, String.valueOf(calibrationSoh));
                }
                if (calibrationTimestampMs > 0) {
                    props.setProperty(PROP_CALIBRATION_TIMESTAMP, String.valueOf(calibrationTimestampMs));
                }
                if (capacityAhSoh > 0) {
                    props.setProperty(PROP_CAPACITY_AH_SOH, String.valueOf(capacityAhSoh));
                }
                if (capacityAhTimestampMs > 0) {
                    props.setProperty(PROP_CAPACITY_AH_TIMESTAMP, String.valueOf(capacityAhTimestampMs));
                }
                if (capacityAhDisabled) {
                    props.setProperty(PROP_CAPACITY_AH_DISABLED, "true");
                }
                if (!liveHistory.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    boolean first = true;
                    for (Double v : liveHistory) {
                        if (!first) sb.append(',');
                        sb.append(v);
                        first = false;
                    }
                    props.setProperty(PROP_LIVE_HISTORY, sb.toString());
                }
                if (peakRemainKwhAtFull > 0) {
                    props.setProperty(PROP_PEAK_REMAIN_KWH, String.valueOf(peakRemainKwhAtFull));
                    props.setProperty(PROP_PEAK_REMAIN_KWH_SAMPLES, String.valueOf(peakRemainKwhSamples));
                    props.setProperty(PROP_PEAK_REMAIN_KWH_TS, String.valueOf(peakRemainKwhTimestampMs));
                }
                if (peakMismatchNotified) {
                    props.setProperty(PROP_PEAK_REMAIN_KWH_NOTIFIED, "true");
                }

                try (FileOutputStream fos = new FileOutputStream(SOH_FILE)) {
                    props.store(fos, "ABRP SOH Estimate");
                }
                new File(SOH_FILE).setReadable(true, false);
            } catch (Exception e) {
                logger.error("Failed to persist SOH: " + e.getMessage());
            }
        }
    }

    // ==================== CHEMISTRY-AWARE SCALES ====================

    private static double displayToAbsoluteSocScale(double highCellVoltage) {
        if (!Double.isNaN(highCellVoltage) && highCellVoltage >= 3.75) {
            return 0.95;
        }
        return 1.0;
    }

    private static double scaleDisplaySoc(double displaySoc, double scale) {
        if (scale >= 0.999) return displaySoc;
        return displaySoc * scale + (1.0 - scale) / 2.0 * 100.0;
    }

    // ==================== MAPPINGS ====================

    private static int nearestKnownAh(int rawAh, int toleranceAh) {
        int[] knownAh = {50, 56, 72, 75, 79, 80, 100, 110, 120, 135, 140,
                         150, 153, 157, 166, 170, 176, 180, 200};
        int best = 0;
        int bestDiff = Integer.MAX_VALUE;
        for (int i = 0; i < knownAh.length; i++) {
            int k = knownAh[i];
            int d = Math.abs(rawAh - k);

            int leftGap = (i == 0) ? Integer.MAX_VALUE : (k - knownAh[i - 1]);
            int rightGap = (i == knownAh.length - 1)
                ? Integer.MAX_VALUE
                : (knownAh[i + 1] - k);
            int halfGap = Math.min(leftGap, rightGap) / 2;
            int safeTolerance = Math.min(toleranceAh, halfGap);

            if (d <= safeTolerance && d < bestDiff) {
                bestDiff = d;
                best = k;
            }
        }
        return best;
    }

    private static double mapAhToKwh(int ah) {
        switch (ah) {
            case 150: return 60.48;
            case 153: return 82.56;
            case 157: return 61.44;
            case 140: return 71.8;
            case 170: return 87.0;
            case 166: return 85.44;
            case 120: return 44.9;
            case 135: return 60.48;
            case 100: return 38.0;
            case 80:  return 30.08;
            case 200: return 108.8;
            case 176: return 56.4;
            case 180: return 91.3;
            case 110: return 43.2;
            case 50:  return 18.3;
            case 56:  return 18.3;
            case 72:  return 26.6;
            case 75:  return 26.6;
            case 79:  return 26.6;
            default:  return 0;
        }
    }

    // E6 (71.7 kWh) intentionally omitted: legacy taxi model, virtually
    // indistinguishable from Seal U (71.8 kWh).
    private static final double[] KNOWN_PACK_KWH = {
        18.3, 26.6, 30.08, 38.0, 43.2, 44.9, 56.4,
        60.48, 61.44, 71.8, 82.56, 85.44, 87.0, 91.3, 108.8
    };

    private static double matchNearestCapacity(double estimated) {
        return matchNearestCapacity(estimated, Double.NaN, Double.NaN);
    }

    private static double matchNearestCapacity(double estimated,
                                               double packVoltage,
                                               double socPercent) {
        double bestMatch = 0;
        double bestDiff = Double.MAX_VALUE;
        for (double k : KNOWN_PACK_KWH) {
            double diff = Math.abs(estimated - k);
            double tolerance = (k < 40 ? 0.20 : 0.10) * k;
            if (diff > tolerance) continue;
            if (!packVoltagePlausibleForPack(k, packVoltage, socPercent)) continue;
            if (diff < bestDiff) {
                bestDiff = diff;
                bestMatch = k;
            }
        }
        return bestMatch;
    }

    private static boolean packVoltagePlausibleForPack(double kwh,
                                                       double packVoltage,
                                                       double socPercent) {
        if (Double.isNaN(packVoltage) || packVoltage < 200) return true;
        if (Double.isNaN(socPercent) || socPercent < 5 || socPercent > 100) return true;
        int cellCount = cellCountForCapacity(kwh);
        if (cellCount <= 0) return true;
        double impliedCellV = packVoltage / cellCount;
        double minV = lfpMinCellVoltageAt(socPercent);
        double maxV = lfpMaxCellVoltageAt(socPercent);
        return impliedCellV >= minV && impliedCellV <= maxV;
    }

    private static double lfpMinCellVoltageAt(double socPercent) {
        if (socPercent >= 95) return 3.28;
        if (socPercent >= 80) return 3.18;
        if (socPercent >= 50) return 3.10;
        if (socPercent >= 30) return 3.00;
        if (socPercent >= 15) return 2.85;
        if (socPercent >= 5)  return 2.70;
        return 2.50;
    }

    private static double lfpMaxCellVoltageAt(double socPercent) {
        if (socPercent >= 95) return 3.55;
        if (socPercent >= 80) return 3.40;
        if (socPercent >= 50) return 3.30;
        if (socPercent >= 30) return 3.22;
        if (socPercent >= 15) return 3.18;
        if (socPercent >= 5)  return 3.10;
        return 3.00;
    }

    public static int cellCountForCapacity(double nominalKwh) {
        if (matches(nominalKwh, 60.48) || matches(nominalKwh, 60.4))  return 126;
        if (matches(nominalKwh, 61.44))                                return 128;
        if (matches(nominalKwh, 82.56) || matches(nominalKwh, 82.5))   return 172;
        if (matches(nominalKwh, 71.8))                                 return 138;
        if (matches(nominalKwh, 87.0))                                 return 166;
        if (matches(nominalKwh, 85.44))                                return 156;
        if (matches(nominalKwh, 91.3))                                 return 170;
        if (matches(nominalKwh, 108.8))                                return 192;
        if (matches(nominalKwh, 44.9))                                 return 104;
        if (matches(nominalKwh, 30.08))                                return 96;
        if (matches(nominalKwh, 38.0))                                 return 100;
        if (matches(nominalKwh, 43.2))                                 return 96;
        if (matches(nominalKwh, 56.4))                                 return 116;
        if (matches(nominalKwh, 18.3))                                 return 80;
        if (matches(nominalKwh, 26.6))                                 return 84;
        return 0;
    }

    private static boolean matches(double a, double b) {
        return Math.abs(a - b) < 0.5;
    }

    private static double mapCellCountToCapacity(int cellCount) {
        if (cellCount >= 82 && cellCount <= 86)   return 26.6;
        if (cellCount >= 94 && cellCount <= 98)   return 30.08;
        if (cellCount >= 102 && cellCount <= 106) return 44.9;
        if (cellCount >= 114 && cellCount <= 118) return 56.4;
        if (cellCount >= 136 && cellCount <= 140) return 71.8;
        if (cellCount >= 154 && cellCount <= 158) return 85.44;
        if (cellCount >= 164 && cellCount <= 168) return 87.0;
        if (cellCount >= 190 && cellCount <= 194) return 108.8;
        return 0;
    }

    // Package-visible (not private) purely so SohModelCapacityTest can pin the branch
    // ORDERING: the DM-i branches must precede their BEV namesakes, and adding one must not
    // change any BEV outcome. That is an ordering invariant no caller can express.
    static double mapCarTypeToCapacity(String carType) {
        String ct = carType.toUpperCase();
        // PHEV / DM-i marketing strings FIRST — otherwise "Seal U DM-i" falls into
        // the BEV "SEAL U" branch (71.8) and "Destroyer 05" matches nothing. These
        // return the GROSS nameplate (18.3), which is exactly what the SOH formula
        // needs: PHEV remainKwh is corrected to the gross frame at the HAL read
        // boundary (BydDataCollector.PHEV_ENERGY_HALF_SCALE_CORRECTION), so a
        // healthy pack reads ~100% against gross with no frame-mismatch nudge.
        // Keeps PHEV auto-detect in the right pack class instead of a wildly-wrong
        // BEV capacity.
        boolean isDmiString = ct.contains("DM-I") || ct.contains("DMI") || ct.contains("DM-P");
        if (ct.contains("DESTROYER")) return 18.3;
        if (isDmiString && (ct.contains("SEAL U") || ct.contains("SEALU") || ct.contains("SEAL-U"))) return 18.3;
        // TANG DM-i — MUST precede the bare "TANG" BEV branch below. Without this a Tang
        // DM-i was auto-detected as the 108.8 kWh BEV pack: a ~5x nominal overstatement
        // that poisons every capacity-derived number (SOH, remaining kWh, trip energy,
        // range). 21.5 kWh gross is the DM-i pack; the SOH formula wants gross because
        // PHEV remainKwh is corrected to the gross frame at the HAL boundary.
        if (isDmiString && ct.contains("TANG")) return 21.5;
        // Song / Qin / Frigate DM-i share the ~18.3 kWh DM-i pack class. Listed before the
        // BEV name branches for the same reason as above; a DM-i string must never fall
        // through to a BEV capacity.
        if (isDmiString && (ct.contains("SONG") || ct.contains("QIN") || ct.contains("FRIGATE"))) return 18.3;
        // Han DM-i / DM-p — MUST precede the bare "HAN" branch for the same reason the Tang
        // branch exists: without it a Han PHEV is auto-detected as the 85.44 kWh BEV pack, a
        // ~4.7x overstatement that poisons SOH, remaining kWh, trip energy and range.
        //
        // Returns 0 = "not detected" rather than a number, deliberately. Unlike Tang/Song/Qin
        // (one pack class each) the Han PHEV pack varies far too widely across trims and model
        // years to name a single value, and a confidently-wrong constant here would be
        // indistinguishable from a real detection to every downstream consumer. 0 falls the
        // caller through to the measurement-based tiers (BMS fuzzy capacity, then pack
        // voltage), and failing those it logs "SOH estimation disabled until capacity is
        // identified" — an honest unknown. A user who knows their trim can still pin it exactly
        // via the user-nominal override.
        if (isDmiString && ct.contains("HAN")) return 0;
        if (ct.contains("SEALION 6") || ct.contains("SEALION6") || ct.contains("SEA LION 6")) return 26.6;
        if (ct.contains("SEALION") || ct.contains("SEA LION")) return 91.3;
        if (ct.contains("SEAL U") || ct.contains("SEALU") || ct.contains("SEAL-U") || ct.contains("S7")) return 71.8;
        if (ct.contains("SEAL")) return 82.56;
        // Han BEV. The bare `|| ct.contains("DM-P")` that used to be on this line is gone: it
        // assigned an 85 kWh BEV pack to ANY DM-p (i.e. PHEV) model that reached here, which is
        // the precise bug the branches above fix — a PHEV inheriting a BEV capacity. Named DM-p
        // models are handled above; an unnamed one now falls through to 0 (not detected).
        if (ct.contains("HAN")) return 85.44;
        if (ct.contains("TANG")) return 108.8;
        if (ct.contains("ATTO 3") || ct.contains("ATTO3") || ct.contains("YUAN PLUS")) return 60.48;
        if (ct.contains("ATTO 2") || ct.contains("ATTO2")) return 44.9;
        if (ct.contains("ATTO 1") || ct.contains("ATTO1")) return 30.08;
        if (ct.contains("YUAN PRO")) return 38.0;
        if (ct.contains("YUAN")) return 60.48;
        if (ct.contains("DOLPHIN MINI") || ct.contains("SEAGULL")) return 38.0;
        if (ct.contains("DOLPHIN")) return 44.9;
        if (ct.contains("SONG")) return 71.8;
        if (ct.contains("QIN")) return 56.4;
        return 0;
    }
}
